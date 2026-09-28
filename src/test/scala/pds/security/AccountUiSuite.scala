package pds.security

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.typelevel.ci.CIString
import pds.TestEnv.*

class AccountUiSuite extends munit.CatsEffectSuite:
  private val origin = "https://pds.example.com"

  private def cookieOf(response: Response[IO]): Option[String] =
    response.headers.get(CIString("Set-Cookie")).toVector.flatMap(_.toList)
      .map(_.value).find(_.startsWith("__Host-pds-security="))
      .map(_.drop("__Host-pds-security=".length).takeWhile(_ != ';'))

  private def action(
      name: String, body: Json, cookie: String, csrf: String
  ): Request[IO] =
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
      response.as[Json].map(body => (cookieOf(response).get,
        body.hcursor.get[String]("csrf").toOption.get, body)))

  private def signIn(server: Harness) =
    for
      opened <- open(server)
      loggedIn <- server.run(action("login/password", Json.obj(
        "identifier" -> Json.fromString("alice.pds.example.com"),
        "password" -> Json.fromString("correct horse battery")), opened._1, opened._2))
      body <- loggedIn.as[Json]
    yield (cookieOf(loggedIn).get, body.hcursor.get[String]("csrf").toOption.get, body)

  test("the account page and its assets are served with a strict policy") {
    harness().use { server =>
      for
        page <- server.run(get("/account"))
        script <- server.run(get("/account/app.js"))
        styles <- server.run(get("/account/style.css"))
        body <- page.as[String]
      yield
        assertEquals(page.status, Status.Ok)
        assert(body.contains("/account/app.js"), body)
        assertEquals(page.headers.get(CIString("Content-Security-Policy")).map(_.head.value),
          Some("default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; " +
            "img-src 'self' data:; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"))
        assertEquals(page.headers.get(CIString("X-Frame-Options")).map(_.head.value), Some("DENY"))
        assertEquals(script.status, Status.Ok)
        assertEquals(styles.status, Status.Ok)
    }
  }

  test("a session starts anonymous and carries the server policy") {
    harness(Map("PDS_INVITE_REQUIRED" -> "true")).use { server =>
      server.run(get("/account/session")).flatMap { response =>
        response.as[Json].map { body =>
          assertEquals(body.hcursor.get[String]("stage"), Right("login"))
          assertEquals(body.hcursor.get[String]("origin"), Right(origin))
          assertEquals(body.hcursor.get[Boolean]("invite-required"), Right(true))
          assertEquals(body.hcursor.get[String]("user-domain"), Right("pds.example.com"))
          assertEquals(body.hcursor.get[String]("csrf").toOption.map(_.length), Some(43))
          val cookie = response.headers.get(CIString("Set-Cookie")).get.head.value
          assert(cookie.contains("HttpOnly"), cookie)
          assert(cookie.contains("SameSite=Lax"), cookie)
          assert(cookie.contains("Secure"), cookie)
          assert(cookie.contains("Max-Age=300"), cookie)
        }
      }
    }
  }

  test("signing in rotates the session token and reaches the authenticated stage") {
    harness().use { server =>
      for
        _ <- register(server)
        opened <- open(server)
        result <- signIn(server)
      yield
        assertEquals(result._3.hcursor.get[String]("stage"), Right("authenticated"))
        assertEquals(result._3.hcursor.get[String]("handle"), Right("alice.pds.example.com"))
        assertNotEquals(result._1, opened._1)
        assertNotEquals(result._2, opened._2)
    }
  }

  test("actions require a matching origin and CSRF token") {
    harness().use { server =>
      for
        _ <- register(server)
        opened <- open(server)
        crossOrigin <- server.run(post("/account/action/login/password", Json.obj())
          .putHeaders(Header.Raw(CIString("Origin"), "https://evil.example.com"),
            Header.Raw(CIString("Cookie"), s"__Host-pds-security=${opened._1}")))
        noCsrf <- server.run(post("/account/action/login/password", Json.obj())
          .putHeaders(Header.Raw(CIString("Origin"), origin),
            Header.Raw(CIString("Cookie"), s"__Host-pds-security=${opened._1}")))
        wrongCsrf <- server.run(action("login/password", Json.obj(), opened._1,
          pds.crypto.Hash.token()))
        noCookie <- server.run(post("/account/action/login/password", Json.obj())
          .putHeaders(Header.Raw(CIString("Origin"), origin),
            Header.Raw(CIString("X-CSRF-Token"), opened._2)))
      yield
        assertEquals(crossOrigin.status, Status.Forbidden)
        assertEquals(noCsrf.status, Status.Forbidden)
        assertEquals(wrongCsrf.status, Status.Forbidden)
        assertEquals(noCookie.status, Status.Unauthorized)
    }
  }

  test("signing up through the interface creates the account and signs in") {
    harness().use { server =>
      for
        opened <- open(server)
        created <- server.run(action("signup", Json.obj(
          "handle" -> Json.fromString("alice.pds.example.com"),
          "email" -> Json.fromString("alice@example.com"),
          "password" -> Json.fromString("correct horse battery")), opened._1, opened._2))
        body <- created.as[Json]
        resolved <- server.json(get(
          "/xrpc/com.atproto.identity.resolveHandle?handle=alice.pds.example.com"))
      yield
        assertEquals(created.status, Status.Ok)
        assertEquals(body.hcursor.get[String]("stage"), Right("authenticated"))
        assertEquals(resolved._1, Status.Ok)
    }
  }

  test("app passwords are created and revoked from the interface") {
    harness().use { server =>
      for
        _ <- register(server)
        signedIn <- signIn(server)
        created <- server.run(action("app-passwords/create",
          Json.obj("name" -> Json.fromString("phone")), signedIn._1, signedIn._2))
        createdBody <- created.as[Json]
        listed = createdBody.hcursor.downField("appPasswords").values.map(_.size)
        revoked <- server.run(action("app-passwords/revoke",
          Json.obj("name" -> Json.fromString("phone")), signedIn._1, signedIn._2))
        revokedBody <- revoked.as[Json]
      yield
        assertEquals(created.status, Status.Ok)
        assertEquals(listed, Some(1))
        assert(createdBody.hcursor.downField("result").get[String]("password").isRight)
        assertEquals(revokedBody.hcursor.downField("appPasswords").values.map(_.size), Some(0))
    }
  }

  test("an authenticator is enrolled, verified and used as a second factor") {
    harness().use { server =>
      for
        _ <- register(server)
        signedIn <- signIn(server)
        begun <- server.run(action("totp/begin", Json.obj(), signedIn._1, signedIn._2))
        begunBody <- begun.as[Json]
        secret = begunBody.hcursor.downField("result").get[String]("secret").toOption.get
        bytes = pds.crypto.Encoding.unbase32(secret.toLowerCase).get
        now <- server.env.now
        confirmed <- server.run(action("totp/confirm",
          Json.obj("code" -> Json.fromString(Totp.code(bytes, now / 1000 / 30))),
          signedIn._1, signedIn._2))
        confirmedBody <- confirmed.as[Json]
        codes = confirmedBody.hcursor.downField("result")
          .get[Vector[String]]("recoveryCodes").toOption.get
        stale = cookieOf(confirmed).get
        csrf = confirmedBody.hcursor.get[String]("csrf").toOption.get
        again <- open(server)
        challenge <- server.run(action("login/password", Json.obj(
          "identifier" -> Json.fromString("alice.pds.example.com"),
          "password" -> Json.fromString("correct horse battery")), again._1, again._2))
        challengeBody <- challenge.as[Json]
        wrong <- server.run(action("login/factor", Json.obj(
          "code" -> Json.fromString("000000")), cookieOf(challenge).get,
          challengeBody.hcursor.get[String]("csrf").toOption.get))
        recovery <- server.run(action("login/factor", Json.obj(
          "code" -> Json.fromString(codes.head)), cookieOf(challenge).get,
          challengeBody.hcursor.get[String]("csrf").toOption.get))
        recoveryBody <- recovery.as[Json]
      yield
        assertEquals(begun.status, Status.Ok)
        assertEquals(secret.length, 32)
        assertEquals(confirmed.status, Status.Ok)
        assertEquals(codes.length, 8)
        assertEquals(confirmedBody.hcursor.get[String]("factor"), Right("totp"))
        assertEquals(challengeBody.hcursor.get[String]("stage"), Right("factor"))
        assertEquals(wrong.status, Status.BadRequest)
        assertEquals(recoveryBody.hcursor.get[String]("stage"), Right("authenticated"))
        assertNotEquals(stale, csrf)
    }
  }

  test("changing the password ends other sessions but keeps this one") {
    harness().use { server =>
      for
        created <- register(server)
        access = created._2.hcursor.get[String]("accessJwt").toOption.get
        signedIn <- signIn(server)
        changed <- server.run(action("password/change", Json.obj(
          "currentPassword" -> Json.fromString("correct horse battery"),
          "newPassword" -> Json.fromString("a brand new password")), signedIn._1, signedIn._2))
        changedBody <- changed.as[Json]
        apiSession <- server.json(authorized(get("/xrpc/com.atproto.server.getSession"), access))
        stillWorks <- server.run(action("app-passwords/create",
          Json.obj("name" -> Json.fromString("phone")),
          cookieOf(changed).get, changedBody.hcursor.get[String]("csrf").toOption.get))
        stillWorksBody <- stillWorks.as[Json]
        wrongCurrent <- server.run(action("password/change", Json.obj(
          "currentPassword" -> Json.fromString("not the password"),
          "newPassword" -> Json.fromString("another password")),
          cookieOf(stillWorks).get,
          stillWorksBody.hcursor.get[String]("csrf").toOption.get))
      yield
        assertEquals(changed.status, Status.Ok)
        assertEquals(changedBody.hcursor.get[String]("stage"), Right("authenticated"))
        assertEquals(apiSession._1, Status.Unauthorized)
        assertEquals(stillWorks.status, Status.Ok)
        assertEquals(wrongCurrent.status, Status.Unauthorized)
    }
  }

  test("signing out clears the cookie and returns to the login stage") {
    harness().use { server =>
      for
        _ <- register(server)
        signedIn <- signIn(server)
        out <- server.run(action("logout", Json.obj(), signedIn._1, signedIn._2))
        body <- out.as[Json]
        reused <- server.run(action("app-passwords/create",
          Json.obj("name" -> Json.fromString("phone")), signedIn._1, signedIn._2))
      yield
        assertEquals(body.hcursor.get[String]("stage"), Right("login"))
        assert(out.headers.get(CIString("Set-Cookie")).get.head.value.contains("Max-Age=0"))
        assertEquals(reused.status, Status.Unauthorized)
    }
  }

  test("unknown actions are refused") {
    harness().use { server =>
      for
        _ <- register(server)
        signedIn <- signIn(server)
        unknown <- server.run(action("does/not/exist", Json.obj(), signedIn._1, signedIn._2))
      yield assertEquals(unknown.status, Status.NotFound)
    }
  }
