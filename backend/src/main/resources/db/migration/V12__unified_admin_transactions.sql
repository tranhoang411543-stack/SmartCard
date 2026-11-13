-- A durable, read-only projection of financial events. Card secrets and full
-- transfer descriptions are deliberately not copied into this journal.
CREATE TABLE admin_transaction_journal (
 source_key VARCHAR(160) PRIMARY KEY,
 kind VARCHAR(24) NOT NULL CHECK (kind IN ('TOPUP','CARD_INVOICE','BANK_INVOICE','UNKNOWN')),
 occurred_at TIMESTAMPTZ NOT NULL,
 reference VARCHAR(160), bank_reference VARCHAR(150), provider_id VARCHAR(100),
 card_id VARCHAR(16), student_code VARCHAR(8),
 amount BIGINT NOT NULL CHECK (amount>0),
 status VARCHAR(24), reason VARCHAR(40), simulated BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX admin_transaction_time ON admin_transaction_journal(occurred_at DESC,source_key DESC);

CREATE FUNCTION refresh_admin_bank_transaction(source_provider VARCHAR) RETURNS void LANGUAGE plpgsql AS $$
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
                   SELECT 1 FROM wallet_operations w WHERE w.topup_id=b.active_topup_id
                     AND w.kind='CREDIT' AND w.status='APPLIED') THEN 'CREDITED'
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

CREATE FUNCTION refresh_admin_invoice_transaction(invoice_key BIGINT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO admin_transaction_journal(source_key,kind,occurred_at,reference,card_id,student_code,amount,status)
 SELECT 'CARD:'||w.id,'CARD_INVOICE',w.created_at,'HD'||lpad(i.id::text,8,'0'),w.card_id,
        COALESCE(i.student_code,c.student_code),w.amount,'PAID'
 FROM invoices i JOIN wallet_operations w ON w.invoice_id=i.id AND w.kind='DEBIT' AND w.status='APPLIED'
 LEFT JOIN cards c ON c.card_id=w.card_id
 WHERE i.id=invoice_key AND i.status='PAID' AND i.payment_method='CARD'
 ON CONFLICT(source_key) DO UPDATE SET status=EXCLUDED.status,
   card_id=COALESCE(EXCLUDED.card_id,admin_transaction_journal.card_id),
   student_code=COALESCE(EXCLUDED.student_code,admin_transaction_journal.student_code);
END $$;

CREATE FUNCTION refresh_admin_topup_transactions(topup_key BIGINT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE provider VARCHAR;
BEGIN
 FOR provider IN SELECT b.provider_id FROM bank_events b JOIN topups t ON t.id=topup_key
   WHERE b.topup_id=t.id OR b.topup_reference=t.reference LOOP
   PERFORM refresh_admin_bank_transaction(provider);
 END LOOP;
END $$;

CREATE FUNCTION journal_bank_event_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM refresh_admin_bank_transaction(NEW.provider_id); RETURN NEW; END $$;
CREATE TRIGGER journal_bank_event AFTER INSERT OR UPDATE ON bank_events
 FOR EACH ROW EXECUTE FUNCTION journal_bank_event_changed();

CREATE FUNCTION journal_topup_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM refresh_admin_topup_transactions(NEW.id); RETURN NEW; END $$;
CREATE TRIGGER journal_topup AFTER UPDATE OF status ON topups
 FOR EACH ROW EXECUTE FUNCTION journal_topup_changed();

CREATE FUNCTION journal_wallet_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.topup_id IS NOT NULL THEN PERFORM refresh_admin_topup_transactions(NEW.topup_id); END IF;
 IF NEW.invoice_id IS NOT NULL THEN PERFORM refresh_admin_invoice_transaction(NEW.invoice_id); END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER journal_wallet AFTER INSERT OR UPDATE OF status ON wallet_operations
 FOR EACH ROW EXECUTE FUNCTION journal_wallet_changed();

CREATE FUNCTION journal_invoice_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE provider VARCHAR;
BEGIN
 PERFORM refresh_admin_invoice_transaction(NEW.id);
 FOR provider IN SELECT e.provider_id FROM bank_events e JOIN invoice_payment_references r
   ON r.reference=e.invoice_reference WHERE r.invoice_id=NEW.id LOOP
   PERFORM refresh_admin_bank_transaction(provider);
 END LOOP;
 RETURN NEW;
END $$;
CREATE TRIGGER journal_invoice AFTER UPDATE ON invoices
 FOR EACH ROW EXECUTE FUNCTION journal_invoice_changed();

-- Populate only from evidence that still exists; never infer deleted card identities.
DO $$ DECLARE source RECORD; BEGIN
 FOR source IN SELECT provider_id FROM bank_events WHERE amount>0 LOOP
   PERFORM refresh_admin_bank_transaction(source.provider_id);
 END LOOP;
 FOR source IN SELECT id FROM invoices WHERE status='PAID' AND payment_method='CARD' LOOP
   PERFORM refresh_admin_invoice_transaction(source.id);
 END LOOP;
END $$;

-- Historical card credits without a surviving matched bank event remain visible once.
INSERT INTO admin_transaction_journal(source_key,kind,occurred_at,reference,card_id,student_code,amount,status)
SELECT 'LEGACY_CREDIT:'||w.id,'TOPUP',w.created_at,COALESCE(s.bank_memo,s.payment_code,t.reference),
       w.card_id,c.student_code,w.amount,'CREDITED'
FROM wallet_operations w LEFT JOIN topups t ON t.id=w.topup_id
LEFT JOIN sepay_topup_requests s ON s.reference=t.reference
LEFT JOIN cards c ON c.card_id=w.card_id
WHERE w.kind='CREDIT' AND w.status='APPLIED' AND NOT EXISTS (
 SELECT 1 FROM bank_events e WHERE e.result='MATCHED'
   AND (e.topup_id=w.topup_id OR (t.reference IS NOT NULL AND e.topup_reference=t.reference)))
ON CONFLICT(source_key) DO NOTHING;
