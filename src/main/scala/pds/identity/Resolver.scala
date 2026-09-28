package pds.identity

import cats.effect.IO
import io.circe.Json
import io.circe.parser.parse
import java.net.IDN
import org.xbill.DNS.{Lookup, Record, TXTRecord, Type}
import pds.ServerConfig
import pds.protocol.Syntax
import pds.storage.{Database, Sql}

/** Handle and DID resolution with a bounded database cache. Handles resolve
  * through DNS first, then the well-known HTTPS document; a handle is only
  * accepted once the resolved DID document claims it back.
  */
final class Resolver(net: Net, database: Database, config: ServerConfig):
  private val cacheSeconds = 600L

  def resolveHandleLocally(handle: String): IO[Option[String]] =
    database.read(connection =>
      Sql.first(connection, "SELECT did FROM accounts WHERE handle = ? AND status <> 'deleted'",
        Syntax.normalizeHandle(handle))(_.string("did")))

  def resolveHandle(handle: String): IO[Option[String]] =
    val normalized = Syntax.normalizeHandle(handle)
    if !Syntax.isHandle(normalized) then IO.pure(None)
    else
      resolveHandleLocally(normalized).flatMap {
        case found @ Some(_) => IO.pure(found)
        case None            => dnsHandle(normalized).flatMap {
          case found @ Some(_) => IO.pure(found)
          case None            => wellKnownHandle(normalized)
        }
      }

  private def dnsHandle(handle: String): IO[Option[String]] =
    IO.blocking {
      val lookup = new Lookup(s"_atproto.${IDN.toASCII(handle)}.", Type.TXT)
      Option(lookup.run()).toVector.flatten.collect { case txt: TXTRecord =>
        txt.getStrings.toArray.map(_.toString).mkString
      }.collect { case value if value.startsWith("did=") => value.drop(4) }
        .filter(Syntax.isDid) match
        case Vector(single) => Some(single)
        case _              => None
    }.handleError(_ => None)

  private def wellKnownHandle(handle: String): IO[Option[String]] =
    net.getText(s"https://$handle/.well-known/atproto-did")
      .map(_.map(_.trim).filter(Syntax.isDid))

  def resolveDid(did: String): IO[Option[DidDocument]] =
    if !Syntax.isDid(did) then IO.pure(None)
    else
      cached(did).flatMap {
        case Some(document) => IO.pure(Some(document))
        case None =>
          fetchDid(did).flatTap {
            case Some(document) => store(did, document.raw)
            case None           => IO.unit
          }
      }

  private def fetchDid(did: String): IO[Option[DidDocument]] =
    val url = did.split(":", 3) match
      case Array("did", "plc", _)        => Some(s"${config.plcDirectory}/$did")
      case Array("did", "web", identity) => webUrl(identity)
      case _                             => None
    url match
      case None      => IO.pure(None)
      case Some(url) => net.getJson(url).map(_.flatMap(DidDocument.parse(_).toOption))
        .map(_.filter(_.id == did))

  private def webUrl(identity: String): Option[String] =
    identity.split(":").toVector match
      case host +: path =>
        val decoded = host.replace("%3A", ":")
        Option.when(decoded.nonEmpty)(
          if path.isEmpty then s"https://$decoded/.well-known/did.json"
          else s"https://$decoded/${path.mkString("/")}/did.json")
      case _ => None

  def resolveIdentity(identifier: String): IO[Option[DidDocument]] =
    if Syntax.isDid(identifier) then resolveDid(identifier)
    else
      resolveHandle(identifier).flatMap {
        case None      => IO.pure(None)
        case Some(did) => resolveDid(did).map(_.filter(_.handle.contains(Syntax.normalizeHandle(identifier))))
      }

  private def cached(did: String): IO[Option[DidDocument]] =
    database.read { connection =>
      Sql.first(connection,
        "SELECT document FROM identity_cache WHERE identifier = ? AND expires_at > ?",
        did, System.currentTimeMillis())(_.string("document"))
    }.map(_.flatMap(parse(_).toOption).flatMap(DidDocument.parse(_).toOption))

  private def store(did: String, document: Json): IO[Unit] =
    val now = System.currentTimeMillis()
    database.transact { connection =>
      Sql.update(connection, "DELETE FROM identity_cache WHERE identifier = ?", did)
      Sql.update(connection,
        "INSERT INTO identity_cache(identifier, document, fetched_at, expires_at) VALUES (?, ?, ?, ?)",
        did, document.noSpaces, now, now + cacheSeconds * 1000)
    }.void

  def invalidate(did: String): IO[Unit] =
    database.transact(connection =>
      Sql.update(connection, "DELETE FROM identity_cache WHERE identifier = ?", did)).void
