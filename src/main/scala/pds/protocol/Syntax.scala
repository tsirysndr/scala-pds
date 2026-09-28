package pds.protocol

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale

/** Identifier syntax from the AT Protocol specifications. */
object Syntax:
  /** TLDs the specification reserves; syntactically valid but never hosted. */
  val reservedTlds =
    Set("alt", "arpa", "example", "internal", "invalid", "local", "localhost", "onion", "test")

  private def segments(value: String): Array[String] = value.split("\\.", -1)

  private def domainSegment(segment: String): Boolean =
    segment.length >= 1 && segment.length <= 63 &&
      segment.forall(c => c.isLetterOrDigit && c < 128 || c == '-') &&
      segment.head != '-' && segment.last != '-'

  def isHandle(value: String): Boolean =
    val parts = segments(value)
    value.length <= 253 && parts.length >= 2 && parts.forall(domainSegment) &&
      parts.last.headOption.exists(c => c.isLetter && c < 128)

  def normalizeHandle(value: String): String = value.toLowerCase(Locale.ROOT)

  def isReservedHandle(value: String): Boolean =
    reservedTlds.contains(segments(normalizeHandle(value)).last)

  def isDid(value: String): Boolean =
    value.length <= 2048 && (value.split(":", -1) match
      case Array("did", method, rest*) =>
        method.nonEmpty && method.forall(c => c >= 'a' && c <= 'z') &&
          rest.nonEmpty && rest.last.nonEmpty && isDidIdentifier(rest.mkString(":"))
      case _ => false)

  private def isDidIdentifier(value: String): Boolean =
    val allowed = value.forall(c => c.isLetterOrDigit && c < 128 || ".-_:%".contains(c))
    val percents = value.indices.filter(value(_) == '%')
    allowed && percents.forall { index =>
      index + 2 < value.length &&
        value.slice(index + 1, index + 3).forall(c => "0123456789abcdefABCDEF".contains(c))
    }

  def isAtIdentifier(value: String): Boolean = isDid(value) || isHandle(value)

  def isNsid(value: String): Boolean =
    val parts = segments(value)
    value.length <= 317 && parts.length >= 3 && parts.forall(domainSegment) &&
      !parts.head.head.isDigit && !parts.last.head.isDigit &&
      parts.last.forall(c => c.isLetterOrDigit && c < 128) &&
      parts.dropRight(1).mkString(".").length <= 253

  def isRecordKey(value: String): Boolean =
    value.length >= 1 && value.length <= 512 && value != "." && value != ".." &&
      value.forall(c => c.isLetterOrDigit && c < 128 || "-.:_~".contains(c))

  private val tidAlphabet = "234567abcdefghijklmnopqrstuvwxyz"

  def isTid(value: String): Boolean =
    value.length == 13 && value.forall(tidAlphabet.contains) &&
      "234567abcdefghij".contains(value.head)

  def isCidString(value: String): Boolean = Cid.parse(value).isDefined

  private val datetimePattern =
    """(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})(\.\d+)?(Z|[+-]\d{2}:\d{2})""".r

  private val earliest =
    OffsetDateTime.of(0, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant

  def isDatetime(value: String): Boolean = value match
    case datetimePattern(date, time, fraction, zone) if !value.endsWith("-00:00") =>
      try
        val parsed = OffsetDateTime.parse(
          s"${date}T$time${Option(fraction).map(_.take(10)).getOrElse("")}$zone")
        !parsed.toInstant.isBefore(earliest)
      catch case _: DateTimeParseException => false
    case _ => false

  /** Canonical RFC 3339 rendering used for record and commit timestamps. */
  def datetime(instant: java.time.Instant): String =
    instant.atOffset(java.time.ZoneOffset.UTC)
      .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
      .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))

  def isLanguage(value: String): Boolean =
    value.matches("""[a-zA-Z]{2,3}(-[a-zA-Z0-9]{1,8})*|i(-[a-zA-Z0-9]{1,8})+|x(-[a-zA-Z0-9]{1,8})+""")

final case class AtUri(authority: String, collection: Option[String], recordKey: Option[String]):
  override def toString: String =
    s"at://$authority" + collection.fold("")("/" + _) + recordKey.fold("")("/" + _)

object AtUri:
  def parse(value: String): Option[AtUri] =
    if !value.startsWith("at://") || value.length > 8192 ||
      value.contains('?') || value.contains('#') || value.contains(' ')
    then None
    else
      value.drop(5).split("/", -1).toList match
        case authority :: rest if Syntax.isAtIdentifier(authority) =>
          rest match
            case Nil                                                     => Some(AtUri(authority, None, None))
            case collection :: Nil if Syntax.isNsid(collection)          => Some(AtUri(authority, Some(collection), None))
            case collection :: key :: Nil
              if Syntax.isNsid(collection) && Syntax.isRecordKey(key)    => Some(AtUri(authority, Some(collection), Some(key)))
            case _                                                       => None
        case _ => None
