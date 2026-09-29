package pds.api

import cats.effect.IO
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.typelevel.ci.CIString
import pds.{Env, XrpcError}
import pds.accounts.{Accounts, Session}
import pds.crypto.Jwt
import pds.identity.DidDocument
import pds.protocol.Syntax
import pds.repo.RepoStore

/** Authenticated proxying of AppView, labeler and moderation calls. The upstream
  * service is named by `atproto-proxy`, or defaults to the configured AppView —
  * to the moderation service for `com.atproto.moderation.*`, which is where a
  * report belongs. The request is re-signed as an inter-service token for the
  * account.
  */
object Proxy:
  private val hopByHop = Set("connection", "keep-alive", "transfer-encoding", "upgrade",
    "proxy-authenticate", "proxy-authorization", "te", "trailer", "host", "content-length",
    "authorization", "dpop", "cookie", "set-cookie")

  /** A report names the account making it, so it cannot be sent anonymously. */
  private val attributed = "com.atproto.moderation."

  def handle(
      env: Env, client: Client[IO], method: String, request: Request[IO]
  ): IO[Response[IO]] =
    for
      session <- Xrpc.optionalSession(env, request)
      _ <- IO.raiseWhen(method.startsWith(attributed) && session.isEmpty)(
        XrpcError.authRequired())
      target <- resolveTarget(env, method, request)
      upstream <- buildRequest(env, method, request, session, target)
      response <- client.run(upstream).use { proxied =>
        proxied.body.take(20L * 1024 * 1024).compile.to(Array).map { bytes =>
          Response[IO](proxied.status)
            .withEntity(bytes)
            .withHeaders(Headers(proxied.headers.headers.filterNot(header =>
              hopByHop.contains(header.name.toString.toLowerCase))))
        }
      }
    yield response

  private final case class Target(did: String, endpoint: String)

  private def resolveTarget(env: Env, method: String, request: Request[IO]): IO[Target] =
    request.headers.get(CIString("atproto-proxy")).map(_.head.value) match
      case Some(value) =>
        value.split("#", 2) match
          case Array(did, fragment) if Syntax.isDid(did) && fragment.nonEmpty =>
            env.resolver.resolveDid(did).flatMap { document =>
              IO.fromOption(document.flatMap(service(_, fragment)).map(Target(did, _)))(
                XrpcError.invalidRequest("The requested service could not be resolved"))
            }
          case _ => IO.raiseError(XrpcError.invalidRequest(
            "atproto-proxy must be did#service_id"))
      case None =>
        val configured =
          if method.startsWith(attributed) then (env.config.modServiceUrl, env.config.modServiceDid)
          else (env.config.appviewUrl, env.config.appviewDid)
        configured match
          case (Some(url), Some(did)) => IO.pure(Target(did, url))
          case _ => IO.raiseError(XrpcError.named(Status.NotImplemented, "NotImplemented",
            if method.startsWith(attributed) then "This server has no moderation service"
            else "This server does not proxy application queries"))

  private def service(document: DidDocument, fragment: String): Option[String] =
    document.raw.hcursor.downField("service").values.getOrElse(Nil).toVector.collectFirst {
      case entry if entry.hcursor.get[String]("id").toOption
        .exists(id => id == s"#$fragment" || id == s"${document.id}#$fragment") =>
        entry.hcursor.get[String]("serviceEndpoint").toOption
    }.flatten

  private def buildRequest(
      env: Env, method: String, request: Request[IO], session: Option[Session], target: Target
  ): IO[Request[IO]] =
    for
      uri <- IO.fromEither(Uri.fromString(
        s"${target.endpoint}/xrpc/$method${request.uri.query.renderString match {
          case "" => ""
          case query => s"?$query"
        }}").left.map(_ => XrpcError.invalidRequest("The upstream URL is not valid")))
      token <- session.traverseOption(serviceToken(env, _, target.did, method))
      body <- request.body.take(Xrpc.maxBodyBytes + 1).compile.to(Array)
      _ <- IO.raiseWhen(body.length > Xrpc.maxBodyBytes)(
        XrpcError.payloadTooLarge("Request body is too large"))
    yield
      val forwarded = Request[IO](request.method, uri)
        .withHeaders(Headers(request.headers.headers.filterNot(header =>
          hopByHop.contains(header.name.toString.toLowerCase))))
      val authorized = token.fold(forwarded)(value =>
        forwarded.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, value))))
      if body.isEmpty then authorized else authorized.withEntity(body)

  private def serviceToken(env: Env, session: Session, audience: String, method: String): IO[String] =
    env.now.flatMap { now =>
      env.database.read { connection =>
        val account = Accounts.requireActive(connection, session.did)
        val key = RepoStore.signingKey(connection, account.did, env.sealing)
        Jwt.signService(key, io.circe.Json.obj(
          "iss" -> io.circe.Json.fromString(account.did),
          "aud" -> io.circe.Json.fromString(audience),
          "lxm" -> io.circe.Json.fromString(method),
          "exp" -> io.circe.Json.fromLong(now / 1000 + 60),
          "iat" -> io.circe.Json.fromLong(now / 1000),
          "jti" -> io.circe.Json.fromString(pds.crypto.Hash.token())
        ))
      }
    }

  extension [A](value: Option[A])
    private def traverseOption[B](work: A => IO[B]): IO[Option[B]] =
      value.fold(IO.pure(None))(item => work(item).map(Some.apply))
