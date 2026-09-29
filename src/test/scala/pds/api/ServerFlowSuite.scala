package pds.api

import cats.effect.IO
import io.circe.Json
import org.http4s.{Method, Request, Status, Uri}
import pds.TestEnv
import pds.TestEnv.*

class ServerFlowSuite extends munit.CatsEffectSuite:
  private def account(handle: String = "alice.pds.example.com") = Json.obj(
    "handle" -> Json.fromString(handle),
    "email" -> Json.fromString(s"${handle.takeWhile(_ != '.')}@example.com"),
    "password" -> Json.fromString("correct horse battery")
  )

  test("describeServer advertises the handle domain and invite policy") {
    harness(Map("PDS_INVITE_REQUIRED" -> "true")).use { server =>
      server.json(get("/xrpc/com.atproto.server.describeServer")).map { (status, body) =>
        assertEquals(status, Status.Ok)
        assertEquals(body.hcursor.get[String]("did"), Right("did:web:pds.example.com"))
        assertEquals(body.hcursor.get[Vector[String]]("availableUserDomains"),
          Right(Vector(".pds.example.com")))
        assertEquals(body.hcursor.get[Boolean]("inviteCodeRequired"), Right(true))
      }
    }
  }

  test("an account can be created, authenticated and refreshed") {
    harness().use { server =>
      for
        created <- server.json(post("/xrpc/com.atproto.server.createAccount", account()))
        did = created._2.hcursor.get[String]("did").toOption.get
        session <- server.json(post("/xrpc/com.atproto.server.createSession", Json.obj(
          "identifier" -> Json.fromString("alice.pds.example.com"),
          "password" -> Json.fromString("correct horse battery"))))
        access = session._2.hcursor.get[String]("accessJwt").toOption.get
        refreshToken = session._2.hcursor.get[String]("refreshJwt").toOption.get
        current <- server.json(authorized(get("/xrpc/com.atproto.server.getSession"), access))
        refreshed <- server.json(authorized(
          post("/xrpc/com.atproto.server.refreshSession", Json.obj()), refreshToken))
        replayed <- server.json(authorized(
          post("/xrpc/com.atproto.server.refreshSession", Json.obj()), refreshToken))
      yield
        assertEquals(created._1, Status.Ok)
        assert(did.startsWith("did:web:pds.example.com:u:alice"), did)
        assertEquals(session._1, Status.Ok)
        assertEquals(current._1, Status.Ok)
        assertEquals(current._2.hcursor.get[String]("did"), Right(did))
        assertEquals(current._2.hcursor.get[String]("handle"), Right("alice.pds.example.com"))
        assertEquals(refreshed._1, Status.Ok)
        assertNotEquals(refreshed._2.hcursor.get[String]("refreshJwt").toOption, Some(refreshToken))
        assertEquals(replayed._1, Status.Unauthorized)
    }
  }

  test("handles are validated against the server policy") {
    harness().use { server =>
      val cases = List(
        "alice.example.org" -> "InvalidHandle",
        "ab.pds.example.com" -> "InvalidHandle",
        "admin.pds.example.com" -> "InvalidHandle",
        "a.b.pds.example.com" -> "InvalidHandle",
        "not a handle" -> "InvalidHandle"
      )
      cases.foldLeft(IO.unit) { (acc, item) =>
        acc *> server.json(post("/xrpc/com.atproto.server.createAccount", account(item._1)))
          .map { (status, body) =>
            assertEquals(status, Status.BadRequest, item._1)
            assertEquals(body.hcursor.get[String]("error"), Right(item._2), item._1)
          }
      }
    }
  }

  test("duplicate handles and emails are refused") {
    harness().use { server =>
      for
        _ <- server.json(post("/xrpc/com.atproto.server.createAccount", account()))
        sameHandle <- server.json(post("/xrpc/com.atproto.server.createAccount", account()))
        sameEmail <- server.json(post("/xrpc/com.atproto.server.createAccount",
          account("bob.pds.example.com").deepMerge(
            Json.obj("email" -> Json.fromString("alice@example.com")))))
      yield
        assertEquals(sameHandle._1, Status.BadRequest)
        assertEquals(sameHandle._2.hcursor.get[String]("error"), Right("HandleNotAvailable"))
        assertEquals(sameEmail._2.hcursor.get[String]("error"), Right("HandleNotAvailable"))
    }
  }

  test("weak passwords are refused before an account exists") {
    harness().use { server =>
      for
        weak <- server.json(post("/xrpc/com.atproto.server.createAccount",
          account().deepMerge(Json.obj("password" -> Json.fromString("short")))))
        session <- server.json(post("/xrpc/com.atproto.server.createSession", Json.obj(
          "identifier" -> Json.fromString("alice.pds.example.com"),
          "password" -> Json.fromString("short"))))
      yield
        assertEquals(weak._2.hcursor.get[String]("error"), Right("InvalidPassword"))
        assertEquals(session._1, Status.Unauthorized)
    }
  }

  test("invite codes gate signup when required") {
    harness(Map("PDS_INVITE_REQUIRED" -> "true")).use { server =>
      for
        without <- server.json(post("/xrpc/com.atproto.server.createAccount", account()))
        code <- server.json(admin(post("/xrpc/com.atproto.server.createInviteCode",
          Json.obj("useCount" -> Json.fromInt(1)))))
        value = code._2.hcursor.get[String]("code").toOption.get
        with0 <- server.json(post("/xrpc/com.atproto.server.createAccount",
          account().deepMerge(Json.obj("inviteCode" -> Json.fromString(value)))))
        reused <- server.json(post("/xrpc/com.atproto.server.createAccount",
          account("bob.pds.example.com").deepMerge(Json.obj("inviteCode" -> Json.fromString(value)))))
        unauthenticated <- server.json(post("/xrpc/com.atproto.server.createInviteCode",
          Json.obj("useCount" -> Json.fromInt(1))))
      yield
        assertEquals(without._2.hcursor.get[String]("error"), Right("InvalidInviteCode"))
        assertEquals(code._1, Status.Ok)
        assert(value.startsWith("pds.example.com-"), value)
        assertEquals(with0._1, Status.Ok)
        assertEquals(reused._2.hcursor.get[String]("error"), Right("InvalidInviteCode"))
        assertEquals(unauthenticated._1, Status.Unauthorized)
    }
  }

  test("signup can be closed entirely") {
    harness(Map("PDS_SIGNUP_ENABLED" -> "false")).use { server =>
      server.json(post("/xrpc/com.atproto.server.createAccount", account())).map { (status, body) =>
        assertEquals(status, Status.Forbidden)
        assertEquals(body.hcursor.get[String]("error"), Right("Forbidden"))
      }
    }
  }

  test("app passwords authenticate with reduced privileges") {
    harness().use { server =>
      for
        created <- server.json(post("/xrpc/com.atproto.server.createAccount", account()))
        access = created._2.hcursor.get[String]("accessJwt").toOption.get
        made <- server.json(authorized(post("/xrpc/com.atproto.server.createAppPassword",
          Json.obj("name" -> Json.fromString("phone"))), access))
        password = made._2.hcursor.get[String]("password").toOption.get
        session <- server.json(post("/xrpc/com.atproto.server.createSession", Json.obj(
          "identifier" -> Json.fromString("alice.pds.example.com"),
          "password" -> Json.fromString(password))))
        appAccess = session._2.hcursor.get[String]("accessJwt").toOption.get
        blocked <- server.json(authorized(post("/xrpc/com.atproto.server.createAppPassword",
          Json.obj("name" -> Json.fromString("laptop"))), appAccess))
        listed <- server.json(authorized(get("/xrpc/com.atproto.server.listAppPasswords"), access))
        revoked <- server.json(authorized(post("/xrpc/com.atproto.server.revokeAppPassword",
          Json.obj("name" -> Json.fromString("phone"))), access))
        afterRevoke <- server.json(post("/xrpc/com.atproto.server.createSession", Json.obj(
          "identifier" -> Json.fromString("alice.pds.example.com"),
          "password" -> Json.fromString(password))))
      yield
        assertEquals(made._1, Status.Ok)
        assertEquals(password.length, 19)
        assertEquals(session._1, Status.Ok)
        assertEquals(blocked._1, Status.Forbidden)
        assertEquals(listed._2.hcursor.downField("passwords").values.map(_.size), Some(1))
        assertEquals(revoked._1, Status.Ok)
        assertEquals(afterRevoke._1, Status.Unauthorized)
    }
  }

  test("changing a password through the admin API revokes existing sessions") {
    harness().use { server =>
      for
        created <- server.json(post("/xrpc/com.atproto.server.createAccount", account()))
        access = created._2.hcursor.get[String]("accessJwt").toOption.get
        did = created._2.hcursor.get[String]("did").toOption.get
        before <- server.json(authorized(get("/xrpc/com.atproto.server.getSession"), access))
        _ <- server.json(admin(post("/xrpc/com.atproto.admin.updateAccountPassword", Json.obj(
          "did" -> Json.fromString(did),
          "password" -> Json.fromString("a different password")))))
        after <- server.json(authorized(get("/xrpc/com.atproto.server.getSession"), access))
      yield
        assertEquals(before._1, Status.Ok)
        assertEquals(after._1, Status.Unauthorized)
    }
  }

  test("deactivation and reactivation are reflected in account status") {
    harness().use { server =>
      for
        created <- server.json(post("/xrpc/com.atproto.server.createAccount", account()))
        access = created._2.hcursor.get[String]("accessJwt").toOption.get
        did = created._2.hcursor.get[String]("did").toOption.get
        _ <- server.json(authorized(post("/xrpc/com.atproto.server.deactivateAccount", Json.obj()), access))
        blocked <- server.json(authorized(get("/xrpc/com.atproto.server.getSession"), access))
        status <- server.json(get(s"/xrpc/com.atproto.sync.getRepoStatus?did=$did"))
      yield
        assertEquals(blocked._1, Status.BadRequest)
        assertEquals(blocked._2.hcursor.get[String]("error"), Right("AccountDeactivated"))
        assertEquals(status._2.hcursor.get[Boolean]("active"), Right(false))
        assertEquals(status._2.hcursor.get[String]("status"), Right("deactivated"))
    }
  }

  test("a takedown blocks the account and reports its status") {
    harness().use { server =>
      for
        created <- server.json(post("/xrpc/com.atproto.server.createAccount", account()))
        access = created._2.hcursor.get[String]("accessJwt").toOption.get
        did = created._2.hcursor.get[String]("did").toOption.get
        _ <- server.json(admin(post("/xrpc/com.atproto.admin.updateSubjectStatus", Json.obj(
          "subject" -> Json.obj(
            "$type" -> Json.fromString("com.atproto.admin.defs#repoRef"),
            "did" -> Json.fromString(did)),
          "takedown" -> Json.obj("applied" -> Json.True)))))
        blocked <- server.json(authorized(get("/xrpc/com.atproto.server.getSession"), access))
        subject <- server.json(admin(get(s"/xrpc/com.atproto.admin.getSubjectStatus?did=$did")))
        _ <- server.json(admin(post("/xrpc/com.atproto.admin.updateSubjectStatus", Json.obj(
          "subject" -> Json.obj(
            "$type" -> Json.fromString("com.atproto.admin.defs#repoRef"),
            "did" -> Json.fromString(did)),
          "takedown" -> Json.obj("applied" -> Json.False)))))
        restored <- server.json(post("/xrpc/com.atproto.server.createSession", Json.obj(
          "identifier" -> Json.fromString("alice.pds.example.com"),
          "password" -> Json.fromString("correct horse battery"))))
      yield
        assertEquals(blocked._1, Status.Forbidden)
        assertEquals(blocked._2.hcursor.get[String]("error"), Right("AccountTakedown"))
        assertEquals(subject._2.hcursor.downField("takedown").get[Boolean]("applied"), Right(true))
        assertEquals(restored._1, Status.Ok)
    }
  }

  test("service tokens are signed for a bounded audience and lifetime") {
    harness().use { server =>
      for
        created <- server.json(post("/xrpc/com.atproto.server.createAccount", account()))
        access = created._2.hcursor.get[String]("accessJwt").toOption.get
        did = created._2.hcursor.get[String]("did").toOption.get
        token <- server.json(authorized(get(
          "/xrpc/com.atproto.server.getServiceAuth?aud=did:web:appview.example.com" +
            "&lxm=app.bsky.feed.getTimeline"), access))
        tooLong <- server.json(authorized(get(
          "/xrpc/com.atproto.server.getServiceAuth?aud=did:web:appview.example.com&exp=99999999999"),
          access))
        badAudience <- server.json(authorized(get(
          "/xrpc/com.atproto.server.getServiceAuth?aud=not-a-did"), access))
        jwt = pds.crypto.Jwt.parse(token._2.hcursor.get[String]("token").toOption.get)
          .fold(fail(_), identity)
      yield
        assertEquals(token._1, Status.Ok)
        assertEquals(jwt.claim("iss"), Some(did))
        assertEquals(jwt.claim("aud"), Some("did:web:appview.example.com"))
        assertEquals(jwt.claim("lxm"), Some("app.bsky.feed.getTimeline"))
        assertEquals(jwt.algorithm, Some("ES256K"))
        assertEquals(tooLong._2.hcursor.get[String]("error"), Right("BadExpiration"))
        assertEquals(badAudience._1, Status.BadRequest)
    }
  }

  test("unknown methods, wrong verbs and missing routes answer in the XRPC envelope") {
    harness().use { server =>
      for
        unknown <- server.json(get("/xrpc/com.atproto.server.notAMethod"))
        wrongVerb <- server.json(post("/xrpc/com.atproto.server.describeServer", Json.obj()))
        missing <- server.json(get("/nowhere"))
        health <- server.json(get("/xrpc/_health"))
      yield
        assertEquals(unknown._1, Status.NotImplemented)
        assertEquals(unknown._2.hcursor.get[String]("error"), Right("MethodNotImplemented"))
        assertEquals(wrongVerb._1, Status.BadRequest)
        assertEquals(missing._1, Status.NotFound)
        assertEquals(missing._2.hcursor.get[String]("error"), Right("NotFound"))
        assertEquals(health._2.hcursor.get[String]("version"),
          Right("scala-pds 0.1.0-SNAPSHOT"))
    }
  }

  test("the service DID document and hosted account documents are served") {
    harness().use { server =>
      for
        created <- server.json(post("/xrpc/com.atproto.server.createAccount", account()))
        service <- server.json(get("/.well-known/did.json"))
        hosted <- server.json(get("/u/alice/did.json"))
        absent <- server.json(get("/u/nobody/did.json"))
      yield
        assertEquals(service._2.hcursor.get[String]("id"), Right("did:web:pds.example.com"))
        assertEquals(hosted._1, Status.Ok)
        assertEquals(hosted._2.hcursor.get[String]("id"),
          Right(created._2.hcursor.get[String]("did").toOption.get))
        assertEquals(hosted._2.hcursor.get[Vector[String]]("alsoKnownAs"),
          Right(Vector("at://alice.pds.example.com")))
        assertEquals(absent._1, Status.NotFound)
    }
  }

  test("handles resolve locally and reject unknown names") {
    harness().use { server =>
      for
        _ <- server.json(post("/xrpc/com.atproto.server.createAccount", account()))
        resolved <- server.json(get(
          "/xrpc/com.atproto.identity.resolveHandle?handle=alice.pds.example.com"))
        upper <- server.json(get(
          "/xrpc/com.atproto.identity.resolveHandle?handle=ALICE.pds.example.com"))
        identity <- server.json(get(
          "/xrpc/com.atproto.identity.resolveIdentity?identifier=alice.pds.example.com"))
        invalid <- server.json(get("/xrpc/com.atproto.identity.resolveHandle?handle=not%20a%20handle"))
      yield
        assertEquals(resolved._1, Status.Ok)
        assert(resolved._2.hcursor.get[String]("did").toOption.get.startsWith("did:web:"))
        assertEquals(upper._2.hcursor.get[String]("did"), resolved._2.hcursor.get[String]("did"))
        assertEquals(identity._2.hcursor.downField("didDoc").get[String]("id"),
          resolved._2.hcursor.get[String]("did"))
        assertEquals(invalid._1, Status.BadRequest)
    }
  }

  test("a handle change is validated, applied and reflected in resolution") {
    harness().use { server =>
      for
        created <- server.json(post("/xrpc/com.atproto.server.createAccount", account()))
        access = created._2.hcursor.get[String]("accessJwt").toOption.get
        changed <- server.json(authorized(post("/xrpc/com.atproto.identity.updateHandle",
          Json.obj("handle" -> Json.fromString("alice2.pds.example.com"))), access))
        resolved <- server.json(get(
          "/xrpc/com.atproto.identity.resolveHandle?handle=alice2.pds.example.com"))
        external <- server.json(authorized(post("/xrpc/com.atproto.identity.updateHandle",
          Json.obj("handle" -> Json.fromString("alice.example.org"))), access))
      yield
        assertEquals(changed._1, Status.Ok)
        assertEquals(resolved._2.hcursor.get[String]("did"),
          created._2.hcursor.get[String]("did"))
        assertEquals(external._1, Status.BadRequest)
        assertEquals(external._2.hcursor.get[String]("error"), Right("InvalidHandle"))
    }
  }

  test("preferences are private to the account and validated") {
    harness().use { server =>
      for
        created <- server.json(post("/xrpc/com.atproto.server.createAccount", account()))
        access = created._2.hcursor.get[String]("accessJwt").toOption.get
        other <- server.json(post("/xrpc/com.atproto.server.createAccount",
          account("bob.pds.example.com")))
        otherAccess = other._2.hcursor.get[String]("accessJwt").toOption.get
        _ <- server.json(authorized(post("/xrpc/app.bsky.actor.putPreferences", Json.obj(
          "preferences" -> Json.arr(Json.obj(
            "$type" -> Json.fromString("app.bsky.actor.defs#adultContentPref"),
            "enabled" -> Json.True)))), access))
        mine <- server.json(authorized(get("/xrpc/app.bsky.actor.getPreferences"), access))
        theirs <- server.json(authorized(get("/xrpc/app.bsky.actor.getPreferences"), otherAccess))
        invalid <- server.json(authorized(post("/xrpc/app.bsky.actor.putPreferences", Json.obj(
          "preferences" -> Json.arr(Json.obj("enabled" -> Json.True)))), access))
        anonymous <- server.json(get("/xrpc/app.bsky.actor.getPreferences"))
      yield
        assertEquals(mine._2.hcursor.downField("preferences").values.map(_.size), Some(1))
        assertEquals(theirs._2.hcursor.downField("preferences").values.map(_.size), Some(0))
        assertEquals(invalid._1, Status.BadRequest)
        assertEquals(anonymous._1, Status.Unauthorized)
    }
  }
