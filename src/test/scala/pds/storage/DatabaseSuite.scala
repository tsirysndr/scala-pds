package pds.storage

import cats.effect.IO
import java.nio.file.Files

class DatabaseSuite extends munit.CatsEffectSuite:
  private def temporary: IO[DatabaseConfig] =
    IO.blocking(DatabaseConfig.sqliteAt(
      Files.createTempDirectory("pds-test").resolve("pds.sqlite3")))

  test("migrations create the schema once and are idempotent") {
    temporary.flatMap { config =>
      Database.resource(config).use { database =>
        for
          first <- Migrations.run(database)
          second <- Migrations.run(database)
          tables <- database.read(connection =>
            Sql.query(connection, "SELECT name FROM sqlite_master WHERE type = 'table'")(
              _.string("name")).toSet)
        yield
          assertEquals(first, Vector(1, 2, 3))
          assertEquals(second, Vector.empty)
          assert(tables.contains("accounts"), tables.toString)
          assert(tables.contains("repo_blocks"))
          assert(tables.contains("oauth_tokens"))
          assert(tables.contains("schema_migrations"))
          assert(tables.contains("blob_deletions"))
          assert(tables.contains("account_passkeys"))
          assert(!tables.contains("blobs_next"), "the rebuilt table must be renamed in place")
      }
    }
  }

  test("transactions roll back on failure and commit otherwise") {
    temporary.flatMap { config =>
      Database.resource(config).use { database =>
        for
          _ <- Migrations.run(database)
          _ <- database.transact(connection =>
            Sql.update(connection,
              "INSERT INTO accounts(did, handle, created_at) VALUES (?, ?, ?)",
              "did:plc:one", "one.example.com", 1L))
          failure <- database.transact { connection =>
            Sql.update(connection,
              "INSERT INTO accounts(did, handle, created_at) VALUES (?, ?, ?)",
              "did:plc:two", "two.example.com", 2L)
            throw new RuntimeException("rollback")
          }.attempt
          dids <- database.read(connection =>
            Sql.query(connection, "SELECT did FROM accounts ORDER BY did")(_.string("did")))
        yield
          assert(failure.isLeft)
          assertEquals(dids, Vector("did:plc:one"))
      }
    }
  }

  test("foreign keys and check constraints are enforced") {
    temporary.flatMap { config =>
      Database.resource(config).use { database =>
        for
          _ <- Migrations.run(database)
          orphan <- database.transact(connection =>
            Sql.update(connection,
              "INSERT INTO repo_roots(did, commit_cid, data_cid, rev) VALUES (?, ?, ?, ?)",
              "did:plc:missing", "bafy", "bafy", "3k")).attempt
          badStatus <- database.transact(connection =>
            Sql.update(connection,
              "INSERT INTO accounts(did, handle, status, created_at) VALUES (?, ?, ?, ?)",
              "did:plc:bad", "bad.example.com", "nonsense", 1L)).attempt
        yield
          assert(orphan.isLeft)
          assert(badStatus.isLeft)
      }
    }
  }

  test("the event sequence returns increasing identifiers") {
    temporary.flatMap { config =>
      Database.resource(config).use { database =>
        for
          _ <- Migrations.run(database)
          _ <- database.transact(connection =>
            Sql.update(connection,
              "INSERT INTO accounts(did, handle, created_at) VALUES (?, ?, ?)",
              "did:plc:one", "one.example.com", 1L))
          sequence <- database.transact { connection =>
            (1 to 3).toVector.map(index =>
              Sql.insertReturningId(connection,
                "INSERT INTO repo_events(did, kind, payload, created_at) VALUES (?, ?, ?, ?)",
                "did:plc:one", "commit", Array(index.toByte), index.toLong))
          }
        yield assertEquals(sequence, Vector(1L, 2L, 3L))
      }
    }
  }
