package pds.protocol

import io.circe.Json
import pds.crypto.Encoding

class CborSuite extends munit.FunSuite:
  test("upstream data-model fixtures encode to the expected bytes and CID") {
    val fixtures = Fixtures.json("data-model/data-model-fixtures.json")
    assert(fixtures.nonEmpty)
    fixtures.foreach { fixture =>
      val json = fixture.hcursor.downField("json").focus.get
      val expected = Encoding.unb64Std(fixture.hcursor.get[String]("cbor_base64").toOption.get).get
      val node = Node.fromJson(json).fold(error => fail(error), identity)
      val encoded = Cbor.encode(node)
      assertEquals(Encoding.hex(encoded), Encoding.hex(expected))
      assertEquals(Cid.ofCbor(encoded).toString, fixture.hcursor.get[String]("cid").toOption.get)
      val decoded = Cbor.decode(encoded).fold(error => fail(error), identity)
      assert(Node.same(decoded, node))
      assertEquals(Node.toJson(decoded).noSpaces, Node.toJson(node).noSpaces)
    }
  }

  test("valid data-model records are accepted") {
    Fixtures.json("data-model/data-model-valid.json").foreach { fixture =>
      val json = fixture.hcursor.downField("json").focus.get
      assert(Node.fromJson(json).isRight, fixture.noSpaces)
    }
  }

  test("invalid data-model records are rejected") {
    Fixtures.json("data-model/data-model-invalid.json").foreach { fixture =>
      val json = fixture.hcursor.downField("json").focus.get
      val result = Node.fromJson(json).flatMap { node =>
        if node.isInstanceOf[Node.Obj] then Right(node) else Left("Records must be objects")
      }
      assert(result.isLeft, s"accepted ${fixture.noSpaces}")
    }
  }

  test("integer-valued floats are accepted and fractional ones are not") {
    assertEquals(Node.fromJson(Json.fromDoubleOrNull(123.0)), Right(Node.Integer(123L)))
    assert(Node.fromJson(Json.fromDoubleOrNull(123.456)).isLeft)
  }

  test("non-canonical CBOR is rejected") {
    val cases = Map(
      "indefinite-length array" -> Array(0x9f, 0x01, 0xff),
      "indefinite-length map" -> Array(0xbf, 0x61, 0x61, 0x01, 0xff),
      "overlong integer" -> Array(0x18, 0x01),
      "overlong two-byte integer" -> Array(0x19, 0x00, 0x10),
      "float" -> Array(0xfa, 0x00, 0x00, 0x00, 0x00),
      "double" -> Array(0xfb, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
      "undefined" -> Array(0xf7),
      "unsorted map keys" -> Array(0xa2, 0x61, 0x62, 0x01, 0x61, 0x61, 0x02),
      "duplicate map keys" -> Array(0xa2, 0x61, 0x61, 0x01, 0x61, 0x61, 0x02),
      "length-sorted map keys out of order" -> Array(0xa2, 0x62, 0x61, 0x61, 0x01, 0x61, 0x62, 0x02),
      "integer map key" -> Array(0xa1, 0x01, 0x01),
      "unknown tag" -> Array(0xc1, 0x01),
      "trailing bytes" -> Array(0x01, 0x01)
    )
    cases.foreach { (name, bytes) =>
      assert(Cbor.decode(bytes.map(_.toByte)).isLeft, name)
    }
  }

  test("CIDs round-trip and reject other multibase or codec forms") {
    val cid = Cid.ofCbor(Encoding.utf8("block"))
    assertEquals(Cid.parse(cid.toString), Some(cid))
    assertEquals(cid.toString.length, 59)
    assert(Cid.parse("bafkreiccldh766hwcnuxnf2wh6jgzepf2nlu2lvcllt63eww5p6chi4ity").isDefined)
    assert(Cid.parse("QmdfTbBqBPQ7VNxZEYEj14VmRuZBkqFbiwReogJgS1zR1n").isEmpty)
    assert(Cid.parse("bafkreiccldh766hwcnuxnf2wh6jgzepf2nlu2lvcllt63eww5p6chi4it").isEmpty)
    assert(Cid.parse(cid.toString.toUpperCase).isEmpty)
  }

  test("links encode as tag 42 with the identity multibase prefix") {
    val cid = Cid.ofRaw(Encoding.utf8("blob"))
    val encoded = Cbor.encode(Node.Link(cid))
    assertEquals(encoded(0) & 0xff, 0xd8)
    assertEquals(encoded(1) & 0xff, 42)
    assertEquals(encoded(2) & 0xff, 0x58)
    assertEquals(encoded(3) & 0xff, 37)
    assertEquals(encoded(4) & 0xff, 0x00)
    assertEquals(Cbor.decode(encoded), Right(Node.Link(cid)))
  }

  test("blob references must be raw CIDs with a size and mime type") {
    def blob(link: String, size: Json) = Json.obj(
      "$type" -> Json.fromString("blob"),
      "ref" -> Json.obj("$link" -> Json.fromString(link)),
      "mimeType" -> Json.fromString("image/png"),
      "size" -> size
    )
    val raw = Cid.ofRaw(Encoding.utf8("image")).toString
    val cbor = Cid.ofCbor(Encoding.utf8("image")).toString
    assert(Node.fromJson(blob(raw, Json.fromLong(10))).isRight)
    assert(Node.fromJson(blob(cbor, Json.fromLong(10))).isLeft)
    assert(Node.fromJson(blob(raw, Json.fromLong(-1))).isLeft)
    assert(Node.fromJson(blob(raw, Json.fromString("10"))).isLeft)
    val node = Node.fromJson(blob(raw, Json.fromLong(10))).toOption.get
    assertEquals(Node.blobs(node).map(_.mimeType), Vector("image/png"))
  }
