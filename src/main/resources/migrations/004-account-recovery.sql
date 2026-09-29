CREATE TABLE account_recoveries (
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  previous_epoch bigint NOT NULL,
  security_epoch bigint NOT NULL,
  removed {{JSON}} NOT NULL,
  reference text NOT NULL,
  completed_at bigint NOT NULL,
  CHECK (security_epoch > previous_epoch),
  CHECK (length(reference) >= 1 AND length(reference) <= 128),
  PRIMARY KEY (did, previous_epoch)
);
