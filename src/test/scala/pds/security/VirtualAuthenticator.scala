package pds.security

import io.circe.Json
import java.io.ByteArrayOutputStream
import pds.crypto.{Curve, Encoding, Hash, PrivateKey}

/** A WebAuthn authenticator, enough of one to exercise real ceremonies.
  *
  * It produces the attestation and assertion responses a browser would, signed
  * with a P-256 key, so registration and sign-in are verified by the library
  * rather than stubbed out. Attestation format is `none`, which is what a
  * platform authenticator normally reports.
  */
final class VirtualAuthenticator(val credentialId: Array[Byte] = Hash.randomBytes(32)):
  private val key = PrivateKey.generate(Curve.P256)
  private var counter = 0L

  private val aaguid = new Array[Byte](16)

  /** COSE_Key for an ES256 public key: kty=2, alg=-7, crv=1, x, y. */
  private def coseKey: Array[Byte] =
    val point = key.publicKey.point.normalize()
    val width = 32
    val x = Encoding.unsigned(BigInt(point.getAffineXCoord.toBigInteger), width)
    val y = Encoding.unsigned(BigInt(point.getAffineYCoord.toBigInteger), width)
    val out = new ByteArrayOutputStream()
    Cbor.map(out, 5)
    Cbor.int(out, 1); Cbor.int(out, 2)
    Cbor.int(out, 3); Cbor.int(out, -7)
    Cbor.int(out, -1); Cbor.int(out, 1)
    Cbor.int(out, -2); Cbor.bytes(out, x)
    Cbor.int(out, -3); Cbor.bytes(out, y)
    out.toByteArray

  private def authenticatorData(rpId: String, attested: Boolean): Array[Byte] =
    val flags = (0x01 | 0x04 | (if attested then 0x40 else 0x00)).toByte
    val out = new ByteArrayOutputStream()
    out.write(Hash.sha256(rpId))
    out.write(flags.toInt)
    counter += 1
    (3 to 0 by -1).foreach(shift => out.write(((counter >>> (shift * 8)) & 0xff).toInt))
    if attested then
      out.write(aaguid)
      out.write((credentialId.length >> 8) & 0xff)
      out.write(credentialId.length & 0xff)
      out.write(credentialId)
      out.write(coseKey)
    out.toByteArray

  private def clientData(kind: String, challenge: String, origin: String): Array[Byte] =
    Encoding.utf8(Json.obj(
      "type" -> Json.fromString(kind),
      "challenge" -> Json.fromString(challenge),
      "origin" -> Json.fromString(origin),
      "crossOrigin" -> Json.False
    ).noSpaces)

  def register(rpId: String, challenge: String, origin: String): String =
    val data = authenticatorData(rpId, attested = true)
    val attestation = new ByteArrayOutputStream()
    Cbor.map(attestation, 3)
    Cbor.text(attestation, "fmt"); Cbor.text(attestation, "none")
    Cbor.text(attestation, "attStmt"); Cbor.map(attestation, 0)
    Cbor.text(attestation, "authData"); Cbor.bytes(attestation, data)
    Json.obj(
      "id" -> Json.fromString(Encoding.b64(credentialId)),
      "rawId" -> Json.fromString(Encoding.b64(credentialId)),
      "type" -> Json.fromString("public-key"),
      "clientExtensionResults" -> Json.obj(),
      "response" -> Json.obj(
        "clientDataJSON" -> Json.fromString(
          Encoding.b64(clientData("webauthn.create", challenge, origin))),
        "attestationObject" -> Json.fromString(Encoding.b64(attestation.toByteArray)),
        "transports" -> Json.arr(Json.fromString("internal"))
      )
    ).noSpaces

  def authenticate(rpId: String, challenge: String, origin: String, userHandle: Array[Byte]): String =
    val data = authenticatorData(rpId, attested = false)
    val client = clientData("webauthn.get", challenge, origin)
    val signature = der(key.sign(data ++ Hash.sha256(client)))
    Json.obj(
      "id" -> Json.fromString(Encoding.b64(credentialId)),
      "rawId" -> Json.fromString(Encoding.b64(credentialId)),
      "type" -> Json.fromString("public-key"),
      "clientExtensionResults" -> Json.obj(),
      "response" -> Json.obj(
        "clientDataJSON" -> Json.fromString(Encoding.b64(client)),
        "authenticatorData" -> Json.fromString(Encoding.b64(data)),
        "signature" -> Json.fromString(Encoding.b64(signature)),
        "userHandle" -> Json.fromString(Encoding.b64(userHandle))
      )
    ).noSpaces

  /** WebAuthn carries DER-encoded ECDSA, not the raw r||s the repository uses. */
  private def der(raw: Array[Byte]): Array[Byte] =
    def integer(value: Array[Byte]): Array[Byte] =
      val trimmed = value.dropWhile(_ == 0)
      val body = if trimmed.isEmpty then Array(0.toByte)
        else if (trimmed.head & 0x80) != 0 then Array(0.toByte) ++ trimmed
        else trimmed
      Array(0x02.toByte, body.length.toByte) ++ body
    val parts = integer(raw.take(32)) ++ integer(raw.drop(32))
    Array(0x30.toByte, parts.length.toByte) ++ parts

/** Just enough CBOR to build COSE keys and attestation objects. */
private object Cbor:
  def header(out: ByteArrayOutputStream, major: Int, value: Long): Unit =
    val base = major << 5
    if value < 24 then out.write(base | value.toInt)
    else if value <= 0xff then { out.write(base | 24); out.write(value.toInt) }
    else if value <= 0xffff then
      out.write(base | 25)
      out.write(((value >> 8) & 0xff).toInt)
      out.write((value & 0xff).toInt)
    else
      out.write(base | 26)
      (3 to 0 by -1).foreach(shift => out.write(((value >>> (shift * 8)) & 0xff).toInt))

  def int(out: ByteArrayOutputStream, value: Long): Unit =
    if value >= 0 then header(out, 0, value) else header(out, 1, -1 - value)

  def bytes(out: ByteArrayOutputStream, value: Array[Byte]): Unit =
    header(out, 2, value.length.toLong)
    out.write(value)

  def text(out: ByteArrayOutputStream, value: String): Unit =
    val encoded = Encoding.utf8(value)
    header(out, 3, encoded.length.toLong)
    out.write(encoded)

  def map(out: ByteArrayOutputStream, entries: Int): Unit = header(out, 5, entries.toLong)
