package vn.edu.smartcard.backend;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import vn.edu.smartcard.shared.Api;

/** Durable bank receipts arbitrate with the card's pre-APDU claim under the invoice lock. */
final class InvoiceBankPayments {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final CardRepository repo;
  private final SecurityMaterial security;
  private final StationSessions stations;

  InvoiceBankPayments(CardRepository repo, SecurityMaterial security, StationSessions stations) {
    this.repo = repo;
    this.security = security;
    this.stations = stations;
  }

  void register(long id) {
    String student =
        repo.db.queryForObject("SELECT student_code FROM invoices WHERE id=?", String.class, id);
    String memo = student == null ? SePayGateway.invoiceCode(id) : SePayGateway.invoiceCode(student, id);
    for (int n = 0; n < 10; n++) {
      String ref =
          "CTHD" + String.format(Locale.ROOT, "%012d", RANDOM.nextLong(1_000_000_000_000L));
      if (repo.db.update(
              "INSERT INTO"
                  + " invoice_payment_references(reference,invoice_id,invoice_number,amount,opened_at,expires_at,payment_code)"
                  + " SELECT ?,id,?,total,created_at,expires_at,? FROM invoices WHERE id=? ON"
                  + " CONFLICT DO NOTHING",
              ref,
              String.format("HD%08d", id),
              memo,
              id)
          == 1) return;
    }
    throw new IllegalStateException("Không cấp được mã thanh toán hóa đơn.");
  }

  void receive(SePayGateway.Notice notice) {
    repo.tx.executeWithoutResult(
        tx -> {
          BankMemos.lock(repo, notice.paymentCode());
          var request = BankMemos.find(repo, BankMemos.Kind.INVOICE, notice.paymentCode());
          String internalReference = request == null ? null : (String) request.get("reference");
          int inserted =
              repo.db.update(
                  "INSERT INTO"
                      + " bank_events(provider_id,bank_reference,account_token,amount,description,result,payment_code,bank_transaction_at,invoice_reference,review_reason)"
                      + " VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                  notice.providerId(),
                  notice.bankReference(),
                  security.keyed("account:" + notice.account()),
                  notice.amount(),
                  notice.description(),
                  notice.amount() > 0 ? "REVIEW" : "IGNORED_OUTGOING",
                  notice.paymentCode(),
                  Timestamp.from(notice.transactionTime()),
                  internalReference,
                  "UNKNOWN_INVOICE");
          if (inserted == 1 && notice.amount() > 0 && request != null) process(notice.providerId());
        });
  }

  private void result(String provider, String result, String reason) {
    repo.db.update(
        "UPDATE bank_events SET result=?,review_reason=? WHERE provider_id=?",
        result,
        reason,
        provider);
  }

