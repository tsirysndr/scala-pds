package pds

import cats.effect.IO
import cats.syntax.semigroupk.toSemigroupKOps
import io.circe.Json
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.{Allow, `Content-Type`, Location}
import org.http4s.server.websocket.WebSocketBuilder2
import org.typelevel.ci.CIString
import pds.api.*
import pds.firehose.Firehose
import pds.identity.DidDocument
import pds.oauth.{Dpop, Metadata}
import pds.protocol.Syntax
import pds.repo.RepoStore
import pds.security.Web

object PdsApp:
  private val welcome = """         __                         __
        |        /\ \__                     /\ \__
        |    __  \ \ ,_\  _____   _ __   ___\ \ ,_\   ___
        |  /'__'\ \ \ \/ /\ '__'\/\''__\/ __'\ \ \/  / __'\
        | /\ \L\.\_\ \ \_\ \ \L\ \ \ \//\ \L\ \ \ \_/\ \L\ \
        | \ \__/.\_\\ \__\\ \ ,__/\ \_\\ \____/\ \__\ \____/
        |  \/__/\/_/ \/__/ \ \ \/  \/_/ \/___/  \/__/\/___/
        |                   \ \_\
        |                    \/_/
        |
        |
        |This is an AT Protocol Personal Data Server (aka, an atproto PDS)
        |
        |Most API routes are under /xrpc/
        |
        |Docs: https://scala-pds.tsirysndr.deno.net
        |""".stripMargin

  val version = "scala-pds 0.1.0-SNAPSHOT"

  private val proxied = Set("app.bsky.", "chat.bsky.", "tools.ozone.")

  def apply(
      env: Env,
      client: Client[IO],
      limiter: RateLimit,
      builder: Option[WebSocketBuilder2[IO]],
      metrics: Metrics
  ): HttpApp[IO] =
    val endpoints = ServerApi.endpoints(env) ++ RepoApi.endpoints(env) ++
      SyncApi.endpoints(env) ++ IdentityApi.endpoints(env) ++ AdminApi.endpoints(env) ++
      BskyApi.endpoints(env)

    val routes = HttpRoutes.of[IO] {
      case request @ GET -> Root =>
        IO.pure(Response[IO](Status.Ok).withEntity(welcome)
          .withContentType(`Content-Type`(MediaType.text.plain)))

      case GET -> Root / "xrpc" / "_health" =>
        env.database.read(_ => ()).attempt.flatMap {
          case Right(_) => Xrpc.ok(Json.obj("version" -> Json.fromString(version)))
          case Left(_)  => IO.pure(XrpcError.internal("Database is unavailable").response[IO])
        }

      case GET -> Root / "_health" =>
        Xrpc.ok(Json.obj("version" -> Json.fromString(version)))

      case request @ GET -> Root / "metrics" =>
        Xrpc.admin(env, request) *> metrics.render(env).map(body =>
          Response[IO](Status.Ok).withEntity(body)
            .withContentType(`Content-Type`(MediaType.text.plain)))

      case request @ GET -> Root / ".well-known" / "did.json" =>
        // A did:web document is served at the host its DID names, so the same
        // path answers for the service and for any account hosted here.
        hostedDocument(env, s"did:web:${authority(env, request)}").flatMap { document =>
          document.fold(
            Xrpc.ok(DidDocument.service(env.config.serviceDid, env.config.publicUrl)))(Xrpc.ok)
        }

      case GET -> Root / ".well-known" / "oauth-authorization-server" =>
        Xrpc.ok(Metadata.authorizationServer(env.config))

      case GET -> Root / ".well-known" / "oauth-protected-resource" =>
        Xrpc.ok(Metadata.protectedResource(env.config))

      case request @ GET -> Root / "xrpc" / "com.atproto.sync.subscribeRepos" =>
        builder match
          case Some(sockets) => Firehose.subscribe(env, request, sockets, metrics)
          case None => IO.pure(XrpcError.named(Status.NotImplemented, "MethodNotImplemented",
            "WebSocket subscriptions are not available here").response[IO])

      case request @ (GET | POST) -> Root / "xrpc" / method =>
        metrics.method(method) *> (endpoints.get(method) match
          case Some(endpoint) =>
            val expected = if endpoint.kind == Kind.Query then Method.GET else Method.POST
            if request.method == expected then endpoint.run(request)
            else IO.pure(XrpcError.invalidRequest(
              s"Use ${expected.name} for this method").response[IO]
              .putHeaders(Allow(expected)))
          case None if proxied.exists(method.startsWith) && Syntax.isNsid(method) =>
            Proxy.handle(env, client, method, request)
          case None =>
            IO.pure(XrpcError.named(Status.NotImplemented, "MethodNotImplemented",
              "Unknown XRPC method").response[IO]))

      case request @ OPTIONS -> _ => IO.pure(Response[IO](Status.NoContent))
    }

    val app = Web.routes(env) <+> Oauth.routes(env, client) <+> routes

    HttpApp[IO] { request =>
      val handled = for
        now <- env.now
        key = request.headers.get(CIString("x-forwarded-for")).map(_.head.value.takeWhile(_ != ','))
          .orElse(request.remoteAddr.map(_.toString)).getOrElse("unknown")
        allowed <- limiter.check(key.trim, now)
        response <-
          if !allowed then metrics.rateLimited.as(XrpcError.rateLimited().response[IO]
            .putHeaders(Header.Raw(CIString("Retry-After"), "60")))
          else Xrpc.recover(app.run(request).getOrElse(
            XrpcError.notFound("Route not found").response[IO]))
        decorated = decorate(env, request, response, now)
        _ <- metrics.record(surface(request), decorated.status.code)
        finished <- env.now
        _ <- access(env, request, decorated, key, finished - now)
      yield decorated
      Xrpc.recover(handled)
    }

  private def surface(request: Request[IO]): String =
    val path = request.uri.path.renderString
    if path.startsWith("/xrpc/") then "xrpc"
    else if path.startsWith("/oauth/") || path.startsWith("/.well-known/oauth") then "oauth"
    else if path.startsWith("/account") then "account"
    else "other"

  /** One structured line per request, off by default in development. */
  private def access(
      env: Env, request: Request[IO], response: Response[IO], client: String, millis: Long
  ): IO[Unit] =
    if !env.config.accessLog then IO.unit
    else
      IO.println(
        s"""level=info msg=request method=${request.method.name} """ +
          s"""path="${request.uri.path.renderString}" status=${response.status.code} """ +
          s"""duration_ms=$millis client="$client"""")

  /** The request's host, lowercased and without its port. */
  private def authority(env: Env, request: Request[IO]): String =
    request.headers.get(CIString("Host")).map(_.head.value)
      .orElse(request.uri.host.map(_.value))
      .getOrElse(env.config.hostname)
      .takeWhile(_ != ':')
      .toLowerCase

  private def hostedDocument(env: Env, did: String): IO[Option[Json]] =
    env.database.read { connection =>
      pds.accounts.Accounts.byDid(connection, did).filter(_.status != "deleted").map { account =>
        val key = RepoStore.signingKey(connection, account.did, env.sealing)
        DidDocument.build(account.did, account.handle, key.publicKey, env.config.publicUrl)
      }
    }

  /** CORS, cache and DPoP headers, plus the OAuth discovery challenge. */
  private def decorate(
      env: Env, request: Request[IO], response: Response[IO], now: Long
  ): Response[IO] =
    val path = request.uri.path.renderString
    val isXrpc = path.startsWith("/xrpc/")
    val isOauth = path.startsWith("/oauth/") || path.startsWith("/.well-known/oauth")
    val withCors =
      if isXrpc || isOauth then
        response.putHeaders(
          Header.Raw(CIString("Access-Control-Allow-Origin"), "*"),
          Header.Raw(CIString("Access-Control-Allow-Methods"), "GET, HEAD, POST, OPTIONS"),
          Header.Raw(CIString("Access-Control-Allow-Headers"),
            "Authorization, Content-Type, DPoP, atproto-accept-labelers, atproto-proxy"),
          Header.Raw(CIString("Access-Control-Expose-Headers"),
            "DPoP-Nonce, WWW-Authenticate, Retry-After, Atproto-Repo-Rev, Atproto-Content-Labelers"))
      else response
    val withNonce =
      if isOauth || (isXrpc && Dpop.required(request)) then
        withCors.putHeaders(
          Header.Raw(CIString("DPoP-Nonce"), Dpop.nonce(env, now)),
          Header.Raw(CIString("Cache-Control"), "no-store"),
          Header.Raw(CIString("X-Content-Type-Options"), "nosniff"))
      else withCors
    if isXrpc && response.status == Status.Unauthorized then
      val discovery =
        s"""resource_metadata="${env.config.publicUrl}/.well-known/oauth-protected-resource""""
      // A DPoP challenge already names the error; only the discovery hint is added.
      val existing = response.headers.get(CIString("WWW-Authenticate")).map(_.head.value)
      val challenge = existing match
        case Some(value) if value.startsWith("DPoP") => s"$value, $discovery"
        case Some(value)                             => s"$value, DPoP $discovery"
        case None                                    => s"DPoP $discovery"
      withNonce.putHeaders(Header.Raw(CIString("WWW-Authenticate"), challenge))
    else withNonce
