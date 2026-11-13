package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class CatalogMigrationTest extends DatabaseFixture {
  @Test
  void populatedV5UpgradesWithoutChangingHistoricalPricesAndAllowsHardDelete() {
    assertEquals("jdbc:postgresql://127.0.0.1:5548/ttm_test", config.required("db.url"));
    String schema = "catalog_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    repo.db.execute("CREATE SCHEMA " + schema);
    try {
      Flyway.configure()
          .dataSource(source)
          .locations("classpath:db/migration")
          .schemas(schema)
          .defaultSchema(schema)
          .target("5")
          .load()
          .migrate();
      repo.db.update(
          "INSERT INTO "
              + schema
              + ".cards(card_id,public_key,status) VALUES('MIGRATE01','migration-key','ACTIVE')");
      long invoice =
          repo.db.queryForObject(
              "INSERT INTO "
                  + schema
                  + ".invoices(card_id,student_code,full_name,total,status)"
                  + " VALUES('MIGRATE01','AT650101','Migration',70000,'PAID') RETURNING id",
              Long.class);
      long item =
          repo.db.queryForObject("SELECT min(id) FROM " + schema + ".menu_items", Long.class);
      repo.db.update(
          "INSERT INTO "
              + schema
              + ".invoice_lines(invoice_id,item_id,name,quantity,unit_price) VALUES(?,?,'Cơm gà lúc"
              + " bán',2,35000)",
          invoice,
          item);
      repo.db.update("UPDATE " + schema + ".menu_items SET price=40000 WHERE id=?", item);
      Flyway.configure()
          .dataSource(source)
          .locations("classpath:db/migration")
          .schemas(schema)
          .defaultSchema(schema)
          .load()
          .migrate();
      long category =
          repo.db.queryForObject(
              "SELECT category_id FROM " + schema + ".menu_items WHERE id=?", Long.class, item);
      repo.db.update("DELETE FROM " + schema + ".menu_categories WHERE id=?", category);
      var line =
          repo.db.queryForMap(
              "SELECT * FROM " + schema + ".invoice_lines WHERE invoice_id=?", invoice);
      assertNull(line.get("item_id"));
      assertEquals("Cơm gà lúc bán", line.get("name"));
      assertEquals(35000L, ((Number) line.get("unit_price")).longValue());
      assertEquals(
          70000,
          repo.db.queryForObject(
              "SELECT total FROM " + schema + ".invoices WHERE id=?", Long.class, invoice));
      assertEquals(
          "CARD",
          repo.db.queryForObject(
              "SELECT payment_method FROM " + schema + ".invoices WHERE id=?",
              String.class,
              invoice));
    } finally {
      repo.db.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }
}
