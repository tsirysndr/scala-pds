package pds

import io.circe.Json
import org.http4s.{Response, Status}
import org.http4s.circe.*

/** XRPC errors carry a machine-readable name alongside the HTTP status.
  *
  * OAuth surfaces additionally render `error_description`, which is what RFC
  * 6749 names the field, and may carry a `WWW-Authenticate` challenge.
  */
final case class XrpcError(
    status: Status,
    error: String,
    message: String,
    challenge: Option[String] = None,
    oauth: Boolean = false
) extends RuntimeException(message):
  def response[F[_]]: Response[F] =
    val body = Json.obj(
      "error" -> Json.fromString(error),
      "message" -> Json.fromString(message)
    ).deepMerge(
      if oauth then Json.obj("error_description" -> Json.fromString(message)) else Json.obj())
    val rendered = Response[F](status).withEntity(body)
    challenge.fold(rendered)(value =>
      rendered.putHeaders(
        org.http4s.Header.Raw(org.typelevel.ci.CIString("WWW-Authenticate"), value)))

object XrpcError:
  def invalidRequest(message: String): XrpcError =
    XrpcError(Status.BadRequest, "InvalidRequest", message)

  def named(status: Status, error: String, message: String): XrpcError =
    XrpcError(status, error, message)

  def authRequired(message: String = "Authentication is required"): XrpcError =
    XrpcError(Status.Unauthorized, "AuthenticationRequired", message)

  def expiredToken(message: String = "Token has expired"): XrpcError =
    XrpcError(Status.BadRequest, "ExpiredToken", message)

  def forbidden(message: String): XrpcError =
    XrpcError(Status.Forbidden, "Forbidden", message)

  def notFound(message: String): XrpcError =
    XrpcError(Status.NotFound, "NotFound", message)

  def recordNotFound(message: String = "Record was not found"): XrpcError =
    XrpcError(Status.BadRequest, "RecordNotFound", message)

  def conflict(error: String, message: String): XrpcError =
    XrpcError(Status.Conflict, error, message)

  def rateLimited(message: String = "Rate limit exceeded"): XrpcError =
    XrpcError(Status.TooManyRequests, "RateLimitExceeded", message)

  def payloadTooLarge(message: String): XrpcError =
    XrpcError(Status.PayloadTooLarge, "PayloadTooLarge", message)

  def internal(message: String = "Something went wrong"): XrpcError =
    XrpcError(Status.InternalServerError, "InternalServerError", message)

  def unsupported(message: String): XrpcError =
    XrpcError(Status.BadRequest, "UnsupportedDomain", message)
