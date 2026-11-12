package pds.security

import cats.effect.IO
import io.circe.Json
import org.http4s.Status
import pds.{Env, XrpcError}
import scala.concurrent.duration.*

/** Public signup is protected at the account service, including direct XRPC. */
object Turnstile:
  val endpoint = "https://challenges.cloudflare.com/turnstile/v0/siteverify"

  def describe(env: Env): Json = env.config.turnstileSiteKey.fold(Json.Null)(key =>
    Json.obj("siteKey" -> Json.fromString(key), "action" -> Json.fromString("signup")))

  private def rejected = XrpcError.named(Status.BadRequest, "TurnstileFailed",
    "Complete the verification and try again")
  private def unavailable = XrpcError.named(Status.ServiceUnavailable, "TurnstileUnavailable",
    "Signup verification is temporarily unavailable; try again")

  def verify(env: Env, token: Option[String]): IO[Unit] =
    env.config.turnstileSecretKey match
      case None => IO.unit
      case Some(secret) =>
        for
          value <- IO.fromOption(token.filter(t => t.nonEmpty && t.length <= 2048))(rejected)
          response <- env.net.postJson(endpoint, Json.obj(
            "secret" -> Json.fromString(secret), "response" -> Json.fromString(value)))
            .timeoutTo(10.seconds, IO.raiseError(unavailable))
          result <- IO.fromEither(response.left.map(_ => unavailable))
          cursor = result.hcursor
          hosts = if env.config.turnstileHostnames.nonEmpty then env.config.turnstileHostnames
            else Set(java.net.URI.create(env.config.publicUrl).getHost)
          _ <- IO.raiseUnless(cursor.get[Boolean]("success").toOption.contains(true) &&
            cursor.get[String]("action").toOption.contains("signup") &&
            cursor.get[String]("hostname").toOption.exists(hosts.contains))(rejected)
        yield ()
