package pds.storage

import cats.effect.IO
import java.sql.Connection
import pds.crypto.{Encoding, Hash}
import scala.io.Source

/** Versioned migrations applied at startup. Checksums are recorded so an
  * edited migration fails loudly instead of diverging between deployments.
  */
object Migrations:
  private val files = Vector(
    "001-baseline.sql", "002-blob-backends.sql", "003-passkeys.sql",
    "004-account-recovery.sql")

  def run(database: Database): IO[Vector[Int]] =
    database.transact { connection =>
      lock(connection, database.dialect)
      Sql.update(connection,
        """CREATE TABLE IF NOT EXISTS schema_migrations (
           version integer PRIMARY KEY,
           checksum text NOT NULL,
           applied_at bigint NOT NULL)""")
      val applied = Sql.query(connection, "SELECT version, checksum FROM schema_migrations")(row =>
        row.int("version") -> row.string("checksum")).toMap
      files.zipWithIndex.flatMap { (name, index) =>
        val version = index + 1
        val body = read(name)
        val checksum = Encoding.b64(Hash.sha256(body))
        applied.get(version) match
          case Some(recorded) if recorded == checksum => None
          case Some(_) =>
            throw new IllegalStateException(s"Migration $name changed after it was applied")
          case None =>
            statements(database.dialect.render(body)).foreach(Sql.update(connection, _))
            Sql.update(connection,
              "INSERT INTO schema_migrations(version, checksum, applied_at) VALUES (?, ?, ?)",
              version, checksum, System.currentTimeMillis())
            Some(version)
      }
    }

  private def lock(connection: Connection, dialect: Dialect): Unit =
    if dialect.backend == Backend.Postgres then
      Sql.update(connection, "SELECT pg_advisory_xact_lock(4711)")

  private def read(name: String): String =
    val stream = getClass.getResourceAsStream(s"/migrations/$name")
    require(stream != null, s"missing migration $name")
    try Source.fromInputStream(stream, "UTF-8").mkString
    finally stream.close()

  /** Splits on semicolons at the end of a line, which the schema always uses. */
  private def statements(body: String): Vector[String] =
    body.split(";\\s*\n").toVector.map(_.trim).filter(_.nonEmpty)
