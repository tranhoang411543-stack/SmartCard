package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Destructive only in the explicitly guarded ttm_test fixture, never the application DB. */
class MigrationRetentionTest extends DatabaseFixture {
  @Test
  void preflightPreservesPendingReceiptsBeforeV3DeletesLegacyCards() {
    assertEquals("jdbc:postgresql://127.0.0.1:5548/ttm_test", config.required("db.url"));
    var old =
        Flyway.configure()
            .dataSource(source)
            .locations("classpath:db/migration")
            .target("2")
            .cleanDisabled(false)
            .load();
    old.clean();
    old.migrate();
    repo.db.update(
        "INSERT INTO cards(card_id,public_key,student_code,profile_enc,status)"
            + " VALUES('TTM900001','fixture-public-key','AT450101','legacy-ciphertext-not-archived','ACTIVE')");
    long received =
        repo.db.queryForObject(
            "INSERT INTO topups(card_id,reference,amount,status,expires_at)"
                + " VALUES('TTM900001','TTMNAP N00900001',30000,'BANK_RECEIVED',now()+interval '10"
                + " minutes') RETURNING id",
            Long.class);
    repo.db.update(
        "INSERT INTO topups(card_id,reference,amount,status,expires_at) VALUES('TTM900001','TTMNAP"
            + " N00900002',40000,'WAITING_BANK',now()+interval '10 minutes')");
    repo.db.update(
        "INSERT INTO"
            + " bank_events(provider_id,bank_reference,account_token,amount,description,topup_id,result)"
            + " VALUES('migration-receipt','migration-bank-ref',?,30000,'TTMNAP"
            + " N00900001',?,'MATCHED')",
        "0".repeat(64),
        received);
    FinancialRetention.beforeMigration(source);
    repo.db.update(
        "INSERT INTO"
            + " bank_events(provider_id,bank_reference,account_token,amount,description,result)"
            + " VALUES('new-after-preflight','new-after-preflight-ref',?,40000,'TTMNAP"
            + " N00900002','REVIEW')",
        "0".repeat(64));
    var guarded =
        Flyway.configure()
            .dataSource(source)
            .locations("classpath:db/migration")
            .callbacks(FinancialRetention.guard())
            .load();
    assertThrows(RuntimeException.class, guarded::migrate);
    assertEquals(1, repo.db.queryForObject("SELECT count(*) FROM cards", Integer.class));
    config.flyway(source).migrate();
    assertEquals(0, repo.db.queryForObject("SELECT count(*) FROM cards", Integer.class));
    assertEquals(2, repo.db.queryForObject("SELECT count(*) FROM refund_items", Integer.class));
    assertEquals(
        1,
        repo.db.queryForObject(
            "SELECT count(*) FROM bank_events WHERE provider_id='migration-receipt' AND topup_id IS"
                + " NULL AND refund_item_id IS NOT NULL",
            Integer.class));
    assertEquals(
        30000,
        repo.db.queryForObject(
            "SELECT received_amount FROM refund_items WHERE source_topup_id=?",
            Long.class,
            received));
    assertEquals(
        1,
        repo.db.queryForObject(
            "SELECT count(*) FROM retention_preflight WHERE version=3", Integer.class));
    assertEquals(9, repo.db.queryForObject("SELECT count(*) FROM menu_items", Integer.class));
    assertFalse(
        repo.db
            .queryForObject("SELECT string_agg(evidence_json,'') FROM refund_items", String.class)
            .contains("legacy-ciphertext"));
    config.flyway(source).migrate();
    assertEquals(2, repo.db.queryForObject("SELECT count(*) FROM refund_items", Integer.class));
  }
}
