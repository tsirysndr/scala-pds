package pds.accounts

import io.circe.Json
import java.sql.Connection
import pds.{Env, XrpcError}
import pds.crypto.{Encoding, Hash, Jwt}
import pds.storage.Sql

enum Credential:
  case Access, Refresh, AppPassword, PrivilegedAppPassword

  def scope: String = this match
    case Access                 => "com.atproto.access"
    case Refresh                => "com.atproto.refresh"
    case AppPassword            => "com.atproto.appPass"
    case PrivilegedAppPassword  => "com.atproto.appPassPrivileged"

object Credential:
  def fromScope(value: String): Option[Credential] = values.find(_.scope == value)

final case class Session(
    did: String,
    credential: Credential,
    sessionId: Option[String],
    securityEpoch: Long,
    oauthScope: Option[String] = None
):
  def privileged: Boolean =
    credential == Credential.Access || credential == Credential.PrivilegedAppPassword ||
      oauthScope.exists(scope => scope.split(" ").contains("transition:generic"))

  def appPassword: Boolean =
    credential == Credential.AppPassword || credential == Credential.PrivilegedAppPassword

object Tokens:
  val accessSeconds = 2 * 60 * 60L
  val refreshSeconds = 90 * 24 * 60 * 60L

  private def key(env: Env): Array[Byte] = Hash.sha256(env.sealing.mac("pds/session/v1", "key"))

  def access(env: Env, did: String, credential: Credential, epoch: Long, now: Long): String =
    Jwt.signHs256(key(env), Json.obj(
      "scope" -> Json.fromString(credential.scope),
      "sub" -> Json.fromString(did),
      "aud" -> Json.fromString(env.config.serviceDid),
      "epoch" -> Json.fromLong(epoch),
      "iat" -> Json.fromLong(now / 1000),
      "exp" -> Json.fromLong(now / 1000 + accessSeconds),
      "jti" -> Json.fromString(Hash.token())
    ), "at+jwt")

  def refresh(env: Env, did: String, sessionId: String, epoch: Long, now: Long): String =
    Jwt.signHs256(key(env), Json.obj(
      "scope" -> Json.fromString(Credential.Refresh.scope),
      "sub" -> Json.fromString(did),
      "aud" -> Json.fromString(env.config.serviceDid),
      "epoch" -> Json.fromLong(epoch),
      "jti" -> Json.fromString(sessionId),
      "iat" -> Json.fromLong(now / 1000),
      "exp" -> Json.fromLong(now / 1000 + refreshSeconds)
    ), "refresh+jwt")

  /** Verifies structure, audience and expiry. Account state is checked by the
    * caller inside the request transaction.
    */
  def verify(env: Env, token: String, now: Long): Either[XrpcError, Session] =
    for
      jwt <- Jwt.verifyHs256(key(env), token).left.map(_ => XrpcError.authRequired("Invalid token"))
      scope <- jwt.claim("scope").flatMap(Credential.fromScope)
        .toRight(XrpcError.authRequired("Token has no usable scope"))
      did <- jwt.claim("sub").filter(pds.protocol.Syntax.isDid)
        .toRight(XrpcError.authRequired("Token has no subject"))
      audience <- jwt.claim("aud").toRight(XrpcError.authRequired("Token has no audience"))
      _ <- Either.cond(audience == env.config.serviceDid, (),
        XrpcError.authRequired("Token was issued for another service"))
      expiry <- jwt.numeric("exp").toRight(XrpcError.authRequired("Token has no expiry"))
      _ <- Either.cond(expiry * 1000 > now, (), XrpcError.expiredToken())
      epoch = jwt.numeric("epoch").getOrElse(0L)
    yield Session(did, scope, jwt.claim("jti").filter(_ => scope == Credential.Refresh), epoch)

  /** Confirms the token still matches live account and session state. */
  def confirm(connection: Connection, session: Session): Account =
    val account = Accounts.requireActive(connection, session.did)
    if account.securityEpoch != session.securityEpoch then
      throw XrpcError.authRequired("Credentials were revoked; sign in again")
    session.sessionId.foreach { id =>
      val live = Sql.first(connection,
        "SELECT revoked, expires_at FROM sessions WHERE id = ? AND did = ?", id, session.did)(row =>
        (row.bool("revoked"), row.long("expires_at")))
      live match
        case Some((false, expires)) if expires > System.currentTimeMillis() => ()
        case _ => throw XrpcError.authRequired("Session is no longer valid")
    }
    account
