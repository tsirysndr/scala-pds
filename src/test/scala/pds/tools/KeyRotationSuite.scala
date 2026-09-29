package pds.tools

import cats.effect.{IO, Ref}
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import pds.TestEnv
import pds.TestEnv.*
import pds.crypto.PublicKey
import pds.protocol.{Car, Cbor, Commit, Repository}
import pds.storage.Sql

class KeyRotationSuite extends munit.CatsEffectSuite:
  private val credentials = Json.obj(
    "handle" -> Json.fromString("alice.pds.example.com"),
    "email" -> Json.fromString("alice@example.com"),
    "password" -> Json.fromString("correct horse battery")
  )

  private def register(server: Harness) =
    server.json(post("/xrpc/com.atproto.server.createAccount", credentials)).map { (_, body) =>
      (body.hcursor.get[String]("accessJwt").toOption.get,
        body.hcursor.get[String]("did").toOption.get)
    }

  private def write(server: Harness, access: String, did: String) =
    server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
      "repo" -> Json.fromString(did),
      "collection" -> Json.fromString("app.bsky.feed.post"),
      "rkey" -> Json.fromString("3jqfcqzm3fo2j"),
      "record" -> Json.obj(
        "$type" -> Json.fromString("app.bsky.feed.post"),
        "text" -> Json.fromString("hello"),
        "createdAt" -> Json.fromString("2026-01-01T00:00:00.000Z")))), access))

  private def publishedKey(server: Harness, did: String): IO[PublicKey] =
    server.json(get(s"/xrpc/com.atproto.repo.describeRepo?repo=$did")).map { (_, body) =>
      body.hcursor.downField("didDoc").downField("verificationMethod").downArray
        .get[String]("publicKeyMultibase").toOption.flatMap(PublicKey.fromMultibase).get
    }

  test("rotating the signing key re-signs the head so exports still verify") {
    harness().use { server =>
      for
        auth <- register(server)
        (access, did) = auth
        _ <- write(server, access, did)
        before <- publishedKey(server, did)
        beforeHead <- server.json(get(s"/xrpc/com.atproto.sync.getLatestCommit?did=$did"))
        result <- KeyRotation.rotate(server.env, "alice.pds.example.com",
          signing = true, rotation = false)
        after <- publishedKey(server, did)
        afterHead <- server.json(get(s"/xrpc/com.atproto.sync.getLatestCommit?did=$did"))
        exported <- server.run(get(s"/xrpc/com.atproto.sync.getRepo?did=$did"))
        archive <- exported.body.compile.to(Array)
        listed <- server.json(get(
          s"/xrpc/com.atproto.repo.listRecords?repo=$did&collection=app.bsky.feed.post"))
      yield
        assertNotEquals(after, before, "the published key must change")
        assertEquals(result.signingKey, Some(after.didKey))
        assert(result.revision.isDefined)
        assertNotEquals(afterHead._2.hcursor.get[String]("cid").toOption,
          beforeHead._2.hcursor.get[String]("cid").toOption)
        assert(afterHead._2.hcursor.get[String]("rev").toOption.get >
          beforeHead._2.hcursor.get[String]("rev").toOption.get)

        // The export verifies against the new document, not the old key.
        val (roots, blocks) = Car.read(archive).fold(fail(_), identity)
        val store = blocks.toMap
        val commit = Cbor.decode(store(roots.head)).flatMap(Commit.decode).fold(fail(_), identity)
        assert(commit.verify(after))
        assert(!commit.verify(before))
        assertEquals(Repository.verify(commit, after, store.get), Right(()))
        // The records themselves are untouched.
        assertEquals(listed._2.hcursor.downField("records").values.map(_.size), Some(1))
    }
  }

  test("rotating the rotation key replaces only the server's own key") {
    harness().use { server =>
      for
        auth <- register(server)
        (_, did) = auth
        before <- server.env.database.read(connection =>
          Sql.first(connection, "SELECT rotation_public, signing_public FROM account_keys WHERE did = ?",
            did)(row => (row.stringOpt("rotation_public"), row.string("signing_public"))).get)
        result <- KeyRotation.rotate(server.env, did, signing = false, rotation = true)
        after <- server.env.database.read(connection =>
          Sql.first(connection, "SELECT rotation_public, signing_public FROM account_keys WHERE did = ?",
            did)(row => (row.stringOpt("rotation_public"), row.string("signing_public"))).get)
        head <- server.json(get(s"/xrpc/com.atproto.sync.getLatestCommit?did=$did"))
      yield
        assertNotEquals(after._1, before._1)
        assertEquals(after._2, before._2, "the signing key must be untouched")
        assertEquals(result.rotationKey, after._1)
        assertEquals(result.revision, None, "no re-signing is needed")
        assertEquals(head._1, Status.Ok)
    }
  }

  test("the new signing key is sealed and usable for the next write") {
    harness().use { server =>
      for
        auth <- register(server)
        (access, did) = auth
        _ <- KeyRotation.rotate(server.env, did, signing = true, rotation = true)
        published <- publishedKey(server, did)
        written <- write(server, access, did)
        exported <- server.run(get(s"/xrpc/com.atproto.sync.getRepo?did=$did"))
        archive <- exported.body.compile.to(Array)
        opens <- MasterKeyRotation.verify(server.env.database, server.env.sealing)
      yield
        assertEquals(written._1, Status.Ok)
        val (roots, blocks) = Car.read(archive).fold(fail(_), identity)
        val store = blocks.toMap
        val commit = Cbor.decode(store(roots.head)).flatMap(Commit.decode).fold(fail(_), identity)
        assert(commit.verify(published), "writes after rotation use the published key")
        assertEquals(opens.signingKeys, 1)
        assertEquals(opens.rotationKeys, 1)
    }
  }

  test("rotation emits identity and commit events") {
    harness().use { server =>
      for
        auth <- register(server)
        (access, did) = auth
        _ <- write(server, access, did)
        before <- server.env.database.read(connection =>
          pds.firehose.Events.since(connection, 0L, 100).map(_.kind))
        _ <- KeyRotation.rotate(server.env, did, signing = true, rotation = false)
        after <- server.env.database.read(connection =>
          pds.firehose.Events.since(connection, 0L, 100).map(_.kind))
      yield
        assertEquals(before, Vector("#identity", "#account", "#sync", "#commit"))
        assertEquals(after.drop(before.length), Vector("#commit", "#identity"))
    }
  }

  test("an unknown or inactive account, and an empty request, are refused") {
    harness().use { server =>
      for
        auth <- register(server)
        (access, did) = auth
        unknown <- KeyRotation.rotate(server.env, "nobody.pds.example.com",
          signing = true, rotation = true).attempt
        nothing <- KeyRotation.rotate(server.env, did, signing = false, rotation = false).attempt
        _ <- server.json(authorized(
          post("/xrpc/com.atproto.server.deactivateAccount", Json.obj()), access))
        inactive <- KeyRotation.rotate(server.env, did, signing = true, rotation = false).attempt
      yield
        assert(unknown.left.exists(_.getMessage.contains("No account matches")))
        assert(nothing.left.exists(_.getMessage.contains("Nothing to rotate")))
        assert(inactive.left.exists(_.getMessage.contains("is not active")))
    }
  }
