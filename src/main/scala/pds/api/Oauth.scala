package pds.api

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.http4s.client.Client
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.headers.Location
import org.typelevel.ci.CIString
import pds.{Env, XrpcError}
import pds.oauth.{Interaction, Par, TokenEndpoint}
import pds.security.Web

/** HTTP surface of the authorization server. Protocol endpoints take form
  * bodies; the browser endpoints keep secrets in cookies and put only random,
  * non-secret interaction identifiers in URLs.
  */
object Oauth:
  private val flowPattern = """^/oauth/flow/([A-Za-z0-9_-]{43})(?:/(state|attach|decide))?$""".r

  private def form(request: Request[IO]): IO[Map[String, String]] =
    request.body.take(64 * 1024L).compile.to(Array).flatMap { bytes =>
      val text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
      // Several OAuth clients send PAR as JSON rather than a form. The shape is
      // the same flat map of strings, so both bodies are accepted.
      val isJson = request.contentType.exists(_.mediaType == org.http4s.MediaType.application.json)
      if isJson then
        IO.fromEither(io.circe.parser.parse(text).toOption
          .flatMap(_.asObject)
          .map(_.toMap.flatMap((key, value) => value.asString.map(key -> _)))
          .toRight(XrpcError.invalidRequest("Expected a JSON object of strings")))
      else
        IO.fromEither(UrlForm.decodeString(Charset.`UTF-8`)(text)
          .left.map(_ => XrpcError.invalidRequest("Expected a form-encoded body")))
          .map(_.values.view.mapValues(_.headOption.getOrElse("")).toMap)
    }

  def routes(env: Env, client: Client[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "oauth" / "par" =>
      for
        values <- form(request)
        now <- env.now
        pushed <- Par.push(env, request, values, now)
        response <- IO.pure(Response[IO](Status.Created).withEntity(Json.obj(
          "request_uri" -> Json.fromString(pushed.requestUri),
          "expires_in" -> Json.fromLong(pushed.expiresIn)
        )))
      yield response

    case request @ POST -> Root / "oauth" / "token" =>
      for
        values <- form(request)
        tokens <- TokenEndpoint.issue(env, request, values)
        response <- Xrpc.ok(tokens)
      yield response

    case request @ POST -> Root / "oauth" / "revoke" =>
      for
        values <- form(request)
        _ <- TokenEndpoint.revoke(env, values)
        response <- Xrpc.empty
      yield response

    case request @ GET -> Root / "oauth" / "authorize" =>
      for
        clientId <- IO.fromOption(request.params.get("client_id"))(
          XrpcError.named(Status.BadRequest, "invalid_request", "client_id is required"))
        requestUri <- IO.fromOption(request.params.get("request_uri"))(
          XrpcError.named(Status.BadRequest, "invalid_request",
            "Use a pushed authorization request"))
        _ <- IO.raiseUnless(request.params.keySet == Set("client_id", "request_uri"))(
          XrpcError.named(Status.BadRequest, "invalid_request",
            "Use a pushed authorization request"))
        started <- Interaction.start(env, clientId, requestUri)
        response <- IO.pure(Response[IO](Status.SeeOther)
          .putHeaders(Location(Uri.unsafeFromString(s"/oauth/flow/${started.id}")))
          .putHeaders(Web.cookie(env, "oauth", Some(started.browserSecret),
            Interaction.lifetimeSeconds))
          .putHeaders(Web.headers*))
      yield response

    case request if flowPattern.findFirstMatchIn(request.uri.path.renderString).isDefined =>
      val matched = flowPattern.findFirstMatchIn(request.uri.path.renderString).get
      val id = matched.group(1)
      val action = Option(matched.group(2))
      val secret = Web.readCookie(env, "oauth", request)
      val csrf = request.headers.get(CIString("X-CSRF-Token")).map(_.head.value)
      (request.method, action) match
        case (Method.GET, None) =>
          Interaction.inspect(env, id, secret) *> IO.pure(
            Web.asset("/account").getOrElse(Response[IO](Status.NotFound))
              .putHeaders(Web.headers*))
        case (Method.GET, Some("state")) =>
          Interaction.inspect(env, id, secret).flatMap(Xrpc.ok)
            .map(_.putHeaders(Web.headers*))
        case (Method.POST, Some("attach")) =>
          for
            _ <- Web.checkOrigin(env, request)
            body <- Xrpc.body(request)
            state <- Interaction.attach(env, id, secret, csrf,
              Web.readCookie(env, "security", request), Xrpc.field(body, "accountCsrf"))
            response <- Xrpc.ok(state)
          yield response.putHeaders(Web.headers*)
        case (Method.POST, Some("decide")) =>
          for
            _ <- Web.checkOrigin(env, request)
            body <- Xrpc.body(request)
            approve = body.hcursor.get[Boolean]("approve").getOrElse(false)
            decision <- Interaction.decide(env, id, secret, csrf, approve)
            response <- Xrpc.ok(Json.obj("location" -> Json.fromString(decision.location)))
          yield response.putHeaders(Web.headers*)
        case _ =>
          IO.pure(XrpcError.named(Status.MethodNotAllowed, "invalid_request",
            "Method is not allowed").response[IO])
  }
