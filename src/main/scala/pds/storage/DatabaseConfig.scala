package pds.storage

import java.nio.file.{Path, Paths}

enum Backend:
  case Sqlite, Postgres

/** Credentials are deliberately excluded from the string representation. */
final class DatabaseConfig private (
    val backend: Backend,
    val url: String,
    val user: Option[String],
    val password: Option[String],
    val poolSize: Int
):
  override def toString: String = s"DatabaseConfig($backend,<redacted>,poolSize=$poolSize)"

object DatabaseConfig:
  val defaultSqlitePath = "data/scala-pds.sqlite3"

  private val postgresKeys =
    Set("PDS_DATABASE_URL", "PDS_DATABASE_USER", "PDS_DATABASE_PASSWORD", "PDS_DATABASE_POOL_SIZE")

  /** PostgreSQL is used when any `PDS_DATABASE_*` variable is set; otherwise a
    * single SQLite file needs no configuration at all.
    */
  def fromEnv(env: Map[String, String]): Either[String, DatabaseConfig] =
    if env.keys.exists(postgresKeys.contains) then postgres(env) else Right(sqlite(env))

  def sqlite(env: Map[String, String]): DatabaseConfig =
    val path = env.getOrElse("PDS_SQLITE_PATH", defaultSqlitePath)
    new DatabaseConfig(Backend.Sqlite, s"jdbc:sqlite:$path", None, None, 1)

  def sqliteAt(path: Path): DatabaseConfig =
    new DatabaseConfig(Backend.Sqlite, s"jdbc:sqlite:$path", None, None, 1)

  def sqlitePath(env: Map[String, String]): Path =
    Paths.get(env.getOrElse("PDS_SQLITE_PATH", defaultSqlitePath))

  private def postgres(env: Map[String, String]): Either[String, DatabaseConfig] =
    for
      url <- env.get("PDS_DATABASE_URL").filter(_.startsWith("jdbc:postgresql://"))
        .toRight("PDS_DATABASE_URL must be a PostgreSQL JDBC URL (jdbc:postgresql://host:port/database)")
      user <- env.get("PDS_DATABASE_USER").filter(_.trim.nonEmpty)
        .toRight("PDS_DATABASE_USER is required when PostgreSQL is configured")
      password <- env.get("PDS_DATABASE_PASSWORD").filter(_.nonEmpty)
        .toRight("PDS_DATABASE_PASSWORD is required when PostgreSQL is configured")
      size <- env.getOrElse("PDS_DATABASE_POOL_SIZE", "10").toIntOption
        .filter(value => value >= 1 && value <= 100)
        .toRight("PDS_DATABASE_POOL_SIZE must be an integer between 1 and 100")
    yield new DatabaseConfig(Backend.Postgres, url, Some(user), Some(password), size)
