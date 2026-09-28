package pds.api

import cats.effect.IO
import io.circe.Json
import org.http4s.{Request, Response}
import pds.{Env, XrpcError}
import pds.accounts.Preferences

object BskyApi:
  def endpoints(env: Env): Map[String, Endpoint] = Map(
    "app.bsky.actor.getPreferences" -> Endpoint.query(getPreferences(env, _)),
    "app.bsky.actor.putPreferences" -> Endpoint.procedure(putPreferences(env, _))
  )

  private def getPreferences(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      values <- env.database.read(connection =>
        Preferences.list(connection, session.did, "app.bsky"))
      response <- Xrpc.ok(Json.obj("preferences" -> Json.arr(values*)))
    yield response

  private def putPreferences(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      body <- Xrpc.body(request)
      values <- IO.fromOption(body.hcursor.downField("preferences").values.map(_.toVector))(
        XrpcError.invalidRequest("preferences is required"))
      _ <- env.database.transact(connection =>
        Preferences.replace(connection, session.did, "app.bsky", values))
      response <- Xrpc.empty
    yield response
