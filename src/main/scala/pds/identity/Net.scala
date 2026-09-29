package pds.identity

import cats.effect.IO
import io.circe.Json
import java.net.{InetAddress, URI}
import org.http4s.client.Client
import org.http4s.{Header, Headers, MediaType, Method, Request, Status, Uri}
import org.http4s.headers.{Accept, `Content-Type`}
import org.typelevel.ci.CIString
import pds.XrpcError

/** Outbound HTTP for identity, OAuth client metadata and relay notification.
  * Every request resolves its host first and refuses private address space
  * unless the deployment explicitly allows it, which keeps handle and client
  * documents from reaching internal services.
  */
final class Net(client: Client[IO], allowPrivate: Boolean, val maxBytes: Long = 128 * 1024):
  def checkUrl(value: String): IO[Uri] =
    for
      uri <- IO.fromEither(Uri.fromString(value).left.map(_ =>
        XrpcError.invalidRequest(s"Invalid URL")))
      scheme <- IO.fromOption(uri.scheme.map(_.value))(XrpcError.invalidRequest("URL has no scheme"))
      host <- IO.fromOption(uri.host.map(_.value))(XrpcError.invalidRequest("URL has no host"))
      _ <- IO.raiseUnless(scheme == "https" || (allowPrivate && scheme == "http"))(
        XrpcError.invalidRequest("Only HTTPS URLs may be fetched"))
      _ <- verifyHost(host)
    yield uri

  private def verifyHost(host: String): IO[Unit] =
    if allowPrivate then IO.unit
    else
      IO.blocking(InetAddress.getAllByName(host).toVector)
        .handleErrorWith(_ => IO.raiseError(XrpcError.invalidRequest("Host does not resolve")))
        .flatMap { addresses =>
          IO.raiseWhen(addresses.isEmpty || addresses.exists(forbidden))(
            XrpcError.invalidRequest("Host resolves to a private address"))
        }

  private def forbidden(address: InetAddress): Boolean =
    address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
      address.isAnyLocalAddress || address.isMulticastAddress ||
      (address.getAddress.length == 4 && (address.getAddress()(0) & 0xff) == 100 &&
        ((address.getAddress()(1) & 0xff) >= 64 && (address.getAddress()(1) & 0xff) <= 127))

  def getText(url: String, accept: Option[MediaType] = None): IO[Option[String]] =
    checkUrl(url).flatMap { uri =>
      val request = Request[IO](Method.GET, uri,
        headers = accept.map(media => Headers(Accept(media))).getOrElse(Headers.empty))
      client.run(request).use { response =>
        if response.status == Status.Ok then
          response.body.take(maxBytes).compile.to(Array)
            .map(bytes => Some(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)))
        else IO.pure(None)
      }.handleError(_ => None)
    }

  /** Raw bytes with an explicit ceiling, for signed CAR proofs. */
  def getBytes(url: String, limit: Long): IO[Option[Array[Byte]]] =
    checkUrl(url).flatMap { uri =>
      client.run(Request[IO](Method.GET, uri)).use { response =>
        if response.status == Status.Ok then
          response.body.take(limit + 1).compile.to(Array)
            .map(bytes => Option.when(bytes.length <= limit)(bytes))
        else IO.pure(None)
      }.handleError(_ => None)
    }

  def getJson(url: String): IO[Option[Json]] =
    getText(url, Some(MediaType.application.json)).map(_.flatMap(io.circe.parser.parse(_).toOption))

  def postJson(url: String, body: Json, headers: Headers = Headers.empty): IO[Either[String, Json]] =
    checkUrl(url).flatMap { uri =>
      val request = Request[IO](Method.POST, uri, headers = headers.put(`Content-Type`(MediaType.application.json)))
        .withEntity(body.noSpaces)
      client.run(request).use { response =>
        response.body.take(maxBytes).compile.to(Array).map { bytes =>
          val text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
          if response.status.isSuccess then
            Right(io.circe.parser.parse(text).getOrElse(Json.obj()))
          else Left(s"${response.status.code}: ${text.take(512)}")
        }
      }.handleError(error => Left(error.getMessage))
    }

  def header(name: String, value: String): Header.Raw = Header.Raw(CIString(name), value)
