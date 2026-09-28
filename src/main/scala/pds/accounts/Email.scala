package pds.accounts

import cats.effect.IO
import io.circe.Json
import java.sql.Connection
import java.util.UUID
import org.http4s.Headers
import pds.{Env, XrpcError}
import pds.crypto.Hash
import pds.identity.Net
import pds.storage.Sql

/** One-time email tokens and a durable outbox. Delivery is an HTTP POST to a
  * configured endpoint, so the PDS needs no SMTP credentials of its own.
  */
object Email:
  val tokenSeconds = 900L

  def requireEnabled(env: Env): Unit =
    if !env.config.emailEnabled then
      throw XrpcError.named(org.http4s.Status.BadRequest, "EmailUnavailable",
        "Email delivery is not configured on this server")

  /** Six groups of five base32 characters, as the reference PDS uses. */
  def code(): String = Hash.randomBase32(8).take(11).toUpperCase.grouped(6).mkString("-")

  def issue(connection: Connection, account: Account, purpose: String, now: Long): String =
    val email = account.email.getOrElse(
      throw XrpcError.invalidRequest("Account has no email address"))
    Sql.update(connection,
      "DELETE FROM account_tokens WHERE did = ? AND purpose = ?", account.did, purpose)
    val token = code()
    Sql.update(connection,
      """INSERT INTO account_tokens(token_hash, did, purpose, email, created_at, expires_at)
         VALUES (?, ?, ?, ?, ?, ?)""",
      Hash.digestToken(token), account.did, purpose, email, now, now + tokenSeconds * 1000)
    enqueue(connection, email, purpose, token, now)
    token

  def consume(
      connection: Connection, purpose: String, token: String, did: String, email: String, now: Long
  ): Unit =
    val row = Sql.first(connection,
      """SELECT did, email, expires_at FROM account_tokens WHERE token_hash = ? AND purpose = ?""",
      Hash.digestToken(token.trim.toUpperCase), purpose)(row =>
      (row.string("did"), row.string("email"), row.long("expires_at")))
    row match
      case Some((tokenDid, tokenEmail, expires))
        if tokenDid == did && tokenEmail == email && expires > now =>
        Sql.update(connection, "DELETE FROM account_tokens WHERE token_hash = ?",
          Hash.digestToken(token.trim.toUpperCase))
      case _ =>
        throw XrpcError.named(org.http4s.Status.BadRequest, "InvalidToken",
          "Token is invalid or has expired")

  def enqueue(connection: Connection, to: String, purpose: String, token: String, now: Long): Unit =
    send(connection, to, purpose, Json.obj("token" -> Json.fromString(token)), now)

  def send(connection: Connection, to: String, purpose: String, fields: Json, now: Long): Unit =
    Sql.update(connection,
      """INSERT INTO email_outbox(id, payload, available_at, created_at) VALUES (?, ?, ?, ?)""",
      UUID.randomUUID().toString,
      Json.obj(
        "to" -> Json.fromString(to),
        "purpose" -> Json.fromString(purpose)
      ).deepMerge(fields).noSpaces,
      now, now)

  /** Drains pending messages; failures are retried with a backoff. */
  def deliver(env: Env): IO[Int] =
    if !env.config.emailEnabled then IO.pure(0)
    else
      env.now.flatMap { now =>
        env.database.transact(connection =>
          Sql.query(connection,
            "SELECT id, payload, attempts FROM email_outbox WHERE status = 'pending' AND available_at <= ? LIMIT 20",
            now)(row => (row.string("id"), row.string("payload"), row.int("attempts")))
        ).flatMap { pending =>
          pending.traverseCount { (id, payload, attempts) =>
            val body = io.circe.parser.parse(payload).getOrElse(Json.obj())
            val headers = env.config.emailToken
              .map(token => Headers(env.net.header("Authorization", s"Bearer $token")))
              .getOrElse(Headers.empty)
            env.net.postJson(env.config.emailEndpoint.get, body.deepMerge(
              Json.obj("from" -> Json.fromString(env.config.emailFrom))), headers).flatMap {
              case Right(_) =>
                env.database.transact(connection =>
                  Sql.update(connection,
                    "UPDATE email_outbox SET status = 'sent', sent_at = ? WHERE id = ?", now, id)
                ).as(1)
              case Left(error) =>
                val failed = attempts + 1 >= 5
                env.database.transact(connection =>
                  Sql.update(connection,
                    """UPDATE email_outbox SET attempts = ?, last_error = ?, status = ?,
                       available_at = ? WHERE id = ?""",
                    attempts + 1, error.take(500), if failed then "failed" else "pending",
                    now + math.min(3600, 30 * (1 << attempts)).toLong * 1000, id)
                ).as(0)
            }
          }
        }
      }

  extension [A](values: Vector[A])
    private def traverseCount(work: A => IO[Int]): IO[Int] =
      values.foldLeft(IO.pure(0))((acc, value) => acc.flatMap(total => work(value).map(_ + total)))
