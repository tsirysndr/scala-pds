package pds.api

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import pds.{Env, XrpcError}
import pds.accounts.{Account, Accounts, Credential, Session, Tokens}
import pds.crypto.{Encoding, Hash}
import pds.oauth.Resource

/** Shared plumbing for XRPC endpoints: authentication, body parsing, query
  * parameters and the JSON error envelope.
  */
object Xrpc:
  val maxBodyBytes = 512 * 1024L

  def ok(body: Json): IO[Response[IO]] = IO.pure(Response[IO](Status.Ok).withEntity(body))

  def empty: IO[Response[IO]] = ok(Json.obj())

  def bearer(request: Request[IO]): Option[String] =
    request.headers.get[Authorization].collect {
      case Authorization(Credentials.Token(scheme, token))
        if scheme == AuthScheme.Bearer || scheme.toString.equalsIgnoreCase("dpop") => token
    }

  /** Resolves any supported credential to a session, or fails. */
  def session(env: Env, request: Request[IO]): IO[Session] =
    optionalSession(env, request).flatMap(
      IO.fromOption(_)(XrpcError.authRequired("Authentication is required")))

  def optionalSession(env: Env, request: Request[IO]): IO[Option[Session]] =
    bearer(request) match
      case None        => IO.pure(None)
      case Some(token) =>
        env.now.flatMap { now =>
          Tokens.verify(env, token, now) match
            case Right(found) if found.credential != Credential.Refresh => IO.pure(Some(found))
            case Right(_)                                               => IO.pure(None)
            case Left(_) => Resource.session(env, request, token, now).map(Some.apply)
        }

  def refreshSession(env: Env, request: Request[IO]): IO[(String, Session)] =
    IO.fromOption(bearer(request))(XrpcError.authRequired("A refresh token is required"))
      .flatMap { token =>
        env.now.flatMap { now =>
          IO.fromEither(Tokens.verify(env, token, now)).flatMap { found =>
            IO.raiseUnless(found.credential == Credential.Refresh)(
              XrpcError.authRequired("A refresh token is required")).as(token -> found)
          }
        }
      }

  /** Confirms live account state and returns the account. */
  def account(env: Env, session: Session): IO[Account] =
    env.database.read(connection => Tokens.confirm(connection, session))

  def requirePrivileged(session: Session): IO[Unit] =
    IO.raiseUnless(session.privileged)(
      XrpcError.named(Status.Forbidden, "InvalidToken", "App passwords cannot perform this action"))

  def admin(env: Env, request: Request[IO]): IO[Unit] =
    val expected = env.config.adminPassword
    val supplied = request.headers.get[Authorization].collect {
      case Authorization(BasicCredentials(user, password)) if user == "admin" => password
    }
    (expected, supplied) match
      case (Some(reference), Some(value)) if Hash.constantTimeEquals(reference, value) => IO.unit
      case _ => moderation(env, request)

  /** A configured moderation service — Ozone, say — acts with a service token
    * scoped to the method it calls instead of the administrator password, so
    * decisions taken there reach this server without sharing that password.
    */
  private def moderation(env: Env, request: Request[IO]): IO[Unit] =
    val refused = XrpcError.authRequired("Administrator credentials are required")
    val called = request.uri.path.segments.map(_.decoded()).toVector match
      case Vector("xrpc", method) if pds.protocol.Syntax.isNsid(method) => Some(method)
      case _                                                           => None
    (env.config.modServiceDid, pds.security.ServiceAuth.bearer(request), called) match
      case (Some(authority), Some(token), Some(method)) =>
        pds.security.ServiceAuth.verify(env, token, method)
          .flatMap(issuer => IO.raiseUnless(issuer == authority)(refused))
          .adaptError { case _ => refused }
      case _ => IO.raiseError(refused)

  def body(request: Request[IO]): IO[Json] =
    request.body.take(maxBodyBytes + 1).compile.to(Array).flatMap { bytes =>
      if bytes.length > maxBodyBytes then
        IO.raiseError(XrpcError.payloadTooLarge("Request body is too large"))
      else if bytes.isEmpty then IO.pure(Json.obj())
      else
        IO.fromEither(io.circe.parser.parse(Encoding.text(bytes))
          .left.map(_ => XrpcError.invalidRequest("Request body must be JSON")))
          .flatMap(json =>
            IO.raiseUnless(json.isObject)(XrpcError.invalidRequest("Request body must be a JSON object"))
              .as(json))
    }

  def field(json: Json, name: String): Option[String] =
    json.hcursor.get[String](name).toOption.map(_.trim).filter(_.nonEmpty)

  def requireField(json: Json, name: String): String =
    field(json, name).getOrElse(throw XrpcError.invalidRequest(s"$name is required"))

  def flag(json: Json, name: String): Option[Boolean] = json.hcursor.get[Boolean](name).toOption

  def param(request: Request[IO], name: String): Option[String] =
    request.params.get(name).map(_.trim).filter(_.nonEmpty)

  def requireParam(request: Request[IO], name: String): String =
    param(request, name).getOrElse(throw XrpcError.invalidRequest(s"$name is required"))

  def intParam(request: Request[IO], name: String, default: Int, min: Int, max: Int): Int =
    param(request, name) match
      case None => default
      case Some(value) => value.toIntOption.filter(parsed => parsed >= min && parsed <= max)
        .getOrElse(throw XrpcError.invalidRequest(s"$name must be between $min and $max"))

  def boolParam(request: Request[IO], name: String, default: Boolean): Boolean =
    param(request, name) match
      case None => default
      case Some("true") => true
      case Some("false") => false
      case Some(_) => throw XrpcError.invalidRequest(s"$name must be true or false")

  /** Renders any failure as an XRPC error envelope. */
  def recover(work: IO[Response[IO]]): IO[Response[IO]] =
    work.handleErrorWith {
      case error: XrpcError => IO.pure(error.response[IO])
      case error: org.http4s.MessageFailure =>
        IO.pure(XrpcError.invalidRequest("Request could not be read").response[IO])
      case error =>
        IO.consoleForIO.errorln(s"unhandled error: ${error}").as(
          XrpcError.internal().response[IO])
    }
