package pds.oauth

import cats.effect.IO
import org.http4s.{Request, Status}
import pds.{Env, XrpcError}
import pds.accounts.{Accounts, Credential, Session}
import pds.crypto.Hash
import pds.storage.Sql

/** Resource-server verification of OAuth access tokens. Tokens are opaque,
  * stored only as digests, and bound to the DPoP key that requested them.
  */
object Resource:
  def session(env: Env, request: Request[IO], token: String, now: Long): IO[Session] =
    for
      _ <- IO.raiseUnless(Dpop.required(request))(
        XrpcError.named(Status.Unauthorized, "InvalidToken",
          "OAuth access tokens require a DPoP proof"))
      proof <- Dpop.verify(env, request, Some(token), now, requireNonce = false)
      found <- env.database.read { connection =>
        Sql.first(connection,
          """SELECT did, scope, dpop_jkt, security_epoch, revoked, access_expires_at
             FROM oauth_tokens WHERE access_hash = ?""",
          Hash.digestToken(token))(row =>
          (row.string("did"), row.string("scope"), row.string("dpop_jkt"),
            row.long("security_epoch"), row.bool("revoked"), row.long("access_expires_at")))
      }
      result <- found match
        case Some((did, scope, jkt, epoch, false, expires))
          if expires > now && Hash.constantTimeEquals(jkt, proof.thumbprint) =>
          IO.pure(Session(did, Credential.Access, None, epoch, Some(scope)))
        case Some((_, _, _, _, _, expires)) if expires <= now =>
          IO.raiseError(XrpcError.named(Status.Unauthorized, "InvalidToken",
            "The access token has expired"))
        case _ =>
          IO.raiseError(XrpcError.named(Status.Unauthorized, "InvalidToken",
            "The access token is not valid"))
    yield result

  /** Enforces that a scoped OAuth session may call a given XRPC method. */
  def authorize(session: Session, method: String): IO[Unit] =
    session.oauthScope match
      case None => IO.unit
      case Some(scope) =>
        IO.raiseUnless(Scope.grants(scope.split(" ").toVector, method))(
          XrpcError.named(Status.Forbidden, "InvalidToken",
            s"This authorization does not permit $method"))
