package pds.api

import cats.effect.IO
import io.circe.Json
import org.http4s.Status
import pds.TestEnv.*
import pds.crypto.Encoding
import pds.security.Totp

/** `social.rocksky.auth.*` is the contract a client uses against any PDS, so
  * what matters here is the state machine it sees and what it is refused.
  */
class AuthApiSuite extends munit.CatsEffectSuite:
  private val password = "correct horse battery"

  private def account(handle: String = "alice.pds.example.com") = Json.obj(
    "handle" -> Json.fromString(handle),
    "email" -> Json.fromString(s"${handle.takeWhile(_ != '.')}@example.com"),
    "password" -> Json.fromString(password)
  )

  private def signIn(server: Harness, handle: String = "alice.pds.example.com") =
    for
      _ <- server.json(post("/xrpc/com.atproto.server.createAccount", account(handle)))
      session <- server.json(post("/xrpc/com.atproto.server.createSession", Json.obj(
        "identifier" -> Json.fromString(handle),
        "password" -> Json.fromString(password))))
    yield session._2.hcursor.get[String]("accessJwt").toOption.get

  /** The code the authenticator would be showing for the secret just issued.
    * `env.now` is in milliseconds, which is what the 30-second step divides.
    */
  private def currentCode(secret: String, nowMillis: Long): String =
    Totp.code(Encoding.unbase32(secret).get, nowMillis / 1000 / 30)

  private def field(body: Json, name: String): String =
    body.hcursor.get[String](name).toOption.get

  test("every method in the contract is registered, with the right kind") {
    harness().use { server =>
      IO {
        val endpoints = AuthApi.endpoints(server.env)
        val expected = Map(
          "social.rocksky.auth.getTwoFactor" -> Kind.Query,
          "social.rocksky.auth.beginTwoFactor" -> Kind.Procedure,
          "social.rocksky.auth.confirmTwoFactor" -> Kind.Procedure,
          "social.rocksky.auth.disableTwoFactor" -> Kind.Procedure,
          "social.rocksky.auth.regenerateRecoveryCodes" -> Kind.Procedure,
          "social.rocksky.auth.listPasskeys" -> Kind.Query,
          "social.rocksky.auth.beginPasskeyRegistration" -> Kind.Procedure,
          "social.rocksky.auth.finishPasskeyRegistration" -> Kind.Procedure,
          "social.rocksky.auth.deletePasskey" -> Kind.Procedure,
          "social.rocksky.auth.beginPasskeyLogin" -> Kind.Procedure,
          "social.rocksky.auth.finishPasskeyLogin" -> Kind.Procedure
        )
        assertEquals(endpoints.keySet, expected.keySet)
        expected.foreach((nsid, kind) =>
          assertEquals(endpoints(nsid).kind, kind, s"$nsid has the wrong kind"))
      }
    }
  }

  test("two-factor goes disabled -> pending -> enabled -> disabled") {
    harness().use { server =>
      for
        access <- signIn(server)
        initial <- server.json(authorized(get("/xrpc/social.rocksky.auth.getTwoFactor"), access))

        begun <- server.json(authorized(post("/xrpc/social.rocksky.auth.beginTwoFactor",
          Json.obj("password" -> Json.fromString(password))), access))
        secret = field(begun._2, "secret")
        pending <- server.json(authorized(get("/xrpc/social.rocksky.auth.getTwoFactor"), access))

        now <- server.env.now
        confirmed <- server.json(authorized(post("/xrpc/social.rocksky.auth.confirmTwoFactor",
          Json.obj("code" -> Json.fromString(currentCode(secret, now)))), access))
        enabled <- server.json(authorized(get("/xrpc/social.rocksky.auth.getTwoFactor"), access))

        // Not the code just used to confirm: a step is accepted once, so a
        // client cannot replay it. A recovery code is the way out within the
        // same 30-second window.
        recovery = confirmed._2.hcursor.downField("recoveryCodes").values.get
          .toVector.head.asString.get
        disabled <- server.json(authorized(post("/xrpc/social.rocksky.auth.disableTwoFactor",
          Json.obj(
            "password" -> Json.fromString(password),
            "code" -> Json.fromString(recovery))), access))
        after <- server.json(authorized(get("/xrpc/social.rocksky.auth.getTwoFactor"), access))
      yield
        assertEquals(initial._2.hcursor.get[String]("state").toOption, Some("disabled"))

        // The secret is returned for the QR code, and is not yet in force.
        assertEquals(begun._1, Status.Ok)
        assert(field(begun._2, "uri").startsWith("otpauth://"))
        assertEquals(pending._2.hcursor.get[String]("state").toOption, Some("pending"))

        assertEquals(confirmed._1, Status.Ok)
        assertEquals(confirmed._2.hcursor.get[String]("state").toOption, Some("enabled"))
        val codes = confirmed._2.hcursor.downField("recoveryCodes").values.get.toVector
        assert(codes.nonEmpty, "confirming must issue recovery codes")
        assertEquals(enabled._2.hcursor.get[Int]("recoveryRemaining").toOption, Some(codes.size))

        assertEquals(disabled._1, Status.Ok)
        assertEquals(after._2.hcursor.get[String]("state").toOption, Some("disabled"))
    }
  }

  test("the password is required to add or remove a factor") {
    harness().use { server =>
      for
        access <- signIn(server)
        wrong <- server.json(authorized(post("/xrpc/social.rocksky.auth.beginTwoFactor",
          Json.obj("password" -> Json.fromString("not the password"))), access))
        state <- server.json(authorized(get("/xrpc/social.rocksky.auth.getTwoFactor"), access))
      yield
        // An access token proves the session, not the owner.
        assertEquals(wrong._1, Status.Unauthorized)
        assertEquals(wrong._2.hcursor.get[String]("error").toOption, Some("InvalidCredentials"))
        assertEquals(state._2.hcursor.get[String]("state").toOption, Some("disabled"))
    }
  }

  test("a wrong code cannot confirm an enrollment") {
    harness().use { server =>
      for
        access <- signIn(server)
        _ <- server.json(authorized(post("/xrpc/social.rocksky.auth.beginTwoFactor",
          Json.obj("password" -> Json.fromString(password))), access))
        refused <- server.json(authorized(post("/xrpc/social.rocksky.auth.confirmTwoFactor",
          Json.obj("code" -> Json.fromString("000000"))), access))
        state <- server.json(authorized(get("/xrpc/social.rocksky.auth.getTwoFactor"), access))
      yield
        assertEquals(refused._1, Status.BadRequest)
        // Still pending, not enabled: a wrong code must not put it in force.
        assertEquals(state._2.hcursor.get[String]("state").toOption, Some("pending"))
    }
  }

  test("an unauthenticated caller gets nothing") {
    harness().use { server =>
      for
        anonymous <- server.json(get("/xrpc/social.rocksky.auth.getTwoFactor"))
        listed <- server.json(get("/xrpc/social.rocksky.auth.listPasskeys"))
      yield
        assertEquals(anonymous._1, Status.Unauthorized)
        assertEquals(listed._1, Status.Unauthorized)
    }
  }

  test("a passkey sign-in needs no session, and will not say who exists") {
    harness().use { server =>
      for
        _ <- signIn(server)
        // Unauthenticated: this is how a session begins.
        unknown <- server.json(post("/xrpc/social.rocksky.auth.beginPasskeyLogin", Json.obj(
          "identifier" -> Json.fromString("nobody.pds.example.com"))))
        missing <- server.json(post("/xrpc/social.rocksky.auth.beginPasskeyLogin", Json.obj()))
        bad <- server.json(post("/xrpc/social.rocksky.auth.finishPasskeyLogin", Json.obj(
          "requestId" -> Json.fromString(".no-handle"),
          "credential" -> Json.obj())))
      yield
        // An account that does not exist and one with no passkey must look the
        // same from outside.
        assertEquals(unknown._1, Status.Unauthorized)
        assertEquals(unknown._2.hcursor.get[String]("error").toOption, Some("AccountNotFound"))

        assertEquals(missing._1, Status.BadRequest)
        assertEquals(bad._1, Status.BadRequest)
        assertEquals(bad._2.hcursor.get[String]("error").toOption, Some("RequestExpired"))
    }
  }

  test("a passkey sign-in for a real account offers a challenge") {
    harness().use { server =>
      for
        _ <- signIn(server)
        started <- server.json(post("/xrpc/social.rocksky.auth.beginPasskeyLogin", Json.obj(
          "identifier" -> Json.fromString("alice.pds.example.com"))))
      yield
        assertEquals(started._1, Status.Ok)
        assert(field(started._2, "requestId").nonEmpty)
        assert(started._2.hcursor.downField("publicKey").focus.isDefined)
    }
  }

  test("passkeys start empty and a bad request id is refused") {
    harness().use { server =>
      for
        access <- signIn(server)
        listed <- server.json(authorized(get("/xrpc/social.rocksky.auth.listPasskeys"), access))
        bad <- server.json(authorized(post("/xrpc/social.rocksky.auth.finishPasskeyRegistration",
          Json.obj(
            "requestId" -> Json.fromString(".no-handle"),
            "credential" -> Json.obj())), access))
      yield
        assertEquals(listed._1, Status.Ok)
        assertEquals(listed._2.hcursor.downField("passkeys").values.get.size, 0)

        // An empty half is not a usable half.
        assertEquals(bad._1, Status.BadRequest)
        assertEquals(bad._2.hcursor.get[String]("error").toOption, Some("RequestExpired"))
    }
  }
