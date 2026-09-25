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
