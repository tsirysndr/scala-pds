package pds.crypto

import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.{ECDomainParameters, ECPrivateKeyParameters, ECPublicKeyParameters}
import org.bouncycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}
import org.bouncycastle.math.ec.ECPoint

enum Curve(val name: String, val multicodec: Array[Byte], val jwtAlgorithm: String):
  case K256 extends Curve("secp256k1", Array(0xe7.toByte, 0x01.toByte), "ES256K")
  case P256 extends Curve("secp256r1", Array(0x80.toByte, 0x24.toByte), "ES256")

  private[crypto] lazy val parameters: X9ECParameters =
    CustomNamedCurves.getByName(name)
  private[crypto] lazy val domain: ECDomainParameters =
    new ECDomainParameters(parameters.getCurve, parameters.getG, parameters.getN, parameters.getH)
  private[crypto] def fieldWidth: Int = (parameters.getCurve.getFieldSize + 7) / 8

object Curve:
  def fromMulticodec(prefix: Array[Byte]): Option[Curve] =
    values.find(curve => curve.multicodec.sameElements(prefix))

  def fromJwk(name: String): Option[Curve] = name match
    case "P-256"     => Some(P256)
    case "secp256k1" => Some(K256)
    case _           => None

final case class PublicKey(curve: Curve, point: ECPoint):
  def compressed: Array[Byte] = point.getEncoded(true)
  def uncompressed: Array[Byte] = point.getEncoded(false)

  def multibase: String = "z" + Encoding.base58(curve.multicodec ++ compressed)

  /** base58btc of the bare compressed key, as the legacy
    * `EcdsaSecp256(k|r)1VerificationKey2019` DID document suites encode it. */
  def legacyMultibase: String = "z" + Encoding.base58(compressed)
  def didKey: String = s"did:key:$multibase"

  def jwk: io.circe.Json =
    val width = curve.fieldWidth
    val normalized = point.normalize()
    io.circe.Json.obj(
      "kty" -> io.circe.Json.fromString("EC"),
      "crv" -> io.circe.Json.fromString(if curve == Curve.P256 then "P-256" else "secp256k1"),
      "x" -> io.circe.Json.fromString(
        Encoding.b64(Encoding.unsigned(BigInt(normalized.getAffineXCoord.toBigInteger), width))),
      "y" -> io.circe.Json.fromString(
        Encoding.b64(Encoding.unsigned(BigInt(normalized.getAffineYCoord.toBigInteger), width)))
    )

  def verify(message: Array[Byte], signature: Array[Byte]): Boolean =
    val width = curve.fieldWidth
    if signature.length != width * 2 then false
    else
      val r = BigInt(1, signature.take(width))
      val s = BigInt(1, signature.drop(width))
      val order = BigInt(curve.parameters.getN)
      if r <= 0 || s <= 0 || r >= order || s > order / 2 then false
      else
        val signer = new ECDSASigner()
        signer.init(false, new ECPublicKeyParameters(point, curve.domain))
        signer.verifySignature(Hash.sha256(message), r.bigInteger, s.bigInteger)

object PublicKey:
  def fromMultibase(value: String): Option[PublicKey] =
    if !value.startsWith("z") then None
    else
      for
        bytes <- Encoding.unbase58(value.drop(1))
        if bytes.length > 2
        curve <- Curve.fromMulticodec(bytes.take(2))
        key <- fromCompressed(curve, bytes.drop(2))
      yield key

  def fromDidKey(did: String): Option[PublicKey] =
    if did.startsWith("did:key:") then fromMultibase(did.drop("did:key:".length)) else None

  def fromCompressed(curve: Curve, bytes: Array[Byte]): Option[PublicKey] =
    if bytes.length != curve.fieldWidth + 1 then None else decode(curve, bytes)

  def fromCoordinates(curve: Curve, x: Array[Byte], y: Array[Byte]): Option[PublicKey] =
    val width = curve.fieldWidth
    if x.length != width || y.length != width then None
    else decode(curve, Array(0x04.toByte) ++ x ++ y)

  private def decode(curve: Curve, bytes: Array[Byte]): Option[PublicKey] =
    try
      val point = curve.parameters.getCurve.decodePoint(bytes).normalize()
      if point.isValid && !point.isInfinity then Some(PublicKey(curve, point)) else None
    catch case _: IllegalArgumentException | _: ArithmeticException => None

final case class PrivateKey(curve: Curve, scalar: BigInt):
  lazy val publicKey: PublicKey =
    PublicKey(curve, curve.parameters.getG.multiply(scalar.bigInteger).normalize())

  def bytes: Array[Byte] = Encoding.unsigned(scalar, curve.fieldWidth)

  def sign(message: Array[Byte]): Array[Byte] =
    val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()))
    signer.init(true, new ECPrivateKeyParameters(scalar.bigInteger, curve.domain))
    val Array(r, s) = signer.generateSignature(Hash.sha256(message))
    val order = BigInt(curve.parameters.getN)
    val low = if BigInt(s) > order / 2 then order - BigInt(s) else BigInt(s)
    val width = curve.fieldWidth
    Encoding.unsigned(BigInt(r), width) ++ Encoding.unsigned(low, width)

object PrivateKey:
  def generate(curve: Curve): PrivateKey =
    val order = BigInt(curve.parameters.getN)
    var scalar = BigInt(0)
    while scalar < 1 || scalar >= order do scalar = BigInt(1, Hash.randomBytes(curve.fieldWidth))
    PrivateKey(curve, scalar)

  def fromBytes(curve: Curve, bytes: Array[Byte]): Option[PrivateKey] =
    val scalar = BigInt(1, bytes)
    if bytes.length != curve.fieldWidth || scalar < 1 || scalar >= BigInt(curve.parameters.getN)
    then None
    else Some(PrivateKey(curve, scalar))
