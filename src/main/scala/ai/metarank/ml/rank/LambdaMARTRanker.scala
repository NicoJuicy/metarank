package ai.metarank.ml.rank

import ai.metarank.config.BoosterConfig.{LightGBMConfig, XGBoostConfig}
import ai.metarank.config.Selector.AcceptSelector
import ai.metarank.config.{BoosterConfig, ModelConfig, Selector}
import ai.metarank.flow.ClickthroughQuery
import ai.metarank.main.command.Train.info
import ai.metarank.main.command.train.SplitStrategy
import ai.metarank.main.command.train.SplitStrategy.Split
import ai.metarank.ml.Model.{ItemScore, RankModel, Response}
import ai.metarank.ml.Predictor.RankPredictor
import ai.metarank.ml.{Model, Predictor}
import ai.metarank.model.FeatureWeight.{SingularWeight, VectorWeight}
import ai.metarank.model.{ClickthroughValues, FeatureWeight, QueryMetadata}
import ai.metarank.model.Key.FeatureName
import ai.metarank.util.Logging
import cats.data.NonEmptyList
import cats.effect.{IO, ParallelF}
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.deriveEncoder
import io.github.metarank.ltrlib.booster.{Booster, LightGBMBooster, LightGBMOptions, XGBoostBooster, XGBoostOptions}
import io.github.metarank.ltrlib.model.{DatasetDescriptor, Feature}
import io.github.metarank.ltrlib.ranking.pairwise.LambdaMART

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import scala.util.{Failure, Success, Try}

object LambdaMARTRanker {

  import ai.metarank.util.DurationJson._

  case class LambdaMARTConfig(
      backend: BoosterConfig,
      features: NonEmptyList[FeatureName],
      weights: Map[String, Double],
      selector: Selector = AcceptSelector(),
      split: SplitStrategy = SplitStrategy.default
  ) extends ModelConfig

  val BITSTREAM_VERSION = 1

  case class LambdaMARTPredictor(name: String, config: LambdaMARTConfig, desc: DatasetDescriptor)
      extends RankPredictor[LambdaMARTConfig, LambdaMARTModel]
      with Logging {
    override def fit(data: fs2.Stream[IO, ClickthroughValues]): IO[LambdaMARTModel] = for {
      clickthroughs <- loadDataset(data)
      split         <- splitDataset(config.split, desc, clickthroughs)
    } yield {
      val booster = config.backend match {
        case LightGBMConfig(it, lr, ndcg, depth, seed, leaves, sampling) =>
          val opts = LightGBMOptions(
            trees = it,
            numLeaves = leaves,
            randomSeed = seed,
            learningRate = lr,
            ndcgCutoff = ndcg,
            maxDepth = depth,
            featureFraction = sampling
          )
          LambdaMART(split.train, opts, LightGBMBooster, Some(split.test))
        case XGBoostConfig(it, lr, ndcg, depth, seed, sampling) =>
          val opts = XGBoostOptions(
            trees = it,
            randomSeed = seed,
            learningRate = lr,
            ndcgCutoff = ndcg,
            maxDepth = depth,
            subsample = sampling
          )
          LambdaMART(split.train, opts, XGBoostBooster, Some(split.test))
      }
      val result = booster.fit()
      LambdaMARTModel(name, config, result.model)
    }

    override def load(bytes: Option[Array[Byte]]): Either[Throwable, LambdaMARTModel] = bytes match {
      case None => Left(new Exception(s"cannot load model: not found, maybe you forgot to run train?"))
      case Some(blob) =>
        val stream = new DataInputStream(new ByteArrayInputStream(blob))
        Try(stream.readByte()) match {
          case Success(BITSTREAM_VERSION) =>
            val boosterType = stream.readByte()
            val size        = stream.readInt()
            val buf         = new Array[Byte](size)
            stream.read(buf)
            boosterType match {
              case 0     => Right(LambdaMARTModel(name, config, LightGBMBooster(buf)))
              case 1     => Right(LambdaMARTModel(name, config, XGBoostBooster(buf)))
              case other => Left(new Exception(s"unsupported booster tag $other"))
            }
          case Success(other) => Left(new Exception(s"unsupported bitstream version $other, please re-run train"))
          case Failure(ex)    => Left(ex)
        }
    }

    def splitDataset(splitter: SplitStrategy, desc: DatasetDescriptor, clickthroughs: List[QueryMetadata]): IO[Split] =
      for {
        split <- splitter.split(desc, clickthroughs)
        _ <- split match {
          case Split(train, _) if train.groups.isEmpty =>
            IO.raiseError(
              new Exception(s"Train dataset is empty (with ${clickthroughs.size} total click-through events)")
            )
          case Split(_, test) if test.groups.isEmpty =>
            IO.raiseError(
              new Exception(s"Test dataset is empty (with ${clickthroughs.size} total click-through events)")
            )
          case Split(train, test) if (train.groups.size < 10) || (test.groups.size < 10) =>
            IO.raiseError(
              new Exception(s"""Train/test datasets are too small: train=${train.groups.size}, test=${test.groups.size}.
                               |It is not possible to train the ML model on such a small dataset.""".stripMargin)
            )
          case Split(train, test) =>
            info(s"Train/Test split finished: ${train.groups.size}/${test.groups.size} click-through events")
        }

      } yield {
        split
      }

    def loadDataset(data: fs2.Stream[IO, ClickthroughValues]) = for {
      clickthroughs <- data
        .filter(_.ct.interactions.nonEmpty)
        .filter(ct => config.selector.accept(ct.ct))
        .map(ct =>
          QueryMetadata(
            query = ClickthroughQuery(ct.values, ct.ct.interactions, ct, config.weights, desc),
            ts = ct.ct.ts,
            user = ct.ct.user
          )
        )
        .compile
        .toList

      _ <- info(s"loaded ${clickthroughs.size} clickthroughs")
      _ <- IO.whenA(clickthroughs.isEmpty)(
        IO.raiseError(
          new Exception(
            """
              |Cannot train model on an empty dataset. Maybe you forgot to do 'metarank import'?
              |Possible options:
              |1. Dataset in-consistency. To check for consistency issues, run 'metarank validate --config conf.yml --data data.jsonl.gz',
              |2. You've used an in-memory persistence for import, and after restart the data was lost. Maybe try setting 'state.type=redis'?
              |""".stripMargin
          )
        )
      )
    } yield {
      clickthroughs
    }
  }

