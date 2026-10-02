package pds.oauth

import cats.effect.IO
import io.circe.Json
import java.sql.Connection
import org.http4s.Status
import pds.{Env, XrpcError}
import pds.accounts.Accounts
import pds.crypto.Hash
import pds.security.Browser
import pds.storage.Sql

/** Browser-side authorization state. The URL carries only a random,
  * non-secret interaction identifier; the secret lives in a cookie, so a
  * leaked URL cannot be replayed by another browser.
  */
object Interaction:
  val lifetimeSeconds = 600L

  private def fail(error: String, message: String) =
    XrpcError.named(Status.BadRequest, error, message)

  private def missing = fail("invalid_request",
    "Authorization could not be completed. Restart from your application.")

  final case class Started(id: String, browserSecret: String)

  def start(env: Env, clientId: String, requestUri: String): IO[Started] =
    for
      now <- env.now
      parameters <- Par.consume(env, clientId, requestUri, now)
      id = Hash.token()
      secret = Hash.token()
      _ <- env.database.transact { connection =>
        Sql.update(connection, "DELETE FROM oauth_interactions WHERE expires_at <= ?", now)
        Sql.update(connection,
          """INSERT INTO oauth_interactions(id, browser_hash, csrf_nonce, request_uri, client_id,
             parameters, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
          id, Hash.digestToken(secret), Hash.token(), requestUri, clientId,
          Json.fromFields(parameters.view.mapValues(Json.fromString).toSeq).noSpaces,
          now, now + lifetimeSeconds * 1000)
      }
    yield Started(id, secret)

  private final case class State(
      id: String,
      csrfNonce: String,
      clientId: String,
      requestUri: String,
      parameters: Map[String, String],
      did: Option[String],
      securityEpoch: Option[Long],
      decided: Boolean
  )

  private def load(connection: Connection, id: String, secret: Option[String], now: Long): State =
    val value = secret.filter(Hash.isToken).getOrElse(throw missing)
    Sql.first(connection,
      """SELECT id, browser_hash, csrf_nonce, client_id, request_uri, parameters, did,
         security_epoch, decided, expires_at FROM oauth_interactions WHERE id = ?""", id)(row =>
      (row.string("browser_hash"), State(row.string("id"), row.string("csrf_nonce"),
        row.string("client_id"), row.string("request_uri"),
        io.circe.parser.parse(row.string("parameters")).toOption.flatMap(_.asObject)
          .map(_.toMap.flatMap((key, json) => json.asString.map(key -> _))).getOrElse(Map.empty),
        row.stringOpt("did"), row.longOpt("security_epoch"), row.bool("decided")),
        row.long("expires_at"))
    ) match
      case Some((hash, state, expires))
        if expires > now && Hash.constantTimeEquals(hash, Hash.digestToken(value)) => state
      case _ => throw missing

  def inspect(env: Env, id: String, secret: Option[String]): IO[Json] =
    env.now.flatMap { now =>
      env.database.read { connection =>
        val state = load(connection, id, secret, now)
        render(env, connection, state, secret.get)
      }
    }

  private def render(env: Env, connection: Connection, state: State, secret: String): Json =
    val scope = state.parameters.getOrElse("scope", Scope.required).split(" ").toVector
    Json.obj(
      "client-id" -> Json.fromString(state.clientId),
      "parameters" -> Json.fromFields(
        state.parameters.view.filterKeys(Set("scope", "login_hint", "prompt"))
          .mapValues(Json.fromString).toSeq),
      "did" -> state.did.map(Json.fromString).getOrElse(Json.Null),
      "csrf" -> Json.fromString(env.sealing.mac("pds/oauth/csrf/v1", s"${state.csrfNonce}/$secret")),
      "permissions" -> Json.arr(Scope.permissions(scope).map(Json.fromString)*),
      "permission-sets" -> Json.arr()
    )

  private def checkCsrf(env: Env, state: State, secret: String, supplied: Option[String]): Unit =
    val expected = env.sealing.mac("pds/oauth/csrf/v1", s"${state.csrfNonce}/$secret")
    if !supplied.exists(value => Hash.constantTimeEquals(expected, value)) then
      throw XrpcError.named(Status.Forbidden, "InvalidCsrf", "Reload this page and try again")

  /** Binds an authenticated browser owner to this authorization. */
  def attach(
      env: Env,
      id: String,
      secret: Option[String],
      csrfToken: Option[String],
      accountToken: Option[String],
      accountCsrf: Option[String]
  ): IO[Json] =
    env.now.flatMap { now =>
      env.database.transact { connection =>
        val state = load(connection, id, secret, now)
        checkCsrf(env, state, secret.get, csrfToken)
        if state.decided then throw missing
        val (did, epoch, _) = Browser.owner(env, connection, accountToken, accountCsrf, now)
        state.parameters.get("login_hint").foreach { hint =>
          val account = Accounts.require(connection, did)
          if hint != account.handle && hint != account.did && !account.email.contains(hint) then
            throw fail("access_denied", "Sign in with the account requested by this application")
        }
        Sql.update(connection,
          "UPDATE oauth_interactions SET did = ?, security_epoch = ?, authenticated_at = ? WHERE id = ?",
          did, epoch, now, id)
        render(env, connection, state.copy(did = Some(did), securityEpoch = Some(epoch)), secret.get)
      }
    }

  final case class Decision(location: String)

  /** Records consent and issues the authorization code, or redirects a denial. */
  def decide(
      env: Env, id: String, secret: Option[String], csrfToken: Option[String], approve: Boolean
  ): IO[Decision] =
    env.now.flatMap { now =>
      env.database.transact { connection =>
        val state = load(connection, id, secret, now)
        checkCsrf(env, state, secret.get, csrfToken)
        if state.decided then throw missing
        val did = state.did.getOrElse(throw fail("login_required", "Sign in to continue"))
        Sql.update(connection, "UPDATE oauth_interactions SET decided = true WHERE id = ?", id)
        val redirect = state.parameters.getOrElse("redirect_uri",
          throw fail("invalid_request", "The request has no redirect target"))
        val fragmentMode = state.parameters.get("response_mode").contains("fragment")
        val stateValue = state.parameters.getOrElse("state", "")
        if !approve then
          Decision(redirectTo(redirect, fragmentMode, Map(
            "error" -> "access_denied",
            "error_description" -> "The account owner denied the request",
            "state" -> stateValue,
            "iss" -> env.config.publicUrl)))
        else
          val code = Hash.token()
          val account = Accounts.requireActive(connection, did)
          Sql.update(connection,
            """INSERT INTO oauth_codes(code_hash, did, client_id, scope, redirect_uri,
               code_challenge, dpop_jkt, security_epoch, created_at, expires_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            Hash.digestToken(code), did, state.clientId,
            state.parameters.getOrElse("scope", Scope.required), redirect,
            state.parameters.getOrElse("code_challenge", ""),
            Sql.first(connection, "SELECT dpop_jkt FROM oauth_requests WHERE request_uri = ?",
              state.requestUri)(_.stringOpt("dpop_jkt")).flatten,
            account.securityEpoch, now, now + 60_000)
          Decision(redirectTo(redirect, fragmentMode, Map(
            "code" -> code, "state" -> stateValue, "iss" -> env.config.publicUrl)))
      }
    }

  private def redirectTo(target: String, fragment: Boolean, params: Map[String, String]): String =
    val query = params.filter(_._2.nonEmpty).map((key, value) =>
      s"$key=${java.net.URLEncoder.encode(value, "UTF-8")}").mkString("&")
    // The client chose where the response lands at PAR time: the fragment is
    // for apps that can only read location.hash, the query for everyone else.
    if fragment then s"$target#$query"
    else if target.contains('?') then s"$target&$query"
    else s"$target?$query"
