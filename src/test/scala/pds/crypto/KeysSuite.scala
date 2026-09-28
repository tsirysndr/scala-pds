package pds.crypto

import pds.protocol.Fixtures

class KeysSuite extends munit.FunSuite:
  test("upstream signature fixtures verify exactly when atproto accepts them") {
    val fixtures = Fixtures.json("crypto/signature-fixtures.json")
    assert(fixtures.nonEmpty)
    fixtures.foreach { fixture =>
      val cursor = fixture.hcursor
      val comment = cursor.get[String]("comment").getOrElse("")
      val message = Encoding.unb64Std(cursor.get[String]("messageBase64").toOption.get).get
      val signature = Encoding.unb64Std(cursor.get[String]("signatureBase64").toOption.get).get
      val expected = cursor.get[Boolean]("validSignature").toOption.get
      val did = cursor.get[String]("publicKeyDid").toOption.get
      val key = PublicKey.fromDidKey(did)
      assert(key.isDefined, s"$comment: $did")
      assertEquals(key.get.verify(message, signature), expected, comment)
      assertEquals(key.get.legacyMultibase, cursor.get[String]("publicKeyMultibase").toOption.get, comment)
    }
  }

  test("W3C did:key vectors derive from their private keys") {
    Fixtures.json("crypto/w3c_didkey_K256.json").foreach { fixture =>
      val bytes = Encoding.unhex(fixture.hcursor.get[String]("privateKeyBytesHex").toOption.get).get
      val key = PrivateKey.fromBytes(Curve.K256, bytes).get
      assertEquals(key.publicKey.didKey, fixture.hcursor.get[String]("publicDidKey").toOption.get)
    }
    Fixtures.json("crypto/w3c_didkey_P256.json").foreach { fixture =>
      val bytes = Encoding.unbase58(fixture.hcursor.get[String]("privateKeyBytesBase58").toOption.get).get
      val key = PrivateKey.fromBytes(Curve.P256, bytes).get
      assertEquals(key.publicKey.didKey, fixture.hcursor.get[String]("publicDidKey").toOption.get)
    }
  }

  test("signatures round-trip on both curves and reject altered messages") {
    Curve.values.foreach { curve =>
      val key = PrivateKey.generate(curve)
      val message = Encoding.utf8("commit bytes")
      val signature = key.sign(message)
      assertEquals(signature.length, 64)
      assert(key.publicKey.verify(message, signature))
      assert(!key.publicKey.verify(Encoding.utf8("other bytes"), signature))
      assert(!key.publicKey.verify(message, signature.updated(0, (signature(0) ^ 1).toByte)))
      assertEquals(PublicKey.fromDidKey(key.publicKey.didKey), Some(key.publicKey))
      assertEquals(PrivateKey.fromBytes(curve, key.bytes), Some(key))
    }
  }

  test("signing is deterministic so equal commits produce equal bytes") {
    val key = PrivateKey.generate(Curve.K256)
    val message = Encoding.utf8("same input")
    assertEquals(Encoding.hex(key.sign(message)), Encoding.hex(key.sign(message)))
  }

  test("public keys expose JWK coordinates of the right width") {
    val key = PrivateKey.generate(Curve.P256).publicKey
    val jwk = key.jwk
    assertEquals(jwk.hcursor.get[String]("crv"), Right("P-256"))
    assertEquals(Encoding.unb64(jwk.hcursor.get[String]("x").toOption.get).get.length, 32)
    assertEquals(Encoding.unb64(jwk.hcursor.get[String]("y").toOption.get).get.length, 32)
  }

  test("malformed did:key values are rejected") {
    List("did:key:z", "did:key:abc", "did:web:example.com", "z6Mk", "",
      "did:key:zQ3shokFTS3brHcDQrn82RUDfCZESWL1ZdCEJwekUDPQiYBm"
    ).foreach(value => assert(PublicKey.fromDidKey(value).isEmpty, value))
  }

  test("scrypt password hashes verify and reject near misses") {
    val hash = Passwords.hash("correct horse battery staple")
    assert(Passwords.matches("correct horse battery staple", hash))
    assert(!Passwords.matches("Correct horse battery staple", hash))
    assert(!Passwords.matches("", hash))
    assert(!Passwords.matches("anything", "not-a-hash"))
    assertNotEquals(Passwords.hash("same"), Passwords.hash("same"))
    assert(!Passwords.matches("wrong", Passwords.dummy))
  }

  test("base encodings reject the forms they never emit") {
    val bytes = Hash.randomBytes(32)
    assertEquals(Encoding.unb64(Encoding.b64(bytes)).map(Encoding.hex), Some(Encoding.hex(bytes)))
    assertEquals(Encoding.unbase32(Encoding.base32(bytes)).map(Encoding.hex), Some(Encoding.hex(bytes)))
    assertEquals(Encoding.unbase58(Encoding.base58(bytes)).map(Encoding.hex), Some(Encoding.hex(bytes)))
    assertEquals(Encoding.unhex(Encoding.hex(bytes)).map(Encoding.hex), Some(Encoding.hex(bytes)))
    assert(Encoding.unb64("abc=").isEmpty)
    assert(Encoding.unb64("a+b/").isEmpty)
    assert(Encoding.unbase32("ABC0189").isEmpty)
    assert(Encoding.unbase58("0OIl").isEmpty)
    assert(Encoding.unhex("xyz").isEmpty)
  }

  test("tokens are 43 base64url characters and digests never reveal them") {
    val token = Hash.token()
    assert(Hash.isToken(token))
    assertEquals(Hash.digestToken(token).length, 43)
    assertNotEquals(Hash.digestToken(token), token)
    assert(!Hash.isToken(token.dropRight(1)))
  }
