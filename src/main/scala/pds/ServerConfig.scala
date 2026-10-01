package pds

import com.comcast.ip4s.{Host, Port}
import java.util.Locale

final case class ServerConfig private (
    host: Host,
    port: Port,
    hostname: String,
    publicUrl: String,
    userDomain: String,
    signupEnabled: Boolean,
    inviteRequired: Boolean,
    plcDirectory: String,
    didMethod: String,
    appviewUrl: Option[String],
    appviewDid: Option[String],
    modServiceDid: Option[String],
    modServiceUrl: Option[String],
    handleAuthority: Option[String],
    reservedHandles: Set[String],
    relayUrls: Vector[String],
    adminPassword: Option[String],
    contactEmail: Option[String],
    privacyPolicyUrl: Option[String],
    termsOfServiceUrl: Option[String],
    emailEndpoint: Option[String],
    emailToken: Option[String],
    emailFrom: String,
    blobMaxSize: Long,
    rateLimitPerMinute: Int,
    firehoseRetentionHours: Int,
    accessLog: Boolean,
    webauthnRpId: Option[String],
    webauthnOrigins: Vector[String]
):
  val serviceDid: String = s"did:web:$hostname"
  def emailEnabled: Boolean = emailEndpoint.isDefined
  def secure: Boolean = publicUrl.startsWith("https://")
  def availableUserDomains: Vector[String] = Vector(s".$userDomain")