  private void process(String provider) {
    var events = repo.db.queryForList("SELECT * FROM bank_events WHERE provider_id=?", provider);
    if (events.isEmpty()) return;
    var e = events.getFirst();
    if (!Set.of("REVIEW", "PENDING_CARD").contains(e.get("result"))) return;
    var refs =
        repo.db.queryForList(
            "SELECT * FROM invoice_payment_references WHERE reference=?",
            e.get("invoice_reference"));
    if (refs.isEmpty() || refs.getFirst().get("invoice_id") == null) {
      result(provider, "REVIEW", "INVOICE_DELETED");
      return;
    }
    var ref = refs.getFirst();
    long id = ((Number) ref.get("invoice_id")).longValue();
    // Card deletion may remove the invoice while this receipt is waiting for its session.
    if (repo.db.queryForList("SELECT id FROM invoices WHERE id=?", id).isEmpty()) {
      result(provider, "REVIEW", "INVOICE_DELETED");
      return;
    }
    repo.db.queryForList(
        "SELECT session_id FROM station_sessions WHERE session_id=(SELECT station_session_id FROM"
            + " invoices WHERE id=?) FOR UPDATE",
        id);
    var rows =
        repo.db.queryForList(
            "SELECT *,expires_at>clock_timestamp() AS valid FROM invoices WHERE id=? FOR UPDATE",
            id);
    if (rows.isEmpty()) {
      result(provider, "REVIEW", "INVOICE_DELETED");
      return;
    }
    var i = rows.getFirst();
    // Another recovery worker may have settled this receipt while we waited for the invoice.
    var current =
        repo.db.queryForMap(
            "SELECT result FROM bank_events WHERE provider_id=? FOR UPDATE", provider);
    if (!Set.of("REVIEW", "PENDING_CARD").contains(current.get("result"))) return;
    long amount = ((Number) e.get("amount")).longValue();
    Instant time = ((Timestamp) e.get("bank_transaction_at")).toInstant();
    if (!BankMemos.contains(ref, time)) {
      result(provider, "REVIEW", "OUTSIDE_WINDOW");
      return;
    }
    if ("PAID".equals(i.get("status"))) {
      result(provider, "REVIEW", "ALREADY_PAID");
      return;
    }
    if ("CANCELLED".equals(i.get("status"))) {
      result(provider, "REVIEW", "INVOICE_CLOSED");
      return;
    }
    if ("CARD_PROCESSING".equals(i.get("payment_phase"))
        && "PENDING_CARD".equals(current.get("result"))) {
      result(provider, "PENDING_CARD", "CARD_RESULT_PENDING");
      return;
    }
    Instant receivedNow =
        repo.db.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    if (i.get("outcome") != null || ref.get("closed_at") != null) {
      result(provider, "REVIEW", "INVOICE_CLOSED");
      return;
    }
    if (!receivedNow.isBefore(((Timestamp) ref.get("expires_at")).toInstant())
        || !receivedNow.isBefore(((Timestamp) i.get("expires_at")).toInstant())) {
      result(provider, "REVIEW", "REQUEST_EXPIRED");
      return;
    }
    if (amount != ((Number) ref.get("amount")).longValue()) {
      result(provider, "REVIEW", "AMOUNT_MISMATCH");
      return;
    }
    if ("CARD_PROCESSING".equals(i.get("payment_phase"))) {
      result(provider, "PENDING_CARD", "CARD_RESULT_PENDING");
      return;
    }
    if (!repo.db
        .queryForList(
            "SELECT id FROM wallet_operations WHERE invoice_id=? AND status<>'FAILED'", id)
        .isEmpty()) {
      result(provider, "PENDING_CARD", "CARD_RESULT_PENDING");
      return;
    }
    repo.db.update(
        "UPDATE payment_attempts SET decision='CANCEL',released=true WHERE invoice_id=? AND"
            + " debit_wallet_id IS NULL",
        id);
    repo.db.update(
        "UPDATE invoices SET status='PAID',outcome='SUCCESS',payment_method='BANK_TRANSFER',"
            + "payment_phase='FINISHED',paid_at=clock_timestamp(),revision=revision+1 WHERE id=?",
        id);
    result(provider, "MATCHED_INVOICE", null);
  }

  void reconcile() {
    for (String provider :
        repo.db.queryForList(
            "SELECT provider_id FROM bank_events WHERE result='PENDING_CARD' ORDER BY created_at"
                + " LIMIT 100",
            String.class)) repo.tx.executeWithoutResult(tx -> process(provider));
  }

  List<Api.InvoiceBankReceipt> reviews() {
    return repo.db.query(
        "SELECT e.*,r.invoice_number,r.amount AS expected FROM bank_events e LEFT JOIN"
            + " invoice_payment_references r ON r.reference=e.invoice_reference WHERE e.amount>0"
            + " AND e.result IN('REVIEW','PENDING_CARD') AND (e.payment_code LIKE 'TT%' OR"
            + " (e.payment_code IS NULL AND e.topup_reference IS NULL)) ORDER BY e.created_at DESC"
            + " LIMIT 200",
        (r, n) ->
            new Api.InvoiceBankReceipt(
                r.getString("provider_id"),
                Objects.toString(r.getString("invoice_number"), "Không xác định"),
                Objects.toString(r.getString("payment_code"), "Không xác định"),
                r.getLong("expected"),
                r.getLong("amount"),
                r.getString("result"),
                Objects.toString(r.getString("review_reason"), "UNMATCHED"),
                r.getTimestamp("created_at").toInstant().toString()));
  }
}
