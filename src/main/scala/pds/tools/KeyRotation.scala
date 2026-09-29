package pds.tools

import cats.effect.IO
import pds.{Env, XrpcError}
import pds.accounts.Accounts
import pds.crypto.{Curve, PrivateKey}
import pds.firehose.Events
import pds.identity.{Plc, PlcDirectory}
import pds.protocol.{Commit, Tid}
import pds.repo.RepoStore
import pds.storage.Sql

/** Replaces an account's managed keys and publishes the change.
  *
  * Rotating the signing key means the head commit was signed by a key the DID
  * document no longer names, so the head is re-signed at a new revision in the
  * same transaction that stores the new key. Consumers that verify the current
  * commit against the current document therefore never see a gap.
  */
object KeyRotation:
  final case class Failed(reason: String) extends RuntimeException(reason)

  final case class Result(
      did: String,
      handle: String,
      signingKey: Option[String],
      rotationKey: Option[String],
      revision: Option[String]
  )

  def rotate(
      env: Env, identifier: String, signing: Boolean, rotation: Boolean
  ): IO[Result] =
    if !signing && !rotation then IO.raiseError(Failed("Nothing to rotate"))
    else
      for
        account <- env.database.read(connection =>
          Accounts.byIdentifier(connection, identifier)
            .getOrElse(throw Failed(s"No account matches $identifier")))
        _ <- IO.raiseUnless(account.active)(Failed(s"${account.handle} is not active"))
        nextSigning = Option.when(signing)(PrivateKey.generate(Curve.K256))
        nextRotation = Option.when(rotation)(PrivateKey.generate(Curve.K256))
        _ <- publish(env, account.did, nextSigning, nextRotation)
        result <- store(env, account.did, account.handle, nextSigning, nextRotation)
      yield result

  /** Updates the directory first: a rejected operation must not leave the
    * server holding a key the published document does not name.
    */
  private def publish(
      env: Env, did: String, signing: Option[PrivateKey], rotation: Option[PrivateKey]
  ): IO[Unit] =
    if !did.startsWith("did:plc:") then IO.unit
    else
      for
        key <- env.database.read(connection => rotationKey(env, connection, did))
        current <- IO.fromOption(key)(Failed("This account has no managed rotation key"))
        directory = PlcDirectory(env.net, env.config.plcDirectory)
        last <- directory.lastOperation(did)
        previous <- IO.fromOption(last)(
          Failed("The directory has no operation log for this account"))
        operation = Plc.update(previous._1, previous._2) { op =>
          op.copy(
            verificationMethods = signing.fold(op.verificationMethods)(value =>
              op.verificationMethods.updated("atproto", value.publicKey.didKey)),
            rotationKeys = rotation.fold(op.rotationKeys)(value =>
              // The new key replaces this server's; any recovery key stays.
              op.rotationKeys.filterNot(_ == current.publicKey.didKey) :+ value.publicKey.didKey)
          )
        }.sign(current)
        _ <- directory.submit(did, operation)
        _ <- env.resolver.invalidate(did)
      yield ()

  private def store(
      env: Env, did: String, handle: String, signing: Option[PrivateKey],
      rotation: Option[PrivateKey]
  ): IO[Result] =
    env.database.transact { connection =>
      signing.foreach { key =>
        Sql.update(connection,
          """UPDATE account_keys SET signing_curve = ?, signing_public = ?, signing_sealed = ?
             WHERE did = ?""",
          key.curve.name, key.publicKey.didKey, env.sealing.sealKey(key), did)
      }
      rotation.foreach { key =>
        Sql.update(connection,
          "UPDATE account_keys SET rotation_public = ?, rotation_sealed = ? WHERE did = ?",
          key.publicKey.didKey, env.sealing.sealKey(key), did)
      }
      val revision = signing.map { key =>
        val head = RepoStore.requireHead(connection, did)
        val rev = Tid.next()
        val resigned = Commit.sign(did, head.root, rev, Some(head.commit.cid), key)
        RepoStore.writeBlocks(connection, did, Map(resigned.cid -> resigned.bytes), rev)
        Sql.update(connection,
          "UPDATE repo_roots SET commit_cid = ?, rev = ? WHERE did = ?",
          resigned.cid.toString, rev, did)
        Events.commit(connection, did,
          pds.protocol.Applied(resigned, resigned.cid, head.root, Some(head.root),
            Map(resigned.cid -> resigned.bytes), Vector.empty, Map.empty),
          Some(head.rev))
        rev
      }
      Events.identity(connection, did, Some(handle))
      Result(did, handle, signing.map(_.publicKey.didKey), rotation.map(_.publicKey.didKey),
        revision)
    }

  private def rotationKey(
      env: Env, connection: java.sql.Connection, did: String
  ): Option[PrivateKey] =
    Sql.first(connection, "SELECT rotation_sealed FROM account_keys WHERE did = ?", did)(
      _.bytesOpt("rotation_sealed")).flatten.flatMap(env.sealing.openKey(Curve.K256, _))
