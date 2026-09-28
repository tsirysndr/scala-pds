package pds.crypto

import java.security.{MessageDigest, SecureRandom}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hash:
  private val random = new SecureRandom()

  def sha256(bytes: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(bytes)

  def sha256(value: String): Array[Byte] = sha256(Encoding.utf8(value))

  def sha512(bytes: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-512").digest(bytes)

  def hmacSha256(key: Array[Byte], message: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(message)

  def constantTimeEquals(a: Array[Byte], b: Array[Byte]): Boolean =
    MessageDigest.isEqual(a, b)

  def constantTimeEquals(a: String, b: String): Boolean =
    constantTimeEquals(Encoding.utf8(a), Encoding.utf8(b))

  def randomBytes(count: Int): Array[Byte] =
    val bytes = new Array[Byte](count)
    random.nextBytes(bytes)
    bytes

  def token(): String = Encoding.b64(randomBytes(32))

  def digestToken(token: String): String = Encoding.b64(sha256(token))

  def isToken(value: String): Boolean =
    value.length == 43 && value.forall(c => c.isLetterOrDigit || c == '-' || c == '_')

  def randomBase32(bytes: Int): String = Encoding.base32(randomBytes(bytes))
