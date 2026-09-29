package pds.api

import cats.effect.{IO, Ref}
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.typelevel.ci.CIString
import pds.TestEnv.*
import pds.crypto.{Jwt, PublicKey}

class ProxySuite extends munit.CatsEffectSuite:
  private val appviewDid = "did:web:appview.example.com"
  private val appviewUrl = "https://appview.example.com"

  private val credentials = Json.obj(
    "handle" -> Json.fromString("alice.pds.example.com"),
    "email" -> Json.fromString("alice@example.com"),
    "password" -> Json.fromString("correct horse battery")
  )

  private final case class Seen(
      uri: String, method: String, authorization: Option[String], headers: Vector[String])

  /** An upstream that records what reached it and answers with a marker. */
  private def upstream(seen: Ref[IO, Vector[Seen]])
      : PartialFunction[Request[IO], IO[Response[IO]]] =
    case request if request.uri.renderString.startsWith(appviewUrl) =>
      seen.update(_ :+ Seen(
        request.uri.renderString,
        request.method.name,
        request.headers.get(CIString("Authorization")).map(_.head.value),
        request.headers.headers.map(_.name.toString.toLowerCase).toVector
      )) *> IO.pure(Response[IO](Status.Ok)
        .withEntity(Json.obj("feed" -> Json.arr()))
        .putHeaders(Header.Raw(CIString("X-Upstream"), "yes")))

  private def register(server: Harness) =
    server.json(post("/xrpc/com.atproto.server.createAccount", credentials)).map { (_, body) =>
      (body.hcursor.get[String]("accessJwt").toOption.get,
        body.hcursor.get[String]("did").toOption.get)
    }

  private def configured = Map(
    "PDS_APPVIEW_URL" -> appviewUrl,
    "PDS_APPVIEW_DID" -> appviewDid
  )

  test("an unimplemented app.bsky method reaches the configured AppView") {
    Ref.of[IO, Vector[Seen]](Vector.empty).flatMap { seen =>
      harness(configured, routes(upstream(seen))).use { server =>
        for
          auth <- register(server)
          (access, _) = auth
          response <- server.run(authorized(
            get("/xrpc/app.bsky.feed.getTimeline?limit=30"), access))
          body <- response.as[Json]
          recorded <- seen.get
        yield
          assertEquals(response.status, Status.Ok)
          assertEquals(body.hcursor.downField("feed").values.map(_.size), Some(0))
          assertEquals(response.headers.get(CIString("X-Upstream")).map(_.head.value), Some("yes"))
          assertEquals(recorded.length, 1)
          assertEquals(recorded.head.uri,
            s"$appviewUrl/xrpc/app.bsky.feed.getTimeline?limit=30")
      }
    }
  }

  test("the request is re-signed as an inter-service token for that method") {
    Ref.of[IO, Vector[Seen]](Vector.empty).flatMap { seen =>
      harness(configured, routes(upstream(seen))).use { server =>
        for
          auth <- register(server)
          (access, did) = auth
          _ <- server.run(authorized(get("/xrpc/app.bsky.feed.getTimeline"), access))
          recorded <- seen.get
          described <- server.json(get(s"/xrpc/com.atproto.repo.describeRepo?repo=$did"))
        yield
          val token = recorded.head.authorization.get.stripPrefix("Bearer ")
          assertNotEquals(token, access)
          val key = described._2.hcursor.downField("didDoc").downField("verificationMethod")
            .downArray.get[String]("publicKeyMultibase").toOption
            .flatMap(PublicKey.fromMultibase).get
          val jwt = Jwt.verifyEs(key, token).fold(fail(_), identity)
          assertEquals(jwt.claim("iss"), Some(did))
          assertEquals(jwt.claim("aud"), Some(appviewDid))
          assertEquals(jwt.claim("lxm"), Some("app.bsky.feed.getTimeline"))
      }
    }
  }

  test("client credentials are never forwarded upstream") {
    Ref.of[IO, Vector[Seen]](Vector.empty).flatMap { seen =>
      harness(configured, routes(upstream(seen))).use { server =>
        for
          auth <- register(server)
          (access, _) = auth
          _ <- server.run(authorized(get("/xrpc/app.bsky.feed.getTimeline"), access)
            .putHeaders(
              Header.Raw(CIString("Cookie"), "__Host-pds-security=secret"),
              Header.Raw(CIString("DPoP"), "a-proof"),
              Header.Raw(CIString("atproto-accept-labelers"), "did:web:labeler.example.com")))
          recorded <- seen.get
        yield
          assert(!recorded.head.headers.contains("cookie"), recorded.head.headers.toString)
          assert(!recorded.head.headers.contains("dpop"), recorded.head.headers.toString)
          assert(recorded.head.headers.contains("atproto-accept-labelers"))
      }
    }
  }

  test("an anonymous request is proxied without a service token") {
    Ref.of[IO, Vector[Seen]](Vector.empty).flatMap { seen =>
      harness(configured, routes(upstream(seen))).use { server =>
        for
          response <- server.run(get("/xrpc/app.bsky.feed.getTimeline"))
          recorded <- seen.get
        yield
          assertEquals(response.status, Status.Ok)
          assertEquals(recorded.head.authorization, None)
      }
    }
  }

  test("atproto-proxy names the service, which is resolved from its DID document") {
    val labeler = "did:web:labeler.example.com"
    Ref.of[IO, Vector[Seen]](Vector.empty).flatMap { seen =>
      val document: PartialFunction[Request[IO], IO[Response[IO]]] = {
        case request if request.uri.renderString ==
          "https://labeler.example.com/.well-known/did.json" =>
          IO.pure(Response[IO](Status.Ok).withEntity(Json.obj(
            "id" -> Json.fromString(labeler),
            "service" -> Json.arr(Json.obj(
              "id" -> Json.fromString("#atproto_labeler"),
              "type" -> Json.fromString("AtprotoLabeler"),
              "serviceEndpoint" -> Json.fromString(appviewUrl))))))
      }
      harness(configured, routes(document.orElse(upstream(seen)))).use { server =>
        for
          auth <- register(server)
          (access, _) = auth
          routed <- server.run(authorized(get("/xrpc/tools.ozone.moderation.getRepo"), access)
            .putHeaders(Header.Raw(CIString("atproto-proxy"), s"$labeler#atproto_labeler")))
          recorded <- seen.get
          badFragment <- server.json(authorized(get("/xrpc/app.bsky.feed.getTimeline"), access)
            .putHeaders(Header.Raw(CIString("atproto-proxy"), s"$labeler#nope")))
          malformed <- server.json(authorized(get("/xrpc/app.bsky.feed.getTimeline"), access)
            .putHeaders(Header.Raw(CIString("atproto-proxy"), "not-a-did")))
        yield
          assertEquals(routed.status, Status.Ok)
          val token = recorded.head.authorization.get.stripPrefix("Bearer ")
          assertEquals(Jwt.parse(token).toOption.flatMap(_.claim("aud")), Some(labeler))
          assertEquals(badFragment._1, Status.BadRequest)
          assertEquals(malformed._1, Status.BadRequest)
      }
    }
  }

  test("preferences are answered locally and never proxied") {
    Ref.of[IO, Vector[Seen]](Vector.empty).flatMap { seen =>
      harness(configured, routes(upstream(seen))).use { server =>
        for
          auth <- register(server)
          (access, _) = auth
          response <- server.json(authorized(get("/xrpc/app.bsky.actor.getPreferences"), access))
          recorded <- seen.get
        yield
          assertEquals(response._1, Status.Ok)
          assertEquals(response._2.hcursor.downField("preferences").values.map(_.size), Some(0))
          assertEquals(recorded, Vector.empty)
      }
    }
  }

  test("without an AppView the server says so instead of failing obscurely") {
    harness().use { server =>
      for
        auth <- register(server)
        (access, _) = auth
        response <- server.json(authorized(get("/xrpc/app.bsky.feed.getTimeline"), access))
      yield
        assertEquals(response._1, Status.NotImplemented)
        assertEquals(response._2.hcursor.get[String]("error"), Right("NotImplemented"))
    }
  }

  test("methods outside the proxied namespaces are not forwarded") {
    Ref.of[IO, Vector[Seen]](Vector.empty).flatMap { seen =>
      harness(configured, routes(upstream(seen))).use { server =>
        for
          response <- server.json(get("/xrpc/com.example.custom.method"))
          recorded <- seen.get
        yield
          assertEquals(response._1, Status.NotImplemented)
          assertEquals(response._2.hcursor.get[String]("error"), Right("MethodNotImplemented"))
          assertEquals(recorded, Vector.empty)
      }
    }
  }
