package pds.identity

import io.circe.Json
import pds.crypto.{Encoding, Hash, PrivateKey, PublicKey}
import pds.protocol.{Cbor, Node}

/** did:plc genesis and update operations. The DID is the truncated base32 of
  * the SHA-256 over the signed genesis operation, so it is bound to the keys
  * and services it was created with.
  */
object Plc:
  final case class Operation(
      rotationKeys: Vector[String],
      verificationMethods: Map[String, String],
      alsoKnownAs: Vector[String],
      services: Map[String, (String, String)],
      prev: Option[String],
      signature: Option[String]
  ):
    def node: Node =
      val base = Map(
        "type" -> Node.Text("plc_operation"),
        "rotationKeys" -> Node.Arr(rotationKeys.map(Node.Text.apply)),
        "verificationMethods" -> Node.Obj(verificationMethods.view.mapValues(Node.Text.apply).toMap),
        "alsoKnownAs" -> Node.Arr(alsoKnownAs.map(Node.Text.apply)),
        "services" -> Node.Obj(services.view.mapValues { (kind, endpoint) =>
          Node.Obj(Map("type" -> Node.Text(kind), "endpoint" -> Node.Text(endpoint)))
        }.toMap),
        "prev" -> prev.map(Node.Text.apply).getOrElse(Node.Null)
      )
      Node.Obj(signature.fold(base)(value => base.updated("sig", Node.Text(value))))

    def unsignedBytes: Array[Byte] = Cbor.encode(copy(signature = None).node)
    def bytes: Array[Byte] = Cbor.encode(node)
    def json: Json = Node.toJson(node)

    def sign(key: PrivateKey): Operation =
      copy(signature = Some(Encoding.b64(key.sign(unsignedBytes))))

    def verify(key: PublicKey): Boolean =
      signature.flatMap(Encoding.unb64).exists(key.verify(unsignedBytes, _))

  def genesis(
      handle: String,
      signingKey: PublicKey,
      rotationKeys: Vector[PublicKey],
      pdsEndpoint: String
  ): Operation =
    Operation(
      rotationKeys = rotationKeys.map(_.didKey),
      verificationMethods = Map("atproto" -> signingKey.didKey),
      alsoKnownAs = Vector(s"at://$handle"),
      services = Map("atproto_pds" -> ("AtprotoPersonalDataServer", pdsEndpoint)),
      prev = None,
      signature = None
    )

  /** The DID is derived from the *signed* genesis operation. */
  def did(signed: Operation): String =
    "did:plc:" + Encoding.base32(Hash.sha256(signed.bytes)).take(24)

  def update(previous: Operation, previousCid: String)(change: Operation => Operation): Operation =
    change(previous.copy(prev = Some(previousCid), signature = None))

  def cid(signed: Operation): String = pds.protocol.Cid.ofCbor(signed.bytes).toString

  def parse(json: Json): Either[String, Operation] =
    for
      node <- Node.fromJson(json)
      _ <- Either.cond(node("type").flatMap(_.asString).contains("plc_operation"), (),
        "Unsupported PLC operation type")
      rotation <- node("rotationKeys").flatMap(_.asVector)
        .map(_.flatMap(_.asString)).toRight("PLC operation has no rotation keys")
      _ <- Either.cond(rotation.nonEmpty && rotation.length <= 5, (),
        "A PLC operation needs between one and five rotation keys")
      methods = node("verificationMethods").map(_.entries).getOrElse(Map.empty)
        .flatMap((name, value) => value.asString.map(name -> _))
      aliases = node("alsoKnownAs").flatMap(_.asVector).map(_.flatMap(_.asString)).getOrElse(Vector.empty)
      services = node("services").map(_.entries).getOrElse(Map.empty).flatMap { (name, value) =>
        for
          kind <- value("type").flatMap(_.asString)
          endpoint <- value("endpoint").flatMap(_.asString)
        yield name -> (kind, endpoint)
      }
      signature = node("sig").flatMap(_.asString)
      prev = node("prev").flatMap(_.asString)
    yield Operation(rotation, methods, aliases, services, prev, signature)

  /** The DID document a directory derives from an operation log head. */
  def document(did: String, operation: Operation): Json =
    Json.obj(
      "@context" -> Json.arr(DidDocument.contexts.map(Json.fromString)*),
      "id" -> Json.fromString(did),
      "alsoKnownAs" -> Json.arr(operation.alsoKnownAs.map(Json.fromString)*),
      "verificationMethod" -> Json.arr(
        operation.verificationMethods.toVector.sortBy(_._1).flatMap { (name, didKey) =>
          PublicKey.fromDidKey(didKey).map { key =>
            Json.obj(
              "id" -> Json.fromString(s"$did#$name"),
              "type" -> Json.fromString("Multikey"),
              "controller" -> Json.fromString(did),
              "publicKeyMultibase" -> Json.fromString(key.multibase)
            )
          }
        }*
      ),
      "service" -> Json.arr(
        operation.services.toVector.sortBy(_._1).map { (name, service) =>
          Json.obj(
            "id" -> Json.fromString(s"#$name"),
            "type" -> Json.fromString(service._1),
            "serviceEndpoint" -> Json.fromString(service._2)
          )
        }*
      )
    )
