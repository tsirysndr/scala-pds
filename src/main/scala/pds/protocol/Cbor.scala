package pds.protocol

import java.io.ByteArrayOutputStream
import java.nio.charset.{CharacterCodingException, StandardCharsets}
import java.nio.ByteBuffer
import java.util.Arrays

/** Deterministic DAG-CBOR. Encoding emits the only permitted form; decoding
  * rejects every alternative encoding of the same value so a block's CID is a
  * function of its bytes alone.
  */
object Cbor:
  private val LinkTag = 42L

  def encode(node: Node): Array[Byte] =
    val out = new ByteArrayOutputStream(256)
    write(out, node)
    out.toByteArray

  private def write(out: ByteArrayOutputStream, node: Node): Unit = node match
    case Node.Null           => out.write(0xf6)
    case Node.Bool(value)    => out.write(if value then 0xf5 else 0xf4)
    case Node.Integer(value) =>
      if value >= 0 then header(out, 0, value) else header(out, 1, -1L - value)
    case Node.Text(value) =>
      val bytes = value.getBytes(StandardCharsets.UTF_8)
      header(out, 3, bytes.length.toLong)
      out.write(bytes)
    case Node.Bytes(value) =>
      header(out, 2, value.length.toLong)
      out.write(value)
    case Node.Link(cid) =>
      header(out, 6, LinkTag)
      val identity = Array(0x00.toByte) ++ cid.bytes
      header(out, 2, identity.length.toLong)
      out.write(identity)
    case Node.Arr(values) =>
      header(out, 4, values.length.toLong)
      values.foreach(write(out, _))
    case Node.Obj(fields) =>
      header(out, 5, fields.size.toLong)
      fields.toVector.map((key, value) => (key.getBytes(StandardCharsets.UTF_8), value))
        .sortWith((a, b) => compareKeys(a._1, b._1) < 0)
        .foreach { (key, value) =>
          header(out, 3, key.length.toLong)
          out.write(key)
          write(out, value)
        }

  /** Canonical CBOR map order: shorter keys first, then unsigned bytewise. */
  private def compareKeys(a: Array[Byte], b: Array[Byte]): Int =
    if a.length != b.length then a.length - b.length else Arrays.compareUnsigned(a, b)

  private def header(out: ByteArrayOutputStream, major: Int, value: Long): Unit =
    val base = major << 5
    if value < 0 then throw new IllegalArgumentException("negative CBOR header")
    else if value < 24 then out.write(base | value.toInt)
    else if value <= 0xffL then
      out.write(base | 24)
      out.write(value.toInt)
    else if value <= 0xffffL then
      out.write(base | 25)
      (1 to 0 by -1).foreach(shift => out.write(((value >>> (shift * 8)) & 0xff).toInt))
    else if value <= 0xffffffffL then
      out.write(base | 26)
      (3 to 0 by -1).foreach(shift => out.write(((value >>> (shift * 8)) & 0xff).toInt))
    else
      out.write(base | 27)
      (7 to 0 by -1).foreach(shift => out.write(((value >>> (shift * 8)) & 0xff).toInt))

  final class Reader(private val bytes: Array[Byte], private var offset: Int):
    def position: Int = offset
    def exhausted: Boolean = offset >= bytes.length

    private def fail(message: String): Nothing = throw CborError(message)

    private def take(count: Int): Array[Byte] =
      if count < 0 || offset + count > bytes.length then fail("Truncated CBOR input")
      val slice = bytes.slice(offset, offset + count)
      offset += count
      slice

    private def byte(): Int =
      if offset >= bytes.length then fail("Truncated CBOR input")
      val value = bytes(offset) & 0xff
      offset += 1
      value

    private def argument(info: Int): Long = info match
      case value if value < 24 => value.toLong
      case 24 =>
        val value = byte().toLong
        if value < 24 then fail("Non-canonical CBOR integer") else value
      case 25 =>
        val value = take(2).foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xffL))
        if value <= 0xffL then fail("Non-canonical CBOR integer") else value
      case 26 =>
        val value = take(4).foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xffL))
        if value <= 0xffffL then fail("Non-canonical CBOR integer") else value
      case 27 =>
        val value = take(8).foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xffL))
        if value >= 0 && value <= 0xffffffffL then fail("Non-canonical CBOR integer")
        else if value < 0 then fail("CBOR integer exceeds the data model range")
        else value
      case 31 => fail("Indefinite lengths are not permitted")
      case _  => fail("Reserved CBOR additional information")

    def read(depth: Int = 0): Node =
      if depth > 64 then fail("CBOR nesting exceeds the supported depth")
      val initial = byte()
      val major = initial >>> 5
      val info = initial & 0x1f
      major match
        case 0 => Node.Integer(argument(info))
        case 1 =>
          val value = argument(info)
          if value == Long.MinValue then fail("CBOR integer exceeds the data model range")
          Node.Integer(-1L - value)
        case 2 => Node.Bytes(take(length(info)))
        case 3 => Node.Text(utf8(take(length(info))))
        case 4 =>
          val count = length(info)
          Node.Arr(Vector.fill(count)(read(depth + 1)))
        case 5 =>
          val count = length(info)
          var previous: Array[Byte] = null
          val fields = Vector.fill(count) {
            val header = byte()
            if (header >>> 5) != 3 then fail("CBOR map keys must be text strings")
            val key = take(length(header & 0x1f))
            if previous != null && compareKeys(previous, key) >= 0 then
              fail("CBOR map keys must be sorted and unique")
            previous = key
            utf8(key) -> read(depth + 1)
          }
          Node.Obj(fields.toMap)
        case 6 =>
          if argument(info) != LinkTag then fail("Only CID links may be tagged")
          val header = byte()
          if (header >>> 5) != 2 then fail("A CID link must be a byte string")
          val payload = take(length(header & 0x1f))
          if payload.isEmpty || payload(0) != 0x00 then fail("A CID link needs the identity prefix")
          Node.Link(Cid.fromBytes(payload.drop(1)).getOrElse(fail("Unsupported CID in link")))
        case _ =>
          info match
            case 20 => Node.Bool(false)
            case 21 => Node.Bool(true)
            case 22 => Node.Null
            case 23 => fail("Undefined is not part of the data model")
            case 25 | 26 | 27 => fail("Floating point is not part of the data model")
            case _ => fail("Unsupported CBOR simple value")

    private def length(info: Int): Int =
      val value = argument(info)
      if value > Int.MaxValue then fail("CBOR length exceeds the supported size")
      value.toInt

    private def utf8(value: Array[Byte]): String =
      try StandardCharsets.UTF_8.newDecoder.decode(ByteBuffer.wrap(value)).toString
      catch case _: CharacterCodingException => fail("CBOR text must be valid UTF-8")

  final case class CborError(message: String) extends RuntimeException(message)

  def decode(bytes: Array[Byte]): Either[String, Node] =
    try
      val reader = new Reader(bytes, 0)
      val node = reader.read()
      if !reader.exhausted then Left("Trailing bytes after the CBOR value") else Right(node)
    catch case error: CborError => Left(error.message)

  /** Reads one value from a longer stream, as CAR block payloads require. */
  def decodePrefix(bytes: Array[Byte], offset: Int): Either[String, (Node, Int)] =
    try
      val reader = new Reader(bytes, offset)
      val node = reader.read()
      Right(node -> reader.position)
    catch case error: CborError => Left(error.message)

  /** UTF-8 byte length, used for record size limits. */
  def size(node: Node): Int = encode(node).length

