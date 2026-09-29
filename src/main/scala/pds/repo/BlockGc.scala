package pds.repo

import cats.effect.IO
import java.sql.Connection
import pds.Env
import pds.protocol.{Cid, Mst, MstOps}
import pds.storage.Sql

/** Reclaims repository blocks that no longer serve anyone.
  *
  * A block is kept when it is reachable from the current commit, or when its
  * revision is still needed to satisfy firehose backfill. Everything else is an
  * orphan left by an overwritten or deleted record, and is removed. Reachable
  * blocks are never deleted, whatever their revision, so the repository stays
  * exportable and verifiable.
  */
object BlockGc:
  final case class Result(did: String, examined: Int, removed: Int)

  /** The oldest revision still required: the earliest retained event for the
    * account, or the current head when no events remain.
    */
  def cutoff(connection: Connection, did: String): Option[String] =
    Sql.first(connection,
      "SELECT MIN(rev) AS rev FROM repo_events WHERE did = ? AND rev IS NOT NULL", did)(
      _.stringOpt("rev")).flatten
      .orElse(RepoStore.head(connection, did).map(_.rev))

  def reachable(connection: Connection, did: String): Either[String, Set[Cid]] =
    RepoStore.head(connection, did) match
      case None => Right(Set.empty)
      case Some(head) =>
        val store = new Mst.Store(RepoStore.blockReader(connection, did))
        for
          nodes <- MstOps.nodeCids(store, head.root)
          tree <- store.tree(head.root)
          leaves <- MstOps.entries(store, tree)
        yield (head.commit.cid +: nodes).toSet ++ leaves.map(_._2)

  def collect(env: Env, did: String): IO[Result] =
    env.database.transact { connection =>
      cutoff(connection, did) match
        case None => Result(did, 0, 0)
        case Some(boundary) =>
          val live = reachable(connection, did)
            .fold(message => throw new IllegalStateException(message), identity)
          val candidates = Sql.query(connection,
            "SELECT cid FROM repo_blocks WHERE did = ? AND rev < ?", did, boundary)(
            _.string("cid"))
          val removable = candidates.filterNot(cid =>
            Cid.parse(cid).exists(live.contains))
          removable.foreach(cid =>
            Sql.update(connection, "DELETE FROM repo_blocks WHERE did = ? AND cid = ?", did, cid))
          Result(did, candidates.length, removable.length)
    }

  /** Collects a bounded number of accounts per pass, oldest cursor first. */
  def sweep(env: Env, limit: Int = 25): IO[Vector[Result]] =
    if env.config.firehoseRetentionHours <= 0 then IO.pure(Vector.empty)
    else
      env.database.read { connection =>
        Sql.query(connection,
          "SELECT did FROM repo_roots ORDER BY did LIMIT ?", limit)(_.string("did"))
      }.flatMap { dids =>
        dids.foldLeft(IO.pure(Vector.empty[Result])) { (acc, did) =>
          for
            results <- acc
            result <- collect(env, did).handleError(_ => Result(did, 0, 0))
          yield results :+ result
        }
      }

  /** Drops firehose events past the retention window. */
  def expireEvents(env: Env): IO[Int] =
    val hours = env.config.firehoseRetentionHours
    if hours <= 0 then IO.pure(0)
    else
      env.now.flatMap { now =>
        env.database.transact { connection =>
          Sql.update(connection, "DELETE FROM repo_events WHERE created_at < ?",
            now - hours.toLong * 3600_000L)
        }
      }
