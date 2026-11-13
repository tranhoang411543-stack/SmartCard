-- Keep V8 student-only memos for historical evidence. Only 18-character request
-- memos are issued/accepted by the new application; legacy rows are never rebound.
ALTER TABLE sepay_topup_requests ALTER COLUMN bank_memo TYPE VARCHAR(18);
ALTER TABLE sepay_topup_requests DROP CONSTRAINT sepay_topup_requests_bank_memo_check;
ALTER TABLE sepay_topup_requests ADD CONSTRAINT sepay_topup_requests_bank_memo_check
 CHECK(bank_memo ~ '^NT(AT|CT|DT)([0-9]{6}|[0-9]{14})$');
CREATE UNIQUE INDEX topup_unique_request_memo ON sepay_topup_requests(bank_memo)
 WHERE length(bank_memo)=18;

ALTER TABLE invoice_payment_references ALTER COLUMN payment_code TYPE VARCHAR(18);
ALTER TABLE invoice_payment_references DROP CONSTRAINT invoice_payment_references_payment_code_check;
ALTER TABLE invoice_payment_references ADD CONSTRAINT invoice_payment_references_payment_code_check
 CHECK(payment_code ~ '^TT(AT|CT|DT)([0-9]{6}|[0-9]{14})$');
CREATE UNIQUE INDEX invoice_unique_request_memo ON invoice_payment_references(payment_code)
 WHERE length(payment_code)=18;

-- Do not invalidate already admitted bank receipts or card recovery journals.
-- Legacy waiting top-ups need a new request/QR. Invoice sessions are invalidated
-- through the existing restart/recovery flow; their old QR is no longer exposed.
UPDATE topups t SET status='EXPIRED'
 WHERE t.status='WAITING_BANK' AND EXISTS(
  SELECT 1 FROM sepay_topup_requests s WHERE s.reference=t.reference
   AND (s.bank_memo IS NULL OR length(s.bank_memo)<>18));
