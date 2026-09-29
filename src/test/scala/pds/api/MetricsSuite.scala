package pds.api

import cats.effect.IO
import io.circe.Json
import org.http4s.Status
import pds.TestEnv.*

class MetricsSuite extends munit.CatsEffectSuite:
  private def lines(body: String): Map[String, String] =
    body.linesIterator.filterNot(_.startsWith("#")).filter(_.nonEmpty)
      .map(line => line.reverse.dropWhile(_ != ' ').reverse.trim -> line.split(" ").last)
      .toMap

  test("metrics are administrator-only") {
    harness().use { server =>
      for
        anonymous <- server.json(get("/metrics"))
        authorized <- server.run(admin(get("/metrics")))
      yield
        assertEquals(anonymous._1, Status.Unauthorized)
        assertEquals(authorized.status, Status.Ok)
        assertEquals(authorized.contentType.map(_.mediaType.subType), Some("plain"))
    }
  }

  test("counters follow requests, methods and rate-limit rejections") {
    harness(Map("PDS_RATE_LIMIT_PER_MINUTE" -> "2")).use { server =>
      // Each group uses its own address, so exhausting one client's window
      // leaves the others — including the scrape — unaffected.
      def as(address: String)(request: org.http4s.Request[cats.effect.IO]) =
        server.run(request.putHeaders(
          org.http4s.Header.Raw(org.typelevel.ci.CIString("X-Forwarded-For"), address)))
      for
        _ <- as("198.51.100.7")(get("/xrpc/com.atproto.server.describeServer"))
        _ <- as("198.51.100.7")(get("/xrpc/com.atproto.server.describeServer"))
        refused <- as("198.51.100.7")(get("/xrpc/com.atproto.server.describeServer"))
        _ <- as("198.51.100.8")(get("/xrpc/com.atproto.server.notAMethod"))
        _ <- as("198.51.100.8")(get("/nowhere"))
        scraped <- as("198.51.100.9")(admin(get("/metrics")))
        body <- scraped.as[String]
      yield
        assertEquals(refused.status, Status.TooManyRequests)
        assertEquals(scraped.status, Status.Ok)
        val values = lines(body)
        assertEquals(values.get("""pds_requests_total{surface="xrpc",status="200"}"""),
          Some("2"), values.filter(_._1.startsWith("pds_requests")).toString)
        assertEquals(values.get("""pds_requests_total{surface="xrpc",status="501"}"""), Some("1"))
        assertEquals(values.get("""pds_requests_total{surface="other",status="404"}"""), Some("1"))
        assertEquals(
          values.get("""pds_xrpc_method_total{method="com.atproto.server.describeServer"}"""),
          Some("2"), "the refused request never reached a method")
        assertEquals(values.get("pds_rate_limited_total"), Some("1"))
    }
  }

  test("gauges report live account, record and sequence counts") {
    harness().use { server =>
      for
        created <- server.json(post("/xrpc/com.atproto.server.createAccount", Json.obj(
          "handle" -> Json.fromString("alice.pds.example.com"),
          "email" -> Json.fromString("alice@example.com"),
          "password" -> Json.fromString("correct horse battery"))))
        access = created._2.hcursor.get[String]("accessJwt").toOption.get
        did = created._2.hcursor.get[String]("did").toOption.get
        _ <- server.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "record" -> Json.obj(
            "$type" -> Json.fromString("app.bsky.feed.post"),
            "text" -> Json.fromString("hello"),
            "createdAt" -> Json.fromString("2026-01-01T00:00:00.000Z")))), access))
        scraped <- server.run(admin(get("/metrics")))
        body <- scraped.as[String]
      yield
        val values = lines(body)
        assertEquals(values.get("pds_accounts"), Some("1"))
        assertEquals(values.get("pds_records"), Some("1"))
        assertEquals(values.get("pds_blobs"), Some("0"))
        assertEquals(values.get("pds_sequence"), Some("4"))
        assertEquals(values.get("pds_firehose_subscribers"), Some("0"))
        assert(values.contains("pds_uptime_seconds"))
        assert(body.contains("# TYPE pds_requests_total counter"), body.take(200))
        assert(body.contains("# TYPE pds_accounts gauge"))
    }
  }
