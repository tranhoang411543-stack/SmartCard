-- Stable student memos are distinct from the unique internal top-up reference.
-- These minimal financial mappings deliberately survive hard deletion of a card.
CREATE TABLE sepay_payment_codes (
 payment_code VARCHAR(9) PRIMARY KEY CHECK(payment_code ~ '^TTM[0-9]{6}$'),
 original_card_id VARCHAR(16) NOT NULL,
 student_code VARCHAR(8) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE sepay_topup_requests (
 reference VARCHAR(25) PRIMARY KEY,
 payment_code VARCHAR(9) NOT NULL REFERENCES sepay_payment_codes,
 original_card_id VARCHAR(16) NOT NULL,
 amount BIGINT NOT NULL CHECK(amount>0),
 opened_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 expires_at TIMESTAMPTZ NOT NULL,
 CHECK(expires_at>opened_at)
);
CREATE INDEX sepay_request_window ON sepay_topup_requests(payment_code,opened_at DESC);
ALTER TABLE bank_events ADD COLUMN payment_code VARCHAR(9);
ALTER TABLE bank_events ADD COLUMN bank_transaction_at TIMESTAMPTZ;
