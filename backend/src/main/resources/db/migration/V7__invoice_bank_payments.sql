ALTER TABLE invoices ALTER COLUMN card_id DROP NOT NULL;
ALTER TABLE invoices ALTER COLUMN student_code DROP NOT NULL;
ALTER TABLE invoices ALTER COLUMN full_name DROP NOT NULL;
ALTER TABLE invoices ADD COLUMN request_id UUID;
ALTER TABLE invoices ADD COLUMN request_payload TEXT;
CREATE UNIQUE INDEX invoice_create_request ON invoices(station_session_id,request_id);
ALTER TABLE invoices ADD COLUMN payment_method VARCHAR(20) CHECK(payment_method IN ('CARD','BANK_TRANSFER'));
ALTER TABLE invoices ADD COLUMN payment_phase VARCHAR(20) NOT NULL DEFAULT 'NONE'
 CHECK(payment_phase IN ('NONE','CARD_AUTH','CARD_PROCESSING','FINISHED'));
UPDATE invoices SET payment_method='CARD',payment_phase='FINISHED' WHERE status='PAID';
UPDATE invoices i SET payment_phase=CASE WHEN EXISTS(SELECT 1 FROM payment_attempts a
 WHERE a.invoice_id=i.id AND a.debit_wallet_id IS NOT NULL) THEN 'CARD_PROCESSING' ELSE 'CARD_AUTH' END
 WHERE status IN ('PAYMENT_PENDING','CANCEL_PENDING');
CREATE TABLE invoice_payment_references (
 reference VARCHAR(16) PRIMARY KEY CHECK(reference ~ '^CTHD[0-9]{12}$'),
 invoice_id BIGINT UNIQUE REFERENCES invoices ON DELETE SET NULL,
 invoice_number VARCHAR(30) NOT NULL, amount BIGINT NOT NULL CHECK(amount>0),
 opened_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
 final_outcome VARCHAR(10), payment_method VARCHAR(20), qr_data_url TEXT NOT NULL DEFAULT ''
);
ALTER TABLE bank_events ALTER COLUMN payment_code TYPE VARCHAR(32);
ALTER TABLE bank_events ADD COLUMN invoice_reference VARCHAR(16) REFERENCES invoice_payment_references;
ALTER TABLE bank_events ADD COLUMN review_reason VARCHAR(40);
ALTER TABLE bank_events ADD CONSTRAINT single_bank_target
 CHECK(invoice_reference IS NULL OR (topup_id IS NULL AND topup_reference IS NULL AND refund_item_id IS NULL));
CREATE UNIQUE INDEX one_bank_invoice_payment ON bank_events(invoice_reference) WHERE result='MATCHED_INVOICE';
CREATE INDEX invoice_bank_pending ON bank_events(result) WHERE result='PENDING_CARD';
CREATE FUNCTION retain_invoice_payment_outcome() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE invoice_payment_references SET final_outcome=OLD.outcome,payment_method=OLD.payment_method
 WHERE invoice_id=OLD.id;
 IF TG_OP='UPDATE' THEN
  UPDATE invoice_payment_references SET final_outcome=NEW.outcome,payment_method=NEW.payment_method
  WHERE invoice_id=NEW.id;
  RETURN NEW;
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER retain_invoice_payment_update AFTER UPDATE OF outcome,payment_method ON invoices
 FOR EACH ROW EXECUTE FUNCTION retain_invoice_payment_outcome();
CREATE TRIGGER retain_invoice_payment_delete BEFORE DELETE ON invoices
 FOR EACH ROW EXECUTE FUNCTION retain_invoice_payment_outcome();
