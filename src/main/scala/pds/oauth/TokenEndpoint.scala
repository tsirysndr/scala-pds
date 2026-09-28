package pds.oauth

import cats.effect.IO
import io.circe.Json
import java.sql.Connection
import java.util.UUID
import org.http4s.{Request, Status}
import pds.{Env, XrpcError}
import pds.accounts.Accounts
import pds.crypto.{Encoding, Hash}
import pds.storage.Sql

object TokenEndpoint:
  val accessSeconds = 3600L
  val refreshSeconds = 90 * 24 * 3600L

  private def fail(error: String, message: String) =
    XrpcError.named(Status.BadRequest, error, message)

  def issue(env: Env, request: Request[IO], form: Map[String, String]): IO[Json] =
    for
      now <- env.now
      clientId <- IO.fromOption(form.get("client_id"))(
        fail("invalid_request", "client_id is required"))
      metadata <- Client.fetch(env, clientId)
      _ <- authenticate(env, metadata, form, now)
      proof <- Dpop.verify(env, request, None, now, requireNonce = true)
      result <- form.get("grant_type") match
        case Some("authorization_code") => authorizationCode(env, metadata, form, proof, now)
        case Some("refresh_token")      => refreshToken(env, metadata, form, proof, now)
        case _ => IO.raiseError(fail("unsupported_grant_type", "Unsupported grant type"))
    yield result

  private def authenticate(
      env: Env, metadata: ClientMetadata, form: Map[String, String], now: Long
  ): IO[Unit] =
    if metadata.authMethod == "none" then IO.unit
    else
      for
        assertion <- IO.fromOption(form.get("client_assertion"))(
          fail("invalid_client", "A client assertion is required"))
        _ <- IO.raiseUnless(form.get("client_assertion_type")
          .contains("urn:ietf:params:oauth:client-assertion-type:jwt-bearer"))(
          fail("invalid_client", "Unsupported client assertion type"))
        _ <- Client.verifyAssertion(env, metadata, assertion, env.config.publicUrl, now)
      yield ()

  private def authorizationCode(
      env: Env, metadata: ClientMetadata, form: Map[String, String], proof: Proof, now: Long
  ): IO[Json] =
    for
      code <- IO.fromOption(form.get("code").filter(Hash.isToken))(
        fail("invalid_grant", "The authorization code is not valid"))
      verifier <- IO.fromOption(form.get("code_verifier")
        .filter(value => value.length >= 43 && value.length <= 128))(
        fail("invalid_grant", "A PKCE code verifier is required"))
      redirect <- IO.fromOption(form.get("redirect_uri"))(
        fail("invalid_request", "redirect_uri is required"))
      result <- env.database.transact { connection =>
        Sql.update(connection, "DELETE FROM oauth_codes WHERE expires_at <= ?", now)
        val row = Sql.first(connection,
          """SELECT did, client_id, scope, redirect_uri, code_challenge, dpop_jkt, security_epoch,
             consumed, expires_at FROM oauth_codes WHERE code_hash = ?""",
          Hash.digestToken(code))(row =>
          (row.string("did"), row.string("client_id"), row.string("scope"),
            row.string("redirect_uri"), row.string("code_challenge"), row.stringOpt("dpop_jkt"),
            row.long("security_epoch"), row.bool("consumed"), row.long("expires_at")))
          .getOrElse(throw fail("invalid_grant", "The authorization code is not valid"))
        if row._8 || row._9 <= now then
          Sql.update(connection, "UPDATE oauth_tokens SET revoked = true WHERE did = ? AND client_id = ?",
            row._1, row._2)
          throw fail("invalid_grant", "The authorization code is not valid")
        Sql.update(connection, "UPDATE oauth_codes SET consumed = true WHERE code_hash = ?",
          Hash.digestToken(code))
        if row._2 != metadata.clientId then
          throw fail("invalid_grant", "The code was issued to another client")
        if row._4 != redirect then
          throw fail("invalid_grant", "The redirect URI does not match the authorization")
        if !Hash.constantTimeEquals(
          Encoding.b64(Hash.sha256(verifier)), row._5) then
          throw fail("invalid_grant", "The PKCE verifier does not match")
        row._6.foreach { bound =>
          if !Hash.constantTimeEquals(bound, proof.thumbprint) then
            throw fail("invalid_grant", "The request was bound to another DPoP key")
        }
        val account = Accounts.requireActive(connection, row._1)
        if account.securityEpoch != row._7 then
          throw fail("invalid_grant", "Credentials were revoked; authorize again")
        mint(env, connection, account.did, metadata.clientId, row._3, proof.thumbprint,
          account.securityEpoch, now)
      }
    yield result

  private def refreshToken(
      env: Env, metadata: ClientMetadata, form: Map[String, String], proof: Proof, now: Long
  ): IO[Json] =
    for
      token <- IO.fromOption(form.get("refresh_token").filter(Hash.isToken))(
        fail("invalid_grant", "The refresh token is not valid"))
      result <- env.database.transact { connection =>
        val row = Sql.first(connection,
          """SELECT id, did, client_id, scope, dpop_jkt, security_epoch, revoked, refresh_expires_at
             FROM oauth_tokens WHERE refresh_hash = ?""", Hash.digestToken(token))(row =>
          (row.string("id"), row.string("did"), row.string("client_id"), row.string("scope"),
            row.string("dpop_jkt"), row.long("security_epoch"), row.bool("revoked"),
            row.long("refresh_expires_at")))
          .getOrElse(throw fail("invalid_grant", "The refresh token is not valid"))
        if row._7 || row._8 <= now then
          throw fail("invalid_grant", "The refresh token is not valid")
        if row._3 != metadata.clientId then
          throw fail("invalid_grant", "The token was issued to another client")
        if !Hash.constantTimeEquals(row._5, proof.thumbprint) then
          throw fail("invalid_grant", "The token is bound to another DPoP key")
        val account = Accounts.requireActive(connection, row._2)
        if account.securityEpoch != row._6 then
          throw fail("invalid_grant", "Credentials were revoked; authorize again")
        Sql.update(connection, "DELETE FROM oauth_tokens WHERE id = ?", row._1)
        mint(env, connection, account.did, metadata.clientId, row._4, proof.thumbprint,
          account.securityEpoch, now)
      }
    yield result

  private def mint(
      env: Env,
      connection: Connection,
      did: String,
      clientId: String,
      scope: String,
      thumbprint: String,
      epoch: Long,
      now: Long
  ): Json =
    val access = Hash.token()
    val refresh = Hash.token()
    Sql.update(connection,
      """INSERT INTO oauth_tokens(id, did, client_id, scope, access_hash, refresh_hash, dpop_jkt,
         security_epoch, created_at, access_expires_at, refresh_expires_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
      UUID.randomUUID().toString, did, clientId, scope, Hash.digestToken(access),
      Hash.digestToken(refresh), thumbprint, epoch, now,
      now + accessSeconds * 1000, now + refreshSeconds * 1000)
    Json.obj(
      "access_token" -> Json.fromString(access),
      "token_type" -> Json.fromString("DPoP"),
      "refresh_token" -> Json.fromString(refresh),
      "expires_in" -> Json.fromLong(accessSeconds),
      "scope" -> Json.fromString(scope),
      "sub" -> Json.fromString(did)
    )

  /** Revocation accepts either token of a pair and is idempotent. */
  def revoke(env: Env, form: Map[String, String]): IO[Unit] =
    form.get("token").filter(Hash.isToken) match
      case None => IO.unit
      case Some(token) =>
        val digest = Hash.digestToken(token)
        env.database.transact { connection =>
          Sql.update(connection,
            "UPDATE oauth_tokens SET revoked = true WHERE access_hash = ? OR refresh_hash = ?",
            digest, digest)
        }.void
