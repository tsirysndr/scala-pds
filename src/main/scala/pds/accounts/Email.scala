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

  /** The endpoint owns sender identity and provider credentials, so a message on
    * the wire is exactly `{to, subject, text}`. Templating happens here rather
    * than in the endpoint, which only has a purpose string to go on otherwise.
    */
  def render(hostname: String, payload: Json): Option[Json] =
    val cursor = payload.hcursor
    val body =
      for
        to <- cursor.get[String]("to").toOption
        rendered <- cursor.get[String]("purpose").toOption.flatMap { purpose =>
          def coded(subject: String, lead: String): Option[(String, String)] =
            cursor.get[String]("token").toOption.map { code =>
              (subject, s"$lead\n\n    $code\n\n" +
                s"The code is valid for ${tokenSeconds / 60} minutes. " +
                s"If you did not ask for it, you can ignore this message.\n\n$hostname\n")
            }
          purpose match
            case "sign-in" => coded(s"Your $hostname sign-in code",
              "Use this code to finish signing in:")
            case "confirm-email" => coded(s"Confirm your email address on $hostname",
              "Use this code to confirm your email address:")
            case "update-email" => coded(s"Confirm your new email address on $hostname",
              "Use this code to confirm your new email address:")
            case "reset-password" => coded(s"Reset your $hostname password",
              "Use this code to set a new password:")
            case "delete-account" => coded(s"Confirm deleting your $hostname account",
              "Use this code to confirm deleting your account. This cannot be undone:")
            case "plc-operation" => coded(s"Confirm an identity change on $hostname",
              "Use this code to confirm a change to your identity:")
            case "admin-notice" =>
              for
                subject <- cursor.get[String]("subject").toOption
                content <- cursor.get[String]("content").toOption
              yield (subject, content)
            case _ => None
        }
      yield Json.obj(
        "to" -> Json.fromString(to),
        "subject" -> Json.fromString(rendered._1),
        "text" -> Json.fromString(rendered._2)
      )
    body

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
            def give(error: String, permanent: Boolean): IO[Int] =
              val failed = permanent || attempts + 1 >= 5
              env.database.transact(connection =>
                Sql.update(connection,
                  """UPDATE email_outbox SET attempts = ?, last_error = ?, status = ?,
                     available_at = ? WHERE id = ?""",
                  attempts + 1, error.take(500), if failed then "failed" else "pending",
                  now + math.min(3600, 30 * (1 << attempts)).toLong * 1000, id)
              ).as(0)
            render(env.config.hostname,
              io.circe.parser.parse(payload).getOrElse(Json.obj())) match
              case None => give("Message cannot be rendered", permanent = true)
              case Some(body) =>
                // The row id is the idempotency key, so a retry after an
                // ambiguous failure cannot deliver the same message twice.
                val headers = Headers(env.net.header("Idempotency-Key", id)) ++
                  env.config.emailToken
                    .map(token => Headers(env.net.header("Authorization", s"Bearer $token")))
                    .getOrElse(Headers.empty)
                env.net.postJson(env.config.emailEndpoint.get, body, headers).flatMap {
                  case Right(_) =>
                    env.database.transact(connection =>
                      Sql.update(connection,
                        "UPDATE email_outbox SET status = 'sent', sent_at = ? WHERE id = ?", now, id)
                    ).as(1)
                  case Left(error) => give(error, permanent = false)
                }
          }
        }
      }

  extension [A](values: Vector[A])
    private def traverseCount(work: A => IO[Int]): IO[Int] =
      values.foldLeft(IO.pure(0))((acc, value) => acc.flatMap(total => work(value).map(_ + total)))
