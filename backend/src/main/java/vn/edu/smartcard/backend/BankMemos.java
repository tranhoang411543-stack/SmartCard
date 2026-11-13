package vn.edu.smartcard.backend;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Full bank memos identify one retained request; timestamps only validate its eligibility. */
final class BankMemos {
  enum Kind {
    TOPUP("sepay_topup_requests", "bank_memo"),
    INVOICE("invoice_payment_references", "payment_code");
    final String table, column;

    Kind(String table, String column) {
      this.table = table;
      this.column = column;
    }
  }

  private BankMemos() {}

  static void lock(CardRepository repo, String memo) {
    repo.db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,51827))", memo);
  }

  static Map<String, Object> find(CardRepository repo, Kind kind, String memo) {
    if (!SePayGateway.validMemo(memo)) return null;
    var rows =
        repo.db.queryForList("SELECT * FROM " + kind.table + " WHERE " + kind.column + "=?", memo);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  static boolean contains(Map<String, Object> request, Instant bankTime) {
    Instant begin =
        ((Timestamp) request.get("opened_at"))
            .toInstant()
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    Instant end = ((Timestamp) request.get("expires_at")).toInstant();
    if (request.get("closed_at") instanceof Timestamp closed && closed.toInstant().isBefore(end))
      end = closed.toInstant();
    return !bankTime.isBefore(begin) && bankTime.isBefore(end);
  }
}
