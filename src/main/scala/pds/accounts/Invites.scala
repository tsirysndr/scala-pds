package pds.accounts

import io.circe.Json
import java.sql.Connection
import pds.XrpcError
import pds.crypto.Hash
import pds.storage.Sql

object Invites:
  def generate(hostname: String): String = s"$hostname-${Hash.randomBase32(5)}"

  def create(
      connection: Connection, hostname: String, useCount: Int, forAccount: String, createdBy: String,
      now: Long
  ): String =
    val code = generate(hostname)
    Sql.update(connection,
      """INSERT INTO invite_codes(code, available, for_account, created_by, created_at)
         VALUES (?, ?, ?, ?, ?)""",
      code, useCount, forAccount, createdBy, now)
    code

  /** Consumes one use of a code, refusing disabled, exhausted or absent codes. */
  def consume(connection: Connection, code: String, did: String, now: Long): Unit =
    val row = Sql.first(connection,
      "SELECT available, disabled, for_account FROM invite_codes WHERE code = ?", code)(row =>
      (row.int("available"), row.bool("disabled"), row.string("for_account"))
    ).getOrElse(throw XrpcError.named(org.http4s.Status.BadRequest, "InvalidInviteCode",
      "Invite code is not available"))
    val used = Sql.count(connection,
      "SELECT COUNT(*) AS total FROM invite_uses WHERE code = ?", code)
    if row._2 || used >= row._1 then
      throw XrpcError.named(org.http4s.Status.BadRequest, "InvalidInviteCode",
        "Invite code is not available")
    if row._3 != "admin" && Accounts.byDid(connection, row._3).exists(_.invitesDisabled) then
      throw XrpcError.named(org.http4s.Status.BadRequest, "InvalidInviteCode",
        "Invite code is not available")
    Sql.update(connection, "INSERT INTO invite_uses(code, used_by, used_at) VALUES (?, ?, ?)",
      code, did, now)

  def forAccount(connection: Connection, did: String): Vector[Json] =
    Sql.query(connection,
      """SELECT code, available, disabled, for_account, created_by, created_at
         FROM invite_codes WHERE for_account = ? ORDER BY created_at DESC""", did)(row =>
      (row.string("code"), row.int("available"), row.bool("disabled"), row.string("for_account"),
        row.string("created_by"), row.long("created_at"))
    ).map { (code, available, disabled, account, createdBy, createdAt) =>
      val uses = Sql.query(connection,
        "SELECT used_by, used_at FROM invite_uses WHERE code = ? ORDER BY used_at", code)(row =>
        Json.obj(
          "usedBy" -> Json.fromString(row.string("used_by")),
          "usedAt" -> Json.fromString(
            pds.protocol.Syntax.datetime(java.time.Instant.ofEpochMilli(row.long("used_at"))))
        ))
      Json.obj(
        "code" -> Json.fromString(code),
        "available" -> Json.fromInt(available),
        "disabled" -> Json.fromBoolean(disabled),
        "forAccount" -> Json.fromString(account),
        "createdBy" -> Json.fromString(createdBy),
        "createdAt" -> Json.fromString(
          pds.protocol.Syntax.datetime(java.time.Instant.ofEpochMilli(createdAt))),
        "uses" -> Json.arr(uses*)
      )
    }

  def disable(connection: Connection, codes: Vector[String], accounts: Vector[String]): Unit =
    codes.foreach(code =>
      Sql.update(connection, "UPDATE invite_codes SET disabled = true WHERE code = ?", code))
    accounts.foreach(did =>
      Sql.update(connection, "UPDATE invite_codes SET disabled = true WHERE for_account = ?", did))

  def setAccountInvites(connection: Connection, did: String, enabled: Boolean): Unit =
    Sql.update(connection, "UPDATE accounts SET invites_disabled = ? WHERE did = ?", !enabled, did)
