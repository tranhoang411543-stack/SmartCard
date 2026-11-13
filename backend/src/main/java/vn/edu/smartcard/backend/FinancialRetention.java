package vn.edu.smartcard.backend;

import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Must commit before Flyway is allowed to run the historical destructive V3. */
final class FinancialRetention {
  private static boolean exists(JdbcTemplate db, String table) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE"
                + " table_schema=current_schema() AND table_name=?)",
            Boolean.class,
            table));
  }

  static String fingerprint(JdbcTemplate db) {
    StringBuilder value = new StringBuilder();
    String[] queries = {
      "SELECT card_id FROM cards ORDER BY card_id",
      "SELECT id,card_id,reference,amount,status FROM topups ORDER BY id",
      "SELECT provider_id,bank_reference,amount,topup_id,topup_reference,result FROM bank_events"
          + " ORDER BY provider_id",
      "SELECT id,card_id,kind,amount,status,topup_id FROM wallet_operations ORDER BY id",
      "SELECT id,card_id,kind,phase,before_balance,next_balance,wallet_operation_id FROM"
          + " card_operations ORDER BY id"
    };
    String[] tables = {"cards", "topups", "bank_events", "wallet_operations", "card_operations"};
    for (int i = 0; i < queries.length; i++) {
      value.append(tables[i]);
      try {
        value.append(
            SecurityMaterial.JSON.writeValueAsString(
                exists(db, tables[i]) ? db.queryForList(queries[i]) : java.util.List.of()));
      } catch (java.io.IOException e) {
        throw new IllegalStateException(e);
      }
    }
    return java.util.HexFormat.of()
        .formatHex(
            vn.edu.smartcard.shared.Wire.hash(value.toString().getBytes(StandardCharsets.UTF_8)));
  }

  static org.flywaydb.core.api.callback.Callback guard() {
    return new org.flywaydb.core.api.callback.Callback() {
      public boolean supports(
          org.flywaydb.core.api.callback.Event e, org.flywaydb.core.api.callback.Context c) {
        return e == org.flywaydb.core.api.callback.Event.BEFORE_EACH_MIGRATE
            && c.getMigrationInfo() != null
            && c.getMigrationInfo().getVersion() != null
            && "3".equals(c.getMigrationInfo().getVersion().getVersion());
      }

      public boolean canHandleInTransaction(
          org.flywaydb.core.api.callback.Event e, org.flywaydb.core.api.callback.Context c) {
        return true;
      }

      public String getCallbackName() {
        return "financial-retention-before-V3";
      }

      public void handle(
          org.flywaydb.core.api.callback.Event e, org.flywaydb.core.api.callback.Context c) {
        try {
          if (c.getConnection().getAutoCommit())
            throw new IllegalStateException(
                "V3 phải chạy trong transaction để khóa dữ liệu trước xóa.");
        } catch (java.sql.SQLException error) {
          throw new IllegalStateException(error);
        }
        JdbcTemplate db =
            new JdbcTemplate(
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                    c.getConnection(), true));
        db.execute(
            "LOCK TABLE cards,topups,bank_events,wallet_operations,card_operations IN ACCESS"
                + " EXCLUSIVE MODE");
        long rows =
            db.queryForObject(
                "SELECT (SELECT count(*) FROM cards)+(SELECT count(*) FROM topups)+(SELECT count(*)"
                    + " FROM bank_events)",
                Long.class);
        if (rows == 0) return;
        if (!exists(db, "retention_preflight"))
          throw new IllegalStateException(
              "Chưa giữ chứng từ trước V3. Dừng backend cũ rồi chạy lại nâng cấp.");
        var saved =
            db.queryForList(
                "SELECT financial_fingerprint FROM retention_preflight WHERE version=3");
        if (saved.isEmpty()
            || !fingerprint(db).equals(saved.getFirst().get("financial_fingerprint")))
          throw new IllegalStateException(
              "Dữ liệu tài chính đổi sau preflight. Dừng backend/worker cũ rồi chạy lại; chưa xóa"
                  + " dữ liệu V2.");
      }
    };
  }

  static void beforeMigration(DataSource source) {
    JdbcTemplate db = new JdbcTemplate(source);
    if (db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE"
                + " table_schema=current_schema() AND table_name='topups')",
            Boolean.class)
        != Boolean.TRUE) return;
    if (db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE"
                + " table_schema=current_schema() AND table_name='cards' AND"
                + " column_name='profile_json')",
            Boolean.class)
        == Boolean.TRUE) return;
    new TransactionTemplate(new DataSourceTransactionManager(source))
        .executeWithoutResult(tx -> {
          db.execute("SELECT pg_advisory_xact_lock(73343004)");
          try {
            db.execute(
                new ClassPathResource("db/retention-schema.sql")
                    .getContentAsString(StandardCharsets.UTF_8));
          } catch (java.io.IOException e) {
            throw new IllegalStateException("Không đọc được schema lưu đối chiếu.", e);
          }
          RefundStore store = new RefundStore(db);
          var ids = db.queryForList("SELECT card_id,student_code FROM cards ORDER BY card_id");
          for (var row : ids)
            store.archive(
                (String) row.get("card_id"),
                (String) row.get("student_code"),
                "",
                null,
                null,
                null,
                true);
          long lost =
              db.queryForObject(
                  "SELECT count(*) FROM topups t WHERE NOT EXISTS(SELECT 1 FROM"
                      + " retired_topup_references r WHERE r.reference=t.reference)",
                  Long.class);
          if (lost != 0)
            throw new IllegalStateException(
                "Chưa giữ đủ mã nạp trước migration V3; dừng nâng cấp.");
          db.update(
              "INSERT INTO retention_preflight(version,archived_cards,archived_items)"
                  + " VALUES(3,?,(SELECT count(*) FROM refund_items)) ON CONFLICT(version) DO"
                  + " UPDATE SET"
                  + " archived_cards=excluded.archived_cards,archived_items=excluded.archived_items,completed_at=now()",
              ids.size());
          db.update(
              "UPDATE retention_preflight SET financial_fingerprint=? WHERE version=3",
              fingerprint(db));
        });
  }
}
