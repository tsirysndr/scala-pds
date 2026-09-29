package pds.tools

import cats.effect.IO
import java.sql.Connection
import pds.crypto.{Curve, Sealing}
import pds.storage.{Database, Sql}

/** Re-encrypts every sealed secret under a new master key.
  *
  * This runs offline, in one transaction: either every value is readable under
  * the old key and rewritten under the new one, or nothing changes. Values that
  * are merely derived from the key — session tokens, CSRF tokens, DPoP nonces —
  * are not stored, so they are simply invalidated by the change.
  */
object MasterKeyRotation:
  final case class Summary(signingKeys: Int, rotationKeys: Int, authenticators: Int):
    def total: Int = signingKeys + rotationKeys + authenticators

  final case class Failed(reason: String) extends RuntimeException(reason)

  def run(database: Database, current: Sealing, next: Sealing): IO[Summary] =
    database.transact { connection =>
      val signing = rotateSigningKeys(connection, current, next)
      val rotation = rotateRotationKeys(connection, current, next)
      val authenticators = rotateAuthenticators(connection, current, next)
      Summary(signing, rotation, authenticators)
    }

  private def rotateSigningKeys(connection: Connection, current: Sealing, next: Sealing): Int =
    val rows = Sql.query(connection,
      "SELECT did, signing_curve, signing_sealed FROM account_keys")(row =>
      (row.string("did"), row.string("signing_curve"), row.bytes("signing_sealed")))
    rows.foreach { (did, curveName, sealed0) =>
      val curve = Curve.values.find(_.name == curveName)
        .getOrElse(throw Failed(s"$did has an unknown signing curve $curveName"))
      val key = current.openKey(curve, sealed0)
        .getOrElse(throw Failed(s"$did: the signing key does not open with the current master key"))
      Sql.update(connection, "UPDATE account_keys SET signing_sealed = ? WHERE did = ?",
        next.sealKey(key), did)
    }
    rows.length

  private def rotateRotationKeys(connection: Connection, current: Sealing, next: Sealing): Int =
    val rows = Sql.query(connection,
      "SELECT did, rotation_sealed FROM account_keys WHERE rotation_sealed IS NOT NULL")(row =>
      (row.string("did"), row.bytes("rotation_sealed")))
    rows.foreach { (did, sealed0) =>
      val key = current.openKey(Curve.K256, sealed0)
        .getOrElse(throw Failed(s"$did: the rotation key does not open with the current master key"))
      Sql.update(connection, "UPDATE account_keys SET rotation_sealed = ? WHERE did = ?",
        next.sealKey(key), did)
    }
    rows.length

  private def rotateAuthenticators(connection: Connection, current: Sealing, next: Sealing): Int =
    val rows = Sql.query(connection, "SELECT did, sealed_secret FROM account_totp")(row =>
      (row.string("did"), row.bytes("sealed_secret")))
    rows.foreach { (did, sealed0) =>
      val secret = current.open("pds/totp", sealed0)
        .getOrElse(throw Failed(s"$did: the authenticator secret does not open with the current master key"))
      Sql.update(connection, "UPDATE account_totp SET sealed_secret = ? WHERE did = ?",
        next.seal("pds/totp", secret), did)
    }
    rows.length

  /** Confirms every sealed value opens under `key` without writing anything. */
  def verify(database: Database, key: Sealing): IO[Summary] =
    database.read { connection =>
      val signing = Sql.query(connection,
        "SELECT did, signing_curve, signing_sealed FROM account_keys")(row =>
        (row.string("did"), row.string("signing_curve"), row.bytes("signing_sealed")))
      signing.foreach { (did, curveName, sealed0) =>
        val curve = Curve.values.find(_.name == curveName)
          .getOrElse(throw Failed(s"$did has an unknown signing curve $curveName"))
        if key.openKey(curve, sealed0).isEmpty then
          throw Failed(s"$did: the signing key does not open with this master key")
      }
      val rotation = Sql.query(connection,
        "SELECT did, rotation_sealed FROM account_keys WHERE rotation_sealed IS NOT NULL")(row =>
        (row.string("did"), row.bytes("rotation_sealed")))
      rotation.foreach { (did, sealed0) =>
        if key.openKey(Curve.K256, sealed0).isEmpty then
          throw Failed(s"$did: the rotation key does not open with this master key")
      }
      val authenticators = Sql.query(connection, "SELECT did, sealed_secret FROM account_totp")(
        row => (row.string("did"), row.bytes("sealed_secret")))
      authenticators.foreach { (did, sealed0) =>
        if key.open("pds/totp", sealed0).isEmpty then
          throw Failed(s"$did: the authenticator secret does not open with this master key")
      }
      Summary(signing.length, rotation.length, authenticators.length)
    }
