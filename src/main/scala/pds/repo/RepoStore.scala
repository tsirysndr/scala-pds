package pds.repo

import java.sql.Connection
import pds.XrpcError
import pds.crypto.{Curve, PrivateKey, Sealing}
import pds.protocol.*
import pds.storage.{Database, Sql}

final case class Head(commit: Commit, root: Cid, rev: String)

/** Repository persistence: blocks, the record index, roots and the event log.
  * Blocks are append-only so historical revisions stay resolvable for the
  * firehose; exports walk the current tree, so orphans are never served.
  */
object RepoStore:
  def blockReader(connection: Connection, did: String): Cid => Option[Array[Byte]] =
    cid =>
      Sql.first(connection, "SELECT content FROM repo_blocks WHERE did = ? AND cid = ?",
        did, cid.toString)(_.bytes("content"))

  def head(connection: Connection, did: String): Option[Head] =
    Sql.first(connection,
      "SELECT commit_cid, data_cid, rev FROM repo_roots WHERE did = ?", did)(row =>
      (row.string("commit_cid"), row.string("data_cid"), row.string("rev"))
    ).flatMap { (commitCid, dataCid, rev) =>
      for
        cid <- Cid.parse(commitCid)
        root <- Cid.parse(dataCid)
        bytes <- blockReader(connection, did)(cid)
        node <- Cbor.decode(bytes).toOption
        commit <- Commit.decode(node).toOption
      yield Head(commit, root, rev)
    }

  def requireHead(connection: Connection, did: String): Head =
    head(connection, did).getOrElse(
      throw XrpcError.notFound("Repository was not found for this account"))

  def signingKey(connection: Connection, did: String, sealing: Sealing): PrivateKey =
    val row = Sql.first(connection,
      "SELECT signing_curve, signing_sealed FROM account_keys WHERE did = ?", did)(row =>
      (row.string("signing_curve"), row.bytes("signing_sealed"))
    ).getOrElse(throw XrpcError.internal("Account has no signing key"))
    val curve = Curve.values.find(_.name == row._1)
      .getOrElse(throw XrpcError.internal("Unknown signing curve"))
    sealing.openKey(curve, row._2).getOrElse(throw XrpcError.internal("Signing key cannot be opened"))

  def create(connection: Connection, did: String, key: PrivateKey): Head =
    val rev = Tid.next()
    val applied = Repository.create(did, rev, key).fold(message =>
      throw XrpcError.internal(message), identity)
    writeBlocks(connection, did, applied.blocks, rev)
    Sql.update(connection,
      "INSERT INTO repo_roots(did, commit_cid, data_cid, rev) VALUES (?, ?, ?, ?)",
      did, applied.commitCid.toString, applied.root.toString, rev)
    Head(applied.commit, applied.root, rev)

  def writeBlocks(
      connection: Connection, did: String, blocks: Map[Cid, Array[Byte]], rev: String
  ): Unit =
    blocks.foreach { (cid, bytes) =>
      Sql.update(connection,
        """INSERT INTO repo_blocks(did, cid, content, rev) VALUES (?, ?, ?, ?)
           ON CONFLICT (did, cid) DO NOTHING""",
        did, cid.toString, bytes, rev)
    }

  /** Applies writes, persists the new head, reindexes records and records the
    * event the firehose replays.
    */
  def commit(
      connection: Connection,
      did: String,
      sealing: Sealing,
      writes: Vector[Write],
      swapCommit: Option[Cid] = None
  ): Applied =
    val current = requireHead(connection, did)
    swapCommit.foreach { expected =>
      if expected != current.commit.cid then
        throw XrpcError.named(org.http4s.Status.BadRequest, "InvalidSwap",
          "Repository has changed since the expected commit")
    }
    val key = signingKey(connection, did, sealing)
    val rev = Tid.next()
    val applied = Repository.apply(did, current.commit, rev, key, writes,
      blockReader(connection, did)).fold(message => throw XrpcError.invalidRequest(message), identity)
    writeBlocks(connection, did, applied.blocks, rev)
    Sql.update(connection,
      "UPDATE repo_roots SET commit_cid = ?, data_cid = ?, rev = ? WHERE did = ?",
      applied.commitCid.toString, applied.root.toString, rev, did)
    applied.ops.foreach(operation => indexOp(connection, did, operation, rev, applied))
    applied

  private def indexOp(
      connection: Connection, did: String, operation: Op, rev: String, applied: Applied
  ): Unit =
    val Array(collection, rkey) = operation.path.split("/", 2)
    operation.action match
      case "delete" =>
        Sql.update(connection, "DELETE FROM records WHERE did = ? AND collection = ? AND rkey = ?",
          did, collection, rkey)
        Sql.update(connection,
          "DELETE FROM record_blobs WHERE did = ? AND collection = ? AND rkey = ?",
          did, collection, rkey)
      case _ =>
        val cid = operation.cid.get.toString
        Sql.update(connection,
          """INSERT INTO records(did, collection, rkey, cid, rev, indexed_at) VALUES (?, ?, ?, ?, ?, ?)
             ON CONFLICT (did, collection, rkey) DO UPDATE SET cid = ?, rev = ?, indexed_at = ?""",
          did, collection, rkey, cid, rev, System.currentTimeMillis(),
          cid, rev, System.currentTimeMillis())
        Sql.update(connection,
          "DELETE FROM record_blobs WHERE did = ? AND collection = ? AND rkey = ?",
          did, collection, rkey)
        applied.records.get(operation.path).foreach { (_, record) =>
          Node.blobs(record).map(_.ref.toString).distinct.foreach { blob =>
            Sql.update(connection,
              "INSERT INTO record_blobs(did, collection, rkey, cid) VALUES (?, ?, ?, ?)",
              did, collection, rkey, blob)
          }
        }

  def record(connection: Connection, did: String, collection: String, rkey: String): Option[(Cid, Node)] =
    for
      cidText <- Sql.first(connection,
        "SELECT cid FROM records WHERE did = ? AND collection = ? AND rkey = ? AND takedown_ref IS NULL",
        did, collection, rkey)(_.string("cid"))
      cid <- Cid.parse(cidText)
      bytes <- blockReader(connection, did)(cid)
      node <- Cbor.decode(bytes).toOption
    yield (cid, node)

  final case class Listing(rkey: String, cid: Cid, value: Node)

  def listRecords(
      connection: Connection,
      did: String,
      collection: String,
      limit: Int,
      cursor: Option[String],
      reverse: Boolean
  ): Vector[Listing] =
    val comparison = if reverse then ">" else "<"
    val order = if reverse then "ASC" else "DESC"
    val bound = cursor.getOrElse(if reverse then "" else "￿")
    Sql.query(connection,
      s"""SELECT rkey, cid FROM records
          WHERE did = ? AND collection = ? AND takedown_ref IS NULL AND rkey $comparison ?
          ORDER BY rkey $order LIMIT ?""",
      did, collection, bound, limit)(row => (row.string("rkey"), row.string("cid")))
      .flatMap { (rkey, cidText) =>
        for
          cid <- Cid.parse(cidText)
          bytes <- blockReader(connection, did)(cid)
          node <- Cbor.decode(bytes).toOption
        yield Listing(rkey, cid, node)
      }

  def collections(connection: Connection, did: String): Vector[String] =
    Sql.query(connection,
      "SELECT DISTINCT collection FROM records WHERE did = ? ORDER BY collection", did)(
      _.string("collection"))

  /** Every block reachable from the current commit, for CAR export. */
  def exportBlocks(connection: Connection, did: String): Either[String, (Cid, Vector[(Cid, Array[Byte])])] =
    val reader = blockReader(connection, did)
    head(connection, did).toRight("Repository was not found").flatMap { current =>
      val store = new Mst.Store(reader)
      for
        nodes <- MstOps.nodeCids(store, current.root)
        tree <- store.tree(current.root)
        leaves <- MstOps.entries(store, tree)
        cids = (current.commit.cid +: nodes) ++ leaves.map(_._2)
        blocks <- cids.distinct.foldLeft[Either[String, Vector[(Cid, Array[Byte])]]](Right(Vector.empty)) {
          (acc, cid) =>
            for
              list <- acc
              bytes <- reader(cid).toRight(s"Missing block during export")
            yield list :+ (cid -> bytes)
        }
      yield (current.commit.cid, blocks)
    }
