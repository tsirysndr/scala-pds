package pds.protocol

import java.io.{ByteArrayOutputStream, InputStream}

/** CARv1 archives: the interchange format for repository blocks. */
object Car:
  val maxBlockSize = 4 * 1024 * 1024

  def varint(value: Long): Array[Byte] =
    val out = new ByteArrayOutputStream(10)
    var remaining = value
    while remaining >= 0x80 do
      out.write(((remaining & 0x7f) | 0x80).toInt)
      remaining >>>= 7
    out.write(remaining.toInt)
    out.toByteArray

  def header(roots: Seq[Cid]): Array[Byte] =
    val body = Cbor.encode(Node.Obj(Map(
      "roots" -> Node.Arr(roots.toVector.map(Node.Link.apply)),
      "version" -> Node.Integer(1L)
    )))
    varint(body.length.toLong) ++ body

  def block(cid: Cid, data: Array[Byte]): Array[Byte] =
    val cidBytes = cid.bytes
    varint((cidBytes.length + data.length).toLong) ++ cidBytes ++ data

  def write(roots: Seq[Cid], blocks: Seq[(Cid, Array[Byte])]): Array[Byte] =
    val out = new ByteArrayOutputStream(1024)
    out.write(header(roots))
    blocks.foreach((cid, data) => out.write(block(cid, data)))
    out.toByteArray

  /** Sequential reader that verifies every block against its own CID. */
  final class Reader(input: InputStream):
    private var consumed = 0L

    def bytesRead: Long = consumed

    private def readByte(): Option[Int] =
      val value = input.read()
      if value < 0 then None
      else
        consumed += 1
        Some(value)

    private def readVarint(): Either[String, Option[Long]] =
      readByte() match
        case None => Right(None)
        case Some(first) =>
          var result = (first & 0x7f).toLong
          var shift = 7
          var current = first
          var failure: Option[String] = None
          while (current & 0x80) != 0 && failure.isEmpty do
            if shift > 56 then failure = Some("CAR varint is too long")
            else
              readByte() match
                case None => failure = Some("Truncated CAR varint")
                case Some(next) =>
                  result |= (next & 0x7f).toLong << shift
                  shift += 7
                  current = next
          failure.toLeft(Some(result))

    private def readExactly(count: Int): Either[String, Array[Byte]] =
      val buffer = new Array[Byte](count)
      var filled = 0
      while filled < count do
        val read = input.read(buffer, filled, count - filled)
        if read < 0 then filled = count + 1 else filled += read
      if filled != count then Left("Truncated CAR section")
      else
        consumed += count
        Right(buffer)

    def roots(): Either[String, Vector[Cid]] =
      for
        length <- readVarint()
        size <- length.toRight("CAR archive is empty")
        _ <- Either.cond(size > 0 && size <= maxBlockSize, (), "Invalid CAR header length")
        bytes <- readExactly(size.toInt)
        node <- Cbor.decode(bytes)
        _ <- Either.cond(node("version").flatMap(_.asLong).contains(1L), (),
          "Only CAR version 1 is supported")
        roots <- node("roots").flatMap(_.asVector).toRight("CAR header is missing its roots")
        parsed <- roots.foldLeft[Either[String, Vector[Cid]]](Right(Vector.empty)) { (acc, item) =>
          for
            list <- acc
            cid <- item.asLink.toRight("CAR root is not a CID link")
          yield list :+ cid
        }
      yield parsed

    /** The next block, or None at the end of the archive. */
    def next(): Either[String, Option[(Cid, Array[Byte])]] =
      readVarint().flatMap {
        case None => Right(None)
        case Some(size) =>
          for
            _ <- Either.cond(size > 36 && size <= maxBlockSize, (), "Invalid CAR block length")
            bytes <- readExactly(size.toInt)
            cid <- Cid.fromBytes(bytes.take(36)).toRight("Unsupported CID in CAR block")
            data = bytes.drop(36)
            actual = if cid.codec == Cid.Raw then Cid.ofRaw(data) else Cid.ofCbor(data)
            _ <- Either.cond(actual == cid, (), s"CAR block does not match its CID")
          yield Some(cid -> data)
      }

  def read(bytes: Array[Byte], maxBlocks: Int = 200000): Either[String, (Vector[Cid], Vector[(Cid, Array[Byte])])] =
    val reader = new Reader(new java.io.ByteArrayInputStream(bytes))
    def loop(acc: Vector[(Cid, Array[Byte])]): Either[String, Vector[(Cid, Array[Byte])]] =
      if acc.length > maxBlocks then Left("CAR archive has too many blocks")
      else
        reader.next().flatMap {
          case None        => Right(acc)
          case Some(block) => loop(acc :+ block)
        }
    for
      roots <- reader.roots()
      blocks <- loop(Vector.empty)
    yield (roots, blocks)
