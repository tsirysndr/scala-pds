package pds.security

import cats.effect.IO
import org.http4s.Request
import pds.crypto.Jwt
import pds.protocol.Syntax
import pds.{Env, XrpcError}

/** Inbound inter-service authentication.
  *
  * Another service calls here with a JWT signed by an account's repository key,
  * naming this server as the audience and the method it intends to call. The
  * issuer's signing key comes from its DID document, so the token is only as
  * good as the identity behind it.
  */
object ServiceAuth:
  def bearer(request: Request[IO]): Option[String] =
    request.headers.get(org.typelevel.ci.CIString("Authorization"))
      .map(_.head.value)
      .collect { case value if value.regionMatches(true, 0, "Bearer ", 0, 7) => value.drop(7).trim }
      .filter(_.count(_ == '.') == 2)

  /** The issuer DID, once the token is proven to be signed by it. */
  def verify(env: Env, token: String, method: String): IO[String] =
    for
      jwt <- IO.fromEither(Jwt.parse(token).left.map(message => XrpcError.authRequired(message)))
      issuer <- IO.fromOption(jwt.claim("iss").filter(Syntax.isDid))(
        XrpcError.authRequired("Service token has no issuer"))
      _ <- IO.raiseUnless(jwt.claim("aud").contains(env.config.serviceDid))(
        XrpcError.authRequired("Service token names another audience"))
      _ <- IO.raiseUnless(jwt.claim("lxm").contains(method))(
        XrpcError.authRequired(s"Service token is not scoped to $method"))
      now <- env.now
      _ <- IO.raiseUnless(jwt.numeric("exp").exists(_ > now / 1000))(
        XrpcError.expiredToken("Service token has expired"))
      _ <- IO.raiseUnless(jwt.numeric("iat").forall(_ <= now / 1000 + 30))(
        XrpcError.authRequired("Service token is issued in the future"))
      document <- env.resolver.resolveDid(issuer)
      key <- IO.fromOption(document.flatMap(_.signingKey))(
        XrpcError.authRequired("Service token issuer publishes no signing key"))
      _ <- IO.fromEither(Jwt.verifyEs(key, token).left.map(message =>
        XrpcError.authRequired(message)).map(_ => ()))
    yield issuer

  /** Whether a request carries service auth from `did` for `method`. */
  def issuedBy(env: Env, request: Request[IO], did: String, method: String): IO[Boolean] =
    bearer(request) match
      case None => IO.pure(false)
      case Some(token) =>
        verify(env, token, method).map(_ == did).handleError(_ => false)
