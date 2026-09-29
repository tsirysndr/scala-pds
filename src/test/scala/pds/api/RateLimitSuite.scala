package pds.api

import cats.effect.{IO, Ref}
import io.circe.Json
import org.http4s.circe.*
import org.http4s.{Header, Status}
import org.typelevel.ci.CIString
import pds.TestEnv.*
import pds.storage.RedisConfig

class RateLimitSuite extends munit.CatsEffectSuite:
  test("a fixed window admits the limit and then refuses") {
    for
      limiter <- RateLimit.create(3)
      results <- (1 to 5).toVector.foldLeft(IO.pure(Vector.empty[Boolean])) { (acc, _) =>
        acc.flatMap(list => limiter.check("client", 1000L).map(list :+ _))
      }
      nextWindow <- limiter.check("client", 61_000L)
      otherClient <- limiter.check("other", 1000L)
    yield
      assertEquals(results, Vector(true, true, true, false, false))
      assert(nextWindow, "a new window starts fresh")
      assert(otherClient, "clients are counted separately")
      assert(!limiter.shared)
  }

  /** Two limiters over one counter store, as two instances would be. */
  test("a shared store makes the limit a total across instances") {
    for
      store <- Counters.inProcess
      first = RateLimit(store, 4)
      second = RateLimit(store, 4)
      a <- first.check("client", 1000L)
      b <- second.check("client", 1000L)
      c <- first.check("client", 1000L)
      d <- second.check("client", 1000L)
      e <- first.check("client", 1000L)
    yield assertEquals(Vector(a, b, c, d, e), Vector(true, true, true, true, false))
  }

  /** A counter store that always fails, as an unreachable Redis would. */
  private final class Broken(fallback: Counters) extends Counters:
    def shared: Boolean = true
    def increment(key: String, window: Long, ttl: Long): IO[Long] =
      IO.raiseError(new RuntimeException("connection refused"))
        .handleErrorWith(_ => fallback.increment(key, window, ttl))

  test("an unreachable shared store falls back instead of failing requests") {
    for
      fallback <- Counters.inProcess
      limiter = RateLimit(new Broken(fallback), 2)
      a <- limiter.check("client", 1000L)
      b <- limiter.check("client", 1000L)
      c <- limiter.check("client", 1000L)
    yield assertEquals(Vector(a, b, c), Vector(true, true, false))
  }

  test("the server answers RateLimitExceeded with Retry-After") {
    harness(Map("PDS_RATE_LIMIT_PER_MINUTE" -> "2")).use { server =>
      for
        first <- server.run(get("/xrpc/com.atproto.server.describeServer"))
        second <- server.run(get("/xrpc/com.atproto.server.describeServer"))
        third <- server.run(get("/xrpc/com.atproto.server.describeServer"))
        body <- third.as[Json]
      yield
        assertEquals(first.status, Status.Ok)
        assertEquals(second.status, Status.Ok)
        assertEquals(third.status, Status.TooManyRequests)
        assertEquals(body.hcursor.get[String]("error"), Right("RateLimitExceeded"))
        assertEquals(third.headers.get(CIString("Retry-After")).map(_.head.value), Some("60"))
    }
  }

  test("limits are keyed on the forwarded client address") {
    harness(Map("PDS_RATE_LIMIT_PER_MINUTE" -> "1")).use { server =>
      def request(address: String) =
        server.run(get("/xrpc/com.atproto.server.describeServer")
          .putHeaders(Header.Raw(CIString("X-Forwarded-For"), address)))
      for
        one <- request("198.51.100.7")
        two <- request("198.51.100.7, 10.0.0.1")
        other <- request("203.0.113.9")
      yield
        assertEquals(one.status, Status.Ok)
        assertEquals(two.status, Status.TooManyRequests, "the first hop identifies the client")
        assertEquals(other.status, Status.Ok)
    }
  }

  test("Redis is optional and its URL is validated") {
    assertEquals(RedisConfig.fromEnv(Map.empty), Right(None))
    assert(RedisConfig.fromEnv(Map("PDS_REDIS_URL" -> "http://localhost:6379")).isLeft)
    assert(RedisConfig.fromEnv(Map("PDS_REDIS_URL" -> "not a url")).isLeft)
    assert(RedisConfig.fromEnv(
      Map("PDS_REDIS_URL" -> "redis://localhost:6379", "PDS_REDIS_POOL_SIZE" -> "0")).isLeft)
    val config = RedisConfig.fromEnv(Map("PDS_REDIS_URL" -> "rediss://cache.example.com:6380"))
      .toOption.get.get
    assertEquals(config.uri.getHost, "cache.example.com")
    assertEquals(config.poolSize, 8)
    assert(!config.toString.contains("password"))
  }
