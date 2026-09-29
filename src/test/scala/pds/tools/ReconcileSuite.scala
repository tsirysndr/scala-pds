package pds.tools

import cats.effect.{IO, Ref}
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import pds.TestEnv
import pds.TestEnv.*
import pds.crypto.{Curve, PrivateKey}
import pds.identity.Plc

/** A scripted `did:plc` directory, so an operation signed elsewhere can be put
  * in front of the server the way a recovery fork would arrive.
  */
class ReconcileSuite extends munit.CatsEffectSuite:
  private val credentials = Json.obj(
    "handle" -> Json.fromString("alice.pds.example.com"),
    "email" -> Json.fromString("alice@example.com"),
    "password" -> Json.fromString("correct horse battery")
  )

  private def scripted(log: Ref[IO, Map[String, Json]]) =
    TestEnv.routes {
      case request @ POST -> Root / did if did.startsWith("did:plc:") =>
        request.as[Json].flatMap(operation => log.update(_.updated(did, operation)))
          .as(Response[IO](Status.Ok))
      case GET -> Root / did / "log" / "last" =>
        log.get.map(_.get(did)
          .fold(Response[IO](Status.NotFound))(Response[IO](Status.Ok).withEntity(_)))
    }

  private def hosted(log: Ref[IO, Map[String, Json]]) =
    TestEnv.harness(Map("PDS_DID_METHOD" -> "plc"), scripted(log))

  private def register(server: Harness) =
    server.json(post("/xrpc/com.atproto.server.createAccount", credentials)).map { (_, body) =>
      body.hcursor.get[String]("did").toOption.get
    }

  private def signedIn(server: Harness) =
    server.json(post("/xrpc/com.atproto.server.createAccount", credentials)).map { (_, body) =>
      (body.hcursor.get[String]("accessJwt").toOption.get,
        body.hcursor.get[String]("did").toOption.get)
    }

  private def confirmed(server: Harness, did: String) =
    server.env.database.read(connection =>
      pds.storage.Sql.first(connection,
        "SELECT plc_confirmed FROM account_keys WHERE did = ?", did)(_.bool("plc_confirmed")))

  /** Replaces the log head the way a holder of another rotation key would. */
  private def fork(
      log: Ref[IO, Map[String, Json]],
      did: String
  )(change: Plc.Operation => Plc.Operation): IO[Unit] =
    log.get.flatMap { entries =>
      val current = Plc.parse(entries(did)).fold(message => throw new Exception(message), identity)
      val next = change(Plc.update(current, Plc.cid(current))(identity))
        .sign(PrivateKey.generate(Curve.K256))
      log.update(_.updated(did, next.json))
    }

  private def account(server: Harness, did: String) =
    server.env.database.read(connection => pds.accounts.Accounts.require(connection, did))

  test("a managed identity that matches the directory shows no drift") {
    for
      log <- Ref.of[IO, Map[String, Json]](Map.empty)
      result <- hosted(log).use { server =>
        register(server).flatMap(_ => Reconcile.run(server.env, None, repair = false))
      }
    yield
      assertEquals(result.checked, 1)
      assertEquals(result.drift, Vector.empty)
  }

  test("keys replaced by an operation signed elsewhere are reported, not repaired") {
    for
      log <- Ref.of[IO, Map[String, Json]](Map.empty)
      result <- hosted(log).use { server =>
        for
          did <- register(server)
          stranger = PrivateKey.generate(Curve.K256)
          _ <- fork(log, did)(operation => operation.copy(
            verificationMethods = Map("atproto" -> stranger.publicKey.didKey),
            rotationKeys = Vector(stranger.publicKey.didKey)))
          reconciled <- Reconcile.run(server.env, None, repair = true)
        yield reconciled
      }
    yield
      assertEquals(result.drift.map(_.kind).sorted, Vector("rotation-key", "signing-key"))
      assertEquals(result.drift.map(_.repaired), Vector(false, false))
      assertEquals(result.outstanding.size, 2)
  }

  test("a handle changed in the directory is adopted when repairing") {
    for
      log <- Ref.of[IO, Map[String, Json]](Map.empty)
      result <- hosted(log).use { server =>
        for
          did <- register(server)
          _ <- fork(log, did)(_.copy(alsoKnownAs = Vector("at://renamed.pds.example.com")))
          reported <- Reconcile.run(server.env, None, repair = false)
          repaired <- Reconcile.run(server.env, None, repair = true)
          after <- account(server, did)
        yield (reported, repaired, after.handle)
      }
    yield
      assertEquals(result._1.drift.map(_.kind), Vector("handle"))
      assertEquals(result._1.drift.map(_.repaired), Vector(false))
      assertEquals(result._2.drift.map(_.repaired), Vector(true))
      assertEquals(result._3, "renamed.pds.example.com")
  }

  test("an identity pointed at another server is deactivated when repairing") {
    for
      log <- Ref.of[IO, Map[String, Json]](Map.empty)
      result <- hosted(log).use { server =>
        for
          did <- register(server)
          _ <- fork(log, did)(_.copy(services = Map(
            "atproto_pds" -> ("AtprotoPersonalDataServer", "https://elsewhere.example.com"))))
          repaired <- Reconcile.run(server.env, None, repair = true)
          after <- account(server, did)
        yield (repaired, after.active)
      }
    yield
      assertEquals(result._1.drift.map(_.kind), Vector("endpoint"))
      assertEquals(result._1.drift.map(_.repaired), Vector(true))
      assertEquals(result._2, false)
  }

  test("an identity the directory has lost is deactivated when repairing") {
    for
      log <- Ref.of[IO, Map[String, Json]](Map.empty)
      result <- hosted(log).use { server =>
        for
          did <- register(server)
          _ <- log.set(Map.empty)
          repaired <- Reconcile.run(server.env, None, repair = true)
          after <- account(server, did)
        yield (repaired, after.active)
      }
    yield
      assertEquals(result._1.drift.map(_.kind), Vector("absent"))
      assertEquals(result._1.drift.map(_.repaired), Vector(true))
      assertEquals(result._2, false)
  }

  test("submitting an operation that hands the identity away does not confirm the keys") {
    for
      log <- Ref.of[IO, Map[String, Json]](Map.empty)
      result <- hosted(log).use { server =>
        for
          auth <- signedIn(server)
          (access, did) = auth
          current <- log.get.map(entries =>
            Plc.parse(entries(did)).fold(message => fail(message), identity))
          stranger = PrivateKey.generate(Curve.K256)
          taken = Plc.update(current, Plc.cid(current))(operation => operation.copy(
            verificationMethods = Map("atproto" -> stranger.publicKey.didKey))).sign(stranger)
          submitted <- server.json(authorized(
            post("/xrpc/com.atproto.identity.submitPlcOperation",
              Json.obj("operation" -> taken.json)), access))
          after <- confirmed(server, did)
        yield (submitted._1, after)
      }
    yield
      assertEquals(result._1, Status.Ok)
      assertEquals(result._2, Some(false))
  }
