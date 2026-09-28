package pds.security

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import pds.{Env, XrpcError}
import pds.api.Xrpc
import scala.io.Source

/** The account interface: a single-page app served under a strict same-origin
  * policy, with a cookie-backed browser session and CSRF-protected actions.
  */
object Web:
  val headers: Seq[Header.ToRaw] = Seq(
    Header.Raw(CIString("Cache-Control"), "no-store"),
    Header.Raw(CIString("Pragma"), "no-cache"),
    Header.Raw(CIString("X-Content-Type-Options"), "nosniff"),
    Header.Raw(CIString("Referrer-Policy"), "no-referrer"),
    Header.Raw(CIString("X-Frame-Options"), "DENY"),
    Header.Raw(CIString("Content-Security-Policy"),
      "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; " +
        "img-src 'self' data:; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"),
    Header.Raw(CIString("Permissions-Policy"),
      "publickey-credentials-get=(self), publickey-credentials-create=(self)")
  )

  def cookieName(env: Env, prefix: String): String =
    if env.config.secure then s"__Host-pds-$prefix" else s"pds-$prefix"

  def cookie(env: Env, prefix: String, value: Option[String], lifetime: Long): Header.Raw =
    val age = value.map(_ => lifetime).getOrElse(0L)
    Header.Raw(CIString("Set-Cookie"),
      s"${cookieName(env, prefix)}=${value.getOrElse("")}; Path=/; HttpOnly; SameSite=Lax; " +
        s"Max-Age=$age" + (if env.config.secure then "; Secure" else ""))

  def readCookie(env: Env, prefix: String, request: Request[IO]): Option[String] =
    val name = cookieName(env, prefix)
    request.cookies.filter(_.name == name) match
      case List(single) => Some(single.content)
      case _            => None

  private lazy val assets: Map[String, (String, String)] = Map(
    "/account" -> ("text/html; charset=utf-8", resource("ui/index.html")),
    "/account/" -> ("text/html; charset=utf-8", resource("ui/index.html")),
    "/account/app.js" -> ("text/javascript; charset=utf-8", resource("ui/app.js")),
    "/account/style.css" -> ("text/css; charset=utf-8", resource("ui/style.css"))
  )

  private def resource(path: String): String =
    Option(getClass.getClassLoader.getResourceAsStream(path)) match
      case Some(stream) =>
        try Source.fromInputStream(stream, "UTF-8").mkString finally stream.close()
      case None => ""

  def asset(path: String): Option[Response[IO]] =
    assets.get(path).filter(_._2.nonEmpty).map { (mime, body) =>
      Response[IO](Status.Ok).withEntity(body)
        .putHeaders(Header.Raw(CIString("Content-Type"), mime))
    }

  /** Same-origin enforcement for state-changing JSON posts. */
  def checkOrigin(env: Env, request: Request[IO]): IO[Unit] =
    val origin = request.headers.get(CIString("Origin")).map(_.head.value)
    val site = request.headers.get(CIString("Sec-Fetch-Site")).map(_.head.value)
    val contentType = request.contentType.map(_.mediaType)
    for
      _ <- IO.raiseUnless(origin.contains(env.config.publicUrl))(
        XrpcError.named(Status.Forbidden, "InvalidOrigin",
          "Open this page directly on your PDS to continue"))
      _ <- IO.raiseUnless(site.forall(_ == "same-origin"))(
        XrpcError.named(Status.Forbidden, "InvalidOrigin",
          "Open this page directly on your PDS to continue"))
      _ <- IO.raiseUnless(contentType.contains(MediaType.application.json))(
        XrpcError.invalidRequest("Expected a JSON body"))
    yield ()

  private def render(env: Env, result: BrowserResult, extra: Json = Json.obj()): Response[IO] =
    if result.loggedOut then
      Response[IO](Status.Ok).withEntity(result.view.deepMerge(extra))
        .putHeaders(cookie(env, "security", None, 0))
    else
      Response[IO](Status.Ok)
        .withEntity(result.view.deepMerge(extra).deepMerge(
          Json.obj("result" -> result.result)))
        .putHeaders(cookie(env, "security", result.token, Browser.lifetimeSeconds))

  def routes(env: Env): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> path if asset(path.renderString).isDefined =>
      IO.pure(asset(path.renderString).get.putHeaders(headers*))

    case request @ GET -> Root / "account" / "session" =>
      Browser.open(env, readCookie(env, "security", request))
        .map(result => render(env, result, settings(env)).putHeaders(headers*))

    case request @ POST -> "account" /: "action" /: rest =>
      for
        _ <- checkOrigin(env, request)
        body <- Xrpc.body(request)
        name = rest.segments.map(_.encoded).mkString("/")
        csrf = request.headers.get(CIString("X-CSRF-Token")).map(_.head.value)
        token = readCookie(env, "security", request)
        result <-
          if name == "signup" then Browser.register(env, token, csrf, body)
          else Browser.action(env, token, csrf, name, body)
      yield render(env, result, settings(env)).putHeaders(headers*)
  }

  /** The session view carries the server policy the interface needs. */
  def settings(env: Env): Json = Json.obj(
    "origin" -> Json.fromString(env.config.publicUrl),
    "signup-enabled" -> Json.fromBoolean(env.config.signupEnabled),
    "email-enabled" -> Json.fromBoolean(env.config.emailEnabled),
    "invite-required" -> Json.fromBoolean(env.config.inviteRequired),
    "user-domain" -> Json.fromString(env.config.userDomain),
    "passkeys-available" -> Json.False
  )
