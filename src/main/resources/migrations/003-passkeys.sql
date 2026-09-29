CREATE TABLE account_webauthn_users (
  did text PRIMARY KEY REFERENCES accounts(did) ON DELETE CASCADE,
  user_handle {{BLOB}} NOT NULL UNIQUE
);

CREATE TABLE account_passkeys (
  credential_id text PRIMARY KEY,
  did text NOT NULL REFERENCES account_webauthn_users(did) ON DELETE CASCADE,
  label text NOT NULL,
  public_key_cose {{BLOB}} NOT NULL,
  signature_count bigint NOT NULL DEFAULT 0,
  backup_eligible boolean NOT NULL DEFAULT false,
  backed_up boolean NOT NULL DEFAULT false,
  transports {{JSON}} NOT NULL DEFAULT '[]',
  created_at bigint NOT NULL,
  last_used_at bigint,
  CHECK (length(label) >= 1 AND length(label) <= 64),
  CHECK (signature_count >= 0)
);
CREATE INDEX account_passkeys_did ON account_passkeys(did);

CREATE TABLE webauthn_requests (
  id text PRIMARY KEY,
  did text REFERENCES accounts(did) ON DELETE CASCADE,
  purpose text NOT NULL,
  session_hash text NOT NULL,
  request {{JSON}} NOT NULL,
  label text,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL,
  CHECK (purpose IN ('register', 'authenticate'))
);
