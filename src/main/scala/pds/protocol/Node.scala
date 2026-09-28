package pds.protocol

import io.circe.{Json, JsonObject}
import pds.crypto.Encoding

/** The AT Protocol data model: the subset of IPLD that DAG-CBOR records use. */
enum Node:
  case Null
  case Bool(value: Boolean)
  case Integer(value: Long)
  case Text(value: String)
  case Bytes(value: Array[Byte])
  case Link(cid: Cid)
  case Arr(values: Vector[Node])
  case Obj(fields: Map[String, Node])

  def apply(key: String): Option[Node] = this match
    case Obj(fields) => fields.get(key)
    case _           => None

  def asString: Option[String] = this match
    case Text(value) => Some(value)
    case _           => None

  def asLong: Option[Long] = this match
    case Integer(value) => Some(value)
    case _              => None

  def asLink: Option[Cid] = this match
    case Link(cid) => Some(cid)
    case _         => None

  def asBytes: Option[Array[Byte]] = this match
    case Bytes(value) => Some(value)
    case _            => None

  def asVector: Option[Vector[Node]] = this match
    case Arr(values) => Some(values)
    case _           => None

  def entries: Map[String, Node] = this match
    case Obj(values) => values
    case _           => Map.empty

  def recordType: Option[String] = apply("$type").flatMap(_.asString)

object Node:
  def obj(entries: (String, Node)*): Node = Obj(entries.toMap)

  /** Byte strings hold arrays, so identity is the canonical encoding. */
  def same(left: Node, right: Node): Boolean =
    java.util.Arrays.equals(Cbor.encode(left), Cbor.encode(right))
  def text(value: String): Node = Text(value)

  private val maxDepth = 32

  /** Decodes the JSON representation of the data model, where links appear as
    * `{"$link": cid}` and byte strings as `{"$bytes": base64}`.
    */
  def fromJson(json: Json, depth: Int = 0): Either[String, Node] =
    if depth > maxDepth then Left("Data nesting exceeds the supported depth")
    else
      json.fold(
        Right(Null),
        value => Right(Bool(value)),
        number =>
          number.toLong.map(value => Integer(value))
            .toRight("Only integer numbers are supported by the data model"),
        value => Right(Text(value)),
        values =>
          values.foldLeft[Either[String, Vector[Node]]](Right(Vector.empty)) { (acc, item) =>
            acc.flatMap(list => fromJson(item, depth + 1).map(list :+ _))
          }.map(Arr.apply),
        fields => objectFromJson(fields, depth)
      )

  private def objectFromJson(fields: JsonObject, depth: Int): Either[String, Node] =
    val keys = fields.keys.toVector
    if keys.contains("$link") then
      if keys.length != 1 then Left("A link must have exactly one field")
      else
        fields("$link").flatMap(_.asString).flatMap(Cid.parse)
          .map(Link.apply).toRight("Invalid CID in $link")
    else if keys.contains("$bytes") then
      if keys.length != 1 then Left("A byte string must have exactly one field")
      else
        fields("$bytes").flatMap(_.asString).flatMap(Encoding.unb64Std)
          .map(Bytes.apply).toRight("Invalid base64 in $bytes")
    else
      for
        entries <- keys.foldLeft[Either[String, Map[String, Node]]](Right(Map.empty)) { (acc, key) =>
          for
            result <- acc
            value <- fromJson(fields(key).get, depth + 1)
          yield result.updated(key, value)
        }
        _ <- checkType(entries)
        node = Obj(entries)
        _ <- checkBlob(node)
      yield node

  private def checkType(entries: Map[String, Node]): Either[String, Unit] =
    entries.get("$type") match
      case None => Right(())
      case Some(Text(value)) if value == "blob" || isTypeRef(value) => Right(())
      case _ => Left("$type must be a Lexicon reference or \"blob\"")

  private def isTypeRef(value: String): Boolean =
    value.split("#", -1) match
      case Array(nsid)           => Syntax.isNsid(nsid)
      case Array(nsid, fragment) => Syntax.isNsid(nsid) && fragment.nonEmpty
      case _                     => false

  private def checkBlob(node: Node): Either[String, Unit] =
    if node.recordType.contains("blob") then
      val fields = node.entries
      val valid = fields.keySet == Set("$type", "ref", "mimeType", "size") &&
        fields.get("ref").flatMap(_.asLink).exists(_.codec == Cid.Raw) &&
        fields.get("mimeType").flatMap(_.asString).exists(_.nonEmpty) &&
        fields.get("size").flatMap(_.asLong).exists(_ >= 0)
      if valid then Right(()) else Left("Invalid blob reference")
    else Right(())

  def toJson(node: Node): Json = node match
    case Null           => Json.Null
    case Bool(value)    => Json.fromBoolean(value)
    case Integer(value) => Json.fromLong(value)
    case Text(value)    => Json.fromString(value)
    case Bytes(value)   => Json.obj("$bytes" -> Json.fromString(Encoding.b64Std(value)))
    case Link(cid)      => Json.obj("$link" -> Json.fromString(cid.toString))
    case Arr(values)    => Json.arr(values.map(toJson)*)
    case Obj(fields)    => Json.obj(fields.toVector.sortBy(_._1).map((k, v) => k -> toJson(v))*)

  /** Blob references carry their own shape, checked before a record is stored. */
  final case class Blob(ref: Cid, mimeType: String, size: Long)

  def blobs(node: Node): Vector[Blob] = node match
    case Arr(values) => values.flatMap(blobs)
    case Obj(fields) =>
      val own = for
        tpe <- fields.get("$type").flatMap(_.asString) if tpe == "blob"
        ref <- fields.get("ref").flatMap(_.asLink) if ref.codec == Cid.Raw
        mime <- fields.get("mimeType").flatMap(_.asString) if mime.nonEmpty
        size <- fields.get("size").flatMap(_.asLong) if size >= 0
      yield Blob(ref, mime, size)
      own.toVector ++ fields.values.toVector.flatMap(blobs)
    case _ => Vector.empty
