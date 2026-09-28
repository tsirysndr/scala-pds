package pds.api

import cats.effect.{IO, Ref}
import scala.collection.immutable.Queue

/** Fixed-window request counting per client address. */
final class RateLimit(state: Ref[IO, Map[String, (Long, Int)]], limit: Int):
  private val windowMillis = 60_000L

  def check(key: String, now: Long): IO[Boolean] =
    state.modify { current =>
      val window = now / windowMillis
      current.get(key) match
        case Some((stored, count)) if stored == window && count >= limit =>
          (current, false)
        case Some((stored, count)) if stored == window =>
          (current.updated(key, (window, count + 1)), true)
        case _ =>
          val pruned = if current.size > 20000 then
            current.filter((_, value) => value._1 >= window - 1) else current
          (pruned.updated(key, (window, 1)), true)
    }

object RateLimit:
  def create(limit: Int): IO[RateLimit] =
    Ref.of[IO, Map[String, (Long, Int)]](Map.empty).map(new RateLimit(_, limit))
