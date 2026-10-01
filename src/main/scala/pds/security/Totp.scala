package pds.security

import java.sql.Connection
import pds.{Env, XrpcError}
import pds.crypto.{Encoding, Hash}
import pds.storage.Sql

/** RFC 6238 authenticator factor with single-use recovery codes. */
object Totp:
  private val step = 30L
  private val digits = 1000000
  private val enrollmentSeconds = 600L
  private val maxAttempts = 5
  private val attemptWindowSeconds = 300L

  private val base32Upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

  def secret(): Array[Byte] = Hash.randomBytes(20)

  def render(secret: Array[Byte]): String =
    Encoding.base32(secret).toUpperCase.filter(base32Upper.contains)

  def uri(issuer: String, handle: String, secret: Array[Byte]): String =
    val label = java.net.URLEncoder.encode(s"$issuer:$handle", "UTF-8")
    s"otpauth://totp/$label?secret=${render(secret)}&issuer=" +
      java.net.URLEncoder.encode(issuer, "UTF-8") + "&algorithm=SHA1&digits=6&period=30"

  def code(secret: Array[Byte], counter: Long): String =
    val message = (7 to 0 by -1).map(shift => ((counter >>> (shift * 8)) & 0xff).toByte).toArray
    val mac = javax.crypto.Mac.getInstance("HmacSHA1")
    mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA1"))
    val digest = mac.doFinal(message)
    val offset = digest(digest.length - 1) & 0x0f
    val binary = ((digest(offset) & 0x7f) << 24) | ((digest(offset + 1) & 0xff) << 16) |
      ((digest(offset + 2) & 0xff) << 8) | (digest(offset + 3) & 0xff)
    f"${binary % digits}%06d"

  def matches(secret: Array[Byte], supplied: String, now: Long): Option[Long] =
    val counter = now / 1000 / step
    Vector(counter - 1, counter, counter + 1)
      .find(candidate => Hash.constantTimeEquals(code(secret, candidate), supplied))

  def enabled(connection: Connection, did: String): Boolean =
    Sql.exists(connection, "SELECT 1 FROM account_totp WHERE did = ? AND confirmed = true", did)

  final case class Enrollment(secret: String, uri: String)

  def begin(env: Env, connection: Connection, did: String, handle: String, now: Long): Enrollment =
    if enabled(connection, did) then
      throw XrpcError.named(org.http4s.Status.Conflict, "TotpAlreadyEnabled",
        "An authenticator is already enabled")
    val value = secret()
    Sql.update(connection, "DELETE FROM account_totp WHERE did = ?", did)
    Sql.update(connection,
      """INSERT INTO account_totp(did, sealed_secret, enrollment_expires_at, attempt_window, created_at)
         VALUES (?, ?, ?, ?, ?)""",
      did, env.sealing.seal("pds/totp", value), now + enrollmentSeconds * 1000, now, now)
    Enrollment(render(value), uri(env.config.hostname, handle, value))

  def confirm(env: Env, connection: Connection, did: String, supplied: String, now: Long): Vector[String] =
    val row = Sql.first(connection,
      "SELECT sealed_secret, confirmed, enrollment_expires_at FROM account_totp WHERE did = ?", did)(
      row => (row.bytes("sealed_secret"), row.bool("confirmed"), row.long("enrollment_expires_at")))
      .getOrElse(throw XrpcError.invalidRequest("Start authenticator enrollment first"))
    if row._2 then throw XrpcError.named(org.http4s.Status.Conflict, "TotpAlreadyEnabled",
      "An authenticator is already enabled")
    if row._3 <= now then
      Sql.update(connection, "DELETE FROM account_totp WHERE did = ?", did)
      throw XrpcError.named(org.http4s.Status.BadRequest, "InvalidToken", "Enrollment expired")
    val secretValue = env.sealing.open("pds/totp", row._1)
      .getOrElse(throw XrpcError.internal("Authenticator secret cannot be opened"))
    val counter = matches(secretValue, supplied.trim, now)
      .getOrElse(throw XrpcError.named(org.http4s.Status.BadRequest, "InvalidToken",
        "That code is not valid"))
    Sql.update(connection,
      "UPDATE account_totp SET confirmed = true, last_step = ? WHERE did = ?", counter, did)
    issueRecoveryCodes(connection, did)

  /** Verifies a code or a recovery code, rate-limited and replay-protected. */
  def verify(env: Env, connection: Connection, did: String, supplied: String, now: Long): Unit =
    val row = Sql.first(connection,
      """SELECT sealed_secret, last_step, failed_attempts, attempt_window
         FROM account_totp WHERE did = ? AND confirmed = true""", did)(row =>
      (row.bytes("sealed_secret"), row.longOpt("last_step"), row.int("failed_attempts"),
        row.long("attempt_window")))
      .getOrElse(throw XrpcError.authRequired("No authenticator is enrolled"))
    val windowOpen = row._4 + attemptWindowSeconds * 1000 > now
    if windowOpen && row._3 >= maxAttempts then throw XrpcError.rateLimited("Too many attempts")
    val attempts = if windowOpen then row._3 else 0
    val window = if windowOpen then row._4 else now
    val code0 = supplied.trim.toUpperCase
    val recovery = Sql.update(connection,
      "DELETE FROM account_recovery_codes WHERE did = ? AND code_hash = ?",
      did, Hash.digestToken(code0)) > 0
    val secretValue = env.sealing.open("pds/totp", row._1)
      .getOrElse(throw XrpcError.internal("Authenticator secret cannot be opened"))
    val counter = if recovery then None else matches(secretValue, code0, now)
    if recovery then
      Sql.update(connection,
        "UPDATE account_totp SET failed_attempts = 0, attempt_window = ? WHERE did = ?", now, did)
    else counter match
      case Some(step0) if !row._2.contains(step0) && row._2.forall(_ < step0) =>
        Sql.update(connection,
          """UPDATE account_totp SET last_step = ?, failed_attempts = 0, attempt_window = ?
             WHERE did = ?""", step0, now, did)
      case _ =>
        Sql.update(connection,
          "UPDATE account_totp SET failed_attempts = ?, attempt_window = ? WHERE did = ?",
          attempts + 1, window, did)
        throw XrpcError.named(org.http4s.Status.BadRequest, "InvalidToken", "That code is not valid")

  def disable(env: Env, connection: Connection, did: String, supplied: String, now: Long): Unit =
    verify(env, connection, did, supplied, now)
    Sql.update(connection, "DELETE FROM account_totp WHERE did = ?", did)

  /** Issues a fresh set, discarding any that were outstanding. */
  private def issueRecoveryCodes(connection: Connection, did: String): Vector[String] =
    val codes = Vector.fill(8)(Hash.randomBase32(16).take(26).toUpperCase)
    Sql.update(connection, "DELETE FROM account_recovery_codes WHERE did = ?", did)
    codes.foreach(value =>
      Sql.update(connection,
        "INSERT INTO account_recovery_codes(did, code_hash) VALUES (?, ?)",
        did, Hash.digestToken(value)))
    codes

  /** Replaces the recovery codes. Requires a current proof for the same reason
    * disabling does: the codes are themselves a way past the factor.
    */
  def regenerate(
      env: Env, connection: Connection, did: String, supplied: String, now: Long
  ): Vector[String] =
    verify(env, connection, did, supplied, now)
    issueRecoveryCodes(connection, did)

  /** Reported to the owner. An unconfirmed enrollment is pending rather than
    * enabled: until it is confirmed the account still authenticates with its
    * password alone, so a half-finished enrollment locks nobody out.
    */
  def state(connection: Connection, did: String): String =
    Sql.first(connection, "SELECT confirmed FROM account_totp WHERE did = ?", did)(
      _.bool("confirmed")) match
      case Some(true)  => "enabled"
      case Some(false) => "pending"
      case None        => "disabled"

  def remaining(connection: Connection, did: String): Int =
    Sql.count(connection,
      "SELECT COUNT(*) AS total FROM account_recovery_codes WHERE did = ?", did).toInt
