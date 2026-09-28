package pds.protocol

import pds.crypto.{Encoding, Hash}

/** CIDv1 with a SHA-256 multihash, restricted to the two codecs a repository
  * uses: dag-cbor for nodes and raw for blobs.
  */
final case class Cid(codec: Int, digest: Array[Byte]):
  require(digest.length == 32, "CID digest must be SHA-256")
  require(codec == Cid.DagCbor || codec == Cid.Raw, "unsupported CID codec")

  def bytes: Array[Byte] = Array(0x01.toByte, codec.toByte, 0x12.toByte, 0x20.toByte) ++ digest

  override def toString: String = "b" + Encoding.base32(bytes)

  override def equals(other: Any): Boolean = other match
    case that: Cid => codec == that.codec && digest.sameElements(that.digest)
    case _         => false

  override def hashCode: Int = codec * 31 + java.util.Arrays.hashCode(digest)

object Cid:
  val DagCbor = 0x71
  val Raw = 0x55

  def ofCbor(bytes: Array[Byte]): Cid = Cid(DagCbor, Hash.sha256(bytes))
  def ofRaw(bytes: Array[Byte]): Cid = Cid(Raw, Hash.sha256(bytes))

  def parse(value: String): Option[Cid] =
    if value.length != 59 || !value.startsWith("b") then None
    else
      Encoding.unbase32(value.drop(1)).filter(bytes => Encoding.base32(bytes) == value.drop(1))
        .flatMap(fromBytes)

  def fromBytes(bytes: Array[Byte]): Option[Cid] =
    if bytes.length != 36 || bytes(0) != 0x01 || bytes(2) != 0x12 || bytes(3) != 0x20 then None
    else
      val codec = bytes(1) & 0xff
      if codec == DagCbor || codec == Raw then Some(Cid(codec, bytes.drop(4))) else None
