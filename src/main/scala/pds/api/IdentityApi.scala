package pds.api

import cats.effect.IO
import io.circe.Json
import org.http4s.{Request, Response, Status}
import pds.{Env, XrpcError}
import pds.accounts.{Accounts, Email}
import pds.crypto.{Curve, PrivateKey, PublicKey}
import pds.firehose.Events
import pds.identity.{DidDocument, HandleAuthority, Plc, PlcDirectory}
import pds.protocol.Syntax
import pds.repo.RepoStore
import pds.storage.Sql

object IdentityApi:
  def endpoints(env: Env): Map[String, Endpoint] = Map(
    "com.atproto.identity.resolveHandle" -> Endpoint.query(resolveHandle(env, _)),
    "com.atproto.identity.resolveDid" -> Endpoint.query(resolveDid(env, _)),
    "com.atproto.identity.resolveIdentity" -> Endpoint.query(resolveIdentity(env, _)),
    "com.atproto.identity.refreshIdentity" -> Endpoint.procedure(refreshIdentity(env, _)),
    "com.atproto.identity.updateHandle" -> Endpoint.procedure(updateHandle(env, _)),
    "com.atproto.identity.getRecommendedDidCredentials" -> Endpoint.query(recommended(env, _)),
    "com.atproto.identity.requestPlcOperationSignature" -> Endpoint.procedure(requestSignature(env, _)),
    "com.atproto.identity.signPlcOperation" -> Endpoint.procedure(signOperation(env, _)),
    "com.atproto.identity.submitPlcOperation" -> Endpoint.procedure(submitOperation(env, _))
  )

  private def resolveHandle(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      handle <- IO.pure(Syntax.normalizeHandle(Xrpc.requireParam(request, "handle")))
      _ <- IO.raiseUnless(Syntax.isHandle(handle))(
        XrpcError.invalidRequest("handle must be a domain name"))
      did <- env.resolver.resolveHandle(handle)
      found <- IO.fromOption(did)(
        XrpcError.named(Status.BadRequest, "HandleNotFound", "Handle could not be resolved"))
      response <- Xrpc.ok(Json.obj("did" -> Json.fromString(found)))
    yield response

  private def resolveDid(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      did <- IO.pure(Xrpc.requireParam(request, "did"))
      document <- localDocument(env, did).flatMap {
        case found @ Some(_) => IO.pure(found)
        case None            => env.resolver.resolveDid(did).map(_.map(_.raw))
      }
      found <- IO.fromOption(document)(
        XrpcError.named(Status.BadRequest, "DidNotFound", "DID could not be resolved"))
      response <- Xrpc.ok(Json.obj("didDoc" -> found))
    yield response

  private def resolveIdentity(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      identifier <- IO.pure(Xrpc.requireParam(request, "identifier"))
      did <- if Syntax.isDid(identifier) then IO.pure(Some(identifier))
        else env.resolver.resolveHandle(identifier)
      resolved <- IO.fromOption(did)(
        XrpcError.named(Status.BadRequest, "HandleNotFound", "Identity could not be resolved"))
      document <- localDocument(env, resolved).flatMap {
        case found @ Some(_) => IO.pure(found)
        case None            => env.resolver.resolveDid(resolved).map(_.map(_.raw))
      }
      found <- IO.fromOption(document)(
        XrpcError.named(Status.BadRequest, "DidNotFound", "DID could not be resolved"))
      response <- Xrpc.ok(Json.obj(
        "did" -> Json.fromString(resolved), "didDoc" -> found))
    yield response

  /** Accounts hosted here are answered from local state, not the network. */
  private def localDocument(env: Env, did: String): IO[Option[Json]] =
    env.database.read { connection =>
      Accounts.byDid(connection, did).filter(_.status != "deleted").map { account =>
        val key = RepoStore.signingKey(connection, account.did, env.sealing)
        DidDocument.build(account.did, account.handle, key.publicKey, env.config.publicUrl)
      }
    }

  private def refreshIdentity(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      body <- Xrpc.body(request)
      identifier = Xrpc.requireField(body, "identifier")
      _ <- env.resolver.invalidate(identifier)
      _ <- env.database.transact { connection =>
        Accounts.byIdentifier(connection, identifier).foreach(account =>
          Events.identity(connection, account.did, Some(account.handle)))
      }
      response <- Xrpc.empty
    yield response

  private def updateHandle(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      body <- Xrpc.body(request)
      handle = Syntax.normalizeHandle(Xrpc.requireField(body, "handle"))
      _ <- IO.raiseUnless(Syntax.isHandle(handle))(
        XrpcError.named(Status.BadRequest, "InvalidHandle", "Handle is not a valid domain name"))
      hosted = handle.endsWith(s".${env.config.userDomain}")
      _ <-
        if hosted then HandleAuthority.ensureAvailable(env, handle, Some(session.did))
        else verifyExternal(env, handle, session.did)
      now <- env.now
      _ <- env.database.transact { connection =>
        val account = Accounts.requireActive(connection, session.did)
        if account.handle != handle then
          if !Accounts.handleAvailable(connection, handle) then
            throw XrpcError.named(Status.BadRequest, "HandleNotAvailable",
              "Handle is already taken")
          Accounts.setHandle(connection, account.did, handle)
          Sql.update(connection,
            "INSERT INTO handle_reservations(handle, did, created_at) VALUES (?, ?, ?)",
            handle, account.did, now)
          Events.identity(connection, account.did, Some(handle))
      }
      _ <- submitHandleUpdate(env, session.did, handle)
      response <- Xrpc.empty
    yield response

  private def verifyExternal(env: Env, handle: String, did: String): IO[Unit] =
    env.resolver.resolveHandle(handle).flatMap { resolved =>
      IO.raiseUnless(resolved.contains(did))(
        XrpcError.named(Status.BadRequest, "InvalidHandle",
          "That domain does not point at this account"))
    }

  /** Publishes the new handle in the DID document when we control the DID. */
  private def submitHandleUpdate(env: Env, did: String, handle: String): IO[Unit] =
    if !did.startsWith("did:plc:") then IO.unit
    else
      for
        rotation <- env.database.read(connection => rotationKey(env, connection, did))
        directory = PlcDirectory(env.net, env.config.plcDirectory)
        last <- directory.lastOperation(did)
        _ <- (rotation, last) match
          case (Some(key), Some((previous, cid))) =>
            val next = Plc.update(previous, cid)(
              _.copy(alsoKnownAs = Vector(s"at://$handle"))).sign(key)
            directory.submit(did, next) *> env.resolver.invalidate(did)
          case _ => IO.unit
      yield ()

  private def rotationKey(env: Env, connection: java.sql.Connection, did: String): Option[PrivateKey] =
    Sql.first(connection, "SELECT rotation_sealed FROM account_keys WHERE did = ?", did)(
      _.bytesOpt("rotation_sealed")).flatten.flatMap(env.sealing.openKey(Curve.K256, _))

  private def recommended(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      credentials <- env.database.read { connection =>
        val account = Accounts.require(connection, session.did)
        val signing = RepoStore.signingKey(connection, account.did, env.sealing)
        val rotation = Sql.first(connection,
          "SELECT rotation_public FROM account_keys WHERE did = ?", account.did)(
          _.stringOpt("rotation_public")).flatten
        Json.obj(
          "rotationKeys" -> Json.arr(rotation.toVector.map(Json.fromString)*),
          "alsoKnownAs" -> Json.arr(Json.fromString(s"at://${account.handle}")),
          "verificationMethods" -> Json.obj(
            "atproto" -> Json.fromString(signing.publicKey.didKey)),
          "services" -> Json.obj("atproto_pds" -> Json.obj(
            "type" -> Json.fromString("AtprotoPersonalDataServer"),
            "endpoint" -> Json.fromString(env.config.publicUrl)))
        )
      }
      response <- Xrpc.ok(credentials)
    yield response

  private def requestSignature(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      now <- env.now
      _ <- env.database.transact { connection =>
        Email.requireEnabled(env)
        Email.issue(connection, Accounts.requireActive(connection, session.did),
          "plc-operation", now)
      }
      response <- Xrpc.empty
    yield response

  /** Signs an owner-supplied PLC operation with the account rotation key. */
  private def signOperation(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      body <- Xrpc.body(request)
      now <- env.now
      directory = PlcDirectory(env.net, env.config.plcDirectory)
      last <- directory.lastOperation(session.did)
      previous <- IO.fromOption(last)(
        XrpcError.invalidRequest("The directory has no operation log for this account"))
      signed <- env.database.transact { connection =>
        val account = Accounts.requireActive(connection, session.did)
        if env.config.emailEnabled then
          Email.consume(connection, "plc-operation", Xrpc.requireField(body, "token"),
            account.did, account.email.getOrElse(""), now)
        val key = rotationKey(env, connection, account.did)
          .getOrElse(throw XrpcError.invalidRequest("This account has no managed rotation key"))
        val rotationKeys = body.hcursor.get[Vector[String]]("rotationKeys").toOption
        val aliases = body.hcursor.get[Vector[String]]("alsoKnownAs").toOption
        val methods = body.hcursor.downField("verificationMethods").as[Map[String, String]].toOption
        val services = body.hcursor.downField("services").focus
          .flatMap(_.asObject).map(_.toMap.flatMap { (name, value) =>
            for
              kind <- value.hcursor.get[String]("type").toOption
              endpoint <- value.hcursor.get[String]("endpoint").toOption
            yield name -> (kind, endpoint)
          })
        Plc.update(previous._1, previous._2)(operation => operation.copy(
          rotationKeys = rotationKeys.getOrElse(operation.rotationKeys),
          alsoKnownAs = aliases.getOrElse(operation.alsoKnownAs),
          verificationMethods = methods.getOrElse(operation.verificationMethods),
          services = services.getOrElse(operation.services)
        )).sign(key)
      }
      response <- Xrpc.ok(Json.obj("operation" -> signed.json))
    yield response

  private def submitOperation(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      body <- Xrpc.body(request)
      json <- IO.fromOption(body.hcursor.downField("operation").focus)(
        XrpcError.invalidRequest("operation is required"))
      operation <- IO.fromEither(Plc.parse(json).left.map(XrpcError.invalidRequest))
      _ <- PlcDirectory(env.net, env.config.plcDirectory).submit(session.did, operation)
      _ <- env.resolver.invalidate(session.did)
      _ <- env.database.transact { connection =>
        // A recovery operation signed outside this server can hand the identity
        // to another key, so confirmation follows what was actually published.
        val local = RepoStore.signingKey(connection, session.did, env.sealing).publicKey.didKey
        Sql.update(connection, "UPDATE account_keys SET plc_confirmed = ? WHERE did = ?",
          operation.verificationMethods.get("atproto").contains(local), session.did)
        Events.identity(connection, session.did,
          operation.alsoKnownAs.headOption.map(_.stripPrefix("at://")))
      }
      response <- Xrpc.empty
    yield response
