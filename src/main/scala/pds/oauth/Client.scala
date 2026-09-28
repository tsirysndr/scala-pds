package pds.oauth

import cats.effect.IO
import io.circe.Json
import org.http4s.Status
import pds.{Env, XrpcError}
import pds.crypto.{Jwt, PublicKey}

final case class ClientMetadata(
    clientId: String,
    name: Option[String],
    redirectUris: Vector[String],
    scope: Vector[String],
    authMethod: String,
    jwks: Vector[Json],
    jwksUri: Option[String]
)

/** Clients identify themselves by an HTTPS URL that serves their metadata
  * document. Nothing in the document is treated as trusted branding: it only
  * constrains the redirect targets, scopes and authentication method.
  */
object Client:
  private def fail(error: String, message: String) =
    XrpcError.named(Status.BadRequest, error, message)

  def fetch(env: Env, clientId: String): IO[ClientMetadata] =
    if isLoopback(clientId) then IO.fromEither(loopback(clientId))
    else
      for
        _ <- IO.raiseUnless(clientId.startsWith("https://"))(
          fail("invalid_client", "Client identifiers must be HTTPS URLs"))
        document <- env.net.getJson(clientId)
        json <- IO.fromOption(document)(
          fail("invalid_client", "The client metadata document could not be fetched"))
        metadata <- IO.fromEither(parse(clientId, json))
      yield metadata

  /** Development clients use `http://localhost` with parameters in the query. */
  def isLoopback(clientId: String): Boolean =
    clientId == "http://localhost" || clientId.startsWith("http://localhost?") ||
      clientId.startsWith("http://localhost/?")

  private def loopback(clientId: String): Either[XrpcError, ClientMetadata] =
    val query = clientId.dropWhile(_ != '?').drop(1)
    val params = query.split("&").filter(_.nonEmpty).flatMap { pair =>
      pair.split("=", 2) match
        case Array(key, value) => Some(key -> java.net.URLDecoder.decode(value, "UTF-8"))
        case _                 => None
    }.toMap
    val redirects = params.get("redirect_uri").map(Vector(_))
      .getOrElse(Vector("http://127.0.0.1/", "http://[::1]/"))
    val scope = params.getOrElse("scope", "atproto").split(" ").toVector.filter(_.nonEmpty)
    if redirects.exists(uri => !uri.startsWith("http://127.0.0.1") && !uri.startsWith("http://[::1]"))
    then Left(fail("invalid_client", "Loopback clients may only redirect to loopback addresses"))
    else Right(ClientMetadata(clientId, None, redirects, scope, "none", Vector.empty, None))

  def parse(clientId: String, json: Json): Either[XrpcError, ClientMetadata] =
    val cursor = json.hcursor
    for
      declared <- cursor.get[String]("client_id").toOption
        .toRight(fail("invalid_client", "The metadata document has no client_id"))
      _ <- Either.cond(declared == clientId, (),
        fail("invalid_client", "The metadata client_id does not match the document URL"))
      redirects <- cursor.get[Vector[String]]("redirect_uris").toOption.filter(_.nonEmpty)
        .toRight(fail("invalid_client", "The metadata document has no redirect_uris"))
      _ <- Either.cond(redirects.forall(validRedirect), (),
        fail("invalid_client", "Redirect URIs must be HTTPS or loopback addresses"))
      grants = cursor.get[Vector[String]]("grant_types").getOrElse(Vector("authorization_code"))
      _ <- Either.cond(grants.contains("authorization_code"), (),
        fail("invalid_client", "Clients must support the authorization_code grant"))
      responses = cursor.get[Vector[String]]("response_types").getOrElse(Vector("code"))
      _ <- Either.cond(responses == Vector("code"), (),
        fail("invalid_client", "Only the code response type is supported"))
      bound = cursor.get[Boolean]("dpop_bound_access_tokens").getOrElse(false)
      _ <- Either.cond(bound, (),
        fail("invalid_client", "Clients must declare dpop_bound_access_tokens"))
      method = cursor.get[String]("token_endpoint_auth_method").getOrElse("none")
      _ <- Either.cond(Set("none", "private_key_jwt").contains(method), (),
        fail("invalid_client", "Unsupported token endpoint authentication method"))
      scope = cursor.get[String]("scope").getOrElse("atproto").split(" ").toVector.filter(_.nonEmpty)
      keys = cursor.downField("jwks").downField("keys").values.map(_.toVector).getOrElse(Vector.empty)
      jwksUri = cursor.get[String]("jwks_uri").toOption
      _ <- Either.cond(method == "none" || keys.nonEmpty || jwksUri.isDefined, (),
        fail("invalid_client", "private_key_jwt clients need a key set"))
    yield ClientMetadata(clientId, cursor.get[String]("client_name").toOption,
      redirects, scope, method, keys, jwksUri)

  private def validRedirect(uri: String): Boolean =
    uri.startsWith("https://") || uri.startsWith("http://127.0.0.1") ||
      uri.startsWith("http://[::1]") || uri.matches("""[a-z][a-z0-9.+-]*:/.*""")

  /** Verifies a `private_key_jwt` client assertion against the client key set. */
  def verifyAssertion(
      env: Env, metadata: ClientMetadata, assertion: String, audience: String, now: Long
  ): IO[Unit] =
    for
      jwt <- IO.fromEither(Jwt.parse(assertion).left.map(message =>
        fail("invalid_client", message)))
      _ <- IO.raiseUnless(jwt.claim("iss").contains(metadata.clientId) &&
        jwt.claim("sub").contains(metadata.clientId))(
        fail("invalid_client", "The assertion issuer must be the client"))
      _ <- IO.raiseUnless(jwt.claim("aud").contains(audience))(
        fail("invalid_client", "The assertion audience must be this server"))
      expiry <- IO.fromOption(jwt.numeric("exp"))(
        fail("invalid_client", "The assertion has no expiry"))
      _ <- IO.raiseUnless(expiry * 1000 > now && expiry * 1000 < now + 600_000)(
        fail("invalid_client", "The assertion expiry is out of range"))
      jti <- IO.fromOption(jwt.claim("jti"))(fail("invalid_client", "The assertion has no jti"))
      keys <- keySet(env, metadata)
      _ <- IO.raiseUnless(keys.exists(key =>
        Jwt.verifyEs(key, assertion).isRight))(
        fail("invalid_client", "The assertion signature does not verify"))
      _ <- replay(env, metadata.clientId, jti, expiry * 1000)
    yield ()

  private def keySet(env: Env, metadata: ClientMetadata): IO[Vector[PublicKey]] =
    val inline = metadata.jwks.flatMap(Jwt.publicKeyFromJwk(_).toOption)
    metadata.jwksUri match
      case Some(uri) if inline.isEmpty =>
        env.net.getJson(uri).map { document =>
          document.flatMap(_.hcursor.downField("keys").values.map(_.toVector)).getOrElse(Vector.empty)
            .flatMap(Jwt.publicKeyFromJwk(_).toOption)
        }
      case _ => IO.pure(inline)

  private def replay(env: Env, clientId: String, jti: String, expires: Long): IO[Unit] =
    env.database.transact { connection =>
      val key = pds.crypto.Hash.digestToken(s"$clientId/$jti")
      pds.storage.Sql.update(connection, "DELETE FROM service_token_replay WHERE expires_at <= ?",
        System.currentTimeMillis())
      if pds.storage.Sql.exists(connection, "SELECT 1 FROM service_token_replay WHERE jti = ?", key)
      then throw fail("invalid_client", "The assertion has already been used")
      pds.storage.Sql.update(connection,
        "INSERT INTO service_token_replay(jti, expires_at) VALUES (?, ?)", key, expires)
    }.void
