package pds.accounts

import cats.effect.IO
import io.circe.Json
import java.sql.Connection
import pds.{Env, XrpcError}
import pds.crypto.{Curve, Passwords, PrivateKey, PublicKey}
import pds.firehose.Events
import pds.identity.{DidDocument, Plc, PlcDirectory}
import pds.protocol.Syntax
import pds.repo.RepoStore
import pds.storage.Sql

final case class Registration(
    handle: String,
    email: Option[String],
    password: Option[String],
    inviteCode: Option[String],
    did: Option[String],
    recoveryKey: Option[String],
    verificationCode: Option[String]
)

object Register:
  private val reservedPrefixes = Set(
    "admin", "administrator", "api", "account", "atproto", "did", "help", "localhost",
    "moderation", "oauth", "pds", "root", "security", "support", "system", "www", "xrpc")

  final case class Result(account: Account, tokens: Tokens0, didDoc: Json)

  def run(env: Env, request: Registration): IO[Result] =
    for
      handle <- IO.fromEither(normalizeHandle(env, request.handle))
      email <- IO.fromEither(validateEmail(env, request))
      password <- IO.fromEither(validatePassword(request))
      _ <- ensureAvailable(env, handle)
      signing = PrivateKey.generate(Curve.K256)
      rotation = PrivateKey.generate(Curve.K256)
      recovery <- IO.fromEither(parseRecoveryKey(request.recoveryKey))
      identity <- provision(env, request, handle, signing, rotation, recovery)
      result <- persist(env, request, identity, handle, email, password, signing, rotation)
    yield result

  private def normalizeHandle(env: Env, value: String): Either[XrpcError, String] =
    val handle = Syntax.normalizeHandle(value.trim)
    val suffix = s".${env.config.userDomain}"
    def invalid(message: String) =
      Left(XrpcError.named(org.http4s.Status.BadRequest, "InvalidHandle", message))
    if !Syntax.isHandle(handle) then invalid("Handle is not a valid domain name")
    else if !handle.endsWith(suffix) then
      invalid(s"Handle must end with $suffix on this server")
    else
      val name = handle.dropRight(suffix.length)
      if name.contains('.') then invalid("Handle may not contain extra subdomains")
      else if name.length < 3 then invalid("Handle must be at least three characters")
      else if reservedPrefixes.contains(name) then invalid("Handle is reserved")
      else Right(handle)

  private def validateEmail(env: Env, request: Registration): Either[XrpcError, Option[String]] =
    request.email.map(_.trim.toLowerCase) match
      case Some(email) if email.length <= 320 && email.matches("""[^@\s]+@[^@\s.]+(\.[^@\s.]+)+""") =>
        Right(Some(email))
      case Some(_) => Left(XrpcError.named(org.http4s.Status.BadRequest, "InvalidEmail",
        "Email address is not valid"))
      case None if env.config.emailEnabled =>
        Left(XrpcError.invalidRequest("An email address is required"))
      case None => Right(None)

  private def validatePassword(request: Registration): Either[XrpcError, Option[String]] =
    request.password match
      case Some(password) if password.length >= 8 && password.length <= 1024 => Right(Some(password))
      case Some(_) => Left(XrpcError.named(org.http4s.Status.BadRequest, "InvalidPassword",
        "Passwords need at least 8 characters"))
      case None => Right(None)

  private def parseRecoveryKey(value: Option[String]): Either[XrpcError, Option[PublicKey]] =
    value match
      case None => Right(None)
      case Some(didKey) => PublicKey.fromDidKey(didKey.trim).map(Some.apply)
        .toRight(XrpcError.invalidRequest("Recovery key must be a did:key value"))

  private def ensureAvailable(env: Env, handle: String): IO[Unit] =
    env.database.read(connection => Accounts.handleAvailable(connection, handle)).flatMap { free =>
      IO.raiseUnless(free)(XrpcError.named(org.http4s.Status.BadRequest, "HandleNotAvailable",
        "Handle is already taken"))
    }

  private final case class Identity(did: String, operation: Option[Plc.Operation])

  /** Derives the account DID. `did:plc` operations are submitted before any
    * account row exists, so a directory failure cannot leave a half-account.
    */
  private def provision(
      env: Env,
      request: Registration,
      handle: String,
      signing: PrivateKey,
      rotation: PrivateKey,
      recovery: Option[PublicKey]
  ): IO[Identity] =
    request.did match
      case Some(existing) => adopt(env, existing).as(Identity(existing, None))
      case None if env.config.didMethod == "web" =>
        // AT Protocol allows only hostname did:web, with no port or path, so
        // the account's DID is its own handle's domain.
        IO.pure(Identity(s"did:web:$handle", None))
      case None =>
        val keys = recovery.toVector :+ rotation.publicKey
        val genesis = Plc.genesis(handle, signing.publicKey, keys, env.config.publicUrl)
          .sign(rotation)
        val did = Plc.did(genesis)
        PlcDirectory(env.net, env.config.plcDirectory).submit(did, genesis)
          .as(Identity(did, Some(genesis)))

  private def adopt(env: Env, did: String): IO[Unit] =
    for
      _ <- IO.raiseUnless(Syntax.isDid(did))(XrpcError.invalidRequest("Invalid DID"))
      document <- env.resolver.resolveDid(did)
      resolved <- IO.fromOption(document)(
        XrpcError.invalidRequest("Supplied DID could not be resolved"))
      _ <- IO.raiseUnless(resolved.pdsEndpoint.contains(env.config.publicUrl))(
        XrpcError.invalidRequest("Supplied DID does not point at this server"))
    yield ()

  private def persist(
      env: Env,
      request: Registration,
      identity: Identity,
      handle: String,
      email: Option[String],
      password: Option[String],
      signing: PrivateKey,
      rotation: PrivateKey
  ): IO[Result] =
    env.now.flatMap { now =>
      env.database.transact { connection =>
        if !Accounts.handleAvailable(connection, handle) then
          throw XrpcError.named(org.http4s.Status.BadRequest, "HandleNotAvailable",
            "Handle is already taken")
        if email.exists(value => Accounts.byEmail(connection, value).isDefined) then
          throw XrpcError.named(org.http4s.Status.BadRequest, "HandleNotAvailable",
            "That username, email, or account already exists")
        if Accounts.byDid(connection, identity.did).isDefined then
          throw XrpcError.invalidRequest("Account already exists")
        if env.config.inviteRequired then
          val code = request.inviteCode.getOrElse(
            throw XrpcError.named(org.http4s.Status.BadRequest, "InvalidInviteCode",
              "An invite code is required"))
          Accounts.insert(connection, identity.did, handle, email,
            password.map(Passwords.hash), "active", now)
          Invites.consume(connection, code, identity.did, now)
        else
          Accounts.insert(connection, identity.did, handle, email,
            password.map(Passwords.hash), "active", now)
        Sql.update(connection,
          """INSERT INTO account_keys(did, signing_curve, signing_public, signing_sealed,
             rotation_public, rotation_sealed, plc_confirmed, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
          identity.did, signing.curve.name, signing.publicKey.didKey,
          env.sealing.sealKey(signing), Some(rotation.publicKey.didKey),
          Some(env.sealing.sealKey(rotation)), identity.operation.isDefined, now)
        Sql.update(connection,
          "INSERT INTO handle_reservations(handle, did, created_at) VALUES (?, ?, ?)",
          handle, identity.did, now)
        val head = RepoStore.create(connection, identity.did, signing)
        Events.identity(connection, identity.did, Some(handle))
        Events.account(connection, identity.did, active = true, None)
        Events.sync(connection, identity.did, head,
          Map(head.commit.cid -> head.commit.bytes))
        val account = Accounts.require(connection, identity.did)
        val tokens = Sessions.issue(env, connection, account, Credential.Access, None, now)
        Result(account, tokens,
          DidDocument.build(identity.did, handle, signing.publicKey, env.config.publicUrl))
      }
    }
