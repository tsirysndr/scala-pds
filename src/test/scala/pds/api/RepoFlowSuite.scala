package pds.api

import cats.effect.IO
import io.circe.Json
import org.http4s.{Header, MediaType, Method, Request, Status, Uri}
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import pds.TestEnv.*
import pds.protocol.{Car, Cbor, Cid, Commit, Mst, MstOps, Node, Repository}

class RepoFlowSuite extends munit.CatsEffectSuite:
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

  private def signedIn(server: Harness) =
    server.json(post("/xrpc/com.atproto.server.createAccount", credentials)).map { (_, body) =>
      (body.hcursor.get[String]("accessJwt").toOption.get,
        body.hcursor.get[String]("did").toOption.get)
    }

  test("records are created, read, listed, updated and deleted") {
    harness().use { server =>
      for
        auth <- signedIn(server)
        (access, did) = auth
        created <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "record" -> post0("hello"))), access))
        uri = created._2.hcursor.get[String]("uri").toOption.get
        key = uri.split("/").last
        fetched <- server.json(get(
          s"/xrpc/com.atproto.repo.getRecord?repo=$did&collection=app.bsky.feed.post&rkey=$key"))
        updated <- server.json(authorized(post("/xrpc/com.atproto.repo.putRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString(key),
          "record" -> post0("edited"))), access))
        afterUpdate <- server.json(get(
          s"/xrpc/com.atproto.repo.getRecord?repo=$did&collection=app.bsky.feed.post&rkey=$key"))
        listed <- server.json(get(
          s"/xrpc/com.atproto.repo.listRecords?repo=$did&collection=app.bsky.feed.post"))
        described <- server.json(get(s"/xrpc/com.atproto.repo.describeRepo?repo=$did"))
        deleted <- server.json(authorized(post("/xrpc/com.atproto.repo.deleteRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString(key))), access))
        afterDelete <- server.json(get(
          s"/xrpc/com.atproto.repo.getRecord?repo=$did&collection=app.bsky.feed.post&rkey=$key"))
      yield
        assertEquals(created._1, Status.Ok)
        assertEquals(fetched._2.hcursor.downField("value").get[String]("text"), Right("hello"))
        assertEquals(updated._1, Status.Ok)
        assertEquals(afterUpdate._2.hcursor.downField("value").get[String]("text"), Right("edited"))
        assertNotEquals(afterUpdate._2.hcursor.get[String]("cid").toOption,
          fetched._2.hcursor.get[String]("cid").toOption)
        assertEquals(listed._2.hcursor.downField("records").values.map(_.size), Some(1))
        assertEquals(described._2.hcursor.get[Vector[String]]("collections"),
          Right(Vector("app.bsky.feed.post")))
        assertEquals(deleted._1, Status.Ok)
        assertEquals(afterDelete._1, Status.BadRequest)
        assertEquals(afterDelete._2.hcursor.get[String]("error"), Right("RecordNotFound"))
    }
  }

  test("record writes are rejected without authentication or across accounts") {
    harness().use { server =>
      for
        auth <- signedIn(server)
        (access, did) = auth
        other <- server.json(post("/xrpc/com.atproto.server.createAccount", Json.obj(
          "handle" -> Json.fromString("bob.pds.example.com"),
          "email" -> Json.fromString("bob@example.com"),
          "password" -> Json.fromString("correct horse battery"))))
        otherDid = other._2.hcursor.get[String]("did").toOption.get
        anonymous <- server.json(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "record" -> post0("hello"))))
        crossAccount <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(otherDid),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "record" -> post0("hello"))), access))
      yield
        assertEquals(anonymous._1, Status.Unauthorized)
        assertEquals(crossAccount._1, Status.Forbidden)
    }
  }

  test("record validation rejects bad collections, keys and mismatched types") {
    harness().use { server =>
      for
        auth <- signedIn(server)
        (access, did) = auth
        badCollection <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("not an nsid"),
          "record" -> post0("hello"))), access))
        badKey <- server.json(authorized(post("/xrpc/com.atproto.repo.putRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString(".."),
          "record" -> post0("hello"))), access))
        mismatched <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "record" -> Json.obj("$type" -> Json.fromString("app.bsky.feed.like")))), access))
        float <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "record" -> Json.obj(
            "$type" -> Json.fromString("app.bsky.feed.post"),
            "value" -> Json.fromDoubleOrNull(1.5)))), access))
      yield
        assertEquals(badCollection._1, Status.BadRequest)
        assertEquals(badKey._1, Status.BadRequest)
        assertEquals(mismatched._1, Status.BadRequest)
        assertEquals(float._1, Status.BadRequest)
    }
  }

  test("compare-and-swap protects concurrent writers") {
    harness().use { server =>
      for
        auth <- signedIn(server)
        (access, did) = auth
        created <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString("3jqfcqzm3fo2j"),
          "record" -> post0("hello"))), access))
        cid = created._2.hcursor.get[String]("cid").toOption.get
        stale <- server.json(authorized(post("/xrpc/com.atproto.repo.putRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString("3jqfcqzm3fo2j"),
          "swapRecord" -> Json.fromString(
            Cid.ofRaw(pds.crypto.Encoding.utf8("other")).toString),
          "record" -> post0("edited"))), access))
        fresh <- server.json(authorized(post("/xrpc/com.atproto.repo.putRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString("3jqfcqzm3fo2j"),
          "swapRecord" -> Json.fromString(cid),
          "record" -> post0("edited"))), access))
        staleCommit <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "swapCommit" -> Json.fromString(
            Cid.ofCbor(pds.crypto.Encoding.utf8("other")).toString),
          "record" -> post0("hello"))), access))
      yield
        assertEquals(stale._1, Status.BadRequest)
        assertEquals(stale._2.hcursor.get[String]("error"), Right("InvalidSwap"))
        assertEquals(fresh._1, Status.Ok)
        assertEquals(staleCommit._2.hcursor.get[String]("error"), Right("InvalidSwap"))
    }
  }

  test("applyWrites commits a batch atomically") {
    harness().use { server =>
      for
        auth <- signedIn(server)
        (access, did) = auth
        applied <- server.json(authorized(post("/xrpc/com.atproto.repo.applyWrites", Json.obj(
          "repo" -> Json.fromString(did),
          "writes" -> Json.arr(
            Json.obj(
              "$type" -> Json.fromString("com.atproto.repo.applyWrites#create"),
              "collection" -> Json.fromString("app.bsky.feed.post"),
              "rkey" -> Json.fromString("3jqfcqzm3fo2j"),
              "value" -> post0("one")),
            Json.obj(
              "$type" -> Json.fromString("com.atproto.repo.applyWrites#create"),
              "collection" -> Json.fromString("app.bsky.feed.post"),
              "rkey" -> Json.fromString("3jqfcqzm3fp2j"),
              "value" -> post0("two"))))), access))
        listed <- server.json(get(
          s"/xrpc/com.atproto.repo.listRecords?repo=$did&collection=app.bsky.feed.post"))
        conflicting <- server.json(authorized(post("/xrpc/com.atproto.repo.applyWrites", Json.obj(
          "repo" -> Json.fromString(did),
          "writes" -> Json.arr(
            Json.obj(
              "$type" -> Json.fromString("com.atproto.repo.applyWrites#create"),
              "collection" -> Json.fromString("app.bsky.feed.post"),
              "rkey" -> Json.fromString("3jqfcqzm3fq2j"),
              "value" -> post0("three")),
            Json.obj(
              "$type" -> Json.fromString("com.atproto.repo.applyWrites#delete"),
              "collection" -> Json.fromString("app.bsky.feed.post"),
              "rkey" -> Json.fromString("absent"))))), access))
        afterFailure <- server.json(get(
          s"/xrpc/com.atproto.repo.listRecords?repo=$did&collection=app.bsky.feed.post"))
      yield
        assertEquals(applied._1, Status.Ok)
        assertEquals(applied._2.hcursor.downField("results").values.map(_.size), Some(2))
        assertEquals(listed._2.hcursor.downField("records").values.map(_.size), Some(2))
        assertEquals(conflicting._1, Status.BadRequest)
        assertEquals(afterFailure._2.hcursor.downField("records").values.map(_.size), Some(2))
    }
  }

  test("blobs upload, download and must exist before a record references them") {
    harness().use { server =>
      val bytes = "an image".getBytes("UTF-8")
      val cid = Cid.ofRaw(bytes)
      for
        auth <- signedIn(server)
        (access, did) = auth
        missing <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.actor.profile"),
          "rkey" -> Json.fromString("self"),
          "record" -> Json.obj(
            "$type" -> Json.fromString("app.bsky.actor.profile"),
            "avatar" -> Json.obj(
              "$type" -> Json.fromString("blob"),
              "ref" -> Json.obj("$link" -> Json.fromString(cid.toString)),
              "mimeType" -> Json.fromString("image/png"),
              "size" -> Json.fromInt(bytes.length))))), access))
        uploaded <- server.json(authorized(
          Request[IO](Method.POST, Uri.unsafeFromString("/xrpc/com.atproto.repo.uploadBlob"))
            .withEntity(bytes)
            .withContentType(`Content-Type`(MediaType.image.png)), access))
        written <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.actor.profile"),
          "rkey" -> Json.fromString("self"),
          "record" -> Json.obj(
            "$type" -> Json.fromString("app.bsky.actor.profile"),
            "avatar" -> uploaded._2.hcursor.downField("blob").focus.get))), access))
        downloaded <- server.run(get(s"/xrpc/com.atproto.sync.getBlob?did=$did&cid=$cid"))
        body <- downloaded.body.compile.to(Array)
        listed <- server.json(get(s"/xrpc/com.atproto.sync.listBlobs?did=$did"))
      yield
        assertEquals(missing._1, Status.BadRequest)
        assertEquals(uploaded._2.hcursor.downField("blob").downField("ref").get[String]("$link"),
          Right(cid.toString))
        assertEquals(written._1, Status.Ok)
        assertEquals(downloaded.status, Status.Ok)
        assertEquals(new String(body, "UTF-8"), "an image")
        assertEquals(listed._2.hcursor.get[Vector[String]]("cids"), Right(Vector(cid.toString)))
    }
  }

  test("blobs above the configured limit are refused") {
    harness(Map("PDS_BLOB_MAX_SIZE" -> "2048")).use { server =>
      for
        auth <- signedIn(server)
        (access, _) = auth
        response <- server.json(authorized(
          Request[IO](Method.POST, Uri.unsafeFromString("/xrpc/com.atproto.repo.uploadBlob"))
            .withEntity(new Array[Byte](4096))
            .withContentType(`Content-Type`(MediaType.image.png)), access))
      yield
        assertEquals(response._1, Status.PayloadTooLarge)
    }
  }

  test("an exported repository verifies against the account signing key") {
    harness().use { server =>
      for
        auth <- signedIn(server)
        (access, did) = auth
        _ <- (1 to 30).toVector.foldLeft(IO.unit) { (acc, index) =>
          acc *> server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
            "repo" -> Json.fromString(did),
            "collection" -> Json.fromString("app.bsky.feed.post"),
            "record" -> post0(s"post $index"))), access)).void
        }
        exported <- server.run(get(s"/xrpc/com.atproto.sync.getRepo?did=$did"))
        bytes <- exported.body.compile.to(Array)
        latest <- server.json(get(s"/xrpc/com.atproto.sync.getLatestCommit?did=$did"))
        described <- server.json(get(s"/xrpc/com.atproto.repo.describeRepo?repo=$did"))
      yield
        assertEquals(exported.status, Status.Ok)
        assertEquals(exported.contentType.map(_.mediaType.subType), Some("vnd.ipld.car"))
        val (roots, blocks) = Car.read(bytes).fold(fail(_), identity)
        val store = blocks.toMap
        assertEquals(roots.length, 1)
        assertEquals(roots.head.toString, latest._2.hcursor.get[String]("cid").toOption.get)
        val commit = Cbor.decode(store(roots.head)).flatMap(Commit.decode).fold(fail(_), identity)
        assertEquals(commit.did, did)
        val key = described._2.hcursor.downField("didDoc").downField("verificationMethod")
          .downArray.get[String]("publicKeyMultibase").toOption
          .flatMap(pds.crypto.PublicKey.fromMultibase).get
        assert(commit.verify(key))
        assertEquals(Repository.verify(commit, key, store.get), Right(()))
        val tree = new Mst.Store(store.get)
        assertEquals(MstOps.entries(tree, tree.tree(commit.data).toOption.get)
          .fold(fail(_), identity).length, 30)
    }
  }

  test("sync serves inclusion proofs, blocks and the repository listing") {
    harness().use { server =>
      for
        auth <- signedIn(server)
        (access, did) = auth
        created <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString("3jqfcqzm3fo2j"),
          "record" -> post0("hello"))), access))
        proof <- server.run(get(
          s"/xrpc/com.atproto.sync.getRecord?did=$did&collection=app.bsky.feed.post&rkey=3jqfcqzm3fo2j"))
        proofBytes <- proof.body.compile.to(Array)
        recordCid = created._2.hcursor.get[String]("cid").toOption.get
        blocks <- server.run(get(s"/xrpc/com.atproto.sync.getBlocks?did=$did&cids=$recordCid"))
        blockBytes <- blocks.body.compile.to(Array)
        repos <- server.json(get("/xrpc/com.atproto.sync.listRepos"))
        byCollection <- server.json(get(
          "/xrpc/com.atproto.sync.listReposByCollection?collection=app.bsky.feed.post"))
        absent <- server.json(get(s"/xrpc/com.atproto.sync.getBlocks?did=$did&cids=" +
          Cid.ofCbor(pds.crypto.Encoding.utf8("nothing")).toString))
      yield
        assertEquals(proof.status, Status.Ok)
        val proofBlocks = Car.read(proofBytes).fold(fail(_), identity)._2.map(_._1.toString)
        assert(proofBlocks.contains(recordCid), proofBlocks.toString)
        assertEquals(Car.read(blockBytes).fold(fail(_), identity)._2.length, 1)
        assertEquals(repos._2.hcursor.downField("repos").values.map(_.size), Some(1))
        assertEquals(byCollection._2.hcursor.downField("repos").values.map(_.size), Some(1))
        assertEquals(absent._1, Status.BadRequest)
        assertEquals(absent._2.hcursor.get[String]("error"), Right("BlockNotFound"))
    }
  }

  test("record takedowns hide a record without deleting the repository") {
    harness().use { server =>
      for
        auth <- signedIn(server)
        (access, did) = auth
        created <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString("3jqfcqzm3fo2j"),
          "record" -> post0("hello"))), access))
        uri = created._2.hcursor.get[String]("uri").toOption.get
        _ <- server.json(admin(post("/xrpc/com.atproto.admin.updateSubjectStatus", Json.obj(
          "subject" -> Json.obj(
            "$type" -> Json.fromString("com.atproto.repo.strongRef"),
            "uri" -> Json.fromString(uri)),
          "takedown" -> Json.obj("applied" -> Json.True)))))
        hidden <- server.json(get(
          s"/xrpc/com.atproto.repo.getRecord?repo=$did&collection=app.bsky.feed.post&rkey=3jqfcqzm3fo2j"))
        listed <- server.json(get(
          s"/xrpc/com.atproto.repo.listRecords?repo=$did&collection=app.bsky.feed.post"))
        status <- server.json(admin(get(
          s"/xrpc/com.atproto.admin.getSubjectStatus?uri=${java.net.URLEncoder.encode(uri, "UTF-8")}")))
      yield
        assertEquals(hidden._1, Status.BadRequest)
        assertEquals(listed._2.hcursor.downField("records").values.map(_.size), Some(0))
        assertEquals(status._2.hcursor.downField("takedown").get[Boolean]("applied"), Right(true))
    }
  }

  test("account status reports repository counters") {
    harness().use { server =>
      for
        auth <- signedIn(server)
        (access, did) = auth
        _ <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "record" -> post0("hello"))), access))
        status <- server.json(authorized(get("/xrpc/com.atproto.server.checkAccountStatus"), access))
      yield
        assertEquals(status._2.hcursor.get[Boolean]("activated"), Right(true))
        assertEquals(status._2.hcursor.get[Long]("indexedRecords"), Right(1L))
        assert(status._2.hcursor.get[Long]("repoBlocks").toOption.exists(_ >= 3L))
    }
  }
