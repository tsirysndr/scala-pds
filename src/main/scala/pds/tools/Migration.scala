package pds.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

final case class MigrationPlan(
    from: Uri,
    to: Uri,
    identifier: String,
    password: String,
    handle: String,
    email: String,
    newPassword: String,
    inviteCode: Option[String] = None,
    plcToken: Option[String] = None
)

final case class MigrationReport(
    did: String,
    bytes: Long,
    records: Int,
    blobs: Int,
    preferences: Int,
    identity: String,
    activated: Boolean
)

/** Runs a whole account migration: prepares the destination account, moves the
  * repository, blobs and preferences, switches the identity and hands over.
  *
  * Every step is an ordinary XRPC call, so this drives two independent servers
  * the way any client would; nothing here reaches into a database.
  */
object Migration:
  private val maxRepository = 256L * 1024 * 1024

  def run(
      client: org.http4s.client.Client[IO], plan: MigrationPlan, log: String => IO[Unit]
  ): IO[MigrationReport] =
    for
      source <- procedure(client, plan.from, "com.atproto.server.createSession", Json.obj(
        "identifier" -> Json.fromString(plan.identifier),
        "password" -> Json.fromString(plan.password)
      ), None)
      did <- required(source, "did")
      sourceToken <- required(source, "accessJwt")
      _ <- log(s"source session for $did")

      description <- query(client, plan.to, "com.atproto.server.describeServer", None)
      audience <- required(description, "did")
      vouch <- query(client, plan.from, "com.atproto.server.getServiceAuth", Some(sourceToken),
        "aud" -> audience, "lxm" -> "com.atproto.server.createAccount")
      vouchToken <- required(vouch, "token")

      created <- procedure(client, plan.to, "com.atproto.server.createAccount", Json.obj(
        "did" -> Json.fromString(did),
        "handle" -> Json.fromString(plan.handle),
        "email" -> Json.fromString(plan.email),
        "password" -> Json.fromString(plan.newPassword),
        "inviteCode" -> plan.inviteCode.map(Json.fromString).getOrElse(Json.Null)
      ).deepDropNullValues, Some(vouchToken))
      token <- required(created, "accessJwt")
      _ <- log(s"created ${plan.handle} on $audience")

      car <- download(client, plan.from, "com.atproto.sync.getRepo", None, "did" -> did)
      _ <- IO.raiseUnless(car._1.length <= maxRepository)(
        error(s"the repository is ${car._1.length} bytes, over the ${maxRepository} limit"))
      _ <- upload(client, plan.to, "com.atproto.repo.importRepo", car._1,
        "application/vnd.ipld.car", Some(token))
      _ <- log(s"imported ${car._1.length} bytes of repository")

      blobs <- moveBlobs(client, plan, did, sourceToken, token, log)
      preferences <- movePreferences(client, plan, sourceToken, token)
      _ <- log(s"moved $blobs blob(s) and $preferences preference group(s)")

      status <- query(client, plan.to, "com.atproto.server.checkAccountStatus", Some(token))
      records = status.hcursor.get[Int]("indexedRecords").getOrElse(0)
      expected = status.hcursor.get[Int]("expectedBlobs").getOrElse(0)
      imported = status.hcursor.get[Int]("importedBlobs").getOrElse(0)
      _ <- IO.raiseUnless(expected == imported)(
        error(s"the destination is still missing ${expected - imported} blob(s)"))
      _ <- log(s"destination holds $records record(s) and $imported blob(s)")

      identity <- switchIdentity(client, plan, did, sourceToken, token, log)
      activated <-
        if identity != "published" then IO.pure(false)
        else
          procedure(client, plan.to, "com.atproto.server.activateAccount", Json.obj(), Some(token)) *>
            procedure(client, plan.from, "com.atproto.server.deactivateAccount", Json.obj(),
              Some(sourceToken)) *>
            log("activated on the destination and deactivated on the source").as(true)
    yield MigrationReport(did, car._1.length.toLong, records, blobs, preferences, identity, activated)

  /** The destination names what it still lacks, so the list is re-read until it
    * is empty rather than paged over a set that shrinks while it is walked.
    */
  private def moveBlobs(
      client: org.http4s.client.Client[IO],
      plan: MigrationPlan,
      did: String,
      sourceToken: String,
      token: String,
      log: String => IO[Unit]
  ): IO[Int] =
    def loop(moved: Int): IO[Int] =
      query(client, plan.to, "com.atproto.repo.listMissingBlobs", Some(token), "limit" -> "100")
        .flatMap { page =>
          val cids = page.hcursor.downField("blobs").values.getOrElse(Vector.empty).toVector
            .flatMap(_.hcursor.get[String]("cid").toOption)
          if cids.isEmpty then IO.pure(moved)
          else
            cids.traverse_ { cid =>
              download(client, plan.from, "com.atproto.sync.getBlob", Some(sourceToken),
                "did" -> did, "cid" -> cid).flatMap { (bytes, contentType) =>
                upload(client, plan.to, "com.atproto.repo.uploadBlob", bytes, contentType,
                  Some(token))
              }
            } *> log(s"transferred ${cids.size} blob(s)") *> loop(moved + cids.size)
        }
    loop(0)

  private def movePreferences(
      client: org.http4s.client.Client[IO], plan: MigrationPlan, sourceToken: String, token: String
  ): IO[Int] =
    query(client, plan.from, "app.bsky.actor.getPreferences", Some(sourceToken)).flatMap { stored =>
      val values = stored.hcursor.downField("preferences").values.getOrElse(Vector.empty).toVector
      if values.isEmpty then IO.pure(0)
      else
        procedure(client, plan.to, "app.bsky.actor.putPreferences",
          Json.obj("preferences" -> Json.arr(values*)), Some(token)).as(values.size)
    }

  /** `published`, `pending` while the emailed code is outstanding, or `manual`
    * for a `did:web` account whose document is not this server's to publish.
    */
  private def switchIdentity(
      client: org.http4s.client.Client[IO],
      plan: MigrationPlan,
      did: String,
      sourceToken: String,
      token: String,
      log: String => IO[Unit]
  ): IO[String] =
    if !did.startsWith("did:plc:") then
      log(s"publish the new key and endpoint for $did at its own domain to finish the move")
        .as("manual")
    else
      query(client, plan.to, "com.atproto.identity.getRecommendedDidCredentials", Some(token))
        .flatMap { credentials =>
          plan.plcToken match
            case None =>
              procedure(client, plan.from, "com.atproto.identity.requestPlcOperationSignature",
                Json.obj(), Some(sourceToken)) *>
                log("the source server emailed a confirmation code; re-run with it to finish")
                  .as("pending")
            case Some(code) =>
              for
                signed <- procedure(client, plan.from, "com.atproto.identity.signPlcOperation",
                  credentials.deepMerge(Json.obj("token" -> Json.fromString(code))),
                  Some(sourceToken))
                operation <- IO.fromOption(signed.hcursor.downField("operation").focus)(
                  error("the source server signed no operation"))
                _ <- procedure(client, plan.to, "com.atproto.identity.submitPlcOperation",
                  Json.obj("operation" -> operation), Some(token))
                _ <- log(s"published the directory operation for $did")
              yield "published"
        }

  private def error(message: String): Throwable = new RuntimeException(message)

  private def required(body: Json, field: String): IO[String] =
    IO.fromOption(body.hcursor.get[String](field).toOption)(
      error(s"the response carried no $field"))

  private def target(base: Uri, method: String, params: Seq[(String, String)]): Uri =
    params.foldLeft(base / "xrpc" / method)((uri, param) => uri.withQueryParam(param._1, param._2))

  private def bearer(token: Option[String]): Headers =
    token.fold(Headers.empty)(value =>
      Headers(Header.Raw(CIString("Authorization"), s"Bearer $value")))

  private def json(client: org.http4s.client.Client[IO], request: Request[IO]): IO[Json] =
    client.run(request).use { response =>
      response.bodyText.compile.string.flatMap { text =>
        if response.status.isSuccess then
          IO.fromEither(io.circe.parser.parse(if text.isEmpty then "{}" else text))
        else
          IO.raiseError(error(s"${request.method.name} ${request.uri.path} " +
            s"→ ${response.status.code}: ${text.take(300)}"))
      }
    }

  private def query(
      client: org.http4s.client.Client[IO],
      base: Uri,
      method: String,
      token: Option[String],
      params: (String, String)*
  ): IO[Json] =
    json(client, Request[IO](Method.GET, target(base, method, params), headers = bearer(token)))

  private def procedure(
      client: org.http4s.client.Client[IO],
      base: Uri,
      method: String,
      body: Json,
      token: Option[String]
  ): IO[Json] =
    json(client, Request[IO](Method.POST, target(base, method, Nil), headers = bearer(token))
      .withEntity(body))

  private def download(
      client: org.http4s.client.Client[IO],
      base: Uri,
      method: String,
      token: Option[String],
      params: (String, String)*
  ): IO[(Array[Byte], String)] =
    val request = Request[IO](Method.GET, target(base, method, params), headers = bearer(token))
    client.run(request).use { response =>
      response.body.compile.to(Array).flatMap { bytes =>
        if response.status.isSuccess then
          IO.pure(bytes -> response.headers.get(CIString("Content-Type")).map(_.head.value)
            .getOrElse("application/octet-stream"))
        else
          IO.raiseError(error(s"GET ${request.uri.path} → ${response.status.code}"))
      }
    }

  private def upload(
      client: org.http4s.client.Client[IO],
      base: Uri,
      method: String,
      body: Array[Byte],
      contentType: String,
      token: Option[String]
  ): IO[Json] =
    val media = MediaType.parse(contentType).getOrElse(MediaType.application.`octet-stream`)
    json(client, Request[IO](Method.POST, target(base, method, Nil),
      headers = bearer(token).put(`Content-Type`(media))).withEntity(body))
