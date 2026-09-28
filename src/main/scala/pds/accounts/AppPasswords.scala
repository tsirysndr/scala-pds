package pds.accounts

import io.circe.Json
import java.sql.Connection
import java.util.UUID
import pds.XrpcError
import pds.crypto.{Hash, Passwords}
import pds.protocol.Syntax
import pds.storage.Sql

object AppPasswords:
  /** App passwords are shown once, in the `xxxx-xxxx-xxxx-xxxx` shape clients expect. */
  def generate(): String =
    Hash.randomBase32(10).take(16).grouped(4).mkString("-")

  def create(
      connection: Connection, did: String, name: String, privileged: Boolean, now: Long
  ): Json =
    if name.trim.isEmpty || name.length > 64 then
      throw XrpcError.invalidRequest("App password names are 1 to 64 characters")
    if Sql.exists(connection, "SELECT 1 FROM app_passwords WHERE did = ? AND name = ?", did, name)
    then throw XrpcError.invalidRequest("An app password with that name already exists")
    if Sql.count(connection,
      "SELECT COUNT(*) AS total FROM app_passwords WHERE did = ?", did) >= 100
    then throw XrpcError.invalidRequest("Too many app passwords")
    val password = generate()
    Sql.update(connection,
      """INSERT INTO app_passwords(id, did, name, password_digest, privileged, created_at)
         VALUES (?, ?, ?, ?, ?, ?)""",
      UUID.randomUUID().toString, did, name, Passwords.hash(password), privileged, now)
    Json.obj(
      "name" -> Json.fromString(name),
      "password" -> Json.fromString(password),
      "privileged" -> Json.fromBoolean(privileged),
      "createdAt" -> Json.fromString(Syntax.datetime(java.time.Instant.ofEpochMilli(now)))
    )

  def list(connection: Connection, did: String): Vector[Json] =
    Sql.query(connection,
      "SELECT name, privileged, created_at FROM app_passwords WHERE did = ? ORDER BY created_at",
      did)(row => Json.obj(
      "name" -> Json.fromString(row.string("name")),
      "privileged" -> Json.fromBoolean(row.bool("privileged")),
      "createdAt" -> Json.fromString(
        Syntax.datetime(java.time.Instant.ofEpochMilli(row.long("created_at"))))
    ))

  /** Revoking an app password also ends the sessions it created. */
  def revoke(connection: Connection, did: String, name: String): Unit =
    val id = Sql.first(connection,
      "SELECT id FROM app_passwords WHERE did = ? AND name = ?", did, name)(_.string("id"))
    id.foreach { value =>
      Sql.update(connection, "UPDATE sessions SET revoked = true WHERE app_password_id = ?", value)
      Sql.update(connection, "DELETE FROM app_passwords WHERE id = ?", value)
    }
