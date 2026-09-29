package pds.lexicon

import cats.effect.{IO, Ref}
import cats.effect.std.Semaphore
import io.circe.Json
import pds.identity.{Net, Resolver}

/** A bounded, process-local cache of admitted remote schema catalogs.
  *
  * Resolution is expensive and involves the network, so it happens outside
  * every write transaction, at most sixteen at a time, and a failure is
  * remembered briefly so a broken namespace cannot be used to hammer DNS.
  */
final class Schemas(
    entries: Ref[IO, Map[String, Schemas.Entry]],
    permits: Semaphore[IO],
    resolve: (String) => IO[Either[String, Json]]
):
  import Schemas.*

  def known(collection: String, now: Long): IO[Option[Catalog]] =
    entries.get.map(_.get(collection).filter(_.until > now).flatMap(_.catalog))

  /** The admitted catalog for `collection`, resolving it if necessary. */
  def catalog(collection: String, now: Long): IO[Either[String, Catalog]] =
    known(collection, now).flatMap {
      case Some(found) => IO.pure(Right(found))
      case None =>
        permits.tryPermit.use {
          case false => IO.pure(Left("Record schema resolution is busy; try again"))
          case true  => crawl(collection).flatMap(remember(collection, _, now))
        }
    }

  private def remember(
      collection: String, result: Either[String, Catalog], now: Long
  ): IO[Either[String, Catalog]] =
    val entry = result match
      case Right(catalog) => Entry(Some(catalog), now + successMillis, size(catalog))
      case Left(_)        => Entry(None, now + failureMillis, 0)
    entries.update { current =>
      val live = current.filter((_, value) => value.until > now)
      val bytes = live.values.map(_.bytes).sum + entry.bytes
      if live.size >= capacity || bytes > byteCapacity then
        live.updated(collection, Entry(None, now + failureMillis, 0))
      else live.updated(collection, entry)
    }.as(result)

  private def size(catalog: Catalog): Int =
    catalog.schemas.values.map(_.noSpaces.getBytes("UTF-8").length).sum

  /** Fetches the reachable document graph, then admits it as a whole. */
  private def crawl(collection: String): IO[Either[String, Catalog]] =
    def loop(
        documents: Map[String, Json], pending: List[String]
    ): IO[Either[String, Map[String, Json]]] =
      pending match
        case Nil => IO.pure(Right(documents))
        case id :: rest if documents.contains(id) => loop(documents, rest)
        case _ if documents.size >= Admission.maxDocuments =>
          IO.pure(Left("Schema graph needs too many documents"))
        case id :: rest =>
          resolve(id).flatMap {
            case Left(reason) => IO.pure(Left(reason))
            case Right(document) =>
              val next = documents.updated(id, document)
              loop(next, rest ++ Admission.referencedIds(document).diff(next.keySet).toList)
          }
    loop(Map.empty, List(collection)).map(_.flatMap(documents =>
      Admission.catalog(id => documents.get(id).orElse(Catalog.trusted.schema(id)), collection)))

object Schemas:
  val successMillis = 3600_000L
  val failureMillis = 60_000L
  val capacity = 128
  val byteCapacity = 16 * 1024 * 1024

  final case class Entry(catalog: Option[Catalog], until: Long, bytes: Int)

  def create(resolve: String => IO[Either[String, Json]]): IO[Schemas] =
    for
      entries <- Ref.of[IO, Map[String, Entry]](Map.empty)
      permits <- Semaphore[IO](16)
    yield new Schemas(entries, permits, resolve)

  def network(net: Net, resolver: Resolver): IO[Schemas] =
    create(nsid => Resolution.resolve(net, resolver, nsid))

  /** A cache that never resolves: used where the network is not available. */
  def offline: IO[Schemas] = create(_ => IO.pure(Left("Record schema resolution is unavailable")))
