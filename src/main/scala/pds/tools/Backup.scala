package pds.tools

import cats.effect.IO
import io.circe.Json
import java.nio.file.{Files, Path, StandardCopyOption}
import pds.crypto.{Encoding, Hash}
import pds.storage.{Backend, Database, DatabaseConfig, Sql}

/** Checksummed snapshots of a SQLite deployment.
  *
  * SQLite can copy itself consistently while the server runs, so a backup is a
  * `VACUUM INTO` followed by a checksum and a manifest of what the copy should
  * contain. PostgreSQL has `pg_dump`, which does this properly; this command
  * says so rather than producing something weaker.
  *
  * The master key is deliberately **not** included: a backup that carries both
  * the sealed secrets and the key that opens them protects nothing.
  */
object Backup:
  final case class Failed(reason: String) extends RuntimeException(reason)

  final case class Manifest(
      file: Path,
      bytes: Long,
      checksum: String,
      counts: Map[String, Long],
      migrations: Int
  ):
    def json: Json = Json.obj(
      "file" -> Json.fromString(file.getFileName.toString),
      "bytes" -> Json.fromLong(bytes),
      "sha256" -> Json.fromString(checksum),
      "migrations" -> Json.fromInt(migrations),
      "counts" -> Json.fromFields(counts.toVector.sortBy(_._1).map((k, v) => k -> Json.fromLong(v)))
    )

  private val counted = Vector(
    "accounts", "account_keys", "app_passwords", "records", "repo_blocks", "blobs",
    "repo_events", "oauth_tokens", "account_preferences", "invite_codes")

  def create(config: DatabaseConfig, target: Path): IO[Manifest] =
    if config.backend != Backend.Sqlite then
      IO.raiseError(Failed(
        "This command backs up SQLite deployments. For PostgreSQL use pg_dump, " +
          "which snapshots consistently and restores with pg_restore."))
    else
      for
        _ <- IO.blocking(Option(target.getParent).foreach(Files.createDirectories(_)))
        temporary = target.resolveSibling(s"${target.getFileName}.partial")
        _ <- IO.blocking(Files.deleteIfExists(temporary))
        _ <- Database.resource(config).use { database =>
          // VACUUM INTO writes a consistent copy even while the server runs.
          database.read(connection =>
            Sql.update(connection, s"VACUUM INTO '${temporary.toAbsolutePath}'")).void
        }
        manifest <- inspect(temporary)
        _ <- IO.blocking(Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING))
        placed = manifest.copy(file = target)
        _ <- IO.blocking {
          Files.writeString(target.resolveSibling(s"${target.getFileName}.sha256"),
            s"${placed.checksum}  ${target.getFileName}\n")
          Files.writeString(target.resolveSibling(s"${target.getFileName}.manifest.json"),
            placed.json.spaces2 + "\n")
        }
      yield placed

  /** Re-reads a backup: checksum, schema version and row counts. */
  def verify(file: Path): IO[Manifest] =
    for
      _ <- IO.raiseUnless(Files.isReadable(file))(Failed(s"$file cannot be read"))
      manifest <- inspect(file)
      recorded <- IO.blocking {
        val sidecar = file.resolveSibling(s"${file.getFileName}.sha256")
        Option.when(Files.isReadable(sidecar))(
          Files.readString(sidecar).trim.takeWhile(_ != ' '))
      }
      _ <- recorded match
        case Some(expected) if expected != manifest.checksum =>
          IO.raiseError(Failed(s"$file does not match its recorded checksum"))
        case _ => IO.unit
    yield manifest

  private def inspect(file: Path): IO[Manifest] =
    for
      bytes <- IO.blocking(Files.size(file))
      checksum <- IO.blocking(Encoding.hex(Hash.sha256(Files.readAllBytes(file))))
      result <- Database.readOnlySqlite(file).use { database =>
        database.read { connection =>
          val migrations = Sql.count(connection,
            "SELECT COUNT(*) AS total FROM schema_migrations").toInt
          val counts = counted.map(table =>
            table -> Sql.count(connection, s"SELECT COUNT(*) AS total FROM $table")).toMap
          Manifest(file, bytes, checksum, counts, migrations)
        }
      }.handleErrorWith(error =>
        IO.raiseError(Failed(s"$file is not a readable scala-pds database: ${error.getMessage}")))
    yield result
