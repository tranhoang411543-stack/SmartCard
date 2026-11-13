ALTER TABLE cards ADD COLUMN sync_status VARCHAR(24) NOT NULL DEFAULT 'MIGRATION_REQUIRED';
CREATE TABLE card_wallets (
 card_id VARCHAR(16) PRIMARY KEY REFERENCES cards,
 balance BIGINT NOT NULL CHECK(balance BETWEEN 0 AND 2147483647),
 wallet_version BIGINT NOT NULL CHECK(wallet_version>=0),
 state_counter BIGINT NOT NULL CHECK(state_counter>=0),
 last_operation_id BIGINT NOT NULL DEFAULT 0,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE card_cipher_snapshots (
 card_id VARCHAR(16) PRIMARY KEY REFERENCES cards,
 profile_version BIGINT NOT NULL, crypto_version BIGINT NOT NULL,
 cipher_hash CHAR(64) NOT NULL, cipher_blob BYTEA NOT NULL,
 card_attestation BYTEA NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE card_operations (
 id BIGSERIAL PRIMARY KEY, card_id VARCHAR(16) NOT NULL REFERENCES cards,
 kind INT NOT NULL CHECK(kind IN (1,2,3,4,6,7,8)),
 phase VARCHAR(24) NOT NULL CHECK(phase IN ('CREATED','CARD_PREPARED','COMMIT_DECIDED','CARD_COMMITTED','DB_COMMITTED','SYNCED','ABORTED','CONFLICT')),
 before_counter BIGINT NOT NULL, before_balance BIGINT NOT NULL,
 before_profile BIGINT NOT NULL, before_crypto BIGINT NOT NULL, before_wallet BIGINT NOT NULL,
 next_profile BIGINT NOT NULL, next_crypto BIGINT NOT NULL, next_wallet BIGINT NOT NULL,
 next_balance BIGINT NOT NULL, request_hash CHAR(64),
 candidate_blob BYTEA, candidate_hash CHAR(64), proof BYTEA,
 profile_pending_enc TEXT, actor_id BIGINT REFERENCES admins,
 wallet_operation_id BIGINT UNIQUE REFERENCES wallet_operations,
 last_error VARCHAR(40), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX one_active_card_operation ON card_operations(card_id)
 WHERE phase NOT IN ('SYNCED','ABORTED');
CREATE INDEX card_operations_recovery ON card_operations(phase,card_id);
