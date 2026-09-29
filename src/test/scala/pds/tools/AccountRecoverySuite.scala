package pds.tools

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.typelevel.ci.CIString
import pds.TestEnv.*
import pds.security.{Totp, VirtualAuthenticator}
import pds.storage.Sql

class AccountRecoverySuite extends munit.CatsEffectSuite:
  private val origin = "https://pds.example.com"

  private def cookieOf(response: Response[IO]): Option[String] =
    response.headers.get(CIString("Set-Cookie")).toVector.flatMap(_.toList)
      .map(_.value).find(_.startsWith("__Host-pds-security="))
      .map(_.drop("__Host-pds-security=".length).takeWhile(_ != ';'))

  private def action(name: String, body: Json, cookie: String, csrf: String): Request[IO] =
    post(s"/account/action/$name", body).putHeaders(
      Header.Raw(CIString("Origin"), origin),
      Header.Raw(CIString("Sec-Fetch-Site"), "same-origin"),
      Header.Raw(CIString("X-CSRF-Token"), csrf),
      Header.Raw(CIString("Cookie"), s"__Host-pds-security=$cookie"))

  private def register(server: Harness) =
    server.json(post("/xrpc/com.atproto.server.createAccount", Json.obj(
      "handle" -> Json.fromString("alice.pds.example.com"),
      "email" -> Json.fromString("alice@example.com"),
      "password" -> Json.fromString("correct horse battery"))))

  private def signIn(server: Harness) =
    for
      opened <- server.run(get("/account/session"))
      openedBody <- opened.as[Json]
      in <- server.run(action("login/password", Json.obj(
        "identifier" -> Json.fromString("alice.pds.example.com"),
        "password" -> Json.fromString("correct horse battery")),
        cookieOf(opened).get, openedBody.hcursor.get[String]("csrf").toOption.get))
      body <- in.as[Json]
    yield (cookieOf(in).get, body.hcursor.get[String]("csrf").toOption.get, body)

  /** An account locked behind an authenticator and a passkey. */
  private def locked(server: Harness): IO[String] =
    for
      created <- register(server)
      did = created._2.hcursor.get[String]("did").toOption.get
      session <- signIn(server)
      (cookie, csrf, _) = session
      begun <- server.run(action("totp/begin", Json.obj(), cookie, csrf))
      begunBody <- begun.as[Json]
      secret = begunBody.hcursor.downField("result").get[String]("secret").toOption.get
      bytes = pds.crypto.Encoding.unbase32(secret.toLowerCase).get
      now <- server.env.now
      confirmed <- server.run(action("totp/confirm",
        Json.obj("code" -> Json.fromString(Totp.code(bytes, now / 1000 / 30))),
        cookieOf(begun).get, begunBody.hcursor.get[String]("csrf").toOption.get))
      confirmedBody <- confirmed.as[Json]
      authenticator = new VirtualAuthenticator()
      passkeyBegun <- server.run(action("passkeys/begin",
        Json.obj("name" -> Json.fromString("laptop")),
        cookieOf(confirmed).get, confirmedBody.hcursor.get[String]("csrf").toOption.get))
      passkeyBody <- passkeyBegun.as[Json]
      _ <- server.run(action("passkeys/finish", Json.obj(
        "id" -> Json.fromString(
          passkeyBody.hcursor.downField("result").get[String]("id").toOption.get),
        "response" -> Json.fromString(authenticator.register("pds.example.com",
          passkeyBody.hcursor.downField("result").downField("options").downField("publicKey")
            .get[String]("challenge").toOption.get, origin))),
        cookieOf(passkeyBegun).get, passkeyBody.hcursor.get[String]("csrf").toOption.get))
    yield did

  test("recovery clears every second factor and ends existing sessions") {
    harness().use { server =>
      for
        did <- locked(server)
        access <- server.json(post("/xrpc/com.atproto.server.createSession", Json.obj(
          "identifier" -> Json.fromString("alice.pds.example.com"),
          "password" -> Json.fromString("correct horse battery"))))
          .map(_._2.hcursor.get[String]("accessJwt").toOption.get)
        before <- server.json(authorized(get("/xrpc/com.atproto.server.getSession"), access))
        summary <- AccountRecovery.run(server.env, "alice.pds.example.com", "ticket-42")
        after <- server.json(authorized(get("/xrpc/com.atproto.server.getSession"), access))
        counts <- server.env.database.read { connection =>
          (Sql.count(connection, "SELECT COUNT(*) AS total FROM account_totp WHERE did = ?", did),
            Sql.count(connection,
              "SELECT COUNT(*) AS total FROM account_recovery_codes WHERE did = ?", did),
            Sql.count(connection,
              "SELECT COUNT(*) AS total FROM account_passkeys WHERE did = ?", did))
        }
        // The password still works, and now reaches the authenticated stage.
        opened <- server.run(get("/account/session"))
        openedBody <- opened.as[Json]
        fresh <- server.run(action("login/password", Json.obj(
          "identifier" -> Json.fromString("alice.pds.example.com"),
          "password" -> Json.fromString("correct horse battery")),
          cookieOf(opened).get, openedBody.hcursor.get[String]("csrf").toOption.get))
        freshBody <- fresh.as[Json]
      yield
        assertEquals(before._1, Status.Ok)
        assertEquals(summary.did, did)
        assertEquals(summary.handle, "alice.pds.example.com")
        assert(summary.authenticator)
        assertEquals(summary.recoveryCodes, 8)
        assertEquals(summary.passkeys, 1)
        assertEquals(after._1, Status.Unauthorized, "existing sessions must end")
        assertEquals(counts, (0L, 0L, 0L))
        assertEquals(freshBody.hcursor.get[String]("stage"), Right("authenticated"))
    }
  }

  test("every recovery is recorded with its reference") {
    harness().use { server =>
      for
        did <- locked(server)
        first <- AccountRecovery.run(server.env, did, "ticket-42")
        second <- AccountRecovery.run(server.env, did, "ticket-43")
        history <- AccountRecovery.history(server.env, did)
      yield
        assertEquals(history.length, 2)
        assertEquals(history.head.hcursor.get[String]("reference"), Right("ticket-42"))
        assertEquals(history.head.hcursor.downField("removed").get[Boolean]("authenticator"),
          Right(true))
        assertEquals(history.head.hcursor.downField("removed").get[Int]("passkeys"), Right(1))
        assertEquals(history(1).hcursor.get[String]("reference"), Right("ticket-43"))
        // The second pass found nothing left to remove but still bumped the epoch.
        assert(!second.removedAnything)
        assert(second.epoch > first.epoch)
    }
  }

  test("an unknown account or a missing reference is refused") {
    harness().use { server =>
      for
        _ <- register(server)
        unknown <- AccountRecovery.run(server.env, "nobody.pds.example.com", "ticket").attempt
        blank <- AccountRecovery.run(server.env, "alice.pds.example.com", "  ").attempt
        tooLong <- AccountRecovery.run(server.env, "alice.pds.example.com", "x" * 200).attempt
        history <- server.env.database.read(connection =>
          Sql.count(connection, "SELECT COUNT(*) AS total FROM account_recoveries"))
      yield
        assert(unknown.left.exists(_.getMessage.contains("No account matches")))
        assert(blank.isLeft)
        assert(tooLong.isLeft)
        assertEquals(history, 0L)
    }
  }
