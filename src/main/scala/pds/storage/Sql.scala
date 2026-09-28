package pds.storage

import java.sql.{Connection, PreparedStatement, ResultSet, Types}
import scala.collection.mutable

enum Param:
  case Text(value: String)
  case Number(value: Long)
  case Flag(value: Boolean)
  case Binary(value: Array[Byte])
  case Missing

object Param:
  given Conversion[String, Param] = Text(_)
  given Conversion[Long, Param] = Number(_)
  given Conversion[Int, Param] = value => Number(value.toLong)
  given Conversion[Boolean, Param] = Flag(_)
  given Conversion[Array[Byte], Param] = Binary(_)
  given [A](using conversion: Conversion[A, Param]): Conversion[Option[A], Param] =
    _.map(conversion).getOrElse(Missing)

final class Row(private val rs: ResultSet):
  def string(column: String): String = rs.getString(column)
  def stringOpt(column: String): Option[String] = Option(rs.getString(column))
  def long(column: String): Long = rs.getLong(column)
  def longOpt(column: String): Option[Long] =
    val value = rs.getLong(column)
    if rs.wasNull() then None else Some(value)
  def int(column: String): Int = rs.getInt(column)
  def bool(column: String): Boolean = rs.getBoolean(column)
  def bytes(column: String): Array[Byte] = rs.getBytes(column)
  def bytesOpt(column: String): Option[Array[Byte]] = Option(rs.getBytes(column))

object Sql:
  private def bind(statement: PreparedStatement, params: Seq[Param]): Unit =
    params.zipWithIndex.foreach { (param, index) =>
      val position = index + 1
      param match
        case Param.Text(value)   => statement.setString(position, value)
        case Param.Number(value) => statement.setLong(position, value)
        case Param.Flag(value)   => statement.setBoolean(position, value)
        case Param.Binary(value) => statement.setBytes(position, value)
        case Param.Missing       => statement.setNull(position, Types.NULL)
    }

  def query[A](connection: Connection, sql: String, params: Param*)(map: Row => A): Vector[A] =
    val statement = connection.prepareStatement(sql)
    try
      bind(statement, params)
      val rs = statement.executeQuery()
      val results = mutable.ArrayBuffer.empty[A]
      while rs.next() do results += map(new Row(rs))
      results.toVector
    finally statement.close()

  def first[A](connection: Connection, sql: String, params: Param*)(map: Row => A): Option[A] =
    query(connection, sql, params*)(map).headOption

  def update(connection: Connection, sql: String, params: Param*): Int =
    val statement = connection.prepareStatement(sql)
    try
      bind(statement, params)
      statement.executeUpdate()
    finally statement.close()

  def exists(connection: Connection, sql: String, params: Param*): Boolean =
    first(connection, sql, params*)(_ => true).isDefined

  def count(connection: Connection, sql: String, params: Param*): Long =
    first(connection, sql, params*)(_.long("total")).getOrElse(0L)

  /** Inserts and returns the generated sequence value. */
  def insertReturningId(connection: Connection, sql: String, params: Param*): Long =
    val statement = connection.prepareStatement(sql, Array("seq"))
    try
      bind(statement, params)
      statement.executeUpdate()
      val keys = statement.getGeneratedKeys
      if keys.next() then keys.getLong(1) else 0L
    finally statement.close()
