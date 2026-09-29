package pds

class ServerConfigSuite extends munit.FunSuite:
  test("development defaults bind only to loopback") {
    val config = ServerConfig.fromEnv(Map.empty).toOption.get
    assertEquals(config.host.toString, "127.0.0.1")
    assertEquals(config.port.value, 3000)
    assertEquals(config.serviceDid, "did:web:localhost")
  }

  test("public hostname is normalized independently of bind address") {
    val config = ServerConfig.fromEnv(Map(
      "PDS_HOST" -> "0.0.0.0", "PDS_PORT" -> "8080", "PDS_HOSTNAME" -> "PDS.Example.com"
    )).toOption.get
    assertEquals(config.host.toString, "0.0.0.0")
    assertEquals(config.port.value, 8080)
    assertEquals(config.serviceDid, "did:web:pds.example.com")
  }

  test("invalid ports fail instead of falling back to defaults") {
    List("", "abc", "0", "-1", "65536", "999999999999").foreach { port =>
      assert(ServerConfig.fromEnv(Map("PDS_PORT" -> port)).isLeft, port)
    }
  }

  test("invalid hostnames cannot become service DIDs") {
    List("", "https://example.com", "example.com:3000", "example.com/path",
      "a..com", "-a.com", "a_.com", "example.com.", "127.0.0.1", "a" * 64 + ".com"
    ).foreach { hostname =>
      assert(ServerConfig.fromEnv(Map("PDS_HOSTNAME" -> hostname)).isLeft, hostname)
    }
  }

  test("invalid bind address fails validation") {
    assert(ServerConfig.fromEnv(Map("PDS_HOST" -> "http://localhost")).isLeft)
  }

  test("the public URL defaults to loopback HTTP in development and HTTPS otherwise") {
    assertEquals(ServerConfig.fromEnv(Map.empty).toOption.get.publicUrl, "http://localhost:3000")
    assertEquals(
      ServerConfig.fromEnv(Map("PDS_HOSTNAME" -> "pds.example.com")).toOption.get.publicUrl,
      "https://pds.example.com")
  }

  test("only canonical origins are accepted as the public URL") {
    List("https://PDS.example.com", "https://pds.example.com/", "https://pds.example.com:443",
      "http://pds.example.com", "pds.example.com", "https://pds.example.com/path"
    ).foreach(value =>
      assert(ServerConfig.fromEnv(Map("PDS_PUBLIC_URL" -> value)).isLeft, value))
    assertEquals(
      ServerConfig.fromEnv(Map("PDS_PUBLIC_URL" -> "https://pds.example.com:8443"))
        .map(_.publicUrl), Right("https://pds.example.com:8443"))
  }

  test("optional services are validated when supplied") {
    assert(ServerConfig.fromEnv(Map("PDS_APPVIEW_URL" -> "not a url")).isLeft)
    assert(ServerConfig.fromEnv(Map("PDS_DID_METHOD" -> "sov")).isLeft)
    assert(ServerConfig.fromEnv(Map("PDS_ADMIN_PASSWORD" -> "short")).isLeft)
    assert(ServerConfig.fromEnv(Map("PDS_BLOB_MAX_SIZE" -> "10")).isLeft)
    assert(ServerConfig.fromEnv(Map("PDS_RELAY_URLS" -> "https://relay.example.com,bogus")).isLeft)
    val config = ServerConfig.fromEnv(Map(
      "PDS_RELAY_URLS" -> "https://relay1.example.com, https://relay2.example.com",
      "PDS_APPVIEW_URL" -> "https://appview.example.com",
      "PDS_APPVIEW_DID" -> "did:web:appview.example.com"
    )).toOption.get
    assertEquals(config.relayUrls.length, 2)
    assertEquals(config.appviewDid, Some("did:web:appview.example.com"))
  }

  test("handle domains follow the user domain and default to the hostname") {
    assertEquals(
      ServerConfig.fromEnv(Map("PDS_HOSTNAME" -> "pds.example.com")).toOption.get.availableUserDomains,
      Vector(".pds.example.com"))
    assertEquals(
      ServerConfig.fromEnv(Map("PDS_HOSTNAME" -> "pds.example.com", "PDS_USER_DOMAIN" -> "Example.com"))
        .toOption.get.availableUserDomains, Vector(".example.com"))
  }

  test("signup and invite policy come from the environment") {
    val defaults = ServerConfig.fromEnv(Map.empty).toOption.get
    assert(defaults.signupEnabled)
    assert(!defaults.inviteRequired)
    val strict = ServerConfig.fromEnv(
      Map("PDS_SIGNUP_ENABLED" -> "false", "PDS_INVITE_REQUIRED" -> "true")).toOption.get
    assert(!strict.signupEnabled)
    assert(strict.inviteRequired)
  }

  test("reserved handles add to the built-in ones") {
    val config = ServerConfig.fromEnv(Map(
      "PDS_HOSTNAME" -> "pds.example.com",
      "PDS_RESERVED_HANDLES" -> "dietpi, raspberrypi4,ORANGEPI-ZERO-3W, ,noreply")).toOption.get
    // Configured names are added, lowercased and trimmed.
    assert(config.reservedHandles.contains("dietpi"))
    assert(config.reservedHandles.contains("raspberrypi4"))
    assert(config.reservedHandles.contains("orangepi-zero-3w"))
    assert(config.reservedHandles.contains("noreply"))
    assert(!config.reservedHandles.contains(""))
    // The built-in ones that shadow this server's own routes survive.
    assert(config.reservedHandles.contains("xrpc"))
    assert(config.reservedHandles.contains("oauth"))
    assert(config.reservedHandles.contains("did"))
  }

  test("an unset list still reserves the built-in names") {
    val config = ServerConfig.fromEnv(Map("PDS_HOSTNAME" -> "pds.example.com")).toOption.get
    assert(config.reservedHandles.contains("admin"))
    assert(config.reservedHandles.contains("xrpc"))
  }

  test("a reserved entry that is not a handle label is refused") {
    for value <- Vector("alice.example.com", "has space", "UPPER_CASE", "a/b") do
      assert(clue(ServerConfig.fromEnv(Map(
        "PDS_HOSTNAME" -> "pds.example.com",
        "PDS_RESERVED_HANDLES" -> value)).left.toOption).isDefined)
  }
