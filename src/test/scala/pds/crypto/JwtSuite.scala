package pds.crypto

import io.circe.Json

class JwtSuite extends munit.FunSuite:
  private val payload = Json.obj(
    "sub" -> Json.fromString("did:plc:abc"),
    "exp" -> Json.fromLong(1893456000L)
  )

  test("HS256 tokens verify under their key only") {
    val key = Hash.randomBytes(32)
    val token = Jwt.signHs256(key, payload, "at+jwt")
    val jwt = Jwt.verifyHs256(key, token).fold(fail(_), identity)
    assertEquals(jwt.claim("sub"), Some("did:plc:abc"))
    assertEquals(jwt.numeric("exp"), Some(1893456000L))
    assertEquals(jwt.typ, Some("at+jwt"))
    assert(Jwt.verifyHs256(Hash.randomBytes(32), token).isLeft)
    assert(Jwt.verifyHs256(key, token.dropRight(2) + "xy").isLeft)
  }

  test("ES tokens verify against the signing key on both curves") {
    Curve.values.foreach { curve =>
      val key = PrivateKey.generate(curve)
      val token = Jwt.signService(key, payload)
      assertEquals(Jwt.verifyEs(key.publicKey, token).map(_.claim("sub")), Right(Some("did:plc:abc")))
      assert(Jwt.verifyEs(PrivateKey.generate(curve).publicKey, token).isLeft)
      assertEquals(Jwt.parse(token).toOption.get.algorithm, Some(curve.jwtAlgorithm))
    }
  }

  test("algorithm confusion is rejected") {
    val key = PrivateKey.generate(Curve.P256)
    val token = Jwt.signService(key, payload)
    assert(Jwt.verifyHs256(key.bytes, token).isLeft)
    val hs = Jwt.signHs256(Hash.randomBytes(32), payload)
    assert(Jwt.verifyEs(key.publicKey, hs).isLeft)
  }

  /** The high-S counterpart of a signature: (r, n - s) verifies the same
    * message under the same key, and is what a conforming JOSE signer such as
    * WebCrypto emits about half the time.
    */
  private def raiseS(curve: Curve, signature: Array[Byte]): Array[Byte] =
    val width = signature.length / 2
    val order = curve match
      case Curve.P256 => BigInt(
        "115792089210356248762697446949407573529996955224135760342422259061068512044369")
      case Curve.K256 => BigInt(
        "115792089237316195423570985008687907852837564279074904382605163141518161494337")
    val s = BigInt(1, signature.drop(width))
    signature.take(width) ++ Encoding.unsigned(order - s, width)

  test("JOSE signatures verify whether or not they are low-S") {
    Curve.values.foreach { curve =>
      val key = PrivateKey.generate(curve)
      val token = Jwt.signService(key, payload)
      val parts = token.split("\\.")
      val signature = Encoding.unb64(parts(2)).get
      val high = raiseS(curve, signature)
      assertNotEquals(Encoding.hex(high), Encoding.hex(signature))

      val raised = s"${parts(0)}.${parts(1)}.${Encoding.b64(high)}"
      // RFC 7515 does not require low-S, so a JOSE verifier must accept it.
      assertEquals(Jwt.verifyEs(key.publicKey, raised).map(_.claim("sub")),
        Right(Some("did:plc:abc")), curve.name)

      // AT Protocol data signatures still must be low-S.
      val signed = Encoding.utf8(s"${parts(0)}.${parts(1)}")
      assert(key.publicKey.verify(signed, signature), curve.name)
      assert(!key.publicKey.verify(signed, high), s"${curve.name} accepted a high-S signature")
      assert(key.publicKey.verifyJose(signed, high), curve.name)
    }
  }

  test("a signature that is not a point on the curve is refused either way") {
    val key = PrivateKey.generate(Curve.P256)
    val message = Encoding.utf8("payload")
    val signature = key.sign(message)
    val broken = signature.updated(0, (signature(0) ^ 0xff).toByte)
    assert(!key.publicKey.verify(message, broken))
    assert(!key.publicKey.verifyJose(message, broken))
    assert(!key.publicKey.verifyJose(message, new Array[Byte](64)))
    assert(!key.publicKey.verifyJose(message, Array.emptyByteArray))
  }

  test("malformed tokens are refused") {
    List("", "a", "a.b", "a.b.c.d", "!!!.b.c", "eyJ9.eyJ9.AA").foreach(value =>
      assert(Jwt.parse(value).isLeft, value))
  }

  test("JWK round-trips the public key and its thumbprint is stable") {
    val key = PrivateKey.generate(Curve.P256).publicKey
    assertEquals(Jwt.publicKeyFromJwk(key.jwk), Right(key))
    val thumbprint = Jwt.thumbprint(key.jwk).fold(fail(_), identity)
    assertEquals(thumbprint.length, 43)
    assertEquals(Jwt.thumbprint(key.jwk), Right(thumbprint))
    assert(Jwt.publicKeyFromJwk(Json.obj("kty" -> Json.fromString("RSA"))).isLeft)
  }

  test("the RFC 7638 example thumbprint is reproduced for an EC key") {
    val jwk = Json.obj(
      "kty" -> Json.fromString("EC"),
      "crv" -> Json.fromString("P-256"),
      "x" -> Json.fromString("MKBCTNIcKUSDii11ySs3526iDZ8AiTo7Tu6KPAqv7D4"),
      "y" -> Json.fromString("4Etl6SRW2YiLUrN5vfvVHuhp7x8PxltmWWlbbM4IFyM")
    )
    assertEquals(Jwt.thumbprint(jwk), Right("cn-I_WNMClehiVp51i_0VpOENW1upEerA8sEam5hn-s"))
  }
