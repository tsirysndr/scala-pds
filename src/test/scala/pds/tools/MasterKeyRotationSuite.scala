package pds.tools

import cats.effect.IO
import io.circe.Json
import java.nio.file.Files
import pds.crypto.{Curve, Sealing}
import pds.storage.{Database, DatabaseConfig, Migrations, Sql}
import pds.TestEnv.*

class MasterKeyRotationSuite extends munit.CatsEffectSuite:
  private val credentials = Json.obj(
    "handle" -> Json.fromString("alice.pds.example.com"),
    "email" -> Json.fromString("alice@example.com"),
    "password" -> Json.fromString("correct horse battery")
  )

  /** A populated database: one account with signing and rotation keys, plus a
    * confirmed authenticator, so all three sealed kinds are present.
    */
  private def populated(server: Harness): IO[(String, Array[Byte])] =
    for
      created <- server.json(post("/xrpc/com.atproto.server.createAccount", credentials))
      did = created._2.hcursor.get[String]("did").toOption.get
      secret = pds.security.Totp.secret()
      now <- server.env.now
      _ <- server.env.database.transact { connection =>
        Sql.update(connection,
          """INSERT INTO account_totp(did, sealed_secret, enrollment_expires_at, attempt_window,
             created_at, confirmed) VALUES (?, ?, ?, ?, ?, ?)""",
          did, server.env.sealing.seal("pds/totp", secret), now + 600_000, now, now, true)
      }
    yield (did, secret)

  test("rotation re-encrypts every sealed value and only the new key opens them") {
    harness().use { server =>
      val next = Sealing.fromBase64(Sealing.generate()).toOption.get
      for
        prepared <- populated(server)
        (did, secret) = prepared
        before <- MasterKeyRotation.verify(server.env.database, server.env.sealing)
        summary <- MasterKeyRotation.run(server.env.database, server.env.sealing, next)
        afterNew <- MasterKeyRotation.verify(server.env.database, next)
        afterOld <- MasterKeyRotation.verify(server.env.database, server.env.sealing).attempt
        reopened <- server.env.database.read { connection =>
          val row = Sql.first(connection,
            "SELECT signing_curve, signing_sealed FROM account_keys WHERE did = ?", did)(row =>
            (row.string("signing_curve"), row.bytes("signing_sealed"))).get
          val totp = Sql.first(connection,
            "SELECT sealed_secret FROM account_totp WHERE did = ?", did)(_.bytes("sealed_secret")).get
          (next.openKey(Curve.K256, row._2), next.open("pds/totp", totp))
        }
      yield
        assertEquals(before.signingKeys, 1)
        assertEquals(before.rotationKeys, 1)
        assertEquals(before.authenticators, 1)
        assertEquals(summary.total, 3)
        assertEquals(afterNew.total, 3)
        assert(afterOld.isLeft)
        assert(reopened._1.isDefined, "the signing key must open under the new master key")
        assertEquals(reopened._2.map(_.toVector), Some(secret.toVector))
    }
  }

  test("the repository still signs with the same key after rotation") {
    harness().use { server =>
      val next = Sealing.fromBase64(Sealing.generate()).toOption.get
      for
        prepared <- populated(server)
        (did, _) = prepared
        before <- server.env.database.read(connection =>
          pds.repo.RepoStore.signingKey(connection, did, server.env.sealing).publicKey.didKey)
        _ <- MasterKeyRotation.run(server.env.database, server.env.sealing, next)
        after <- server.env.database.read(connection =>
          pds.repo.RepoStore.signingKey(connection, did, next).publicKey.didKey)
      yield assertEquals(after, before)
    }
  }

  test("a value that does not open under the current key aborts the whole rotation") {
    harness().use { server =>
      val next = Sealing.fromBase64(Sealing.generate()).toOption.get
      val stranger = Sealing.fromBase64(Sealing.generate()).toOption.get
      for
        prepared <- populated(server)
        (did, _) = prepared
        // A second account whose signing key was sealed by a different deployment.
        _ <- server.json(post("/xrpc/com.atproto.server.createAccount", Json.obj(
          "handle" -> Json.fromString("bobby.pds.example.com"),
          "email" -> Json.fromString("bobby@example.com"),
          "password" -> Json.fromString("correct horse battery"))))
        foreign = pds.crypto.PrivateKey.generate(Curve.K256)
        _ <- server.env.database.transact(connection =>
          Sql.update(connection,
            "UPDATE account_keys SET signing_sealed = ? WHERE did <> ?",
            stranger.sealKey(foreign), did))
        failed <- MasterKeyRotation.run(server.env.database, server.env.sealing, next).attempt
        // The first account's key is untouched, because the transaction rolled back.
        unchanged <- MasterKeyRotation.verify(server.env.database, server.env.sealing).attempt
        original <- server.env.database.read(connection =>
          pds.repo.RepoStore.signingKey(connection, did, server.env.sealing).publicKey.didKey)
      yield
        assert(failed.isLeft)
        assert(failed.left.exists(_.getMessage.contains("does not open")), failed.toString)
        assert(unchanged.isLeft, "verification still fails on the foreign row")
        assert(original.startsWith("did:key:"))
    }
  }

  test("rotating to the same key is a no-op that still verifies") {
    harness().use { server =>
      for
        _ <- populated(server)
        summary <- MasterKeyRotation.run(
          server.env.database, server.env.sealing, server.env.sealing)
        verified <- MasterKeyRotation.verify(server.env.database, server.env.sealing)
      yield
        assertEquals(summary.total, 3)
        assertEquals(verified.total, 3)
    }
  }

  test("an empty database rotates cleanly") {
    val config = DatabaseConfig.sqliteAt(
      Files.createTempDirectory("pds-rotate").resolve("pds.sqlite3"))
    Database.resource(config).use { database =>
      val current = Sealing.fromBase64(Sealing.generate()).toOption.get
      val next = Sealing.fromBase64(Sealing.generate()).toOption.get
      for
        _ <- Migrations.run(database)
        summary <- MasterKeyRotation.run(database, current, next)
        verified <- MasterKeyRotation.verify(database, next)
      yield
        assertEquals(summary.total, 0)
        assertEquals(verified.total, 0)
    }
  }
