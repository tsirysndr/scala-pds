package pds.accounts

import io.circe.Json
import java.sql.Connection
import java.util.UUID
import pds.{Env, XrpcError}
import pds.crypto.{Hash, Passwords}
import pds.storage.Sql

final case class Tokens0(accessJwt: String, refreshJwt: String)

object Sessions:
  final case class Created(account: Account, tokens: Tokens0, credential: Credential)

  /** Authenticates a password or app password and issues a session pair. */
  def create(
      env: Env, connection: Connection, identifier: String, password: String, now: Long
  ): Created =
    if identifier.isEmpty || identifier.length > 2048 || password.isEmpty || password.length > 1024
    then throw XrpcError.authRequired("Invalid identifier or password")
    val account = Accounts.byIdentifier(connection, identifier)
    val appPassword = account.flatMap(found => matchAppPassword(connection, found.did, password))
    val credential = appPassword match
      case Some((_, true))  => Credential.PrivilegedAppPassword
      case Some((_, false)) => Credential.AppPassword
      case None =>
        if !Accounts.verifyPassword(account, password) then
          throw XrpcError.authRequired("Invalid identifier or password")
        Credential.Access
    val found = account.get
    if found.status == "taken_down" then
      throw XrpcError.named(org.http4s.Status.Forbidden, "AccountTakedown", "Account has been suspended")
    if found.status == "provisioning" then
      throw XrpcError.named(org.http4s.Status.BadRequest, "RegistrationPending",
        "Account registration is still being confirmed")
    val tokens = issue(env, connection, found, credential, appPassword.map(_._1), now)
    Created(found, tokens, credential)

  private def matchAppPassword(
      connection: Connection, did: String, password: String
  ): Option[(String, Boolean)] =
    Sql.query(connection,
      "SELECT id, password_digest, privileged FROM app_passwords WHERE did = ?", did)(row =>
      (row.string("id"), row.string("password_digest"), row.bool("privileged"))
    ).collectFirst {
      case (id, digest, privileged) if Passwords.matches(password, digest) => (id, privileged)
    }

  def issue(
      env: Env,
      connection: Connection,
      account: Account,
      credential: Credential,
      appPasswordId: Option[String],
      now: Long
  ): Tokens0 =
    val id = UUID.randomUUID().toString
    val refresh = Tokens.refresh(env, account.did, id, account.securityEpoch, now)
    Sql.update(connection,
      """INSERT INTO sessions(id, did, refresh_hash, app_password_id, privileged, security_epoch,
         created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
      id, account.did, Hash.digestToken(refresh), appPasswordId,
      credential != Credential.AppPassword, account.securityEpoch, now,
      now + Tokens.refreshSeconds * 1000)
    Tokens0(Tokens.access(env, account.did, credential, account.securityEpoch, now), refresh)

  /** Rotates a refresh token, refusing replay of an already-rotated token. */
  def refresh(env: Env, connection: Connection, token: String, now: Long): Created =
    val session = Tokens.verify(env, token, now).fold(throw _, identity)
    if session.credential != Credential.Refresh then
      throw XrpcError.authRequired("A refresh token is required")
    val id = session.sessionId.getOrElse(throw XrpcError.authRequired("Token has no session"))
    val row = Sql.first(connection,
      """SELECT refresh_hash, revoked, expires_at, app_password_id, privileged
         FROM sessions WHERE id = ? AND did = ?""", id, session.did)(row =>
      (row.string("refresh_hash"), row.bool("revoked"), row.long("expires_at"),
        row.stringOpt("app_password_id"), row.bool("privileged"))
    ).getOrElse(throw XrpcError.authRequired("Session is no longer valid"))
    if row._2 || row._3 <= now || !Hash.constantTimeEquals(row._1, Hash.digestToken(token)) then
      throw XrpcError.authRequired("Session is no longer valid")
    val account = Accounts.requireActive(connection, session.did)
    if account.securityEpoch != session.securityEpoch then
      throw XrpcError.authRequired("Credentials were revoked; sign in again")
    Sql.update(connection, "DELETE FROM sessions WHERE id = ?", id)
    val credential = (row._4, row._5) match
      case (None, _)        => Credential.Access
      case (Some(_), true)  => Credential.PrivilegedAppPassword
      case (Some(_), false) => Credential.AppPassword
    Created(account, issue(env, connection, account, credential, row._4, now), credential)

  def delete(env: Env, connection: Connection, token: String, now: Long): Unit =
    val session = Tokens.verify(env, token, now).fold(throw _, identity)
    if session.credential != Credential.Refresh then
      throw XrpcError.authRequired("A refresh token is required")
    session.sessionId.foreach(id =>
      Sql.update(connection, "DELETE FROM sessions WHERE id = ? AND did = ?", id, session.did))

  def describe(env: Env, account: Account, credential: Credential): Json =
    Json.obj(
      "handle" -> Json.fromString(account.handle),
      "did" -> Json.fromString(account.did),
      "email" -> account.email.map(Json.fromString).getOrElse(Json.Null),
      "emailConfirmed" -> Json.fromBoolean(account.emailConfirmed),
      "emailAuthFactor" -> Json.fromBoolean(account.emailAuthFactor),
      "active" -> Json.fromBoolean(account.active),
      "status" -> (if account.active then Json.Null
        else Json.fromString(if account.status == "taken_down" then "takendown" else "deactivated")),
      "didDoc" -> Json.Null
    ).deepDropNullValues
