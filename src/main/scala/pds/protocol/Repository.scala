package pds.protocol

import pds.crypto.{PrivateKey, PublicKey}

final case class Commit(
    did: String,
    version: Int,
    data: Cid,
    rev: String,
    prev: Option[Cid],
    sig: Array[Byte]
):
  def unsigned: Node = Commit.unsigned(did, version, data, rev, prev)

  def node: Node = unsigned match
    case Node.Obj(fields) => Node.Obj(fields.updated("sig", Node.Bytes(sig)))
    case other            => other

  def bytes: Array[Byte] = Cbor.encode(node)
  def cid: Cid = Cid.ofCbor(bytes)

  def verify(key: PublicKey): Boolean = key.verify(Cbor.encode(unsigned), sig)

object Commit:
  val version = 3

  def unsigned(did: String, version: Int, data: Cid, rev: String, prev: Option[Cid]): Node =
    Node.Obj(Map(
      "did" -> Node.Text(did),
      "version" -> Node.Integer(version.toLong),
      "data" -> Node.Link(data),
      "rev" -> Node.Text(rev),
      "prev" -> prev.map(Node.Link.apply).getOrElse(Node.Null)
    ))

  def sign(did: String, data: Cid, rev: String, prev: Option[Cid], key: PrivateKey): Commit =
    val body = unsigned(did, version, data, rev, prev)
    Commit(did, version, data, rev, prev, key.sign(Cbor.encode(body)))

  def decode(node: Node): Either[String, Commit] =
    for
      did <- node("did").flatMap(_.asString).filter(Syntax.isDid).toRight("Commit has no DID")
      version <- node("version").flatMap(_.asLong).filter(_ == 3L).toRight("Unsupported commit version")
      data <- node("data").flatMap(_.asLink).toRight("Commit has no data link")
      rev <- node("rev").flatMap(_.asString).filter(Syntax.isTid).toRight("Commit has no revision")
      sig <- node("sig").flatMap(_.asBytes).toRight("Commit is not signed")
      prev = node("prev").flatMap(_.asLink)
    yield Commit(did, version.toInt, data, rev, prev, sig)

/** Writes applied to a repository in a single commit. */
enum Write:
  case Create(collection: String, recordKey: String, record: Node)
  case Update(collection: String, recordKey: String, record: Node)
  case Delete(collection: String, recordKey: String)

  def collection: String
  def recordKey: String
  def path: String = s"$collection/$recordKey"

  def action: String = this match
    case _: Create => "create"
    case _: Update => "update"
    case _: Delete => "delete"

final case class Op(action: String, path: String, cid: Option[Cid], prev: Option[Cid])

final case class Applied(
    commit: Commit,
    commitCid: Cid,
    root: Cid,
    previousRoot: Option[Cid],
    blocks: Map[Cid, Array[Byte]],
    ops: Vector[Op],
    records: Map[String, (Cid, Node)]
)

object Repository:
  val maxRecordSize = 64 * 1024

  /** Creates the genesis commit: an empty tree signed at revision `rev`. */
  def create(did: String, rev: String, key: PrivateKey): Either[String, Applied] =
    val store = new Mst.Store(_ => None)
    for
      root <- store.persist(Mst.empty)
      commit = Commit.sign(did, root, rev, None, key)
      blocks = store.blocks.updated(commit.cid, commit.bytes)
    yield Applied(commit, commit.cid, root, None, blocks, Vector.empty, Map.empty)

  def apply(
      did: String,
      head: Commit,
      rev: String,
      key: PrivateKey,
      writes: Vector[Write],
      reader: Cid => Option[Array[Byte]]
  ): Either[String, Applied] =
    val store = new Mst.Store(reader)
    val newBlocks = scala.collection.mutable.LinkedHashMap.empty[Cid, Array[Byte]]
    for
      _ <- Either.cond(writes.nonEmpty, (), "A commit needs at least one write")
      _ <- Either.cond(writes.map(_.path).distinct.length == writes.length, (),
        "A commit cannot write the same record twice")
      start <- store.tree(head.data)
      folded <- writes.foldLeft[Either[String, (Mst.Tree, Vector[Op], Map[String, (Cid, Node)])]](
        Right((start, Vector.empty, Map.empty))
      ) { case (acc, write) =>
        for
          state <- acc
          (tree, ops, records) = state
          _ <- Either.cond(Mst.isValidKey(write.path), (), s"Invalid record path ${write.path}")
          existing <- MstOps.get(store, tree, write.path)
          result <- write match
            case Write.Delete(_, _) =>
              for
                _ <- existing.toRight("Record was not found in the repository")
                updated <- MstOps.delete(store, tree, write.path)
              yield (updated, ops :+ Op("delete", write.path, None, existing), records)
            case Write.Create(_, _, record) if existing.isDefined =>
              Left("Record already exists at that key")
            case Write.Create(_, _, record) =>
              stage(newBlocks, record).flatMap { cid =>
                MstOps.put(store, tree, write.path, cid).map { updated =>
                  (updated, ops :+ Op("create", write.path, Some(cid), None),
                    records.updated(write.path, cid -> record))
                }
              }
            case Write.Update(_, _, record) =>
              stage(newBlocks, record).flatMap { cid =>
                MstOps.put(store, tree, write.path, cid).map { updated =>
                  (updated, ops :+ Op("update", write.path, Some(cid), existing),
                    records.updated(write.path, cid -> record))
                }
              }
        yield result
      }
      (tree, ops, records) = folded
      root <- store.persist(tree)
      commit = Commit.sign(did, root, rev, Some(head.cid), key)
      blocks = store.blocks ++ newBlocks.toMap + (commit.cid -> commit.bytes)
    yield Applied(commit, commit.cid, root, Some(head.data), blocks, ops, records)

  private def stage(
      sink: scala.collection.mutable.LinkedHashMap[Cid, Array[Byte]], record: Node
  ): Either[String, Cid] =
    val bytes = Cbor.encode(record)
    if bytes.length > maxRecordSize then Left("Record exceeds the maximum size")
    else
      val cid = Cid.ofCbor(bytes)
      sink.update(cid, bytes)
      Right(cid)

  /** Verifies a commit chain head against a signing key and its own tree. */
  def verify(commit: Commit, key: PublicKey, reader: Cid => Option[Array[Byte]]): Either[String, Unit] =
    val store = new Mst.Store(reader)
    for
      _ <- Either.cond(commit.verify(key), (), "Commit signature does not verify")
      tree <- store.tree(commit.data)
      _ <- MstOps.entries(store, tree)
    yield ()
