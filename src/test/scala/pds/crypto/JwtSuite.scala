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
