package pds.lexicon

import io.circe.Json
import java.util.regex.Pattern
import pds.crypto.Encoding
import pds.protocol.{Cid, Node, Syntax}

/** Validates a data-model value against a Lexicon definition. Callers pass
  * values that already satisfy the data model, so this adds the schema's own
  * constraints: required fields, bounds, formats, refs and unions.
  */
object Validator:
  private val maxDepth = 64
  private val graphemes = Pattern.compile("\\X")

  final case class Invalid(path: String, reason: String):
    def message: String = s"$path $reason"

  private type Result = Either[Invalid, Unit]
  private val ok: Result = Right(())

  private def check(valid: Boolean, path: String, reason: String): Result =
    if valid then ok else Left(Invalid(path, reason))

  /** A schema this implementation cannot interpret is a catalog fault, not a
    * client fault, so it is reported separately from validation failures.
    */
  final case class Unsupported(detail: String) extends RuntimeException(detail)

  def validate(catalog: Catalog, context: String, schema: Json, value: Node): Result =
    validate(catalog, context, schema, value, "$", 0)

  private def validate(
      catalog: Catalog, context: String, schema: Json, value: Node, path: String, depth: Int
  ): Result =
    if depth > maxDepth then Left(Invalid(path, "exceeds the validation depth limit"))
    else
      val cursor = schema.hcursor
      val body = cursor.get[String]("type").toOption.getOrElse(
        throw Unsupported("Lexicon definition has no type")) match
        case "boolean" =>
          check(value.isInstanceOf[Node.Bool], path, "must be a boolean")
        case "integer" =>
          value.asLong match
            case None        => Left(Invalid(path, "must be an integer"))
            case Some(number) => bounds(schema, number, "minimum", "maximum", path)
        case "string"    => string(schema, value, path)
        case "bytes" =>
          value.asBytes match
            case None        => Left(Invalid(path, "must be bytes"))
            case Some(bytes) => bounds(schema, bytes.length.toLong, "minLength", "maxLength", path)
        case "cid-link"  => check(value.asLink.isDefined, path, "must be a CID link")
        case "blob"      => blob(schema, value, path)
        case "array"     => array(catalog, context, schema, value, path, depth)
        case "object"    => obj(catalog, context, schema, value, path, depth)
        case "unknown"   => check(plainObject(value), path, "must be an object")
        case "ref"       => ref(catalog, context, schema, value, path, depth)
        case "union"     => union(catalog, context, schema, value, path, depth)
        case "record" =>
          cursor.downField("record").focus match
            case Some(record) => validate(catalog, context, record, value, path, depth + 1)
            case None         => throw Unsupported("Lexicon record has no body")
        case other => throw Unsupported(s"Unsupported Lexicon value type $other")
      body.flatMap(_ => constants(schema, value, path))

  private def bounds(
      schema: Json, value: Long, minimum: String, maximum: String, path: String
  ): Result =
    for
      _ <- schema.hcursor.get[Long](minimum).toOption
        .fold(ok)(lower => check(value >= lower, path, s"is below $minimum"))
      _ <- schema.hcursor.get[Long](maximum).toOption
        .fold(ok)(upper => check(value <= upper, path, s"exceeds $maximum"))
    yield ()

  private def string(schema: Json, value: Node, path: String): Result =
    value.asString match
      case None => Left(Invalid(path, "must be a string"))
      case Some(text) =>
        for
          _ <- bounds(schema, Encoding.utf8(text).length.toLong, "minLength", "maxLength", path)
          _ <-
            if schema.hcursor.keys.exists(_.exists(_.startsWith("minGraphemes"))) ||
              schema.hcursor.keys.exists(_.exists(_.startsWith("maxGraphemes")))
            then bounds(schema, graphemeCount(text), "minGraphemes", "maxGraphemes", path)
            else ok
          _ <- schema.hcursor.get[String]("format").toOption.fold(ok) { format =>
            Formats.validator(format) match
              case None => throw Unsupported(s"Unsupported Lexicon string format $format")
              case Some(valid) => check(valid(text), path, s"must have format $format")
          }
        yield ()

  private def graphemeCount(text: String): Long =
    val matcher = graphemes.matcher(text)
    var count = 0L
    while matcher.find() do count += 1
    count

  private def blob(schema: Json, value: Node, path: String): Result =
    Node.blobs(value).headOption.filter(_ => value.recordType.contains("blob")) match
      case None => Left(Invalid(path, "must be a blob"))
      case Some(found) =>
        for
          _ <- bounds(schema, found.size, "minSize", "maxSize", path)
          _ <- schema.hcursor.get[Vector[String]]("accept").toOption.fold(ok) { accept =>
            check(accept.exists(accepts(_, found.mimeType)), path,
              "has an unsupported blob MIME type")
          }
        yield ()

  private def accepts(pattern: String, mimeType: String): Boolean =
    pattern == "*/*" || pattern == mimeType ||
      (pattern.endsWith("/*") && mimeType.takeWhile(_ != '/') == pattern.dropRight(2))

  private def array(
      catalog: Catalog, context: String, schema: Json, value: Node, path: String, depth: Int
  ): Result =
    value.asVector match
      case None => Left(Invalid(path, "must be an array"))
      case Some(items) =>
        val element = schema.hcursor.downField("items").focus
          .getOrElse(throw Unsupported("Lexicon array has no items"))
        for
          _ <- bounds(schema, items.length.toLong, "minLength", "maxLength", path)
          _ <- items.zipWithIndex.foldLeft(ok) { case (acc, (item, index)) =>
            acc.flatMap(_ => validate(catalog, context, element, item, s"$path[$index]", depth + 1))
          }
        yield ()

  private def plainObject(value: Node): Boolean = value match
    case Node.Obj(_) => !value.recordType.contains("blob")
    case _           => false

  private def obj(
      catalog: Catalog, context: String, schema: Json, value: Node, path: String, depth: Int
  ): Result =
    if !plainObject(value) then Left(Invalid(path, "must be an object"))
    else
      val fields = value.entries
      val required = schema.hcursor.get[Vector[String]]("required").getOrElse(Vector.empty)
      val nullable = schema.hcursor.get[Vector[String]]("nullable").getOrElse(Vector.empty)
      val properties = schema.hcursor.downField("properties").focus
        .flatMap(_.asObject).map(_.toMap).getOrElse(Map.empty)
      for
        _ <- required.foldLeft(ok) { (acc, key) =>
          acc.flatMap(_ => check(fields.contains(key), s"$path.$key", "is required"))
        }
        _ <- properties.foldLeft(ok) { case (acc, (key, property)) =>
          acc.flatMap { _ =>
            fields.get(key) match
              case None                                            => ok
              case Some(Node.Null) if nullable.contains(key)       => ok
              case Some(item) => validate(catalog, context, property, item, s"$path.$key", depth + 1)
          }
        }
      yield ()

  private def ref(
      catalog: Catalog, context: String, schema: Json, value: Node, path: String, depth: Int
  ): Result =
    val target = schema.hcursor.get[String]("ref").toOption
      .getOrElse(throw Unsupported("Lexicon ref has no target"))
    catalog.definition(context, target) match
      case None => throw Unsupported(s"Unresolved Lexicon reference $target")
      case Some((id, definition)) =>
        for
          _ <-
            if definition.hcursor.get[String]("type").contains("record") then
              check(value.recordType.contains(id), path + ".$type",
                "must match the referenced record")
            else ok
          _ <- validate(catalog, id, definition, value, path, depth + 1)
        yield ()

  private def union(
      catalog: Catalog, context: String, schema: Json, value: Node, path: String, depth: Int
  ): Result =
    val refs = schema.hcursor.get[Vector[String]]("refs").getOrElse(Vector.empty)
      .map(item => (if item.startsWith("#") then s"$context$item" else item).stripSuffix("#main"))
      .toSet
    value.recordType.filter(isTypeRef) match
      case None => Left(Invalid(path, "must have a union $type"))
      case Some(tag) if refs.contains(tag) =>
        ref(catalog, context, Json.obj("ref" -> Json.fromString(tag)), value, path, depth)
      case Some(_) =>
        check(!schema.hcursor.get[Boolean]("closed").getOrElse(false), path,
          "has an unknown union $type")

  private def isTypeRef(value: String): Boolean =
    value.split("#", -1) match
      case Array(nsid)           => Syntax.isNsid(nsid)
      case Array(nsid, fragment) => Syntax.isNsid(nsid) && fragment.nonEmpty
      case _                     => false

  private def constants(schema: Json, value: Node, path: String): Result =
    for
      _ <- schema.hcursor.downField("const").focus.fold(ok) { expected =>
        check(Node.toJson(value) == expected, path, "does not match const")
      }
      _ <- schema.hcursor.downField("enum").focus.flatMap(_.asArray).fold(ok) { allowed =>
        check(allowed.contains(Node.toJson(value)), path, "is not in enum")
      }
    yield ()

  /** `Some("valid")`, `Some("unknown")`, or `None` when validation is skipped. */
  def validateRecord(
      catalog: Catalog, collection: String, recordKey: String, value: Node, mode: Option[Boolean]
  ): Either[Invalid, Option[String]] =
    if mode.contains(false) then Right(None)
    else
      catalog.record(collection) match
        case None if mode.contains(true) =>
          Left(Invalid("$", "has no known record schema"))
        case None => Right(Some("unknown"))
        case Some(schema) =>
          for
            _ <- check(value.recordType.contains(collection), "$" + ".$type",
              "must match the collection")
            _ <- recordKeyMatches(schema, recordKey)
            _ <- validate(catalog, collection, schema, value)
          yield Some("valid")

  private def recordKeyMatches(schema: Json, recordKey: String): Result =
    val declared = schema.hcursor.get[String]("key").getOrElse("any")
    val valid = declared match
      case "any"                                  => true
      case "tid"                                  => Syntax.isTid(recordKey)
      case literal if literal.startsWith("literal:") => recordKey == literal.drop(8)
      case _                                      => false
    check(valid, "$rkey", "does not match the record schema")
