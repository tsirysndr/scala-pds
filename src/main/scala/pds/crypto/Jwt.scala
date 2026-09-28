package pds.crypto

import io.circe.Json
import io.circe.parser.parse

final case class Jwt(header: Json, payload: Json, signature: Array[Byte], signed: String):
  def claim(name: String): Option[String] = payload.hcursor.get[String](name).toOption
  def numeric(name: String): Option[Long] = payload.hcursor.get[Long](name).toOption
  def headerValue(name: String): Option[String] = header.hcursor.get[String](name).toOption
  def algorithm: Option[String] = headerValue("alg")
  def typ: Option[String] = headerValue("typ")

object Jwt:
  private def segment(json: Json): String = Encoding.b64(Encoding.utf8(json.noSpaces))

  def signHs256(key: Array[Byte], payload: Json, typ: String = "JWT"): String =
    val header = Json.obj("alg" -> Json.fromString("HS256"), "typ" -> Json.fromString(typ))
    val body = s"${segment(header)}.${segment(payload)}"
    s"$body.${Encoding.b64(Hash.hmacSha256(key, Encoding.utf8(body)))}"

  def signEs(key: PrivateKey, payload: Json, header: Json): String =
    val body = s"${segment(header)}.${segment(payload)}"
    s"$body.${Encoding.b64(key.sign(Encoding.utf8(body)))}"

  def signService(key: PrivateKey, payload: Json): String =
    signEs(key, payload, Json.obj(
      "alg" -> Json.fromString(key.curve.jwtAlgorithm),
      "typ" -> Json.fromString("JWT")
    ))

  def parse(token: String): Either[String, Jwt] =
    token.split("\\.", -1) match
      case Array(header, payload, signature) =>
        for
          headerBytes <- Encoding.unb64(header).toRight("Malformed token header")
          payloadBytes <- Encoding.unb64(payload).toRight("Malformed token payload")
          signatureBytes <- Encoding.unb64(signature).toRight("Malformed token signature")
          headerJson <- io.circe.parser.parse(Encoding.text(headerBytes)).left.map(_ => "Malformed token header")
          payloadJson <- io.circe.parser.parse(Encoding.text(payloadBytes)).left.map(_ => "Malformed token payload")
          _ <- Either.cond(headerJson.isObject && payloadJson.isObject, (), "Malformed token")
        yield Jwt(headerJson, payloadJson, signatureBytes, s"$header.$payload")
      case _ => Left("Malformed token")

  def verifyHs256(key: Array[Byte], token: String): Either[String, Jwt] =
    for
      jwt <- parse(token)
      _ <- Either.cond(jwt.algorithm.contains("HS256"), (), "Unexpected token algorithm")
      _ <- Either.cond(
        Hash.constantTimeEquals(Hash.hmacSha256(key, Encoding.utf8(jwt.signed)), jwt.signature),
        (), "Token signature does not verify")
    yield jwt

  def verifyEs(key: PublicKey, token: String): Either[String, Jwt] =
    for
      jwt <- parse(token)
      _ <- Either.cond(jwt.algorithm.contains(key.curve.jwtAlgorithm), (),
        "Unexpected token algorithm")
      _ <- Either.cond(key.verify(Encoding.utf8(jwt.signed), jwt.signature), (),
        "Token signature does not verify")
    yield jwt

  /** JWK thumbprint (RFC 7638) over the canonical EC members. */
  def thumbprint(jwk: Json): Either[String, String] =
    for
      crv <- jwk.hcursor.get[String]("crv").toOption.toRight("JWK has no curve")
      x <- jwk.hcursor.get[String]("x").toOption.toRight("JWK has no x coordinate")
      y <- jwk.hcursor.get[String]("y").toOption.toRight("JWK has no y coordinate")
    yield
      val canonical = s"""{"crv":"$crv","kty":"EC","x":"$x","y":"$y"}"""
      Encoding.b64(Hash.sha256(canonical))

  def publicKeyFromJwk(jwk: Json): Either[String, PublicKey] =
    for
      kty <- jwk.hcursor.get[String]("kty").toOption.toRight("JWK has no key type")
      _ <- Either.cond(kty == "EC", (), "Only EC keys are supported")
      name <- jwk.hcursor.get[String]("crv").toOption.toRight("JWK has no curve")
      curve <- Curve.fromJwk(name).toRight("Unsupported JWK curve")
      x <- jwk.hcursor.get[String]("x").toOption.flatMap(Encoding.unb64).toRight("Invalid JWK x")
      y <- jwk.hcursor.get[String]("y").toOption.flatMap(Encoding.unb64).toRight("Invalid JWK y")
      key <- PublicKey.fromCoordinates(curve, x, y).toRight("JWK is not a point on the curve")
    yield key
