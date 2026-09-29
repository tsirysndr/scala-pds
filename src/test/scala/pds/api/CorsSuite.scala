package pds.api

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.typelevel.ci.CIString
import pds.TestEnv.*

/** Browser clients reach XRPC, OAuth and the identity documents from another
  * origin, so what a preflight is told decides whether they work at all.
  */
class CorsSuite extends munit.CatsEffectSuite:
  private def header(response: Response[IO], name: String): Option[String] =
    response.headers.get(CIString(name)).map(_.head.value)

  private def preflight(path: String, method: String, requested: Option[String]) =
    val base = Request[IO](Method.OPTIONS, Uri.unsafeFromString(path)).putHeaders(
      Header.Raw(CIString("Origin"), "https://app.example.com"),
      Header.Raw(CIString("Access-Control-Request-Method"), method))
    requested.fold(base)(value =>
      base.putHeaders(Header.Raw(CIString("Access-Control-Request-Headers"), value)))

  private def register(server: Harness) =
    server.json(post("/xrpc/com.atproto.server.createAccount", Json.obj(
      "handle" -> Json.fromString("alice.pds.example.com"),
      "email" -> Json.fromString("alice@example.com"),
      "password" -> Json.fromString("correct horse battery"))))
      .map(_._2.hcursor.get[String]("did").toOption.get)

  test("a preflight is answered with the headers it asked for, and cached") {
    harness().use { server =>
      server.run(preflight("/xrpc/com.atproto.repo.createRecord", "POST",
        Some("authorization, content-type, dpop, atproto-proxy"))).map { response =>
        assertEquals(response.status, Status.NoContent)
        assertEquals(header(response, "Access-Control-Allow-Origin"), Some("*"))
        assertEquals(header(response, "Access-Control-Allow-Headers"),
          Some("authorization, content-type, dpop, atproto-proxy"))
        assertEquals(header(response, "Access-Control-Max-Age"), Some("600"))
        assertEquals(header(response, "Vary"),
          Some("Access-Control-Request-Method, Access-Control-Request-Headers"))
      }
    }
  }

  test("a preflight that names no headers is told the ones clients usually send") {
    harness().use { server =>
      for
        silent <- server.run(preflight("/xrpc/com.atproto.server.getSession", "GET", None))
        malformed <- server.run(preflight("/xrpc/com.atproto.server.getSession", "GET",
          Some("authorization, bad header")))
      yield
        val expected = Some("Authorization, Content-Type, DPoP, atproto-accept-labelers, atproto-proxy")
        assertEquals(header(silent, "Access-Control-Allow-Headers"), expected)
        // A name that is not an RFC 7230 token is never echoed back.
        assertEquals(header(malformed, "Access-Control-Allow-Headers"), expected)
      }
  }

  test("the identity documents are readable from another origin") {
    harness().use { server =>
      for
        _ <- register(server)
        document <- server.run(get("/.well-known/did.json")
          .putHeaders(Header.Raw(CIString("Origin"), "https://app.example.com")))
        handle <- server.run(get("/.well-known/atproto-did")
          .putHeaders(Header.Raw(CIString("Host"), "alice.pds.example.com")))
        metadata <- server.run(get("/.well-known/oauth-protected-resource"))
      yield
        assertEquals(header(document, "Access-Control-Allow-Origin"), Some("*"))
        assertEquals(header(handle, "Access-Control-Allow-Origin"), Some("*"))
        assertEquals(header(metadata, "Access-Control-Allow-Origin"), Some("*"))
    }
  }

  test("the cookie-authenticated account interface stays same-origin") {
    harness().use { server =>
      for
        session <- server.run(get("/account/session")
          .putHeaders(Header.Raw(CIString("Origin"), "https://app.example.com")))
        page <- server.run(get("/account"))
      yield
        assertEquals(header(session, "Access-Control-Allow-Origin"), None)
        assertEquals(header(page, "Access-Control-Allow-Origin"), None)
    }
  }
