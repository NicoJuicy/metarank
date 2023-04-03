package ai.metarank.feature.matcher

import ai.metarank.feature.matcher.BM25Matcher.TermFreqDic
import ai.metarank.flow.PrintProgress
import ai.metarank.model.Event
import ai.metarank.model.Event.ItemEvent
import ai.metarank.model.Field.{StringField, StringListField}
import ai.metarank.util.TextAnalyzer
import cats.effect.IO
import io.circe.{Decoder, Encoder, Json, JsonObject}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.apache.commons.io.IOUtils
import fs2.{Chunk, Stream}

import java.io.{File, FileReader}
import io.circe.parser._

case class BM25Matcher(language: TextAnalyzer, freq: TermFreqDic) extends FieldMatcher {
  lazy val term                                        = TermMatcher(language)
  val K1                                               = 1.2  // as in Lucene
  val B                                                = 0.75 // as in Lucene
  override def tokenize(string: String): Array[String] = term.tokenize(string)

  override def score(query: Array[String], doc: Array[String]): Double = {
    var sum     = 0.0
    var i       = 0
    val docFreq = doc.groupMapReduce(identity)(_ => 1)(_ + _) // is it a hot spot?
    while (i < query.length) {
      val term           = query(i)
      val docTermFreq    = docFreq.getOrElse(term, 0)
      val globalTermFreq = freq.termfreq.getOrElse(term, 0)
      val termIDF        = math.log(1.0 + (freq.docs - globalTermFreq + 0.5) / (globalTermFreq + 0.5))
      sum += termIDF * (docTermFreq * (K1 + 1.0)) / (docTermFreq + K1 * (1.0 - B + B * (doc.length / freq.avgdl)))
      i += 1
    }
    sum

  }

}

object BM25Matcher {
  case class TermFreqDic(language: String, fields: List[String], docs: Int, avgdl: Double, termfreq: Map[String, Int])

  object TermFreqDic {
    case class Builder(docs: Int = 0, lenSum: Long = 0L, docFreq: Map[String, Int] = Map()) {
      def withString(terms: Array[String]): Builder = {
        Builder(
          docs = docs + 1,
          lenSum = lenSum + terms.length,
          docFreq = terms.foldLeft(docFreq)((acc, next) => {
            val cnt = acc.getOrElse(next, 0)
            acc.updated(next, cnt + 1)
          })
        )
      }
    }
    def fromEvents(events: Stream[IO, Event], fields: Set[String], language: TextAnalyzer): IO[TermFreqDic] = {
      events
        .through(PrintProgress.tap(None, "events"))
        .collect { case e: ItemEvent => e }
        .mapChunks(items =>
          items.flatMap(item =>
            Chunk.seq(item.fields.flatMap {
              case StringField(name, value) if fields.contains(name)      => List(value)
              case StringListField(name, values) if fields.contains(name) => values
              case _                                                      => Nil
            })
          )
        )
        .compile
        .fold(Builder())((acc, next) => acc.withString(language.split(next)))
        .map(builder =>
          TermFreqDic(
            docs = builder.docs,
            avgdl = builder.lenSum.toDouble / builder.docs,
            termfreq = builder.docFreq,
            language = language.names.head,
            fields = fields.toList.sorted
          )
        )
    }

    def fromFile(path: String): IO[TermFreqDic] = for {
      text    <- IO.blocking(IOUtils.toString(new FileReader(new File(path))))
      decoded <- IO.fromEither(decode[TermFreqDic](text))
    } yield {
      decoded
    }
  }
  implicit val termDicEncoder: Encoder[TermFreqDic] = Encoder.instance(dic =>
    Json.fromJsonObject(
      JsonObject.fromMap(
        Map(
          "language" -> Json.fromString(dic.language),
          "fields"   -> Json.fromValues(dic.fields.map(Json.fromString)),
          "avgdl"    -> Json.fromDoubleOrNull(dic.avgdl),
          "docs"     -> Json.fromInt(dic.docs),
          "termfreq" -> Json.fromJsonObject(
            JsonObject.fromIterable(
              dic.termfreq.toList.sortBy(-_._2).map(x => x._1 -> Json.fromInt(x._2))
            ) // sort by freq
          )
        )
      )
    )
  )
  implicit val termDicDecoder: Decoder[TermFreqDic] = deriveDecoder[TermFreqDic]

}