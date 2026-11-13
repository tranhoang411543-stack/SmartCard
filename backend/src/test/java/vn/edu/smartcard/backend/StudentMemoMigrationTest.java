package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class StudentMemoMigrationTest extends DatabaseFixture {
  @Test
  void v8ExpiresOldQrsAndPreservesReceivedMoneyAndHistoricalReferences() {
    assertEquals("jdbc:postgresql://127.0.0.1:5548/ttm_test", config.required("db.url"));
    String schema = "memo_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    repo.db.execute("CREATE SCHEMA " + schema);
    try {
      Flyway.configure()
          .dataSource(source)
          .schemas(schema)
          .defaultSchema(schema)
          .locations("classpath:db/migration")
          .target("7")
          .load()
          .migrate();
      repo.db.update(
          "INSERT INTO "
              + schema
              + ".cards(card_id,public_key,status)"
              + " VALUES('MEMO0001','memo-migration-key','ACTIVE')");
      repo.db.update(
          "INSERT INTO "
              + schema
              + ".sepay_payment_codes VALUES('TTM880101','MEMO0001','AT880101',clock_timestamp())");
      for (String status : java.util.List.of("WAITING_BANK", "BANK_RECEIVED", "CREDITED")) {
        String ref = "MIG-" + status;
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
                + ".sepay_topup_requests(reference,payment_code,original_card_id,amount,expires_at)"
                + " VALUES(?,'TTM880101','MEMO0001',50000,clock_timestamp()+interval '15 minutes')",
            ref);
      }
      long invoice =
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
              + ".invoice_payment_references(reference,invoice_id,invoice_number,amount,opened_at,expires_at,final_outcome,payment_method)"
              + " VALUES('CTHD000000000001',?,'HD00000001',35000,clock_timestamp(),clock_timestamp()+interval"
              + " '5 minutes','SUCCESS','BANK_TRANSFER')",
          invoice);
      Flyway.configure()
          .dataSource(source)
          .schemas(schema)
          .defaultSchema(schema)
          .locations("classpath:db/migration")
          .load()
          .migrate();
      assertEquals(
          "EXPIRED",
          repo.db.queryForObject(
              "SELECT status FROM " + schema + ".topups WHERE reference='MIG-WAITING_BANK'",
              String.class));
      for (String status : java.util.List.of("BANK_RECEIVED", "CREDITED"))
        assertEquals(
            status,
            repo.db.queryForObject(
                "SELECT status FROM " + schema + ".topups WHERE reference=?",
                String.class,
                "MIG-" + status));
      var ref =
          repo.db.queryForMap(
              "SELECT * FROM " + schema + ".invoice_payment_references WHERE invoice_id=?",
              invoice);
      assertEquals("CTHD000000000001", ref.get("reference"));
      assertEquals("SUCCESS", ref.get("final_outcome"));
      assertNull(ref.get("payment_code"));
      assertEquals(
          1,
          repo.db.queryForObject(
              "SELECT count(*) FROM "
                  + schema
                  + ".sepay_payment_codes WHERE payment_code='TTM880101'",
              Integer.class));
    } finally {
      repo.db.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }
}
