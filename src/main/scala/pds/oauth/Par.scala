package pds.oauth

import cats.effect.IO
import io.circe.Json
import org.http4s.{Request, Status, UrlForm}
import pds.{Env, XrpcError}
import pds.crypto.Hash
import pds.storage.Sql

/** Pushed authorization requests. Every authorization starts here, so the
  * browser never carries request parameters that a client could tamper with.
  */
object Par:
  val lifetimeSeconds = 300L
  private val prefix = "urn:ietf:params:oauth:request_uri:"

  private def fail(error: String, message: String) =
    XrpcError.named(Status.BadRequest, error, message)

  final case class Pushed(requestUri: String, expiresIn: Long)

  def push(env: Env, request: Request[IO], form: Map[String, String], now: Long): IO[Pushed] =
    for
      clientId <- IO.fromOption(form.get("client_id"))(
        fail("invalid_request", "client_id is required"))
      metadata <- Client.fetch(env, clientId)
      _ <- authenticate(env, metadata, form, now)
      parameters <- IO.fromEither(validate(metadata, form))
      proof <- if Dpop.required(request)
        then Dpop.verify(env, request, None, now, requireNonce = true).map(value => Some(value.thumbprint))
        else IO.pure(None)
      identifier = Hash.token()
      _ <- env.database.transact { connection =>
        Sql.update(connection, "DELETE FROM oauth_requests WHERE expires_at <= ?", now)
        Sql.update(connection,
          """INSERT INTO oauth_requests(request_uri, client_id, parameters, dpop_jkt,
             created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)""",
          prefix + identifier, clientId, Json.fromFields(
            parameters.view.mapValues(Json.fromString).toSeq).noSpaces,
          proof, now, now + lifetimeSeconds * 1000)
      }
    yield Pushed(prefix + identifier, lifetimeSeconds)

  private def authenticate(
      env: Env, metadata: ClientMetadata, form: Map[String, String], now: Long
  ): IO[Unit] =
    if metadata.authMethod == "none" then IO.unit
    else
      for
        kind <- IO.fromOption(form.get("client_assertion_type"))(
          fail("invalid_client", "A client assertion is required"))
        _ <- IO.raiseUnless(kind == "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")(
          fail("invalid_client", "Unsupported client assertion type"))
        assertion <- IO.fromOption(form.get("client_assertion"))(
          fail("invalid_client", "A client assertion is required"))
        _ <- Client.verifyAssertion(env, metadata, assertion, env.config.publicUrl, now)
      yield ()

  private def validate(
      metadata: ClientMetadata, form: Map[String, String]
  ): Either[XrpcError, Map[String, String]] =
    for
      _ <- Either.cond(form.get("response_type").contains("code"), (),
        fail("unsupported_response_type", "Only the code response type is supported"))
      redirect <- form.get("redirect_uri").toRight(fail("invalid_request", "redirect_uri is required"))
      _ <- Either.cond(metadata.redirectUris.contains(redirect), (),
        fail("invalid_request", "redirect_uri is not registered for this client"))
      challenge <- form.get("code_challenge")
        .filter(value => value.length >= 43 && value.length <= 128)
        .toRight(fail("invalid_request", "code_challenge is required"))
      _ <- Either.cond(form.get("code_challenge_method").contains("S256"), (),
        fail("invalid_request", "code_challenge_method must be S256"))
      state <- form.get("state").filter(value => value.length >= 8 && value.length <= 512)
        .toRight(fail("invalid_request", "state is required"))
      scope <- Scope.parse(form.getOrElse("scope", metadata.scope.mkString(" ")))
        .left.map(message => fail("invalid_scope", message))
      _ <- Either.cond(form.get("prompt").forall(
        Set("none", "login", "consent", "select_account", "create").contains), (),
        fail("invalid_request", "Unsupported prompt value"))
      _ <- Either.cond(form.get("login_hint").forall(_.length <= 2048), (),
        fail("invalid_request", "login_hint is too long"))
    yield Map(
      "client_id" -> metadata.clientId,
      "response_type" -> "code",
      "redirect_uri" -> redirect,
      "code_challenge" -> challenge,
      "code_challenge_method" -> "S256",
      "state" -> state,
      "scope" -> scope.mkString(" ")
    ) ++ form.view.filterKeys(Set("login_hint", "prompt")).toMap

  def consume(env: Env, clientId: String, requestUri: String, now: Long): IO[Map[String, String]] =
    env.database.transact { connection =>
      val row = Sql.first(connection,
        """SELECT parameters, expires_at, consumed FROM oauth_requests
           WHERE request_uri = ? AND client_id = ?""", requestUri, clientId)(row =>
        (row.string("parameters"), row.long("expires_at"), row.bool("consumed")))
      row match
        case Some((parameters, expires, false)) if expires > now =>
          Sql.update(connection, "UPDATE oauth_requests SET consumed = true WHERE request_uri = ?",
            requestUri)
          io.circe.parser.parse(parameters).toOption
            .flatMap(_.asObject)
            .map(_.toMap.flatMap((key, value) => value.asString.map(key -> _)))
            .getOrElse(throw fail("invalid_request", "The authorization request is unreadable"))
        case _ =>
          throw fail("invalid_request", "The authorization request has expired or was already used")
    }
