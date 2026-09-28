package pds.storage

class DatabaseConfigSuite extends munit.FunSuite:
  private val postgresEnv = Map(
    "PDS_DATABASE_URL" -> "jdbc:postgresql://localhost:5432/pds",
    "PDS_DATABASE_USER" -> "pds",
    "PDS_DATABASE_PASSWORD" -> "test-secret"
  )

  test("an unconfigured server uses a single SQLite file") {
    val config = DatabaseConfig.fromEnv(Map.empty).toOption.get
    assertEquals(config.backend, Backend.Sqlite)
    assertEquals(config.url, s"jdbc:sqlite:${DatabaseConfig.defaultSqlitePath}")
    assertEquals(config.poolSize, 1)
    assertEquals(config.user, None)
  }

  test("the SQLite location is configurable") {
    val config = DatabaseConfig.fromEnv(Map("PDS_SQLITE_PATH" -> "/srv/pds.sqlite3")).toOption.get
    assertEquals(config.url, "jdbc:sqlite:/srv/pds.sqlite3")
  }

  test("PostgreSQL configuration has a bounded pool and does not print credentials") {
    val config = DatabaseConfig.fromEnv(postgresEnv).toOption.get
    assertEquals(config.backend, Backend.Postgres)
    assertEquals(config.poolSize, 10)
    assertEquals(config.url, postgresEnv("PDS_DATABASE_URL"))
    assertEquals(config.user, Some("pds"))
    assert(!config.toString.contains("test-secret"))
    assert(!config.toString.contains("localhost"))
  }

  test("any PostgreSQL variable requires the complete set") {
    postgresEnv.keys.foreach { key =>
      val result = DatabaseConfig.fromEnv(postgresEnv - key)
      assert(result.isLeft, key)
      assert(!result.toString.contains("test-secret"))
    }
    assert(DatabaseConfig.fromEnv(Map("PDS_DATABASE_POOL_SIZE" -> "5")).isLeft)
  }

  test("non-PostgreSQL URLs and invalid pool sizes fail") {
    List("", "jdbc:sqlite:pds.db", "postgres://user:secret@localhost/pds").foreach { url =>
      val result = DatabaseConfig.fromEnv(postgresEnv.updated("PDS_DATABASE_URL", url))
      assert(result.isLeft)
      assert(!result.toString.contains("secret"))
    }
    List("0", "-1", "101", "many", "999999999999").foreach { size =>
      assert(DatabaseConfig.fromEnv(postgresEnv.updated("PDS_DATABASE_POOL_SIZE", size)).isLeft)
    }
    List("1", "100").foreach { size =>
      assertEquals(DatabaseConfig.fromEnv(postgresEnv.updated("PDS_DATABASE_POOL_SIZE", size))
        .map(_.poolSize), Right(size.toInt))
    }
  }

  test("the dialect renders schema placeholders per backend") {
    assertEquals(Dialect(Backend.Postgres).render("a {{BLOB}} {{JSON}} {{ID_PK}}"),
      "a bytea jsonb bigserial PRIMARY KEY")
    assertEquals(Dialect(Backend.Sqlite).render("a {{BLOB}} {{JSON}} {{ID_PK}}"),
      "a blob text integer PRIMARY KEY AUTOINCREMENT")
    assertEquals(Dialect(Backend.Sqlite).forUpdate, "")
    assertEquals(Dialect(Backend.Postgres).forUpdate, " FOR UPDATE")
  }
