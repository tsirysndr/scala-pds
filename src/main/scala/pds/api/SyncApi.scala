package pds.api

import cats.effect.IO
import fs2.Stream
import io.circe.Json
import org.http4s.{Header, MediaType, Request, Response, Status}
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import pds.{Env, XrpcError}
import pds.accounts.Accounts
import pds.firehose.Events
import pds.protocol.*
import pds.repo.RepoStore
import pds.storage.Sql

object SyncApi:
  def endpoints(env: Env): Map[String, Endpoint] = Map(
    "com.atproto.sync.getRepo" -> Endpoint.query(getRepo(env, _)),
    "com.atproto.sync.getRepoStatus" -> Endpoint.query(getRepoStatus(env, _)),
    "com.atproto.sync.getLatestCommit" -> Endpoint.query(getLatestCommit(env, _)),
    "com.atproto.sync.getRecord" -> Endpoint.query(getRecord(env, _)),
    "com.atproto.sync.getBlocks" -> Endpoint.query(getBlocks(env, _)),
    "com.atproto.sync.getBlob" -> Endpoint.query(getBlob(env, _)),
    "com.atproto.sync.listBlobs" -> Endpoint.query(listBlobs(env, _)),
    "com.atproto.sync.listRepos" -> Endpoint.query(listRepos(env, _)),
    "com.atproto.sync.listReposByCollection" -> Endpoint.query(listReposByCollection(env, _))
  )

  private def car(bytes: Array[Byte], rev: Option[String]): Response[IO] =
    Response[IO](Status.Ok)
      .withEntity(bytes)
      .withContentType(`Content-Type`(MediaType.unsafeParse("application/vnd.ipld.car")))
      .putHeaders(rev.toSeq.map[Header.ToRaw](value =>
        Header.Raw(CIString("Atproto-Repo-Rev"), value))*)

  private def account(env: Env, identifier: String): IO[String] =
    env.database.read(connection =>
      Accounts.byIdentifier(connection, identifier)
        .getOrElse(throw XrpcError.named(Status.BadRequest, "RepoNotFound",
          "Repository was not found")).did)

  private def getRepo(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      did <- account(env, Xrpc.requireParam(request, "did"))
      exported <- env.database.read { connection =>
        Accounts.requireActive(connection, did)
        RepoStore.exportBlocks(connection, did)
      }
      result <- IO.fromEither(exported.left.map(XrpcError.invalidRequest))
      head <- env.database.read(connection => RepoStore.head(connection, did))
    yield car(Car.write(Vector(result._1), result._2), head.map(_.rev))

  private def getRepoStatus(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      did <- account(env, Xrpc.requireParam(request, "did"))
      status <- env.database.read { connection =>
        val found = Accounts.require(connection, did)
        val head = RepoStore.head(connection, did)
        Json.obj(
          "did" -> Json.fromString(did),
          "active" -> Json.fromBoolean(found.active),
          "status" -> (if found.active then Json.Null
            else Json.fromString(
              if found.status == "taken_down" then "takendown"
              else if found.status == "deleted" then "deleted" else "deactivated")),
          "rev" -> head.map(value => Json.fromString(value.rev)).getOrElse(Json.Null)
        ).deepDropNullValues
      }
      response <- Xrpc.ok(status)
    yield response

  private def getLatestCommit(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      did <- account(env, Xrpc.requireParam(request, "did"))
      head <- env.database.read { connection =>
        Accounts.requireActive(connection, did)
        RepoStore.head(connection, did)
      }
      found <- IO.fromOption(head)(
        XrpcError.named(Status.BadRequest, "RepoNotFound", "Repository was not found"))
      response <- Xrpc.ok(Json.obj(
        "cid" -> Json.fromString(found.commit.cid.toString),
        "rev" -> Json.fromString(found.rev)
      ))
    yield response

  private def getRecord(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      did <- account(env, Xrpc.requireParam(request, "did"))
      collection = Xrpc.requireParam(request, "collection")
      key = Xrpc.requireParam(request, "rkey")
      proof <- env.database.read { connection =>
        Accounts.requireActive(connection, did)
        val head = RepoStore.requireHead(connection, did)
        val reader = RepoStore.blockReader(connection, did)
        val store = new Mst.Store(reader)
        val path = s"$collection/$key"
        for
          nodes <- proofPath(store, head.root, path)
          record <- MstOps.get(store, store.tree(head.root).fold(message =>
            throw XrpcError.invalidRequest(message), identity), path)
          blocks = (Vector(head.commit.cid -> head.commit.bytes) ++
            nodes.flatMap(cid => reader(cid).map(cid -> _)) ++
            record.flatMap(cid => reader(cid).map(cid -> _)).toVector)
        yield Car.write(Vector(head.commit.cid), blocks.distinctBy(_._1.toString))
      }
      bytes <- IO.fromEither(proof.left.map(XrpcError.invalidRequest))
    yield car(bytes, None)

  /** The nodes on the path to a key, which prove inclusion or exclusion. */
  private def proofPath(store: Mst.Store, root: Cid, path: String): Either[String, Vector[Cid]] =
    def walk(cid: Cid): Either[String, Vector[Cid]] =
      store.tree(cid).flatMap { tree =>
        val index = tree.entries.indexWhere {
          case Mst.Entry.Leaf(key, _) => key >= path
          case _                      => false
        }
        val position = if index >= 0 then index else tree.entries.length
        tree.entries.lift(position) match
          case Some(Mst.Entry.Leaf(key, _)) if key == path => Right(Vector(cid))
          case _ => tree.entries.lift(position - 1) match
            case Some(Mst.Entry.Sub(Mst.Child.Stored(child))) => walk(child).map(cid +: _)
            case _                                            => Right(Vector(cid))
      }
    walk(root)

  private def getBlocks(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      did <- account(env, Xrpc.requireParam(request, "did"))
      requested = request.multiParams.getOrElse("cids", Nil).toVector
      _ <- IO.raiseUnless(requested.nonEmpty && requested.length <= 1000)(
        XrpcError.invalidRequest("Between one and one thousand CIDs may be requested"))
      cids = requested.map(value =>
        Cid.parse(value).getOrElse(throw XrpcError.invalidRequest(s"Invalid CID")))
      blocks <- env.database.read { connection =>
        Accounts.requireActive(connection, did)
        val reader = RepoStore.blockReader(connection, did)
        cids.map(cid => cid -> reader(cid).getOrElse(
          throw XrpcError.named(Status.BadRequest, "BlockNotFound", s"Block was not found")))
      }
    yield car(Car.write(Vector.empty, blocks), None)

  private def getBlob(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      did <- account(env, Xrpc.requireParam(request, "did"))
      cid <- IO.fromOption(Cid.parse(Xrpc.requireParam(request, "cid")))(
        XrpcError.invalidRequest("cid must be a CID"))
      blob <- pds.repo.BlobStore.read(env, did, cid)
    yield Response[IO](Status.Ok)
      .withEntity(blob._1)
      .withContentType(`Content-Type`(MediaType.unsafeParse(blob._2)))
      .putHeaders(Header.Raw(CIString("Content-Security-Policy"), "default-src 'none'; sandbox"),
        Header.Raw(CIString("X-Content-Type-Options"), "nosniff"))

  private def listBlobs(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      did <- account(env, Xrpc.requireParam(request, "did"))
      limit = Xrpc.intParam(request, "limit", 500, 1, 1000)
      since = Xrpc.param(request, "since")
      cursor = Xrpc.param(request, "cursor").getOrElse("")
      cids <- env.database.read { connection =>
        Accounts.requireActive(connection, did)
        val revFilter = since.map(_ => " AND r.rev > ?").getOrElse("")
        val params: Vector[pds.storage.Param] =
          Vector(pds.storage.Param.Text(did), pds.storage.Param.Text(cursor)) ++
            since.map(pds.storage.Param.Text.apply).toVector :+
            pds.storage.Param.Number(limit.toLong)
        Sql.query(connection,
          s"""SELECT DISTINCT rb.cid AS cid FROM record_blobs rb
              JOIN records r ON r.did = rb.did AND r.collection = rb.collection AND r.rkey = rb.rkey
              WHERE rb.did = ? AND rb.cid > ?$revFilter ORDER BY rb.cid LIMIT ?""",
          params*)(_.string("cid"))
      }
      response <- Xrpc.ok(Json.obj(
        "cids" -> Json.arr(cids.map(Json.fromString)*),
        "cursor" -> cids.lastOption.map(Json.fromString).getOrElse(Json.Null)
      ).deepDropNullValues)
    yield response

  private def listRepos(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      limit <- IO.pure(Xrpc.intParam(request, "limit", 500, 1, 1000))
      repos <- env.database.read { connection =>
        Accounts.list(connection, limit, Xrpc.param(request, "cursor")).map { found =>
          val head = RepoStore.head(connection, found.did)
          Json.obj(
            "did" -> Json.fromString(found.did),
            "head" -> head.map(value => Json.fromString(value.commit.cid.toString))
              .getOrElse(Json.Null),
            "rev" -> head.map(value => Json.fromString(value.rev)).getOrElse(Json.Null),
            "active" -> Json.fromBoolean(found.active),
            "status" -> (if found.active then Json.Null
              else Json.fromString(
                if found.status == "taken_down" then "takendown" else "deactivated"))
          ).deepDropNullValues
        }
      }
      response <- Xrpc.ok(Json.obj(
        "repos" -> Json.arr(repos*),
        "cursor" -> repos.lastOption.flatMap(_.hcursor.get[String]("did").toOption)
          .map(Json.fromString).getOrElse(Json.Null)
      ).deepDropNullValues)
    yield response

  private def listReposByCollection(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      collection <- IO.pure(Xrpc.requireParam(request, "collection"))
      limit = Xrpc.intParam(request, "limit", 500, 1, 2000)
      dids <- env.database.read { connection =>
        Sql.query(connection,
          """SELECT DISTINCT r.did AS did FROM records r JOIN accounts a ON a.did = r.did
             WHERE r.collection = ? AND a.status = 'active' AND r.did > ?
             ORDER BY r.did LIMIT ?""",
          collection, Xrpc.param(request, "cursor").getOrElse(""), limit)(_.string("did"))
      }
      response <- Xrpc.ok(Json.obj(
        "repos" -> Json.arr(dids.map(did => Json.obj("did" -> Json.fromString(did)))*),
        "cursor" -> dids.lastOption.map(Json.fromString).getOrElse(Json.Null)
      ).deepDropNullValues)
    yield response
