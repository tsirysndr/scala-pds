package pds

import cats.effect.{ExitCode, IO, IOApp, Resource}
import io.circe.Json
import java.nio.file.{Files, Path, Paths}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import pds.accounts.Email
import pds.api.{Counters, Metrics, RateLimit}
import pds.crypto.Sealing
import pds.identity.{Net, Resolver}
import pds.lexicon.Schemas
import pds.storage.{Backend, Database, DatabaseConfig, Migrations, RedisConfig, S3, S3Config, Sql}
import pds.tools.MasterKeyRotation
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
      code <- arguments match
        case Nil | "serve" :: Nil     => serve(config, database, sealing, env)
        case "rotate-master-key" :: _ => rotateMasterKey(database, sealing, env)
        case "verify-master-key" :: _ => verifyMasterKey(database, sealing)
        case other =>
          IO.println(s"scala-pds: unknown command ${other.mkString(" ")}") *>
            IO.println("usage: scala-pds [serve | rotate-master-key | verify-master-key]")
              .as(ExitCode(2))
    yield code

  /** Offline re-encryption of every stored secret under PDS_NEW_MASTER_KEY. */
  private def rotateMasterKey(
      database: DatabaseConfig, current: Sealing, environment: Map[String, String]
  ): IO[ExitCode] =
    environment.get("PDS_NEW_MASTER_KEY").filter(_.nonEmpty) match
      case None =>
        IO.println("scala-pds: set PDS_NEW_MASTER_KEY to the key to rotate to").as(ExitCode(2))
      case Some(value) =>
        IO.fromEither(Sealing.fromBase64(value).left.map(new IllegalArgumentException(_)))
          .flatMap { next =>
            Database.resource(database).use { db =>
              Migrations.run(db) *> MasterKeyRotation.run(db, current, next).flatMap { summary =>
                IO.println(s"scala-pds: re-encrypted ${summary.signingKeys} signing key(s), " +
                  s"${summary.rotationKeys} rotation key(s) and " +
                  s"${summary.authenticators} authenticator secret(s)") *>
                  IO.println("scala-pds: set PDS_MASTER_KEY to the new key before restarting; " +
                    "every session and OAuth token is now invalid")
                    .as(ExitCode.Success)
              }
            }
          }.handleErrorWith { error =>
            IO.println(s"scala-pds: rotation failed, nothing was changed: ${error.getMessage}")
              .as(ExitCode.Error)
          }

  private def verifyMasterKey(database: DatabaseConfig, key: Sealing): IO[ExitCode] =
    Database.resource(database).use { db =>
      MasterKeyRotation.verify(db, key).flatMap { summary =>
        IO.println(s"scala-pds: ${summary.total} sealed value(s) open with this master key")
          .as(ExitCode.Success)
      }
    }.handleErrorWith { error =>
      IO.println(s"scala-pds: ${error.getMessage}").as(ExitCode.Error)
    }

  private def serve(
      config: ServerConfig,
      databaseConfig: DatabaseConfig,
      sealing: Sealing,
      environment: Map[String, String]
  ): IO[ExitCode] =
    val resources = for
      redis <- Resource.eval(IO.fromEither(RedisConfig.fromEnv(environment)
        .left.map(new IllegalArgumentException(_))))
      database <- Database.resource(databaseConfig)
      client <- EmberClientBuilder.default[IO].withTimeout(15.seconds).build
      counters <- Counters.resource(redis)
    yield (database, client, counters)

    resources.use { (database, client, counters) =>
      val allowPrivate = !config.secure ||
        environment.get("PDS_ALLOW_PRIVATE_NETWORK").contains("true")
      val net = new Net(client, allowPrivate)
      val identity = new Resolver(net, database, config)
      for
        blobs <- IO.fromEither(S3Config.fromEnv(environment)
          .left.map(new IllegalArgumentException(_)))
          .map(_.map(new S3(_, client)))
        schemas <- Schemas.network(net, identity)
        env = Env(config, database, sealing, net, identity, schemas, blobs)
        applied <- Migrations.run(database)
        _ <- IO.println(s"scala-pds: ${databaseConfig.backend} storage, " +
          s"${applied.length} migration(s) applied" +
          blobs.fold("")(store => s", blobs in ${store.bucket}"))
        limiter = RateLimit(counters, config.rateLimitPerMinute)
        metrics <- Metrics.create
        _ <- requestCrawl(env).start
        _ <- background(env).start
        _ <- IO.println(s"scala-pds: listening on ${config.host}:${config.port} " +
          s"as ${config.publicUrl}, rate limits " +
          (if counters.shared then "shared through Redis" else "per instance"))
        exit <- EmberServerBuilder.default[IO]
          .withHost(config.host)
          .withPort(config.port)
          .withIdleTimeout(75.seconds)
          .withHttpWebSocketApp(builder => PdsApp(env, client, limiter, Some(builder), metrics))
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
    val work = Email.deliver(env).attempt *> pds.repo.BlobStore.sweep(env).attempt *>
      pds.repo.BlockGc.expireEvents(env).attempt *>
      pds.repo.BlockGc.sweep(env).attempt *> sweep(env).attempt
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
