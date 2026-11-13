\set ON_ERROR_STOP on
-- Thirty inert examples for the unified admin history. They are journal-only:
-- no bank notification, top-up, invoice, wallet operation or balance is created.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
SELECT pg_advisory_xact_lock(hashtext('ttm-demo-transactions-20261111b'));

DO $$
BEGIN
  IF NOT ((current_database()='ttm_cantin' AND current_schema()='public')
       OR (current_database()='ttm_test' AND current_schema() LIKE 'demo_seed_preview_%')) THEN
    RAISE EXCEPTION 'Sai CSDL hoac schema; dung tao du lieu mo phong.';
  END IF;
  IF to_regclass('admin_transaction_journal') IS NULL
     OR NOT EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='12' AND success) THEN
    RAISE EXCEPTION 'Can cap nhat backend va CSDL len V12 truoc khi tao du lieu.';
  END IF;
  IF EXISTS(SELECT 1 FROM admin_transaction_journal WHERE source_key LIKE 'DEMO:20261111B:%') THEN
    RAISE EXCEPTION 'Lo du lieu DEMO:20261111B da ton tai; dung de tranh trung.';
  END IF;
END $$;

CREATE TEMP TABLE demo_transaction_cards (
  n integer PRIMARY KEY, card_id varchar(16) NOT NULL, student_code varchar(8) NOT NULL
) ON COMMIT DROP;
INSERT INTO demo_transaction_cards(n,card_id,student_code)
SELECT row_number() OVER (ORDER BY chosen.card_id),chosen.card_id,chosen.student_code
FROM (SELECT card_id,student_code FROM cards
      WHERE status='ACTIVE' AND student_code IS NOT NULL
      ORDER BY card_id LIMIT 5 FOR SHARE SKIP LOCKED) chosen;
DO $$ BEGIN
  IF (SELECT count(*) FROM demo_transaction_cards)<>5 THEN
    RAISE EXCEPTION 'Khong du 5 the dang hoat dong; khong tao du lieu.';
  END IF;
END $$;

WITH examples(n,kind,state,reason,amount,card_n) AS (
  VALUES
    ( 1,'TOPUP',       'WAIT_CARD',NULL,                    10000,1),
    ( 2,'TOPUP',       'WAIT_CARD',NULL,                    20000,2),
    ( 3,'TOPUP',       'WAIT_CARD',NULL,                    50000,3),
    ( 4,'TOPUP',       'WAIT_CARD',NULL,                   100000,4),
    ( 5,'TOPUP',       'CREDITED', NULL,                    30000,5),
    ( 6,'TOPUP',       'CREDITED', NULL,                    40000,1),
    ( 7,'TOPUP',       'CREDITED', NULL,                    60000,2),
    ( 8,'TOPUP',       'CREDITED', NULL,                    80000,3),
    ( 9,'CARD_INVOICE','PAID',     NULL,                    15000,4),
    (10,'CARD_INVOICE','PAID',     NULL,                    22000,5),
    (11,'CARD_INVOICE','PAID',     NULL,                    30000,1),
    (12,'CARD_INVOICE','PAID',     NULL,                    35000,2),
    (13,'CARD_INVOICE','PAID',     NULL,                    45000,3),
    (14,'CARD_INVOICE','PAID',     NULL,                    60000,4),
    (15,'CARD_INVOICE','PAID',     NULL,                    75000,5),
    (16,'BANK_INVOICE','PAID',     NULL,                    18000,1),
    (17,'BANK_INVOICE','PAID',     NULL,                    25000,2),
    (18,'BANK_INVOICE','PAID',     NULL,                    32000,3),
    (19,'BANK_INVOICE','PAID',     NULL,                    40000,4),
    (20,'BANK_INVOICE','PAID',     NULL,                    55000,5),
    (21,'BANK_INVOICE','PAID',     NULL,                    70000,1),
    (22,'BANK_INVOICE','PAID',     NULL,                    90000,2),
    (23,'UNKNOWN',     'REVIEW',   'AMOUNT_MISMATCH',       30000,3),
    (24,'UNKNOWN',     'REVIEW',   'AMOUNT_MISMATCH',       70000,4),
    (25,'UNKNOWN',     'REVIEW',   'INVALID_TRANSFER_CODE', 20000,NULL),
    (26,'UNKNOWN',     'REVIEW',   'INVALID_TRANSFER_CODE', 45000,NULL),
    (27,'UNKNOWN',     'REVIEW',   'NO_ACTIVE_REQUEST',     50000,NULL),
    (28,'UNKNOWN',     'REVIEW',   'REQUEST_EXPIRED',       35000,5),
    (29,'UNKNOWN',     'REVIEW',   'REQUEST_CLOSED',        40000,1),
    (30,'UNKNOWN',     'REVIEW',   'ALREADY_PAID',          25000,2)
)
INSERT INTO admin_transaction_journal
  (source_key,kind,occurred_at,reference,bank_reference,provider_id,
   card_id,student_code,amount,status,reason,simulated)
SELECT 'DEMO:20261111B:'||lpad(e.n::text,2,'0'),e.kind,
       clock_timestamp() - e.n*interval '2 minutes',
       CASE WHEN e.kind='TOPUP' THEN 'DEMO-NAP-20261111B-'
            WHEN e.kind IN ('CARD_INVOICE','BANK_INVOICE') THEN 'DEMO-HD-20261111B-'
            ELSE 'DEMO-KHONGRO-20261111B-' END ||lpad(e.n::text,2,'0'),
       CASE WHEN e.kind<>'CARD_INVOICE' THEN 'DEMO-BANK-20261111B-'||lpad(e.n::text,2,'0') END,
       CASE WHEN e.kind<>'CARD_INVOICE' THEN 'DEMO:20261111B:BANK:'||lpad(e.n::text,2,'0') END,
       c.card_id,c.student_code,e.amount,e.state,e.reason,true
FROM examples e LEFT JOIN demo_transaction_cards c ON c.n=e.card_n;

DO $$
BEGIN
  IF (SELECT count(*) FROM admin_transaction_journal WHERE source_key LIKE 'DEMO:20261111B:%' AND simulated)<>30
    OR (SELECT count(*) FROM admin_transaction_journal WHERE source_key LIKE 'DEMO:20261111B:%' AND kind='TOPUP' AND status='WAIT_CARD')<>4
    OR (SELECT count(*) FROM admin_transaction_journal WHERE source_key LIKE 'DEMO:20261111B:%' AND kind='TOPUP' AND status='CREDITED')<>4
    OR (SELECT count(*) FROM admin_transaction_journal WHERE source_key LIKE 'DEMO:20261111B:%' AND kind='CARD_INVOICE')<>7
    OR (SELECT count(*) FROM admin_transaction_journal WHERE source_key LIKE 'DEMO:20261111B:%' AND kind='BANK_INVOICE')<>7
    OR (SELECT count(*) FROM admin_transaction_journal WHERE source_key LIKE 'DEMO:20261111B:%' AND kind='UNKNOWN')<>8 THEN
    RAISE EXCEPTION 'Kiem tra du lieu mo phong that bai; huy toan bo.';
  END IF;
END $$;

SELECT kind,status,COALESCE(reason,'') AS reason,count(*) AS examples
FROM admin_transaction_journal WHERE source_key LIKE 'DEMO:20261111B:%'
GROUP BY kind,status,reason ORDER BY kind,status,reason;
\if :{?demo_preview}
ROLLBACK;
\else
COMMIT;
\endif
