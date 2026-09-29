package pds.firehose

import cats.effect.IO
import fs2.Stream
import org.http4s.{Request, Response}
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import pds.Env
import scala.concurrent.duration.*

/** `com.atproto.sync.subscribeRepos`: backfill from the requested cursor, then
  * poll the durable sequence. Frames are DAG-CBOR, header then body.
  */
object Firehose:
  private val pollInterval = 500.millis
  private val keepAlive = 30.seconds

  /** What a requested cursor means against the sequence still retained.
    *
    * A cursor beyond the end cannot be honoured at all. A cursor older than the
    * oldest retained event can be resumed from, but events in between are gone,
    * and a consumer that is not told so would believe it had a complete history.
    */
  final case class Plan(start: Long, ahead: Boolean, skipped: Boolean)

  def plan(requested: Option[Long], oldest: Long, latest: Long): Plan =
    requested match
      case Some(cursor) if cursor > latest  => Plan(latest, ahead = true, skipped = false)
      case Some(cursor) if cursor < 0       => Plan(latest, ahead = false, skipped = false)
      case Some(cursor) if oldest > 0 && cursor < oldest - 1 =>
        Plan(oldest - 1, ahead = false, skipped = true)
      case Some(cursor)                     => Plan(cursor, ahead = false, skipped = false)
      case None                             => Plan(latest, ahead = false, skipped = false)

  def subscribe(
      env: Env,
      request: Request[IO],
      builder: WebSocketBuilder2[IO],
      metrics: pds.api.Metrics
  ): IO[Response[IO]] =
    val requested = request.params.get("cursor").flatMap(_.toLongOption)
    for
      bounds <- env.database.read(connection =>
        (Events.oldest(connection), Events.latest(connection)))
      chosen = plan(requested, bounds._1, bounds._2)
      _ <- metrics.subscriberOpened
      response <- builder
        .withOnClose(metrics.subscriberClosed)
        .build(frames(env, chosen), _.drain)
    yield response

  private def binary(bytes: Array[Byte]): WebSocketFrame =
    WebSocketFrame.Binary(scodec.bits.ByteVector(bytes))

  private def frames(env: Env, chosen: Plan): Stream[IO, WebSocketFrame] =
    if chosen.ahead then
      // An error frame ends the subscription, so nothing follows it.
      Stream.emit(binary(Events.errorFrame("FutureCursor", "Cursor is ahead of the sequence")))
    else
      notice(chosen) ++ live(env, chosen.start)

  private def notice(chosen: Plan): Stream[IO, WebSocketFrame] =
    if !chosen.skipped then Stream.empty
    else Stream.emit(binary(Events.infoFrame("OutdatedCursor",
      "Cursor is older than the retained sequence, so some events were skipped")))

  private def live(env: Env, start: Long): Stream[IO, WebSocketFrame] =
    Stream.eval(cats.effect.Ref.of[IO, Long](start)).flatMap { cursor =>
      val events = Stream.awakeEvery[IO](pollInterval).evalMap { _ =>
        cursor.get.flatMap(from =>
          env.database.read(connection => Events.since(connection, from, Events.maxBackfill)))
      }.flatMap { batch =>
        Stream.eval(batch.lastOption.map(event => cursor.set(event.seq)).getOrElse(IO.unit)) >>
          Stream.emits(batch.map(event => binary(Events.frame(event))))
      }
      events.merge(Stream.awakeEvery[IO](keepAlive).map(_ => WebSocketFrame.Ping()))
    }
