package pds.api

import cats.effect.IO

/** Fixed-window request limiting over whichever counter store is configured. */
final class RateLimit(counters: Counters, limit: Int):
  private val windowMillis = 60_000L

  def shared: Boolean = counters.shared

  def check(key: String, now: Long): IO[Boolean] =
    counters.increment(key, now / windowMillis, 120L).map(_ <= limit.toLong)

object RateLimit:
  def create(limit: Int): IO[RateLimit] =
    Counters.inProcess.map(new RateLimit(_, limit))

  def apply(counters: Counters, limit: Int): RateLimit = new RateLimit(counters, limit)
