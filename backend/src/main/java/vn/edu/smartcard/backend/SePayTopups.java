package vn.edu.smartcard.backend;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Each NT memo binds the full student identity to one retained top-up request. */
final class SePayTopups {
  private final CardRepository repo;
  private final SecurityMaterial security;
  private final SePayGateway gateway;
  private final InvoiceBankPayments invoices;

  SePayTopups(
      CardRepository repo,
      SecurityMaterial security,
      SePayGateway gateway,
      InvoiceBankPayments invoices) {
    this.repo = repo;
    this.security = security;
    this.gateway = gateway;
    this.invoices = invoices;
  }

  void register(long id, String cardId, String studentCode) {
    String memo = SePayGateway.paymentCode(studentCode, id);
    var existing =
        repo.db.queryForList(
            "SELECT s.* FROM sepay_topup_requests s JOIN topups t ON t.reference=s.reference WHERE"
                + " t.id=? AND t.card_id=?",
            id,
            cardId);
    if (!existing.isEmpty()) {
      if (!memo.equals(existing.getFirst().get("bank_memo")))
        throw Failure.bad("Yêu cầu nạp dùng mã cũ. Hủy chờ và tạo QR mới.");
      return;
    }
    int created =
        repo.db.update(
            "INSERT INTO"
                + " sepay_topup_requests(reference,bank_memo,original_card_id,amount,expires_at)"
                + " SELECT reference,?,?,amount,expires_at FROM topups WHERE id=? AND card_id=? AND"
                + " status='WAITING_BANK'",
            memo,
            cardId,
            id,
            cardId);
    if (created != 1) throw Failure.bad("Yêu cầu nạp không còn hiệu lực.");
  }

  void receive(String raw, String authorization) {
    var notice = gateway.verify(raw, authorization);
    if (notice.probe()) return;
    if (notice.paymentCode() != null && notice.paymentCode().startsWith("TT")) {
      invoices.receive(notice);
      return;
    }
    repo.tx.executeWithoutResult(
        tx -> {
          String memo = notice.paymentCode();
          if (memo != null) BankMemos.lock(repo, memo);
          var request = memo == null ? null : BankMemos.find(repo, BankMemos.Kind.TOPUP, memo);
          String ref = request == null ? null : (String) request.get("reference");
          String original = request == null ? null : (String) request.get("original_card_id");
          boolean registered =
              original != null
                  && !repo.db
                      .queryForList(
                          "SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", original)
                      .isEmpty();
          int inserted =
              repo.db.update(
                  "INSERT INTO"
                      + " bank_events(provider_id,bank_reference,account_token,amount,description,result,topup_reference,payment_code,bank_transaction_at,card_id,review_reason)"
                      + " VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                  notice.providerId(),
                  notice.bankReference(),
                  security.keyed("account:" + notice.account()),
                  notice.amount(),
                  notice.description(),
                  notice.amount() > 0 ? "REVIEW" : "IGNORED_OUTGOING",
                  ref,
                  memo,
                  Timestamp.from(notice.transactionTime()),
                  registered ? original : null,
                  memo == null ? "INVALID_TRANSFER_CODE" : "NO_ACTIVE_REQUEST");
          if (inserted == 0 || notice.amount() <= 0 || request == null) return;
          var rows =
              registered
                  ? repo.db.queryForList(
                      "SELECT * FROM topups WHERE reference=? AND card_id=? FOR UPDATE",
                      ref,
                      original)
                  : List.<Map<String, Object>>of();
          if (rows.isEmpty()) {
            new RefundStore(repo.db).acceptArchived(ref, notice.providerId(), notice.amount());
            return;
          }
          var topup = rows.getFirst();
          long id = ((Number) topup.get("id")).longValue();
          request =
              repo.db.queryForMap("SELECT * FROM sepay_topup_requests WHERE reference=?", ref);
          Instant now =
              repo.db.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
          boolean unexpired =
              now.isBefore(((Timestamp) request.get("expires_at")).toInstant())
                  && now.isBefore(((Timestamp) topup.get("expires_at")).toInstant());
          String reason = null;
          if (!"WAITING_BANK".equals(topup.get("status")) || request.get("closed_at") != null)
            reason = "REQUEST_CLOSED";
          else if (!unexpired) {
            repo.db.update(
                "UPDATE topups SET status='EXPIRED' WHERE id=? AND status='WAITING_BANK'", id);
            reason = "REQUEST_EXPIRED";
          } else if (!BankMemos.contains(request, notice.transactionTime()))
            reason = "OUTSIDE_WINDOW";
          else if (((Number) topup.get("amount")).longValue() != notice.amount()) {
            repo.db.update(
                "UPDATE topups SET status='REVIEW' WHERE id=? AND status='WAITING_BANK'", id);
            reason = "AMOUNT_MISMATCH";
          }
          if (reason != null) {
            repo.db.update(
                "UPDATE bank_events SET review_reason=? WHERE provider_id=?",
                reason,
                notice.providerId());
            return;
          }
          repo.db.update(
              "UPDATE bank_events SET topup_id=?,result='MATCHED',review_reason=NULL WHERE"
                  + " provider_id=?",
              id,
              notice.providerId());
          repo.db.update("UPDATE topups SET status='BANK_RECEIVED' WHERE id=?", id);
        });
  }
}
