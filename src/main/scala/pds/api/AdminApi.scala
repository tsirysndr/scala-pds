package pds.api

import cats.effect.IO
import io.circe.Json
import org.http4s.{Request, Response, Status}
import pds.{Env, XrpcError}
import pds.accounts.{Account, Accounts, Email, Invites}
import pds.firehose.Events
import pds.protocol.{AtUri, Cid, Syntax}
import pds.repo.RepoStore
import pds.storage.Sql

object AdminApi:
  def endpoints(env: Env): Map[String, Endpoint] = Map(
    "com.atproto.admin.getAccountInfo" -> Endpoint.query(getAccountInfo(env, _)),
    "com.atproto.admin.getAccountInfos" -> Endpoint.query(getAccountInfos(env, _)),
    "com.atproto.admin.searchAccounts" -> Endpoint.query(searchAccounts(env, _)),
    "com.atproto.admin.updateAccountEmail" -> Endpoint.procedure(updateAccountEmail(env, _)),
    "com.atproto.admin.updateAccountHandle" -> Endpoint.procedure(updateAccountHandle(env, _)),
    "com.atproto.admin.updateAccountPassword" -> Endpoint.procedure(updateAccountPassword(env, _)),
    "com.atproto.admin.deleteAccount" -> Endpoint.procedure(deleteAccount(env, _)),
    "com.atproto.admin.getInviteCodes" -> Endpoint.query(getInviteCodes(env, _)),
    "com.atproto.admin.disableInviteCodes" -> Endpoint.procedure(disableInviteCodes(env, _)),
    "com.atproto.admin.disableAccountInvites" -> Endpoint.procedure(setInvites(env, _, false)),
    "com.atproto.admin.enableAccountInvites" -> Endpoint.procedure(setInvites(env, _, true)),
    "com.atproto.admin.getSubjectStatus" -> Endpoint.query(getSubjectStatus(env, _)),
    "com.atproto.admin.updateSubjectStatus" -> Endpoint.procedure(updateSubjectStatus(env, _)),
    "com.atproto.admin.sendEmail" -> Endpoint.procedure(sendEmail(env, _))
  )

  private def describe(account: Account, invites: Vector[Json]): Json =
    Json.obj(
      "did" -> Json.fromString(account.did),
      "handle" -> Json.fromString(account.handle),
      "email" -> account.email.map(Json.fromString).getOrElse(Json.Null),
      "emailConfirmedAt" -> (if account.emailConfirmed then
        Json.fromString(Syntax.datetime(java.time.Instant.ofEpochMilli(account.createdAt)))
        else Json.Null),
      "indexedAt" -> Json.fromString(
        Syntax.datetime(java.time.Instant.ofEpochMilli(account.createdAt))),
      "invitesDisabled" -> Json.fromBoolean(account.invitesDisabled),
      "invites" -> Json.arr(invites*),
      "deactivatedAt" -> account.deactivatedAt.map(value =>
        Json.fromString(Syntax.datetime(java.time.Instant.ofEpochMilli(value)))).getOrElse(Json.Null),
      "threatSignatures" -> Json.arr()
    ).deepDropNullValues

  private def getAccountInfo(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      _ <- Xrpc.admin(env, request)
      did = Xrpc.requireParam(request, "did")
      info <- env.database.read { connection =>
        val account = Accounts.require(connection, did)
        describe(account, Invites.forAccount(connection, did))
      }
      response <- Xrpc.ok(info)
    yield response

  private def getAccountInfos(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      _ <- Xrpc.admin(env, request)
      dids = request.multiParams.getOrElse("dids", Nil).toVector.distinct.take(100)
      infos <- env.database.read { connection =>
        dids.flatMap(did => Accounts.byDid(connection, did).map(account =>
          describe(account, Invites.forAccount(connection, did))))
      }
      response <- Xrpc.ok(Json.obj("infos" -> Json.arr(infos*)))
    yield response

  private def searchAccounts(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      _ <- Xrpc.admin(env, request)
      limit = Xrpc.intParam(request, "limit", 50, 1, 100)
      email = Xrpc.param(request, "email")
      accounts <- env.database.read { connection =>
        email match
          case Some(value) => Accounts.byEmail(connection, value).toVector
          case None        => Accounts.list(connection, limit, Xrpc.param(request, "cursor"))
      }
      response <- Xrpc.ok(Json.obj(
        "accounts" -> Json.arr(accounts.map(account => describe(account, Vector.empty))*),
        "cursor" -> accounts.lastOption.map(account => Json.fromString(account.did)).getOrElse(Json.Null)
      ).deepDropNullValues)
    yield response

  private def updateAccountEmail(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      _ <- Xrpc.admin(env, request)
      body <- Xrpc.body(request)
      _ <- env.database.transact { connection =>
        val account = Accounts.byIdentifier(connection, Xrpc.requireField(body, "account"))
          .getOrElse(throw XrpcError.notFound("Account was not found"))
        Accounts.setEmail(connection, account.did, Xrpc.requireField(body, "email"),
          confirmed = false)
      }
      response <- Xrpc.empty
    yield response

  private def updateAccountHandle(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      _ <- Xrpc.admin(env, request)
      body <- Xrpc.body(request)
      handle = Syntax.normalizeHandle(Xrpc.requireField(body, "handle"))
      _ <- IO.raiseUnless(Syntax.isHandle(handle))(
        XrpcError.named(Status.BadRequest, "InvalidHandle", "Handle is not valid"))
      now <- env.now
      _ <- env.database.transact { connection =>
        val account = Accounts.require(connection, Xrpc.requireField(body, "did"))
        if account.handle != handle then
          if !Accounts.handleAvailable(connection, handle) then
            throw XrpcError.named(Status.BadRequest, "HandleNotAvailable", "Handle is already taken")
          Accounts.setHandle(connection, account.did, handle)
          Sql.update(connection,
            "INSERT INTO handle_reservations(handle, did, created_at) VALUES (?, ?, ?)",
            handle, account.did, now)
          Events.identity(connection, account.did, Some(handle))
      }
      response <- Xrpc.empty
    yield response

  private def updateAccountPassword(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      _ <- Xrpc.admin(env, request)
      body <- Xrpc.body(request)
      password = Xrpc.requireField(body, "password")
      _ <- IO.raiseUnless(password.length >= 8)(
        XrpcError.named(Status.BadRequest, "InvalidPassword",
          "Passwords need at least 8 characters"))
      _ <- env.database.transact { connection =>
        val account = Accounts.require(connection, Xrpc.requireField(body, "did"))
        Accounts.setPassword(connection, account.did, password)
        Accounts.bumpSecurityEpoch(connection, account.did)
      }
      response <- Xrpc.empty
    yield response

  private def deleteAccount(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      _ <- Xrpc.admin(env, request)
      body <- Xrpc.body(request)
      _ <- env.database.transact { connection =>
        val did = Xrpc.requireField(body, "did")
        Accounts.require(connection, did)
        Events.account(connection, did, active = false, Some("deleted"))
        Accounts.delete(connection, did)
      }
      response <- Xrpc.empty
    yield response

  private def getInviteCodes(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      _ <- Xrpc.admin(env, request)
      limit = Xrpc.intParam(request, "limit", 100, 1, 500)
      codes <- env.database.read { connection =>
        Sql.query(connection,
          "SELECT for_account FROM invite_codes WHERE code > ? ORDER BY code LIMIT ?",
          Xrpc.param(request, "cursor").getOrElse(""), limit)(_.string("for_account"))
          .distinct.flatMap(account => Invites.forAccount(connection, account))
      }
      response <- Xrpc.ok(Json.obj(
        "codes" -> Json.arr(codes*),
        "cursor" -> codes.lastOption.flatMap(_.hcursor.get[String]("code").toOption)
          .map(Json.fromString).getOrElse(Json.Null)
      ).deepDropNullValues)
    yield response

  private def disableInviteCodes(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      _ <- Xrpc.admin(env, request)
      body <- Xrpc.body(request)
      _ <- env.database.transact(connection =>
        Invites.disable(connection,
          body.hcursor.get[Vector[String]]("codes").getOrElse(Vector.empty),
          body.hcursor.get[Vector[String]]("accounts").getOrElse(Vector.empty)))
      response <- Xrpc.empty
    yield response

  private def setInvites(env: Env, request: Request[IO], enabled: Boolean): IO[Response[IO]] =
    for
      _ <- Xrpc.admin(env, request)
      body <- Xrpc.body(request)
      _ <- env.database.transact { connection =>
        val account = Accounts.require(connection, Xrpc.requireField(body, "account"))
        Invites.setAccountInvites(connection, account.did, enabled)
      }
      response <- Xrpc.empty
    yield response

  private def getSubjectStatus(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      _ <- Xrpc.admin(env, request)
      status <- env.database.read { connection =>
        (Xrpc.param(request, "did"), Xrpc.param(request, "uri"), Xrpc.param(request, "blob")) match
          case (Some(did), None, None) =>
            val account = Accounts.require(connection, did)
            subject(Json.obj("$type" -> Json.fromString("com.atproto.admin.defs#repoRef"),
              "did" -> Json.fromString(did)), account.takedownRef)
          case (Some(did), None, Some(blob)) =>
            val reference = Sql.first(connection,
              "SELECT takedown_ref FROM blobs WHERE did = ? AND cid = ?", did, blob)(
              _.stringOpt("takedown_ref")).flatten
            subject(Json.obj("$type" -> Json.fromString("com.atproto.admin.defs#repoBlobRef"),
              "did" -> Json.fromString(did), "cid" -> Json.fromString(blob)), reference)
          case (_, Some(uri), None) =>
            val parsed = AtUri.parse(uri)
              .getOrElse(throw XrpcError.invalidRequest("uri must be an AT URI"))
            val reference = Sql.first(connection,
              "SELECT takedown_ref FROM records WHERE did = ? AND collection = ? AND rkey = ?",
              parsed.authority, parsed.collection.getOrElse(""), parsed.recordKey.getOrElse(""))(
              _.stringOpt("takedown_ref")).flatten
            subject(Json.obj("$type" -> Json.fromString("com.atproto.repo.strongRef"),
              "uri" -> Json.fromString(uri)), reference)
          case _ => throw XrpcError.invalidRequest("Provide did, uri or did and blob")
      }
      response <- Xrpc.ok(status)
    yield response

  private def subject(value: Json, reference: Option[String]): Json =
    Json.obj(
      "subject" -> value,
      "takedown" -> Json.obj(
        "applied" -> Json.fromBoolean(reference.isDefined),
        "ref" -> reference.map(Json.fromString).getOrElse(Json.Null)
      ).deepDropNullValues
    )

  private def updateSubjectStatus(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      _ <- Xrpc.admin(env, request)
      body <- Xrpc.body(request)
      applied = body.hcursor.downField("takedown").get[Boolean]("applied").getOrElse(false)
      reference: Option[String] =
        if applied then body.hcursor.downField("takedown").get[String]("ref").toOption
          .orElse(Some(pds.crypto.Hash.token()))
        else None
      subjectJson <- IO.fromOption(body.hcursor.downField("subject").focus)(
        XrpcError.invalidRequest("subject is required"))
      _ <- env.database.transact { connection =>
        val kind = subjectJson.hcursor.get[String]("$type").toOption.getOrElse("")
        kind match
          case "com.atproto.admin.defs#repoRef" =>
            val did = subjectJson.hcursor.get[String]("did").toOption
              .getOrElse(throw XrpcError.invalidRequest("subject.did is required"))
            Accounts.require(connection, did)
            Accounts.takedown(connection, did, reference)
            val account = Accounts.require(connection, did)
            Events.account(connection, did, active = account.active,
              Option.when(!account.active)("takendown"))
          case "com.atproto.admin.defs#repoBlobRef" =>
            Sql.update(connection, "UPDATE blobs SET takedown_ref = ? WHERE did = ? AND cid = ?",
              reference,
              subjectJson.hcursor.get[String]("did").toOption.getOrElse(""),
              subjectJson.hcursor.get[String]("cid").toOption.getOrElse(""))
          case "com.atproto.repo.strongRef" =>
            val parsed = AtUri.parse(subjectJson.hcursor.get[String]("uri").toOption.getOrElse(""))
              .getOrElse(throw XrpcError.invalidRequest("subject.uri must be an AT URI"))
            Sql.update(connection,
              """UPDATE records SET takedown_ref = ?
                 WHERE did = ? AND collection = ? AND rkey = ?""",
              reference, parsed.authority,
              parsed.collection.getOrElse(""), parsed.recordKey.getOrElse(""))
          case other => throw XrpcError.invalidRequest(s"Unsupported subject type $other")
      }
      response <- Xrpc.ok(subject(subjectJson, reference))
    yield response

  private def sendEmail(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      _ <- Xrpc.admin(env, request)
      body <- Xrpc.body(request)
      now <- env.now
      _ <- env.database.transact { connection =>
        Email.requireEnabled(env)
        val account = Accounts.require(connection, Xrpc.requireField(body, "recipientDid"))
        val email = account.email.getOrElse(
          throw XrpcError.invalidRequest("That account has no email address"))
        Email.send(connection, email, "admin-notice", Json.obj(
          "subject" -> Json.fromString(Xrpc.field(body, "subject").getOrElse("A message from your PDS")),
          "content" -> Json.fromString(Xrpc.requireField(body, "content"))), now)
      }
      response <- Xrpc.ok(Json.obj("sent" -> Json.True))
    yield response
