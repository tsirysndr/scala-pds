package pds.lexicon

import java.net.URI
import java.time.{LocalDateTime, ZoneOffset}
import java.util.{IllformedLocaleException, Locale}
import pds.protocol.{AtUri, Cid, Syntax}

/** The `format` values the AT Protocol Lexicon vocabulary defines for strings. */
object Formats:
  private val datetimePattern =
    """(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.\d+)?(Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)""".r

  def datetime(value: String): Boolean =
    value.length <= 64 && !value.endsWith("-00:00") && (value match
      case datetimePattern(local, zone) =>
        try
          val parsed = LocalDateTime.parse(local)
          val offset = if zone == "Z" then 0
            else
              val sign = if zone.startsWith("-") then -1 else 1
              sign * (zone.drop(1).take(2).toInt * 3600 + zone.takeRight(2).toInt * 60)
          val year = LocalDateTime.ofEpochSecond(
            parsed.toEpochSecond(ZoneOffset.UTC) - offset, 0, ZoneOffset.UTC).getYear
          year >= 0 && year <= 9999
        catch case _: RuntimeException => false
      case _ => false)

  def uri(value: String): Boolean =
    value.length <= 8192 && value.forall(c => c >= 0x21 && c <= 0x7e) &&
      (try URI(value).isAbsolute catch case _: IllegalArgumentException => false)

  private val grandfathered = Set(
    "en-GB-oed", "i-ami", "i-bnn", "i-default", "i-enochian", "i-hak", "i-klingon",
    "i-lux", "i-mingo", "i-navajo", "i-pwn", "i-tao", "i-tay", "i-tsu",
    "sgn-BE-FR", "sgn-BE-NL", "sgn-CH-DE", "art-lojban", "cel-gaulish",
    "no-bok", "no-nyn", "zh-guoyu", "zh-hakka", "zh-min", "zh-min-nan", "zh-xiang")

  def language(value: String): Boolean =
    grandfathered.contains(value) ||
      (Syntax.isLanguage(value) &&
        (try
          Locale.Builder().setLanguageTag(value)
          true
        catch case _: IllformedLocaleException => false))

  /** `None` for a format this implementation does not know, which is a
    * catalog error rather than a client error.
    */
  def validator(format: String): Option[String => Boolean] = format match
    case "at-identifier" => Some(Syntax.isAtIdentifier)
    case "at-uri"        => Some(value => AtUri.parse(value).isDefined)
    case "cid"           => Some(value => Cid.parse(value).isDefined)
    case "datetime"      => Some(datetime)
    case "did"           => Some(Syntax.isDid)
    case "handle"        => Some(Syntax.isHandle)
    case "language"      => Some(language)
    case "nsid"          => Some(Syntax.isNsid)
    case "record-key"    => Some(Syntax.isRecordKey)
    case "tid"           => Some(Syntax.isTid)
    case "uri"           => Some(uri)
    case _               => None
