-- V5/V7 references remain immutable internal identities for retention/deduplication.
-- Only the new TT/NT + full student code memos are issued or accepted by new code.
ALTER TABLE sepay_topup_requests ALTER COLUMN payment_code DROP NOT NULL;
ALTER TABLE sepay_topup_requests ADD COLUMN bank_memo VARCHAR(10)
 CHECK(bank_memo ~ '^NT(AT|CT|DT)[0-9]{6}$');
ALTER TABLE sepay_topup_requests ADD COLUMN closed_at TIMESTAMPTZ;
CREATE INDEX topup_student_memo_window ON sepay_topup_requests(bank_memo,opened_at DESC);
ALTER TABLE invoice_payment_references ADD COLUMN payment_code VARCHAR(10)
 CHECK(payment_code ~ '^TT(AT|CT|DT)[0-9]{6}$');
ALTER TABLE invoice_payment_references ADD COLUMN closed_at TIMESTAMPTZ;
CREATE INDEX invoice_student_memo_window ON invoice_payment_references(payment_code,opened_at DESC);

CREATE FUNCTION close_topup_bank_window() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  UPDATE sepay_topup_requests SET closed_at=COALESCE(closed_at,clock_timestamp()) WHERE reference=OLD.reference;
  RETURN OLD;
 END IF;
 IF NEW.status<>'WAITING_BANK' THEN
  UPDATE sepay_topup_requests SET closed_at=COALESCE(closed_at,clock_timestamp()) WHERE reference=NEW.reference;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER topup_bank_window_update AFTER UPDATE OF status ON topups
 FOR EACH ROW EXECUTE FUNCTION close_topup_bank_window();
CREATE TRIGGER topup_bank_window_delete BEFORE DELETE ON topups
 FOR EACH ROW EXECUTE FUNCTION close_topup_bank_window();

CREATE OR REPLACE FUNCTION retain_invoice_payment_outcome() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  UPDATE invoice_payment_references SET final_outcome=OLD.outcome,payment_method=OLD.payment_method,
   closed_at=COALESCE(closed_at,clock_timestamp()) WHERE invoice_id=OLD.id;
  RETURN OLD;
 END IF;
 UPDATE invoice_payment_references SET final_outcome=NEW.outcome,payment_method=NEW.payment_method,
  closed_at=CASE WHEN NEW.outcome IS NOT NULL THEN COALESCE(closed_at,clock_timestamp()) ELSE closed_at END
 WHERE invoice_id=NEW.id;
 RETURN NEW;
END $$;

-- Previously displayed TTM/CTHD QRs must not remain payable after the format switch.
-- Already received credits/debits and their recovery journals are preserved.
UPDATE topups SET status='EXPIRED' WHERE status='WAITING_BANK';
UPDATE sepay_topup_requests s SET closed_at=COALESCE(s.closed_at,clock_timestamp())
 WHERE NOT EXISTS(SELECT 1 FROM topups t WHERE t.reference=s.reference AND t.status='WAITING_BANK');
