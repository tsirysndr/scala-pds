package pds.storage

/** The few places the two backends differ. Timestamps are epoch milliseconds
  * and UUIDs are text everywhere else, so schemas and queries stay shared.
  */
final case class Dialect(backend: Backend):
  def blob: String = if backend == Backend.Postgres then "bytea" else "blob"
  def json: String = if backend == Backend.Postgres then "jsonb" else "text"
  def idPrimaryKey: String =
    if backend == Backend.Postgres then "bigserial PRIMARY KEY"
    else "integer PRIMARY KEY AUTOINCREMENT"

  /** Row locks exist only on PostgreSQL; SQLite serializes writers already. */
  def forUpdate: String = if backend == Backend.Postgres then " FOR UPDATE" else ""

  def render(statement: String): String =
    statement
      .replace("{{BLOB}}", blob)
      .replace("{{JSON}}", json)
      .replace("{{ID_PK}}", idPrimaryKey)
