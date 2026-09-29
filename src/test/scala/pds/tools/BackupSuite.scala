package pds.tools

import cats.effect.IO
import io.circe.Json
import java.nio.file.{Files, Path}
import pds.TestEnv.*
import pds.storage.{Database, DatabaseConfig, Migrations, Sql}

class BackupSuite extends munit.CatsEffectSuite:
  private def temporaryDirectory: IO[Path] =
    IO.blocking(Files.createTempDirectory("pds-backup"))

  /** A populated SQLite deployment, and the config that points at it. */
  private def populated: IO[(DatabaseConfig, Path)] =
    for
      directory <- temporaryDirectory
      file = directory.resolve("pds.sqlite3")
      config = DatabaseConfig.sqliteAt(file)
      _ <- Database.resource(config).use { database =>
        for
          _ <- Migrations.run(database)
          _ <- database.transact { connection =>
            (1 to 3).foreach(index =>
              Sql.update(connection,
                "INSERT INTO accounts(did, handle, created_at) VALUES (?, ?, ?)",
                s"did:web:example.com:u:a$index", s"a$index.example.com", 1L))
          }
        yield ()
      }
    yield (config, directory)

  test("a backup is consistent, checksummed and re-readable") {
    for
      prepared <- populated
      (config, directory) = prepared
      target = directory.resolve("backups/pds-backup.sqlite3")
      manifest <- Backup.create(config, target)
      verified <- Backup.verify(target)
      sidecar <- IO.blocking(
        Files.readString(target.resolveSibling(s"${target.getFileName}.sha256")))
      described <- IO.blocking(
        Files.readString(target.resolveSibling(s"${target.getFileName}.manifest.json")))
    yield
      assert(Files.isReadable(target))
      assertEquals(manifest.counts("accounts"), 3L)
      assertEquals(manifest.migrations, 4)
      assertEquals(manifest.checksum.length, 64)
      assertEquals(verified.checksum, manifest.checksum)
      assertEquals(verified.counts, manifest.counts)
      assert(sidecar.startsWith(manifest.checksum), sidecar)
      assert(sidecar.contains(target.getFileName.toString))
      assertEquals(
        io.circe.parser.parse(described).toOption.flatMap(_.hcursor.get[Long]("bytes").toOption),
        Some(manifest.bytes))
      // Nothing is left behind if the copy is interrupted partway.
      assert(!Files.exists(target.resolveSibling(s"${target.getFileName}.partial")))
  }

  test("a corrupted backup fails verification") {
    for
      prepared <- populated
      (config, directory) = prepared
      target = directory.resolve("pds-backup.sqlite3")
      _ <- Backup.create(config, target)
      _ <- IO.blocking {
        val bytes = Files.readAllBytes(target)
        bytes(bytes.length / 2) = (bytes(bytes.length / 2) ^ 0xff).toByte
        Files.write(target, bytes)
      }
      corrupted <- Backup.verify(target).attempt
    yield assert(corrupted.isLeft, corrupted.toString)
  }

  test("a file that is not a scala-pds database is rejected") {
    for
      directory <- temporaryDirectory
      stranger = directory.resolve("notes.txt")
      _ <- IO.blocking(Files.writeString(stranger, "not a database"))
      result <- Backup.verify(stranger).attempt
      missing <- Backup.verify(directory.resolve("absent.sqlite3")).attempt
    yield
      assert(result.left.exists(_.getMessage.contains("not a readable scala-pds database")),
        result.toString)
      assert(missing.left.exists(_.getMessage.contains("cannot be read")))
  }

  test("PostgreSQL deployments are told to use pg_dump") {
    val postgres = DatabaseConfig.fromEnv(Map(
      "PDS_DATABASE_URL" -> "jdbc:postgresql://localhost:5432/pds",
      "PDS_DATABASE_USER" -> "pds",
      "PDS_DATABASE_PASSWORD" -> "secret")).toOption.get
    for
      directory <- temporaryDirectory
      result <- Backup.create(postgres, directory.resolve("pds.sqlite3")).attempt
    yield
      assert(result.left.exists(_.getMessage.contains("pg_dump")), result.toString)
      assert(!Files.exists(directory.resolve("pds.sqlite3")))
  }

  test("a live server's data survives a backup and restore") {
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
          "rkey" -> Json.fromString("3jqfcqzm3fo2j"),
          "record" -> Json.obj(
            "$type" -> Json.fromString("app.bsky.feed.post"),
            "text" -> Json.fromString("hello"),
            "createdAt" -> Json.fromString("2026-01-01T00:00:00.000Z")))), access))
        directory <- temporaryDirectory
        target = directory.resolve("restore.sqlite3")
        // The harness database is live, and SQLite copies itself consistently.
        manifest <- Backup.create(server.databaseConfig, target)
        restored <- Database.resource(DatabaseConfig.sqliteAt(target)).use { database =>
          database.read { connection =>
            (Sql.count(connection, "SELECT COUNT(*) AS total FROM accounts"),
              Sql.count(connection, "SELECT COUNT(*) AS total FROM records"),
              Sql.first(connection, "SELECT handle FROM accounts WHERE did = ?", did)(
                _.string("handle")))
          }
        }
      yield
        assertEquals(manifest.counts("accounts"), 1L)
        assertEquals(restored._1, 1L)
        assertEquals(restored._2, 1L)
        assertEquals(restored._3, Some("alice.pds.example.com"))
    }
  }
