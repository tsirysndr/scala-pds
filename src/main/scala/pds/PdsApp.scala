package pds

import cats.effect.IO
import io.circe.Json
import org.http4s.{HttpApp, HttpRoutes, Response, Status}
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.headers.Allow

object PdsApp:
  def apply(config: ServerConfig): HttpApp[IO] =
    val routes = HttpRoutes.of[IO] {
      case GET -> Root / "_health" =>
        Ok(Json.obj("version" -> Json.fromString("0.1.0-SNAPSHOT")))

      case GET -> Root / "xrpc" / "com.atproto.server.describeServer" =>
        Ok(Json.obj(
          "did" -> Json.fromString(config.serviceDid),
          "availableUserDomains" -> Json.arr()
        ))

      case _ -> Root / "xrpc" / "com.atproto.server.describeServer" =>
        IO.pure(error(Status.MethodNotAllowed, "InvalidRequest", "Use GET for this query")
          .putHeaders(Allow(org.http4s.Method.GET)))

      case _ -> Root / "xrpc" / _ =>
        IO.pure(error(Status.BadRequest, "InvalidRequest", "Unknown XRPC method"))
    }
    HttpApp[IO] { request =>
      routes.run(request).getOrElse(error(Status.NotFound, "NotFound", "Route not found"))
    }

  private def error(status: Status, code: String, message: String): Response[IO] =
    Response[IO](status).withEntity(Json.obj(
      "error" -> Json.fromString(code), "message" -> Json.fromString(message)
    ))