  case class LambdaMARTModel(name: String, conf: LambdaMARTConfig, booster: Booster[_]) extends RankModel with Logging {
    override def predict(request: QueryRequest): IO[Model.Response] = {
      IO(booster.predictMat(request.query.values, request.query.rows, request.query.columns).toList).flatMap {
        case head :: tail =>
          for {
            items <- IO(request.items.zip(NonEmptyList.of(head, tail: _*)).map { case (item, score) =>
              ItemScore(item.id, score)
            })
          } yield {
            Response(items)
          }
        case _ => IO.raiseError(new Exception("booster.predictMat returned empty array"))
      }
    }

    override def save(): Option[Array[Byte]] = {
      val buf    = new ByteArrayOutputStream()
      val stream = new DataOutputStream(buf)
      stream.writeByte(BITSTREAM_VERSION)
      val bytes = booster.save()
      booster match {
        case _: LightGBMBooster =>
          stream.writeByte(0)
          stream.writeInt(bytes.length)
          stream.write(bytes)
        case _: XGBoostBooster =>
          stream.writeByte(1)
          stream.writeInt(bytes.length)
          stream.write(bytes)
        case _ =>
          logger.warn("serializing unsupported booster")
      }
      Some(buf.toByteArray)
    }

    def weights(desc: DatasetDescriptor): Map[String, FeatureWeight] = {
      val w = booster.weights()
      val result = for {
        feature <- desc.features
        offset  <- desc.offsets.get(feature)
      } yield {
        feature match {
          case Feature.SingularFeature(_)     => feature.name -> SingularWeight(w(offset))
          case Feature.VectorFeature(_, size) => feature.name -> VectorWeight(w.slice(offset, offset + size))
          case Feature.CategoryFeature(_)     => feature.name -> SingularWeight(w(offset))
        }
      }
      result.toMap
    }
  }

  implicit val lmDecoder: Decoder[LambdaMARTConfig] = Decoder.instance(c =>
    for {
      backend  <- c.downField("backend").as[BoosterConfig]
      features <- c.downField("features").as[NonEmptyList[FeatureName]]
      weights  <- c.downField("weights").as[Map[String, Double]]
      selector <- c.downField("selector").as[Option[Selector]].map(_.getOrElse(AcceptSelector()))
      split    <- c.downField("split").as[Option[SplitStrategy]].map(_.getOrElse(SplitStrategy.default))
    } yield {
      LambdaMARTConfig(backend, features, weights, selector, split)
    }
  )
  implicit val lmEncoder: Encoder[LambdaMARTConfig] = deriveEncoder

}