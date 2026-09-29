package pds.api

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import pds.TestEnv
import pds.TestEnv.*
import pds.crypto.{Curve, Jwt, PrivateKey}
import pds.identity.DidDocument

/** A moderation service acting on this server with its own identity, the way
  * Ozone does, instead of with the administrator password.
  */
class ModerationAuthSuite extends munit.CatsEffectSuite:
  private val authority = "did:web:ozone.example.com"
  private val key = PrivateKey.generate(Curve.K256)
  private val stranger = PrivateKey.generate(Curve.K256)

  private val directory = TestEnv.routes {
    case GET -> Root / ".well-known" / "did.json" =>
      IO.pure(Response[IO](Status.Ok).withEntity(
        DidDocument.build(authority, "ozone.example.com", key.publicKey,
          "https://ozone.example.com")))
  }

  private def hosted = TestEnv.harness(Map("PDS_MOD_SERVICE_DID" -> authority), directory)

  private def token(
      signer: PrivateKey = key,
      issuer: String = authority,
      audience: String = "did:web:pds.example.com",
      method: String = "com.atproto.admin.updateSubjectStatus",
      lifetime: Long = 300
  ): IO[String] =
    IO.realTime.map(_.toSeconds).map { now =>
      Jwt.signService(signer, Json.obj(
        "iss" -> Json.fromString(issuer),
        "aud" -> Json.fromString(audience),
        "lxm" -> Json.fromString(method),
        "iat" -> Json.fromLong(now),
        "exp" -> Json.fromLong(now + lifetime)))
    }

  private def register(server: Harness) =
    server.json(post("/xrpc/com.atproto.server.createAccount", Json.obj(
      "handle" -> Json.fromString("alice.pds.example.com"),
      "email" -> Json.fromString("alice@example.com"),
      "password" -> Json.fromString("correct horse battery"))))
      .map(_._2.hcursor.get[String]("did").toOption.get)

  private def takedown(server: Harness, did: String, bearer: String) =
    server.json(authorized(post("/xrpc/com.atproto.admin.updateSubjectStatus", Json.obj(
      "subject" -> Json.obj(
        "$type" -> Json.fromString("com.atproto.admin.defs#repoRef"),
        "did" -> Json.fromString(did)),
      "takedown" -> Json.obj(
        "applied" -> Json.True,
        "ref" -> Json.fromString("ozone-1")))), bearer))

  test("the configured moderation service acts with a scoped service token") {
    hosted.use { server =>
      for
        did <- register(server)
        bearer <- token()
        applied <- takedown(server, did, bearer)
        status <- server.json(admin(get(
          s"/xrpc/com.atproto.admin.getSubjectStatus?did=$did")))
      yield
        assertEquals(applied._1, Status.Ok)
        assertEquals(status._2.hcursor.downField("takedown").get[Boolean]("applied"), Right(true))
    }
  }

  test("a token for another method, audience, issuer or signer is refused") {
    hosted.use { server =>
      for
        did <- register(server)
        wrongMethod <- token(method = "com.atproto.admin.sendEmail")
        wrongAudience <- token(audience = "did:web:elsewhere.example.com")
        wrongIssuer <- token(issuer = "did:web:pds.example.com")
        wrongSigner <- token(signer = stranger)
        expired <- token(lifetime = -60)
        refused <- Vector(wrongMethod, wrongAudience, wrongIssuer, wrongSigner, expired)
          .foldLeft(IO.pure(Vector.empty[Status]))((acc, value) =>
            acc.flatMap(all => takedown(server, did, value).map(all :+ _._1)))
        status <- server.json(admin(get(
          s"/xrpc/com.atproto.admin.getSubjectStatus?did=$did")))
      yield
        assertEquals(refused, Vector.fill(5)(Status.Unauthorized))
        assertEquals(status._2.hcursor.downField("takedown").get[Boolean]("applied"), Right(false))
    }
  }

  test("a service token is refused when no moderation service is configured") {
    TestEnv.harness(Map.empty, directory).use { server =>
      for
        did <- register(server)
        bearer <- token()
        refused <- takedown(server, did, bearer)
      yield assertEquals(refused._1, Status.Unauthorized)
    }
  }
