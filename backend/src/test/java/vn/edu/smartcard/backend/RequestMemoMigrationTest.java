package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class RequestMemoMigrationTest extends DatabaseFixture {
  @Test
  void v9PreservesLegacyEvidenceAndAcceptedReceiptsWhileRetiringWaitingQrs() {
    assertEquals("jdbc:postgresql://127.0.0.1:5548/ttm_test", config.required("db.url"));
    String schema = "request_memo_" + UUID.randomUUID().toString().replace("-", "");
    repo.db.execute("CREATE SCHEMA " + schema);
    try {
      migration(schema, "8").migrate();
      repo.db.update(
          "INSERT INTO "
              + schema
              + ".cards(card_id,public_key,status)"
              + " VALUES('MEMO0001','request-migration-key','ACTIVE')");
      for (String status : List.of("WAITING_BANK", "BANK_RECEIVED", "CREDITED")) {
        String ref = "UPGRADE-" + status;
        repo.db.update(
            "INSERT INTO "
                + schema
                + ".topups(card_id,reference,amount,status,expires_at)"
                + " VALUES('MEMO0001',?,50000,?,clock_timestamp()+interval '15 minutes')",
            ref,
            status);
        repo.db.update(
            "INSERT INTO "
                + schema
                + ".sepay_topup_requests(reference,bank_memo,original_card_id,amount,expires_at)"
                + " VALUES(?,'NTAT880101','MEMO0001',50000,clock_timestamp()+interval '15"
                + " minutes')",
            ref);
      }
      long id =
          repo.db.queryForObject(
              "INSERT INTO "
                  + schema
                  + ".invoices(card_id,student_code,full_name,total,status,outcome,payment_method,payment_phase)"
                  + " VALUES('MEMO0001','AT880101','Migration',35000,'PAID','SUCCESS','BANK_TRANSFER','FINISHED')"
                  + " RETURNING id",
              Long.class);
      repo.db.update(
          "INSERT INTO "
              + schema
              + ".invoice_payment_references(reference,invoice_id,invoice_number,amount,opened_at,expires_at,final_outcome,payment_method,payment_code)"
              + " VALUES('CTHD000000000001',?,'HD00000001',35000,clock_timestamp(),clock_timestamp()+interval"
              + " '5 minutes','SUCCESS','BANK_TRANSFER','TTAT880101')",
          id);
      repo.db.update(
          "INSERT INTO "
              + schema
              + ".bank_events(provider_id,bank_reference,account_token,amount,description,result,payment_code,invoice_reference)"
              + " VALUES('UPGRADE-EVENT','UPGRADE-BANK',repeat('0',64),35000,'legacy"
              + " evidence','MATCHED_INVOICE','TTAT880101','CTHD000000000001')");
      migration(schema, "9").migrate();
      assertEquals(
          "EXPIRED",
          repo.db.queryForObject(
              "SELECT status FROM " + schema + ".topups WHERE reference='UPGRADE-WAITING_BANK'",
              String.class));
      assertNotNull(
          repo.db.queryForObject(
              "SELECT closed_at FROM "
                  + schema
                  + ".sepay_topup_requests WHERE reference='UPGRADE-WAITING_BANK'",
              java.sql.Timestamp.class));
      for (String status : List.of("BANK_RECEIVED", "CREDITED"))
        assertEquals(
            status,
            repo.db.queryForObject(
                "SELECT status FROM " + schema + ".topups WHERE reference=?",
                String.class,
                "UPGRADE-" + status));
      var invoice =
          repo.db.queryForMap(
              "SELECT * FROM " + schema + ".invoice_payment_references WHERE invoice_id=?", id);
      assertEquals("TTAT880101", invoice.get("payment_code"));
      assertEquals("SUCCESS", invoice.get("final_outcome"));
      assertEquals(
          "MATCHED_INVOICE",
          repo.db.queryForObject(
              "SELECT result FROM " + schema + ".bank_events WHERE provider_id='UPGRADE-EVENT'",
              String.class));

      String insert =
          "INSERT INTO "
              + schema
              + ".sepay_topup_requests(reference,bank_memo,original_card_id,amount,expires_at)"
              + " VALUES(?,?,'MEMO0001',50000,clock_timestamp()+interval '15 minutes')";
      repo.db.update(insert, "NEW-1", "NTAT88010100000125");
      assertThrows(
          DataIntegrityViolationException.class,
          () -> repo.db.update(insert, "NEW-2", "NTAT88010100000125"));
      assertThrows(
          DataIntegrityViolationException.class,
          () -> repo.db.update(insert, "BAD", "NTAT8801010000125"));
      repo.db.update(
          "UPDATE "
              + schema
              + ".invoice_payment_references SET payment_code='TTAT88010100000125' WHERE"
              + " invoice_id=?",
          id);
      assertThrows(
          DataIntegrityViolationException.class,
          () ->
              repo.db.update(
                  "INSERT INTO "
                      + schema
                      + ".invoice_payment_references(reference,invoice_number,amount,opened_at,expires_at,payment_code)"
                      + " VALUES('CTHD000000000002','HD00000002',35000,clock_timestamp(),clock_timestamp()+interval"
                      + " '5 minutes','TTAT88010100000125')"));
    } finally {
      repo.db.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private Flyway migration(String schema, String target) {
    return Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .target(target)
        .load();
  }
}
