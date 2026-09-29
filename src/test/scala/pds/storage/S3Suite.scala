package pds.storage

import cats.effect.{IO, Ref}
import io.circe.Json
import java.time.{ZoneOffset, ZonedDateTime}
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import pds.TestEnv
import pds.TestEnv.*
import pds.crypto.Encoding
import pds.protocol.Cid

class S3Suite extends munit.CatsEffectSuite:
  private val config = S3Config(
    bucket = "pds-blobs",
    region = "us-east-1",
    endpoint = "https://objects.example.com",
    accessKeyId = "AKIAIOSFODNN7EXAMPLE",
    secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    pathStyle = true,
    prefix = "blobs/"
  )

  /** An in-memory bucket that records the signed requests it received. */
  private final class Bucket(
      objects: Ref[IO, Map[String, (Array[Byte], String)]],
      seen: Ref[IO, Vector[(String, String, Map[String, String])]]
  ):
    val client: Client[IO] = Client.fromHttpApp(HttpApp[IO] { request =>
      val key = request.uri.path.renderString.stripPrefix("/pds-blobs/")
      val headers = request.headers.headers
        .map(header => header.name.toString.toLowerCase -> header.value).toMap
      seen.update(_ :+ (request.method.name, key, headers)) *> (request.method match
        case Method.PUT =>
          request.body.compile.to(Array).flatMap { bytes =>
            objects.update(_.updated(key, bytes ->
              request.contentType.map(_.mediaType.toString).getOrElse("application/octet-stream")))
              .as(Response[IO](Status.Ok))
          }
        case Method.GET =>
          objects.get.map(_.get(key) match
            case Some((bytes, mime)) => Response[IO](Status.Ok).withEntity(bytes)
            case None => Response[IO](Status.NotFound).withEntity("<Error>NoSuchKey</Error>"))
        case Method.DELETE =>
          objects.update(_.removed(key)).as(Response[IO](Status.NoContent))
        case _ => IO.pure(Response[IO](Status.MethodNotAllowed)))
    })

  private def bucket: IO[(Bucket, Ref[IO, Map[String, (Array[Byte], String)]],
      Ref[IO, Vector[(String, String, Map[String, String])]])] =
    for
      objects <- Ref.of[IO, Map[String, (Array[Byte], String)]](Map.empty)
      seen <- Ref.of[IO, Vector[(String, String, Map[String, String])]](Vector.empty)
    yield (new Bucket(objects, seen), objects, seen)

  test("requests carry a Signature Version 4 authorization over their payload") {
    val moment = ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC)
    val uri = Uri.unsafeFromString("https://objects.example.com/pds-blobs/blobs/did_web_x/bafy")
    val headers = S3.sign(config, "PUT", uri, Encoding.utf8("payload"), Some("image/png"), moment)
      .toMap

    assertEquals(headers("x-amz-date"), "20260102T030405Z")
    assertEquals(headers("x-amz-content-sha256"),
      Encoding.hex(pds.crypto.Hash.sha256(Encoding.utf8("payload"))))
    val authorization = headers("Authorization")
    assert(authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20260102/us-east-1/s3/aws4_request"),
      authorization)
    assert(authorization.contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date"),
      authorization)

    // The signature covers the payload: different bytes, different signature.
    val other = S3.sign(config, "PUT", uri, Encoding.utf8("other"), Some("image/png"), moment).toMap
    assertNotEquals(other("Authorization"), authorization)
    // ...and the method.
    val getSignature = S3.sign(config, "GET", uri, Array.emptyByteArray, None, moment).toMap
    assertNotEquals(getSignature("Authorization"), authorization)
  }

  test("object keys are path-encoded per segment") {
    assertEquals(S3.encodePath("blobs/did_plc_abc/bafyrei"), "blobs/did_plc_abc/bafyrei")
    assertEquals(S3.encodePath("a b/c+d"), "a%20b/c%2Bd")
    assertEquals(config.objectUrl("blobs/x/y"), "https://objects.example.com/pds-blobs/blobs/x/y")
    assertEquals(config.copy(pathStyle = false).objectUrl("blobs/x/y"),
      "https://pds-blobs.objects.example.com/blobs/x/y")
  }

  test("the client round-trips an object and reports failures") {
    bucket.flatMap { (stub, objects, _) =>
      val s3 = new S3(config, stub.client)
      for
        put <- s3.put("blobs/one", "image/png", Encoding.utf8("bytes"))
        got <- s3.get("blobs/one")
        missing <- s3.get("blobs/absent")
        deleted <- s3.delete("blobs/one")
        afterDelete <- s3.get("blobs/one")
        remaining <- objects.get
      yield
        assertEquals(put, Right(()))
        assertEquals(got.map(Encoding.text), Right("bytes"))
        assert(missing.isLeft)
        assert(missing.left.exists(_.contains("404")), missing.toString)
        assertEquals(deleted, Right(()))
        assert(afterDelete.isLeft)
        assertEquals(remaining, Map.empty)
    }
  }

  test("configuration requires the whole set once a bucket is named") {
    assertEquals(S3Config.fromEnv(Map.empty), Right(None))
    assert(S3Config.fromEnv(Map("PDS_S3_BUCKET" -> "b")).isLeft)
    assert(S3Config.fromEnv(Map("PDS_S3_BUCKET" -> "b", "PDS_S3_REGION" -> "r")).isLeft)
    val complete = Map(
      "PDS_S3_BUCKET" -> "b", "PDS_S3_REGION" -> "r",
      "PDS_S3_ACCESS_KEY_ID" -> "an-access-key",
      "PDS_S3_SECRET_ACCESS_KEY" -> "a-very-secret-value")
    val config = S3Config.fromEnv(complete).toOption.get.get
    assertEquals(config.endpoint, "https://s3.r.amazonaws.com")
    assert(!config.toString.contains("a-very-secret-value"), config.toString)
    assert(!config.toString.contains("an-access-key"), config.toString)
    assertEquals(
      S3Config.fromEnv(complete.updated("PDS_S3_ENDPOINT", "https://minio.example.com/"))
        .toOption.get.get.endpoint, "https://minio.example.com")
  }

  test("blobs go to the bucket, come back, and are queued for deletion") {
    bucket.flatMap { (stub, objects, seen) =>
      val s3 = new S3(config, stub.client)
      TestEnv.harness(blobs = Some(s3)).use { server =>
        val bytes = "an image".getBytes("UTF-8")
        val cid = Cid.ofRaw(bytes)
        for
          created <- server.json(post("/xrpc/com.atproto.server.createAccount", Json.obj(
            "handle" -> Json.fromString("alice.pds.example.com"),
            "email" -> Json.fromString("alice@example.com"),
            "password" -> Json.fromString("correct horse battery"))))
          access = created._2.hcursor.get[String]("accessJwt").toOption.get
          did = created._2.hcursor.get[String]("did").toOption.get
          uploaded <- server.json(authorized(
            Request[IO](Method.POST, Uri.unsafeFromString("/xrpc/com.atproto.repo.uploadBlob"))
              .withEntity(bytes)
              .withContentType(`Content-Type`(MediaType.image.png)), access))
          stored <- objects.get
          rows <- server.env.database.read(connection =>
            Sql.first(connection,
              "SELECT storage_backend, object_bucket, object_key, content FROM blobs WHERE did = ?",
              did)(row => (row.string("storage_backend"), row.stringOpt("object_bucket"),
              row.stringOpt("object_key"), row.bytesOpt("content"))))
          downloaded <- server.run(get(s"/xrpc/com.atproto.sync.getBlob?did=$did&cid=$cid"))
          body <- downloaded.body.compile.to(Array)
          _ <- server.json(admin(post("/xrpc/com.atproto.admin.deleteAccount",
            Json.obj("did" -> Json.fromString(did)))))
          queued <- server.env.database.read(connection =>
            Sql.query(connection, "SELECT object_key FROM blob_deletions")(_.string("object_key")))
          swept <- pds.repo.BlobStore.sweep(server.env)
          left <- objects.get
          drained <- server.env.database.read(connection =>
            Sql.count(connection, "SELECT COUNT(*) AS total FROM blob_deletions"))
        yield
          assertEquals(uploaded._1, Status.Ok)
          assertEquals(stored.keySet, Set(s"blobs/${did.replace(':', '_')}/$cid"))
          assertEquals(rows.map(_._1), Some("s3"))
          assertEquals(rows.flatMap(_._2), Some("pds-blobs"))
          assertEquals(rows.flatMap(_._4), None, "the bytes must not also be in the database")
          assertEquals(downloaded.status, Status.Ok)
          assertEquals(new String(body, "UTF-8"), "an image")
          assertEquals(queued.length, 1)
          assertEquals(swept, 1)
          assertEquals(left, Map.empty)
          assertEquals(drained, 0L)
      }
    }
  }

  test("an upload that the bucket refuses does not create a row") {
    val refusing = Client.fromHttpApp(HttpApp[IO](_ =>
      IO.pure(Response[IO](Status.Forbidden).withEntity("<Error>AccessDenied</Error>"))))
    TestEnv.harness(blobs = Some(new S3(config, refusing))).use { server =>
      for
        created <- server.json(post("/xrpc/com.atproto.server.createAccount", Json.obj(
          "handle" -> Json.fromString("alice.pds.example.com"),
          "email" -> Json.fromString("alice@example.com"),
          "password" -> Json.fromString("correct horse battery"))))
        access = created._2.hcursor.get[String]("accessJwt").toOption.get
        did = created._2.hcursor.get[String]("did").toOption.get
        refused <- server.json(authorized(
          Request[IO](Method.POST, Uri.unsafeFromString("/xrpc/com.atproto.repo.uploadBlob"))
            .withEntity("bytes".getBytes("UTF-8"))
            .withContentType(`Content-Type`(MediaType.image.png)), access))
        rows <- server.env.database.read(connection =>
          Sql.count(connection, "SELECT COUNT(*) AS total FROM blobs WHERE did = ?", did))
      yield
        assertEquals(refused._1, Status.BadGateway)
        assertEquals(refused._2.hcursor.get[String]("error"), Right("BlobStoreFailed"))
        assertEquals(rows, 0L)
    }
  }

  test("without a bucket the bytes stay in the database") {
    TestEnv.harness().use { server =>
      val bytes = "an image".getBytes("UTF-8")
      for
        created <- server.json(post("/xrpc/com.atproto.server.createAccount", Json.obj(
          "handle" -> Json.fromString("alice.pds.example.com"),
          "email" -> Json.fromString("alice@example.com"),
          "password" -> Json.fromString("correct horse battery"))))
        access = created._2.hcursor.get[String]("accessJwt").toOption.get
        did = created._2.hcursor.get[String]("did").toOption.get
        _ <- server.json(authorized(
          Request[IO](Method.POST, Uri.unsafeFromString("/xrpc/com.atproto.repo.uploadBlob"))
            .withEntity(bytes)
            .withContentType(`Content-Type`(MediaType.image.png)), access))
        row <- server.env.database.read(connection =>
          Sql.first(connection,
            "SELECT storage_backend, content FROM blobs WHERE did = ?", did)(row =>
            (row.string("storage_backend"), row.bytesOpt("content"))))
        swept <- pds.repo.BlobStore.sweep(server.env)
      yield
        assertEquals(row.map(_._1), Some("database"))
        assertEquals(row.flatMap(_._2).map(_.toVector), Some(bytes.toVector))
        assertEquals(swept, 0)
    }
  }

  test("the key prefix partitions a bucket shared with another service") {
    def prefixOf(env: Map[String, String]) =
      S3Config.fromEnv(env ++ Map(
        "PDS_S3_BUCKET" -> "shared",
        "PDS_S3_REGION" -> "auto",
        "PDS_S3_ENDPOINT" -> "https://objects.example.com",
        "PDS_S3_ACCESS_KEY_ID" -> "key",
        "PDS_S3_SECRET_ACCESS_KEY" -> "secret")).toOption.flatten.get.prefix

    assertEquals(prefixOf(Map.empty), "blobs/")
    // However it is written, it ends up as one trailing slash and no leading one.
    assertEquals(prefixOf(Map("PDS_S3_PREFIX" -> "scala-pds/blobs")), "scala-pds/blobs/")
    assertEquals(prefixOf(Map("PDS_S3_PREFIX" -> "/scala-pds/blobs/")), "scala-pds/blobs/")
    assertEquals(prefixOf(Map("PDS_S3_PREFIX" -> "  ")), "blobs/")

    // Keys land under it, and stay distinct per account.
    val cid = pds.protocol.Cid.ofRaw("an avatar".getBytes("UTF-8"))
    assertEquals(
      pds.repo.BlobStore.objectKey("scala-pds/blobs/", "did:plc:abc", cid),
      s"scala-pds/blobs/did_plc_abc/$cid")
    assertEquals(
      pds.repo.BlobStore.objectKey("blobs/", "did:web:alice.example.com", cid),
      s"blobs/did_web_alice.example.com/$cid")
  }
