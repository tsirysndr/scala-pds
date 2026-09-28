package pds.accounts

import io.circe.Json
import java.sql.Connection
import pds.XrpcError
import pds.storage.Sql

/** Private Bluesky preferences, keyed by their Lexicon `$type`. */
object Preferences:
  val maxCount = 1000

  def list(connection: Connection, did: String, scope: String): Vector[Json] =
    Sql.query(connection,
      "SELECT value FROM account_preferences WHERE did = ? AND scope = ? ORDER BY name",
      did, scope)(_.string("value"))
      .flatMap(value => io.circe.parser.parse(value).toOption)

  def replace(connection: Connection, did: String, scope: String, values: Vector[Json]): Unit =
    if values.length > maxCount then
      throw XrpcError.invalidRequest("Too many preferences")
    val named = values.map { value =>
      val name = value.hcursor.get[String]("$type").toOption
        .getOrElse(throw XrpcError.invalidRequest("Every preference needs a $type"))
      if !pds.protocol.Syntax.isNsid(name.takeWhile(_ != '#')) then
        throw XrpcError.invalidRequest("Preference $type must be a Lexicon reference")
      name -> value
    }
    if named.map(_._1).distinct.length != named.length then
      throw XrpcError.invalidRequest("Preferences must have distinct types")
    Sql.update(connection,
      "DELETE FROM account_preferences WHERE did = ? AND scope = ?", did, scope)
    named.foreach { (name, value) =>
      Sql.update(connection,
        "INSERT INTO account_preferences(did, name, scope, value) VALUES (?, ?, ?, ?)",
        did, name, scope, value.noSpaces)
    }

  def scopeFor(namespace: String): String = namespace
