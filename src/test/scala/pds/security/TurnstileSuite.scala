package pds.security

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import pds.TestEnv.*
import pds.{ServerConfig, XrpcError}

class TurnstileSuite extends munit.CatsEffectSuite:
  private val settings = Map("PDS_TURNSTILE_SITE_KEY" -> "site", "PDS_TURNSTILE_SECRET_KEY" -> "secret")
  private val valid = Json.obj("success" -> Json.True, "action" -> Json.fromString("signup"),
    "hostname" -> Json.fromString("pds.example.com"))
  private val account = Json.obj("handle" -> Json.fromString("alice.pds.example.com"),
    "email" -> Json.fromString("alice@example.com"), "password" -> Json.fromString("correct horse battery"))

  test("partial configuration is refused") {
    IO {
      assert(ServerConfig.fromEnv(Map("PDS_TURNSTILE_SITE_KEY" -> "site")).isLeft)
      assert(ServerConfig.fromEnv(Map("PDS_TURNSTILE_SECRET_KEY" -> "secret")).isLeft)
    }
  }

  test("direct XRPC cannot omit verification or bypass it with an invite or DID") {
    harness(settings).use { server =>
      for
        plain <- server.json(post("/xrpc/com.atproto.server.createAccount", account))
        invited <- server.json(post("/xrpc/com.atproto.server.createAccount", account.deepMerge(
          Json.obj("inviteCode" -> Json.fromString("not-a-bypass")))))
        imported <- server.json(post("/xrpc/com.atproto.server.createAccount", account.deepMerge(
          Json.obj("did" -> Json.fromString("did:plc:fake")))))
      yield
        List(plain, invited, imported).foreach { result =>
          assertEquals(result._1, Status.BadRequest)
          assertEquals(result._2.hcursor.get[String]("error"), Right("TurnstileFailed"))
        }
    }
  }

  test("a verified public signup creates an account without an invitation") {
    val client = routes { case request if request.uri.renderString == Turnstile.endpoint =>
      request.as[Json].flatMap { body =>
        assertEquals(body.hcursor.get[String]("secret"), Right("secret"))
        assertEquals(body.hcursor.get[String]("response"), Right("token"))
        IO.pure(Response[IO](Status.Ok).withEntity(valid))
      }
    }
    harness(settings, client).use { server =>
      server.json(post("/xrpc/com.atproto.server.createAccount", account.deepMerge(
        Json.obj("turnstileToken" -> Json.fromString("token"))))).map { result =>
        assertEquals(result._1, Status.Ok)
        assertEquals(result._2.hcursor.get[String]("handle"), Right("alice.pds.example.com"))
      }
    }
  }

  test("wrong host, action, failed challenge and provider outages are refused") {
    val bodies = List(valid.deepMerge(Json.obj("hostname" -> Json.fromString("attacker.example"))),
      valid.deepMerge(Json.obj("action" -> Json.fromString("login"))),
      valid.deepMerge(Json.obj("success" -> Json.False)))
    bodies.foldLeft(IO.unit) { (previous, body) => previous *> {
      harness(settings, routes { case _ => IO.pure(Response[IO](Status.Ok).withEntity(body)) }).use { server =>
        Turnstile.verify(server.env, Some("token")).attempt.map { result =>
          assertEquals(result.left.toOption.map(_.asInstanceOf[XrpcError].getMessage),
            Some("Complete the verification and try again"))
        }
      }
    }} *> harness(settings).use { server =>
      server.json(post("/xrpc/com.atproto.server.createAccount", account.deepMerge(
        Json.obj("turnstileToken" -> Json.fromString("token"))))).map(result =>
          assertEquals(result._1, Status.ServiceUnavailable))
    }
  }
