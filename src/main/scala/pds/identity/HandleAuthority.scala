package pds.identity

import cats.effect.IO
import org.http4s.Status
import pds.protocol.Syntax
import pds.{Env, XrpcError}

/** The service that owns this server's handle namespace.
  *
  * A handle domain can be served by several PDS instances — `*.bsky.social`
  * works that way — and then the server that owns the wildcard, not this one,
  * is what a handle resolves through. Asking it before allocating a name keeps
  * two servers from handing out the same one.
  */
object HandleAuthority:
  /** Fails when the authority already resolves `handle` to a different account.
    *
    * Only an answered claim counts as taken: an authority that cannot be
    * reached leaves the name unproven, so an outage there does not stop
    * registration here.
    */
  def ensureAvailable(env: Env, handle: String, owner: Option[String] = None): IO[Unit] =
    env.config.handleAuthority match
      case None => IO.unit
      case Some(authority) =>
        claimed(env, authority, handle).flatMap {
          case Some(did) if !owner.contains(did) =>
            IO.raiseError(XrpcError.named(Status.BadRequest, "HandleNotAvailable",
              "Handle is already taken in this domain"))
          case _ => IO.unit
        }

  private def claimed(env: Env, authority: String, handle: String): IO[Option[String]] =
    val query = java.net.URLEncoder.encode(handle, "UTF-8")
    env.net.getJson(s"$authority/xrpc/com.atproto.identity.resolveHandle?handle=$query")
      .map(_.flatMap(_.hcursor.get[String]("did").toOption).filter(Syntax.isDid))
      .handleError(_ => None)
