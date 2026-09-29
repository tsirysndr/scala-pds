package pds.lexicon

import cats.effect.IO
import io.circe.Json
import org.http4s.Status
import pds.TestEnv.*
import pds.crypto.PublicKey
import pds.protocol.{Car, Repository}

class ResolutionSuite extends munit.CatsEffectSuite:
  private val credentials = Json.obj(
    "handle" -> Json.fromString("alice.pds.example.com"),
    "email" -> Json.fromString("alice@example.com"),
    "password" -> Json.fromString("correct horse battery")
  )

  private def schemaDocument(id: String, extra: Json = Json.obj()): Json = Json.obj(
    "$type" -> Json.fromString("com.atproto.lexicon.schema"),
    "lexicon" -> Json.fromInt(1),
    "id" -> Json.fromString(id),
    "defs" -> Json.obj(
      "main" -> Json.obj(
        "type" -> Json.fromString("record"),
        "key" -> Json.fromString("tid"),
        "record" -> Json.obj(
          "type" -> Json.fromString("object"),
          "required" -> Json.arr(Json.fromString("title")),
          "properties" -> Json.obj(
            "title" -> Json.obj(
              "type" -> Json.fromString("string"),
              "maxLength" -> Json.fromInt(64)),
            "createdAt" -> Json.obj(
              "type" -> Json.fromString("string"),
              "format" -> Json.fromString("datetime"))))))
  ).deepMerge(extra)

  test("authority names reverse the namespace under _lexicon") {
    assertEquals(Resolution.authorityName("com.example.custom.thing"),
      Right("_lexicon.custom.example.com."))
    assertEquals(Resolution.authorityName("com.atproto.lexicon.schema"),
      Right("_lexicon.lexicon.atproto.com."))
    assert(Resolution.authorityName("not an nsid").isLeft)
    assert(Resolution.authorityName("com." + ("a" * 60 + ".") * 5 + "thing").isLeft)
  }

  test("a published schema record must declare its own NSID and definitions") {
    val id = "com.example.custom.thing"
    assert(Admission.isSchemaRecord(id, schemaDocument(id)))
    assert(!Admission.isSchemaRecord("com.example.other.thing", schemaDocument(id)))
    assert(!Admission.isSchemaRecord(id,
      schemaDocument(id).deepMerge(Json.obj("lexicon" -> Json.fromInt(2)))))
    assert(!Admission.isSchemaRecord(id,
      schemaDocument(id).mapObject(_.add("defs", Json.obj()))))
    assert(!Admission.isSchemaRecord(id,
      schemaDocument(id).deepMerge(Json.obj("$type" -> Json.fromString("app.bsky.feed.post")))))
    assert(!Admission.isSchemaRecord(id,
      schemaDocument(id).mapObject(_.add("defs", Json.obj(
        "not a name" -> Json.obj("type" -> Json.fromString("object")))))))
  }

  test("admission rejects instructions the validator does not implement") {
    val id = "com.example.custom.thing"
    def reject(defs: Json) =
      Admission.catalog(_ => Some(Json.obj(
        "lexicon" -> Json.fromInt(1), "id" -> Json.fromString(id), "defs" -> defs)), id)

    assert(reject(Json.obj("main" -> Json.obj("type" -> Json.fromString("gadget"))))
      .left.exists(_.contains("Unsupported definition type")))
    assert(reject(Json.obj("main" -> Json.obj(
      "type" -> Json.fromString("string"), "sneaky" -> Json.True)))
      .left.exists(_.contains("unsupported fields")))
    assert(reject(Json.obj("main" -> Json.obj(
      "type" -> Json.fromString("record"), "key" -> Json.fromString("tid"),
      "record" -> Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.obj("a" -> Json.obj(
          "type" -> Json.fromString("string"),
          "format" -> Json.fromString("imaginary")))))))
      .left.exists(_.contains("Unsupported string format")))
    assert(reject(Json.obj("main" -> Json.obj(
      "type" -> Json.fromString("record"), "key" -> Json.fromString("uuid"),
      "record" -> Json.obj("type" -> Json.fromString("object")))))
      .left.exists(_.contains("Unsupported record key")))
    // A root that is not a record cannot be a collection schema.
    assert(reject(Json.obj("main" -> Json.obj("type" -> Json.fromString("object"))))
      .left.exists(_.contains("not a record schema")))
  }

  test("admission closes over referenced documents and refuses missing ones") {
    val id = "com.example.custom.thing"
    val other = "com.example.custom.other"
    val root = Json.obj(
      "lexicon" -> Json.fromInt(1), "id" -> Json.fromString(id),
      "defs" -> Json.obj("main" -> Json.obj(
        "type" -> Json.fromString("record"), "key" -> Json.fromString("tid"),
        "record" -> Json.obj(
          "type" -> Json.fromString("object"),
          "properties" -> Json.obj(
            "link" -> Json.obj("type" -> Json.fromString("ref"),
              "ref" -> Json.fromString(s"$other#thing")))))))
    val leaf = Json.obj(
      "lexicon" -> Json.fromInt(1), "id" -> Json.fromString(other),
      "defs" -> Json.obj("thing" -> Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.obj("a" -> Json.obj("type" -> Json.fromString("string"))))))

    val documents = Map(id -> root, other -> leaf)
    val catalog = Admission.catalog(documents.get, id).fold(fail(_), identity)
    assertEquals(catalog.schemas.keySet, Set(id, other))
    assertEquals(Admission.referencedIds(root), Set(other))

    assert(Admission.catalog(Map(id -> root).get, id)
      .left.exists(_.contains("could not be resolved")))
  }

  test("the admitted catalog actually validates records") {
    val id = "com.example.custom.thing"
    val catalog = Admission.catalog(
      Map(id -> schemaDocument(id).mapObject(_.remove("$type"))).get, id).fold(fail(_), identity)
    def record(title: Json) = pds.protocol.Node.fromJson(Json.obj(
      "$type" -> Json.fromString(id), "title" -> title)).fold(fail(_), identity)
    assertEquals(
      Validator.validateRecord(catalog, id, "3jqfcqzm3fo2j", record(Json.fromString("hi")), None),
      Right(Some("valid")))
    assert(Validator.validateRecord(catalog, id, "3jqfcqzm3fo2j", record(Json.fromInt(3)), None)
      .isLeft)
    assert(Validator.validateRecord(catalog, id, "not-a-tid", record(Json.fromString("hi")), None)
      .isLeft)
  }

  test("the cache serves, expires and briefly remembers failures") {
    val id = "com.example.custom.thing"
    for
      calls <- cats.effect.Ref.of[IO, Int](0)
      schemas <- Schemas.create { nsid =>
        calls.update(_ + 1).as(
          if nsid == id then Right(schemaDocument(id).mapObject(_.remove("$type")))
          else Left("no such namespace"))
      }
      first <- schemas.catalog(id, 1000L)
      second <- schemas.catalog(id, 2000L)
      afterTtl <- schemas.catalog(id, 1000L + Schemas.successMillis + 1)
      resolved <- calls.get
      missing <- schemas.catalog("com.example.other.thing", 1000L)
      cachedFailure <- schemas.known("com.example.other.thing", 1000L)
      retried <- schemas.catalog("com.example.other.thing", 1000L + Schemas.failureMillis + 1)
      total <- calls.get
    yield
      assert(first.isRight, first.toString)
      assert(second.isRight)
      assertEquals(resolved, 2, "a hit within the TTL must not resolve again")
      assert(afterTtl.isRight)
      assert(missing.isLeft)
      assertEquals(cachedFailure, None)
      assert(retried.isLeft)
      assertEquals(total, 4)
  }

  test("a record proof from this server verifies against the account key") {
    harness().use { server =>
      for
        created <- server.json(post("/xrpc/com.atproto.server.createAccount", credentials))
        access = created._2.hcursor.get[String]("accessJwt").toOption.get
        did = created._2.hcursor.get[String]("did").toOption.get
        _ <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString("3jqfcqzm3fo2j"),
          "record" -> Json.obj(
            "$type" -> Json.fromString("app.bsky.feed.post"),
            "text" -> Json.fromString("hello"),
            "createdAt" -> Json.fromString("2026-01-01T00:00:00.000Z")))), access))
        proof <- server.run(get(
          s"/xrpc/com.atproto.sync.getRecord?did=$did&collection=app.bsky.feed.post&rkey=3jqfcqzm3fo2j"))
        archive <- proof.body.compile.to(Array)
        absent <- server.run(get(
          s"/xrpc/com.atproto.sync.getRecord?did=$did&collection=app.bsky.feed.post&rkey=3jqfcqzm3fp2j"))
        absentArchive <- absent.body.compile.to(Array)
        described <- server.json(get(s"/xrpc/com.atproto.repo.describeRepo?repo=$did"))
      yield
        val key = described._2.hcursor.downField("didDoc").downField("verificationMethod")
          .downArray.get[String]("publicKeyMultibase").toOption.flatMap(PublicKey.fromMultibase).get

        val (commit, record) = Repository
          .proveRecord(archive, did, key, "app.bsky.feed.post", "3jqfcqzm3fo2j")
          .fold(fail(_), identity)
        assertEquals(commit.did, did)
        assertEquals(record.map((_, value) => value("text").flatMap(_.asString)),
          Some(Some("hello")))

        // The same proof shape also proves absence.
        assertEquals(Repository
          .proveRecord(absentArchive, did, key, "app.bsky.feed.post", "3jqfcqzm3fp2j")
          .map(_._2), Right(None))

        // Another key, another repository, and a tampered archive are refused.
        val other = pds.crypto.PrivateKey.generate(pds.crypto.Curve.K256).publicKey
        assert(Repository.proveRecord(archive, did, other, "app.bsky.feed.post", "3jqfcqzm3fo2j")
          .isLeft)
        assert(Repository.proveRecord(archive, "did:web:elsewhere", key,
          "app.bsky.feed.post", "3jqfcqzm3fo2j").isLeft)
        assert(Repository.proveRecord(
          archive.updated(archive.length - 1, (archive.last ^ 1).toByte), did, key,
          "app.bsky.feed.post", "3jqfcqzm3fo2j").isLeft)
    }
  }

  test("a resolved schema validates writes to a collection outside the catalog") {
    val id = "com.example.custom.thing"
    val published: String => IO[Either[String, Json]] = nsid =>
      IO.pure(
        if nsid == id then Right(schemaDocument(id).mapObject(_.remove("$type")))
        else Left("no such namespace"))

    harness(lexicons = Some(published)).use { server =>
      for
        created <- server.json(post("/xrpc/com.atproto.server.createAccount", credentials))
        access = created._2.hcursor.get[String]("accessJwt").toOption.get
        did = created._2.hcursor.get[String]("did").toOption.get
        base = Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString(id),
          "validate" -> Json.True)
        valid <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord",
          base.deepMerge(Json.obj("record" -> Json.obj(
            "$type" -> Json.fromString(id),
            "title" -> Json.fromString("a title"))))), access))
        invalid <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord",
          base.deepMerge(Json.obj("record" -> Json.obj(
            "$type" -> Json.fromString(id),
            "title" -> Json.fromInt(3))))), access))
        missingField <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord",
          base.deepMerge(Json.obj("record" -> Json.obj(
            "$type" -> Json.fromString(id))))), access))
        // Once cached, an unqualified write validates against it too.
        optimistic <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString(id),
          "record" -> Json.obj(
            "$type" -> Json.fromString(id),
            "title" -> Json.fromInt(3)))), access))
        unpublished <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("com.example.custom.other"),
          "validate" -> Json.True,
          "record" -> Json.obj(
            "$type" -> Json.fromString("com.example.custom.other")))), access))
      yield
        assertEquals(valid._1, Status.Ok)
        assertEquals(valid._2.hcursor.get[String]("validationStatus"), Right("valid"))
        assertEquals(invalid._1, Status.BadRequest)
        assertEquals(invalid._2.hcursor.get[String]("error"), Right("InvalidRecord"))
        assertEquals(missingField._2.hcursor.get[String]("error"), Right("InvalidRecord"))
        assertEquals(optimistic._1, Status.BadRequest)
        assertEquals(unpublished._1, Status.BadRequest)
        assertEquals(unpublished._2.hcursor.get[String]("error"), Right("InvalidRecord"))
    }
  }
