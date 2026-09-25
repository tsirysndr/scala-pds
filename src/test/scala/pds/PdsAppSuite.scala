package pds

import cats.effect.IO
import io.circe.Json
import org.http4s.{MediaType, Method, Request, Status, Uri}
import org.http4s.circe.*
import org.http4s.headers.Allow

class PdsAppSuite extends munit.CatsEffectSuite:
  private val config = ServerConfig.fromEnv(Map("PDS_HOSTNAME" -> "pds.example.com")).toOption.get
  private val app = PdsApp(config)
  private val describe = Uri.unsafeFromString("/xrpc/com.atproto.server.describeServer")

  test("describeServer returns the required lexicon fields without advertising account creation") {
    for
      response <- app.run(Request[IO](uri = describe))
      body <- response.as[Json]
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(response.contentType.map(_.mediaType), Some(MediaType.application.json))
      assertEquals(body, Json.obj(
        "did" -> Json.fromString("did:web:pds.example.com"),
        "availableUserDomains" -> Json.arr()
      ))
  }

  test("queries reject POST with a JSON error and Allow header") {
    for
      response <- app.run(Request[IO](method = Method.POST, uri = describe))
      body <- response.as[Json]
    yield
      assertEquals(response.status, Status.MethodNotAllowed)
      assertEquals(response.headers.get[Allow], Some(Allow(Method.GET)))
      assertEquals(body.hcursor.get[String]("error"), Right("InvalidRequest"))
  }

  test("unknown XRPC methods return an XRPC error") {
    for
      response <- app.run(Request[IO](uri = Uri.unsafeFromString("/xrpc/com.atproto.repo.getRecord")))
      body <- response.as[Json]
    yield
      assertEquals(response.status, Status.BadRequest)
      assertEquals(body.hcursor.get[String]("error"), Right("InvalidRequest"))
  }

  test("unknown HTTP routes return JSON 404") {
    for
      response <- app.run(Request[IO](uri = Uri.unsafeFromString("/missing")))
      body <- response.as[Json]
    yield
      assertEquals(response.status, Status.NotFound)
      assertEquals(body.hcursor.get[String]("error"), Right("NotFound"))
  }

  test("health endpoint reports the implementation version") {
    for
      response <- app.run(Request[IO](uri = Uri.unsafeFromString("/_health")))
      body <- response.as[Json]
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(body.hcursor.get[String]("version"), Right("0.1.0-SNAPSHOT"))
  }
