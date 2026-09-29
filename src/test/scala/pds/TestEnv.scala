package pds

import cats.effect.{IO, Resource}
import io.circe.Json
import java.nio.file.Files
import org.http4s.*
import org.http4s.client.Client
import org.http4s.circe.*
import pds.api.{Metrics, RateLimit}
import pds.crypto.Sealing
import pds.identity.{Net, Resolver}
import pds.lexicon.Schemas
import pds.storage.{Database, DatabaseConfig, Migrations, S3}

/** A complete PDS wired to a temporary SQLite file and a scripted HTTP client. */
object TestEnv:
  val adminPassword = "admin-password-1234"

  final case class Harness(env: Env, app: HttpApp[IO], databaseConfig: DatabaseConfig):
    def run(request: Request[IO]): IO[Response[IO]] = app.run(request)

    def json(request: Request[IO]): IO[(Status, Json)] =
      app.run(request).flatMap(response =>
        response.as[Json].attempt.map(body =>
          response.status -> body.getOrElse(Json.obj())))

  def routes(upstream: PartialFunction[Request[IO], IO[Response[IO]]] = PartialFunction.empty): Client[IO] =
    Client.fromHttpApp(HttpApp[IO] { request =>
      upstream.lift(request).getOrElse(IO.pure(Response[IO](Status.NotFound)))
    })

  def harness(
      overrides: Map[String, String] = Map.empty,
      client: Client[IO] = routes(),
      lexicons: Option[String => IO[Either[String, io.circe.Json]]] = None,
      blobs: Option[S3] = None
  ): Resource[IO, Harness] =
    val settings = Map(
      "PDS_HOSTNAME" -> "pds.example.com",
      "PDS_PUBLIC_URL" -> "https://pds.example.com",
      "PDS_DID_METHOD" -> "web",
      "PDS_ADMIN_PASSWORD" -> adminPassword,
      "PDS_ACCESS_LOG" -> "false"
    ) ++ overrides
    for
      directory <- Resource.eval(IO.blocking(Files.createTempDirectory("pds-harness")))
      databaseConfig = DatabaseConfig.sqliteAt(directory.resolve("pds.sqlite3"))
      database <- Database.resource(databaseConfig)
      _ <- Resource.eval(Migrations.run(database))
      config <- Resource.eval(IO.fromEither(
        ServerConfig.fromEnv(settings).left.map(new IllegalArgumentException(_))))
      sealing <- Resource.eval(IO.fromEither(
        Sealing.fromBase64(Sealing.generate()).left.map(new IllegalArgumentException(_))))
      net = new Net(client, allowPrivate = true)
      identity = new Resolver(net, database, config)
      schemas <- Resource.eval(
        lexicons.fold(Schemas.network(net, identity))(Schemas.create))
      env = Env(config, database, sealing, net, identity, schemas, blobs)
      // Tests are not rate limited unless one asks to be.
      limiter <- Resource.eval(RateLimit.create(
        if overrides.contains("PDS_RATE_LIMIT_PER_MINUTE") then config.rateLimitPerMinute
        else 100000))
      metrics <- Resource.eval(Metrics.create)
    yield Harness(env, PdsApp(env, client, limiter, None, metrics), databaseConfig)

  def post(path: String, body: Json): Request[IO] =
    Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(body)

  def get(path: String): Request[IO] = Request[IO](Method.GET, Uri.unsafeFromString(path))

  def authorized(request: Request[IO], token: String): Request[IO] =
    request.putHeaders(Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer $token"))

  def admin(request: Request[IO]): Request[IO] =
    request.putHeaders(Header.Raw(org.typelevel.ci.CIString("Authorization"),
      s"Basic ${java.util.Base64.getEncoder.encodeToString(
        s"admin:$adminPassword".getBytes("UTF-8"))}"))
