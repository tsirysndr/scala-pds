package pds.protocol

import io.circe.Json
import io.circe.parser.parse
import scala.io.Source

object Fixtures:
  def json(path: String): Vector[Json] =
    val stream = getClass.getResourceAsStream(s"/fixtures/$path")
    assert(stream != null, s"missing fixture $path")
    val text = Source.fromInputStream(stream, "UTF-8").mkString
    stream.close()
    parse(text).fold(throw _, _.asArray.getOrElse(Vector.empty))
