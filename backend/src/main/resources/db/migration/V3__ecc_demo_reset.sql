-- User-approved reset: V2 cards cannot be migrated to the two-secret/PIN KDF.
-- Preserve admins, menu, configuration and sequences; never reuse bank references.
DELETE FROM card_operations;
DELETE FROM card_cipher_snapshots;
DELETE FROM card_wallets;
DELETE FROM wallet_operations;
DELETE FROM bank_events WHERE topup_id IS NOT NULL;
DELETE FROM topups;
DELETE FROM invoice_lines;
DELETE FROM invoices;
DELETE FROM audit_log WHERE card_id IS NOT NULL;
DELETE FROM cards;

ALTER TABLE cards RENAME COLUMN profile_enc TO profile_json;
ALTER TABLE cards RENAME COLUMN photo_enc TO photo_base64;
ALTER TABLE cards RENAME COLUMN pending_enc TO pending_json;
ALTER TABLE cards RENAME COLUMN citizen_token TO citizen_id;
ALTER TABLE cards ALTER COLUMN citizen_id TYPE VARCHAR(12);
ALTER TABLE cards DROP COLUMN pending_hash;
ALTER TABLE cards ALTER COLUMN sync_status SET DEFAULT 'SYNCED';
ALTER TABLE cards ADD COLUMN lifecycle_status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK(lifecycle_status IN ('ACTIVE','BLOCKED'));
ALTER TABLE cards ADD COLUMN pin_failures INT NOT NULL DEFAULT 0 CHECK(pin_failures BETWEEN 0 AND 5);
ALTER TABLE cards ADD COLUMN block_reason VARCHAR(20) CHECK(block_reason IN ('ADMIN','PIN_FAILURES'));
ALTER TABLE cards ADD COLUMN blocked_at TIMESTAMPTZ;
ALTER TABLE cards ADD CONSTRAINT block_consistency CHECK ((lifecycle_status='ACTIVE' AND block_reason IS NULL AND blocked_at IS NULL) OR (lifecycle_status='BLOCKED' AND block_reason IS NOT NULL AND blocked_at IS NOT NULL));
CREATE TABLE card_secrets(card_id VARCHAR(16) PRIMARY KEY REFERENCES cards,backend_share BYTEA NOT NULL UNIQUE CHECK(octet_length(backend_share)=32));
CREATE TABLE pin_attempts(attempt_id UUID PRIMARY KEY,card_id VARCHAR(16) NOT NULL REFERENCES cards,connection_id UUID NOT NULL,terminal_id VARCHAR(36) NOT NULL,purpose VARCHAR(20) NOT NULL,result VARCHAR(12) NOT NULL CHECK(result IN ('STARTED','OK','WRONG','UNKNOWN')),applied BOOLEAN NOT NULL DEFAULT false,created_at TIMESTAMPTZ NOT NULL DEFAULT now());
ALTER TABLE card_operations RENAME COLUMN profile_pending_enc TO profile_pending_json;
-- Random operation token rather than a PIN-derived digest (prevents a PIN oracle).
ALTER TABLE card_operations RENAME COLUMN request_hash TO operation_token;
ALTER TABLE card_cipher_snapshots RENAME COLUMN card_attestation TO card_state;
ALTER TABLE invoices RENAME COLUMN name_enc TO full_name;
ALTER TABLE invoices ADD COLUMN expires_at TIMESTAMPTZ NOT NULL DEFAULT (now()+interval '5 minutes');
ALTER TABLE invoices ADD COLUMN cancel_reason VARCHAR(30);
ALTER TABLE invoices ADD COLUMN terminal_id VARCHAR(36);
CREATE INDEX invoice_expiry ON invoices(expires_at) WHERE status='OPEN';

ALTER TABLE bank_events ADD COLUMN card_id VARCHAR(16) REFERENCES cards;
CREATE INDEX bank_events_card ON bank_events(card_id);
