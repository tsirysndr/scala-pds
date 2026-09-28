package pds.oauth

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString
import pds.TestEnv
import pds.TestEnv.*
import pds.crypto.{Curve, Encoding, Hash, Jwt, PrivateKey}

class OauthFlowSuite extends munit.CatsEffectSuite:
  private val clientId = "https://app.example.com/client-metadata.json"
  private val redirect = "https://app.example.com/callback"
  private val origin = "https://pds.example.com"

  private val metadata = Json.obj(
    "client_id" -> Json.fromString(clientId),
    "client_name" -> Json.fromString("Example client"),
    "redirect_uris" -> Json.arr(Json.fromString(redirect)),
    "grant_types" -> Json.arr(
      Json.fromString("authorization_code"), Json.fromString("refresh_token")),
    "response_types" -> Json.arr(Json.fromString("code")),
    "scope" -> Json.fromString("atproto transition:generic"),
    "token_endpoint_auth_method" -> Json.fromString("none"),
    "application_type" -> Json.fromString("web"),
    "dpop_bound_access_tokens" -> Json.True
  )

  private def upstream(document: Json = metadata): PartialFunction[Request[IO], IO[Response[IO]]] =
    case request if request.uri.renderString == clientId =>
      IO.pure(Response[IO](Status.Ok).withEntity(document))

  private val key = PrivateKey.generate(Curve.P256)

  private def proof(
      method: String, url: String, nonce: Option[String], accessToken: Option[String], now: Long
  ): String =
    val header = Json.obj(
      "typ" -> Json.fromString("dpop+jwt"),
      "alg" -> Json.fromString("ES256"),
      "jwk" -> key.publicKey.jwk
    )
    val payload = Json.obj(
      "jti" -> Json.fromString(Hash.token()),
      "htm" -> Json.fromString(method),
      "htu" -> Json.fromString(url),
      "iat" -> Json.fromLong(now / 1000),
      "nonce" -> nonce.map(Json.fromString).getOrElse(Json.Null),
      "ath" -> accessToken.map(token =>
        Json.fromString(Encoding.b64(Hash.sha256(token)))).getOrElse(Json.Null)
    ).deepDropNullValues
    Jwt.signEs(key, payload, header)

  private def form(path: String, values: Map[String, String], dpop: String): Request[IO] =
    Request[IO](Method.POST, Uri.unsafeFromString(path))
      .withEntity(UrlForm(values.toSeq*))
      .putHeaders(Header.Raw(CIString("DPoP"), dpop))

  private def nonceOf(response: Response[IO]): Option[String] =
    response.headers.get(CIString("DPoP-Nonce")).map(_.head.value)

  private def sameOrigin(request: Request[IO]): Request[IO] =
    request.putHeaders(Header.Raw(CIString("Origin"), origin),
      Header.Raw(CIString("Sec-Fetch-Site"), "same-origin"))

  private def cookieOf(response: Response[IO], name: String): Option[String] =
    response.headers.get(CIString("Set-Cookie")).toVector.flatMap(_.toList)
      .map(_.value).find(_.startsWith(s"$name="))
      .map(_.drop(name.length + 1).takeWhile(_ != ';'))

  /** Appends to any existing cookie header rather than replacing it. */
  private def withCookie(request: Request[IO], name: String, value: String): Request[IO] =
    val existing = request.headers.get(CIString("Cookie")).map(_.head.value)
    request.putHeaders(Header.Raw(CIString("Cookie"),
      existing.fold(s"$name=$value")(current => s"$current; $name=$value")))

  private val verifier = Hash.token() + Hash.token().take(10)
  private val challenge = Encoding.b64(Hash.sha256(verifier))

  private def parRequest(server: Harness, now: Long, nonce: Option[String]): IO[Response[IO]] =
    server.run(form("/oauth/par", Map(
      "client_id" -> clientId,
      "response_type" -> "code",
      "redirect_uri" -> redirect,
      "scope" -> "atproto transition:generic",
      "state" -> "a-client-state",
      "code_challenge" -> challenge,
      "code_challenge_method" -> "S256"
    ), proof("POST", s"$origin/oauth/par", nonce, None, now)))

  test("discovery documents describe the authorization server") {
    harness(client = routes(upstream())).use { server =>
      for
        authorization <- server.json(get("/.well-known/oauth-authorization-server"))
        resource <- server.json(get("/.well-known/oauth-protected-resource"))
      yield
        assertEquals(authorization._2.hcursor.get[String]("issuer"), Right(origin))
        assertEquals(authorization._2.hcursor.get[Boolean]("require_pushed_authorization_requests"),
          Right(true))
        assertEquals(authorization._2.hcursor.get[Vector[String]]("code_challenge_methods_supported"),
          Right(Vector("S256")))
        assertEquals(authorization._2.hcursor.get[Vector[String]]("dpop_signing_alg_values_supported"),
          Right(Vector("ES256")))
        assertEquals(resource._2.hcursor.get[String]("resource"), Right(origin))
    }
  }

  test("pushed authorization requires a fresh DPoP nonce") {
    harness(client = routes(upstream())).use { server =>
      for
        now <- server.env.now
        without <- parRequest(server, now, None)
        body <- without.as[Json]
        retried <- parRequest(server, now, nonceOf(without))
        pushed <- retried.as[Json]
      yield
        assertEquals(without.status, Status.Unauthorized)
        assertEquals(body.hcursor.get[String]("error"), Right("use_dpop_nonce"))
        assert(nonceOf(without).isDefined)
        assertEquals(retried.status, Status.Created)
        assert(pushed.hcursor.get[String]("request_uri").toOption.get
          .startsWith("urn:ietf:params:oauth:request_uri:"))
    }
  }

  test("pushed authorization validates the client, redirect and scope") {
    harness(client = routes(upstream())).use { server =>
      for
        now <- server.env.now
        seed <- parRequest(server, now, None)
        nonce = nonceOf(seed)
        badRedirect <- server.run(form("/oauth/par", Map(
          "client_id" -> clientId, "response_type" -> "code",
          "redirect_uri" -> "https://evil.example.com/callback",
          "scope" -> "atproto", "state" -> "a-client-state",
          "code_challenge" -> challenge, "code_challenge_method" -> "S256"
        ), proof("POST", s"$origin/oauth/par", nonce, None, now)))
        badScope <- server.run(form("/oauth/par", Map(
          "client_id" -> clientId, "response_type" -> "code", "redirect_uri" -> redirect,
          "scope" -> "transition:generic", "state" -> "a-client-state",
          "code_challenge" -> challenge, "code_challenge_method" -> "S256"
        ), proof("POST", s"$origin/oauth/par", nonce, None, now)))
        plainPkce <- server.run(form("/oauth/par", Map(
          "client_id" -> clientId, "response_type" -> "code", "redirect_uri" -> redirect,
          "scope" -> "atproto", "state" -> "a-client-state",
          "code_challenge" -> challenge, "code_challenge_method" -> "plain"
        ), proof("POST", s"$origin/oauth/par", nonce, None, now)))
        unknownClient <- server.run(form("/oauth/par", Map(
          "client_id" -> "https://unknown.example.com/client.json", "response_type" -> "code",
          "redirect_uri" -> redirect, "scope" -> "atproto", "state" -> "a-client-state",
          "code_challenge" -> challenge, "code_challenge_method" -> "S256"
        ), proof("POST", s"$origin/oauth/par", nonce, None, now)))
      yield
        assertEquals(badRedirect.status, Status.BadRequest)
        assertEquals(badScope.status, Status.BadRequest)
        assertEquals(plainPkce.status, Status.BadRequest)
        assertEquals(unknownClient.status, Status.BadRequest)
    }
  }

  test("clients that do not bind tokens to DPoP are refused") {
    harness(client = routes(upstream(
      metadata.deepMerge(Json.obj("dpop_bound_access_tokens" -> Json.False))))).use { server =>
      for
        now <- server.env.now
        seed <- parRequest(server, now, None)
        response <- parRequest(server, now, nonceOf(seed))
        body <- response.as[Json]
      yield
        assertEquals(response.status, Status.BadRequest)
        assertEquals(body.hcursor.get[String]("error"), Right("invalid_client"))
    }
  }

  private def signIn(server: Harness): IO[(String, String)] =
    for
      _ <- server.json(post("/xrpc/com.atproto.server.createAccount", Json.obj(
        "handle" -> Json.fromString("alice.pds.example.com"),
        "email" -> Json.fromString("alice@example.com"),
        "password" -> Json.fromString("correct horse battery"))))
      opened <- server.run(get("/account/session"))
      cookie = cookieOf(opened, "__Host-pds-security").get
      view <- opened.as[Json]
      csrf = view.hcursor.get[String]("csrf").toOption.get
      loggedIn <- server.run(withCookie(sameOrigin(
        post("/account/action/login/password", Json.obj(
          "identifier" -> Json.fromString("alice.pds.example.com"),
          "password" -> Json.fromString("correct horse battery")))
          .putHeaders(Header.Raw(CIString("X-CSRF-Token"), csrf))), "__Host-pds-security", cookie))
      next = cookieOf(loggedIn, "__Host-pds-security").get
      session <- loggedIn.as[Json]
    yield (next, session.hcursor.get[String]("csrf").toOption.get)

  test("the full authorization code flow issues a DPoP-bound access token") {
    harness(client = routes(upstream())).use { server =>
      for
        now <- server.env.now
        seed <- parRequest(server, now, None)
        pushed <- parRequest(server, now, nonceOf(seed))
        requestUri <- pushed.as[Json].map(_.hcursor.get[String]("request_uri").toOption.get)
        authorize <- server.run(get(
          s"/oauth/authorize?client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}" +
            s"&request_uri=${java.net.URLEncoder.encode(requestUri, "UTF-8")}"))
        flowId = authorize.headers.get(CIString("Location")).map(_.head.value).get
          .stripPrefix("/oauth/flow/")
        flowCookie = cookieOf(authorize, "__Host-pds-oauth").get
        owner <- signIn(server)
        (accountCookie, accountCsrf) = owner
        state <- server.run(withCookie(get(s"/oauth/flow/$flowId/state"), "__Host-pds-oauth", flowCookie))
        flowState <- state.as[Json]
        flowCsrf = flowState.hcursor.get[String]("csrf").toOption.get
        attached <- server.run(
          withCookie(withCookie(sameOrigin(post(s"/oauth/flow/$flowId/attach",
            Json.obj("accountCsrf" -> Json.fromString(accountCsrf)))
            .putHeaders(Header.Raw(CIString("X-CSRF-Token"), flowCsrf))),
            "__Host-pds-oauth", flowCookie), "__Host-pds-security", accountCookie))
        attachedState <- attached.as[Json]
        decided <- server.run(
          withCookie(sameOrigin(post(s"/oauth/flow/$flowId/decide", Json.obj("approve" -> Json.True))
            .putHeaders(Header.Raw(CIString("X-CSRF-Token"), flowCsrf))),
            "__Host-pds-oauth", flowCookie))
        decision <- decided.as[Json]
        location = decision.hcursor.get[String]("location").toOption.get
        code = location.split("[?&]").find(_.startsWith("code=")).get.drop(5)
        tokenSeed <- server.run(form("/oauth/token", Map("client_id" -> clientId),
          proof("POST", s"$origin/oauth/token", None, None, now)))
        tokens <- server.run(form("/oauth/token", Map(
          "grant_type" -> "authorization_code",
          "client_id" -> clientId,
          "code" -> code,
          "code_verifier" -> verifier,
          "redirect_uri" -> redirect
        ), proof("POST", s"$origin/oauth/token", nonceOf(tokenSeed), None, now)))
        issued <- tokens.as[Json]
        access = issued.hcursor.get[String]("access_token").toOption.get
        resource <- server.json(get("/xrpc/com.atproto.server.getSession")
          .putHeaders(
            Header.Raw(CIString("Authorization"), s"DPoP $access"),
            Header.Raw(CIString("DPoP"),
              proof("GET", s"$origin/xrpc/com.atproto.server.getSession", None, Some(access), now))))
        withoutProof <- server.json(get("/xrpc/com.atproto.server.getSession")
          .putHeaders(Header.Raw(CIString("Authorization"), s"DPoP $access")))
        refreshed <- server.run(form("/oauth/token", Map(
          "grant_type" -> "refresh_token",
          "client_id" -> clientId,
          "refresh_token" -> issued.hcursor.get[String]("refresh_token").toOption.get
        ), proof("POST", s"$origin/oauth/token", nonceOf(tokens), None, now)))
        rotated <- refreshed.as[Json]
        replay <- server.run(form("/oauth/token", Map(
          "grant_type" -> "authorization_code",
          "client_id" -> clientId,
          "code" -> code,
          "code_verifier" -> verifier,
          "redirect_uri" -> redirect
        ), proof("POST", s"$origin/oauth/token", nonceOf(refreshed), None, now)))
      yield
        assertEquals(authorize.status, Status.SeeOther)
        assertEquals(flowState.hcursor.get[String]("client-id"), Right(clientId))
        assertEquals(flowState.hcursor.get[Json]("did"), Right(Json.Null))
        assert(flowState.hcursor.get[Vector[String]]("permissions").toOption.get.nonEmpty)
        assertEquals(attached.status, Status.Ok)
        assert(attachedState.hcursor.get[String]("did").toOption.get.startsWith("did:web:"))
        assertEquals(decided.status, Status.Ok)
        assert(location.startsWith(redirect), location)
        assert(location.contains("state=a-client-state"), location)
        assert(location.contains(s"iss=${java.net.URLEncoder.encode(origin, "UTF-8")}"), location)
        assertEquals(tokens.status, Status.Ok)
        assertEquals(issued.hcursor.get[String]("token_type"), Right("DPoP"))
        assertEquals(issued.hcursor.get[String]("scope"), Right("atproto transition:generic"))
        assertEquals(resource._1, Status.Ok)
        assertEquals(resource._2.hcursor.get[String]("handle"), Right("alice.pds.example.com"))
        assertEquals(withoutProof._1, Status.Unauthorized)
        assertEquals(refreshed.status, Status.Ok)
        assertNotEquals(rotated.hcursor.get[String]("access_token").toOption, Some(access))
        assertEquals(replay.status, Status.BadRequest)
    }
  }

  test("a denied authorization redirects with access_denied and issues no code") {
    harness(client = routes(upstream())).use { server =>
      for
        now <- server.env.now
        seed <- parRequest(server, now, None)
        pushed <- parRequest(server, now, nonceOf(seed))
        requestUri <- pushed.as[Json].map(_.hcursor.get[String]("request_uri").toOption.get)
        authorize <- server.run(get(
          s"/oauth/authorize?client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}" +
            s"&request_uri=${java.net.URLEncoder.encode(requestUri, "UTF-8")}"))
        flowId = authorize.headers.get(CIString("Location")).map(_.head.value).get
          .stripPrefix("/oauth/flow/")
        flowCookie = cookieOf(authorize, "__Host-pds-oauth").get
        owner <- signIn(server)
        (accountCookie, accountCsrf) = owner
        state <- server.run(withCookie(get(s"/oauth/flow/$flowId/state"), "__Host-pds-oauth", flowCookie))
        flowCsrf <- state.as[Json].map(_.hcursor.get[String]("csrf").toOption.get)
        _ <- server.run(withCookie(withCookie(sameOrigin(post(s"/oauth/flow/$flowId/attach",
          Json.obj("accountCsrf" -> Json.fromString(accountCsrf)))
          .putHeaders(Header.Raw(CIString("X-CSRF-Token"), flowCsrf))),
          "__Host-pds-oauth", flowCookie), "__Host-pds-security", accountCookie))
        denied <- server.run(withCookie(sameOrigin(
          post(s"/oauth/flow/$flowId/decide", Json.obj("approve" -> Json.False))
            .putHeaders(Header.Raw(CIString("X-CSRF-Token"), flowCsrf))),
          "__Host-pds-oauth", flowCookie))
        decision <- denied.as[Json]
        again <- server.run(withCookie(sameOrigin(
          post(s"/oauth/flow/$flowId/decide", Json.obj("approve" -> Json.True))
            .putHeaders(Header.Raw(CIString("X-CSRF-Token"), flowCsrf))),
          "__Host-pds-oauth", flowCookie))
      yield
        assertEquals(denied.status, Status.Ok)
        val location = decision.hcursor.get[String]("location").toOption.get
        assert(location.contains("error=access_denied"), location)
        assert(!location.contains("code="), location)
        assertEquals(again.status, Status.BadRequest)
    }
  }

  test("flow endpoints require the browser cookie and CSRF token") {
    harness(client = routes(upstream())).use { server =>
      for
        now <- server.env.now
        seed <- parRequest(server, now, None)
        pushed <- parRequest(server, now, nonceOf(seed))
        requestUri <- pushed.as[Json].map(_.hcursor.get[String]("request_uri").toOption.get)
        authorize <- server.run(get(
          s"/oauth/authorize?client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}" +
            s"&request_uri=${java.net.URLEncoder.encode(requestUri, "UTF-8")}"))
        flowId = authorize.headers.get(CIString("Location")).map(_.head.value).get
          .stripPrefix("/oauth/flow/")
        flowCookie = cookieOf(authorize, "__Host-pds-oauth").get
        noCookie <- server.run(get(s"/oauth/flow/$flowId/state"))
        wrongCookie <- server.run(withCookie(get(s"/oauth/flow/$flowId/state"),
          "__Host-pds-oauth", Hash.token()))
        crossOrigin <- server.run(withCookie(post(s"/oauth/flow/$flowId/decide",
          Json.obj("approve" -> Json.True))
          .putHeaders(Header.Raw(CIString("Origin"), "https://evil.example.com")),
          "__Host-pds-oauth", flowCookie))
        noCsrf <- server.run(withCookie(sameOrigin(post(s"/oauth/flow/$flowId/decide",
          Json.obj("approve" -> Json.True))), "__Host-pds-oauth", flowCookie))
      yield
        assertEquals(noCookie.status, Status.BadRequest)
        assertEquals(wrongCookie.status, Status.BadRequest)
        assertEquals(crossOrigin.status, Status.Forbidden)
        assertEquals(noCsrf.status, Status.Forbidden)
    }
  }

  test("a reused request_uri cannot start a second authorization") {
    harness(client = routes(upstream())).use { server =>
      for
        now <- server.env.now
        seed <- parRequest(server, now, None)
        pushed <- parRequest(server, now, nonceOf(seed))
        requestUri <- pushed.as[Json].map(_.hcursor.get[String]("request_uri").toOption.get)
        url = s"/oauth/authorize?client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}" +
          s"&request_uri=${java.net.URLEncoder.encode(requestUri, "UTF-8")}"
        first <- server.run(get(url))
        second <- server.run(get(url))
        withoutPar <- server.run(get(
          s"/oauth/authorize?client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}" +
            "&scope=atproto"))
      yield
        assertEquals(first.status, Status.SeeOther)
        assertEquals(second.status, Status.BadRequest)
        assertEquals(withoutPar.status, Status.BadRequest)
    }
  }

  test("DPoP proofs are bound to the method, URL, token and a single use") {
    harness(client = routes(upstream())).use { server =>
      for
        now <- server.env.now
        seed <- parRequest(server, now, None)
        nonce = nonceOf(seed)
        wrongMethod <- server.run(form("/oauth/par", Map(
          "client_id" -> clientId, "response_type" -> "code", "redirect_uri" -> redirect,
          "scope" -> "atproto", "state" -> "a-client-state",
          "code_challenge" -> challenge, "code_challenge_method" -> "S256"
        ), proof("GET", s"$origin/oauth/par", nonce, None, now)))
        wrongUrl <- server.run(form("/oauth/par", Map(
          "client_id" -> clientId, "response_type" -> "code", "redirect_uri" -> redirect,
          "scope" -> "atproto", "state" -> "a-client-state",
          "code_challenge" -> challenge, "code_challenge_method" -> "S256"
        ), proof("POST", s"$origin/oauth/token", nonce, None, now)))
        stale <- server.run(form("/oauth/par", Map(
          "client_id" -> clientId, "response_type" -> "code", "redirect_uri" -> redirect,
          "scope" -> "atproto", "state" -> "a-client-state",
          "code_challenge" -> challenge, "code_challenge_method" -> "S256"
        ), proof("POST", s"$origin/oauth/par", nonce, None, now - 600_000)))
        single = proof("POST", s"$origin/oauth/par", nonce, None, now)
        values = Map(
          "client_id" -> clientId, "response_type" -> "code", "redirect_uri" -> redirect,
          "scope" -> "atproto", "state" -> "a-client-state",
          "code_challenge" -> challenge, "code_challenge_method" -> "S256")
        firstUse <- server.run(form("/oauth/par", values, single))
        replay <- server.run(form("/oauth/par", values, single))
      yield
        assertEquals(wrongMethod.status, Status.Unauthorized)
        assertEquals(wrongUrl.status, Status.Unauthorized)
        assertEquals(stale.status, Status.Unauthorized)
        assertEquals(firstUse.status, Status.Created)
        assertEquals(replay.status, Status.Unauthorized)
    }
  }

  test("revocation ends an issued session") {
    harness(client = routes(upstream())).use { server =>
      for
        now <- server.env.now
        seed <- parRequest(server, now, None)
        pushed <- parRequest(server, now, nonceOf(seed))
        requestUri <- pushed.as[Json].map(_.hcursor.get[String]("request_uri").toOption.get)
        authorize <- server.run(get(
          s"/oauth/authorize?client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}" +
            s"&request_uri=${java.net.URLEncoder.encode(requestUri, "UTF-8")}"))
        flowId = authorize.headers.get(CIString("Location")).map(_.head.value).get
          .stripPrefix("/oauth/flow/")
        flowCookie = cookieOf(authorize, "__Host-pds-oauth").get
        owner <- signIn(server)
        (accountCookie, accountCsrf) = owner
        state <- server.run(withCookie(get(s"/oauth/flow/$flowId/state"),
          "__Host-pds-oauth", flowCookie))
        flowCsrf <- state.as[Json].map(_.hcursor.get[String]("csrf").toOption.get)
        _ <- server.run(withCookie(withCookie(sameOrigin(post(s"/oauth/flow/$flowId/attach",
          Json.obj("accountCsrf" -> Json.fromString(accountCsrf)))
          .putHeaders(Header.Raw(CIString("X-CSRF-Token"), flowCsrf))),
          "__Host-pds-oauth", flowCookie), "__Host-pds-security", accountCookie))
        decided <- server.run(withCookie(sameOrigin(
          post(s"/oauth/flow/$flowId/decide", Json.obj("approve" -> Json.True))
            .putHeaders(Header.Raw(CIString("X-CSRF-Token"), flowCsrf))),
          "__Host-pds-oauth", flowCookie))
        location <- decided.as[Json].map(_.hcursor.get[String]("location").toOption.get)
        code = location.split("[?&]").find(_.startsWith("code=")).get.drop(5)
        tokenSeed <- server.run(form("/oauth/token", Map("client_id" -> clientId),
          proof("POST", s"$origin/oauth/token", None, None, now)))
        tokens <- server.run(form("/oauth/token", Map(
          "grant_type" -> "authorization_code", "client_id" -> clientId, "code" -> code,
          "code_verifier" -> verifier, "redirect_uri" -> redirect
        ), proof("POST", s"$origin/oauth/token", nonceOf(tokenSeed), None, now)))
        issued <- tokens.as[Json]
        access = issued.hcursor.get[String]("access_token").toOption.get
        before <- server.json(get("/xrpc/com.atproto.server.getSession").putHeaders(
          Header.Raw(CIString("Authorization"), s"DPoP $access"),
          Header.Raw(CIString("DPoP"),
            proof("GET", s"$origin/xrpc/com.atproto.server.getSession", None, Some(access), now))))
        _ <- server.run(Request[IO](Method.POST, Uri.unsafeFromString("/oauth/revoke"))
          .withEntity(UrlForm("token" -> access)))
        after <- server.json(get("/xrpc/com.atproto.server.getSession").putHeaders(
          Header.Raw(CIString("Authorization"), s"DPoP $access"),
          Header.Raw(CIString("DPoP"),
            proof("GET", s"$origin/xrpc/com.atproto.server.getSession", None, Some(access), now))))
      yield
        assertEquals(before._1, Status.Ok)
        assertEquals(after._1, Status.Unauthorized)
    }
  }
