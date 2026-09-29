package pds.api

import cats.effect.{IO, Ref, Resource}
import pds.storage.RedisConfig
import redis.clients.jedis.{JedisPool, JedisPoolConfig}

/** Fixed-window request counters.
  *
  * In-process counters are per instance, so a deployment behind a load balancer
  * enforces its limit once per instance. Pointing `PDS_REDIS_URL` at a shared
  * Redis makes the window shared instead, so the configured limit is the total.
  * A Redis that is unreachable does not fail requests: the limiter falls back to
  * allowing them rather than taking the server down with it.
  */
trait Counters:
  def increment(key: String, window: Long, ttlSeconds: Long): IO[Long]
  def shared: Boolean

object Counters:
  final class InProcess(state: Ref[IO, Map[(String, Long), Int]]) extends Counters:
    def shared: Boolean = false

    def increment(key: String, window: Long, ttlSeconds: Long): IO[Long] =
      state.modify { current =>
        val pruned = if current.size > 20000 then
          current.filter { case ((_, stored), _) => stored >= window - 1 } else current
        val next = pruned.getOrElse((key, window), 0) + 1
        (pruned.updated((key, window), next), next.toLong)
      }

  final class Shared(pool: JedisPool, fallback: Counters) extends Counters:
    def shared: Boolean = true

    def increment(key: String, window: Long, ttlSeconds: Long): IO[Long] =
      IO.blocking {
        val jedis = pool.getResource
        try
          val field = s"pds:rate:$key:$window"
          val count = jedis.incr(field)
          if count == 1L then jedis.expire(field, ttlSeconds)
          count
        finally jedis.close()
      }.handleErrorWith(_ => fallback.increment(key, window, ttlSeconds))

  def inProcess: IO[Counters] =
    Ref.of[IO, Map[(String, Long), Int]](Map.empty).map(new InProcess(_))

  def resource(config: Option[RedisConfig]): Resource[IO, Counters] =
    config match
      case None => Resource.eval(inProcess)
      case Some(redis) =>
        for
          fallback <- Resource.eval(inProcess)
          pool <- Resource.make(IO.blocking {
            val settings = new JedisPoolConfig()
            settings.setMaxTotal(redis.poolSize)
            settings.setMaxIdle(redis.poolSize)
            new JedisPool(settings, redis.uri)
          })(pool => IO.blocking(pool.close()))
        yield new Shared(pool, fallback)
