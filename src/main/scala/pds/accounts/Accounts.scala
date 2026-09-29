package pds.accounts

import java.sql.Connection
import java.util.Locale
import pds.XrpcError
import pds.crypto.Passwords
import pds.protocol.Syntax
import pds.storage.{Sql, Param}

final case class Account(
    did: String,
    handle: String,
    email: Option[String],
    passwordHash: Option[String],
    emailConfirmed: Boolean,
    emailAuthFactor: Boolean,
    status: String,
    statusBeforeTakedown: String,
    takedownRef: Option[String],
    securityEpoch: Long,
    invitesDisabled: Boolean,
    deactivatedAt: Option[Long],
    deleteAfter: Option[Long],
    createdAt: Long
):
  def active: Boolean = status == "active"

object Accounts:
  private val columns =
    """did, handle, email, password_hash, email_confirmed, email_auth_factor, status,
       status_before_takedown, takedown_ref, security_epoch, invites_disabled,
       deactivated_at, delete_after, created_at"""

  private def read(row: pds.storage.Row): Account = Account(
    did = row.string("did"),
    handle = row.string("handle"),
    email = row.stringOpt("email"),
    passwordHash = row.stringOpt("password_hash"),
    emailConfirmed = row.bool("email_confirmed"),
    emailAuthFactor = row.bool("email_auth_factor"),
    status = row.string("status"),
    statusBeforeTakedown = row.string("status_before_takedown"),
    takedownRef = row.stringOpt("takedown_ref"),
    securityEpoch = row.long("security_epoch"),
    invitesDisabled = row.bool("invites_disabled"),
    deactivatedAt = row.longOpt("deactivated_at"),
    deleteAfter = row.longOpt("delete_after"),
    createdAt = row.long("created_at")
  )

  def byDid(connection: Connection, did: String): Option[Account] =
    Sql.first(connection, s"SELECT $columns FROM accounts WHERE did = ?", did)(read)

  def byHandle(connection: Connection, handle: String): Option[Account] =
    Sql.first(connection, s"SELECT $columns FROM accounts WHERE handle = ?",
      Syntax.normalizeHandle(handle))(read)

  def byEmail(connection: Connection, email: String): Option[Account] =
    Sql.first(connection, s"SELECT $columns FROM accounts WHERE email = ?",
      email.toLowerCase(Locale.ROOT))(read)

  def byIdentifier(connection: Connection, identifier: String): Option[Account] =
    val normalized = identifier.toLowerCase(Locale.ROOT)
    Sql.first(connection,
      s"SELECT $columns FROM accounts WHERE did = ? OR handle = ? OR email = ?",
      normalized, normalized, normalized)(read)

  def require(connection: Connection, did: String): Account =
    byDid(connection, did).getOrElse(throw XrpcError.notFound("Account was not found"))

  def requireActive(connection: Connection, did: String): Account =
    val account = require(connection, did)
    if account.status == "taken_down" then
      throw XrpcError.named(org.http4s.Status.Forbidden, "AccountTakedown",
        "Account has been suspended")
    else if account.status == "deactivated" then
      throw XrpcError.named(org.http4s.Status.BadRequest, "AccountDeactivated",
        "Account is deactivated")
    else if !account.active then throw XrpcError.notFound("Account is not available")
    else account

  /** Password verification that costs the same for unknown accounts. */
  def verifyPassword(account: Option[Account], password: String): Boolean =
    val stored = account.flatMap(_.passwordHash).getOrElse(Passwords.dummy)
    Passwords.matches(password, stored) && account.exists(_.passwordHash.isDefined)

  def insert(
      connection: Connection,
      did: String,
      handle: String,
      email: Option[String],
      passwordHash: Option[String],
      status: String,
      now: Long
  ): Unit =
    Sql.update(connection,
      """INSERT INTO accounts(did, handle, email, password_hash, status, created_at)
         VALUES (?, ?, ?, ?, ?, ?)""",
      did, Syntax.normalizeHandle(handle), email.map(_.toLowerCase(Locale.ROOT)),
      passwordHash, status, now)

  def setStatus(connection: Connection, did: String, status: String): Unit =
    Sql.update(connection, "UPDATE accounts SET status = ? WHERE did = ?", status, did)

  def setHandle(connection: Connection, did: String, handle: String): Unit =
    Sql.update(connection, "UPDATE accounts SET handle = ? WHERE did = ?",
      Syntax.normalizeHandle(handle), did)

  def setPassword(connection: Connection, did: String, password: String): Unit =
    Sql.update(connection, "UPDATE accounts SET password_hash = ? WHERE did = ?",
      Passwords.hash(password), did)

  def setEmail(connection: Connection, did: String, email: String, confirmed: Boolean): Unit =
    Sql.update(connection,
      "UPDATE accounts SET email = ?, email_confirmed = ? WHERE did = ?",
      email.toLowerCase(Locale.ROOT), confirmed, did)

  /** Raising the epoch invalidates every issued token for the account. */
  def bumpSecurityEpoch(connection: Connection, did: String): Long =
    Sql.update(connection,
      "UPDATE accounts SET security_epoch = security_epoch + 1 WHERE did = ?", did)
    Sql.update(connection, "UPDATE sessions SET revoked = true WHERE did = ?", did)
    Sql.update(connection, "UPDATE oauth_tokens SET revoked = true WHERE did = ?", did)
    require(connection, did).securityEpoch

  def deactivate(connection: Connection, did: String, deleteAfter: Option[Long], now: Long): Unit =
    Sql.update(connection,
      "UPDATE accounts SET status = 'deactivated', deactivated_at = ?, delete_after = ? WHERE did = ?",
      now, deleteAfter, did)

  def activate(connection: Connection, did: String): Unit =
    Sql.update(connection,
      """UPDATE accounts SET status = 'active', deactivated_at = NULL, delete_after = NULL
         WHERE did = ? AND status = 'deactivated'""", did)

  def takedown(connection: Connection, did: String, reference: Option[String]): Unit =
    reference match
      case Some(value) =>
        Sql.update(connection,
          """UPDATE accounts SET status_before_takedown = status, status = 'taken_down',
             takedown_ref = ? WHERE did = ? AND status <> 'taken_down'""", value, did)
      case None =>
        Sql.update(connection,
          """UPDATE accounts SET status = status_before_takedown, takedown_ref = NULL
             WHERE did = ? AND status = 'taken_down'""", did)

  def delete(connection: Connection, did: String, now: Long): Unit =
    pds.repo.BlobStore.forget(connection, did, now)
    Sql.update(connection, "DELETE FROM repo_blocks WHERE did = ?", did)
    Sql.update(connection, "DELETE FROM blobs WHERE did = ?", did)
    Sql.update(connection, "DELETE FROM accounts WHERE did = ?", did)

  def list(connection: Connection, limit: Int, cursor: Option[String]): Vector[Account] =
    Sql.query(connection,
      s"""SELECT $columns FROM accounts WHERE did > ? AND status <> 'deleted'
          ORDER BY did LIMIT ?""",
      cursor.getOrElse(""), limit)(read)

  def handleAvailable(connection: Connection, handle: String): Boolean =
    val normalized = Syntax.normalizeHandle(handle)
    !Sql.exists(connection, "SELECT 1 FROM accounts WHERE handle = ?", normalized) &&
      !Sql.exists(connection, "SELECT 1 FROM handle_reservations WHERE handle = ?", normalized)
