package pds.api

import cats.effect.IO
import org.http4s.{Request, Response}

enum Kind:
  case Query, Procedure

final case class Endpoint(kind: Kind, run: Request[IO] => IO[Response[IO]])

object Endpoint:
  def query(run: Request[IO] => IO[Response[IO]]): Endpoint = Endpoint(Kind.Query, run)
  def procedure(run: Request[IO] => IO[Response[IO]]): Endpoint = Endpoint(Kind.Procedure, run)
