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

  def subscribe(
      env: Env,
      request: Request[IO],
      builder: WebSocketBuilder2[IO],
      metrics: pds.api.Metrics
  ): IO[Response[IO]] =
    val requested = request.params.get("cursor").flatMap(_.toLongOption)
    for
      latest <- env.database.read(Events.latest)
      start <- requested match
        case None => IO.pure(latest)
        case Some(cursor) if cursor < 0 => IO.pure(latest)
        case Some(cursor) if cursor > latest =>
          IO.pure(latest).flatTap(_ => IO.unit)
        case Some(cursor) => IO.pure(cursor)
      future = requested.exists(_ > latest)
      _ <- metrics.subscriberOpened
      response <- builder
        .withOnClose(metrics.subscriberClosed)
        .build(frames(env, start, future), _.drain)
    yield response

  private def frames(env: Env, start: Long, future: Boolean): Stream[IO, WebSocketFrame] =
    val outdated =
      if !future then Stream.empty
      else Stream.emit(WebSocketFrame.Binary(
        scodec.bits.ByteVector(Events.errorFrame("FutureCursor", "Cursor is ahead of the sequence"))))
    outdated ++ Stream.eval(cats.effect.Ref.of[IO, Long](start)).flatMap { cursor =>
      val events = Stream.awakeEvery[IO](pollInterval).evalMap { _ =>
        cursor.get.flatMap(from =>
          env.database.read(connection => Events.since(connection, from, Events.maxBackfill)))
      }.flatMap { batch =>
        Stream.eval(batch.lastOption.map(event => cursor.set(event.seq)).getOrElse(IO.unit)) >>
          Stream.emits(batch.map(event =>
            WebSocketFrame.Binary(scodec.bits.ByteVector(Events.frame(event)))))
      }
      events.merge(Stream.awakeEvery[IO](keepAlive).map(_ => WebSocketFrame.Ping()))
    }
