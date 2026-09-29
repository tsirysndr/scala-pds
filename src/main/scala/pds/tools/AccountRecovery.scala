package pds.tools

import cats.effect.IO
import io.circe.Json
import pds.Env
import pds.accounts.Accounts
import pds.storage.Sql

/** Operator recovery for an account locked out of its second factors.
  *
  * Removing a factor is a security-relevant act, so it is recorded: what was
  * removed, the epoch it happened at, and an operator-supplied reference. The
  * account's password is left alone — this restores access to whoever knows it,
  * it does not hand the account to the operator.
  */
object AccountRecovery:
  final case class Summary(
      did: String,
      handle: String,
      authenticator: Boolean,
      recoveryCodes: Int,
      passkeys: Int,
      epoch: Long
  ):
    def removedAnything: Boolean = authenticator || recoveryCodes > 0 || passkeys > 0

  final case class Failed(reason: String) extends RuntimeException(reason)

  def run(env: Env, identifier: String, reference: String): IO[Summary] =
    if reference.trim.isEmpty || reference.length > 128 then
      IO.raiseError(Failed("A reference of 1 to 128 characters is required"))
    else
      env.now.flatMap { now =>
        env.database.transact { connection =>
          val account = Accounts.byIdentifier(connection, identifier)
            .getOrElse(throw Failed(s"No account matches $identifier"))
          val previous = account.securityEpoch

          val authenticator = Sql.exists(connection,
            "SELECT 1 FROM account_totp WHERE did = ?", account.did)
          val codes = Sql.count(connection,
            "SELECT COUNT(*) AS total FROM account_recovery_codes WHERE did = ?",
            account.did).toInt
          val passkeys = Sql.count(connection,
            "SELECT COUNT(*) AS total FROM account_passkeys WHERE did = ?", account.did).toInt

          Sql.update(connection, "DELETE FROM account_totp WHERE did = ?", account.did)
          Sql.update(connection, "DELETE FROM account_recovery_codes WHERE did = ?", account.did)
          Sql.update(connection, "DELETE FROM account_passkeys WHERE did = ?", account.did)
          Sql.update(connection, "DELETE FROM account_webauthn_users WHERE did = ?", account.did)
          Sql.update(connection,
            "UPDATE accounts SET email_auth_factor = false WHERE did = ?", account.did)
          Sql.update(connection,
            "DELETE FROM account_tokens WHERE did = ? AND purpose = 'sign-in'", account.did)

          val epoch = Accounts.bumpSecurityEpoch(connection, account.did)

          Sql.update(connection,
            """INSERT INTO account_recoveries(did, previous_epoch, security_epoch, removed,
               reference, completed_at) VALUES (?, ?, ?, ?, ?, ?)""",
            account.did, previous, epoch,
            Json.obj(
              "authenticator" -> Json.fromBoolean(authenticator),
              "recoveryCodes" -> Json.fromInt(codes),
              "passkeys" -> Json.fromInt(passkeys),
              "emailFactor" -> Json.fromBoolean(account.emailAuthFactor)
            ).noSpaces,
            reference.trim, now)

          Summary(account.did, account.handle, authenticator, codes, passkeys, epoch)
        }
      }

  def history(env: Env, did: String): IO[Vector[Json]] =
    env.database.read { connection =>
      Sql.query(connection,
        """SELECT previous_epoch, security_epoch, removed, reference, completed_at
           FROM account_recoveries WHERE did = ? ORDER BY previous_epoch""", did)(row =>
        Json.obj(
          "previousEpoch" -> Json.fromLong(row.long("previous_epoch")),
          "securityEpoch" -> Json.fromLong(row.long("security_epoch")),
          "removed" -> io.circe.parser.parse(row.string("removed")).getOrElse(Json.obj()),
          "reference" -> Json.fromString(row.string("reference")),
          "completedAt" -> Json.fromString(pds.protocol.Syntax.datetime(
            java.time.Instant.ofEpochMilli(row.long("completed_at"))))
        ))
    }
