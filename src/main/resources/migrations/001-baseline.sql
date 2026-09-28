CREATE TABLE accounts (
  did text PRIMARY KEY,
  handle text NOT NULL UNIQUE,
  email text UNIQUE,
  password_hash text,
  email_confirmed boolean NOT NULL DEFAULT false,
  email_auth_factor boolean NOT NULL DEFAULT false,
  status text NOT NULL DEFAULT 'active',
  status_before_takedown text NOT NULL DEFAULT 'active',
  takedown_ref text,
  deactivated_at bigint,
  delete_after bigint,
  invites_disabled boolean NOT NULL DEFAULT false,
  invite_note text,
  imported boolean NOT NULL DEFAULT false,
  security_epoch bigint NOT NULL DEFAULT 0,
  created_at bigint NOT NULL,
  CHECK (status IN ('active', 'deactivated', 'taken_down', 'deleted', 'provisioning'))
);

CREATE TABLE account_keys (
  did text PRIMARY KEY REFERENCES accounts(did) ON DELETE CASCADE,
  signing_curve text NOT NULL,
  signing_public text NOT NULL,
  signing_sealed {{BLOB}} NOT NULL,
  rotation_public text,
  rotation_sealed {{BLOB}},
  plc_confirmed boolean NOT NULL DEFAULT false,
  created_at bigint NOT NULL
);

CREATE TABLE handle_reservations (
  handle text PRIMARY KEY,
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  created_at bigint NOT NULL
);

CREATE TABLE sessions (
  id text PRIMARY KEY,
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  refresh_hash text NOT NULL UNIQUE,
  app_password_id text,
  privileged boolean NOT NULL DEFAULT false,
  revoked boolean NOT NULL DEFAULT false,
  security_epoch bigint NOT NULL DEFAULT 0,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL
);
CREATE INDEX sessions_did ON sessions(did);

CREATE TABLE app_passwords (
  id text PRIMARY KEY,
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  name text NOT NULL,
  password_digest text NOT NULL,
  privileged boolean NOT NULL DEFAULT false,
  created_at bigint NOT NULL,
  UNIQUE (did, name)
);

CREATE TABLE account_tokens (
  token_hash text PRIMARY KEY,
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  purpose text NOT NULL,
  email text NOT NULL,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL,
  CHECK (purpose IN ('confirm-email', 'reset-password', 'delete-account',
                     'update-email', 'sign-in', 'plc-operation'))
);

CREATE TABLE invite_codes (
  code text PRIMARY KEY,
  available integer NOT NULL,
  disabled boolean NOT NULL DEFAULT false,
  for_account text NOT NULL,
  created_by text NOT NULL DEFAULT 'admin',
  created_at bigint NOT NULL,
  CHECK (available > 0)
);

CREATE TABLE invite_uses (
  code text NOT NULL REFERENCES invite_codes(code) ON DELETE CASCADE,
  used_by text NOT NULL UNIQUE,
  used_at bigint NOT NULL,
  PRIMARY KEY (code, used_by)
);

CREATE TABLE repo_roots (
  did text PRIMARY KEY REFERENCES accounts(did) ON DELETE CASCADE,
  commit_cid text NOT NULL,
  data_cid text NOT NULL,
  rev text NOT NULL
);

CREATE TABLE repo_blocks (
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  cid text NOT NULL,
  content {{BLOB}} NOT NULL,
  rev text NOT NULL,
  PRIMARY KEY (did, cid)
);
CREATE INDEX repo_blocks_rev ON repo_blocks(did, rev);

CREATE TABLE records (
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  collection text NOT NULL,
  rkey text NOT NULL,
  cid text NOT NULL,
  rev text NOT NULL,
  takedown_ref text,
  indexed_at bigint NOT NULL,
  PRIMARY KEY (did, collection, rkey)
);
CREATE INDEX records_collection ON records(collection, did);

CREATE TABLE record_blobs (
  did text NOT NULL,
  collection text NOT NULL,
  rkey text NOT NULL,
  cid text NOT NULL,
  PRIMARY KEY (did, collection, rkey, cid)
);
CREATE INDEX record_blobs_cid ON record_blobs(did, cid);

CREATE TABLE blobs (
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  cid text NOT NULL,
  mime_type text NOT NULL,
  size bigint NOT NULL,
  content {{BLOB}} NOT NULL,
  takedown_ref text,
  created_at bigint NOT NULL,
  PRIMARY KEY (did, cid)
);

CREATE TABLE repo_events (
  seq {{ID_PK}},
  did text NOT NULL,
  kind text NOT NULL,
  rev text,
  payload {{BLOB}} NOT NULL,
  created_at bigint NOT NULL
);
CREATE INDEX repo_events_did ON repo_events(did, seq);

