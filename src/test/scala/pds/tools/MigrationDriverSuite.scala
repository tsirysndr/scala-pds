package pds.tools

import cats.effect.{IO, Ref, Resource}
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import pds.TestEnv
import pds.TestEnv.*
import pds.protocol.Tid

/** Two independent servers, wired to each other through one routing client, so
  * the driver moves a real account between them the way it would in production.
  */
class MigrationDriverSuite extends munit.CatsEffectSuite:
  private final case class Pair(source: Harness, destination: Harness, client: Client[IO])

  private val pair: Resource[IO, Pair] =
    for
      table <- Resource.eval(Ref.of[IO, Map[String, HttpApp[IO]]](Map.empty))
      client = Client.fromHttpApp(HttpApp[IO] { request =>
        val host = request.uri.host.map(_.value)
          .orElse(request.headers.get(CIString("Host")).map(_.head.value))
          .getOrElse("")
          .takeWhile(_ != ':')
        table.get.flatMap(_.get(host)
          .fold(IO.pure(Response[IO](Status.NotFound)))(_.run(request)))
      })
      source <- TestEnv.harness(Map(
        "PDS_HOSTNAME" -> "old.example.com",
        "PDS_PUBLIC_URL" -> "https://old.example.com"), client)
      destination <- TestEnv.harness(Map(
        "PDS_HOSTNAME" -> "new.example.com",
        "PDS_PUBLIC_URL" -> "https://new.example.com"), client)
      _ <- Resource.eval(table.set(Map(
        "old.example.com" -> source.app,
        "new.example.com" -> destination.app,
        "alice.old.example.com" -> source.app)))
    yield Pair(source, destination, client)

  private val password = "correct horse battery"

  private def prepare(pair: Pair): IO[(String, String)] =
    for
      created <- pair.source.json(post("/xrpc/com.atproto.server.createAccount", Json.obj(
        "handle" -> Json.fromString("alice.old.example.com"),
        "email" -> Json.fromString("alice@example.com"),
        "password" -> Json.fromString(password))))
      access = created._2.hcursor.get[String]("accessJwt").toOption.get
      did = created._2.hcursor.get[String]("did").toOption.get
      _ <- (1 to 3).toVector.foldLeft(IO.unit) { (acc, index) =>
        acc *> pair.source.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
          "repo" -> Json.fromString(did),
          "collection" -> Json.fromString("app.bsky.feed.post"),
          "rkey" -> Json.fromString(Tid.encode(1780000000000000L + index, 0)),
          "record" -> Json.obj(
            "$type" -> Json.fromString("app.bsky.feed.post"),
            "text" -> Json.fromString(s"post $index"),
            "createdAt" -> Json.fromString("2026-01-01T00:00:00.000Z")))), access)).void
      }
      uploaded <- pair.source.json(authorized(
        Request[IO](Method.POST, Uri.unsafeFromString("/xrpc/com.atproto.repo.uploadBlob"))
          .withEntity("an avatar".getBytes("UTF-8"))
          .withContentType(`Content-Type`(MediaType.image.png)), access))
      _ <- pair.source.json(authorized(post("/xrpc/com.atproto.repo.createRecord", Json.obj(
        "repo" -> Json.fromString(did),
        "collection" -> Json.fromString("app.bsky.actor.profile"),
        "rkey" -> Json.fromString("self"),
        "record" -> Json.obj(
          "$type" -> Json.fromString("app.bsky.actor.profile"),
          "avatar" -> uploaded._2.hcursor.downField("blob").focus.get))), access))
      _ <- pair.source.json(authorized(post("/xrpc/app.bsky.actor.putPreferences", Json.obj(
        "preferences" -> Json.arr(Json.obj(
          "$type" -> Json.fromString("app.bsky.actor.defs#adultContentPref"),
          "enabled" -> Json.True)))), access))
    yield (access, did)

  private def plan(handle: String = "alice.new.example.com") = MigrationPlan(
    from = Uri.unsafeFromString("https://old.example.com"),
    to = Uri.unsafeFromString("https://new.example.com"),
    identifier = "alice.old.example.com",
    password = password,
    handle = handle,
    email = "alice@new.example.com",
    newPassword = "a different passphrase"
  )

  test("the driver moves an account between two servers") {
    pair.use { pair =>
      for
        prepared <- prepare(pair)
        (_, did) = prepared
        report <- Migration.run(pair.client, plan(), _ => IO.unit)
        status <- pair.destination.json(post("/xrpc/com.atproto.server.createSession", Json.obj(
          "identifier" -> Json.fromString("alice.new.example.com"),
          "password" -> Json.fromString("a different passphrase"))))
        moved = status._2.hcursor.get[String]("accessJwt").toOption.get
        posts <- pair.destination.json(authorized(get(
          s"/xrpc/com.atproto.repo.listRecords?repo=$did&collection=app.bsky.feed.post"), moved))
        preferences <- pair.destination.json(authorized(
          get("/xrpc/app.bsky.actor.getPreferences"), moved))
        blobs <- pair.destination.json(get(s"/xrpc/com.atproto.sync.listBlobs?did=$did"))
      yield
        assertEquals(report.did, did)
        assertEquals(report.records, 4)
        assertEquals(report.blobs, 1)
        assertEquals(report.preferences, 1)
        // A did:web document belongs to the domain it names, so the last step of
        // this move is not the destination's to take.
        assertEquals(report.identity, "manual")
        assertEquals(report.activated, false)
        assertEquals(posts._2.hcursor.downField("records").values.map(_.size), Some(3))
        assertEquals(preferences._2.hcursor.downField("preferences").values.map(_.size), Some(1))
        assertEquals(blobs._2.hcursor.get[Vector[String]]("cids").map(_.size), Right(1))
    }
  }

  test("the destination refuses an account whose source did not vouch for it") {
    pair.use { pair =>
      for
        _ <- prepare(pair)
        refused <- pair.destination.json(post("/xrpc/com.atproto.server.createAccount", Json.obj(
          "did" -> Json.fromString("did:web:alice.old.example.com"),
          "handle" -> Json.fromString("alice.new.example.com"),
          "email" -> Json.fromString("alice@new.example.com"),
          "password" -> Json.fromString("a different passphrase"))))
      yield
        assertEquals(refused._1, Status.BadRequest)
        assert(clue(refused._2.hcursor.get[String]("message").toOption.get)
          .contains("no service token authorizes it"))
    }
  }

  test("a service token for another method or audience does not adopt the DID") {
    pair.use { pair =>
      def adopt(token: String) =
        pair.destination.json(authorized(post("/xrpc/com.atproto.server.createAccount", Json.obj(
          "did" -> Json.fromString("did:web:alice.old.example.com"),
          "handle" -> Json.fromString("alice.new.example.com"),
          "email" -> Json.fromString("alice@new.example.com"),
          "password" -> Json.fromString("a different passphrase"))), token))
      def mint(access: String, audience: String, method: String) =
        pair.source.json(authorized(get("/xrpc/com.atproto.server.getServiceAuth" +
          s"?aud=$audience&lxm=$method"), access))
          .map(_._2.hcursor.get[String]("token").toOption.get)
      for
        prepared <- prepare(pair)
        (access, _) = prepared
        wrongMethod <- mint(access, "did:web:new.example.com", "com.atproto.repo.importRepo")
        wrongAudience <- mint(access, "did:web:elsewhere.example.com",
          "com.atproto.server.createAccount")
        byMethod <- adopt(wrongMethod)
        byAudience <- adopt(wrongAudience)
      yield
        assertEquals(byMethod._1, Status.BadRequest)
        assertEquals(byAudience._1, Status.BadRequest)
    }
  }

  test("a handle the destination does not serve stops the move before it starts") {
    pair.use { pair =>
      for
        _ <- prepare(pair)
        failed <- Migration.run(pair.client, plan("alice.elsewhere.example.com"), _ => IO.unit)
          .attempt
      yield assert(clue(failed.left.map(_.getMessage)).isLeft)
    }
  }
