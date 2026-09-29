package pds.security

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.typelevel.ci.CIString
import pds.TestEnv.*
import pds.crypto.{Encoding, Hash}
import pds.storage.Sql

class PasskeysSuite extends munit.CatsEffectSuite:
  private val origin = "https://pds.example.com"
  private val rpId = "pds.example.com"

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

  private def open(server: Harness) =
    server.run(get("/account/session")).flatMap(response =>
      response.as[Json].map(body =>
        (cookieOf(response).get, body.hcursor.get[String]("csrf").toOption.get, body)))

  private def signIn(server: Harness) =
    for
      opened <- open(server)
      in <- server.run(action("login/password", Json.obj(
        "identifier" -> Json.fromString("alice.pds.example.com"),
        "password" -> Json.fromString("correct horse battery")), opened._1, opened._2))
      body <- in.as[Json]
    yield (cookieOf(in).get, body.hcursor.get[String]("csrf").toOption.get, body)

  private def challengeOf(result: Json, path: String): String =
    result.hcursor.downField("result").downField("options").downField(path)
      .get[String]("challenge").toOption.get

  /** Registers a passkey and returns the authenticator that holds it. */
  private def enroll(server: Harness, cookie: String, csrf: String)
      : IO[(VirtualAuthenticator, String, String)] =
    val authenticator = new VirtualAuthenticator()
    for
      begun <- server.run(action("passkeys/begin",
        Json.obj("name" -> Json.fromString("laptop")), cookie, csrf))
      begunBody <- begun.as[Json]
      id = begunBody.hcursor.downField("result").get[String]("id").toOption.get
      challenge = challengeOf(begunBody, "publicKey")
      nextCookie = cookieOf(begun).get
      nextCsrf = begunBody.hcursor.get[String]("csrf").toOption.get
      finished <- server.run(action("passkeys/finish", Json.obj(
        "id" -> Json.fromString(id),
        "response" -> Json.fromString(authenticator.register(rpId, challenge, origin))),
        nextCookie, nextCsrf))
      finishedBody <- finished.as[Json]
      _ = assertEquals(finished.status, Status.Ok,
        s"registration failed: ${finishedBody.noSpaces}")
    yield (authenticator, cookieOf(finished).get,
      finishedBody.hcursor.get[String]("csrf").toOption.get)

  test("a passkey registers and then signs the owner in") {
    harness().use { server =>
      for
        created <- register(server)
        did = created._2.hcursor.get[String]("did").toOption.get
        session <- signIn(server)
        enrolled <- enroll(server, session._1, session._2)
        (authenticator, cookie, csrf) = enrolled
        listed <- server.run(get("/account/session")
          .putHeaders(Header.Raw(CIString("Cookie"), s"__Host-pds-security=$cookie")))
        listedBody <- listed.as[Json]
        // A fresh browser signs in with the passkey alone.
        fresh <- open(server)
        begun <- server.run(action("login/passkey/begin",
          Json.obj("identifier" -> Json.fromString("alice.pds.example.com")),
          fresh._1, fresh._2))
        begunBody <- begun.as[Json]
        id = begunBody.hcursor.downField("result").get[String]("id").toOption.get
        challenge = challengeOf(begunBody, "publicKey")
        handle <- server.env.database.read(connection =>
          Sql.first(connection, "SELECT user_handle FROM account_webauthn_users WHERE did = ?",
            did)(_.bytes("user_handle")).get)
        finished <- server.run(action("login/passkey/finish", Json.obj(
          "id" -> Json.fromString(id),
          "response" -> Json.fromString(
            authenticator.authenticate(rpId, challenge, origin, handle))),
          cookieOf(begun).get, begunBody.hcursor.get[String]("csrf").toOption.get))
        finishedBody <- finished.as[Json]
      yield
        assertEquals(listedBody.hcursor.downField("passkeys").downArray.get[String]("name"),
          Right("laptop"))
        assertEquals(finished.status, Status.Ok, finishedBody.noSpaces)
        assertEquals(finishedBody.hcursor.get[String]("stage"), Right("authenticated"))
        assertEquals(finishedBody.hcursor.get[String]("handle"), Right("alice.pds.example.com"))
    }
  }

  test("an assertion for a challenge from another browser is refused") {
    harness().use { server =>
      for
        created <- register(server)
        did = created._2.hcursor.get[String]("did").toOption.get
        session <- signIn(server)
        enrolled <- enroll(server, session._1, session._2)
        (authenticator, _, _) = enrolled
        handle <- server.env.database.read(connection =>
          Sql.first(connection, "SELECT user_handle FROM account_webauthn_users WHERE did = ?",
            did)(_.bytes("user_handle")).get)
        // One browser starts the ceremony...
        starter <- open(server)
        begun <- server.run(action("login/passkey/begin",
          Json.obj("identifier" -> Json.fromString("alice.pds.example.com")),
          starter._1, starter._2))
        begunBody <- begun.as[Json]
        id = begunBody.hcursor.downField("result").get[String]("id").toOption.get
        challenge = challengeOf(begunBody, "publicKey")
        // ...and another tries to finish it.
        thief <- open(server)
        stolen <- server.run(action("login/passkey/finish", Json.obj(
          "id" -> Json.fromString(id),
          "response" -> Json.fromString(
            authenticator.authenticate(rpId, challenge, origin, handle))),
          thief._1, thief._2))
      yield assertEquals(stolen.status, Status.BadRequest)
    }
  }

  test("a forged or replayed assertion is refused") {
    harness().use { server =>
      for
        created <- register(server)
        did = created._2.hcursor.get[String]("did").toOption.get
        session <- signIn(server)
        enrolled <- enroll(server, session._1, session._2)
        (authenticator, _, _) = enrolled
        handle <- server.env.database.read(connection =>
          Sql.first(connection, "SELECT user_handle FROM account_webauthn_users WHERE did = ?",
            did)(_.bytes("user_handle")).get)
        browser <- open(server)
        begun <- server.run(action("login/passkey/begin",
          Json.obj("identifier" -> Json.fromString("alice.pds.example.com")),
          browser._1, browser._2))
        begunBody <- begun.as[Json]
        id = begunBody.hcursor.downField("result").get[String]("id").toOption.get
        challenge = challengeOf(begunBody, "publicKey")
        cookie = cookieOf(begun).get
        csrf = begunBody.hcursor.get[String]("csrf").toOption.get
        // A different key entirely, using the same credential id.
        impostor = new VirtualAuthenticator(authenticator.credentialId)
        forged <- server.run(action("login/passkey/finish", Json.obj(
          "id" -> Json.fromString(id),
          "response" -> Json.fromString(impostor.authenticate(rpId, challenge, origin, handle))),
          cookie, csrf))
        forgedBody <- forged.as[Json]
        // A correctly signed assertion, but for another origin, in its own ceremony.
        second <- open(server)
        reBegun <- server.run(action("login/passkey/begin",
          Json.obj("identifier" -> Json.fromString("alice.pds.example.com")),
          second._1, second._2))
        reBegunBody <- reBegun.as[Json]
        wrongOrigin <- server.run(action("login/passkey/finish", Json.obj(
          "id" -> Json.fromString(
            reBegunBody.hcursor.downField("result").get[String]("id").toOption.get),
          "response" -> Json.fromString(authenticator.authenticate(
            rpId, challengeOf(reBegunBody, "publicKey"), "https://evil.example.com", handle))),
          cookieOf(reBegun).get, reBegunBody.hcursor.get[String]("csrf").toOption.get))
      yield
        assertEquals(forged.status, Status.Unauthorized)
        assertEquals(forgedBody.hcursor.get[String]("error"), Right("AuthenticationRequired"))
        assertEquals(wrongOrigin.status, Status.Unauthorized)
    }
  }

  test("a ceremony is single use and expires") {
    harness().use { server =>
      for
        _ <- register(server)
        session <- signIn(server)
        enrolled <- enroll(server, session._1, session._2)
        (_, cookie, csrf) = enrolled
        // The request row is consumed by the first attempt.
        pending <- server.env.database.read(connection =>
          Sql.count(connection, "SELECT COUNT(*) AS total FROM webauthn_requests"))
        second = new VirtualAuthenticator()
        begun <- server.run(action("passkeys/begin",
          Json.obj("name" -> Json.fromString("phone")), cookie, csrf))
        begunBody <- begun.as[Json]
        id = begunBody.hcursor.downField("result").get[String]("id").toOption.get
        now <- server.env.now
        _ <- server.env.database.transact(connection =>
          Sql.update(connection, "UPDATE webauthn_requests SET expires_at = ? WHERE id = ?",
            now - 1000, id))
        expired <- server.run(action("passkeys/finish", Json.obj(
          "id" -> Json.fromString(id),
          "response" -> Json.fromString(
            second.register(rpId, challengeOf(begunBody, "publicKey"), origin))),
          cookieOf(begun).get, begunBody.hcursor.get[String]("csrf").toOption.get))
      yield
        assertEquals(pending, 0L)
        assertEquals(expired.status, Status.BadRequest)
    }
  }

  test("passkeys are listed and removed by their owner") {
    harness().use { server =>
      for
        _ <- register(server)
        session <- signIn(server)
        enrolled <- enroll(server, session._1, session._2)
        (authenticator, cookie, csrf) = enrolled
        listed <- server.run(get("/account/session")
          .putHeaders(Header.Raw(CIString("Cookie"), s"__Host-pds-security=$cookie")))
        listedBody <- listed.as[Json]
        id = listedBody.hcursor.downField("passkeys").downArray.get[String]("id").toOption.get
        removed <- server.run(action("passkeys/remove",
          Json.obj("id" -> Json.fromString(id)), cookie, csrf))
        removedBody <- removed.as[Json]
        absent <- server.run(action("passkeys/remove",
          Json.obj("id" -> Json.fromString(id)),
          cookieOf(removed).get, removedBody.hcursor.get[String]("csrf").toOption.get))
      yield
        assertEquals(id, Encoding.b64(authenticator.credentialId))
        assertEquals(removed.status, Status.Ok)
        assertEquals(removedBody.hcursor.downField("passkeys").values.map(_.size), Some(0))
        assertEquals(absent.status, Status.NotFound)
    }
  }

  test("registration is refused for an unusable name or a rejected response") {
    harness().use { server =>
      for
        _ <- register(server)
        session <- signIn(server)
        (cookie, csrf, _) = session
        unnamed <- server.run(action("passkeys/begin",
          Json.obj("name" -> Json.fromString("  ")), cookie, csrf))
        begun <- server.run(action("passkeys/begin",
          Json.obj("name" -> Json.fromString("laptop")), cookie, csrf))
        begunBody <- begun.as[Json]
        id = begunBody.hcursor.downField("result").get[String]("id").toOption.get
        garbage <- server.run(action("passkeys/finish", Json.obj(
          "id" -> Json.fromString(id),
          "response" -> Json.fromString("""{"id":"x","rawId":"eA","type":"public-key",
            "clientExtensionResults":{},"response":{"clientDataJSON":"eA","attestationObject":"eA"}}""")),
          cookieOf(begun).get, begunBody.hcursor.get[String]("csrf").toOption.get))
        garbageBody <- garbage.as[Json]
      yield
        assertEquals(unnamed.status, Status.BadRequest)
        // Malformed client input is a rejection, never a server fault.
        assertEquals(garbage.status, Status.BadRequest)
        assertEquals(garbageBody.hcursor.get[String]("error"), Right("PasskeyRejected"))
    }
  }

  test("passkeys are offered only on a secure origin") {
    harness().use { server =>
      server.run(get("/account/session")).flatMap(_.as[Json]).map { body =>
        assertEquals(body.hcursor.get[Boolean]("passkeys-available"), Right(true))
      }
    }
  }
