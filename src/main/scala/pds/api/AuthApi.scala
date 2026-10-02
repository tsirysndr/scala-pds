package pds.api

import cats.effect.IO
import io.circe.Json
import org.http4s.{Request, Response}
import pds.accounts.{Accounts, Credential, Session, Sessions}
import pds.security.{Passkeys, Totp}
import pds.{Env, XrpcError}

/** `social.rocksky.auth.*`: two-factor and passkeys as XRPC methods.
  *
  * The atproto lexicon defines neither, so each PDS grew its own browser
  * interface and no single client could drive all of them. These endpoints put
  * the existing factor and passkey machinery behind one bearer-authorised
  * contract, which also means a gateway routes them to the account's own PDS
  * with no special handling.
  *
  * A password is required to add or remove any way of signing in. An access
  * token proves the session, not the owner, and a stolen session must not be
  * able to take the second factor off or register a credential of its own.
  */
object AuthApi:
  def endpoints(env: Env): Map[String, Endpoint] = Map(
    "social.rocksky.auth.getTwoFactor" -> Endpoint.query(getTwoFactor(env, _)),
    "social.rocksky.auth.beginTwoFactor" -> Endpoint.procedure(beginTwoFactor(env, _)),
    "social.rocksky.auth.confirmTwoFactor" -> Endpoint.procedure(confirmTwoFactor(env, _)),
    "social.rocksky.auth.disableTwoFactor" -> Endpoint.procedure(disableTwoFactor(env, _)),
    "social.rocksky.auth.regenerateRecoveryCodes" ->
      Endpoint.procedure(regenerateRecoveryCodes(env, _)),
    "social.rocksky.auth.listPasskeys" -> Endpoint.query(listPasskeys(env, _)),
    "social.rocksky.auth.beginPasskeyRegistration" ->
      Endpoint.procedure(beginPasskeyRegistration(env, _)),
    "social.rocksky.auth.finishPasskeyRegistration" ->
      Endpoint.procedure(finishPasskeyRegistration(env, _)),
    "social.rocksky.auth.deletePasskey" -> Endpoint.procedure(deletePasskey(env, _)),
    "social.rocksky.auth.beginPasskeyLogin" -> Endpoint.procedure(beginPasskeyLogin(env, _)),
    "social.rocksky.auth.finishPasskeyLogin" -> Endpoint.procedure(finishPasskeyLogin(env, _))
  )

  /** App passwords are issued to clients, so they are not the owner proving who
    * they are and must not reach account security.
    */
  private def owner(env: Env, request: Request[IO]): IO[Session] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
    yield session

  private def checkPassword(connection: java.sql.Connection, did: String, supplied: String): Unit =
    val account = Accounts.require(connection, did)
    if !Accounts.verifyPassword(Some(account), supplied) then
      throw XrpcError.named(org.http4s.Status.Unauthorized, "InvalidCredentials",
        "Incorrect password")

  /** A ceremony is claimed with both halves: the challenge handle and the token
    * the server bound to it. There is no cookie to hold the token, so it travels
    * inside the opaque requestId and the pair is what authorises the claim.
    */
  private def requestId(id: String, token: String): String = s"$id.$token"

  /** The credential with `clientExtensionResults` filled in when absent.
    *
    * The WebAuthn library's credential model refuses to construct without the
    * field, yet a client that requested no extensions reasonably leaves it out.
    * Without this, such a credential is refused before a signature is ever
    * looked at, as an invalid passkey.
    */
  private def credentialJson(credential: Json): String =
    credential.mapObject { o =>
      if o.contains("clientExtensionResults") then o
      else o.add("clientExtensionResults", Json.obj())
    }.noSpaces

  /** The WebAuthn options themselves.
    *
    * `toCredentialsCreateJson` and `toCredentialsGetJson` already wrap their
    * result in `publicKey`, ready to hand to `navigator.credentials`. The
    * contract carries the options alone, so wrapping again would nest it twice
    * and a client reading `publicKey.challenge` would find nothing.
    */
  private def credentialOptions(options: Json): Json =
    options.hcursor.downField("publicKey").focus.getOrElse(options)

  private def splitRequestId(value: String): (String, String) =
    value.split("\\.", 2) match
      // An empty half is not a usable half: refuse rather than pass it on.
      case Array(id, token) if id.nonEmpty && token.nonEmpty => (id, token)
      case _ =>
        throw XrpcError.named(org.http4s.Status.BadRequest, "RequestExpired",
          "That passkey request is not valid")

  // --- signing in with a passkey -------------------------------------------
  // Unauthenticated by design: these are how a session begins.

  private def beginPasskeyLogin(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      _ <- IO.raiseUnless(Passkeys.available(env))(
        XrpcError.named(org.http4s.Status.NotImplemented, "NotSupported",
          "This server is not configured for passkeys"))
      input <- Xrpc.body(request)
      now <- env.now
      token <- IO(pds.crypto.Hash.randomBase32(32))
      ceremony <- env.database.transact { connection =>
        // A challenge is offered per account here, so the identifier is
        // required. Saying the account is unknown discloses nothing: in atproto
        // a handle is public, and resolveHandle already answers that question
        // for anyone who asks.
        val identifier = Xrpc.requireField(input, "identifier").toLowerCase
        val account = Accounts.byIdentifier(connection, identifier).getOrElse(
          throw XrpcError.named(org.http4s.Status.Unauthorized, "AccountNotFound",
            "No passkey is registered for that account"))
        Passkeys.beginAuthentication(env, connection, account.did, token, now)
      }
      response <- Xrpc.ok(Json.obj(
        "requestId" -> Json.fromString(requestId(ceremony.id, token)),
        "publicKey" -> credentialOptions(ceremony.options)))
    yield response

  private def finishPasskeyLogin(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      input <- Xrpc.body(request)
      now <- env.now
      credential <- IO.fromOption(input.hcursor.downField("credential").focus)(
        XrpcError.invalidRequest("credential is required"))
      body <- env.database.transact { connection =>
        val (id, token) = splitRequestId(Xrpc.requireField(input, "requestId"))
        val did = Passkeys.finishAuthentication(env, connection, id, token,
          credentialJson(credential), now)
        val account = Accounts.requireActive(connection, did)

        // A user-verified passkey is already two factors: the device, and the
        // PIN or biometric that unlocked it, both checked in the assertion just
        // verified. No code is asked on top.
        val tokens = Sessions.issue(env, connection, account, Credential.Access, None, now)
        Sessions.describe(env, account, Credential.Access).deepMerge(Json.obj(
          "accessJwt" -> Json.fromString(tokens.accessJwt),
          "refreshJwt" -> Json.fromString(tokens.refreshJwt)))
      }
      response <- Xrpc.ok(body)
    yield response

  private def getTwoFactor(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- owner(env, request)
      body <- env.database.read { connection =>
        Json.obj(
          "state" -> Json.fromString(Totp.state(connection, session.did)),
          "recoveryRemaining" -> Json.fromInt(Totp.remaining(connection, session.did)))
      }
      response <- Xrpc.ok(body)
    yield response

  private def beginTwoFactor(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- owner(env, request)
      input <- Xrpc.body(request)
      now <- env.now
      enrollment <- env.database.transact { connection =>
        checkPassword(connection, session.did, Xrpc.requireField(input, "password"))
        val account = Accounts.require(connection, session.did)
        Totp.begin(env, connection, session.did, account.handle, now)
      }
      response <- Xrpc.ok(Json.obj(
        "state" -> Json.fromString("pending"),
        "secret" -> Json.fromString(enrollment.secret),
        "uri" -> Json.fromString(enrollment.uri)))
    yield response

  private def confirmTwoFactor(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- owner(env, request)
      input <- Xrpc.body(request)
      now <- env.now
      codes <- env.database.transact(connection =>
        Totp.confirm(env, connection, session.did, Xrpc.requireField(input, "code"), now))
      response <- Xrpc.ok(Json.obj(
        "state" -> Json.fromString("enabled"),
        "recoveryCodes" -> Json.arr(codes.map(Json.fromString)*)))
    yield response

  private def disableTwoFactor(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- owner(env, request)
      input <- Xrpc.body(request)
      now <- env.now
      _ <- env.database.transact { connection =>
        checkPassword(connection, session.did, Xrpc.requireField(input, "password"))
        Totp.disable(env, connection, session.did, Xrpc.requireField(input, "code"), now)
      }
      response <- Xrpc.ok(Json.obj("state" -> Json.fromString("disabled")))
    yield response

  private def regenerateRecoveryCodes(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- owner(env, request)
      input <- Xrpc.body(request)
      now <- env.now
      codes <- env.database.transact { connection =>
        checkPassword(connection, session.did, Xrpc.requireField(input, "password"))
        Totp.regenerate(env, connection, session.did, Xrpc.requireField(input, "code"), now)
      }
      response <- Xrpc.ok(Json.obj(
        "recoveryCodes" -> Json.arr(codes.map(Json.fromString)*)))
    yield response

  private def listPasskeys(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- owner(env, request)
      passkeys <- env.database.read(connection => Passkeys.list(connection, session.did))
      response <- Xrpc.ok(Json.obj("passkeys" -> Json.arr(passkeys*)))
    yield response

  private def beginPasskeyRegistration(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- owner(env, request)
      _ <- IO.raiseUnless(Passkeys.available(env))(
        XrpcError.named(org.http4s.Status.NotImplemented, "NotSupported",
          "This server is not configured for passkeys"))
      input <- Xrpc.body(request)
      now <- env.now
      // The server picks the token and returns it inside the opaque requestId;
      // beginRegistration only stores its digest, so it cannot hand it back.
      token <- IO(pds.crypto.Hash.randomBase32(32))
      ceremony <- env.database.transact { connection =>
        checkPassword(connection, session.did, Xrpc.requireField(input, "password"))
        if Totp.enabled(connection, session.did) then
          Totp.verify(env, connection, session.did, Xrpc.requireField(input, "code"), now)
        val label = input.hcursor.downField("name").as[String].toOption
          .map(_.trim).filter(_.nonEmpty).getOrElse("passkey")
        Passkeys.beginRegistration(env, connection, session.did, token, label, now)
      }
      response <- Xrpc.ok(Json.obj(
        "requestId" -> Json.fromString(requestId(ceremony.id, token)),
        "publicKey" -> credentialOptions(ceremony.options)))
    yield response

  private def finishPasskeyRegistration(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- owner(env, request)
      input <- Xrpc.body(request)
      now <- env.now
      credential <- IO.fromOption(input.hcursor.downField("credential").focus)(
        XrpcError.invalidRequest("credential is required"))
      passkey <- env.database.transact { connection =>
        val (id, token) = splitRequestId(Xrpc.requireField(input, "requestId"))
        Passkeys.finishRegistration(env, connection, id, token, credentialJson(credential), now)
      }
      response <- Xrpc.ok(Json.obj("passkey" -> passkey))
    yield response

  private def deletePasskey(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- owner(env, request)
      input <- Xrpc.body(request)
      _ <- env.database.transact { connection =>
        checkPassword(connection, session.did, Xrpc.requireField(input, "password"))
        Passkeys.remove(connection, session.did, Xrpc.requireField(input, "id"))
      }
      response <- Xrpc.empty
    yield response
