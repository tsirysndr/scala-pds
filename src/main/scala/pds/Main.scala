package pds

import cats.effect.{IO, IOApp}
import org.http4s.ember.server.EmberServerBuilder

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    for
      env <- IO(sys.env)
      config <- IO.fromEither(ServerConfig.fromEnv(env).left.map(new IllegalArgumentException(_)))
      _ <- EmberServerBuilder.default[IO]
        .withHost(config.host)
        .withPort(config.port)
        .withHttpApp(PdsApp(config))
        .build
        .useForever
    yield ()
