-- Block invoice status changes until both the historical backfill and trigger are installed.
LOCK TABLE invoices IN SHARE ROW EXCLUSIVE MODE;

ALTER TABLE menu_items ADD COLUMN sold_quantity BIGINT NOT NULL DEFAULT 0
  CHECK (sold_quantity >= 0);

UPDATE menu_items m SET sold_quantity = totals.quantity
FROM (
  SELECT l.item_id, sum(l.quantity)::bigint AS quantity
  FROM invoice_lines l JOIN invoices i ON i.id = l.invoice_id
  WHERE i.status = 'PAID' AND l.item_id IS NOT NULL
  GROUP BY l.item_id
) totals
WHERE m.id = totals.item_id;

-- Invoice completion is the shared, transactional decision for both payment methods.
-- Lock dishes in the same order for concurrent payments with multiple dishes.
CREATE FUNCTION count_paid_invoice_dishes() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sold record;
BEGIN
  IF OLD.status IS DISTINCT FROM 'PAID' AND NEW.status = 'PAID' THEN
    FOR sold IN
      SELECT item_id, sum(quantity)::bigint AS quantity
      FROM invoice_lines
      WHERE invoice_id = NEW.id AND item_id IS NOT NULL
      GROUP BY item_id ORDER BY item_id
    LOOP
      UPDATE menu_items SET sold_quantity = sold_quantity + sold.quantity
      WHERE id = sold.item_id;
    END LOOP;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER invoice_count_sold_dishes
AFTER UPDATE OF status ON invoices
FOR EACH ROW EXECUTE FUNCTION count_paid_invoice_dishes();
