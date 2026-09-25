package pds

import com.comcast.ip4s.{Host, Port}
import java.util.Locale

final case class ServerConfig private (host: Host, port: Port, hostname: String):
  val serviceDid: String = s"did:web:$hostname"

object ServerConfig:
  def fromEnv(env: Map[String, String]): Either[String, ServerConfig] =
    val hostname = env.getOrElse("PDS_HOSTNAME", "localhost").toLowerCase(Locale.ROOT)
    for
      host <- Host.fromString(env.getOrElse("PDS_HOST", "127.0.0.1"))
        .toRight("PDS_HOST must be a valid bind address")
      port <- env.getOrElse("PDS_PORT", "3000").toIntOption
        .filter(p => p >= 1 && p <= 65535).flatMap(Port.fromInt)
        .toRight("PDS_PORT must be an integer between 1 and 65535")
      _ <- Either.cond(validHostname(hostname), (),
        "PDS_HOSTNAME must be a DNS hostname without scheme, port, or path")
    yield ServerConfig(host, port, hostname)

  private def validHostname(value: String): Boolean =
    value.nonEmpty && value.length <= 253 && value.split("\\.", -1).forall { label =>
      label.length <= 63 && label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
    } && value.lastOption.exists(c => c >= 'a' && c <= 'z')
