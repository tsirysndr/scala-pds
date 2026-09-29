package pds.api

import cats.effect.IO
import io.circe.Json
import org.http4s.{Request, Response, Status}
import pds.{Env, XrpcError}
import pds.accounts.*
import pds.crypto.{Curve, Encoding, Jwt, PrivateKey}
import pds.firehose.Events
import pds.identity.DidDocument
import pds.protocol.Syntax
import pds.repo.RepoStore
import pds.storage.Sql

object ServerApi:
  def endpoints(env: Env): Map[String, Endpoint] = Map(
    "com.atproto.server.describeServer" -> Endpoint.query(_ => describeServer(env)),
    "com.atproto.server.createAccount" -> Endpoint.procedure(createAccount(env, _)),
    "com.atproto.server.createSession" -> Endpoint.procedure(createSession(env, _)),
    "com.atproto.server.refreshSession" -> Endpoint.procedure(refreshSession(env, _)),
    "com.atproto.server.deleteSession" -> Endpoint.procedure(deleteSession(env, _)),
    "com.atproto.server.getSession" -> Endpoint.query(getSession(env, _)),
    "com.atproto.server.createAppPassword" -> Endpoint.procedure(createAppPassword(env, _)),
    "com.atproto.server.listAppPasswords" -> Endpoint.query(listAppPasswords(env, _)),
    "com.atproto.server.revokeAppPassword" -> Endpoint.procedure(revokeAppPassword(env, _)),
    "com.atproto.server.createInviteCode" -> Endpoint.procedure(createInviteCode(env, _)),
    "com.atproto.server.createInviteCodes" -> Endpoint.procedure(createInviteCodes(env, _)),
    "com.atproto.server.getAccountInviteCodes" -> Endpoint.query(accountInviteCodes(env, _)),
    "com.atproto.server.requestEmailConfirmation" -> Endpoint.procedure(requestEmail(env, _, "confirm-email")),
    "com.atproto.server.confirmEmail" -> Endpoint.procedure(confirmEmail(env, _)),
    "com.atproto.server.requestEmailUpdate" -> Endpoint.procedure(requestEmailUpdate(env, _)),
    "com.atproto.server.updateEmail" -> Endpoint.procedure(updateEmail(env, _)),
    "com.atproto.server.requestPasswordReset" -> Endpoint.procedure(requestPasswordReset(env, _)),
    "com.atproto.server.resetPassword" -> Endpoint.procedure(resetPassword(env, _)),
    "com.atproto.server.requestAccountDelete" -> Endpoint.procedure(requestEmail(env, _, "delete-account")),
    "com.atproto.server.deleteAccount" -> Endpoint.procedure(deleteAccount(env, _)),
    "com.atproto.server.deactivateAccount" -> Endpoint.procedure(deactivateAccount(env, _)),
    "com.atproto.server.activateAccount" -> Endpoint.procedure(activateAccount(env, _)),
    "com.atproto.server.checkAccountStatus" -> Endpoint.query(checkAccountStatus(env, _)),
    "com.atproto.server.getServiceAuth" -> Endpoint.query(getServiceAuth(env, _)),
    "com.atproto.server.reserveSigningKey" -> Endpoint.procedure(reserveSigningKey(env, _)),
    "com.atproto.temp.checkSignupQueue" -> Endpoint.query(_ =>
      Xrpc.ok(Json.obj("activated" -> Json.True)))
  )

  private def describeServer(env: Env): IO[Response[IO]] =
    Xrpc.ok(Json.obj(
      "did" -> Json.fromString(env.config.serviceDid),
      "availableUserDomains" -> Json.arr(env.config.availableUserDomains.map(Json.fromString)*),
      "inviteCodeRequired" -> Json.fromBoolean(env.config.inviteRequired),
      "phoneVerificationRequired" -> Json.False,
      "links" -> Json.obj(
        "privacyPolicy" -> env.config.privacyPolicyUrl.map(Json.fromString).getOrElse(Json.Null),
        "termsOfService" -> env.config.termsOfServiceUrl.map(Json.fromString).getOrElse(Json.Null)
      ).deepDropNullValues,
      "contact" -> Json.obj(
        "email" -> env.config.contactEmail.map(Json.fromString).getOrElse(Json.Null)
      ).deepDropNullValues
    ))

  private def createAccount(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      _ <- IO.raiseUnless(env.config.signupEnabled)(
        XrpcError.forbidden("This server is not accepting new accounts"))
      body <- Xrpc.body(request)
      created <- Register.run(env, Registration(
        handle = Xrpc.requireField(body, "handle"),
        email = Xrpc.field(body, "email"),
        password = Xrpc.field(body, "password"),
        inviteCode = Xrpc.field(body, "inviteCode"),
        did = Xrpc.field(body, "did"),
        recoveryKey = Xrpc.field(body, "recoveryKey"),
        verificationCode = Xrpc.field(body, "verificationCode")
      ))
      response <- Xrpc.ok(Json.obj(
        "accessJwt" -> Json.fromString(created.tokens.accessJwt),
        "refreshJwt" -> Json.fromString(created.tokens.refreshJwt),
        "handle" -> Json.fromString(created.account.handle),
        "did" -> Json.fromString(created.account.did),
        "didDoc" -> created.didDoc
      ))
    yield response

  private def createSession(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      body <- Xrpc.body(request)
      now <- env.now
      created <- env.database.transact(connection =>
        Sessions.create(env, connection, Xrpc.requireField(body, "identifier"),
          body.hcursor.get[String]("password").toOption.getOrElse(""), now))
      response <- Xrpc.ok(Sessions.describe(env, created.account, created.credential).deepMerge(
        Json.obj(
          "accessJwt" -> Json.fromString(created.tokens.accessJwt),
          "refreshJwt" -> Json.fromString(created.tokens.refreshJwt)
        )))
    yield response

  private def refreshSession(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      token <- Xrpc.refreshSession(env, request)
      now <- env.now
      created <- env.database.transact(connection => Sessions.refresh(env, connection, token._1, now))
      response <- Xrpc.ok(Json.obj(
        "accessJwt" -> Json.fromString(created.tokens.accessJwt),
        "refreshJwt" -> Json.fromString(created.tokens.refreshJwt),
        "handle" -> Json.fromString(created.account.handle),
        "did" -> Json.fromString(created.account.did),
        "active" -> Json.fromBoolean(created.account.active)
      ))
    yield response

  private def deleteSession(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      token <- Xrpc.refreshSession(env, request)
      now <- env.now
      _ <- env.database.transact(connection => Sessions.delete(env, connection, token._1, now))
      response <- Xrpc.empty
    yield response

  private def getSession(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      account <- Xrpc.account(env, session)
      response <- Xrpc.ok(Sessions.describe(env, account, session.credential).deepMerge(
        Json.obj("didDoc" -> Json.Null)).deepDropNullValues)
    yield response

  private def createAppPassword(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      body <- Xrpc.body(request)
      now <- env.now
      created <- env.database.transact(connection =>
        AppPasswords.create(connection, session.did, Xrpc.requireField(body, "name"),
          Xrpc.flag(body, "privileged").getOrElse(false), now))
      response <- Xrpc.ok(created)
    yield response

  private def listAppPasswords(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      passwords <- env.database.read(connection => AppPasswords.list(connection, session.did))
      response <- Xrpc.ok(Json.obj("passwords" -> Json.arr(passwords*)))
    yield response

  private def revokeAppPassword(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      body <- Xrpc.body(request)
      _ <- env.database.transact(connection =>
        AppPasswords.revoke(connection, session.did, Xrpc.requireField(body, "name")))
      response <- Xrpc.empty
    yield response

  private def createInviteCode(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      _ <- Xrpc.admin(env, request)
      body <- Xrpc.body(request)
      count = body.hcursor.get[Int]("useCount").toOption.filter(value => value >= 1 && value <= 1000)
        .getOrElse(throw XrpcError.invalidRequest("useCount must be between 1 and 1000"))
      now <- env.now
      code <- env.database.transact(connection =>
        Invites.create(connection, env.config.hostname, count,
          Xrpc.field(body, "forAccount").getOrElse("admin"), "admin", now))
      response <- Xrpc.ok(Json.obj("code" -> Json.fromString(code)))
    yield response

  private def createInviteCodes(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      _ <- Xrpc.admin(env, request)
      body <- Xrpc.body(request)
      count = body.hcursor.get[Int]("codeCount").toOption.filter(value => value >= 1 && value <= 100)
        .getOrElse(throw XrpcError.invalidRequest("codeCount must be between 1 and 100"))
      uses = body.hcursor.get[Int]("useCount").toOption.filter(value => value >= 1 && value <= 1000)
        .getOrElse(throw XrpcError.invalidRequest("useCount must be between 1 and 1000"))
      accounts = body.hcursor.get[Vector[String]]("forAccounts").getOrElse(Vector("admin"))
      now <- env.now
      created <- env.database.transact { connection =>
        accounts.map { account =>
          Json.obj(
            "account" -> Json.fromString(account),
            "codes" -> Json.arr((1 to count).toVector.map(_ =>
              Json.fromString(Invites.create(connection, env.config.hostname, uses, account,
                "admin", now)))*)
          )
        }
      }
      response <- Xrpc.ok(Json.obj("codes" -> Json.arr(created*)))
    yield response

  private def accountInviteCodes(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      codes <- env.database.read(connection => Invites.forAccount(connection, session.did))
      response <- Xrpc.ok(Json.obj("codes" -> Json.arr(codes*)))
    yield response

  private def requestEmail(env: Env, request: Request[IO], purpose: String): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      now <- env.now
      _ <- env.database.transact { connection =>
        Email.requireEnabled(env)
        Email.issue(connection, Accounts.requireActive(connection, session.did), purpose, now)
      }
      response <- Xrpc.empty
    yield response

  private def confirmEmail(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      body <- Xrpc.body(request)
      now <- env.now
      _ <- env.database.transact { connection =>
        val account = Accounts.requireActive(connection, session.did)
        val email = Xrpc.requireField(body, "email").toLowerCase
        if !account.email.contains(email) then
          throw XrpcError.named(Status.BadRequest, "InvalidEmail",
            "That email address does not match this account")
        Email.consume(connection, "confirm-email", Xrpc.requireField(body, "token"),
          account.did, email, now)
        Accounts.setEmail(connection, account.did, email, confirmed = true)
      }
      response <- Xrpc.empty
    yield response

  private def requestEmailUpdate(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      now <- env.now
      confirmed <- env.database.transact { connection =>
        val account = Accounts.requireActive(connection, session.did)
        if account.emailConfirmed then
          Email.requireEnabled(env)
          Email.issue(connection, account, "update-email", now)
        account.emailConfirmed
      }
      response <- Xrpc.ok(Json.obj("tokenRequired" -> Json.fromBoolean(confirmed)))
    yield response

  private def updateEmail(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      body <- Xrpc.body(request)
      now <- env.now
      _ <- env.database.transact { connection =>
        val account = Accounts.requireActive(connection, session.did)
        val email = Xrpc.requireField(body, "email").toLowerCase
        if Accounts.byEmail(connection, email).exists(_.did != account.did) then
          throw XrpcError.named(Status.BadRequest, "InvalidEmail",
            "That email address is already in use")
        if account.emailConfirmed then
          Email.consume(connection, "update-email",
            Xrpc.field(body, "token").getOrElse(
              throw XrpcError.named(Status.BadRequest, "TokenRequired",
                "A confirmation token is required")),
            account.did, account.email.getOrElse(""), now)
        Accounts.setEmail(connection, account.did, email, confirmed = false)
        Sql.update(connection, "UPDATE accounts SET email_auth_factor = false WHERE did = ?",
          account.did)
      }
      response <- Xrpc.empty
    yield response

  private def requestPasswordReset(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      body <- Xrpc.body(request)
      now <- env.now
      _ <- env.database.transact { connection =>
        Email.requireEnabled(env)
        Accounts.byEmail(connection, Xrpc.requireField(body, "email"))
          .filter(_.active)
          .foreach(account => Email.issue(connection, account, "reset-password", now))
      }
      response <- Xrpc.empty
    yield response

  private def resetPassword(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      body <- Xrpc.body(request)
      now <- env.now
      _ <- env.database.transact { connection =>
        val token = Xrpc.requireField(body, "token")
        val password = Xrpc.requireField(body, "password")
        if password.length < 8 then
          throw XrpcError.named(Status.BadRequest, "InvalidPassword",
            "Passwords need at least 8 characters")
        val did = Sql.first(connection,
          "SELECT did FROM account_tokens WHERE token_hash = ? AND purpose = 'reset-password'",
          pds.crypto.Hash.digestToken(token.trim.toUpperCase))(_.string("did"))
          .getOrElse(throw XrpcError.named(Status.BadRequest, "InvalidToken",
            "Token is invalid or has expired"))
        val account = Accounts.requireActive(connection, did)
        Email.consume(connection, "reset-password", token, did, account.email.getOrElse(""), now)
        Accounts.setPassword(connection, did, password)
        Accounts.bumpSecurityEpoch(connection, did)
      }
      response <- Xrpc.empty
    yield response

  private def deactivateAccount(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      body <- Xrpc.body(request)
      now <- env.now
      _ <- env.database.transact { connection =>
        val account = Accounts.requireActive(connection, session.did)
        val deleteAfter = Xrpc.field(body, "deleteAfter").filter(Syntax.isDatetime)
          .map(value => java.time.OffsetDateTime.parse(value).toInstant.toEpochMilli)
        Accounts.deactivate(connection, account.did, deleteAfter, now)
        Events.account(connection, account.did, active = false, Some("deactivated"))
      }
      response <- Xrpc.empty
    yield response

  private def activateAccount(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      _ <- env.database.transact { connection =>
        val account = Accounts.require(connection, session.did)
        if account.status == "taken_down" then
          throw XrpcError.named(Status.Forbidden, "AccountTakedown", "Account has been suspended")
        Accounts.activate(connection, account.did)
        Events.account(connection, account.did, active = true, None)
      }
      response <- Xrpc.empty
    yield response

  private def checkAccountStatus(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      status <- env.database.read { connection =>
        val account = Accounts.require(connection, session.did)
        val head = RepoStore.head(connection, account.did)
        Json.obj(
          "activated" -> Json.fromBoolean(account.active),
          "validDid" -> Json.fromBoolean(true),
          "repoCommit" -> head.map(value => Json.fromString(value.commit.cid.toString))
            .getOrElse(Json.Null),
          "repoRev" -> head.map(value => Json.fromString(value.rev)).getOrElse(Json.Null),
          "repoBlocks" -> Json.fromLong(Sql.count(connection,
            "SELECT COUNT(*) AS total FROM repo_blocks WHERE did = ?", account.did)),
          "indexedRecords" -> Json.fromLong(Sql.count(connection,
            "SELECT COUNT(*) AS total FROM records WHERE did = ?", account.did)),
          "privateStateValues" -> Json.fromLong(Sql.count(connection,
            "SELECT COUNT(*) AS total FROM account_preferences WHERE did = ?", account.did)),
          "expectedBlobs" -> Json.fromLong(Sql.count(connection,
            "SELECT COUNT(DISTINCT cid) AS total FROM record_blobs WHERE did = ?", account.did)),
          "importedBlobs" -> Json.fromLong(Sql.count(connection,
            "SELECT COUNT(*) AS total FROM blobs WHERE did = ?", account.did))
        ).deepDropNullValues
      }
      response <- Xrpc.ok(status)
    yield response

  private def deleteAccount(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      body <- Xrpc.body(request)
      now <- env.now
      _ <- env.database.transact { connection =>
        val did = Xrpc.requireField(body, "did")
        val account = Accounts.require(connection, did)
        if !Accounts.verifyPassword(Some(account), Xrpc.requireField(body, "password")) then
          throw XrpcError.authRequired("Invalid password")
        Email.consume(connection, "delete-account", Xrpc.requireField(body, "token"), did,
          account.email.getOrElse(""), now)
        Events.account(connection, did, active = false, Some("deleted"))
        Accounts.delete(connection, did, now)
      }
      response <- Xrpc.empty
    yield response

  /** Short-lived service tokens signed by the account's repository key. */
  private def getServiceAuth(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      audience = Xrpc.requireParam(request, "aud")
      _ <- IO.raiseUnless(Syntax.isDid(audience))(
        XrpcError.invalidRequest("aud must be a DID"))
      method = Xrpc.param(request, "lxm").filter(Syntax.isNsid)
      now <- env.now
      expiry = Xrpc.param(request, "exp").flatMap(_.toLongOption).getOrElse(now / 1000 + 60)
      _ <- IO.raiseUnless(expiry > now / 1000 && expiry <= now / 1000 + 600)(
        XrpcError.named(Status.BadRequest, "BadExpiration",
          "Service tokens may not last longer than ten minutes"))
      token <- env.database.read { connection =>
        val account = Accounts.requireActive(connection, session.did)
        val key = RepoStore.signingKey(connection, account.did, env.sealing)
        Jwt.signService(key, Json.obj(
          "iss" -> Json.fromString(account.did),
          "aud" -> Json.fromString(audience),
          "exp" -> Json.fromLong(expiry),
          "iat" -> Json.fromLong(now / 1000),
          "jti" -> Json.fromString(pds.crypto.Hash.token()),
          "lxm" -> method.map(Json.fromString).getOrElse(Json.Null)
        ).deepDropNullValues)
      }
      response <- Xrpc.ok(Json.obj("token" -> Json.fromString(token)))
    yield response

  /** Reserves a signing key for an account being migrated to this server. */
  private def reserveSigningKey(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      body <- Xrpc.body(request)
      now <- env.now
      key = PrivateKey.generate(Curve.K256)
      did = Xrpc.field(body, "did")
      _ <- env.database.transact { connection =>
        did.foreach { value =>
          Sql.update(connection, "DELETE FROM account_keys WHERE did = ? AND plc_confirmed = false",
            value)
          if Accounts.byDid(connection, value).isDefined then
            Sql.update(connection,
              """INSERT INTO account_keys(did, signing_curve, signing_public, signing_sealed,
                 plc_confirmed, created_at) VALUES (?, ?, ?, ?, ?, ?)""",
              value, key.curve.name, key.publicKey.didKey, env.sealing.sealKey(key), false, now)
        }
      }
      response <- Xrpc.ok(Json.obj(
        "signingKey" -> Json.fromString(key.publicKey.didKey)))
    yield response
