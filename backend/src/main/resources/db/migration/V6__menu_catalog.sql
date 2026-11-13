CREATE TABLE menu_categories (
 id BIGSERIAL PRIMARY KEY, name VARCHAR(50) NOT NULL, name_key VARCHAR(100) NOT NULL UNIQUE,
 revision BIGINT NOT NULL DEFAULT 1
);
INSERT INTO menu_categories(name,name_key)
 SELECT min(category),lower(trim(category)) FROM menu_items GROUP BY lower(trim(category));
ALTER TABLE menu_items ADD COLUMN category_id BIGINT REFERENCES menu_categories ON DELETE CASCADE;
UPDATE menu_items m SET category_id=c.id FROM menu_categories c WHERE c.name_key=lower(trim(m.category));
ALTER TABLE menu_items ALTER COLUMN category_id SET NOT NULL;
ALTER TABLE menu_items ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE menu_items ADD COLUMN image_data BYTEA;
CREATE INDEX menu_category_items ON menu_items(category_id,id);
ALTER TABLE invoice_lines DROP CONSTRAINT invoice_lines_pkey;
ALTER TABLE invoice_lines ADD COLUMN line_id BIGSERIAL PRIMARY KEY;
ALTER TABLE invoice_lines DROP CONSTRAINT invoice_lines_item_id_fkey;
ALTER TABLE invoice_lines ALTER COLUMN item_id DROP NOT NULL;
ALTER TABLE invoice_lines ADD CONSTRAINT invoice_lines_item_id_fkey
 FOREIGN KEY(item_id) REFERENCES menu_items ON DELETE SET NULL;
CREATE TABLE menu_requests (
 request_id UUID PRIMARY KEY, terminal_id UUID NOT NULL, kind VARCHAR(20) NOT NULL,
 payload TEXT NOT NULL, entity_id BIGINT NOT NULL
);
CREATE TABLE menu_audit (
 id BIGSERIAL PRIMARY KEY, terminal_id UUID NOT NULL, action VARCHAR(30) NOT NULL,
 entity_id BIGINT NOT NULL, detail VARCHAR(250) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
