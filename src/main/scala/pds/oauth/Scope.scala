package pds.oauth

/** OAuth scopes this authorization server issues. `atproto` is mandatory and
  * identifies the account; the transition scopes carry the broad permissions
  * current clients expect, and granular `repo:`/`rpc:`/`blob:` permissions are
  * parsed so the consent screen can describe them.
  */
object Scope:
  val required = "atproto"

  val transition = Set("transition:generic", "transition:chat.bsky", "transition:email")

  private val granularPrefixes = Set("repo", "rpc", "blob", "account", "identity", "include")

  def supported: Set[String] = transition + required

  def parse(value: String): Either[String, Vector[String]] =
    val parts = value.split(" ").toVector.map(_.trim).filter(_.nonEmpty)
    if parts.isEmpty then Left("A scope is required")
    else if parts.distinct.length != parts.length then Left("Scope values must be distinct")
    else if !parts.contains(required) then Left("The atproto scope is required")
    else if parts.length > 32 then Left("Too many scope values")
    else if parts.forall(known) then Right(parts)
    else Left("Unsupported scope value")

  private def known(value: String): Boolean =
    supported.contains(value) || value.split(":", 2).headOption.exists(granularPrefixes.contains)

  /** Human-readable permission lines for the consent screen. */
  def permissions(values: Vector[String]): Vector[String] =
    values.flatMap {
      case `required`               => Some("Confirm your account identity")
      case "transition:generic"     => Some("Read and write your repository and account data")
      case "transition:chat.bsky"   => Some("Read and send your direct messages")
      case "transition:email"       => Some("Read your email address")
      case value if value.startsWith("repo:")     => Some(s"Write records in ${value.drop(5)}")
      case value if value.startsWith("rpc:")      => Some(s"Call ${value.drop(4)} on your behalf")
      case value if value.startsWith("blob:")     => Some(s"Upload blobs of type ${value.drop(5)}")
      case value if value.startsWith("account:")  => Some(s"Manage your ${value.drop(8)} settings")
      case value if value.startsWith("identity:") => Some(s"Manage your ${value.drop(9)}")
      case _                                      => None
    }.distinct

  def grants(values: Vector[String], method: String): Boolean =
    values.contains("transition:generic") ||
      values.contains(s"rpc:$method") || values.contains("rpc:*") ||
      (method.startsWith("chat.bsky.") && values.contains("transition:chat.bsky"))