CREATE TABLE account_preferences (
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  name text NOT NULL,
  scope text NOT NULL,
  value {{JSON}} NOT NULL,
  PRIMARY KEY (did, name)
);

CREATE TABLE browser_sessions (
  token_hash text PRIMARY KEY,
  csrf_nonce text NOT NULL,
  did text REFERENCES accounts(did) ON DELETE CASCADE,
  security_epoch bigint,
  auth_method text,
  authenticated_at bigint,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL,
  CHECK ((did IS NULL) = (security_epoch IS NULL))
);

CREATE TABLE account_totp (
  did text PRIMARY KEY REFERENCES accounts(did) ON DELETE CASCADE,
  sealed_secret {{BLOB}} NOT NULL,
  confirmed boolean NOT NULL DEFAULT false,
  enrollment_expires_at bigint NOT NULL,
  last_step bigint,
  failed_attempts integer NOT NULL DEFAULT 0,
  attempt_window bigint NOT NULL,
  created_at bigint NOT NULL
);

CREATE TABLE account_recovery_codes (
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  code_hash text NOT NULL,
  PRIMARY KEY (did, code_hash)
);

CREATE TABLE oauth_requests (
  request_uri text PRIMARY KEY,
  client_id text NOT NULL,
  parameters {{JSON}} NOT NULL,
  dpop_jkt text,
  consumed boolean NOT NULL DEFAULT false,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL
);

CREATE TABLE oauth_interactions (
  id text PRIMARY KEY,
  browser_hash text NOT NULL,
  csrf_nonce text NOT NULL,
  request_uri text NOT NULL,
  client_id text NOT NULL,
  parameters {{JSON}} NOT NULL,
  did text,
  security_epoch bigint,
  authenticated_at bigint,
  decided boolean NOT NULL DEFAULT false,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL
);

CREATE TABLE oauth_codes (
  code_hash text PRIMARY KEY,
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  client_id text NOT NULL,
  scope text NOT NULL,
  redirect_uri text NOT NULL,
  code_challenge text NOT NULL,
  dpop_jkt text,
  security_epoch bigint NOT NULL,
  consumed boolean NOT NULL DEFAULT false,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL
);

CREATE TABLE oauth_tokens (
  id text PRIMARY KEY,
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  client_id text NOT NULL,
  scope text NOT NULL,
  access_hash text NOT NULL UNIQUE,
  refresh_hash text NOT NULL UNIQUE,
  dpop_jkt text NOT NULL,
  security_epoch bigint NOT NULL,
  revoked boolean NOT NULL DEFAULT false,
  created_at bigint NOT NULL,
  access_expires_at bigint NOT NULL,
  refresh_expires_at bigint NOT NULL
);
CREATE INDEX oauth_tokens_did ON oauth_tokens(did);

CREATE TABLE oauth_replay (
  jti text PRIMARY KEY,
  expires_at bigint NOT NULL
);

CREATE TABLE service_token_replay (
  jti text PRIMARY KEY,
  expires_at bigint NOT NULL
);

CREATE TABLE email_outbox (
  id text PRIMARY KEY,
  payload {{JSON}} NOT NULL,
  status text NOT NULL DEFAULT 'pending',
  attempts integer NOT NULL DEFAULT 0,
  available_at bigint NOT NULL,
  last_error text,
  created_at bigint NOT NULL,
  sent_at bigint,
  CHECK (status IN ('pending', 'sent', 'failed'))
);

CREATE TABLE plc_operations (
  did text PRIMARY KEY REFERENCES accounts(did) ON DELETE CASCADE,
  kind text NOT NULL,
  operation {{BLOB}} NOT NULL,
  operation_cid text NOT NULL,
  target_handle text,
  status text NOT NULL DEFAULT 'pending',
  attempts integer NOT NULL DEFAULT 0,
  available_at bigint NOT NULL,
  last_error text,
  created_at bigint NOT NULL,
  CHECK (status IN ('pending', 'submitted', 'failed'))
);

CREATE TABLE identity_cache (
  identifier text PRIMARY KEY,
  document {{JSON}} NOT NULL,
  fetched_at bigint NOT NULL,
  expires_at bigint NOT NULL
);

CREATE TABLE account_imports (
  did text PRIMARY KEY REFERENCES accounts(did) ON DELETE CASCADE,
  source_document {{JSON}} NOT NULL,
  repository_imported boolean NOT NULL DEFAULT false,
  created_at bigint NOT NULL
);
