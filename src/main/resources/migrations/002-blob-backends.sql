CREATE TABLE blobs_next (
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  cid text NOT NULL,
  mime_type text NOT NULL,
  size bigint NOT NULL,
  storage_backend text NOT NULL DEFAULT 'database',
  content {{BLOB}},
  object_bucket text,
  object_key text,
  takedown_ref text,
  created_at bigint NOT NULL,
  CHECK (size >= 0),
  CHECK (
    (storage_backend = 'database' AND content IS NOT NULL
      AND object_bucket IS NULL AND object_key IS NULL)
    OR
    (storage_backend = 's3' AND content IS NULL
      AND object_bucket IS NOT NULL AND object_key IS NOT NULL)
  ),
  PRIMARY KEY (did, cid)
);

INSERT INTO blobs_next (did, cid, mime_type, size, storage_backend, content, takedown_ref, created_at)
  SELECT did, cid, mime_type, size, 'database', content, takedown_ref, created_at FROM blobs;

DROP TABLE blobs;

ALTER TABLE blobs_next RENAME TO blobs;

CREATE TABLE blob_deletions (
  object_bucket text NOT NULL,
  object_key text NOT NULL,
  attempts integer NOT NULL DEFAULT 0,
  available_at bigint NOT NULL,
  last_error text,
  created_at bigint NOT NULL,
  PRIMARY KEY (object_bucket, object_key)
);
