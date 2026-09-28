package pds.identity

import cats.effect.IO
import io.circe.Json
import pds.XrpcError

/** Client for a did:plc directory. */
final class PlcDirectory(net: Net, base: String):
  def submit(did: String, operation: Plc.Operation): IO[Unit] =
    net.postJson(s"$base/$did", operation.json).flatMap {
      case Right(_) => IO.unit
      case Left(error) =>
        IO.raiseError(XrpcError.named(org.http4s.Status.BadGateway, "PlcDirectoryFailed",
          s"The PLC directory rejected the operation: $error"))
    }

  def document(did: String): IO[Option[Json]] = net.getJson(s"$base/$did")

  def log(did: String): IO[Option[Json]] = net.getJson(s"$base/$did/log")

  def lastOperation(did: String): IO[Option[(Plc.Operation, String)]] =
    net.getJson(s"$base/$did/log/last").map { json =>
      json.flatMap(value => Plc.parse(value).toOption).map(operation => operation -> Plc.cid(operation))
    }
