package pds.security

import com.yubico.webauthn.*
import com.yubico.webauthn.data.*
import io.circe.Json
import java.net.URI
import java.sql.Connection
import java.util.Optional
import pds.{Env, XrpcError}
import pds.accounts.Accounts
import pds.crypto.{Encoding, Hash}
import pds.protocol.Syntax
import pds.storage.Sql
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** WebAuthn credentials, as a second factor and as a primary sign-in method.
  *
  * The relying party is the public hostname, so a credential registered here
  * cannot be used against another origin. Ceremony state lives in the database
  * bound to the browser session that started it, so a challenge cannot be
  * completed by a different browser.
  */
object Passkeys:
  val lifetimeSeconds = 300L
  val maxPerAccount = 20

  /** Passkeys need a secure context: an HTTPS origin, or loopback. */
  def available(env: Env): Boolean = relyingParty(env).isDefined

  private def identity(env: Env): Option[(String, String)] =
    val url = env.config.publicUrl
    val host = url.dropWhile(_ != '/').dropWhile(_ == '/').takeWhile(_ != ':')
    Option.when(env.config.secure || host == "localhost" || host == "127.0.0.1")(host -> url)

  private def relyingParty(env: Env): Option[(String, String)] = identity(env)

  private def party(env: Env, connection: Connection): RelyingParty =
    val (host, origin) = relyingParty(env).getOrElse(
      throw XrpcError.invalidRequest("Passkeys need an HTTPS origin"))
    RelyingParty.builder()
      .identity(RelyingPartyIdentity.builder().id(host).name(host).build())
      .credentialRepository(new Repository(connection))
      .origins(Set(origin).asJava)
      .allowOriginPort(true)
      .build()

  /** Reads credentials for the library; it never writes. */
  private final class Repository(connection: Connection) extends CredentialRepository:
    override def getCredentialIdsForUsername(username: String)
        : java.util.Set[PublicKeyCredentialDescriptor] =
      Sql.query(connection,
        "SELECT credential_id, transports FROM account_passkeys WHERE did = ?", username)(row =>
        (row.string("credential_id"), row.string("transports")))
        .flatMap { (id, transports) =>
          Encoding.unb64(id).map { bytes =>
            PublicKeyCredentialDescriptor.builder()
              .id(new ByteArray(bytes))
              .transports(parseTransports(transports))
              .build()
          }
        }.toSet.asJava

    override def getUserHandleForUsername(username: String): Optional[ByteArray] =
      Sql.first(connection, "SELECT user_handle FROM account_webauthn_users WHERE did = ?",
        username)(_.bytes("user_handle")).map(new ByteArray(_)).toJava

    override def getUsernameForUserHandle(handle: ByteArray): Optional[String] =
      Sql.first(connection, "SELECT did FROM account_webauthn_users WHERE user_handle = ?",
        handle.getBytes)(_.string("did")).toJava

    override def lookup(credentialId: ByteArray, userHandle: ByteArray)
        : Optional[RegisteredCredential] =
      Sql.first(connection,
        """SELECT p.did AS did, p.public_key_cose AS cose, p.signature_count AS count
           FROM account_passkeys p JOIN account_webauthn_users u ON u.did = p.did
           WHERE p.credential_id = ? AND u.user_handle = ?""",
        Encoding.b64(credentialId.getBytes), userHandle.getBytes)(row =>
        RegisteredCredential.builder()
          .credentialId(credentialId)
          .userHandle(userHandle)
          .publicKeyCose(new ByteArray(row.bytes("cose")))
          .signatureCount(row.long("count"))
          .build()).toJava

    override def lookupAll(credentialId: ByteArray): java.util.Set[RegisteredCredential] =
      Sql.query(connection,
        """SELECT u.user_handle AS handle, p.public_key_cose AS cose, p.signature_count AS count
           FROM account_passkeys p JOIN account_webauthn_users u ON u.did = p.did
           WHERE p.credential_id = ?""", Encoding.b64(credentialId.getBytes))(row =>
        RegisteredCredential.builder()
          .credentialId(credentialId)
          .userHandle(new ByteArray(row.bytes("handle")))
          .publicKeyCose(new ByteArray(row.bytes("cose")))
          .signatureCount(row.long("count"))
          .build()).toSet.asJava

  private def parseTransports(value: String): java.util.Set[AuthenticatorTransport] =
    io.circe.parser.parse(value).toOption.flatMap(_.asArray)
      .map(_.flatMap(_.asString).map(AuthenticatorTransport.of).toSet)
      .getOrElse(Set.empty).asJava

  private def userHandle(connection: Connection, did: String): Array[Byte] =
    Sql.first(connection, "SELECT user_handle FROM account_webauthn_users WHERE did = ?", did)(
      _.bytes("user_handle")).getOrElse {
      val handle = Hash.randomBytes(32)
      Sql.update(connection,
        "INSERT INTO account_webauthn_users(did, user_handle) VALUES (?, ?)", did, handle)
      handle
    }

  final case class Ceremony(id: String, options: Json)

  private def store(
      connection: Connection,
      did: Option[String],
      purpose: String,
      sessionToken: String,
      request: String,
      label: Option[String],
      now: Long
  ): String =
    val id = Hash.token()
    Sql.update(connection, "DELETE FROM webauthn_requests WHERE expires_at <= ?", now)
    Sql.update(connection,
      """INSERT INTO webauthn_requests(id, did, purpose, session_hash, request, label,
         created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
      id, did, purpose, Hash.digestToken(sessionToken), request, label, now,
      now + lifetimeSeconds * 1000)
    id

  private def take(
      connection: Connection, id: String, purpose: String, sessionToken: String, now: Long
  ): (Option[String], String, Option[String]) =
    val row = Sql.first(connection,
      """SELECT did, session_hash, request, label, expires_at FROM webauthn_requests
         WHERE id = ? AND purpose = ?""", id, purpose)(row =>
      (row.stringOpt("did"), row.string("session_hash"), row.string("request"),
        row.stringOpt("label"), row.long("expires_at")))
      .getOrElse(throw XrpcError.invalidRequest("That passkey request is no longer valid"))
    Sql.update(connection, "DELETE FROM webauthn_requests WHERE id = ?", id)
    if row._5 <= now || !Hash.constantTimeEquals(row._2, Hash.digestToken(sessionToken)) then
      throw XrpcError.invalidRequest("That passkey request is no longer valid")
    (row._1, row._3, row._4)

  private def json(value: String): Json =
    io.circe.parser.parse(value).getOrElse(Json.obj())

  def beginRegistration(
      env: Env, connection: Connection, did: String, sessionToken: String, label: String, now: Long
  ): Ceremony =
    if label.trim.isEmpty || label.length > 64 then
      throw XrpcError.invalidRequest("Passkey names are 1 to 64 characters")
    if Sql.count(connection,
      "SELECT COUNT(*) AS total FROM account_passkeys WHERE did = ?", did) >= maxPerAccount
    then throw XrpcError.invalidRequest("Too many passkeys")
    val account = Accounts.requireActive(connection, did)
    val handle = userHandle(connection, did)
    val options = party(env, connection).startRegistration(
      StartRegistrationOptions.builder()
        .user(UserIdentity.builder()
          .name(account.handle)
          .displayName(account.handle)
          .id(new ByteArray(handle))
          .build())
        .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
          .residentKey(ResidentKeyRequirement.PREFERRED)
          .userVerification(UserVerificationRequirement.PREFERRED)
          .build())
        .build())
    val id = store(connection, Some(did), "register", sessionToken, options.toJson, Some(label), now)
    Ceremony(id, json(options.toCredentialsCreateJson))

  def finishRegistration(
      env: Env, connection: Connection, id: String, sessionToken: String, response: String,
      now: Long
  ): Json =
    val (did, request, label) = take(connection, id, "register", sessionToken, now)
    val owner = did.getOrElse(throw XrpcError.invalidRequest("That passkey request is not valid"))
    try
      val options = PublicKeyCredentialCreationOptions.fromJson(request)
      val credential = PublicKeyCredential.parseRegistrationResponseJson(response)
      val result = party(env, connection).finishRegistration(
        FinishRegistrationOptions.builder().request(options).response(credential).build())
      // The library's BE/BS accessors are marked unstable; read the flag byte,
      // where backup eligibility is bit 3 and backup state is bit 4.
      val flags = credential.getResponse.getParsedAuthenticatorData.getFlags.value
      Sql.update(connection,
        """INSERT INTO account_passkeys(credential_id, did, label, public_key_cose,
           signature_count, backup_eligible, backed_up, transports, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        Encoding.b64(result.getKeyId.getId.getBytes), owner, label.getOrElse("Passkey"),
        result.getPublicKeyCose.getBytes, result.getSignatureCount,
        (flags & 0x08) != 0, (flags & 0x10) != 0,
        Json.arr(credential.getResponse.getTransports.asScala.toVector
          .map(transport => Json.fromString(transport.getId))*).noSpaces,
        now)
      Json.obj("registered" -> Json.True)
    catch
      case error: XrpcError => throw error
      // The response is client input: a malformed one is a rejection, not a fault.
      case scala.util.control.NonFatal(_) =>
        throw XrpcError.named(org.http4s.Status.BadRequest, "PasskeyRejected",
          "That passkey could not be registered")

  def beginAuthentication(
      env: Env, connection: Connection, did: String, sessionToken: String, now: Long
  ): Ceremony =
    val request = party(env, connection).startAssertion(
      StartAssertionOptions.builder()
        .username(did)
        .userVerification(UserVerificationRequirement.PREFERRED)
        .build())
    val id = store(connection, Some(did), "authenticate", sessionToken, request.toJson, None, now)
    Ceremony(id, json(request.toCredentialsGetJson))

  /** Returns the DID the assertion proves, updating the credential counter. */
  def finishAuthentication(
      env: Env, connection: Connection, id: String, sessionToken: String, response: String,
      now: Long
  ): String =
    val (did, request, _) = take(connection, id, "authenticate", sessionToken, now)
    try
      val assertion = AssertionRequest.fromJson(request)
      val credential = PublicKeyCredential.parseAssertionResponseJson(response)
      val result = party(env, connection).finishAssertion(
        FinishAssertionOptions.builder().request(assertion).response(credential).build())
      if !result.isSuccess then
        throw XrpcError.authRequired("That passkey was not accepted")
      val owner = result.getUsername
      if !did.contains(owner) then throw XrpcError.authRequired("That passkey was not accepted")
      Sql.update(connection,
        """UPDATE account_passkeys SET signature_count = ?, last_used_at = ?
           WHERE credential_id = ?""",
        result.getSignatureCount, now, Encoding.b64(result.getCredential.getCredentialId.getBytes))
      owner
    catch
      case error: XrpcError => throw error
      case scala.util.control.NonFatal(_) =>
        throw XrpcError.authRequired("That passkey was not accepted")

  def list(connection: Connection, did: String): Vector[Json] =
    Sql.query(connection,
      """SELECT credential_id, label, created_at, last_used_at FROM account_passkeys
         WHERE did = ? ORDER BY created_at""", did)(row =>
      Json.obj(
        "id" -> Json.fromString(row.string("credential_id")),
        "name" -> Json.fromString(row.string("label")),
        "createdAt" -> Json.fromString(Syntax.datetime(
          java.time.Instant.ofEpochMilli(row.long("created_at")))),
        "lastUsedAt" -> row.longOpt("last_used_at").map(value =>
          Json.fromString(Syntax.datetime(java.time.Instant.ofEpochMilli(value))))
          .getOrElse(Json.Null)
      ).deepDropNullValues)

  def remove(connection: Connection, did: String, id: String): Json =
    val removed = Sql.update(connection,
      "DELETE FROM account_passkeys WHERE did = ? AND credential_id = ?", did, id)
    if removed == 0 then
      throw XrpcError.named(org.http4s.Status.NotFound, "PasskeyNotFound",
        "That passkey was not found")
    Json.obj("removed" -> Json.True)

  def enabled(connection: Connection, did: String): Boolean =
    Sql.exists(connection, "SELECT 1 FROM account_passkeys WHERE did = ?", did)
