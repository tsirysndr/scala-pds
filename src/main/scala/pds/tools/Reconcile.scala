package pds.tools

import cats.effect.IO
import pds.Env
import pds.accounts.{Account, Accounts}
import pds.firehose.Events
import pds.identity.PlcDirectory
import pds.protocol.Syntax
import pds.repo.RepoStore
import pds.storage.Sql

final case class Drift(did: String, handle: String, kind: String, detail: String, repaired: Boolean)

final case class Reconciliation(checked: Int, drift: Vector[Drift]):
  def outstanding: Vector[Drift] = drift.filterNot(_.repaired)

/** Compares every managed `did:plc` account against the directory's log head.
  *
  * The directory is the authority, and anyone holding a rotation key can change
  * it — including a recovery operation signed entirely outside this server. The
  * local view can therefore be stale or simply wrong, so the difference is
  * looked for rather than assumed away.
  */
object Reconcile:
  def run(env: Env, identifier: Option[String], repair: Boolean): IO[Reconciliation] =
    for
      accounts <- env.database.read { connection =>
        identifier match
          case Some(value) => Accounts.byIdentifier(connection, value).toVector
          case None        => Accounts.list(connection, 10000, None)
      }
      managed = accounts.filter(account => account.did.startsWith("did:plc:"))
      drift <- managed.foldLeft(IO.pure(Vector.empty[Drift])) { (acc, account) =>
        acc.flatMap(found => check(env, account, repair).map(found ++ _))
      }
    yield Reconciliation(managed.size, drift)

  private def check(env: Env, account: Account, repair: Boolean): IO[Vector[Drift]] =
    for
      local <- env.database.read { connection =>
        (RepoStore.signingKey(connection, account.did, env.sealing).publicKey.didKey,
          Sql.first(connection, "SELECT rotation_public FROM account_keys WHERE did = ?",
            account.did)(_.stringOpt("rotation_public")).flatten)
      }
      head <- PlcDirectory(env.net, env.config.plcDirectory).lastOperation(account.did)
      // The published document may have changed, so the cached copy goes.
      _ <- env.resolver.invalidate(account.did)
      drift <- head match
        case None =>
          retire(env, account, repair).map(repaired => Vector(Drift(account.did, account.handle,
            "absent", "the directory holds no operation for this DID", repaired)))
        case Some((operation, _)) =>
          val endpoint = operation.services.get("atproto_pds").map(_._2)
          val signing = operation.verificationMethods.get("atproto")
          val published = operation.alsoKnownAs.collectFirst {
            case alias if alias.startsWith("at://") => Syntax.normalizeHandle(alias.drop(5))
          }
          val elsewhere = !endpoint.contains(env.config.publicUrl)
          for
            moved <-
              if !elsewhere then IO.pure(Vector.empty[Drift])
              else
                retire(env, account, repair).map(repaired => Vector(Drift(account.did,
                  account.handle, "endpoint",
                  s"the document names ${endpoint.getOrElse("no PDS")}", repaired)))
            renamed <-
              if elsewhere || published.contains(account.handle) then IO.pure(Vector.empty[Drift])
              else
                rename(env, account, published, repair).map { repaired =>
                  Vector(Drift(account.did, account.handle, "handle",
                    s"the document names ${published.getOrElse("no handle")}", repaired))
                }
          yield
            // A signing or rotation key that moved cannot be repaired from here:
            // whoever signed the change holds a key this server does not.
            val keys = Vector(
              Option.when(!signing.contains(local._1))(Drift(account.did, account.handle,
                "signing-key", s"the document names ${signing.getOrElse("no key")}, " +
                  s"this server holds ${local._1}", false)),
              Option.when(local._2.exists(key => !operation.rotationKeys.contains(key)))(
                Drift(account.did, account.handle, "rotation-key",
                  "the rotation key held here is no longer in the log", false))
            ).flatten
            moved ++ renamed ++ keys
    yield drift

  private def retire(env: Env, account: Account, repair: Boolean): IO[Boolean] =
    if !repair || !account.active then IO.pure(false)
    else
      env.now.flatMap { now =>
        env.database.transact { connection =>
          Accounts.deactivate(connection, account.did, None, now)
          Events.account(connection, account.did, active = false, None)
          true
        }
      }

  private def rename(
      env: Env, account: Account, published: Option[String], repair: Boolean
  ): IO[Boolean] =
    published match
      case Some(handle) if repair && Syntax.isHandle(handle) =>
        env.now.flatMap { now =>
          env.database.transact { connection =>
            if !Accounts.handleAvailable(connection, handle) then false
            else
              Accounts.setHandle(connection, account.did, handle)
              Sql.update(connection,
                "INSERT INTO handle_reservations(handle, did, created_at) VALUES (?, ?, ?)",
                handle, account.did, now)
              Events.identity(connection, account.did, Some(handle))
              true
          }
        }
      case _ => IO.pure(false)
