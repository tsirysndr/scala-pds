package pds.api

import cats.effect.{IO, Ref}
import pds.Env
import pds.storage.Sql

/** Process counters plus a few live gauges, rendered for Prometheus.
  *
  * Counters are per instance and reset on restart, which is what a scrape
  * expects; gauges are read from the database at scrape time.
  */
final class Metrics(
    requests: Ref[IO, Map[(String, Int), Long]],
    methods: Ref[IO, Map[String, Long]],
    rejected: Ref[IO, Long],
    subscribers: Ref[IO, Long],
    started: Long
):
  def record(kind: String, status: Int): IO[Unit] =
    requests.update(current => current.updated((kind, status), current.getOrElse((kind, status), 0L) + 1))

  def method(name: String): IO[Unit] =
    methods.update(current => current.updated(name, current.getOrElse(name, 0L) + 1))

  def rateLimited: IO[Unit] = rejected.update(_ + 1)

  def subscriberOpened: IO[Unit] = subscribers.update(_ + 1)
  def subscriberClosed: IO[Unit] = subscribers.update(value => math.max(0L, value - 1))

  def render(env: Env): IO[String] =
    for
      byStatus <- requests.get
      byMethod <- methods.get
      limited <- rejected.get
      live <- subscribers.get
      gauges <- env.database.read { connection =>
        Map(
          "accounts" -> Sql.count(connection,
            "SELECT COUNT(*) AS total FROM accounts WHERE status = 'active'"),
          "records" -> Sql.count(connection, "SELECT COUNT(*) AS total FROM records"),
          "blobs" -> Sql.count(connection, "SELECT COUNT(*) AS total FROM blobs"),
          "repo_blocks" -> Sql.count(connection, "SELECT COUNT(*) AS total FROM repo_blocks"),
          "sequence" -> Sql.count(connection,
            "SELECT COALESCE(MAX(seq), 0) AS total FROM repo_events"),
          "oauth_sessions" -> Sql.count(connection,
            "SELECT COUNT(*) AS total FROM oauth_tokens WHERE revoked = false"),
          "email_pending" -> Sql.count(connection,
            "SELECT COUNT(*) AS total FROM email_outbox WHERE status = 'pending'")
        )
      }.handleError(_ => Map.empty)
      now <- IO.realTime.map(_.toMillis)
    yield
      val out = new StringBuilder

      def help(name: String, kind: String, text: String): Unit =
        out.append(s"# HELP pds_$name $text\n# TYPE pds_$name $kind\n")

      help("requests_total", "counter", "HTTP requests by surface and status.")
      byStatus.toVector.sortBy((key, _) => (key._1, key._2)).foreach { case ((kind, status), count) =>
        out.append(s"""pds_requests_total{surface="$kind",status="$status"} $count\n""")
      }

      help("xrpc_method_total", "counter", "XRPC calls by method.")
      byMethod.toVector.sortBy(_._1).foreach { (name, count) =>
        out.append(s"""pds_xrpc_method_total{method="$name"} $count\n""")
      }

      help("rate_limited_total", "counter", "Requests refused by the rate limiter.")
      out.append(s"pds_rate_limited_total $limited\n")

      help("firehose_subscribers", "gauge", "Open subscribeRepos connections.")
      out.append(s"pds_firehose_subscribers $live\n")

      gauges.toVector.sortBy(_._1).foreach { (name, value) =>
        help(name, "gauge", s"Rows in $name.")
        out.append(s"pds_$name $value\n")
      }

      help("uptime_seconds", "gauge", "Seconds since this instance started.")
      out.append(s"pds_uptime_seconds ${(now - started) / 1000}\n")

      out.toString

object Metrics:
  def create: IO[Metrics] =
    for
      requests <- Ref.of[IO, Map[(String, Int), Long]](Map.empty)
      methods <- Ref.of[IO, Map[String, Long]](Map.empty)
      rejected <- Ref.of[IO, Long](0L)
      subscribers <- Ref.of[IO, Long](0L)
      now <- IO.realTime.map(_.toMillis)
    yield new Metrics(requests, methods, rejected, subscribers, now)
