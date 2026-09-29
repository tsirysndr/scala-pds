package pds.lexicon

import io.circe.Json
import pds.crypto.{Encoding, Hash}
import scala.io.Source

/** The trusted, offline Lexicon catalog. Schemas are program data, never
  * client-supplied: every file is checked against the checksum recorded in
  * `index.json` at load, so a tampered schema fails loudly at startup.
  */
final class Catalog(val schemas: Map[String, Json]):
  def schema(id: String): Option[Json] = schemas.get(id)

  def contains(id: String): Boolean = schemas.contains(id)

  /** Resolves a possibly relative `#fragment` reference to (id, definition). */
  def definition(context: String, ref: String): Option[(String, Json)] =
    val absolute = (if ref.startsWith("#") then s"$context$ref" else ref).stripSuffix("#main")
    absolute.split("#", 2) match
      case Array(id) =>
        schemas.get(id).flatMap(_.hcursor.downField("defs").downField("main").focus)
          .map(id -> _)
      case Array(id, name) =>
        schemas.get(id).flatMap(_.hcursor.downField("defs").downField(name).focus)
          .map(id -> _)
      case _ => None

  def record(collection: String): Option[Json] =
    schemas.get(collection).flatMap(_.hcursor.downField("defs").downField("main").focus)
      .filter(_.hcursor.get[String]("type").contains("record"))

object Catalog:
  private def read(path: String): Array[Byte] =
    val stream = getClass.getClassLoader.getResourceAsStream(path)
    require(stream != null, s"missing lexicon resource $path")
    try stream.readAllBytes() finally stream.close()

  def load(): Catalog =
    val index = io.circe.parser.parse(Encoding.text(read("lexicons/index.json")))
      .getOrElse(throw new IllegalStateException("lexicon index is not JSON"))
    val checksums = index.hcursor.downField("schemas").as[Map[String, String]]
      .getOrElse(throw new IllegalStateException("lexicon index has no schema checksums"))
    val schemas = checksums.map { (id, checksum) =>
      val bytes = read(s"lexicons/$id.json")
      if Encoding.hex(Hash.sha256(bytes)) != checksum then
        throw new IllegalStateException(s"Lexicon catalog integrity check failed for $id")
      val schema = io.circe.parser.parse(Encoding.text(bytes))
        .getOrElse(throw new IllegalStateException(s"Lexicon $id is not JSON"))
      if !schema.hcursor.get[String]("id").contains(id) ||
        !schema.hcursor.get[Int]("lexicon").contains(1) then
        throw new IllegalStateException(s"Lexicon catalog integrity check failed for $id")
      id -> schema
    }
    new Catalog(schemas)

  lazy val trusted: Catalog = load()
