CREATE TABLE admin_setup_codes (
  id SMALLINT PRIMARY KEY CHECK (id = 1),
  request_id UUID NOT NULL,
  username VARCHAR(60) NOT NULL,
  email VARCHAR(180) NOT NULL,
  code_hash CHAR(64) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  sent_at TIMESTAMPTZ NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5)
);
