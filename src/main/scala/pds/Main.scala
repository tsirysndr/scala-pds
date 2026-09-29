package pds

import cats.effect.{ExitCode, IO, IOApp, Resource}
import io.circe.Json
import java.nio.file.{Files, Path, Paths}
import org.http4s.HttpApp
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import pds.accounts.Email
import pds.api.{Counters, Metrics, RateLimit}
import pds.crypto.Sealing
import pds.identity.{Net, Resolver}
import pds.lexicon.Schemas
import pds.storage.{Backend, Database, DatabaseConfig, Migrations, RedisConfig, S3, S3Config, Sql}
import pds.tools.{AccountRecovery, Backup, KeyRotation, MasterKeyRotation}
import scala.concurrent.duration.*

object Main extends IOApp:
  def run(arguments: List[String]): IO[ExitCode] =
    for
      env <- IO(sys.env)
      config <- IO.fromEither(ServerConfig.fromEnv(env)
        .left.map(new IllegalArgumentException(_)))
      database <- IO.fromEither(DatabaseConfig.fromEnv(env)
        .left.map(new IllegalArgumentException(_)))
      // Commands that never touch a sealed secret do not need the master key,
      // and must not create one as a side effect of running.
      code <- arguments match
        case "backup" :: rest        => backup(database, rest)
        case "verify-backup" :: rest => verifyBackup(rest)
        case Nil | "serve" :: Nil =>
          masterKey(env, database).flatMap(serve(config, database, _, env))
        case "rotate-master-key" :: _ =>
          masterKey(env, database).flatMap(rotateMasterKey(database, _, env))
        case "verify-master-key" :: _ =>
          masterKey(env, database).flatMap(verifyMasterKey(database, _))
        case "recover-account" :: rest =>
          masterKey(env, database).flatMap(recoverAccount(config, database, _, env, rest))
        case "rotate-account-keys" :: rest =>
          masterKey(env, database).flatMap(rotateAccountKeys(config, database, _, env, rest))
        case other =>
          IO.println(s"scala-pds: unknown command ${other.mkString(" ")}") *>
            IO.println("usage: scala-pds [serve | rotate-master-key | verify-master-key |" +
              " recover-account <identifier> <reference> |" +
              " rotate-account-keys <identifier> [signing|rotation|both] |" +
              " backup <path> | verify-backup <path>]").as(ExitCode(2))
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

  /** Clears an account's second factors so its owner can sign in again. */
  private def recoverAccount(
      config: ServerConfig,
      database: DatabaseConfig,
      sealing: Sealing,
      environment: Map[String, String],
      arguments: List[String]
  ): IO[ExitCode] =
    arguments match
      case identifier :: reference :: _ =>
        Database.resource(database).use { db =>
          for
            _ <- Migrations.run(db)
            schemas <- Schemas.offline
            net = new Net(Client.fromHttpApp(HttpApp.notFound[IO]), allowPrivate = false)
            env = Env(config, db, sealing, net, new Resolver(net, db, config), schemas)
            summary <- AccountRecovery.run(env, identifier, reference)
            _ <- IO.println(s"scala-pds: recovered ${summary.handle} (${summary.did})")
            _ <- IO.println(s"scala-pds: removed authenticator=${summary.authenticator} " +
              s"recoveryCodes=${summary.recoveryCodes} passkeys=${summary.passkeys}")
            _ <- IO.println(s"scala-pds: security epoch is now ${summary.epoch}; " +
              "every session and OAuth token for the account has ended")
          yield ExitCode.Success
        }.handleErrorWith(error =>
          IO.println(s"scala-pds: ${error.getMessage}").as(ExitCode.Error))
      case _ =>
        IO.println("usage: scala-pds recover-account <handle|did|email> <reference>")
          .as(ExitCode(2))

  /** Replaces an account's managed keys and republishes its identity. */
  private def rotateAccountKeys(
      config: ServerConfig,
      database: DatabaseConfig,
      sealing: Sealing,
      environment: Map[String, String],
      arguments: List[String]
  ): IO[ExitCode] =
    arguments match
      case identifier :: rest =>
        val what = rest.headOption.getOrElse("both")
        val signing = what == "signing" || what == "both"
        val rotation = what == "rotation" || what == "both"
        if !signing && !rotation then
          IO.println("usage: scala-pds rotate-account-keys <identifier> [signing|rotation|both]")
            .as(ExitCode(2))
        else
          val resources = for
            db <- Database.resource(database)
            client <- EmberClientBuilder.default[IO].withTimeout(15.seconds).build
          yield (db, client)
          resources.use { (db, client) =>
            for
              _ <- Migrations.run(db)
              net = new Net(client, !config.secure)
              schemas <- Schemas.offline
              env = Env(config, db, sealing, net, new Resolver(net, db, config), schemas)
              result <- KeyRotation.rotate(env, identifier, signing, rotation)
              _ <- IO.println(s"scala-pds: rotated keys for ${result.handle} (${result.did})")
              _ <- result.signingKey.fold(IO.unit)(key =>
                IO.println(s"scala-pds: signing key is now $key"))
              _ <- result.revision.fold(IO.unit)(rev =>
                IO.println(s"scala-pds: head re-signed at revision $rev"))
              _ <- result.rotationKey.fold(IO.unit)(key =>
                IO.println(s"scala-pds: rotation key is now $key"))
            yield ExitCode.Success
          }.handleErrorWith(error =>
            IO.println(s"scala-pds: ${error.getMessage}").as(ExitCode.Error))
      case _ =>
        IO.println("usage: scala-pds rotate-account-keys <identifier> [signing|rotation|both]")
          .as(ExitCode(2))

  private def backup(database: DatabaseConfig, arguments: List[String]): IO[ExitCode] =
    arguments.headOption match
      case None => IO.println("usage: scala-pds backup <path>").as(ExitCode(2))
      case Some(path) =>
        Backup.create(database, java.nio.file.Paths.get(path)).flatMap { manifest =>
          IO.println(s"scala-pds: wrote ${manifest.file} (${manifest.bytes} bytes)") *>
            IO.println(s"scala-pds: sha256 ${manifest.checksum}") *>
            IO.println(s"scala-pds: ${manifest.counts.getOrElse("accounts", 0L)} account(s), " +
              s"${manifest.counts.getOrElse("records", 0L)} record(s), " +
              s"${manifest.counts.getOrElse("blobs", 0L)} blob(s)") *>
            IO.println("scala-pds: back up the master key separately; it is not in this file")
              .as(ExitCode.Success)
        }.handleErrorWith(error =>
          IO.println(s"scala-pds: ${error.getMessage}").as(ExitCode.Error))

  private def verifyBackup(arguments: List[String]): IO[ExitCode] =
    arguments.headOption match
      case None => IO.println("usage: scala-pds verify-backup <path>").as(ExitCode(2))
      case Some(path) =>
        Backup.verify(java.nio.file.Paths.get(path)).flatMap { manifest =>
          IO.println(s"scala-pds: ${manifest.file} is readable, " +
            s"${manifest.migrations} migration(s) applied") *>
            IO.println(s"scala-pds: sha256 ${manifest.checksum}") *>
            IO.println(manifest.counts.toVector.sortBy(_._1)
              .map((table, count) => s"  $table $count").mkString("\n"))
              .as(ExitCode.Success)
        }.handleErrorWith(error =>
          IO.println(s"scala-pds: ${error.getMessage}").as(ExitCode.Error))

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
