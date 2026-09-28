package pds

import cats.effect.{ExitCode, IO, IOApp, Resource}
import io.circe.Json
import java.nio.file.{Files, Path, Paths}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import pds.accounts.Email
import pds.api.RateLimit
import pds.crypto.Sealing
import pds.identity.{Net, Resolver}
import pds.storage.{Backend, Database, DatabaseConfig, Migrations, Sql}
import scala.concurrent.duration.*

object Main extends IOApp:
  def run(arguments: List[String]): IO[ExitCode] =
    for
      env <- IO(sys.env)
      config <- IO.fromEither(ServerConfig.fromEnv(env)
        .left.map(new IllegalArgumentException(_)))
      database <- IO.fromEither(DatabaseConfig.fromEnv(env)
        .left.map(new IllegalArgumentException(_)))
      sealing <- masterKey(env, database)
      code <- serve(config, database, sealing, env)
    yield code

  private def serve(
      config: ServerConfig,
      databaseConfig: DatabaseConfig,
      sealing: Sealing,
      environment: Map[String, String]
  ): IO[ExitCode] =
    val resources = for
      database <- Database.resource(databaseConfig)
      client <- EmberClientBuilder.default[IO].withTimeout(15.seconds).build
    yield (database, client)

    resources.use { (database, client) =>
      val allowPrivate = !config.secure ||
        environment.get("PDS_ALLOW_PRIVATE_NETWORK").contains("true")
      val net = new Net(client, allowPrivate)
      val env = Env(config, database, sealing, net, new Resolver(net, database, config))
      for
        applied <- Migrations.run(database)
        _ <- IO.println(s"scala-pds: ${databaseConfig.backend} storage, " +
          s"${applied.length} migration(s) applied")
        limiter <- RateLimit.create(config.rateLimitPerMinute)
        _ <- requestCrawl(env).start
        _ <- background(env).start
        _ <- IO.println(s"scala-pds: listening on ${config.host}:${config.port} " +
          s"as ${config.publicUrl}")
        exit <- EmberServerBuilder.default[IO]
          .withHost(config.host)
          .withPort(config.port)
          .withIdleTimeout(75.seconds)
          .withHttpWebSocketApp(builder => PdsApp(env, client, limiter, Some(builder)))
          .build
          .useForever
          .as(ExitCode.Success)
      yield exit
    }

  /** The master key seals stored secrets. It is read from the environment, or
    * generated once next to the database so a zero-configuration server still
    * keeps a stable key across restarts.
    */
  private def masterKey(environment: Map[String, String], config: DatabaseConfig): IO[Sealing] =
    environment.get("PDS_MASTER_KEY").filter(_.nonEmpty) match
      case Some(value) => IO.fromEither(Sealing.fromBase64(value)
        .left.map(new IllegalArgumentException(_)))
      case None if config.backend == Backend.Postgres =>
        IO.raiseError(new IllegalArgumentException(
          "PDS_MASTER_KEY is required when PostgreSQL is configured"))
      case None =>
        val path = DatabaseConfig.sqlitePath(environment).resolveSibling("master.key")
        IO.blocking {
          Option(path.getParent).foreach(Files.createDirectories(_))
          if Files.exists(path) then Files.readString(path).trim
          else
            val generated = Sealing.generate()
            Files.writeString(path, generated + "\n")
            path.toFile.setReadable(false, false)
            path.toFile.setReadable(true, true)
            path.toFile.setWritable(false, false)
            path.toFile.setWritable(true, true)
            generated
        }.flatMap { value =>
          IO.println(s"scala-pds: using the master key at $path; back it up and " +
            "set PDS_MASTER_KEY in production") *>
            IO.fromEither(Sealing.fromBase64(value).left.map(new IllegalArgumentException(_)))
        }

  /** Asks configured relays to crawl this host, so a new server is discovered. */
  private def requestCrawl(env: Env): IO[Unit] =
    env.config.relayUrls.traverse_ { relay =>
      env.net.postJson(s"$relay/xrpc/com.atproto.sync.requestCrawl",
        Json.obj("hostname" -> Json.fromString(env.config.hostname))).flatMap {
        case Right(_)    => IO.println(s"scala-pds: requested a crawl from $relay")
        case Left(error) => IO.println(s"scala-pds: relay $relay refused the crawl request: $error")
      }
    }

  /** Email delivery and expiry sweeps. */
  private def background(env: Env): IO[Unit] =
    val work = Email.deliver(env).attempt *> sweep(env).attempt
    (work *> IO.sleep(30.seconds)).foreverM

  private def sweep(env: Env): IO[Unit] =
    env.now.flatMap { now =>
      env.database.transact { connection =>
        Sql.update(connection, "DELETE FROM browser_sessions WHERE expires_at <= ?", now)
        Sql.update(connection, "DELETE FROM account_tokens WHERE expires_at <= ?", now)
        Sql.update(connection, "DELETE FROM oauth_requests WHERE expires_at <= ?", now)
        Sql.update(connection, "DELETE FROM oauth_interactions WHERE expires_at <= ?", now)
        Sql.update(connection, "DELETE FROM oauth_codes WHERE expires_at <= ?", now)
        Sql.update(connection, "DELETE FROM oauth_replay WHERE expires_at <= ?", now)
        Sql.update(connection, "DELETE FROM service_token_replay WHERE expires_at <= ?", now)
        Sql.update(connection, "DELETE FROM identity_cache WHERE expires_at <= ?", now)
        Sql.update(connection, "DELETE FROM sessions WHERE expires_at <= ?", now)
        Sql.update(connection,
          "DELETE FROM accounts WHERE status = 'deactivated' AND delete_after IS NOT NULL AND delete_after <= ?",
          now)
      }.void
    }

  extension [A](values: Vector[A])
    private def traverse_(work: A => IO[Unit]): IO[Unit] =
      values.foldLeft(IO.unit)((acc, value) => acc *> work(value))
