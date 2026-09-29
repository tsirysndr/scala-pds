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
      env: Env, client: Client[IO], limiter: RateLimit, builder: Option[WebSocketBuilder2[IO]]
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

      case GET -> Root / ".well-known" / "did.json" =>
        Xrpc.ok(DidDocument.service(env.config.serviceDid, env.config.publicUrl))

      case GET -> Root / ".well-known" / "oauth-authorization-server" =>
        Xrpc.ok(Metadata.authorizationServer(env.config))

      case GET -> Root / ".well-known" / "oauth-protected-resource" =>
        Xrpc.ok(Metadata.protectedResource(env.config))

      case GET -> Root / "u" / name / "did.json" =>
        hostedDocument(env, s"did:web:${env.config.hostname}:u:$name")

      case request @ GET -> Root / "xrpc" / "com.atproto.sync.subscribeRepos" =>
        builder match
          case Some(sockets) => Firehose.subscribe(env, request, sockets)
          case None => IO.pure(XrpcError.named(Status.NotImplemented, "MethodNotImplemented",
            "WebSocket subscriptions are not available here").response[IO])

      case request @ (GET | POST) -> Root / "xrpc" / method =>
        endpoints.get(method) match
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
              "Unknown XRPC method").response[IO])

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
          if !allowed then IO.pure(XrpcError.rateLimited().response[IO]
            .putHeaders(Header.Raw(CIString("Retry-After"), "60")))
          else Xrpc.recover(app.run(request).getOrElse(
            XrpcError.notFound("Route not found").response[IO]))
      yield decorate(env, request, response, now)
      Xrpc.recover(handled)
    }

  private def hostedDocument(env: Env, did: String): IO[Response[IO]] =
    env.database.read { connection =>
      pds.accounts.Accounts.byDid(connection, did).filter(_.status != "deleted").map { account =>
        val key = RepoStore.signingKey(connection, account.did, env.sealing)
        DidDocument.build(account.did, account.handle, key.publicKey, env.config.publicUrl)
      }
    }.flatMap {
      case Some(document) => Xrpc.ok(document)
      case None => IO.pure(XrpcError.notFound("No such account on this server").response[IO])
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
      withNonce.putHeaders(Header.Raw(CIString("WWW-Authenticate"), s"DPoP $discovery"))
    else withNonce
