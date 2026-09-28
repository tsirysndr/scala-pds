package pds.identity

import io.circe.Json
import pds.crypto.{Curve, PrivateKey}
import pds.protocol.{Cbor, Node}

class PlcSuite extends munit.FunSuite:
  private val signing = PrivateKey.generate(Curve.K256)
  private val rotation = PrivateKey.generate(Curve.K256)
  private val endpoint = "https://pds.example.com"

  private def signed =
    Plc.genesis("alice.example.com", signing.publicKey, Vector(rotation.publicKey), endpoint)
      .sign(rotation)

  test("the DID is derived from the signed genesis operation") {
    val operation = signed
    val did = Plc.did(operation)
    assert(did.startsWith("did:plc:"), did)
    assertEquals(did.length, "did:plc:".length + 24)
    assertEquals(Plc.did(operation), did)
    assert(did.drop(8).forall(c => "abcdefghijklmnopqrstuvwxyz234567".contains(c)))
  }

  test("a different key or handle produces a different DID") {
    val other = Plc.genesis("bob.example.com", signing.publicKey, Vector(rotation.publicKey), endpoint)
      .sign(rotation)
    assertNotEquals(Plc.did(signed), Plc.did(other))
  }

  test("operations verify under their rotation key only") {
    val operation = signed
    assert(operation.verify(rotation.publicKey))
    assert(!operation.verify(signing.publicKey))
    assert(!operation.copy(alsoKnownAs = Vector("at://eve.example.com")).verify(rotation.publicKey))
  }

  test("operations round-trip through their JSON form") {
    val operation = signed
    val parsed = Plc.parse(operation.json).fold(fail(_), identity)
    assertEquals(parsed, operation)
    assert(parsed.verify(rotation.publicKey))
    assertEquals(parsed.services("atproto_pds"), ("AtprotoPersonalDataServer", endpoint))
    assertEquals(parsed.verificationMethods("atproto"), signing.publicKey.didKey)
  }

  test("the signature covers the operation without the sig field") {
    val operation = signed
    val unsigned = Cbor.decode(operation.unsignedBytes).fold(fail(_), identity)
    assertEquals(unsigned("sig"), None)
    assertEquals(unsigned("type").flatMap(_.asString), Some("plc_operation"))
  }

  test("updates chain from the previous operation CID") {
    val genesis = signed
    val next = Plc.update(genesis, Plc.cid(genesis))(
      _.copy(alsoKnownAs = Vector("at://alice2.example.com"))).sign(rotation)
    assertEquals(next.prev, Some(Plc.cid(genesis)))
    assert(next.verify(rotation.publicKey))
    assertNotEquals(Plc.cid(next), Plc.cid(genesis))
  }

  test("malformed operations are rejected") {
    assert(Plc.parse(Json.obj("type" -> Json.fromString("plc_tombstone"))).isLeft)
    assert(Plc.parse(Json.obj(
      "type" -> Json.fromString("plc_operation"),
      "rotationKeys" -> Json.arr()
    )).isLeft)
  }

  test("the derived document exposes the signing key, handle and endpoint") {
    val operation = signed
    val did = Plc.did(operation)
    val document = DidDocument.parse(Plc.document(did, operation)).fold(fail(_), identity)
    assertEquals(document.id, did)
    assertEquals(document.handle, Some("alice.example.com"))
    assertEquals(document.signingKey, Some(signing.publicKey))
    assertEquals(document.pdsEndpoint, Some(endpoint))
  }

  test("DID documents built for hosted accounts parse back") {
    val document = DidDocument.build("did:web:pds.example.com:u:alice", "alice.example.com",
      signing.publicKey, endpoint)
    val parsed = DidDocument.parse(document).fold(fail(_), identity)
    assertEquals(parsed.signingKey, Some(signing.publicKey))
    assertEquals(parsed.handle, Some("alice.example.com"))
    assertEquals(parsed.pdsEndpoint, Some(endpoint))
  }

  test("documents without an atproto method or endpoint expose none") {
    val document = DidDocument.parse(DidDocument.service("did:web:pds.example.com", endpoint))
      .fold(fail(_), identity)
    assertEquals(document.signingKey, None)
    assertEquals(document.pdsEndpoint, Some(endpoint))
    assertEquals(document.handle, None)
    assert(DidDocument.parse(Json.obj("id" -> Json.fromString("not-a-did"))).isLeft)
  }
