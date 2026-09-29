package pds.lexicon

import io.circe.Json
import pds.protocol.Syntax
import scala.collection.mutable

/** Admits a remote schema graph before it is used as validator instructions.
  * Every reachable definition must be one this implementation understands, and
  * every reference must resolve, so a published Lexicon can fail resolution but
  * never steer the validator into unsupported behaviour.
  */
object Admission:
  val maxDocuments = 32
  val maxTotalBytes = 4 * 1024 * 1024
  val maxDocumentBytes = 1024 * 1024
  val maxNodes = 10000
  val maxDepth = 64

  private val fields: Map[String, Set[String]] = Map(
    "boolean" -> Set("default", "const"),
    "integer" -> Set("default", "const", "minimum", "maximum", "enum"),
    "string" -> Set("default", "const", "enum", "knownValues", "format",
      "minLength", "maxLength", "minGraphemes", "maxGraphemes"),
    "bytes" -> Set("minLength", "maxLength"),
    "cid-link" -> Set.empty,
    "blob" -> Set("accept", "maxSize", "minSize"),
    "array" -> Set("items", "minLength", "maxLength"),
    "object" -> Set("properties", "required", "nullable"),
    "unknown" -> Set.empty,
    "ref" -> Set("ref"),
    "union" -> Set("refs", "closed"),
    "record" -> Set("key", "record")
  )

  private val ignored = Set("type", "description")
  private val definitionName = "[A-Za-z][A-Za-z0-9]*".r

  final case class Rejected(reason: String) extends RuntimeException(reason)

  private def require(valid: Boolean, reason: String): Unit =
    if !valid then throw Rejected(reason)

  /** Checks the shape of a `com.atproto.lexicon.schema` record. */
  def isSchemaRecord(nsid: String, record: Json): Boolean =
    val cursor = record.hcursor
    cursor.get[String]("$type").contains("com.atproto.lexicon.schema") &&
      cursor.get[Int]("lexicon").contains(1) &&
      cursor.get[String]("id").contains(nsid) &&
      cursor.downField("defs").focus.flatMap(_.asObject).exists { defs =>
        defs.nonEmpty && defs.toMap.forall { (name, definition) =>
          definitionName.matches(name) &&
            definition.isObject && definition.hcursor.get[String]("type").isRight
        }
      }

  /** The NSIDs a document references, for crawling the graph before admitting
    * it. Shape is only checked far enough to read the references; `catalog`
    * performs the full check once every document is in hand.
    */
  def referencedIds(document: Json): Set[String] =
    val found = mutable.Set.empty[String]
    def walk(value: Json): Unit =
      value.arrayOrObject((),
        values => values.foreach(walk),
        fields =>
          fields("ref").flatMap(_.asString).foreach(record)
          fields("refs").flatMap(_.asArray).foreach(_.flatMap(_.asString).foreach(record))
          fields.toMap.values.foreach(walk))
    def record(target: String): Unit =
      if !target.startsWith("#") then
        val id = target.takeWhile(_ != '#')
        if Syntax.isNsid(id) then found += id
    walk(document)
    found.toSet

  /** Builds a closed catalog of everything `collection` reaches, fetching each
    * NSID at most once. Returns a fault reason rather than a partial catalog.
    */
  def catalog(lookup: String => Option[Json], collection: String): Either[String, Catalog] =
    try
      require(Syntax.isNsid(collection), "Invalid collection")
      val documents = mutable.LinkedHashMap.empty[String, Json]
      val pending = mutable.Queue(collection)
      val seen = mutable.Set(collection)
      var nodes = 0
      var total = 0

      def visit(context: String, schema: Json, depth: Int): Unit =
        require(depth <= maxDepth, "Schema nesting is too deep")
        nodes += 1
        require(nodes <= maxNodes, "Schema graph is too large")
        val cursor = schema.hcursor
        val kind = cursor.get[String]("type").toOption
          .getOrElse(throw Rejected("A definition has no type"))
        val allowed = fields.getOrElse(kind, throw Rejected(s"Unsupported definition type $kind"))
        val keys = cursor.keys.map(_.toSet).getOrElse(Set.empty)
        require(keys.forall(key => allowed.contains(key) || ignored.contains(key)),
          s"A $kind definition has unsupported fields")
        kind match
          case "string" =>
            cursor.get[String]("format").toOption.foreach(format =>
              require(Formats.validator(format).isDefined, s"Unsupported string format $format"))
          case "array" =>
            visit(context, cursor.downField("items").focus
              .getOrElse(throw Rejected("An array has no items")), depth + 1)
          case "object" =>
            cursor.downField("properties").focus.flatMap(_.asObject).foreach(properties =>
              properties.toMap.values.foreach(visit(context, _, depth + 1)))
          case "record" =>
            val key = cursor.get[String]("key").getOrElse("any")
            require(key == "any" || key == "tid" || key.startsWith("literal:"),
              s"Unsupported record key type $key")
            visit(context, cursor.downField("record").focus
              .getOrElse(throw Rejected("A record has no body")), depth + 1)
          case "ref"   => reference(context, cursor.get[String]("ref").toOption)
          case "union" =>
            cursor.get[Vector[String]]("refs").getOrElse(Vector.empty)
              .foreach(target => reference(context, Some(target)))
          case _ => ()

      def reference(context: String, target: Option[String]): Unit =
        val value = target.getOrElse(throw Rejected("A reference has no target"))
        val absolute = (if value.startsWith("#") then s"$context$value" else value)
          .stripSuffix("#main")
        val parts = absolute.split("#", -1)
        require(parts.length <= 2 && Syntax.isNsid(parts.head), s"Invalid reference $value")
        if parts.length == 2 then
          require(definitionName.matches(parts(1)), s"Invalid reference $value")
        if !seen.contains(parts.head) then
          seen += parts.head
          pending.enqueue(parts.head)

      while pending.nonEmpty do
        val id = pending.dequeue()
        require(documents.size < maxDocuments, "Schema graph needs too many documents")
        val document = lookup(id).getOrElse(throw Rejected(s"Schema $id could not be resolved"))
        val size = document.noSpaces.getBytes("UTF-8").length
        require(size <= maxDocumentBytes, s"Schema $id is too large")
        total += size
        require(total <= maxTotalBytes, "Schema graph is too large")
        require(document.hcursor.get[Int]("lexicon").contains(1), s"Schema $id is not lexicon 1")
        require(document.hcursor.get[String]("id").contains(id), s"Schema $id has the wrong id")
        val defs = document.hcursor.downField("defs").focus.flatMap(_.asObject)
          .getOrElse(throw Rejected(s"Schema $id has no definitions"))
        defs.toMap.foreach { (name, definition) =>
          require(definitionName.matches(name), s"Schema $id has an invalid definition name")
          visit(id, definition, 0)
        }
        documents.update(id, document)

      val built = new Catalog(documents.toMap)
      require(built.record(collection).isDefined, s"$collection is not a record schema")
      Right(built)
    catch case Rejected(reason) => Left(reason)
