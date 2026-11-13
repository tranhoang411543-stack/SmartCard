package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import vn.edu.smartcard.shared.Api;

class CardBlockStateTest {
  @TempDir Path temp;

  @AfterEach void clear() { AccessContext.clear(); }

  @Test
  void repeatedBlockDoesNotOverwriteAnExistingPinFailureReason() {
    LocalConfig config = new LocalConfig() {
      @Override public Path root() { return temp; }
      @Override public String required(String key) {
        return Base64.getEncoder().encodeToString(new byte[32]);
      }
    };
    var security = new SecurityMaterial(config);
    var stateDb = new StateDatabase();
    PlatformTransactionManager transactions = new PlatformTransactionManager() {
      @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }
      @Override public void commit(TransactionStatus status) {}
      @Override public void rollback(TransactionStatus status) {}
    };
    var repo = new CardRepository(stateDb, transactions, security) {
      @Override public Stored find(String id) { return new Stored(id, "", "ACTIVE", null, null); }
      @Override public Api.CardLifecycle lifecycle(String id) {
        return new Api.CardLifecycle(id, stateDb.status, 0);
      }
      @Override public void audit(long admin, String id, String action, String result) {
        stateDb.audits++;
      }
    };
    var cards = new CardService(new ReaderHub(security, config), repo, security);
    AccessContext.admin(() -> {});

    stateDb.status = "BLOCKED";
    stateDb.reason = "PIN_FAILURES";
    Failure rejected = assertThrows(Failure.class, () -> cards.block("TTM000001", 1));
    assertEquals(409, rejected.status);
    assertEquals("CARD_ALREADY_BLOCKED", rejected.code);
    assertEquals("PIN_FAILURES", stateDb.reason);
    assertEquals(0, stateDb.updates);
    assertEquals(0, stateDb.audits);

    stateDb.status = "ACTIVE";
    cards.block("TTM000001", 1);
    assertEquals("BLOCKED", stateDb.status);
    assertEquals("ADMIN", stateDb.reason);
    assertEquals(1, stateDb.updates);
    assertEquals(1, stateDb.audits);
  }

  private static final class StateDatabase extends JdbcTemplate {
    String status, reason;
    int updates, audits;

    @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
      assertTrue(sql.contains("FOR UPDATE"));
      return List.of(Map.of("card_id", args[0]));
    }

    @Override public int update(String sql, Object... args) {
      assertTrue(sql.contains("lifecycle_status='BLOCKED'"));
      status = "BLOCKED";
      reason = "ADMIN";
      updates++;
      return 1;
    }
  }
}
