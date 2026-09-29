package pds

import cats.effect.IO
import pds.crypto.Sealing
import pds.identity.{Net, Resolver}
import pds.lexicon.Schemas
import pds.storage.Database

final case class Env(
    config: ServerConfig,
    database: Database,
    sealing: Sealing,
    net: Net,
    resolver: Resolver,
    schemas: Schemas
):
  def now: IO[Long] = IO.realTime.map(_.toMillis)
