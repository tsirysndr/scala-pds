package pds.lexicon

import io.circe.Json
import pds.protocol.{Fixtures, Node}
import scala.io.Source

class ValidatorSuite extends munit.FunSuite:
  private val demo: Catalog =
    val stream = getClass.getResourceAsStream("/fixtures/lexicon/catalog/record.json")
    val text = Source.fromInputStream(stream, "UTF-8").mkString
    stream.close()
    val schema = io.circe.parser.parse(text).fold(throw _, identity)
    new Catalog(Map("example.lexicon.record" -> schema))

  private def node(json: Json): Node =
    Node.fromJson(json).fold(message => fail(message), identity)

  test("the trusted catalog loads, checksums and exposes its record roots") {
    val catalog = Catalog.trusted
    assert(catalog.schemas.size >= 100, catalog.schemas.size.toString)
    assert(catalog.record("app.bsky.feed.post").isDefined)
    assert(catalog.record("app.bsky.actor.profile").isDefined)
    assertEquals(catalog.record("app.bsky.feed.defs"), None)
    assertEquals(catalog.definition("app.bsky.feed.post", "#replyRef").map(_._1),
      Some("app.bsky.feed.post"))
    assertEquals(catalog.definition("app.bsky.feed.post", "com.atproto.repo.strongRef").map(_._1),
      Some("com.atproto.repo.strongRef"))
    assertEquals(catalog.definition("app.bsky.feed.post", "#nope"), None)
  }

  /** The upstream "full" fixture gives its blob a dag-cbor CID, which the
    * current data-model specification forbids. The data model is asserted to
    * reject that, and the CID is swapped for a raw one to reach the schema.
    */
  private def rawBlobs(json: Json): Json =
    json.arrayOrObject(json,
      values => Json.arr(values.map(rawBlobs)*),
      fields =>
        if fields("$type").flatMap(_.asString).contains("blob") then
          Json.fromJsonObject(fields.add("ref", Json.obj("$link" -> Json.fromString(
            pds.protocol.Cid.ofRaw(pds.crypto.Encoding.utf8("blob")).toString))))
        else Json.fromJsonObject(fields.mapValues(rawBlobs)))

  test("upstream valid record fixtures are accepted") {
    val fixtures = Fixtures.json("lexicon/record-data-valid.json")
    assert(fixtures.nonEmpty)
    fixtures.foreach { fixture =>
      val name = fixture.hcursor.get[String]("name").toOption.get
      val key = fixture.hcursor.get[String]("rkey").toOption.get
      val data = fixture.hcursor.downField("data").focus.get
      if name == "full" then assert(Node.fromJson(data).isLeft, "dag-cbor blob CID was accepted")
      val result = Validator.validateRecord(
        demo, "example.lexicon.record", key, node(rawBlobs(data)), None)
      assertEquals(result, Right(Some("valid")), s"$name: $result")
    }
  }

  test("upstream invalid record fixtures are rejected with a path and reason") {
    val fixtures = Fixtures.json("lexicon/record-data-invalid.json")
    assert(fixtures.nonEmpty)
    fixtures.foreach { fixture =>
      val name = fixture.hcursor.get[String]("name").toOption.get
      val key = fixture.hcursor.get[String]("rkey").toOption.get
      val data = fixture.hcursor.downField("data").focus.get
      val parsed = Node.fromJson(data)
      // Some fixtures are already refused by the data model itself.
      val result = parsed.left.map(message => Validator.Invalid("$", message))
        .flatMap(value =>
          Validator.validateRecord(demo, "example.lexicon.record", key, value, None))
      assert(result.isLeft, s"accepted $name")
      assert(result.left.exists(_.message.startsWith("$")), result.toString)
    }
  }

  test("record keys must match the schema's declared key type") {
    val data = Json.obj(
      "$type" -> Json.fromString("example.lexicon.record"),
      "integer" -> Json.fromInt(1))
    assertEquals(
      Validator.validateRecord(demo, "example.lexicon.record", "demo", node(data), None),
      Right(Some("valid")))
    assert(Validator.validateRecord(demo, "example.lexicon.record", "other", node(data), None)
      .left.exists(_.path == "$rkey"))

    val post = Json.obj(
      "$type" -> Json.fromString("app.bsky.feed.post"),
      "text" -> Json.fromString("hello"),
      "createdAt" -> Json.fromString("2026-01-01T00:00:00.000Z"))
    assertEquals(Validator.validateRecord(
      Catalog.trusted, "app.bsky.feed.post", "3jqfcqzm3fo2j", node(post), None),
      Right(Some("valid")))
    assert(Validator.validateRecord(
      Catalog.trusted, "app.bsky.feed.post", "not-a-tid", node(post), None)
      .left.exists(_.path == "$rkey"))
  }

  test("the record $type must match the collection it is written to") {
    val post = node(Json.obj(
      "$type" -> Json.fromString("app.bsky.feed.like"),
      "text" -> Json.fromString("hello")))
    assert(Validator.validateRecord(
      Catalog.trusted, "app.bsky.feed.post", "3jqfcqzm3fo2j", post, None).isLeft)
  }

  test("graphemes are counted separately from bytes") {
    def post(text: String) = node(Json.obj(
      "$type" -> Json.fromString("app.bsky.feed.post"),
      "text" -> Json.fromString(text),
      "createdAt" -> Json.fromString("2026-01-01T00:00:00.000Z")))
    val family = "👨‍👩‍👧"
    // text is bounded at 3000 bytes and 300 graphemes, independently: one
    // family emoji is a single grapheme but eighteen bytes.
    assertEquals(Validator.validateRecord(
      Catalog.trusted, "app.bsky.feed.post", "3jqfcqzm3fo2j", post(family * 150), None),
      Right(Some("valid")))
    assert(Validator.validateRecord(
      Catalog.trusted, "app.bsky.feed.post", "3jqfcqzm3fo2j", post("a" * 301), None)
      .left.exists(_.reason.contains("maxGraphemes")))
    assert(Validator.validateRecord(
      Catalog.trusted, "app.bsky.feed.post", "3jqfcqzm3fo2j", post(family * 250), None)
      .left.exists(_.reason.contains("maxLength")))
  }

  test("string formats are enforced") {
    def like(subject: Json, createdAt: String) = node(Json.obj(
      "$type" -> Json.fromString("app.bsky.feed.like"),
      "subject" -> subject,
      "createdAt" -> Json.fromString(createdAt)))
    val subject = Json.obj(
      "uri" -> Json.fromString("at://did:plc:abc123/app.bsky.feed.post/3jqfcqzm3fo2j"),
      "cid" -> Json.fromString("bafyreiclp443lavogvhj3d2ob2cxbfuscni2k5jk7bebjzg7khl3esabwq"))
    assertEquals(Validator.validateRecord(Catalog.trusted, "app.bsky.feed.like",
      "3jqfcqzm3fo2j", like(subject, "2026-01-01T00:00:00.000Z"), None), Right(Some("valid")))
    assert(Validator.validateRecord(Catalog.trusted, "app.bsky.feed.like",
      "3jqfcqzm3fo2j", like(subject, "not a datetime"), None)
      .left.exists(_.reason.contains("datetime")))
    assert(Validator.validateRecord(Catalog.trusted, "app.bsky.feed.like", "3jqfcqzm3fo2j",
      like(subject.deepMerge(Json.obj("uri" -> Json.fromString("nope"))), "2026-01-01T00:00:00.000Z"),
      None).left.exists(_.reason.contains("at-uri")))
  }

  test("unknown collections are reported, or refused when validation is demanded") {
    val record = node(Json.obj("$type" -> Json.fromString("com.example.custom")))
    assertEquals(
      Validator.validateRecord(Catalog.trusted, "com.example.custom", "self", record, None),
      Right(Some("unknown")))
    assertEquals(
      Validator.validateRecord(Catalog.trusted, "com.example.custom", "self", record, Some(false)),
      Right(None))
    assert(
      Validator.validateRecord(Catalog.trusted, "com.example.custom", "self", record, Some(true))
        .left.exists(_.reason.contains("no known record schema")))
  }

  test("skipping validation also skips a known schema's rules") {
    val broken = node(Json.obj(
      "$type" -> Json.fromString("app.bsky.feed.post"),
      "text" -> Json.fromInt(3)))
    assert(Validator.validateRecord(
      Catalog.trusted, "app.bsky.feed.post", "3jqfcqzm3fo2j", broken, None).isLeft)
    assertEquals(Validator.validateRecord(
      Catalog.trusted, "app.bsky.feed.post", "3jqfcqzm3fo2j", broken, Some(false)), Right(None))
  }

  test("open unions accept unknown members, closed unions and untagged values do not") {
    def profile(label: Json) = node(Json.obj(
      "$type" -> Json.fromString("app.bsky.actor.profile"),
      "labels" -> label))
    val known = Json.obj(
      "$type" -> Json.fromString("com.atproto.label.defs#selfLabels"),
      "values" -> Json.arr(Json.obj("val" -> Json.fromString("!no-unauthenticated"))))
    assertEquals(Validator.validateRecord(
      Catalog.trusted, "app.bsky.actor.profile", "self", profile(known), None),
      Right(Some("valid")))

    // The profile labels union is open, so a future member is carried through.
    val unknown = Json.obj("$type" -> Json.fromString("com.example.other#thing"))
    assertEquals(Validator.validateRecord(
      Catalog.trusted, "app.bsky.actor.profile", "self", profile(unknown), None),
      Right(Some("valid")))

    // Without a $type there is no member to select at all.
    assert(Validator.validateRecord(Catalog.trusted, "app.bsky.actor.profile", "self",
      profile(Json.obj("values" -> Json.arr())), None).isLeft)

    // applyWrites#writes is closed, so an unknown member is refused.
    val closed = Catalog.trusted.definition("com.atproto.repo.applyWrites", "#create").get._2
    assert(closed.hcursor.get[String]("type").contains("object"))
    val writes = Catalog.trusted.schema("com.atproto.repo.applyWrites").get
      .hcursor.downField("defs").downField("main").downField("input")
      .downField("schema").downField("properties").downField("writes")
      .downField("items").focus.get
    assert(writes.hcursor.get[Boolean]("closed").contains(true), writes.noSpaces)
    assert(Validator.validate(Catalog.trusted, "com.atproto.repo.applyWrites", writes,
      node(Json.obj("$type" -> Json.fromString("com.example.other#thing"))))
      .left.exists(_.reason.contains("unknown union")))
  }

  test("blob constraints on size and MIME type are enforced") {
    def profile(mime: String, size: Long) = node(Json.obj(
      "$type" -> Json.fromString("app.bsky.actor.profile"),
      "avatar" -> Json.obj(
        "$type" -> Json.fromString("blob"),
        "ref" -> Json.obj("$link" -> Json.fromString(
          pds.protocol.Cid.ofRaw(pds.crypto.Encoding.utf8("image")).toString)),
        "mimeType" -> Json.fromString(mime),
        "size" -> Json.fromLong(size))))
    assertEquals(Validator.validateRecord(
      Catalog.trusted, "app.bsky.actor.profile", "self", profile("image/png", 1000), None),
      Right(Some("valid")))
    assert(Validator.validateRecord(
      Catalog.trusted, "app.bsky.actor.profile", "self", profile("text/plain", 1000), None)
      .left.exists(_.reason.contains("MIME")))
    assert(Validator.validateRecord(
      Catalog.trusted, "app.bsky.actor.profile", "self", profile("image/png", 10_000_000), None)
      .left.exists(_.reason.contains("maxSize")))
  }

  test("every catalog record root validates a minimal instance or names its gaps") {
    val catalog = Catalog.trusted
    val roots = catalog.schemas.keys.filter(id => catalog.record(id).isDefined).toVector.sorted
    assert(roots.length >= 15, roots.length.toString)
    roots.foreach { id =>
      val record = node(Json.obj("$type" -> Json.fromString(id)))
      val key = catalog.record(id).flatMap(_.hcursor.get[String]("key").toOption).getOrElse("any")
      val recordKey = key match
        case "tid"                                     => "3jqfcqzm3fo2j"
        case literal if literal.startsWith("literal:") => literal.drop(8)
        case _                                         => "self"
      // Either it validates, or it fails on a required field: never on an
      // unsupported type or an unresolved reference, which would throw.
      Validator.validateRecord(catalog, id, recordKey, record, Some(true)) match
        case Right(_)      => ()
        case Left(invalid) => assert(invalid.reason == "is required", s"$id: ${invalid.message}")
    }
  }

  test("datetime, URI and language formats follow their fixtures") {
    assert(Formats.datetime("1985-04-12T23:20:50.123Z"))
    assert(Formats.datetime("1985-04-12T23:20:50.1235678912345Z"))
    assert(!Formats.datetime("1985-04-12T23:20:50.123-00:00"))
    assert(!Formats.datetime("1985-04-12t23:20:50.123Z"))
    assert(!Formats.datetime("1985-04-12T23:20:50.123"))
    assert(Formats.uri("https://example.com/path"))
    assert(Formats.uri("at://did:plc:abc/app.bsky.feed.post/3jqfcqzm3fo2j"))
    assert(!Formats.uri("not a uri"))
    assert(!Formats.uri("/relative"))
    assert(Formats.language("en"))
    assert(Formats.language("en-GB"))
    assert(Formats.language("i-klingon"))
    assert(!Formats.language("not a language"))
    assertEquals(Formats.validator("unheard-of"), None)
  }
