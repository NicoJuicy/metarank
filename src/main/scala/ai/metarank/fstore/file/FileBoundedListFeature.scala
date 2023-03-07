package ai.metarank.fstore.file

import ai.metarank.fstore.codec.StoreFormat
import ai.metarank.fstore.file.FileBoundedListFeature.KeyTimeValue
import ai.metarank.fstore.file.client.{FileClient, SortedDB}
import ai.metarank.fstore.transfer.StateSource
import ai.metarank.model.Feature.BoundedListFeature
import ai.metarank.model.Feature.BoundedListFeature.BoundedListConfig
import ai.metarank.model.FeatureValue.BoundedListValue
import ai.metarank.model.FeatureValue.BoundedListValue.TimeValue
import ai.metarank.model.State.BoundedListState
import ai.metarank.model.{FeatureValue, Key, Scalar, State, Timestamp, Write}
import ai.metarank.util.SortedGroupBy
import cats.effect.IO
import org.apache.commons.lang3.ArrayUtils
import fs2.Stream

import scala.annotation.tailrec

case class FileBoundedListFeature(
    config: BoundedListConfig,
    db: SortedDB[Array[Byte]],
    format: StoreFormat
) extends BoundedListFeature {

  private def encodeKey(key: Key, ts: Timestamp): String = {
    format.key.encodeNoPrefix(key) + "/" + ts.ts.toString
  }

  override def put(action: Write.Append): IO[Unit] = IO {
    db.put(encodeKey(action.key, action.ts), format.scalar.encode(action.value))
  }

  override def computeValue(key: Key, ts: Timestamp): IO[Option[FeatureValue.BoundedListValue]] = for {
    last   <- IO(db.lastN(format.key.encodeNoPrefix(key), config.count))
    parsed <- IO.fromEither(decodeRecursive(last, ts.minus(config.duration)))
  } yield {
    parsed match {
      case Nil => None
      case _   => Some(BoundedListValue(key, ts, parsed.map(ktv => TimeValue(ktv.ts, ktv.s))))
    }
  }

  @tailrec final def decodeRecursive(
      items: Iterator[(String, Array[Byte])],
      cutoff: Timestamp,
      acc: List[KeyTimeValue] = Nil
  ): Either[Throwable, List[KeyTimeValue]] = {
    items.nextOption() match {
      case None => Right(acc)
      case Some(head) =>
        decodePrefix(head) match {
          case Left(value)                               => Left(value)
          case Right(value) if value.ts.isBefore(cutoff) => Right(acc)
          case Right(value)                              => decodeRecursive(items, cutoff, acc :+ value)
        }

    }
  }

  def decodePrefix(kv: (String, Array[Byte])): Either[Throwable, KeyTimeValue] = {
    val lastSep = kv._1.lastIndexOf('/')
    if (lastSep == -1) {
      Left(new Exception(s"cannot parse key ${kv._1}"))
    } else {
      val keyString = kv._1.substring(0, lastSep)
      format.key.decodeNoPrefix(keyString) match {
        case Left(err) => Left(err)
        case Right(key) =>
          val ts = kv._1.substring(lastSep + 1)
          ts.toLongOption match {
            case Some(value) =>
              format.scalar.decode(kv._2).flatMap(s => Right(KeyTimeValue(key, Timestamp(value), s)))
            case None => Left(new Exception(s"cannot parse timestamp $ts"))
          }

      }
    }
  }
}

object FileBoundedListFeature {
  case class KeyTimeValue(key: Key, ts: Timestamp, s: Scalar)

  implicit val fileListSource: StateSource[BoundedListState, FileBoundedListFeature] =
    new StateSource[BoundedListState, FileBoundedListFeature] {
      override def source(f: FileBoundedListFeature): fs2.Stream[IO, BoundedListState] = {
        Stream
          .fromBlockingIterator[IO](f.db.all(), 128)
          .evalMap(kv => IO.fromEither(f.decodePrefix(kv)))
          .through(SortedGroupBy.groupBy(_.key))
          .evalMap {
            case Nil             => IO.raiseError(new Exception("empty batch, this should not happen"))
            case all @ head :: _ => IO(BoundedListState(head.key, all.map(ktv => TimeValue(ktv.ts, ktv.s))))
          }
      }

    }
}
