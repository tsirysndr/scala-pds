package pds.oauth

import io.circe.Json
import pds.ServerConfig

object Metadata:
  def authorizationServer(config: ServerConfig): Json =
    val url = config.publicUrl
    Json.obj(
      "issuer" -> Json.fromString(url),
      "authorization_endpoint" -> Json.fromString(s"$url/oauth/authorize"),
      "pushed_authorization_request_endpoint" -> Json.fromString(s"$url/oauth/par"),
      "token_endpoint" -> Json.fromString(s"$url/oauth/token"),
      "revocation_endpoint" -> Json.fromString(s"$url/oauth/revoke"),
      "response_types_supported" -> Json.arr(Json.fromString("code")),
      "response_modes_supported" -> Json.arr(Json.fromString("query"), Json.fromString("fragment")),
      "grant_types_supported" -> Json.arr(
        Json.fromString("authorization_code"), Json.fromString("refresh_token")),
      "code_challenge_methods_supported" -> Json.arr(Json.fromString("S256")),
      "scopes_supported" -> Json.arr(Scope.supported.toVector.sorted.map(Json.fromString)*),
      "token_endpoint_auth_methods_supported" -> Json.arr(
        Json.fromString("none"), Json.fromString("private_key_jwt")),
      "token_endpoint_auth_signing_alg_values_supported" -> Json.arr(Json.fromString("ES256")),
      "revocation_endpoint_auth_methods_supported" -> Json.arr(
        Json.fromString("none"), Json.fromString("private_key_jwt")),
      "dpop_signing_alg_values_supported" -> Json.arr(Json.fromString("ES256")),
      "authorization_response_iss_parameter_supported" -> Json.True,
      "require_pushed_authorization_requests" -> Json.True,
      "require_request_uri_registration" -> Json.True,
      "client_id_metadata_document_supported" -> Json.True
    )

  def protectedResource(config: ServerConfig): Json =
    Json.obj(
      "resource" -> Json.fromString(config.publicUrl),
      "authorization_servers" -> Json.arr(Json.fromString(config.publicUrl)),
      "scopes_supported" -> Json.arr(Scope.supported.toVector.sorted.map(Json.fromString)*),
      "bearer_methods_supported" -> Json.arr(Json.fromString("header")),
      "dpop_signing_alg_values_supported" -> Json.arr(Json.fromString("ES256"))
    )
