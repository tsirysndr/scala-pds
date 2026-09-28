package pds.crypto

import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

/** AES-256-GCM sealing for secrets stored in the database: signing keys, TOTP
  * secrets and PLC rotation keys. The nonce is prepended to the ciphertext and
  * the purpose string is authenticated, so a sealed value cannot be replayed
  * into a different role.
  */
final class Sealing(masterKey: Array[Byte]):
  require(masterKey.length == 32, "master key must be 32 bytes")

  private val key = new SecretKeySpec(masterKey, "AES")

  def seal(purpose: String, plaintext: Array[Byte]): Array[Byte] =
    val nonce = Hash.randomBytes(12)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce))
    cipher.updateAAD(Encoding.utf8(purpose))
    nonce ++ cipher.doFinal(plaintext)

  def open(purpose: String, sealed0: Array[Byte]): Option[Array[Byte]] =
    if sealed0.length <= 12 then None
    else
      try
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed0.take(12)))
        cipher.updateAAD(Encoding.utf8(purpose))
        Some(cipher.doFinal(sealed0.drop(12)))
      catch case _: javax.crypto.AEADBadTagException | _: javax.crypto.IllegalBlockSizeException => None

  def sealKey(private0: PrivateKey): Array[Byte] =
    seal(s"pds/signing-key/${private0.curve.name}", private0.bytes)

  def openKey(curve: Curve, sealed0: Array[Byte]): Option[PrivateKey] =
    open(s"pds/signing-key/${curve.name}", sealed0).flatMap(PrivateKey.fromBytes(curve, _))

  /** Deterministic keyed identifier, for CSRF binding and token digests. */
  def mac(purpose: String, message: String): String =
    Encoding.b64(Hash.hmacSha256(Hash.sha256(masterKey ++ Encoding.utf8(purpose)), Encoding.utf8(message)))

object Sealing:
  def fromBase64(value: String): Either[String, Sealing] =
    Encoding.unb64(value).filter(_.length == 32)
      .map(new Sealing(_))
      .toRight("PDS_MASTER_KEY must be 32 bytes encoded as unpadded base64url")

  def generate(): String = Encoding.b64(Hash.randomBytes(32))
