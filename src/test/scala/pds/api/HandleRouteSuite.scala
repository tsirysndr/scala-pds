package pds.api

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.typelevel.ci.CIString
import pds.TestEnv.*

/** `/.well-known/atproto-did` is how another server resolves a handle hosted
  * here when the domain has no DNS record.
  */
class HandleRouteSuite extends munit.CatsEffectSuite:
  private def register(server: Harness) =
    server.json(post("/xrpc/com.atproto.server.createAccount", Json.obj(
      "handle" -> Json.fromString("alice.pds.example.com"),
      "email" -> Json.fromString("alice@example.com"),
      "password" -> Json.fromString("correct horse battery"))))
      .map(_._2.hcursor.get[String]("did").toOption.get)

  private def resolve(server: Harness, host: String) =
    server.run(get("/.well-known/atproto-did")
      .putHeaders(Header.Raw(CIString("Host"), host)))

  test("a handle hosted here resolves at its own domain") {
    harness().use { server =>
      for
        did <- register(server)
        found <- resolve(server, "alice.pds.example.com")
        body <- found.body.compile.to(Array).map(new String(_, "UTF-8"))
        cased <- resolve(server, "ALICE.pds.example.com:3000")
        casedBody <- cased.body.compile.to(Array).map(new String(_, "UTF-8"))
        absent <- resolve(server, "bob.pds.example.com")
        service <- server.run(get("/.well-known/atproto-did"))
      yield
        assertEquals(found.status, Status.Ok)
        assertEquals(body, did)
        assertEquals(found.contentType.map(_.mediaType), Some(MediaType.text.plain))
        assertEquals(found.headers.get(CIString("Cache-Control")).map(_.head.value),
          Some("no-store"))
        // The port is stripped and the host is lowercased before the lookup.
        assertEquals(casedBody, did)
        assertEquals(absent.status, Status.NotFound)
        // The PDS's own domain is not a handle, so it answers for nobody.
        assertEquals(service.status, Status.NotFound)
    }
  }
