-- Version changes let reconnecting clients ignore stale or reordered snapshots.
ALTER TABLE topups ADD COLUMN status_revision BIGINT NOT NULL DEFAULT 0;

CREATE FUNCTION bump_topup_status_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status IS DISTINCT FROM OLD.status THEN NEW.status_revision := OLD.status_revision + 1; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER topup_revision BEFORE UPDATE OF status ON topups
 FOR EACH ROW EXECUTE FUNCTION bump_topup_status_revision();

-- PostgreSQL delivers NOTIFY only after the surrounding transaction commits.
-- The table remains the source of truth if a listener is offline.
CREATE FUNCTION signal_topup_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
   PERFORM pg_notify('ttm_topup_status',OLD.id::text);
   RETURN OLD;
 ELSIF TG_OP='INSERT' THEN
   PERFORM pg_notify('ttm_topup_status',NEW.id::text);
 ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
   PERFORM pg_notify('ttm_topup_status',NEW.id::text);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER topup_signal AFTER INSERT OR UPDATE OF status ON topups
 FOR EACH ROW EXECUTE FUNCTION signal_topup_status();
CREATE TRIGGER topup_deleted_signal AFTER DELETE ON topups
 FOR EACH ROW EXECUTE FUNCTION signal_topup_status();

-- A top-up is shown as credited only after the card's final acknowledgement.
CREATE OR REPLACE FUNCTION refresh_admin_bank_transaction(source_provider VARCHAR) RETURNS void LANGUAGE plpgsql AS $$
DECLARE b RECORD; category VARCHAR(24); state VARCHAR(24); display_ref VARCHAR(160);
BEGIN
 SELECT e.*,t.status AS topup_status,t.id AS active_topup_id,t.card_id AS topup_card,
        s.original_card_id AS request_card,s.bank_memo,s.payment_code AS old_payment_code,
        r.invoice_number,r.payment_code AS invoice_memo,inv.card_id AS invoice_card,
        inv.student_code AS invoice_student,rc.original_card_id AS archived_card,
        rc.student_code AS archived_student,ri.credited_amount AS archived_credited,
        ri.id AS archived_item, c.student_code AS card_student
 INTO b FROM bank_events e
 LEFT JOIN topups t ON t.id=e.topup_id
 LEFT JOIN sepay_topup_requests s ON s.reference=COALESCE(e.topup_reference,t.reference)
 LEFT JOIN invoice_payment_references r ON r.reference=e.invoice_reference
 LEFT JOIN invoices inv ON inv.id=r.invoice_id
 LEFT JOIN refund_items ri ON ri.id=e.refund_item_id
 LEFT JOIN refund_cases rc ON rc.id=ri.case_id
 LEFT JOIN cards c ON c.card_id=COALESCE(e.card_id,t.card_id,s.original_card_id,inv.card_id)
 WHERE e.provider_id=source_provider AND e.amount>0;
 IF NOT FOUND THEN RETURN; END IF;
 category := CASE WHEN b.result='MATCHED' THEN 'TOPUP'
                  WHEN b.result='MATCHED_INVOICE' THEN 'BANK_INVOICE' ELSE 'UNKNOWN' END;
 state := CASE WHEN category='TOPUP' THEN
                 CASE WHEN b.topup_status='CREDITED' OR EXISTS(
                   SELECT 1 FROM wallet_operations w JOIN card_operations op ON op.wallet_operation_id=w.id
                   WHERE w.topup_id=b.active_topup_id AND w.kind='CREDIT' AND w.status='APPLIED'
                     AND op.phase='SYNCED') THEN 'CREDITED'
                   WHEN b.archived_item IS NOT NULL AND b.archived_credited IS NULL THEN NULL
                   WHEN b.archived_credited IS NOT NULL AND b.archived_credited>=b.amount THEN 'CREDITED'
                   WHEN b.active_topup_id IS NOT NULL OR b.archived_credited IS NOT NULL
                        OR b.provider_id LIKE 'DEMO:20261110A:%' THEN 'WAIT_CARD'
                   ELSE NULL END
               WHEN category='BANK_INVOICE' THEN 'PAID' ELSE 'REVIEW' END;
 display_ref := CASE WHEN category='TOPUP' THEN COALESCE(b.bank_memo,b.old_payment_code,b.topup_reference,b.payment_code,b.bank_reference)
   WHEN category='BANK_INVOICE' THEN COALESCE(b.invoice_memo,b.invoice_number,b.payment_code,b.bank_reference)
   ELSE COALESCE(b.payment_code,b.bank_reference) END;
 INSERT INTO admin_transaction_journal(source_key,kind,occurred_at,reference,bank_reference,provider_id,
     card_id,student_code,amount,status,reason,simulated)
 VALUES ('BANK:'||b.provider_id,category,COALESCE(b.bank_transaction_at,b.created_at),display_ref,
     b.bank_reference,b.provider_id,COALESCE(b.card_id,b.topup_card,b.request_card,b.invoice_card,b.archived_card),
     COALESCE(b.card_student,b.invoice_student,b.archived_student),b.amount,state,
     CASE WHEN category='UNKNOWN' THEN COALESCE(b.review_reason,
       CASE WHEN b.result='PENDING_CARD' THEN 'CARD_RESULT_PENDING' ELSE 'UNMATCHED' END)
       WHEN category='TOPUP' AND state IS NULL THEN 'LEGACY_UNCERTAIN' ELSE NULL END,
     b.provider_id LIKE 'DEMO:20261110A:%')
 ON CONFLICT(source_key) DO UPDATE SET kind=EXCLUDED.kind,occurred_at=EXCLUDED.occurred_at,
   reference=COALESCE(EXCLUDED.reference,admin_transaction_journal.reference),
   bank_reference=EXCLUDED.bank_reference,provider_id=EXCLUDED.provider_id,
   card_id=COALESCE(EXCLUDED.card_id,admin_transaction_journal.card_id),
   student_code=COALESCE(EXCLUDED.student_code,admin_transaction_journal.student_code),
   amount=EXCLUDED.amount,status=CASE WHEN EXCLUDED.kind='TOPUP' AND EXCLUDED.status IS NULL
       AND admin_transaction_journal.status='CREDITED' THEN 'CREDITED' ELSE EXCLUDED.status END,
   reason=CASE WHEN EXCLUDED.kind='TOPUP' AND EXCLUDED.status IS NULL
       AND admin_transaction_journal.status='CREDITED' THEN admin_transaction_journal.reason
       ELSE EXCLUDED.reason END,simulated=EXCLUDED.simulated;
END $$;

-- Correct still-active historical rows that were marked before the final ACK.
DO $$ DECLARE source RECORD; BEGIN
 FOR source IN SELECT provider_id FROM bank_events WHERE amount>0 LOOP
   PERFORM refresh_admin_bank_transaction(source.provider_id);
 END LOOP;
END $$;
