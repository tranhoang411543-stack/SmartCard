package vn.edu.smartcard.backend;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.smartcard.shared.*;

/** Financial evidence survives registration deletion; it contains no card secrets. */
final class RefundStore {
  private final JdbcTemplate db;

  RefundStore(JdbcTemplate db) {
    this.db = db;
  }

  record Preview(int count, String revision) {}

  private static long n(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value instanceof java.math.BigDecimal d
        ? d.longValueExact()
        : ((Number) value).longValue();
  }

  private boolean column(String table, String name) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE"
                + " table_schema=current_schema() AND table_name=? AND column_name=?)",
            Boolean.class,
            table,
            name));
  }

  private List<Map<String, Object>> topups(String card) {
    return db.queryForList(
        "SELECT t.*,COALESCE((SELECT sum(b.amount) FROM bank_events b WHERE b.amount>0 AND"
            + " (b.topup_id=t.id OR b.topup_reference=t.reference)),0) AS received,COALESCE((SELECT"
            + " sum(w.amount) FROM wallet_operations w WHERE w.topup_id=t.id AND w.kind='CREDIT'"
            + " AND w.status='APPLIED'),0) AS credited FROM topups t WHERE t.card_id=? ORDER BY"
            + " t.id",
        card);
  }

  private List<Map<String, Object>> events(String card, boolean legacy) {
    return legacy
        ? db.queryForList(
            "SELECT * FROM bank_events WHERE topup_id IN (SELECT id FROM topups WHERE card_id=?)"
                + " ORDER BY provider_id",
            card)
        : db.queryForList(
            "SELECT * FROM bank_events WHERE card_id=? OR topup_id IN (SELECT id FROM topups WHERE"
                + " card_id=?) OR topup_reference IN (SELECT reference FROM topups WHERE card_id=?)"
                + " ORDER BY provider_id",
            card,
            card,
            card);
  }

  private boolean pending(Map<String, Object> t) {
    return Set.of("WAITING_BANK", "BANK_RECEIVED", "REVIEW").contains(t.get("status"))
        || n(t, "received") > n(t, "credited");
  }

  private void normalizeReferences(String card) {
    db.update(
        "UPDATE bank_events b SET topup_reference=t.reference FROM topups t WHERE t.card_id=? AND"
            + " (b.topup_id=t.id OR regexp_replace(upper(b.description),'[^A-Z0-9]','','g') ~"
            + " ('TTMNAPN'||substring(t.reference from 9)||'([^0-9]|$)')) AND b.topup_reference IS"
            + " NULL",
        card);
  }

  Preview preview(String card) {
    normalizeReferences(card);
    var rows = topups(card);
    var bank = events(card, false);
    int count = (int) rows.stream().filter(this::pending).count();
    Set<String> references = new HashSet<>();
    for (var row : rows) references.add((String) row.get("reference"));
    count +=
        (int)
            bank.stream()
                .filter(b ->
                    n(b, "amount") > 0
                        && b.get("topup_id") == null
                        && !references.contains(b.get("topup_reference")))
                .count();
    // Include identities, amounts, states and journal phases, not just N.
    StringBuilder revision = new StringBuilder();
    for (var row : rows)
      revision
          .append(row.get("id"))
          .append('|')
          .append(row.get("reference"))
          .append('|')
          .append(row.get("amount"))
          .append('|')
          .append(row.get("status"))
          .append('|')
          .append(row.get("received"))
          .append('|')
          .append(row.get("credited"))
          .append(';');
    for (var b : bank)
      revision
          .append(b.get("provider_id"))
          .append('|')
          .append(b.get("bank_reference"))
          .append('|')
          .append(b.get("amount"))
          .append('|')
          .append(b.get("result"))
          .append('|')
          .append(b.get("topup_reference"))
          .append(';');
    for (var op :
        db.queryForList(
            "SELECT id,kind,status,amount FROM wallet_operations WHERE card_id=? ORDER BY id",
            card)) revision.append(op);
    return new Preview(
        count,
        HexFormat.of().formatHex(Wire.hash(revision.toString().getBytes(StandardCharsets.UTF_8))));
  }

  /** Caller owns the card lock and SQL transaction (or exclusive pre-V3 startup). */
  Long archive(
      String card,
      String code,
      String name,
      Long actor,
      Long balance,
      Long walletVersion,
      boolean legacy) {
    // Populate references on old matched events and recognizable REVIEW receipts.
    db.update(
        "UPDATE bank_events b SET topup_reference=t.reference FROM topups t WHERE t.card_id=? AND"
            + " (b.topup_id=t.id OR regexp_replace(upper(b.description),'[^A-Z0-9]','','g') ~"
            + " ('TTMNAPN'||substring(t.reference from 9)||'([^0-9]|$)')) AND b.topup_reference IS"
            + " NULL",
        card);
    var topups = topups(card);
    var events = events(card, legacy);
    Long caseId = null;
    for (var t : topups) {
      Long credited = n(t, "credited");
      if ("CREDITED".equals(t.get("status"))) credited = Math.max(credited, n(t, "amount"));
      boolean unclear =
          Boolean.TRUE.equals(
              db.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM wallet_operations WHERE topup_id=? AND"
                      + " status='PENDING')",
                  Boolean.class,
                  t.get("id")));
      if (unclear) credited = null;
      long received = n(t, "received");
      Long item = null;
      if (pending(t) || unclear) {
        if (caseId == null) caseId = createCase(card, code, name, actor, balance, walletVersion);
        String state =
            credited == null || "REVIEW".equals(t.get("status"))
                ? "REVIEW_REQUIRED"
                : received > credited
                    ? "REFUND_PENDING"
                    : received == 0 ? "WAITING_BANK" : "REVIEW_REQUIRED";
        var evidence =
            db.queryForList(
                "SELECT id,kind,amount,status FROM wallet_operations WHERE topup_id=? ORDER BY id",
                t.get("id"));
        if (column("card_operations", "phase"))
          evidence.addAll(
              db.queryForList(
                  "SELECT c.id,c.kind,c.phase,c.before_balance,c.next_balance,c.wallet_operation_id"
                      + " FROM card_operations c JOIN wallet_operations w ON"
                      + " w.id=c.wallet_operation_id WHERE w.topup_id=? ORDER BY c.id",
                  t.get("id")));
        item =
            db.queryForObject(
                "INSERT INTO"
                    + " refund_items(case_id,source_topup_id,reference,requested_amount,received_amount,credited_amount,status,evidence_json)"
                    + " VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(reference) DO UPDATE SET"
                    + " requested_amount=excluded.requested_amount,received_amount=excluded.received_amount,credited_amount=excluded.credited_amount,status=excluded.status,evidence_json=excluded.evidence_json"
                    + " RETURNING id",
                Long.class,
                caseId,
                t.get("id"),
                t.get("reference"),
                t.get("amount"),
                received,
                credited,
                state,
                encode(evidence));
      }
      db.update(
          "INSERT INTO"
              + " retired_topup_references(reference,original_card_id,student_code,requested_amount,received_amount,credited_amount,refund_item_id)"
              + " VALUES(?,?,?,?,?,?,?) ON CONFLICT(reference) DO UPDATE SET"
              + " requested_amount=excluded.requested_amount,received_amount=excluded.received_amount,credited_amount=excluded.credited_amount,refund_item_id=COALESCE(excluded.refund_item_id,retired_topup_references.refund_item_id)",
          t.get("reference"),
          card,
          code,
          t.get("amount"),
          received,
          credited,
          item);
      if (item != null)
        db.update(
            "UPDATE bank_events SET refund_item_id=? WHERE topup_id=? OR topup_reference=?",
            item,
            t.get("id"),
            t.get("reference"));
    }
    Set<String> known = new HashSet<>();
    for (var t : topups) known.add((String) t.get("reference"));
    for (var b : events) {
      if (n(b, "amount") <= 0
          || b.get("topup_id") != null
          || known.contains(b.get("topup_reference"))) continue;
      if (caseId == null) caseId = createCase(card, code, name, actor, balance, walletVersion);
      long item =
          db.queryForObject(
              "INSERT INTO"
                  + " refund_items(case_id,reference,requested_amount,received_amount,credited_amount,status)"
                  + " VALUES(?,?,0,?,NULL,'REVIEW_REQUIRED') ON CONFLICT(reference) DO UPDATE SET"
                  + " reference=excluded.reference RETURNING id",
              Long.class,
              caseId,
              "BANK:" + b.get("provider_id"),
              n(b, "amount"));
      db.update(
          "UPDATE bank_events SET refund_item_id=? WHERE provider_id=?",
          item,
          b.get("provider_id"));
    }
    // Preserve global bank dedup keys; detach all FK targets before card/topup DELETE.
    if (legacy)
      db.update(
          "UPDATE bank_events SET topup_id=NULL WHERE topup_id IN(SELECT id FROM topups WHERE"
              + " card_id=?)",
          card);
    else
      db.update(
          "UPDATE bank_events SET topup_id=NULL,card_id=NULL WHERE card_id=? OR topup_id IN(SELECT"
              + " id FROM topups WHERE card_id=?) OR topup_reference IN(SELECT reference FROM"
              + " topups WHERE card_id=?)",
          card,
          card,
          card);
    return caseId;
  }

  private long createCase(
      String card, String code, String name, Long actor, Long balance, Long version) {
    return db.queryForObject(
        "INSERT INTO"
            + " refund_cases(original_card_id,student_code,full_name,actor_id,observed_balance,observed_wallet_version)"
            + " VALUES(?,?,?,?,?,?) ON CONFLICT(original_card_id) DO UPDATE SET"
            + " original_card_id=excluded.original_card_id RETURNING id",
        Long.class,
        card,
        code,
        name == null ? "" : name,
        actor,
        balance,
        version);
  }

  private static String encode(Object value) {
    try {
      return SecurityMaterial.JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Không lưu được chứng từ đối chiếu.", e);
    }
  }

  /** Called after bank-event insertion under the same transaction; dedup is global. */
  boolean acceptArchived(String reference, String provider, long amount) {
    var rows =
        db.queryForList(
            "SELECT * FROM retired_topup_references WHERE reference=? FOR UPDATE", reference);
    if (rows.isEmpty()) return false;
    var r = rows.getFirst();
    Long item = (Long) r.get("refund_item_id");
    if (item == null) {
      long caseId =
          createCase(
              (String) r.get("original_card_id"),
              (String) r.get("student_code"),
              "",
              null,
              null,
              null);
      item =
          db.queryForObject(
              "INSERT INTO"
                  + " refund_items(case_id,reference,requested_amount,received_amount,credited_amount,status)"
                  + " VALUES(?,?,?,?,?,'REVIEW_REQUIRED') RETURNING id",
              Long.class,
              caseId,
              reference,
              r.get("requested_amount"),
              r.get("received_amount"),
              r.get("credited_amount"));
      db.update(
          "UPDATE retired_topup_references SET refund_item_id=? WHERE reference=?",
          item,
          reference);
    }
    db.queryForList("SELECT id FROM refund_items WHERE id=? FOR UPDATE", item);
    db.update(
        "UPDATE refund_items SET received_amount=received_amount+?,status=CASE WHEN credited_amount"
            + " IS NULL OR ?<>requested_amount OR status IN('REFUNDED','REVIEW_REQUIRED') THEN"
            + " 'REVIEW_REQUIRED' WHEN received_amount+?>credited_amount THEN 'REFUND_PENDING' ELSE"
            + " 'REVIEW_REQUIRED' END WHERE id=?",
        amount,
        amount,
        amount,
        item);
    db.update(
        "UPDATE bank_events SET refund_item_id=?,topup_reference=?,result='REFUND_REVIEW' WHERE"
            + " provider_id=?",
        item,
        reference,
        provider);
    return true;
  }

  List<Api.RefundItem> list() {
    return db.query(
        "SELECT i.*,c.original_card_id,c.student_code,c.full_name FROM refund_items i JOIN"
            + " refund_cases c ON c.id=i.case_id ORDER BY i.id DESC LIMIT 200",
        (r, n) ->
            new Api.RefundItem(
                r.getLong("id"),
                r.getLong("case_id"),
                r.getString("original_card_id"),
                r.getString("student_code"),
                r.getString("full_name"),
                r.getString("reference"),
                r.getLong("requested_amount"),
                r.getLong("received_amount"),
                (Long) r.getObject("credited_amount"),
                r.getObject("credited_amount") == null
                    ? null
                    : Math.max(0, r.getLong("received_amount") - r.getLong("credited_amount")),
                r.getString("status")));
  }
}
