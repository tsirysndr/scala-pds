package pds.identity

import io.circe.Json
import pds.crypto.PublicKey
import pds.protocol.Syntax

final case class DidDocument(
    id: String,
    alsoKnownAs: Vector[String],
    signingKey: Option[PublicKey],
    pdsEndpoint: Option[String],
    raw: Json
):
  /** The first `at://` alias, which is the account's canonical handle. */
  def handle: Option[String] =
    alsoKnownAs.collectFirst {
      case alias if alias.startsWith("at://") && Syntax.isHandle(alias.drop(5)) => alias.drop(5)
    }

object DidDocument:
  val contexts = Vector(
    "https://www.w3.org/ns/did/v1",
    "https://w3id.org/security/multikey/v1",
    "https://w3id.org/security/suites/secp256k1-2019/v1"
  )

  def parse(json: Json): Either[String, DidDocument] =
    val cursor = json.hcursor
    for
      id <- cursor.get[String]("id").toOption.filter(Syntax.isDid).toRight("DID document has no id")
      aliases = cursor.get[Vector[String]]("alsoKnownAs").getOrElse(Vector.empty)
      methods = cursor.downField("verificationMethod").values.getOrElse(Vector.empty).toVector
      signing = methods.find { method =>
        method.hcursor.get[String]("id").toOption.exists(value =>
          value == "#atproto" || value == s"$id#atproto")
      }.flatMap(method => method.hcursor.get[String]("publicKeyMultibase").toOption)
        .flatMap(PublicKey.fromMultibase)
      services = cursor.downField("service").values.getOrElse(Vector.empty).toVector
      endpoint = services.find { service =>
        service.hcursor.get[String]("id").toOption.exists(value =>
          value == "#atproto_pds" || value == s"$id#atproto_pds") &&
          service.hcursor.get[String]("type").toOption.contains("AtprotoPersonalDataServer")
      }.flatMap(service => service.hcursor.get[String]("serviceEndpoint").toOption)
    yield DidDocument(id, aliases, signing, endpoint, json)

  def build(did: String, handle: String, signingKey: PublicKey, pdsEndpoint: String): Json =
    Json.obj(
      "@context" -> Json.arr(contexts.map(Json.fromString)*),
      "id" -> Json.fromString(did),
      "alsoKnownAs" -> Json.arr(Json.fromString(s"at://$handle")),
      "verificationMethod" -> Json.arr(Json.obj(
        "id" -> Json.fromString(s"$did#atproto"),
        "type" -> Json.fromString("Multikey"),
        "controller" -> Json.fromString(did),
        "publicKeyMultibase" -> Json.fromString(signingKey.multibase)
      )),
      "service" -> Json.arr(Json.obj(
        "id" -> Json.fromString("#atproto_pds"),
        "type" -> Json.fromString("AtprotoPersonalDataServer"),
        "serviceEndpoint" -> Json.fromString(pdsEndpoint)
      ))
    )

  /** The service document for the PDS itself, served at /.well-known/did.json. */
  def service(did: String, endpoint: String): Json =
    Json.obj(
      "@context" -> Json.arr(Json.fromString("https://www.w3.org/ns/did/v1")),
      "id" -> Json.fromString(did),
      "service" -> Json.arr(Json.obj(
        "id" -> Json.fromString("#atproto_pds"),
        "type" -> Json.fromString("AtprotoPersonalDataServer"),
        "serviceEndpoint" -> Json.fromString(endpoint)
      ))
    )
