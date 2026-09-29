package pds.api

import cats.effect.IO
import io.circe.Json
import org.http4s.{MediaType, Request, Response, Status}
import org.http4s.headers.`Content-Type`
import pds.{Env, XrpcError}
import pds.accounts.{Accounts, Session}
import pds.crypto.Encoding
import pds.firehose.Events
import pds.lexicon.{Catalog, Validator}
import pds.protocol.*
import pds.repo.RepoStore
import pds.storage.Sql

object RepoApi:
  def endpoints(env: Env): Map[String, Endpoint] = Map(
    "com.atproto.repo.createRecord" -> Endpoint.procedure(createRecord(env, _)),
    "com.atproto.repo.putRecord" -> Endpoint.procedure(putRecord(env, _)),
    "com.atproto.repo.deleteRecord" -> Endpoint.procedure(deleteRecord(env, _)),
    "com.atproto.repo.applyWrites" -> Endpoint.procedure(applyWrites(env, _)),
    "com.atproto.repo.getRecord" -> Endpoint.query(getRecord(env, _)),
    "com.atproto.repo.listRecords" -> Endpoint.query(listRecords(env, _)),
    "com.atproto.repo.describeRepo" -> Endpoint.query(describeRepo(env, _)),
    "com.atproto.repo.uploadBlob" -> Endpoint.procedure(uploadBlob(env, _)),
    "com.atproto.repo.listMissingBlobs" -> Endpoint.query(listMissingBlobs(env, _)),
    "com.atproto.repo.importRepo" -> Endpoint.procedure(importRepo(env, _))
  )

  private def writer(env: Env, request: Request[IO], method: String): IO[Session] =
    for
      session <- Xrpc.session(env, request)
      _ <- pds.oauth.Resource.authorize(session, method)
    yield session

  private def repoDid(env: Env, session: Session, body: Json): IO[String] =
    val requested = Xrpc.field(body, "repo").getOrElse(session.did)
    if requested == session.did then IO.pure(session.did)
    else
      env.database.read(connection =>
        Accounts.byIdentifier(connection, requested).map(_.did)).flatMap {
        case Some(did) if did == session.did => IO.pure(did)
        case _ => IO.raiseError(XrpcError.forbidden("You may only write to your own repository"))
      }

  private def recordFrom(body: Json, name: String = "record"): Node =
    val json = body.hcursor.downField(name).focus
      .getOrElse(throw XrpcError.invalidRequest(s"$name is required"))
    if !json.isObject then throw XrpcError.invalidRequest(s"$name must be an object")
    Node.fromJson(json).fold(message => throw XrpcError.invalidRequest(message), identity)

  /** The catalog to validate `collection` against. Trusted schemas need no
    * network; anything else is resolved only when `validate` demands it, and
    * otherwise used only if it is already cached. Resolution happens here,
    * outside every write transaction.
    */
  private def catalogFor(
      env: Env, collection: String, validate: Option[Boolean]
  ): IO[Option[Catalog]] =
    if validate.contains(false) then IO.pure(None)
    else if Catalog.trusted.contains(collection) then IO.pure(Some(Catalog.trusted))
    else
      env.now.flatMap { now =>
        if validate.contains(true) then
          env.schemas.catalog(collection, now).flatMap {
            case Right(catalog) => IO.pure(Some(catalog))
            case Left(reason) =>
              IO.raiseError(XrpcError.named(Status.BadRequest, "InvalidRecord", reason))
          }
        else env.schemas.known(collection, now)
      }

  private def catalogsFor(
      env: Env, writes: Vector[(String, Option[Boolean])]
  ): IO[Map[String, Catalog]] =
    writes.distinct.foldLeft(IO.pure(Map.empty[String, Catalog])) {
      case (acc, (collection, validate)) =>
        for
          current <- acc
          found <- if current.contains(collection) then IO.pure(None)
            else catalogFor(env, collection, validate)
        yield found.fold(current)(catalog => current.updated(collection, catalog))
    }

  /** The `$type` and record-key rules always apply; schema validation applies
    * when a catalog was resolved for the collection.
    */
  private def validated(
      catalog: Option[Catalog],
      record: Node,
      collection: String,
      recordKey: String,
      validate: Option[Boolean]
  ): Option[String] =
    record.recordType match
      case Some(value) if value == collection => ()
      case Some(value) =>
        throw XrpcError.invalidRequest(s"Record $$type $value does not match the collection")
      case None if validate.contains(false) => ()
      case None => throw XrpcError.invalidRequest("Records need a $type matching the collection")
    catalog match
      case None if validate.contains(false) => None
      case None                             => Some("unknown")
      case Some(found) =>
        Validator.validateRecord(found, collection, recordKey, record, validate) match
          case Right(status) => status
          case Left(invalid) =>
            throw XrpcError.named(Status.BadRequest, "InvalidRecord", invalid.message)

  private def collectionOf(body: Json): String =
    val value = Xrpc.requireField(body, "collection")
    if !Syntax.isNsid(value) then throw XrpcError.invalidRequest("collection must be an NSID")
    value

  private def recordKey(body: Json, generate: Boolean): String =
    Xrpc.field(body, "rkey") match
      case Some(value) if Syntax.isRecordKey(value) => value
      case Some(_) => throw XrpcError.invalidRequest("rkey is not a valid record key")
      case None if generate => Tid.next()
      case None => throw XrpcError.invalidRequest("rkey is required")

  private def swap(body: Json, name: String): Option[Cid] =
    Xrpc.field(body, name).map(value =>
      Cid.parse(value).getOrElse(throw XrpcError.invalidRequest(s"$name is not a CID")))

  private def commitResult(
      env: Env, did: String, applied: Applied, path: String, status: Option[String]
  ): Json =
    Json.obj(
      "uri" -> Json.fromString(s"at://$did/$path"),
      "cid" -> Json.fromString(applied.records.get(path).map(_._1.toString)
        .getOrElse(applied.commitCid.toString)),
      "commit" -> Json.obj(
        "cid" -> Json.fromString(applied.commitCid.toString),
        "rev" -> Json.fromString(applied.commit.rev)
      ),
      "validationStatus" -> status.map(Json.fromString).getOrElse(Json.Null)
    ).deepDropNullValues

  private def write(
      env: Env, did: String, writes: Vector[Write], swapCommit: Option[Cid]
  ): IO[Applied] =
    env.database.transact { connection =>
      Accounts.requireActive(connection, did)
      val previous = RepoStore.requireHead(connection, did).rev
      val applied = RepoStore.commit(connection, did, env.sealing, writes, swapCommit)
      checkBlobs(connection, did, applied)
      Events.commit(connection, did, applied, Some(previous))
      applied
    }

  /** Every blob a record references must already be uploaded by the account. */
  private def checkBlobs(connection: java.sql.Connection, did: String, applied: Applied): Unit =
    applied.records.values.flatMap((_, record) => Node.blobs(record)).foreach { blob =>
      val stored = Sql.first(connection,
        "SELECT size, mime_type FROM blobs WHERE did = ? AND cid = ?", did, blob.ref.toString)(
        row => (row.long("size"), row.string("mime_type")))
      stored match
        case None => throw XrpcError.invalidRequest(
          s"Blob ${blob.ref} has not been uploaded")
        case Some((size, _)) if size != blob.size => throw XrpcError.invalidRequest(
          s"Blob ${blob.ref} does not have the referenced size")
        case _ => ()
    }

  private def createRecord(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- writer(env, request, "com.atproto.repo.createRecord")
      body <- Xrpc.body(request)
      did <- repoDid(env, session, body)
      collection = collectionOf(body)
      key = recordKey(body, generate = true)
      record = recordFrom(body)
      catalog <- catalogFor(env, collection, Xrpc.flag(body, "validate"))
      status = validated(catalog, record, collection, key, Xrpc.flag(body, "validate"))
      applied <- write(env, did, Vector(Write.Create(collection, key, record)),
        swap(body, "swapCommit"))
      response <- Xrpc.ok(commitResult(env, did, applied, s"$collection/$key", status))
    yield response

  private def putRecord(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- writer(env, request, "com.atproto.repo.putRecord")
      body <- Xrpc.body(request)
      did <- repoDid(env, session, body)
      collection = collectionOf(body)
      key = recordKey(body, generate = false)
      record = recordFrom(body)
      catalog <- catalogFor(env, collection, Xrpc.flag(body, "validate"))
      status = validated(catalog, record, collection, key, Xrpc.flag(body, "validate"))
      swapRecord = Xrpc.field(body, "swapRecord")
      applied <- env.database.transact { connection =>
        Accounts.requireActive(connection, did)
        val existing = RepoStore.record(connection, did, collection, key)
        swapRecord.foreach { expected =>
          if !existing.map(_._1.toString).contains(expected) then
            throw XrpcError.named(Status.BadRequest, "InvalidSwap",
              "The record has changed since the expected version")
        }
        val operation =
          if existing.isDefined then Write.Update(collection, key, record)
          else Write.Create(collection, key, record)
        val previous = RepoStore.requireHead(connection, did).rev
        val applied = RepoStore.commit(connection, did, env.sealing, Vector(operation),
          swap(body, "swapCommit"))
        checkBlobs(connection, did, applied)
        Events.commit(connection, did, applied, Some(previous))
        applied
      }
      response <- Xrpc.ok(commitResult(env, did, applied, s"$collection/$key", status))
    yield response

  private def deleteRecord(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- writer(env, request, "com.atproto.repo.deleteRecord")
      body <- Xrpc.body(request)
      did <- repoDid(env, session, body)
      collection = collectionOf(body)
      key = recordKey(body, generate = false)
      swapRecord = Xrpc.field(body, "swapRecord")
      applied <- env.database.transact { connection =>
        Accounts.requireActive(connection, did)
        val existing = RepoStore.record(connection, did, collection, key)
        swapRecord.foreach { expected =>
          if !existing.map(_._1.toString).contains(expected) then
            throw XrpcError.named(Status.BadRequest, "InvalidSwap",
              "The record has changed since the expected version")
        }
        if existing.isEmpty then None
        else
          val previous = RepoStore.requireHead(connection, did).rev
          val applied = RepoStore.commit(connection, did, env.sealing,
            Vector(Write.Delete(collection, key)), swap(body, "swapCommit"))
          Events.commit(connection, did, applied, Some(previous))
          Some(applied)
      }
      response <- Xrpc.ok(applied.map(value => Json.obj(
        "commit" -> Json.obj(
          "cid" -> Json.fromString(value.commitCid.toString),
          "rev" -> Json.fromString(value.commit.rev)))).getOrElse(Json.obj()))
    yield response

  private def applyWrites(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- writer(env, request, "com.atproto.repo.applyWrites")
      body <- Xrpc.body(request)
      did <- repoDid(env, session, body)
      items <- IO.fromOption(body.hcursor.downField("writes").values.map(_.toVector))(
        XrpcError.invalidRequest("writes is required"))
      _ <- IO.raiseUnless(items.nonEmpty && items.length <= 200)(
        XrpcError.invalidRequest("A batch holds between one and two hundred writes"))
      mode = Xrpc.flag(body, "validate")
      writes = items.map(parseWrite)
      catalogs <- catalogsFor(env, writes.collect {
        case write if write.action != "delete" => write.collection -> mode
      })
      statuses = writes.collect { case write if write.action != "delete" =>
        write.path -> validated(catalogs.get(write.collection), recordOf(write),
          write.collection, write.recordKey, mode)
      }.flatMap((path, status) => status.map(path -> _)).toMap
      applied <- write(env, did, writes, swap(body, "swapCommit"))
      response <- Xrpc.ok(Json.obj(
        "commit" -> Json.obj(
          "cid" -> Json.fromString(applied.commitCid.toString),
          "rev" -> Json.fromString(applied.commit.rev)),
        "results" -> Json.arr(applied.ops.map { operation =>
          val kind = operation.action match
            case "create" => "#createResult"
            case "update" => "#updateResult"
            case _        => "#deleteResult"
          Json.obj(
            "$type" -> Json.fromString(s"com.atproto.repo.applyWrites$kind"),
            "uri" -> Json.fromString(s"at://$did/${operation.path}"),
            "cid" -> operation.cid.map(value => Json.fromString(value.toString)).getOrElse(Json.Null),
            "validationStatus" -> statuses.get(operation.path).map(Json.fromString)
              .getOrElse(Json.Null)
          ).deepDropNullValues
        }*)
      ))
    yield response

  private def parseWrite(item: Json): Write =
    val kind = item.hcursor.get[String]("$type").toOption
      .getOrElse(throw XrpcError.invalidRequest("Every write needs a $type"))
    val collection = collectionOf(item)
    kind match
      case "com.atproto.repo.applyWrites#create" =>
        Write.Create(collection, recordKey(item, generate = true), recordFrom(item, "value"))
      case "com.atproto.repo.applyWrites#update" =>
        Write.Update(collection, recordKey(item, generate = false), recordFrom(item, "value"))
      case "com.atproto.repo.applyWrites#delete" =>
        Write.Delete(collection, recordKey(item, generate = false))
      case other => throw XrpcError.invalidRequest(s"Unsupported write type $other")

  private def recordOf(write: Write): Node = write match
    case Write.Create(_, _, record) => record
    case Write.Update(_, _, record) => record
    case Write.Delete(_, _)         => Node.Null

  private def getRecord(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      repo <- IO.pure(Xrpc.requireParam(request, "repo"))
      collection = Xrpc.requireParam(request, "collection")
      key = Xrpc.requireParam(request, "rkey")
      found <- env.database.read { connection =>
        val account = Accounts.byIdentifier(connection, repo)
          .getOrElse(throw XrpcError.recordNotFound("Repository was not found"))
        RepoStore.record(connection, account.did, collection, key).map(account.did -> _)
      }
      result <- IO.fromOption(found)(XrpcError.recordNotFound())
      response <- Xrpc.ok(Json.obj(
        "uri" -> Json.fromString(s"at://${result._1}/$collection/$key"),
        "cid" -> Json.fromString(result._2._1.toString),
        "value" -> Node.toJson(result._2._2)
      ))
    yield response

  private def listRecords(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      repo <- IO.pure(Xrpc.requireParam(request, "repo"))
      collection = Xrpc.requireParam(request, "collection")
      limit = Xrpc.intParam(request, "limit", 50, 1, 100)
      reverse = Xrpc.boolParam(request, "reverse", false)
      listed <- env.database.read { connection =>
        val account = Accounts.byIdentifier(connection, repo)
          .getOrElse(throw XrpcError.invalidRequest("Repository was not found"))
        account.did -> RepoStore.listRecords(connection, account.did, collection, limit,
          Xrpc.param(request, "cursor"), reverse)
      }
      response <- Xrpc.ok(Json.obj(
        "records" -> Json.arr(listed._2.map(item => Json.obj(
          "uri" -> Json.fromString(s"at://${listed._1}/$collection/${item.rkey}"),
          "cid" -> Json.fromString(item.cid.toString),
          "value" -> Node.toJson(item.value)
        ))*),
        "cursor" -> listed._2.lastOption.map(item => Json.fromString(item.rkey)).getOrElse(Json.Null)
      ).deepDropNullValues)
    yield response

  private def describeRepo(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      repo <- IO.pure(Xrpc.requireParam(request, "repo"))
      described <- env.database.read { connection =>
        val account = Accounts.byIdentifier(connection, repo)
          .getOrElse(throw XrpcError.invalidRequest("Repository was not found"))
        val collections = RepoStore.collections(connection, account.did)
        val key = RepoStore.signingKey(connection, account.did, env.sealing)
        Json.obj(
          "handle" -> Json.fromString(account.handle),
          "did" -> Json.fromString(account.did),
          "didDoc" -> pds.identity.DidDocument.build(account.did, account.handle,
            key.publicKey, env.config.publicUrl),
          "collections" -> Json.arr(collections.map(Json.fromString)*),
          "handleIsCorrect" -> Json.True
        )
      }
      response <- Xrpc.ok(described)
    yield response

  private def uploadBlob(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- writer(env, request, "com.atproto.repo.uploadBlob")
      bytes <- request.body.take(env.config.blobMaxSize + 1).compile.to(Array)
      _ <- IO.raiseWhen(bytes.length > env.config.blobMaxSize)(
        XrpcError.payloadTooLarge("Blob exceeds the configured maximum size"))
      _ <- IO.raiseWhen(bytes.isEmpty)(XrpcError.invalidRequest("Blob is empty"))
      mime = request.contentType.map(value =>
        s"${value.mediaType.mainType}/${value.mediaType.subType}")
        .getOrElse("application/octet-stream")
      cid = Cid.ofRaw(bytes)
      _ <- pds.repo.BlobStore.store(env, session.did, cid, mime, bytes)
      response <- Xrpc.ok(Json.obj("blob" -> Json.obj(
        "$type" -> Json.fromString("blob"),
        "ref" -> Json.obj("$link" -> Json.fromString(cid.toString)),
        "mimeType" -> Json.fromString(mime),
        "size" -> Json.fromLong(bytes.length.toLong)
      )))
    yield response

  private def listMissingBlobs(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      limit = Xrpc.intParam(request, "limit", 500, 1, 1000)
      missing <- env.database.read { connection =>
        Sql.query(connection,
          """SELECT r.cid AS cid, r.collection AS collection, r.rkey AS rkey FROM record_blobs r
             WHERE r.did = ? AND r.cid > ?
             AND NOT EXISTS (SELECT 1 FROM blobs b WHERE b.did = r.did AND b.cid = r.cid)
             ORDER BY r.cid LIMIT ?""",
          session.did, Xrpc.param(request, "cursor").getOrElse(""), limit)(row =>
          Json.obj(
            "cid" -> Json.fromString(row.string("cid")),
            "recordUri" -> Json.fromString(
              s"at://${session.did}/${row.string("collection")}/${row.string("rkey")}")
          ))
      }
      response <- Xrpc.ok(Json.obj(
        "blobs" -> Json.arr(missing*),
        "cursor" -> missing.lastOption.flatMap(_.hcursor.get[String]("cid").toOption)
          .map(Json.fromString).getOrElse(Json.Null)
      ).deepDropNullValues)
    yield response

  val maxImportBytes = 256L * 1024 * 1024

  /** Imports a CAR archive, verifying every block. The archive is staged on
    * disk rather than held in memory, and parsed a block at a time.
    */
  private def importRepo(env: Env, request: Request[IO]): IO[Response[IO]] =
    for
      session <- Xrpc.session(env, request)
      _ <- Xrpc.requirePrivileged(session)
      parsed <- staged(request).use { file =>
        IO.blocking {
          val stream = java.nio.file.Files.newInputStream(file)
          try
            val reader = new Car.Reader(new java.io.BufferedInputStream(stream))
            for
              roots <- reader.roots()
              blocks <- readAll(reader, Vector.empty)
            yield (roots, blocks)
          finally stream.close()
        }
      }
      parsed <- IO.fromEither(parsed.left.map(XrpcError.invalidRequest))
      // A migrated account's DID still names its old host and old signing key,
      // so an archive signed by the published key is accepted as well as one
      // signed by the key reserved here.
      published <- env.resolver.resolveDid(session.did).map(_.flatMap(_.signingKey))
      now <- env.now
      _ <- env.database.transact { connection =>
        val account = Accounts.require(connection, session.did)
        val blocks = parsed._2.toMap
        val root = parsed._1.headOption.getOrElse(
          throw XrpcError.invalidRequest("The archive has no root"))
        val commit = Cbor.decode(blocks.getOrElse(root,
          throw XrpcError.invalidRequest("The archive is missing its root block")))
          .flatMap(Commit.decode).fold(message => throw XrpcError.invalidRequest(message), identity)
        if commit.did != account.did then
          throw XrpcError.invalidRequest("The archive belongs to another account")
        val key = RepoStore.signingKey(connection, account.did, env.sealing)
        val signer = (key.publicKey +: published.toVector).find(commit.verify)
          .getOrElse(throw XrpcError.invalidRequest("Commit signature does not verify"))
        Repository.verify(commit, signer, blocks.get)
          .fold(message => throw XrpcError.invalidRequest(message), identity)
        RepoStore.writeBlocks(connection, account.did, blocks, commit.rev)
        Sql.update(connection,
          """INSERT INTO repo_roots(did, commit_cid, data_cid, rev) VALUES (?, ?, ?, ?)
             ON CONFLICT (did) DO UPDATE SET commit_cid = ?, data_cid = ?, rev = ?""",
          account.did, commit.cid.toString, commit.data.toString, commit.rev,
          commit.cid.toString, commit.data.toString, commit.rev)
        reindex(connection, account.did, commit, blocks)
        Sql.update(connection,
          """INSERT INTO account_imports(did, source_document, repository_imported, created_at)
             VALUES (?, ?, ?, ?) ON CONFLICT (did) DO UPDATE SET repository_imported = ?""",
          account.did, "{}", true, now, true)
        Events.sync(connection, account.did,
          pds.repo.Head(commit, commit.data, commit.rev), Map(commit.cid -> commit.bytes))
      }
      response <- Xrpc.empty
    yield response

  /** Writes the request body to a temporary file, bounded, and removes it. */
  private def staged(request: Request[IO]): cats.effect.Resource[IO, java.nio.file.Path] =
    cats.effect.Resource.make(
      IO.blocking(java.nio.file.Files.createTempFile("pds-import", ".car")).flatMap { file =>
        request.body.take(maxImportBytes + 1)
          .through(fs2.io.file.Files[IO].writeAll(fs2.io.file.Path.fromNioPath(file)))
          .compile.drain *>
          IO.blocking(java.nio.file.Files.size(file)).flatMap { size =>
            IO.raiseWhen(size > maxImportBytes)(
              XrpcError.payloadTooLarge("Repository archive is too large"))
          }.as(file)
      }
    )(file => IO.blocking(java.nio.file.Files.deleteIfExists(file)).void)

  @annotation.tailrec
  private def readAll(
      reader: Car.Reader, acc: Vector[(Cid, Array[Byte])]
  ): Either[String, Vector[(Cid, Array[Byte])]] =
    if acc.length > 200000 then Left("Repository archive has too many blocks")
    else
      reader.next() match
        case Left(message)     => Left(message)
        case Right(None)       => Right(acc)
        case Right(Some(item)) => readAll(reader, acc :+ item)

  private def reindex(
      connection: java.sql.Connection, did: String, commit: Commit, blocks: Map[Cid, Array[Byte]]
  ): Unit =
    val store = new Mst.Store(cid => blocks.get(cid).orElse(RepoStore.blockReader(connection, did)(cid)))
    val entries = for
      tree <- store.tree(commit.data)
      listed <- MstOps.entries(store, tree)
    yield listed
    val listed = entries.fold(message => throw XrpcError.invalidRequest(message), identity)
    Sql.update(connection, "DELETE FROM records WHERE did = ?", did)
    Sql.update(connection, "DELETE FROM record_blobs WHERE did = ?", did)
    listed.foreach { (path, cid) =>
      val Array(collection, rkey) = path.split("/", 2)
      Sql.update(connection,
        "INSERT INTO records(did, collection, rkey, cid, rev, indexed_at) VALUES (?, ?, ?, ?, ?, ?)",
        did, collection, rkey, cid.toString, commit.rev, System.currentTimeMillis())
      blocks.get(cid).orElse(RepoStore.blockReader(connection, did)(cid))
        .flatMap(Cbor.decode(_).toOption).foreach { record =>
          Node.blobs(record).map(_.ref.toString).distinct.foreach(blob =>
            Sql.update(connection,
              "INSERT INTO record_blobs(did, collection, rkey, cid) VALUES (?, ?, ?, ?)",
              did, collection, rkey, blob))
        }
    }
