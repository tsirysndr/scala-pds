package pds.repo

import cats.effect.IO
import java.sql.Connection
import pds.{Env, XrpcError}
import pds.protocol.Cid
import pds.storage.{S3, Sql}

/** Blob bytes live either in the database or in an S3-compatible bucket.
  *
  * The object is written before the row that names it, so a failure leaves an
  * unreferenced object rather than a row pointing at nothing; blobs are
  * content-addressed, so a retry overwrites it with identical bytes. Deletions
  * go through a queue, because the transaction that removes the row cannot wait
  * on a remote call.
  */
object BlobStore:
  def objectKey(prefix: String, did: String, cid: Cid): String =
    s"$prefix${did.replace(':', '_')}/$cid"

  def store(
      env: Env, did: String, cid: Cid, mimeType: String, bytes: Array[Byte]
  ): IO[Unit] =
    env.blobs match
      case None =>
        env.now.flatMap { now =>
          env.database.transact { connection =>
            insert(connection, did, cid, mimeType, bytes.length.toLong, "database",
              Some(bytes), None, None, now)
          }
        }
      case Some(s3) =>
        val key = objectKey(s3.prefix, did, cid)
        s3.put(key, mimeType, bytes).flatMap {
          case Left(reason) =>
            IO.raiseError(XrpcError.named(org.http4s.Status.BadGateway, "BlobStoreFailed",
              s"The blob could not be stored: $reason"))
          case Right(()) =>
            env.now.flatMap { now =>
              env.database.transact { connection =>
                insert(connection, did, cid, mimeType, bytes.length.toLong, "s3",
                  None, Some(s3.bucket), Some(key), now)
              }
            }
        }

  private def insert(
      connection: Connection,
      did: String,
      cid: Cid,
      mimeType: String,
      size: Long,
      backend: String,
      content: Option[Array[Byte]],
      bucket: Option[String],
      key: Option[String],
      now: Long
  ): Unit =
    pds.accounts.Accounts.requireActive(connection, did)
    Sql.update(connection,
      """INSERT INTO blobs(did, cid, mime_type, size, storage_backend, content,
         object_bucket, object_key, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT (did, cid) DO NOTHING""",
      did, cid.toString, mimeType, size, backend, content, bucket, key, now)

  final case class Stored(mimeType: String, size: Long, backend: String,
      content: Option[Array[Byte]], bucket: Option[String], key: Option[String])

  def locate(connection: Connection, did: String, cid: Cid): Option[Stored] =
    Sql.first(connection,
      """SELECT mime_type, size, storage_backend, content, object_bucket, object_key, takedown_ref
         FROM blobs WHERE did = ? AND cid = ?""", did, cid.toString)(row =>
      (Stored(row.string("mime_type"), row.long("size"), row.string("storage_backend"),
        row.bytesOpt("content"), row.stringOpt("object_bucket"), row.stringOpt("object_key")),
        row.stringOpt("takedown_ref")))
      .flatMap((stored, takedown) => Option.when(takedown.isEmpty)(stored))

  def read(env: Env, did: String, cid: Cid): IO[(Array[Byte], String)] =
    env.database.read { connection =>
      pds.accounts.Accounts.requireActive(connection, did)
      locate(connection, did, cid)
    }.flatMap {
      case None =>
        IO.raiseError(XrpcError.named(org.http4s.Status.NotFound, "BlobNotFound",
          "Blob was not found"))
      case Some(stored) =>
        stored.content match
          case Some(bytes) => IO.pure(bytes -> stored.mimeType)
          case None =>
            (env.blobs, stored.key) match
              case (Some(s3), Some(key)) => s3.get(key).flatMap {
                case Right(bytes) => IO.pure(bytes -> stored.mimeType)
                case Left(reason) =>
                  IO.raiseError(XrpcError.named(org.http4s.Status.BadGateway, "BlobStoreFailed",
                    s"The blob could not be read: $reason"))
              }
              case _ =>
                IO.raiseError(XrpcError.named(org.http4s.Status.NotFound, "BlobNotFound",
                  "Blob was not found"))
    }

  /** Queues an account's objects for deletion, in the same transaction that
    * removes its rows, so nothing is forgotten if the process stops here.
    */
  def forget(connection: Connection, did: String, now: Long): Unit =
    Sql.query(connection,
      """SELECT object_bucket, object_key FROM blobs
         WHERE did = ? AND storage_backend = 's3'""", did)(row =>
      (row.string("object_bucket"), row.string("object_key")))
      .foreach { (bucket, key) =>
        Sql.update(connection,
          """INSERT INTO blob_deletions(object_bucket, object_key, available_at, created_at)
             VALUES (?, ?, ?, ?) ON CONFLICT (object_bucket, object_key) DO NOTHING""",
          bucket, key, now, now)
      }

  /** Drains queued deletions; failures retry with a backoff. */
  def sweep(env: Env): IO[Int] =
    env.blobs match
      case None => IO.pure(0)
      case Some(s3) =>
        env.now.flatMap { now =>
          env.database.read { connection =>
            Sql.query(connection,
              """SELECT object_bucket, object_key, attempts FROM blob_deletions
                 WHERE object_bucket = ? AND available_at <= ? LIMIT 50""", s3.bucket, now)(row =>
              (row.string("object_key"), row.int("attempts")))
          }.flatMap { pending =>
            pending.foldLeft(IO.pure(0)) { (acc, entry) =>
              val (key, attempts) = entry
              acc.flatMap { total =>
                s3.delete(key).flatMap {
                  case Right(()) =>
                    env.database.transact(connection =>
                      Sql.update(connection,
                        "DELETE FROM blob_deletions WHERE object_bucket = ? AND object_key = ?",
                        s3.bucket, key)).as(total + 1)
                  case Left(reason) =>
                    env.database.transact(connection =>
                      Sql.update(connection,
                        """UPDATE blob_deletions SET attempts = ?, last_error = ?, available_at = ?
                           WHERE object_bucket = ? AND object_key = ?""",
                        attempts + 1, reason.take(500),
                        now + math.min(3600L, 30L * (1L << math.min(attempts, 10))) * 1000,
                        s3.bucket, key)).as(total)
                }
              }
            }
          }
        }
