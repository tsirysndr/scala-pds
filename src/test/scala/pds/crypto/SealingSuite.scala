package pds.crypto

class SealingSuite extends munit.FunSuite:
  private val sealing = Sealing.fromBase64(Sealing.generate()).toOption.get

  test("sealed values round-trip only under the same purpose and key") {
    val secret = Encoding.utf8("signing key material")
    val box = sealing.seal("pds/test", secret)
    assertEquals(sealing.open("pds/test", box).map(Encoding.text), Some("signing key material"))
    assertEquals(sealing.open("pds/other", box), None)
    val other = Sealing.fromBase64(Sealing.generate()).toOption.get
    assertEquals(other.open("pds/test", box), None)
  }

  test("sealing is randomized and detects tampering") {
    val secret = Encoding.utf8("secret")
    assertNotEquals(Encoding.hex(sealing.seal("p", secret)), Encoding.hex(sealing.seal("p", secret)))
    val box = sealing.seal("p", secret)
    assertEquals(sealing.open("p", box.updated(box.length - 1, (box.last ^ 1).toByte)), None)
    assertEquals(sealing.open("p", box.take(8)), None)
  }

  test("signing keys survive sealing on both curves") {
    Curve.values.foreach { curve =>
      val key = PrivateKey.generate(curve)
      assertEquals(sealing.openKey(curve, sealing.sealKey(key)), Some(key))
    }
  }

  test("master keys must be 32 bytes of base64url") {
    assert(Sealing.fromBase64("short").isLeft)
    assert(Sealing.fromBase64(Encoding.b64(Hash.randomBytes(16))).isLeft)
    assert(Sealing.fromBase64(Sealing.generate()).isRight)
  }

  test("keyed identifiers are stable per purpose and message") {
    assertEquals(sealing.mac("csrf", "nonce"), sealing.mac("csrf", "nonce"))
    assertNotEquals(sealing.mac("csrf", "nonce"), sealing.mac("other", "nonce"))
    assertNotEquals(sealing.mac("csrf", "nonce"), sealing.mac("csrf", "other"))
  }
