package pds.firehose

import java.sql.Connection
import pds.protocol.*
import pds.storage.Sql

final case class Event(seq: Long, kind: String, did: String, body: Node)

/** The durable sequence behind `com.atproto.sync.subscribeRepos`. Events are
  * written in the same transaction as the change they describe, so a consumer
  * never sees a commit the repository does not have.
  */
object Events:
  val maxBackfill = 1000

  def commit(
      connection: Connection,
      did: String,
      applied: Applied,
      since: Option[String]
  ): Long =
    val car = Car.write(Vector(applied.commitCid), applied.blocks.toVector)
    val body = Node.Obj(Map(
      "rebase" -> Node.Bool(false),
      "tooBig" -> Node.Bool(false),
      "repo" -> Node.Text(did),
      "commit" -> Node.Link(applied.commitCid),
      "rev" -> Node.Text(applied.commit.rev),
      "since" -> since.map(Node.Text.apply).getOrElse(Node.Null),
      "blocks" -> Node.Bytes(car),
      "ops" -> Node.Arr(applied.ops.map { operation =>
        Node.Obj(Map(
          "action" -> Node.Text(operation.action),
          "path" -> Node.Text(operation.path),
          "cid" -> operation.cid.map(Node.Link.apply).getOrElse(Node.Null)
        ))
      }),
      "blobs" -> Node.Arr(applied.records.values.toVector
        .flatMap((_, record) => Node.blobs(record)).map(blob => Node.Link(blob.ref)).distinct),
      "prevData" -> applied.previousRoot.map(Node.Link.apply).getOrElse(Node.Null),
      "time" -> Node.Text(Syntax.datetime(java.time.Instant.now()))
    ))
    insert(connection, did, "#commit", Some(applied.commit.rev), body)

  def identity(connection: Connection, did: String, handle: Option[String]): Long =
    insert(connection, did, "#identity", None, Node.Obj(Map(
      "did" -> Node.Text(did),
      "handle" -> handle.map(Node.Text.apply).getOrElse(Node.Null),
      "time" -> Node.Text(Syntax.datetime(java.time.Instant.now()))
    )))

  def account(connection: Connection, did: String, active: Boolean, status: Option[String]): Long =
    insert(connection, did, "#account", None, Node.Obj(Map(
      "did" -> Node.Text(did),
      "active" -> Node.Bool(active),
      "status" -> status.map(Node.Text.apply).getOrElse(Node.Null),
      "time" -> Node.Text(Syntax.datetime(java.time.Instant.now()))
    )))

  def sync(connection: Connection, did: String, head: pds.repo.Head, blocks: Map[Cid, Array[Byte]]): Long =
    insert(connection, did, "#sync", Some(head.rev), Node.Obj(Map(
      "did" -> Node.Text(did),
      "blocks" -> Node.Bytes(Car.write(Vector(head.commit.cid), blocks.toVector)),
      "rev" -> Node.Text(head.rev),
      "time" -> Node.Text(Syntax.datetime(java.time.Instant.now()))
    )))

  private def insert(
      connection: Connection, did: String, kind: String, rev: Option[String], body: Node
  ): Long =
    Sql.insertReturningId(connection,
      "INSERT INTO repo_events(did, kind, rev, payload, created_at) VALUES (?, ?, ?, ?, ?)",
      did, kind, rev, Cbor.encode(body), System.currentTimeMillis())

  def latest(connection: Connection): Long =
    Sql.first(connection, "SELECT COALESCE(MAX(seq), 0) AS total FROM repo_events")(_.long("total"))
      .getOrElse(0L)

  /** The lowest sequence number still retained, or 0 when there is none. */
  def oldest(connection: Connection): Long =
    Sql.first(connection, "SELECT COALESCE(MIN(seq), 0) AS total FROM repo_events")(_.long("total"))
      .getOrElse(0L)

  def since(connection: Connection, cursor: Long, limit: Int): Vector[Event] =
    Sql.query(connection,
      "SELECT seq, kind, did, payload FROM repo_events WHERE seq > ? ORDER BY seq LIMIT ?",
      cursor, limit)(row =>
      (row.long("seq"), row.string("kind"), row.string("did"), row.bytes("payload"))
    ).flatMap { (seq, kind, did, payload) =>
      Cbor.decode(payload).toOption.map(body => Event(seq, kind, did, body))
    }

  /** Wire frame: a header envelope followed by the body, both DAG-CBOR. */
  def frame(event: Event): Array[Byte] =
    val header = Node.Obj(Map("op" -> Node.Integer(1L), "t" -> Node.Text(event.kind)))
    val body = event.body match
      case Node.Obj(fields) => Node.Obj(fields.updated("seq", Node.Integer(event.seq)))
      case other            => other
    Cbor.encode(header) ++ Cbor.encode(body)

  /** An `#info` message, which carries a notice without ending the stream. */
  def infoFrame(name: String, message: String): Array[Byte] =
    Cbor.encode(Node.Obj(Map("op" -> Node.Integer(1L), "t" -> Node.Text("#info")))) ++
      Cbor.encode(Node.Obj(Map(
        "name" -> Node.Text(name),
        "message" -> Node.Text(message)
      )))

  def errorFrame(error: String, message: String): Array[Byte] =
    Cbor.encode(Node.Obj(Map("op" -> Node.Integer(-1L)))) ++
      Cbor.encode(Node.Obj(Map(
        "error" -> Node.Text(error),
        "message" -> Node.Text(message)
      )))
