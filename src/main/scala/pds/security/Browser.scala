package pds.security

import cats.effect.IO
import io.circe.Json
import java.sql.Connection
import pds.{Env, XrpcError}
import pds.accounts.*
import pds.crypto.Hash
import pds.storage.Sql

final case class BrowserSession(
    tokenHash: String,
    csrfNonce: String,
    did: Option[String],
    securityEpoch: Option[Long],
    authMethod: Option[String],
    authenticatedAt: Option[Long],
    expiresAt: Long
):
  def stage: String =
    if authenticatedAt.isDefined then "authenticated"
    else if did.isDefined then "factor"
    else "login"

final case class BrowserResult(token: Option[String], view: Json, result: Json, loggedOut: Boolean)

/** Short-lived owner sessions for the account UI. The absolute five-minute
  * lifetime means a settings page cannot outlive the sign-in that opened it.
  */
object Browser:
  val lifetimeSeconds = 300L

  private def invalid = XrpcError.named(org.http4s.Status.Unauthorized,
    "BrowserSessionRequired", "Sign in again to continue")

  def csrf(env: Env, token: String, nonce: String): String =
    env.sealing.mac("pds/security/csrf/v1", s"$nonce/$token")

  private def load(connection: Connection, token: String, now: Long): BrowserSession =
    if !Hash.isToken(token) then throw invalid
    val row = Sql.first(connection,
      """SELECT token_hash, csrf_nonce, did, security_epoch, auth_method, authenticated_at, expires_at
         FROM browser_sessions WHERE token_hash = ?""", Hash.digestToken(token))(row =>
      BrowserSession(row.string("token_hash"), row.string("csrf_nonce"), row.stringOpt("did"),
        row.longOpt("security_epoch"), row.stringOpt("auth_method"),
        row.longOpt("authenticated_at"), row.long("expires_at")))
      .getOrElse(throw invalid)
    if row.expiresAt <= now then throw invalid
    row.did.foreach { did =>
      val account = Accounts.byDid(connection, did).getOrElse(throw invalid)
      if !account.active || !row.securityEpoch.contains(account.securityEpoch) then throw invalid
    }
    row

  private def checkCsrf(env: Env, session: BrowserSession, token: String, supplied: Option[String]): Unit =
    val expected = csrf(env, token, session.csrfNonce)
    if !supplied.exists(value => Hash.constantTimeEquals(expected, value)) then
      throw XrpcError.named(org.http4s.Status.Forbidden, "InvalidCsrf",
        "Reload this page and try again")

  private def view(env: Env, connection: Connection, session: BrowserSession, token: String): Json =
    val account = session.did.flatMap(Accounts.byDid(connection, _))
    val base = Json.obj(
      "stage" -> Json.fromString(session.stage),
      "csrf" -> Json.fromString(csrf(env, token, session.csrfNonce))
    )
    val identified = account.fold(base)(found =>
      base.deepMerge(Json.obj(
        "handle" -> Json.fromString(found.handle),
        "factor" -> factor(connection, found).map(Json.fromString).getOrElse(Json.Null)
      )))
    if session.stage != "authenticated" then identified
    else
      identified.deepMerge(Json.obj(
        "appPasswords" -> Json.arr(AppPasswords.list(connection, session.did.get)*),
        "recoveryCodes" -> Json.fromInt(Totp.remaining(connection, session.did.get)),
        "oauthSessions" -> Json.arr(oauthSessions(connection, session.did.get)*)
      ))

  private def factor(connection: Connection, account: Account): Option[String] =
    if Totp.enabled(connection, account.did) then Some("totp")
    else if account.emailAuthFactor then Some("email")
    else None

  private def oauthSessions(connection: Connection, did: String): Vector[Json] =
    Sql.query(connection,
      """SELECT id, client_id, scope, created_at, refresh_expires_at FROM oauth_tokens
         WHERE did = ? AND revoked = false ORDER BY created_at DESC LIMIT 100""", did)(row =>
      Json.obj(
        "id" -> Json.fromString(row.string("id")),
        "clientId" -> Json.fromString(row.string("client_id")),
        "scope" -> Json.fromString(row.string("scope")),
        "permissions" -> Json.arr(
          Scope0.permissions(row.string("scope")).map(Json.fromString)*),
        "createdAt" -> Json.fromString(pds.protocol.Syntax.datetime(
          java.time.Instant.ofEpochMilli(row.long("created_at")))),
        "expiresAt" -> Json.fromString(pds.protocol.Syntax.datetime(
          java.time.Instant.ofEpochMilli(row.long("refresh_expires_at"))))
      ))

  private object Scope0:
    def permissions(scope: String): Vector[String] =
      pds.oauth.Scope.permissions(scope.split(" ").toVector.filter(_.nonEmpty))

  private def output(
      env: Env, connection: Connection, token: String, now: Long, result: Json = Json.obj()
  ): BrowserResult =
    val session = load(connection, token, now)
    BrowserResult(Some(token), view(env, connection, session, token), result, loggedOut = false)

  /** Opens or resumes an anonymous browser session. */
  def open(env: Env, token: Option[String]): IO[BrowserResult] =
    env.now.flatMap { now =>
      env.database.transact { connection =>
        val existing = token.filter(Hash.isToken).flatMap { value =>
          try Some(load(connection, value, now))
          catch case error: XrpcError if error.error == "BrowserSessionRequired" => None
        }
        existing match
          case Some(_) => output(env, connection, token.get, now)
          case None =>
            val fresh = Hash.token()
            Sql.update(connection, "DELETE FROM browser_sessions WHERE expires_at <= ?", now)
            Sql.update(connection,
              """INSERT INTO browser_sessions(token_hash, csrf_nonce, created_at, expires_at)
                 VALUES (?, ?, ?, ?)""",
              Hash.digestToken(fresh), Hash.token(), now, now + lifetimeSeconds * 1000)
            output(env, connection, fresh, now)
      }
    }

  private def rotate(
      env: Env,
      connection: Connection,
      session: BrowserSession,
      account: Account,
      method: String,
      authenticated: Boolean,
      now: Long,
      preserve: Boolean = false
  ): String =
    val fresh = Hash.token()
    Sql.update(connection, "DELETE FROM browser_sessions WHERE token_hash = ?", session.tokenHash)
    Sql.update(connection,
      """INSERT INTO browser_sessions(token_hash, csrf_nonce, did, security_epoch, auth_method,
         authenticated_at, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
      Hash.digestToken(fresh), Hash.token(), Some(account.did), Some(account.securityEpoch),
      Some(method),
      Option.when(authenticated)(
        if preserve then session.authenticatedAt.getOrElse(now) else now),
      now, if preserve then session.expiresAt else now + lifetimeSeconds * 1000)
    fresh

  def action(
      env: Env, token: Option[String], csrfToken: Option[String], name: String, body: Json
  ): IO[BrowserResult] =
    env.now.flatMap { now =>
      env.database.transact { connection =>
        val value = token.getOrElse(throw invalid)
        val session = load(connection, value, now)
        checkCsrf(env, session, value, csrfToken)
        name match
          case "logout" =>
            Sql.update(connection, "DELETE FROM browser_sessions WHERE token_hash = ?",
              session.tokenHash)
            BrowserResult(None, Json.obj("stage" -> Json.fromString("login")), Json.obj(), true)

          case "login/password" =>
            if session.did.isDefined then
              throw XrpcError.named(org.http4s.Status.BadRequest, "AlreadySignedIn",
                "Sign out before choosing another account")
            val identifier = pds.api.Xrpc.requireField(body, "identifier")
            val password = body.hcursor.get[String]("password").toOption.getOrElse("")
            val account = Accounts.byIdentifier(connection, identifier)
            if !Accounts.verifyPassword(account, password) || !account.exists(_.active) then
              throw XrpcError.authRequired("Invalid identifier or password")
            primary(env, connection, session, account.get, "password", now)

          case "login/factor" =>
            if session.did.isEmpty || session.authenticatedAt.isDefined then throw invalid
            val account = Accounts.require(connection, session.did.get)
            val code = pds.api.Xrpc.requireField(body, "code")
            factor(connection, account) match
              case Some("totp")  => Totp.verify(env, connection, account.did, code, now)
              case Some("email") =>
                Email.consume(connection, "sign-in", code, account.did,
                  account.email.getOrElse(""), now)
              case _ => throw invalid
            output(env, connection,
              rotate(env, connection, session, account, session.authMethod.getOrElse("password"),
                authenticated = true, now), now)

          case other => authenticated(env, connection, session, value, other, body, now)
      }
    }

  private def primary(
      env: Env, connection: Connection, session: BrowserSession, account: Account,
      method: String, now: Long
  ): BrowserResult =
    val required = factor(connection, account)
    if required.contains("email") then
      Email.requireEnabled(env)
      Email.issue(connection, account, "sign-in", now)
    output(env, connection,
      rotate(env, connection, session, account, method, authenticated = required.isEmpty, now), now)

  private def authenticated(
      env: Env, connection: Connection, session: BrowserSession, token: String,
      name: String, body: Json, now: Long
  ): BrowserResult =
    if session.authenticatedAt.isEmpty then throw invalid
    val did = session.did.get
    val result = name match
      case "app-passwords/create" =>
        AppPasswords.create(connection, did, pds.api.Xrpc.requireField(body, "name"),
          body.hcursor.get[Boolean]("privileged").getOrElse(false), now)
      case "app-passwords/revoke" =>
        AppPasswords.revoke(connection, did, pds.api.Xrpc.requireField(body, "name"))
        Json.obj("revoked" -> Json.True)
      case "oauth/revoke" =>
        val id = pds.api.Xrpc.requireField(body, "id")
        Sql.update(connection,
          "UPDATE oauth_tokens SET revoked = true WHERE id = ? AND did = ?", id, did)
        Json.obj("revoked" -> Json.True)
      case "totp/begin" =>
        val account = Accounts.require(connection, did)
        val enrollment = Totp.begin(env, connection, did, account.handle, now)
        Json.obj("secret" -> Json.fromString(enrollment.secret),
          "uri" -> Json.fromString(enrollment.uri))
      case "totp/confirm" =>
        Json.obj("recoveryCodes" -> Json.arr(
          Totp.confirm(env, connection, did, pds.api.Xrpc.requireField(body, "code"), now)
            .map(Json.fromString)*))
      case "totp/disable" =>
        Totp.disable(env, connection, did, pds.api.Xrpc.requireField(body, "code"), now)
        Json.obj("disabled" -> Json.True)
      case "email/enable" =>
        val account = Accounts.require(connection, did)
        if !account.emailConfirmed then
          throw XrpcError.invalidRequest("Confirm your email address first")
        Email.requireEnabled(env)
        Sql.update(connection, "UPDATE accounts SET email_auth_factor = true WHERE did = ?", did)
        Json.obj("enabled" -> Json.True)
      case "email/disable" =>
        Sql.update(connection, "UPDATE accounts SET email_auth_factor = false WHERE did = ?", did)
        Sql.update(connection,
          "DELETE FROM account_tokens WHERE did = ? AND purpose = 'sign-in'", did)
        Json.obj("disabled" -> Json.True)
      case "password/change" =>
        val current = pds.api.Xrpc.requireField(body, "currentPassword")
        val next = pds.api.Xrpc.requireField(body, "newPassword")
        if !Accounts.verifyPassword(Accounts.byDid(connection, did), current) then
          throw XrpcError.authRequired("Current password is incorrect")
        if next.length < 8 then
          throw XrpcError.named(org.http4s.Status.BadRequest, "InvalidPassword",
            "Passwords need at least 8 characters")
        Accounts.setPassword(connection, did, next)
        Accounts.bumpSecurityEpoch(connection, did)
        Json.obj("changed" -> Json.True)
      case _ => throw XrpcError.notFound("Unknown account action")
    val account = Accounts.require(connection, did)
    if account.securityEpoch != session.securityEpoch.getOrElse(-1L) then
      output(env, connection,
        rotate(env, connection, session, account, session.authMethod.getOrElse("password"),
          authenticated = true, now, preserve = true), now, result)
    else output(env, connection, token, now, result)

  /** Confirms a fully authenticated owner inside an existing transaction. */
  def owner(
      env: Env, connection: Connection, token: Option[String], csrfToken: Option[String], now: Long
  ): (String, Long, Long) =
    val value = token.getOrElse(throw invalid)
    val session = load(connection, value, now)
    checkCsrf(env, session, value, csrfToken)
    val authenticated = session.authenticatedAt.getOrElse(throw invalid)
    (session.did.get, session.securityEpoch.get, authenticated)

  def register(env: Env, token: Option[String], csrfToken: Option[String], body: Json): IO[BrowserResult] =
    for
      now <- env.now
      _ <- env.database.transact { connection =>
        val value = token.getOrElse(throw invalid)
        val session = load(connection, value, now)
        checkCsrf(env, session, value, csrfToken)
        if session.did.isDefined then
          throw XrpcError.named(org.http4s.Status.BadRequest, "AlreadySignedIn",
            "Sign out before creating another account")
      }
      created <- Register.run(env, Registration(
        handle = pds.api.Xrpc.requireField(body, "handle"),
        email = pds.api.Xrpc.field(body, "email"),
        password = pds.api.Xrpc.field(body, "password"),
        inviteCode = pds.api.Xrpc.field(body, "inviteCode"),
        did = None,
        recoveryKey = None,
        verificationCode = None
      ))
      result <- action(env, token, csrfToken, "login/password", Json.obj(
        "identifier" -> Json.fromString(created.account.did),
        "password" -> Json.fromString(pds.api.Xrpc.requireField(body, "password"))
      ))
    yield result
