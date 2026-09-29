package pds.identity

import cats.effect.{IO, Ref}
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import pds.TestEnv
import pds.TestEnv.*

/** A handle domain shared with another PDS: the server that owns the wildcard
  * arbitrates the names, so this one asks before allocating.
  */
class HandleAuthoritySuite extends munit.CatsEffectSuite:
  private val authority = "https://entryway.example.com"
  private val stranger = "did:plc:4zc47fuogx2rdgxolokayzaw"

  private val account = Json.obj(
    "handle" -> Json.fromString("alice.pds.example.com"),
    "email" -> Json.fromString("alice@example.com"),
    "password" -> Json.fromString("correct horse battery")
  )

  private final case class Entryway(
      client: org.http4s.client.Client[IO],
      claims: Ref[IO, Map[String, String]],
      asked: Ref[IO, Vector[String]]
  )

  /** The authority, recording what it was asked and answering from `claims`. */
  private def entryway: IO[Entryway] =
    for
      claims <- Ref.of[IO, Map[String, String]](Map.empty)
      asked <- Ref.of[IO, Vector[String]](Vector.empty)
    yield Entryway(
      TestEnv.routes {
        case request @ GET -> Root / "xrpc" / "com.atproto.identity.resolveHandle" =>
          val handle = request.params.getOrElse("handle", "")
          asked.update(_ :+ handle) *> claims.get.map(_.get(handle) match
            case Some(did) =>
              Response[IO](Status.Ok).withEntity(Json.obj("did" -> Json.fromString(did)))
            case None =>
              Response[IO](Status.BadRequest)
                .withEntity(Json.obj("error" -> Json.fromString("HandleNotFound"))))
      },
      claims,
      asked)

  private def configured = Map("PDS_HANDLE_AUTHORITY" -> authority)

  private def rename(server: Harness, access: String, handle: String) =
    server.json(authorized(post("/xrpc/com.atproto.identity.updateHandle",
      Json.obj("handle" -> Json.fromString(handle))), access))

  test("a handle the authority already resolves cannot be registered here") {
    entryway.flatMap { it =>
      it.claims.set(Map("alice.pds.example.com" -> stranger)) *>
        harness(configured, it.client).use { server =>
          for
            refused <- server.json(post("/xrpc/com.atproto.server.createAccount", account))
            recorded <- it.asked.get
          yield
            assertEquals(refused._1, Status.BadRequest)
            assertEquals(refused._2.hcursor.get[String]("error"), Right("HandleNotAvailable"))
            assertEquals(recorded, Vector("alice.pds.example.com"))
        }
    }
  }

  test("a handle no one claims is registered as usual") {
    entryway.flatMap { it =>
      harness(configured, it.client).use { server =>
        for
          created <- server.json(post("/xrpc/com.atproto.server.createAccount", account))
          recorded <- it.asked.get
        yield
          assertEquals(created._1, Status.Ok)
          assertEquals(created._2.hcursor.get[String]("handle"), Right("alice.pds.example.com"))
          assertEquals(recorded, Vector("alice.pds.example.com"))
      }
    }
  }

  test("a name the authority resolves back to this account is not a collision") {
    entryway.flatMap { it =>
      harness(configured, it.client).use { server =>
        for
          created <- server.json(post("/xrpc/com.atproto.server.createAccount", account))
          access = created._2.hcursor.get[String]("accessJwt").toOption.get
          did = created._2.hcursor.get[String]("did").toOption.get
          // Delegation means the authority now answers with this account's own
          // DID for its own handle, which must not read as taken.
          _ <- it.claims.set(Map("alice2.pds.example.com" -> did))
          mine <- rename(server, access, "alice2.pds.example.com")
          // Another account's name still is.
          _ <- it.claims.set(Map("alice3.pds.example.com" -> stranger))
          theirs <- rename(server, access, "alice3.pds.example.com")
        yield
          assertEquals(mine._1, Status.Ok)
          assertEquals(theirs._1, Status.BadRequest)
          assertEquals(theirs._2.hcursor.get[String]("error"), Right("HandleNotAvailable"))
      }
    }
  }

  test("an authority that cannot be reached does not block registration") {
    // The default scripted client answers 404 for every path, so the ask fails.
    harness(configured).use { server =>
      server.json(post("/xrpc/com.atproto.server.createAccount", account)).map { created =>
        assertEquals(created._1, Status.Ok)
      }
    }
  }

  test("with no authority configured nothing is asked") {
    entryway.flatMap { it =>
      it.claims.set(Map("alice.pds.example.com" -> stranger)) *>
        harness(Map.empty, it.client).use { server =>
          for
            created <- server.json(post("/xrpc/com.atproto.server.createAccount", account))
            recorded <- it.asked.get
          yield
            assertEquals(created._1, Status.Ok)
            assertEquals(recorded, Vector.empty)
        }
    }
  }
