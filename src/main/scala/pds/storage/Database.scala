package pds.storage

import cats.effect.{IO, Resource}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import java.nio.file.{Files, Paths}
import java.sql.Connection

final class Database(private val source: HikariDataSource, val dialect: Dialect):
  def backend: Backend = dialect.backend

  /** Runs `work` in a transaction, rolling back on failure. */
  def transact[A](work: Connection => A): IO[A] = IO.blocking {
    val connection = source.getConnection()
    connection.setAutoCommit(false)
    try
      val result = work(connection)
      connection.commit()
      result
    catch
      case error: Throwable =>
        try connection.rollback()
        catch case _: Throwable => ()
        throw error
    finally
      connection.setAutoCommit(true)
      connection.close()
  }

  def read[A](work: Connection => A): IO[A] = IO.blocking {
    val connection = source.getConnection()
    try work(connection)
    finally connection.close()
  }

  /** A connection held open for the life of a stream, so a large read can be
    * produced incrementally instead of being buffered.
    */
  def connection: Resource[IO, Connection] =
    Resource.make(IO.blocking(source.getConnection()))(c => IO.blocking(c.close()))

object Database:
  def resource(config: DatabaseConfig): Resource[IO, Database] =
    Resource.make(IO.blocking(open(config)))(database => IO.blocking(database.source.close()))

  private def open(config: DatabaseConfig): Database =
    if config.backend == Backend.Sqlite then prepareSqliteDirectory(config.url)
    val hikari = new HikariConfig()
    hikari.setJdbcUrl(config.url)
    config.user.foreach(hikari.setUsername)
    config.password.foreach(hikari.setPassword)
    hikari.setMaximumPoolSize(config.poolSize)
    hikari.setPoolName("pds")
    hikari.setAutoCommit(true)
    if config.backend == Backend.Sqlite then
      hikari.setConnectionInitSql("PRAGMA foreign_keys = ON")
      hikari.addDataSourceProperty("journal_mode", "WAL")
      hikari.addDataSourceProperty("busy_timeout", "5000")
      hikari.addDataSourceProperty("synchronous", "NORMAL")
    val source = new HikariDataSource(hikari)
    new Database(source, Dialect(config.backend))

  private def prepareSqliteDirectory(url: String): Unit =
    val path = Paths.get(url.stripPrefix("jdbc:sqlite:"))
    Option(path.getParent).foreach(parent => Files.createDirectories(parent))
