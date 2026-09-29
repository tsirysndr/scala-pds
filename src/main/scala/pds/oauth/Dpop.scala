package pds.oauth

import cats.effect.IO
import io.circe.Json
import java.sql.Connection
import org.http4s.{Request, Status}
import org.typelevel.ci.CIString
import pds.{Env, XrpcError}
import pds.crypto.{Encoding, Hash, Jwt}
import pds.storage.Sql

final case class Proof(thumbprint: String, jti: String)

/** DPoP proof-of-possession (RFC 9449). Proofs are bound to the method, the
  * URL without its query, the presented access token, and a server nonce; each
  * `jti` is accepted once within its lifetime.
  */
object Dpop:
  val windowSeconds = 30L
  private val nonceStepSeconds = 180L

  def nonce(env: Env, now: Long): String = nonceAt(env, now / 1000 / nonceStepSeconds)

  private def nonceAt(env: Env, step: Long): String =
    env.sealing.mac("pds/dpop-nonce/v1", step.toString).take(24)

  def acceptable(env: Env, now: Long): Set[String] =
    val step = now / 1000 / nonceStepSeconds
    Set(nonceAt(env, step), nonceAt(env, step - 1))

  def required(request: Request[IO]): Boolean =
    request.headers.get(CIString("DPoP")).isDefined

  /** RFC 9449 splits the reply by surface: an authorization-server endpoint
    * answers 400 with a JSON `error`, a resource server answers 401 with a
    * `WWW-Authenticate` challenge. A conforming client only retries the
    * nonce handshake when it sees the right one.
    */
  def fail(error: String, message: String, authorizationServer: Boolean): XrpcError =
    if authorizationServer then
      XrpcError(Status.BadRequest, error, message, oauth = true)
    else
      XrpcError(Status.Unauthorized, error, message,
        challenge = Some(s"""DPoP algs="ES256", error="$error", error_description="$message""""))

  /** Verifies a proof. `accessToken` binds the proof to a presented token and
    * `requireNonce` applies to the token endpoint, which always demands one.
    */
  def verify(
      env: Env,
      request: Request[IO],
      accessToken: Option[String],
      now: Long,
      requireNonce: Boolean,
      authorizationServer: Boolean
  ): IO[Proof] =
    val fails = (error: String, message: String) => fail(error, message, authorizationServer)
    for
      header <- IO.fromOption(request.headers.get(CIString("DPoP")).map(_.head.value))(
        fails("invalid_dpop_proof", "A DPoP proof is required"))
      jwt <- IO.fromEither(Jwt.parse(header).left.map(message =>
        fails("invalid_dpop_proof", message)))
      _ <- IO.raiseUnless(jwt.typ.contains("dpop+jwt"))(
        fails("invalid_dpop_proof", "Proof type must be dpop+jwt"))
      jwk <- IO.fromOption(jwt.header.hcursor.downField("jwk").focus)(
        fails("invalid_dpop_proof", "Proof has no public key"))
      key <- IO.fromEither(Jwt.publicKeyFromJwk(jwk).left.map(message =>
        fails("invalid_dpop_proof", message)))
      _ <- IO.raiseUnless(jwt.algorithm.contains(key.curve.jwtAlgorithm))(
        fails("invalid_dpop_proof", "Unexpected proof algorithm"))
      _ <- IO.fromEither(Jwt.verifyEs(key, header).left.map(message =>
        fails("invalid_dpop_proof", message)))
      thumbprint <- IO.fromEither(Jwt.thumbprint(jwk).left.map(message =>
        fails("invalid_dpop_proof", message)))
      issued <- IO.fromOption(jwt.numeric("iat"))(
        fails("invalid_dpop_proof", "Proof has no issue time"))
      _ <- IO.raiseUnless(math.abs(now / 1000 - issued) <= windowSeconds)(
        fails("invalid_dpop_proof", "Proof is outside the accepted time window"))
      method <- IO.fromOption(jwt.claim("htm"))(fails("invalid_dpop_proof", "Proof has no method"))
      _ <- IO.raiseUnless(method == request.method.name)(
        fails("invalid_dpop_proof", "Proof method does not match the request"))
      url <- IO.fromOption(jwt.claim("htu"))(fails("invalid_dpop_proof", "Proof has no URL"))
      _ <- IO.raiseUnless(url == canonicalUrl(env, request))(
        fails("invalid_dpop_proof", "Proof URL does not match the request"))
      _ <- accessToken.fold(IO.unit) { token =>
        val expected = Encoding.b64(Hash.sha256(token))
        IO.raiseUnless(jwt.claim("ath").contains(expected))(
          fails("invalid_dpop_proof", "Proof is not bound to this access token"))
      }
      _ <- checkNonce(env, jwt.claim("nonce"), now, requireNonce, fails)
      jti <- IO.fromOption(jwt.claim("jti").filter(value => value.length >= 8 && value.length <= 128))(
        fails("invalid_dpop_proof", "Proof has no usable identifier"))
      _ <- recordJti(env, thumbprint, jti, now, fails)
    yield Proof(thumbprint, jti)

  private def checkNonce(
      env: Env,
      supplied: Option[String],
      now: Long,
      requireNonce: Boolean,
      fails: (String, String) => XrpcError
  ): IO[Unit] =
    supplied match
      case Some(value) if acceptable(env, now).contains(value) => IO.unit
      case Some(_) => IO.raiseError(fails("use_dpop_nonce", "The DPoP nonce is stale"))
      case None if requireNonce =>
        IO.raiseError(fails("use_dpop_nonce", "A DPoP nonce is required"))
      case None => IO.unit

  private def recordJti(
      env: Env, thumbprint: String, jti: String, now: Long,
      fails: (String, String) => XrpcError
  ): IO[Unit] =
    env.database.transact { connection =>
      Sql.update(connection, "DELETE FROM oauth_replay WHERE expires_at <= ?", now)
      val key = Hash.digestToken(s"$thumbprint/$jti")
      if Sql.exists(connection, "SELECT 1 FROM oauth_replay WHERE jti = ?", key) then
        throw fails("invalid_dpop_proof", "The proof has already been used")
      Sql.update(connection, "INSERT INTO oauth_replay(jti, expires_at) VALUES (?, ?)",
        key, now + (windowSeconds * 2) * 1000)
    }.void

  /** The request URL as the client saw it: public origin, path, no query. */
  def canonicalUrl(env: Env, request: Request[IO]): String =
    s"${env.config.publicUrl}${request.uri.path.renderString}"
