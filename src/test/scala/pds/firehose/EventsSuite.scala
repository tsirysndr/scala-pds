package pds.firehose

import cats.effect.IO
import io.circe.Json
import org.http4s.Status
import pds.TestEnv.*
import pds.protocol.*

class EventsSuite extends munit.CatsEffectSuite:
  private val credentials = Json.obj(
    "handle" -> Json.fromString("alice.pds.example.com"),
    "email" -> Json.fromString("alice@example.com"),
    "password" -> Json.fromString("correct horse battery")
  )

  private def post0(text: String) = Json.obj(
    "$type" -> Json.fromString("app.bsky.feed.post"),
    "text" -> Json.fromString(text),
    "createdAt" -> Json.fromString("2026-01-01T00:00:00.000Z")
  )

  private def events(server: Harness): IO[Vector[Event]] =
    server.env.database.read(connection => Events.since(connection, 0L, 1000))

  private def register(server: Harness) =
    server.json(post("/xrpc/com.atproto.server.createAccount", credentials)).map { (_, body) =>
      (body.hcursor.get[String]("accessJwt").toOption.get,
        body.hcursor.get[String]("did").toOption.get)
    }

  test("registration writes identity, account and sync events in order") {
    harness().use { server =>
      for
        auth <- register(server)
        (_, did) = auth
        sequence <- events(server)
      yield
        assertEquals(sequence.map(_.kind), Vector("#identity", "#account", "#sync"))
        assertEquals(sequence.map(_.seq), Vector(1L, 2L, 3L))
        assertEquals(sequence.map(_.did).distinct, Vector(did))
        assertEquals(sequence.head.body("handle").flatMap(_.asString),
          Some("alice.pds.example.com"))
        assertEquals(sequence(1).body("active"), Some(Node.Bool(true)))
        assert(sequence(2).body("blocks").flatMap(_.asBytes).exists(_.nonEmpty))
    }
  }

  test("a commit event carries the new blocks, ops and the previous tree root") {
    harness().use { server =>
      for
        auth <- register(server)
        (access, did) = auth
        created <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString("3jqfcqzm3fo2j"),
          "record" -> post0("hello"))), access))
        sequence <- events(server)
      yield
        val commit = sequence.last
        assertEquals(commit.kind, "#commit")
        assertEquals(commit.body("repo").flatMap(_.asString), Some(did))
        assertEquals(commit.body("rev").flatMap(_.asString),
          created._2.hcursor.downField("commit").get[String]("rev").toOption)
        assertEquals(commit.body("commit").flatMap(_.asLink).map(_.toString),
          created._2.hcursor.downField("commit").get[String]("cid").toOption)
        assert(commit.body("since").flatMap(_.asString).isDefined)
        assert(commit.body("prevData").flatMap(_.asLink).isDefined)

        val ops = commit.body("ops").flatMap(_.asVector).get
        assertEquals(ops.length, 1)
        assertEquals(ops.head("action").flatMap(_.asString), Some("create"))
        assertEquals(ops.head("path").flatMap(_.asString),
          Some("app.bsky.feed.post/3jqfcqzm3fo2j"))
        assertEquals(ops.head("cid").flatMap(_.asLink).map(_.toString),
          created._2.hcursor.get[String]("cid").toOption)

        val car = commit.body("blocks").flatMap(_.asBytes).get
        val (roots, blocks) = Car.read(car).fold(fail(_), identity)
        assertEquals(roots.map(_.toString),
          Vector(created._2.hcursor.downField("commit").get[String]("cid").toOption.get))
        assert(blocks.map(_._1.toString)
          .contains(created._2.hcursor.get[String]("cid").toOption.get))
    }
  }

  test("a commit event's blocks verify against the account signing key") {
    harness().use { server =>
      for
        auth <- register(server)
        (access, did) = auth
        _ <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "record" -> post0("hello"))), access))
        sequence <- events(server)
        described <- server.json(get(s"/xrpc/com.atproto.repo.describeRepo?repo=$did"))
      yield
        val car = sequence.last.body("blocks").flatMap(_.asBytes).get
        val (roots, blocks) = Car.read(car).fold(fail(_), identity)
        val store = blocks.toMap
        val commit = Cbor.decode(store(roots.head)).flatMap(Commit.decode).fold(fail(_), identity)
        val key = described._2.hcursor.downField("didDoc").downField("verificationMethod")
          .downArray.get[String]("publicKeyMultibase").toOption
          .flatMap(pds.crypto.PublicKey.fromMultibase).get
        assert(commit.verify(key))
        assertEquals(commit.did, did)
    }
  }

  test("deletes, handle changes and deactivation each append an event") {
    harness().use { server =>
      for
        auth <- register(server)
        (access, did) = auth
        _ <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString("3jqfcqzm3fo2j"),
          "record" -> post0("hello"))), access))
        _ <- server.json(authorized(post("/xrpc/com.atproto.repo.deleteRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString("3jqfcqzm3fo2j"))), access))
        _ <- server.json(authorized(post("/xrpc/com.atproto.identity.updateHandle",
          Json.obj("handle" -> Json.fromString("alice2.pds.example.com"))), access))
        _ <- server.json(authorized(
          post("/xrpc/com.atproto.server.deactivateAccount", Json.obj()), access))
        sequence <- events(server)
      yield
        assertEquals(sequence.map(_.kind),
          Vector("#identity", "#account", "#sync", "#commit", "#commit", "#identity", "#account"))
        assertEquals(sequence(4).body("ops").flatMap(_.asVector).get.head("action")
          .flatMap(_.asString), Some("delete"))
        assertEquals(sequence(5).body("handle").flatMap(_.asString),
          Some("alice2.pds.example.com"))
        assertEquals(sequence(6).body("active"), Some(Node.Bool(false)))
        assertEquals(sequence(6).body("status").flatMap(_.asString), Some("deactivated"))
        assertEquals(sequence.map(_.seq), (1L to 7L).toVector)
    }
  }

  test("a rejected write leaves no event behind") {
    harness().use { server =>
      for
        auth <- register(server)
        (access, did) = auth
        before <- events(server)
        rejected <- server.json(authorized(post("/xrpc/com.atproto.repo.applyWrites", Json.obj(
          "repo" -> Json.fromString(did),
          "writes" -> Json.arr(
            Json.obj(
              "$type" -> Json.fromString("com.atproto.repo.applyWrites#create"),
              "collection" -> Json.fromString("app.bsky.feed.post"),
              "value" -> post0("one")),
            Json.obj(
              "$type" -> Json.fromString("com.atproto.repo.applyWrites#delete"),
              "collection" -> Json.fromString("app.bsky.feed.post"),
              "rkey" -> Json.fromString("absent"))))), access))
        after <- events(server)
      yield
        assertEquals(rejected._1, Status.BadRequest)
        assertEquals(after.length, before.length)
    }
  }

  test("frames carry a header envelope, the sequence number and the message type") {
    harness().use { server =>
      for
        _ <- register(server)
        sequence <- events(server)
      yield
        val frame = Events.frame(sequence.head)
        val (header, offset) = Cbor.decodePrefix(frame, 0).fold(fail(_), identity)
        assertEquals(header("op").flatMap(_.asLong), Some(1L))
        assertEquals(header("t").flatMap(_.asString), Some("#identity"))
        val (body, end) = Cbor.decodePrefix(frame, offset).fold(fail(_), identity)
        assertEquals(body("seq").flatMap(_.asLong), Some(1L))
        assertEquals(end, frame.length)

        val error = Events.errorFrame("FutureCursor", "Cursor is ahead of the sequence")
        val (errorHeader, errorOffset) = Cbor.decodePrefix(error, 0).fold(fail(_), identity)
        assertEquals(errorHeader("op").flatMap(_.asLong), Some(-1L))
        val (errorBody, _) = Cbor.decodePrefix(error, errorOffset).fold(fail(_), identity)
        assertEquals(errorBody("error").flatMap(_.asString), Some("FutureCursor"))
    }
  }

  test("backfill starts after the requested cursor") {
    harness().use { server =>
      for
        auth <- register(server)
        (access, did) = auth
        _ <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "record" -> post0("hello"))), access))
        all <- events(server)
        tail <- server.env.database.read(connection => Events.since(connection, 3L, 1000))
        latest <- server.env.database.read(Events.latest)
        beyond <- server.env.database.read(connection => Events.since(connection, latest, 1000))
      yield
        assertEquals(all.length, 4)
        assertEquals(tail.map(_.kind), Vector("#commit"))
        assertEquals(latest, 4L)
        assertEquals(beyond, Vector.empty)
    }
  }
