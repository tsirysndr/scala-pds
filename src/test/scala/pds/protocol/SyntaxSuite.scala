package pds.protocol

import scala.io.Source

class SyntaxSuite extends munit.FunSuite:
  private def fixture(name: String): List[String] =
    val stream = getClass.getResourceAsStream(s"/fixtures/syntax/$name.txt")
    assert(stream != null, s"missing fixture $name")
    val lines = Source.fromInputStream(stream, "UTF-8").getLines().toList
    stream.close()
    lines.filter(line => line.nonEmpty && !line.startsWith("#"))

  private def check(name: String, expected: Boolean)(predicate: String => Boolean): Unit =
    fixture(name).foreach(value => assertEquals(predicate(value), expected, value))

  test("handles follow the interop fixtures") {
    check("handle_syntax_valid", true)(Syntax.isHandle)
    check("handle_syntax_invalid", false)(Syntax.isHandle)
  }

  test("DIDs follow the interop fixtures") {
    check("did_syntax_valid", true)(Syntax.isDid)
    check("did_syntax_invalid", false)(Syntax.isDid)
  }

  test("AT identifiers accept both DIDs and handles") {
    check("atidentifier_syntax_valid", true)(Syntax.isAtIdentifier)
    check("atidentifier_syntax_invalid", false)(Syntax.isAtIdentifier)
  }

  test("NSIDs follow the interop fixtures") {
    // One upstream "valid" fixture exceeds the 253-character domain authority
    // that https://atproto.com/specs/nsid specifies; it is rejected.
    fixture("nsid_syntax_valid").foreach { value =>
      val authority = value.split("\\.", -1).dropRight(1).mkString(".")
      assertEquals(Syntax.isNsid(value), authority.length <= 253, value)
    }
    check("nsid_syntax_invalid", false)(Syntax.isNsid)
  }

  test("record keys follow the interop fixtures") {
    check("recordkey_syntax_valid", true)(Syntax.isRecordKey)
    check("recordkey_syntax_invalid", false)(Syntax.isRecordKey)
  }

  test("TIDs follow the interop fixtures") {
    check("tid_syntax_valid", true)(Syntax.isTid)
    check("tid_syntax_invalid", false)(Syntax.isTid)
  }

  test("datetimes follow the interop fixtures") {
    check("datetime_syntax_valid", true)(Syntax.isDatetime)
    check("datetime_syntax_invalid", false)(Syntax.isDatetime)
    check("datetime_parse_invalid", false)(Syntax.isDatetime)
  }

  test("AT URIs follow the interop fixtures") {
    check("aturi_syntax_valid", true)(value => AtUri.parse(value).isDefined)
    check("aturi_syntax_invalid", false)(value => AtUri.parse(value).isDefined)
  }

  test("AT URIs round-trip their parsed components") {
    val uri = AtUri.parse("at://did:plc:abc123/app.bsky.feed.post/3jui7kd54zh2y").get
    assertEquals(uri.authority, "did:plc:abc123")
    assertEquals(uri.collection, Some("app.bsky.feed.post"))
    assertEquals(uri.recordKey, Some("3jui7kd54zh2y"))
    assertEquals(uri.toString, "at://did:plc:abc123/app.bsky.feed.post/3jui7kd54zh2y")
  }

  test("generated TIDs are sortable, unique and canonical") {
    val values = Vector.fill(500)(Tid.next())
    assertEquals(values.distinct.length, values.length)
    assertEquals(values.sorted, values)
    values.foreach(value => assert(Syntax.isTid(value), value))
  }

  test("handles normalize to lowercase before storage") {
    assertEquals(Syntax.normalizeHandle("Alice.Example.COM"), "alice.example.com")
  }
