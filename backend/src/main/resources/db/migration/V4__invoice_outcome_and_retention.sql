-- The same idempotent retention DDL is executed before the destructive V3.
CREATE TABLE IF NOT EXISTS refund_cases (
 id BIGSERIAL PRIMARY KEY, original_card_id VARCHAR(16) NOT NULL UNIQUE,
 student_code VARCHAR(8), full_name TEXT NOT NULL DEFAULT '', actor_id BIGINT,
 observed_balance BIGINT, observed_wallet_version BIGINT, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS refund_items (
 id BIGSERIAL PRIMARY KEY, case_id BIGINT NOT NULL REFERENCES refund_cases,
 source_topup_id BIGINT UNIQUE, reference VARCHAR(150) NOT NULL UNIQUE,
 requested_amount BIGINT NOT NULL CHECK(requested_amount>=0), received_amount BIGINT NOT NULL CHECK(received_amount>=0),
 credited_amount BIGINT CHECK(credited_amount>=0),
 status VARCHAR(24) NOT NULL CHECK(status IN ('WAITING_BANK','REFUND_PENDING','REVIEW_REQUIRED','REFUNDED','CLOSED_NO_FUNDS')),
 evidence_json TEXT NOT NULL DEFAULT '[]', created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS retired_topup_references (
 reference VARCHAR(150) PRIMARY KEY, original_card_id VARCHAR(16) NOT NULL, student_code VARCHAR(8),
 requested_amount BIGINT NOT NULL, received_amount BIGINT NOT NULL, credited_amount BIGINT,
 refund_item_id BIGINT REFERENCES refund_items
);
ALTER TABLE bank_events ADD COLUMN IF NOT EXISTS refund_item_id BIGINT REFERENCES refund_items;
ALTER TABLE bank_events ADD COLUMN IF NOT EXISTS topup_reference VARCHAR(150);
CREATE TABLE IF NOT EXISTS retention_preflight (
 version INT PRIMARY KEY, archived_cards BIGINT NOT NULL, archived_items BIGINT NOT NULL, financial_fingerprint VARCHAR(64),
 completed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
UPDATE bank_events b SET topup_reference=t.reference FROM topups t WHERE b.topup_id=t.id AND b.topup_reference IS NULL;

CREATE TABLE station_sessions (
 session_id UUID PRIMARY KEY, station_id UUID NOT NULL, startup_id UUID NOT NULL,
 epoch BIGINT NOT NULL, status VARCHAR(10) NOT NULL CHECK(status IN ('ACTIVE','CLOSED','LOST')),
 heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 lease_until TIMESTAMPTZ NOT NULL, closed_at TIMESTAMPTZ,
 UNIQUE(station_id,startup_id), UNIQUE(station_id,epoch)
);
CREATE UNIQUE INDEX one_active_station_session ON station_sessions(station_id) WHERE status='ACTIVE';
ALTER TABLE invoices DROP CONSTRAINT invoices_status_check;
ALTER TABLE invoices ADD CONSTRAINT invoices_status_check CHECK(status IN ('OPEN','PAYMENT_PENDING','CANCEL_PENDING','PAID','CANCELLED'));
ALTER TABLE invoices ADD COLUMN station_session_id UUID REFERENCES station_sessions;
ALTER TABLE invoices ADD COLUMN outcome VARCHAR(10) CHECK(outcome IN ('SUCCESS','CANCEL'));
ALTER TABLE invoices ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE invoices ADD COLUMN paid_at TIMESTAMPTZ;
ALTER TABLE invoices ADD COLUMN cancelled_at TIMESTAMPTZ;
UPDATE invoices SET outcome=CASE WHEN status='PAID' THEN 'SUCCESS' WHEN status='CANCELLED' THEN 'CANCEL' END;

CREATE TABLE payment_attempts (
 invoice_id BIGINT PRIMARY KEY REFERENCES invoices, attempt_id UUID NOT NULL UNIQUE,
 connection_id UUID NOT NULL, before_balance BIGINT, before_wallet_version BIGINT,
 debit_wallet_id BIGINT UNIQUE REFERENCES wallet_operations,
 reversal_wallet_id BIGINT UNIQUE REFERENCES wallet_operations,
 decision VARCHAR(10) CHECK(decision IN ('SUCCESS','CANCEL')),
 error_code VARCHAR(40), released BOOLEAN NOT NULL DEFAULT false, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE wallet_operations DROP CONSTRAINT wallet_operations_kind_check;
ALTER TABLE wallet_operations ADD CONSTRAINT wallet_operations_kind_check CHECK(kind IN ('CREDIT','DEBIT','REVERSAL'));
ALTER TABLE wallet_operations ADD COLUMN parent_id BIGINT UNIQUE REFERENCES wallet_operations;
ALTER TABLE wallet_operations ADD CONSTRAINT reversal_parent CHECK((kind='REVERSAL')=(parent_id IS NOT NULL));
-- Preserve the association of old failed retries before enforcing one DEBIT.
ALTER TABLE wallet_operations ADD COLUMN legacy_invoice_id BIGINT REFERENCES invoices;
UPDATE wallet_operations SET legacy_invoice_id=invoice_id,invoice_id=NULL WHERE kind='DEBIT' AND status='FAILED';
DROP INDEX one_invoice_payment;
CREATE UNIQUE INDEX one_invoice_payment ON wallet_operations(invoice_id) WHERE kind='DEBIT';
ALTER TABLE card_operations DROP CONSTRAINT card_operations_kind_check;
ALTER TABLE card_operations ADD CONSTRAINT card_operations_kind_check CHECK(kind IN (1,2,3,4,6,7,8,9));
ALTER TABLE card_operations ADD COLUMN pin_counter_reset_applied BOOLEAN NOT NULL DEFAULT false;

-- Old invoices are never resumed. A pre-upgrade debit with an unclear result
-- stays pending for review; do not pretend the card was refunded by migration.
UPDATE invoices i SET outcome='CANCEL',cancel_reason='BACKEND_RESTART',revision=revision+1,
 status=CASE WHEN EXISTS(SELECT 1 FROM wallet_operations w JOIN card_operations c ON c.wallet_operation_id=w.id
 WHERE w.invoice_id=i.id AND c.phase NOT IN ('CREATED','CARD_PREPARED','ABORTED')) THEN 'CANCEL_PENDING' ELSE 'CANCELLED' END
 WHERE i.status='OPEN';
