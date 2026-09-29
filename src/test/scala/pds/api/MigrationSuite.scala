package pds.api

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import pds.TestEnv.*
import pds.protocol.{Car, Cbor, Cid, Commit, Tid}
import pds.storage.Sql

class MigrationSuite extends munit.CatsEffectSuite:
  private def account(name: String) = Json.obj(
    "handle" -> Json.fromString(s"$name.pds.example.com"),
    "email" -> Json.fromString(s"$name@example.com"),
    "password" -> Json.fromString("correct horse battery")
  )

  private def post0(text: String) = Json.obj(
    "$type" -> Json.fromString("app.bsky.feed.post"),
    "text" -> Json.fromString(text),
    "createdAt" -> Json.fromString("2026-01-01T00:00:00.000Z")
  )

  private def register(server: Harness, name: String) =
    server.json(post("/xrpc/com.atproto.server.createAccount", account(name))).map { (_, body) =>
      (body.hcursor.get[String]("accessJwt").toOption.get,
        body.hcursor.get[String]("did").toOption.get)
    }

  /** app.bsky.feed.post declares a TID record key, so generate real ones. */
  private def key(index: Int): String = Tid.encode(1780000000000000L + index, 0)

  private def write(server: Harness, access: String, did: String, index: Int) =
    server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
      "repo" -> Json.fromString(did),
      "collection" -> Json.fromString("app.bsky.feed.post"),
      "rkey" -> Json.fromString(key(index)),
      "record" -> post0(s"post $index"))), access))

  private def exportCar(server: Harness, did: String): IO[Array[Byte]] =
    server.run(get(s"/xrpc/com.atproto.sync.getRepo?did=$did"))
      .flatMap(_.body.compile.to(Array))

  private def importCar(server: Harness, access: String, car: Array[Byte]) =
    server.json(authorized(
      Request[IO](Method.POST, Uri.unsafeFromString("/xrpc/com.atproto.repo.importRepo"))
        .withEntity(car)
        .withContentType(`Content-Type`(MediaType.unsafeParse("application/vnd.ipld.car"))),
      access))

  test("an exported repository re-imports and rebuilds the record index") {
    harness().use { server =>
      for
        auth <- register(server, "alice")
        (access, did) = auth
        _ <- (1 to 12).toVector.foldLeft(IO.unit)((acc, index) =>
          acc *> write(server, access, did, index).void)
        before <- server.json(get(s"/xrpc/com.atproto.sync.getLatestCommit?did=$did"))
        car <- exportCar(server, did)
        // Drop the index so the import has to rebuild it from the tree alone.
        _ <- server.env.database.transact(connection =>
          Sql.update(connection, "DELETE FROM records WHERE did = ?", did))
        emptied <- server.json(get(
          s"/xrpc/com.atproto.repo.listRecords?repo=$did&collection=app.bsky.feed.post"))
        imported <- importCar(server, access, car)
        after <- server.json(get(s"/xrpc/com.atproto.sync.getLatestCommit?did=$did"))
        listed <- server.json(get(
          s"/xrpc/com.atproto.repo.listRecords?repo=$did&collection=app.bsky.feed.post&limit=100"))
        status <- server.json(authorized(get("/xrpc/com.atproto.server.checkAccountStatus"), access))
      yield
        assertEquals(emptied._2.hcursor.downField("records").values.map(_.size), Some(0))
        assertEquals(imported._1, Status.Ok)
        assertEquals(after._2, before._2)
        assertEquals(listed._2.hcursor.downField("records").values.map(_.size), Some(12))
        assertEquals(status._2.hcursor.get[Long]("indexedRecords"), Right(12L))
    }
  }

  test("an archive belonging to another account is refused") {
    harness().use { server =>
      for
        alice <- register(server, "alice")
        bob <- register(server, "bobby")
        _ <- write(server, alice._1, alice._2, 1)
        car <- exportCar(server, alice._2)
        refused <- importCar(server, bob._1, car)
        bobRecords <- server.json(get(
          s"/xrpc/com.atproto.repo.listRecords?repo=${bob._2}&collection=app.bsky.feed.post"))
      yield
        assertEquals(refused._1, Status.BadRequest)
        assertEquals(bobRecords._2.hcursor.downField("records").values.map(_.size), Some(0))
    }
  }

  test("a tampered or truncated archive is refused before anything is stored") {
    harness().use { server =>
      for
        auth <- register(server, "alice")
        (access, did) = auth
        _ <- write(server, access, did, 1)
        car <- exportCar(server, did)
        tampered <- importCar(server, access, car.updated(car.length - 1, (car.last ^ 1).toByte))
        truncated <- importCar(server, access, car.dropRight(5))
        empty <- importCar(server, access, Array.emptyByteArray)
        head <- server.json(get(s"/xrpc/com.atproto.sync.getLatestCommit?did=$did"))
        listed <- server.json(get(
          s"/xrpc/com.atproto.repo.listRecords?repo=$did&collection=app.bsky.feed.post"))
      yield
        assertEquals(tampered._1, Status.BadRequest)
        assertEquals(truncated._1, Status.BadRequest)
        assertEquals(empty._1, Status.BadRequest)
        assertEquals(head._1, Status.Ok)
        assertEquals(listed._2.hcursor.downField("records").values.map(_.size), Some(1))
    }
  }

  test("an archive whose commit is not signed by the account key is refused") {
    harness().use { server =>
      for
        auth <- register(server, "alice")
        (access, did) = auth
        _ <- write(server, access, did, 1)
        car <- exportCar(server, did)
        forged = resign(car, did)
        refused <- importCar(server, access, forged)
      yield assertEquals(refused._1, Status.BadRequest)
    }
  }

  /** Re-signs the commit with a key the account does not hold. */
  private def resign(car: Array[Byte], did: String): Array[Byte] =
    val (roots, blocks) = Car.read(car).fold(fail(_), identity)
    val store = blocks.toMap
    val commit = Cbor.decode(store(roots.head)).flatMap(Commit.decode).fold(fail(_), identity)
    val key = pds.crypto.PrivateKey.generate(pds.crypto.Curve.K256)
    val forged = Commit.sign(did, commit.data, commit.rev, commit.prev, key)
    val replaced = blocks.filterNot(_._1 == roots.head) :+ (forged.cid -> forged.bytes)
    Car.write(Vector(forged.cid), replaced)

  test("blob transfer is driven by the list of missing blobs") {
    harness().use { server =>
      val bytes = "an avatar".getBytes("UTF-8")
      val cid = Cid.ofRaw(bytes)
      for
        auth <- register(server, "alice")
        (access, did) = auth
        uploaded <- server.json(authorized(
          Request[IO](Method.POST, Uri.unsafeFromString("/xrpc/com.atproto.repo.uploadBlob"))
            .withEntity(bytes)
            .withContentType(`Content-Type`(MediaType.image.png)), access))
        _ <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.actor.profile"),
          "rkey" -> Json.fromString("self"),
          "record" -> Json.obj(
            "$type" -> Json.fromString("app.bsky.actor.profile"),
            "avatar" -> uploaded._2.hcursor.downField("blob").focus.get))), access))
        complete <- server.json(authorized(get("/xrpc/com.atproto.repo.listMissingBlobs"), access))
        statusBefore <- server.json(authorized(
          get("/xrpc/com.atproto.server.checkAccountStatus"), access))
        // Drop the stored bytes, as a repository imported before its blobs.
        _ <- server.env.database.transact(connection =>
          Sql.update(connection, "DELETE FROM blobs WHERE did = ?", did))
        missing <- server.json(authorized(get("/xrpc/com.atproto.repo.listMissingBlobs"), access))
        statusAfter <- server.json(authorized(
          get("/xrpc/com.atproto.server.checkAccountStatus"), access))
      yield
        assertEquals(complete._2.hcursor.downField("blobs").values.map(_.size), Some(0))
        assertEquals(statusBefore._2.hcursor.get[Long]("expectedBlobs"), Right(1L))
        assertEquals(statusBefore._2.hcursor.get[Long]("importedBlobs"), Right(1L))
        assertEquals(missing._2.hcursor.downField("blobs").downArray.get[String]("cid"),
          Right(cid.toString))
        assertEquals(missing._2.hcursor.downField("blobs").downArray.get[String]("recordUri"),
          Right(s"at://$did/app.bsky.actor.profile/self"))
        assertEquals(statusAfter._2.hcursor.get[Long]("importedBlobs"), Right(0L))
    }
  }

  test("a large repository exports and re-imports without buffering it whole") {
    harness().use { server =>
      for
        auth <- register(server, "alice")
        (access, did) = auth
        // Enough records that the tree is several layers deep.
        _ <- (1 to 300).toVector.foldLeft(IO.unit)((acc, index) =>
          acc *> write(server, access, did, index).void)
        exported <- server.run(get(s"/xrpc/com.atproto.sync.getRepo?did=$did"))
        archive <- exported.body.compile.to(Array)
        head <- server.json(get(s"/xrpc/com.atproto.sync.getLatestCommit?did=$did"))
        _ <- server.env.database.transact(connection =>
          Sql.update(connection, "DELETE FROM records WHERE did = ?", did))
        imported <- importCar(server, access, archive)
        listed <- server.json(get(
          s"/xrpc/com.atproto.repo.listRecords?repo=$did&collection=app.bsky.feed.post&limit=100"))
        status <- server.json(authorized(
          get("/xrpc/com.atproto.server.checkAccountStatus"), access))
      yield
        assertEquals(exported.status, Status.Ok)
        assertEquals(exported.headers.get(org.typelevel.ci.CIString("Atproto-Repo-Rev"))
          .map(_.head.value), head._2.hcursor.get[String]("rev").toOption)
        val (roots, blocks) = Car.read(archive).fold(fail(_), identity)
        assertEquals(roots.length, 1)
        // commit + tree nodes + one block per record
        assert(blocks.length > 300, blocks.length.toString)
        assertEquals(imported._1, Status.Ok)
        assertEquals(listed._2.hcursor.downField("records").values.map(_.size), Some(100))
        assertEquals(status._2.hcursor.get[Long]("indexedRecords"), Right(300L))
    }
  }

  test("a signing key can be reserved for a DID before it is adopted") {
    harness().use { server =>
      for
        reserved <- server.json(post("/xrpc/com.atproto.server.reserveSigningKey", Json.obj()))
        again <- server.json(post("/xrpc/com.atproto.server.reserveSigningKey", Json.obj()))
      yield
        assertEquals(reserved._1, Status.Ok)
        val key = reserved._2.hcursor.get[String]("signingKey").toOption.get
        assert(key.startsWith("did:key:z"), key)
        assert(pds.crypto.PublicKey.fromDidKey(key).isDefined)
        assertNotEquals(again._2.hcursor.get[String]("signingKey").toOption, Some(key))
    }
  }

  test("an unresolvable or mispointed DID cannot be adopted at registration") {
    harness().use { server =>
      for
        unresolvable <- server.json(post("/xrpc/com.atproto.server.createAccount",
          account("alice").deepMerge(Json.obj(
            "did" -> Json.fromString("did:plc:doesnotexistanywhere")))))
        malformed <- server.json(post("/xrpc/com.atproto.server.createAccount",
          account("alice").deepMerge(Json.obj("did" -> Json.fromString("not-a-did")))))
      yield
        assertEquals(unresolvable._1, Status.BadRequest)
        assertEquals(malformed._1, Status.BadRequest)
    }
  }

  test("an account is only valid once its document names this server and key") {
    for
      hosted <- selfHosted().use { server =>
        for
          auth <- register(server, "alice")
          (access, _) = auth
          status <- server.json(authorized(
            get("/xrpc/com.atproto.server.checkAccountStatus"), access))
        yield status._2.hcursor.get[Boolean]("validDid")
      }
      unresolvable <- harness().use { server =>
        for
          auth <- register(server, "alice")
          (access, _) = auth
          status <- server.json(authorized(
            get("/xrpc/com.atproto.server.checkAccountStatus"), access))
        yield status._2.hcursor.get[Boolean]("validDid")
      }
    yield
      assertEquals(hosted, Right(true))
      assertEquals(unresolvable, Right(false))
  }
