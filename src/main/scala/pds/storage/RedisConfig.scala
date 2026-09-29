package pds.storage

import java.net.URI

final case class RedisConfig(uri: URI, poolSize: Int):
  override def toString: String =
    s"RedisConfig(${uri.getScheme}://${uri.getHost}:${uri.getPort},poolSize=$poolSize)"

object RedisConfig:
  /** Redis is optional; naming a URL is what turns shared counters on. */
  def fromEnv(env: Map[String, String]): Either[String, Option[RedisConfig]] =
    env.get("PDS_REDIS_URL").filter(_.nonEmpty) match
      case None => Right(None)
      case Some(value) =>
        for
          uri <- parse(value).toRight("PDS_REDIS_URL must be a redis:// or rediss:// URL")
          size <- env.getOrElse("PDS_REDIS_POOL_SIZE", "8").toIntOption
            .filter(pool => pool >= 1 && pool <= 100)
            .toRight("PDS_REDIS_POOL_SIZE must be an integer between 1 and 100")
        yield Some(RedisConfig(uri, size))

  private def parse(value: String): Option[URI] =
    try
      val uri = URI.create(value)
      Option.when(
        (uri.getScheme == "redis" || uri.getScheme == "rediss") &&
          Option(uri.getHost).exists(_.nonEmpty))(uri)
    catch case _: IllegalArgumentException => None
