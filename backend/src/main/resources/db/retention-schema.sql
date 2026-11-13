-- Also installed by the pre-V3 retention gate. No FK to a live card.
CREATE TABLE IF NOT EXISTS refund_cases (
 id BIGSERIAL PRIMARY KEY, original_card_id VARCHAR(16) NOT NULL UNIQUE,
 student_code VARCHAR(8), full_name TEXT NOT NULL DEFAULT '', actor_id BIGINT,
 observed_balance BIGINT, observed_wallet_version BIGINT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS refund_items (
 id BIGSERIAL PRIMARY KEY, case_id BIGINT NOT NULL REFERENCES refund_cases,
 source_topup_id BIGINT UNIQUE, reference VARCHAR(150) NOT NULL UNIQUE,
 requested_amount BIGINT NOT NULL CHECK(requested_amount>=0),
 received_amount BIGINT NOT NULL CHECK(received_amount>=0),
 credited_amount BIGINT CHECK(credited_amount>=0),
 status VARCHAR(24) NOT NULL CHECK(status IN ('WAITING_BANK','REFUND_PENDING','REVIEW_REQUIRED','REFUNDED','CLOSED_NO_FUNDS')),
 evidence_json TEXT NOT NULL DEFAULT '[]', created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS retired_topup_references (
 reference VARCHAR(150) PRIMARY KEY, original_card_id VARCHAR(16) NOT NULL,
 student_code VARCHAR(8), requested_amount BIGINT NOT NULL,
 received_amount BIGINT NOT NULL, credited_amount BIGINT,
 refund_item_id BIGINT REFERENCES refund_items
);
ALTER TABLE bank_events ADD COLUMN IF NOT EXISTS refund_item_id BIGINT REFERENCES refund_items;
ALTER TABLE bank_events ADD COLUMN IF NOT EXISTS topup_reference VARCHAR(150);
CREATE TABLE IF NOT EXISTS retention_preflight (
 version INT PRIMARY KEY, archived_cards BIGINT NOT NULL, archived_items BIGINT NOT NULL, financial_fingerprint VARCHAR(64),
 completed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