object ServerConfig:
  /** Names nobody may register. The built-in ones shadow this server's own
    * routes and protocol identifiers; a deployment adds to them, and servers
    * sharing one handle domain should reserve the same names, or a name refused
    * on one can still be taken on another.
    */
  private val builtInReservedHandles = Set(
    "admin", "administrator", "api", "account", "atproto", "did", "help", "localhost",
    "moderation", "oauth", "pds", "root", "security", "support", "system", "www", "xrpc")

  private def reservedHandles(env: Map[String, String]): Either[String, Set[String]] =
    val configured = env.getOrElse("PDS_RESERVED_HANDLES", "").split(",").toVector
      .map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty)
    configured.find(name => !name.matches("[a-z0-9-]+")) match
      case Some(invalid) =>
        Left(s"PDS_RESERVED_HANDLES may only contain handle labels, found '$invalid'")
      case None => Right(builtInReservedHandles ++ configured)

  def fromEnv(env: Map[String, String]): Either[String, ServerConfig] =
    val hostname = env.getOrElse("PDS_HOSTNAME", "localhost").toLowerCase(Locale.ROOT)
    for
      host <- Host.fromString(env.getOrElse("PDS_HOST", "127.0.0.1"))
        .toRight("PDS_HOST must be a valid bind address")
      port <- env.getOrElse("PDS_PORT", "3000").toIntOption
        .filter(value => value >= 1 && value <= 65535).flatMap(Port.fromInt)
        .toRight("PDS_PORT must be an integer between 1 and 65535")
      _ <- Either.cond(validHostname(hostname), (),
        "PDS_HOSTNAME must be a DNS hostname without scheme, port, or path")
      publicUrl <- resolvePublicUrl(env, hostname, port.value)
      userDomain = env.getOrElse("PDS_USER_DOMAIN", hostname).toLowerCase(Locale.ROOT)
      _ <- Either.cond(validHostname(userDomain), (), "PDS_USER_DOMAIN must be a DNS hostname")
      // A loopback development server cannot publish to a public directory.
      didMethod = env.getOrElse("PDS_DID_METHOD",
        if Set("localhost", "127.0.0.1").contains(hostname) then "web" else "plc")
      _ <- Either.cond(Set("plc", "web").contains(didMethod), (),
        "PDS_DID_METHOD must be 'plc' or 'web'")
      plcDirectory <- origin(env.getOrElse("PDS_PLC_DIRECTORY", "https://plc.directory"))
        .toRight("PDS_PLC_DIRECTORY must be an http(s) URL")
      appviewUrl <- optionalOrigin(env, "PDS_APPVIEW_URL")
      appviewDid = env.get("PDS_APPVIEW_DID").filter(_.startsWith("did:"))
      modServiceDid = env.get("PDS_MOD_SERVICE_DID").filter(_.startsWith("did:"))
      modServiceUrl <- optionalOrigin(env, "PDS_MOD_SERVICE_URL")
      handleAuthority <- optionalOrigin(env, "PDS_HANDLE_AUTHORITY")
      reserved <- reservedHandles(env)
      relays <- env.getOrElse("PDS_RELAY_URLS", "").split(",").toVector
        .map(_.trim).filter(_.nonEmpty)
        .foldLeft[Either[String, Vector[String]]](Right(Vector.empty)) { (acc, value) =>
          for
            list <- acc
            url <- origin(value).toRight(s"PDS_RELAY_URLS contains an invalid URL")
          yield list :+ url
        }
      adminPassword <- env.get("PDS_ADMIN_PASSWORD") match
        case Some(value) if value.length < 12 =>
          Left("PDS_ADMIN_PASSWORD must be at least 12 characters")
        case other => Right(other)
      emailEndpoint <- optionalOrigin(env, "PDS_EMAIL_ENDPOINT")
      blobMaxSize <- env.getOrElse("PDS_BLOB_MAX_SIZE", "5242880").toLongOption
        .filter(value => value >= 1024 && value <= 100L * 1024 * 1024)
        .toRight("PDS_BLOB_MAX_SIZE must be between 1024 and 104857600")
      rateLimit <- env.getOrElse("PDS_RATE_LIMIT_PER_MINUTE", "300").toIntOption
        .filter(value => value >= 1 && value <= 100000)
        .toRight("PDS_RATE_LIMIT_PER_MINUTE must be between 1 and 100000")
      retention <- env.getOrElse("PDS_FIREHOSE_RETENTION_HOURS", "72").toIntOption
        .filter(value => value >= 0 && value <= 8760)
        .toRight("PDS_FIREHOSE_RETENTION_HOURS must be between 0 and 8760")
    yield ServerConfig(
      host = host,
      port = port,
      hostname = hostname,
      publicUrl = publicUrl,
      userDomain = userDomain,
      signupEnabled = flag(env, "PDS_SIGNUP_ENABLED", default = true),
      inviteRequired = flag(env, "PDS_INVITE_REQUIRED", default = false),
      plcDirectory = plcDirectory,
      didMethod = didMethod,
      appviewUrl = appviewUrl,
      appviewDid = appviewDid,
      modServiceDid = modServiceDid,
      modServiceUrl = modServiceUrl,
      handleAuthority = handleAuthority,
      reservedHandles = reserved,
      relayUrls = relays,
      adminPassword = adminPassword,
      contactEmail = env.get("PDS_CONTACT_EMAIL").filter(_.contains('@')),
      privacyPolicyUrl = env.get("PDS_PRIVACY_POLICY_URL"),
      termsOfServiceUrl = env.get("PDS_TERMS_OF_SERVICE_URL"),
      emailEndpoint = emailEndpoint,
      emailToken = env.get("PDS_EMAIL_TOKEN").filter(_.nonEmpty),
      emailFrom = env.getOrElse("PDS_EMAIL_FROM", s"noreply@$hostname"),
      blobMaxSize = blobMaxSize,
      rateLimitPerMinute = rateLimit,
      firehoseRetentionHours = retention,
      accessLog = flag(env, "PDS_ACCESS_LOG", default = true),
      // A credential is bound to its relying party for life, and a browser only
      // uses one whose RP ID is the page's own domain or a parent of it. Set
      // this to the domain shared with any sign-in page in front of this node
      // so one credential works from both.
      webauthnRpId = env.get("PDS_WEBAUTHN_RP_ID")
        .map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty),
      webauthnOrigins = env.getOrElse("PDS_WEBAUTHN_ORIGINS", "")
        .split(",").toVector.map(_.trim).filter(_.nonEmpty)
    )

  private def flag(env: Map[String, String], name: String, default: Boolean): Boolean =
    env.get(name).map(value => Set("1", "true", "yes", "on").contains(value.toLowerCase(Locale.ROOT)))
      .getOrElse(default)

  private def optionalOrigin(env: Map[String, String], name: String): Either[String, Option[String]] =
    env.get(name).filter(_.nonEmpty) match
      case None        => Right(None)
      case Some(value) => origin(value).map(Some.apply).toRight(s"$name must be an http(s) URL")

  /** Canonical origin: lowercase host, no path, no default port. */
  private def origin(value: String): Option[String] =
    val uri = try Some(java.net.URI.create(value)) catch case _: IllegalArgumentException => None
    uri.filter { parsed =>
      val scheme = Option(parsed.getScheme).getOrElse("")
      val host = Option(parsed.getHost).getOrElse("")
      val port = parsed.getPort
      host.nonEmpty && host == host.toLowerCase(Locale.ROOT) &&
        (scheme == "https" || (scheme == "http" && Set("localhost", "127.0.0.1").contains(host))) &&
        (port == -1 || (port >= 1 && port <= 65535 && port != (if scheme == "https" then 443 else 80))) &&
        value == s"$scheme://$host${if port == -1 then "" else s":$port"}"
    }.map(_ => value)

  private def resolvePublicUrl(
      env: Map[String, String], hostname: String, port: Int
  ): Either[String, String] =
    env.get("PDS_PUBLIC_URL").filter(_.nonEmpty) match
      case Some(value) => origin(value).toRight("PDS_PUBLIC_URL must be a canonical http(s) origin")
      case None =>
        val loopback = Set("localhost", "127.0.0.1").contains(hostname)
        Right(if loopback then s"http://$hostname:$port" else s"https://$hostname")

  private def validHostname(value: String): Boolean =
    value.nonEmpty && value.length <= 253 && value.split("\\.", -1).forall { label =>
      label.length <= 63 && label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
    } && value.lastOption.exists(c => c >= 'a' && c <= 'z')
