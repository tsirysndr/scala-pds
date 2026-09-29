package pds.storage

import cats.effect.IO
import java.time.{ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import pds.crypto.{Encoding, Hash}

final case class S3Config(
    bucket: String,
    region: String,
    endpoint: String,
    accessKeyId: String,
    secretAccessKey: String,
    pathStyle: Boolean
):
  override def toString: String = s"S3Config($bucket,$region,$endpoint,<redacted>)"

  def objectUrl(key: String): String =
    if pathStyle then s"$endpoint/$bucket/${S3.encodePath(key)}"
    else
      val scheme = endpoint.takeWhile(_ != ':')
      val host = endpoint.dropWhile(_ != '/').dropWhile(_ == '/')
      s"$scheme://$bucket.$host/${S3.encodePath(key)}"

object S3Config:
  /** S3 is used only when a bucket is configured; the rest then has to follow. */
  def fromEnv(env: Map[String, String]): Either[String, Option[S3Config]] =
    env.get("PDS_S3_BUCKET").filter(_.nonEmpty) match
      case None => Right(None)
      case Some(bucket) =>
        for
          region <- env.get("PDS_S3_REGION").filter(_.nonEmpty)
            .toRight("PDS_S3_REGION is required when PDS_S3_BUCKET is set")
          endpoint <- env.get("PDS_S3_ENDPOINT").filter(value =>
            value.startsWith("https://") || value.startsWith("http://"))
            .orElse(Some(s"https://s3.$region.amazonaws.com"))
            .toRight("PDS_S3_ENDPOINT must be an http(s) URL")
          key <- env.get("PDS_S3_ACCESS_KEY_ID").filter(_.nonEmpty)
            .toRight("PDS_S3_ACCESS_KEY_ID is required when PDS_S3_BUCKET is set")
          secret <- env.get("PDS_S3_SECRET_ACCESS_KEY").filter(_.nonEmpty)
            .toRight("PDS_S3_SECRET_ACCESS_KEY is required when PDS_S3_BUCKET is set")
        yield Some(S3Config(bucket, region, endpoint.stripSuffix("/"), key, secret,
          pathStyle = env.get("PDS_S3_PATH_STYLE").forall(_ != "false")))

/** A minimal S3 client: PUT, GET and DELETE of whole objects, signed with
  * AWS Signature Version 4. Blobs are content-addressed and written once, so
  * multipart uploads and ranged reads are not needed.
  */
final class S3(config: S3Config, client: Client[IO]):
  import S3.*

  def bucket: String = config.bucket

  def put(key: String, contentType: String, body: Array[Byte]): IO[Either[String, Unit]] =
    send(Method.PUT, key, Some(body), Some(contentType)).map(_.map(_ => ()))

  def get(key: String): IO[Either[String, Array[Byte]]] =
    send(Method.GET, key, None, None)

  def delete(key: String): IO[Either[String, Unit]] =
    send(Method.DELETE, key, None, None).map(_.map(_ => ()))

  private def send(
      method: Method, key: String, body: Option[Array[Byte]], contentType: Option[String]
  ): IO[Either[String, Array[Byte]]] =
    IO.realTimeInstant.flatMap { now =>
      val moment = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
      val payload = body.getOrElse(Array.emptyByteArray)
      val uri = Uri.fromString(config.objectUrl(key))
      uri match
        case Left(_) => IO.pure(Left("The object URL is not valid"))
        case Right(target) =>
          val headers = sign(config, method.name, target, payload, contentType, moment)
          val request = headers.foldLeft(Request[IO](method, target)) { (acc, header) =>
            acc.putHeaders(Header.Raw(CIString(header._1), header._2))
          }
          val withBody = body.fold(request)(request.withEntity)
          client.run(withBody).use { response =>
            response.body.compile.to(Array).map { bytes =>
              if response.status.isSuccess then Right(bytes)
              else Left(s"${response.status.code}: ${Encoding.text(bytes).take(256)}")
            }
          }.handleError(error => Left(Option(error.getMessage).getOrElse("request failed")))
    }

object S3:
  private val dateStamp = DateTimeFormatter.ofPattern("yyyyMMdd")
  private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
  private val unreserved =
    (('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9') ++ Seq('-', '_', '.', '~')).toSet

  def encodePath(value: String): String =
    value.split("/", -1).map(segment =>
      segment.getBytes("UTF-8").map { byte =>
        val c = (byte & 0xff).toChar
        if unreserved.contains(c) then c.toString else f"%%${byte & 0xff}%02X"
      }.mkString).mkString("/")

  /** AWS Signature Version 4 over the canonical request. */
  def sign(
      config: S3Config,
      method: String,
      uri: Uri,
      payload: Array[Byte],
      contentType: Option[String],
      moment: ZonedDateTime
  ): Vector[(String, String)] =
    val host = uri.authority.map(_.renderString).getOrElse("")
    val date = moment.format(dateStamp)
    val stamp = moment.format(timestamp)
    val payloadHash = Encoding.hex(Hash.sha256(payload))

    val headers = Vector(
      "host" -> host,
      "x-amz-content-sha256" -> payloadHash,
      "x-amz-date" -> stamp
    ) ++ contentType.map("content-type" -> _).toVector
    val ordered = headers.sortBy(_._1)
    val signedHeaders = ordered.map(_._1).mkString(";")
    val canonicalHeaders = ordered.map((name, value) => s"$name:${value.trim}\n").mkString

    val canonicalRequest = Vector(
      method,
      uri.path.renderString,
      uri.query.renderString,
      canonicalHeaders,
      signedHeaders,
      payloadHash
    ).mkString("\n")

    val scope = s"$date/${config.region}/s3/aws4_request"
    val toSign = Vector(
      "AWS4-HMAC-SHA256", stamp, scope, Encoding.hex(Hash.sha256(canonicalRequest))
    ).mkString("\n")

    val dateKey = Hash.hmacSha256(Encoding.utf8(s"AWS4${config.secretAccessKey}"), Encoding.utf8(date))
    val regionKey = Hash.hmacSha256(dateKey, Encoding.utf8(config.region))
    val serviceKey = Hash.hmacSha256(regionKey, Encoding.utf8("s3"))
    val signingKey = Hash.hmacSha256(serviceKey, Encoding.utf8("aws4_request"))
    val signature = Encoding.hex(Hash.hmacSha256(signingKey, Encoding.utf8(toSign)))

    val authorization = s"AWS4-HMAC-SHA256 Credential=${config.accessKeyId}/$scope, " +
      s"SignedHeaders=$signedHeaders, Signature=$signature"

    Vector(
      "x-amz-content-sha256" -> payloadHash,
      "x-amz-date" -> stamp,
      "Authorization" -> authorization
    ) ++ contentType.map("Content-Type" -> _).toVector
