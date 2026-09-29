package pds.repo

import cats.effect.IO
import io.circe.Json
import org.http4s.Status
import pds.TestEnv.*
import pds.protocol.{Car, Cbor, Commit, Tid}
import pds.storage.Sql

class BlockGcSuite extends munit.CatsEffectSuite:
  private val credentials = Json.obj(
    "handle" -> Json.fromString("alice.pds.example.com"),
    "email" -> Json.fromString("alice@example.com"),
    "password" -> Json.fromString("correct horse battery")
  )

  private def post0(text: String) = Json.obj(
    "$type" -> Json.fromString("app.bsky.feed.post"),
    "text" -> Json.fromString(text),
    "createdAt" -> Json.fromString("2026-01-01T00:00:00.000Z"))

  private def register(server: Harness) =
    server.json(post("/xrpc/com.atproto.server.createAccount", credentials)).map { (_, body) =>
      (body.hcursor.get[String]("accessJwt").toOption.get,
        body.hcursor.get[String]("did").toOption.get)
    }

  private def key(index: Int) = Tid.encode(1780000000000000L + index, 0)

  private def blocks(server: Harness, did: String): IO[Long] =
    server.env.database.read(connection =>
      Sql.count(connection, "SELECT COUNT(*) AS total FROM repo_blocks WHERE did = ?", did))

  test("orphaned blocks are reclaimed once no retained event needs them") {
    harness().use { server =>
      for
        auth <- register(server)
        (access, did) = auth
        // Rewrite one record repeatedly: each version orphans the last.
        _ <- (1 to 10).toVector.foldLeft(IO.unit)((acc, index) =>
          acc *> server.json(authorized(post("/xrpc/com.atproto.repo.putRecord", Json.obj(
            "repo" -> Json.fromString(did),
            "collection" -> Json.fromString("app.bsky.feed.post"),
            "rkey" -> Json.fromString(key(1)),
            "record" -> post0(s"version $index"))), access)).void)
        before <- blocks(server, did)
        // While the events are retained, nothing may be reclaimed.
        guarded <- BlockGc.collect(server.env, did)
        stillThere <- blocks(server, did)
        _ <- server.env.database.transact(connection =>
          Sql.update(connection, "DELETE FROM repo_events WHERE did = ?", did))
        collected <- BlockGc.collect(server.env, did)
        after <- blocks(server, did)
      yield
        assert(before >= 12L, before.toString)
        assertEquals(guarded.removed, 0, "retained events still need those revisions")
        assertEquals(stillThere, before)
        assert(collected.removed > 0, collected.toString)
        assertEquals(after, before - collected.removed)
    }
  }

  test("the repository still exports and verifies after collection") {
    harness().use { server =>
      for
        auth <- register(server)
        (access, did) = auth
        _ <- (1 to 8).toVector.foldLeft(IO.unit)((acc, index) =>
          acc *> server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
            "repo" -> Json.fromString(did),
            "collection" -> Json.fromString("app.bsky.feed.post"),
            "rkey" -> Json.fromString(key(index)),
            "record" -> post0(s"post $index"))), access)).void)
        _ <- server.json(authorized(post("/xrpc/com.atproto.repo.deleteRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString(key(3)))), access))
        _ <- server.env.database.transact(connection =>
          Sql.update(connection, "DELETE FROM repo_events WHERE did = ?", did))
        collected <- BlockGc.collect(server.env, did)
        exported <- server.run(get(s"/xrpc/com.atproto.sync.getRepo?did=$did"))
        archive <- exported.body.compile.to(Array)
        described <- server.json(get(s"/xrpc/com.atproto.repo.describeRepo?repo=$did"))
        listed <- server.json(get(
          s"/xrpc/com.atproto.repo.listRecords?repo=$did&collection=app.bsky.feed.post"))
      yield
        assert(collected.removed > 0)
        assertEquals(exported.status, Status.Ok)
        val (roots, parsed) = Car.read(archive).fold(fail(_), identity)
        val store = parsed.toMap
        val commit = Cbor.decode(store(roots.head)).flatMap(Commit.decode).fold(fail(_), identity)
        val signing = described._2.hcursor.downField("didDoc").downField("verificationMethod")
          .downArray.get[String]("publicKeyMultibase").toOption
          .flatMap(pds.crypto.PublicKey.fromMultibase).get
        assert(commit.verify(signing))
        assertEquals(
          pds.protocol.Repository.verify(commit, signing, store.get), Right(()))
        assertEquals(listed._2.hcursor.downField("records").values.map(_.size), Some(7))
    }
  }

  test("nothing the current tree needs is ever removed") {
    harness().use { server =>
      for
        auth <- register(server)
        (access, did) = auth
        _ <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString(key(1)),
          "record" -> post0("only"))), access))
        _ <- server.env.database.transact(connection =>
          Sql.update(connection, "DELETE FROM repo_events WHERE did = ?", did))
        live <- server.env.database.read(connection =>
          BlockGc.reachable(connection, did).fold(fail(_), identity))
        collected <- BlockGc.collect(server.env, did)
        after <- server.env.database.read(connection =>
          Sql.query(connection, "SELECT cid FROM repo_blocks WHERE did = ?", did)(_.string("cid"))
            .flatMap(pds.protocol.Cid.parse).toSet)
        again <- BlockGc.collect(server.env, did)
        fetched <- server.json(get(
          s"/xrpc/com.atproto.repo.getRecord?repo=$did&collection=app.bsky.feed.post&rkey=${key(1)}"))
      yield
        // The superseded genesis commit and its empty tree are real orphans.
        assert(collected.removed > 0, collected.toString)
        assert(live.subsetOf(after), "a reachable block was removed")
        assertEquals(after, live, "only reachable blocks should remain")
        assertEquals(again.removed, 0, "collection is idempotent")
        assertEquals(fetched._1, Status.Ok)
    }
  }

  test("events expire on the configured window and never before it") {
    harness(Map("PDS_FIREHOSE_RETENTION_HOURS" -> "1")).use { server =>
      for
        auth <- register(server)
        (_, did) = auth
        before <- server.env.database.read(connection =>
          Sql.count(connection, "SELECT COUNT(*) AS total FROM repo_events"))
        kept <- BlockGc.expireEvents(server.env)
        stillThere <- server.env.database.read(connection =>
          Sql.count(connection, "SELECT COUNT(*) AS total FROM repo_events"))
        now <- server.env.now
        _ <- server.env.database.transact(connection =>
          Sql.update(connection, "UPDATE repo_events SET created_at = ?", now - 7200_000L))
        removed <- BlockGc.expireEvents(server.env)
        after <- server.env.database.read(connection =>
          Sql.count(connection, "SELECT COUNT(*) AS total FROM repo_events"))
      yield
        assertEquals(before, 3L)
        assertEquals(kept, 0)
        assertEquals(stillThere, 3L)
        assertEquals(removed, 3)
        assertEquals(after, 0L)
    }
  }

  test("retention of zero keeps every event and collects nothing") {
    harness(Map("PDS_FIREHOSE_RETENTION_HOURS" -> "0")).use { server =>
      for
        auth <- register(server)
        (_, did) = auth
        now <- server.env.now
        _ <- server.env.database.transact(connection =>
          Sql.update(connection, "UPDATE repo_events SET created_at = ?", now - 999_999_999L))
        removed <- BlockGc.expireEvents(server.env)
        swept <- BlockGc.sweep(server.env)
        events <- server.env.database.read(connection =>
          Sql.count(connection, "SELECT COUNT(*) AS total FROM repo_events"))
      yield
        assertEquals(removed, 0)
        assertEquals(swept, Vector.empty)
        assertEquals(events, 3L)
    }
  }

  test("the sweep covers accounts and reports what it examined") {
    harness().use { server =>
      for
        auth <- register(server)
        (access, did) = auth
        _ <- server.json(authorized(post("/xrpc/com.atproto.repo.putRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString(key(1)),
          "record" -> post0("one"))), access))
        _ <- server.json(authorized(post("/xrpc/com.atproto.repo.putRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString(key(1)),
          "record" -> post0("two"))), access))
        _ <- server.env.database.transact(connection =>
          Sql.update(connection, "DELETE FROM repo_events"))
        results <- BlockGc.sweep(server.env)
      yield
        assertEquals(results.map(_.did), Vector(did))
        assert(results.head.examined > 0, results.toString)
        assert(results.head.removed > 0, results.toString)
    }
  }
