package vn.edu.smartcard.backend;

import java.util.*;
import vn.edu.smartcard.shared.*;

/** Invoice outcome is independent of the immutable card-operation decision. */
final class PaymentCoordinator {
  private final CardRepository repo;
  private final ReaderHub readers;
  private final CardSynchronizer sync;
  private final StationSessions stations;

  PaymentCoordinator(
      CardRepository repo, ReaderHub readers, CardSynchronizer sync, StationSessions stations) {
    this.repo = repo;
    this.readers = readers;
    this.sync = sync;
    this.stations = stations;
  }

  private static long n(Map<String, Object> row, String field) {
    return ((Number) row.get(field)).longValue();
  }

  void begin(String terminal, long invoice, String card) {
    String connection = readers.connection(terminal);
    repo.tx.executeWithoutResult(
        tx -> {
          stations.requireActive(terminal, true);
          repo.db.queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", card);
          repo.requireActive(card);
          var i =
              repo.db.queryForMap(
                  "SELECT *,expires_at>clock_timestamp() AS valid FROM invoices WHERE id=? FOR"
                      + " UPDATE",
                  invoice);
          if (!"OPEN".equals(i.get("status"))
              || i.get("outcome") != null
              || !Boolean.TRUE.equals(i.get("valid"))) throw closed();
          var stored = repo.find(card);
          if ((i.get("card_id") != null && !card.equals(i.get("card_id")))
              || (i.get("student_code") != null
                  && !stored.current().studentCode().equals(i.get("student_code"))))
            throw new Failure(409, "WRONG_STUDENT", "Thẻ không thuộc sinh viên trên hóa đơn.");
          repo.db.update(
              "UPDATE invoices SET card_id=?,student_code=?,full_name=? WHERE id=?",
              card,
              stored.current().studentCode(),
              stored.current().fullName(),
              invoice);
          repo.db.update(
              "INSERT INTO payment_attempts(invoice_id,attempt_id,connection_id)"
                  + " VALUES(?,?,?::uuid)",
              invoice,
              UUID.randomUUID(),
              connection);
          repo.db.update(
              "UPDATE invoices SET"
                  + " status='PAYMENT_PENDING',payment_phase='CARD_AUTH',revision=revision+1 WHERE"
                  + " id=?",
              invoice);
        });
  }

  void requireAttempt(String terminal, long invoice) {
    stations.requireOwner(terminal, invoice);
    stations.requireActive(terminal, false);
    var rows =
        repo.db.queryForList(
            "SELECT a.*,i.status,i.outcome,i.expires_at>clock_timestamp() AS valid FROM"
                + " payment_attempts a JOIN invoices i ON i.id=a.invoice_id WHERE invoice_id=?",
            invoice);
    if (rows.isEmpty()) throw closed();
    var row = rows.getFirst();
    if (!"PAYMENT_PENDING".equals(row.get("status")) || row.get("outcome") != null) throw closed();
    if (!Boolean.TRUE.equals(row.get("valid")))
      throw new Failure(409, "QR_EXPIRED", "Hóa đơn hết hạn. Vui lòng tạo hóa đơn mới.");
    if (!readers.connection(terminal).equals(row.get("connection_id").toString()))
      throw new Failure(
          409, "CARD_CONNECTION", "Kết nối thẻ đã đổi. Vui lòng xác thực lại cho hóa đơn này.");
  }

  void rebindAuthentication(String terminal, long invoice, String card) {
    String connection = readers.connection(terminal);
    repo.tx.executeWithoutResult(tx -> {
      stations.requireActive(terminal, true);
      repo.db.queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", card);
      repo.requireActive(card);
      var row = repo.db.queryForMap(
          "SELECT i.status,i.outcome,i.card_id,i.payment_phase,i.expires_at>clock_timestamp() AS valid,"
              + " a.debit_wallet_id,a.decision FROM invoices i JOIN payment_attempts a"
              + " ON a.invoice_id=i.id WHERE i.id=? FOR UPDATE OF i,a", invoice);
      if (!"PAYMENT_PENDING".equals(row.get("status")) || row.get("outcome") != null
          || !"CARD_AUTH".equals(row.get("payment_phase")) || !card.equals(row.get("card_id"))
          || row.get("debit_wallet_id") != null || row.get("decision") != null) throw closed();
      if (!Boolean.TRUE.equals(row.get("valid")))
        throw new Failure(409, "QR_EXPIRED", "Hóa đơn hết hạn. Vui lòng tạo hóa đơn mới.");
      repo.db.update("UPDATE payment_attempts SET connection_id=?::uuid WHERE invoice_id=?",
          connection, invoice);
    });
  }

  boolean resumeAfterFailure(long invoice) {
    return Boolean.TRUE.equals(repo.tx.execute(tx -> {
      stations.lockInvoiceSession(invoice, false);
      var rows = repo.db.queryForList(
          "SELECT i.status,i.outcome,i.payment_phase,a.debit_wallet_id FROM invoices i"
              + " JOIN payment_attempts a ON a.invoice_id=i.id WHERE i.id=? FOR UPDATE OF i,a",
          invoice);
      if (rows.isEmpty()) return false;
      var row = rows.getFirst();
      if ("PAYMENT_PENDING".equals(row.get("status")) && row.get("outcome") == null
          && "CARD_PROCESSING".equals(row.get("payment_phase")) && row.get("debit_wallet_id") == null) {
        repo.db.update("UPDATE invoices SET payment_phase='CARD_AUTH',revision=revision+1 WHERE id=?", invoice);
        return true;
      }
      return false;
    }));
  }

  void endAuthentication(long invoice) {
    repo.tx.executeWithoutResult(tx -> {
      stations.lockInvoiceSession(invoice, false);
      var rows = repo.db.queryForList(
          "SELECT i.status,i.outcome,i.payment_phase,a.debit_wallet_id FROM invoices i"
              + " LEFT JOIN payment_attempts a ON a.invoice_id=i.id WHERE i.id=? FOR UPDATE OF i",
          invoice);
      if (rows.isEmpty()) throw Failure.missing("Không tìm thấy hóa đơn.");
      var row = rows.getFirst();
      if (row.get("outcome") != null || !"PAYMENT_PENDING".equals(row.get("status"))) return;
      if (!"CARD_AUTH".equals(row.get("payment_phase")) || row.get("debit_wallet_id") != null)
        throw new Failure(409, "CARD_PROCESSING", "Đang xử lý thanh toán. Vui lòng chờ kết quả.");
      repo.db.update("DELETE FROM payment_attempts WHERE invoice_id=? AND debit_wallet_id IS NULL", invoice);
      // The reserved guest memo proves the card was attached only for this attempt. An actual
      // AT000000 identity is kept bound, because older data cannot distinguish that case.
      boolean guest = Boolean.TRUE.equals(repo.db.queryForObject(
          "SELECT EXISTS(SELECT 1 FROM invoices i JOIN invoice_payment_references r ON r.invoice_id=i.id"
              + " WHERE i.id=? AND r.payment_code LIKE 'TTAT000000%' AND i.student_code<>'AT000000')",
          Boolean.class, invoice));
      if (guest) repo.db.update("UPDATE invoices SET card_id=NULL,student_code=NULL,full_name=NULL WHERE id=?", invoice);
      repo.db.update("UPDATE invoices SET status='OPEN',payment_phase='NONE',revision=revision+1 WHERE id=?", invoice);
    });
  }

  void claimCardProcessing(String terminal, long invoice, String card) {
    repo.tx.executeWithoutResult(tx -> {
      stations.requireActive(terminal, true);
      repo.db.queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", card);
      repo.requireActive(card);
      var row = repo.db.queryForMap(
          "SELECT status,outcome,card_id,payment_phase,expires_at>clock_timestamp() AS valid"
              + " FROM invoices WHERE id=? FOR UPDATE", invoice);
      if (!"PAYMENT_PENDING".equals(row.get("status")) || row.get("outcome") != null
          || !card.equals(row.get("card_id")) || !"CARD_AUTH".equals(row.get("payment_phase")))
        throw closed();
      if (!Boolean.TRUE.equals(row.get("valid")))
        throw new Failure(409, "QR_EXPIRED", "Hóa đơn hết hạn. Vui lòng tạo hóa đơn mới.");
      repo.db.update("UPDATE invoices SET payment_phase='CARD_PROCESSING',revision=revision+1 WHERE id=?",
          invoice);
    });
  }

  void debit(
      String terminal, CardClient card, CardRepository.Stored stored, long invoice, long amount) {
    requireAttempt(terminal, invoice);
    Protocol.State state = sync.settled(card, stored);
    if (state.balance() < amount)
      throw new Failure(409, "FUNDS", "Số dư trên thẻ không đủ!");
    long wallet =
        repo.tx.execute(
            tx -> {
              stations.requireActive(terminal, true);
              repo.db.queryForList(
                  "SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", stored.id());
              repo.requireActive(stored.id());
              var i =
                  repo.db.queryForMap(
                      "SELECT status,outcome,card_id,total FROM invoices WHERE id=? FOR UPDATE",
                      invoice);
              if (!"PAYMENT_PENDING".equals(i.get("status"))
                  || i.get("outcome") != null
                  || !stored.id().equals(i.get("card_id"))
                  || n(i, "total") != amount) throw closed();
              var a =
                  repo.db.queryForMap(
                      "SELECT * FROM payment_attempts WHERE invoice_id=? FOR UPDATE", invoice);
              if (a.get("debit_wallet_id") != null) throw closed();
              long id =
                  repo.db.queryForObject(
                      "INSERT INTO wallet_operations(card_id,kind,amount,invoice_id,status)"
                          + " VALUES(?,'DEBIT',?,?,'PENDING') RETURNING id",
                      Long.class,
                      stored.id(),
                      amount,
                      invoice);
              repo.db.update(
                  "UPDATE payment_attempts SET"
                      + " before_balance=?,before_wallet_version=?,debit_wallet_id=? WHERE"
                      + " invoice_id=?",
                  state.balance(),
                  state.walletVersion(),
                  id,
                  invoice);
              repo.db.update(
                  "UPDATE invoices SET payment_phase='CARD_PROCESSING',revision=revision+1 WHERE"
                      + " id=?",
                  invoice);
              return id;
            });
    try {
      sync.run(
          card,
          stored,
          Wire.DEBIT,
          Wire.operation(wallet, amount),
          null,
          null,
          wallet,
          () -> {
            repo.requireActive(stored.id());
            readers.requireStudent(terminal);
            requireAttempt(terminal, invoice);
          });
    } catch (RuntimeException failure) {
      repo.db.update(
          "UPDATE wallet_operations SET status='FAILED' WHERE id=? AND status='PENDING' AND NOT"
              + " EXISTS(SELECT 1 FROM card_operations WHERE wallet_operation_id=? AND phase NOT"
              + " IN('ABORTED'))",
          wallet,
          wallet);
      throw failure;
    }
    sync.checkpoint("PAYMENT_READY");
    boolean success =
        repo.tx.execute(
            tx -> {
              stations.lockInvoiceSession(invoice, false);
              repo.db.queryForList(
                  "SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", stored.id());
              var i = repo.db.queryForMap("SELECT * FROM invoices WHERE id=? FOR UPDATE", invoice);
              if ("SUCCESS".equals(i.get("outcome"))) return true;
              if ("CANCEL".equals(i.get("outcome"))) return false;
              long applied =
                  repo.db.queryForObject(
                      "SELECT count(*) FROM card_operations WHERE wallet_operation_id=? AND"
                          + " phase='SYNCED'",
                      Long.class,
                      wallet);
              if (applied != 1) throw pending();
              repo.db.update(
                  "UPDATE payment_attempts SET decision='SUCCESS' WHERE invoice_id=? AND decision"
                      + " IS NULL",
                  invoice);
              repo.db.update(
                  "UPDATE invoices SET"
                      + " status='PAID',outcome='SUCCESS',payment_method='CARD',payment_phase='FINISHED',paid_at=clock_timestamp(),revision=revision+1"
                      + " WHERE id=? AND outcome IS NULL",
                  invoice);
              return true;
            });
    if (!success) {
      recover(card, stored);
      throw closed();
    }
    sync.checkpoint("PAYMENT_SUCCESS");
    release(card, invoice);
  }

  void cancel(long invoice, String reason) {
    stations.cancel(invoice, reason);
    finishEmptyCancellations();
  }

  void finishEmptyCancellations() {
    repo.tx.executeWithoutResult(
        tx -> {
          repo.db.update(
              "UPDATE wallet_operations w SET status='FAILED' WHERE w.kind='DEBIT' AND"
                  + " w.status='PENDING' AND EXISTS(SELECT 1 FROM invoices i WHERE"
                  + " i.id=w.invoice_id AND i.outcome='CANCEL') AND NOT EXISTS(SELECT 1 FROM"
                  + " card_operations c WHERE c.wallet_operation_id=w.id AND c.phase<>'ABORTED')");
          var ids =
              repo.db.queryForList(
                  "UPDATE invoices i SET"
                      + " status='CANCELLED',payment_phase='FINISHED',cancelled_at=clock_timestamp(),revision=revision+1"
                      + " WHERE i.status='CANCEL_PENDING' AND i.outcome='CANCEL' AND NOT"
                      + " EXISTS(SELECT 1 FROM wallet_operations w WHERE w.invoice_id=i.id AND"
                      + " w.status<>'FAILED') RETURNING id",
                  Long.class);
          for (long id : ids)
            repo.db.update("UPDATE payment_attempts SET released=true WHERE invoice_id=?", id);
        });
  }

  boolean cancelling(String card) {
    return repo.db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM invoices WHERE card_id=? AND status='CANCEL_PENDING')",
        Boolean.class,
        card);
  }

  /** Correct-card ECC has already been verified by CardService, even if BLOCKED. */
  void recover(CardClient card, CardRepository.Stored stored) {
    sync.recover(card, stored);
    sync.repairWalletRecords(stored.id());
    finishEmptyCancellations();
    var attempts =
        repo.db.queryForList(
            "SELECT a.*,i.outcome,i.status,i.payment_phase FROM payment_attempts a JOIN invoices i ON"
                + " i.id=a.invoice_id WHERE i.card_id=? AND a.released=false ORDER BY i.id",
            stored.id());
    for (var a : attempts) {
      long invoice = n(a, "invoice_id");
      if ("SUCCESS".equals(a.get("outcome"))) {
        release(card, invoice);
        continue;
      }
      if (a.get("debit_wallet_id") == null) {
        if (a.get("outcome") == null && "CARD_PROCESSING".equals(a.get("payment_phase")))
          resumeAfterFailure(invoice);
        continue;
      }
      if (a.get("outcome") == null) {
        recoverUnfinishedPayment(card, stored, invoice, a);
        continue;
      }
      undo(card, stored, invoice, a);
    }
    // Legacy pending debit has no new hold/attempt: retain the evidence, stop new operations.
    long legacy =
        repo.db.queryForObject(
            "SELECT count(*) FROM invoices i WHERE i.card_id=? AND i.status='CANCEL_PENDING' AND"
                + " NOT EXISTS(SELECT 1 FROM payment_attempts a WHERE a.invoice_id=i.id)",
            Long.class,
            stored.id());
    if (legacy > 0)
      throw new Failure(
          409,
          "SYNC_CONFLICT",
          "Có debit từ applet cũ chưa xác định. Giữ chứng từ để đối soát, không ghi đè số dư.");
  }

  private void recoverUnfinishedPayment(
      CardClient card, CardRepository.Stored stored, long invoice, Map<String, Object> attempt) {
    long debit = n(attempt, "debit_wallet_id");
    var wallet = repo.db.queryForMap("SELECT * FROM wallet_operations WHERE id=?", debit);
    var ops = repo.db.queryForList(
        "SELECT id,phase FROM card_operations WHERE wallet_operation_id=? ORDER BY id DESC LIMIT 1", debit);
    if ("PENDING".equals(wallet.get("status")) && ops.isEmpty()) {
      Protocol.State state = sync.settled(card, stored);
      if (state.financialHold() != 0 || state.balance() != n(attempt, "before_balance")
          || state.walletVersion() != n(attempt, "before_wallet_version")) throw conflict();
      repo.db.update("UPDATE wallet_operations SET status='FAILED' WHERE id=? AND status='PENDING'", debit);
      wallet = repo.db.queryForMap("SELECT * FROM wallet_operations WHERE id=?", debit);
    }
    if ("FAILED".equals(wallet.get("status"))) {
      Protocol.State state = sync.settled(card, stored);
      if (state.financialHold() != 0 || state.balance() != n(attempt, "before_balance")
          || state.walletVersion() != n(attempt, "before_wallet_version")) throw conflict();
      restoreFailedDebit(stored.id(), invoice, debit);
      return;
    }
    if (!"APPLIED".equals(wallet.get("status")) || ops.isEmpty()
        || !"SYNCED".equals(ops.getFirst().get("phase"))) throw pending();
    Protocol.State state = sync.financialState(card, stored);
    long amount = n(wallet, "amount"), before = n(attempt, "before_balance");
    if (state.financialHold() != 1 || state.debitId() != n(ops.getFirst(), "id")
        || state.debitBeforeBalance() != before || state.debitAmount() != amount
        || state.balance() != before - amount) throw conflict();
    boolean success = repo.tx.execute(tx -> {
      stations.lockInvoiceSession(invoice, false);
      repo.db.queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", stored.id());
      var row = repo.db.queryForMap("SELECT * FROM invoices WHERE id=? FOR UPDATE", invoice);
      if ("CANCEL".equals(row.get("outcome"))) return false;
      if ("SUCCESS".equals(row.get("outcome"))) return true;
      if (!"PAYMENT_PENDING".equals(row.get("status"))
          || !"CARD_PROCESSING".equals(row.get("payment_phase"))
          || !stored.id().equals(row.get("card_id")) || n(row, "total") != amount) throw conflict();
      var current = repo.db.queryForMap("SELECT * FROM payment_attempts WHERE invoice_id=? FOR UPDATE", invoice);
      if (current.get("debit_wallet_id") == null || n(current, "debit_wallet_id") != debit
          || current.get("decision") != null) throw conflict();
      repo.db.update("UPDATE payment_attempts SET decision='SUCCESS' WHERE invoice_id=?", invoice);
      repo.db.update("UPDATE invoices SET status='PAID',outcome='SUCCESS',payment_method='CARD',"
          + "payment_phase='FINISHED',paid_at=clock_timestamp(),revision=revision+1 WHERE id=?", invoice);
      return true;
    });
    if (success) release(card, invoice);
    else undo(card, stored, invoice,
        repo.db.queryForMap("SELECT * FROM payment_attempts WHERE invoice_id=?", invoice));
  }

  private void restoreFailedDebit(String card, long invoice, long debit) {
    repo.tx.executeWithoutResult(tx -> {
      stations.lockInvoiceSession(invoice, false);
      repo.db.queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", card);
      var row = repo.db.queryForMap("SELECT status,outcome,payment_phase FROM invoices WHERE id=? FOR UPDATE", invoice);
      if (row.get("outcome") != null) return;
      if (!"PAYMENT_PENDING".equals(row.get("status"))
          || !"CARD_PROCESSING".equals(row.get("payment_phase"))) throw conflict();
      var attempt = repo.db.queryForMap("SELECT * FROM payment_attempts WHERE invoice_id=? FOR UPDATE", invoice);
      if (attempt.get("debit_wallet_id") == null || n(attempt, "debit_wallet_id") != debit
          || attempt.get("decision") != null) throw conflict();
      if (repo.db.update("UPDATE wallet_operations SET legacy_invoice_id=invoice_id,invoice_id=NULL"
          + " WHERE id=? AND invoice_id=? AND status='FAILED'", debit, invoice) != 1) throw conflict();
      repo.db.update("UPDATE payment_attempts SET debit_wallet_id=NULL,before_balance=NULL,"
          + "before_wallet_version=NULL WHERE invoice_id=?", invoice);
      repo.db.update("UPDATE invoices SET payment_phase='CARD_AUTH',revision=revision+1 WHERE id=?", invoice);
    });
  }

  private void undo(
      CardClient card, CardRepository.Stored stored, long invoice, Map<String, Object> attempt) {
    long debit = n(attempt, "debit_wallet_id");
    var w = repo.db.queryForMap("SELECT * FROM wallet_operations WHERE id=?", debit);
    if ("FAILED".equals(w.get("status"))) {
      finishEmptyCancellations();
      return;
    }
    var ops =
        repo.db.queryForList(
            "SELECT * FROM card_operations WHERE wallet_operation_id=? ORDER BY id DESC LIMIT 1",
            debit);
    if (ops.isEmpty()) {
      // Cancellation won before a card operation could be created.
      repo.db.update(
          "UPDATE wallet_operations SET status='FAILED' WHERE id=? AND status='PENDING'", debit);
      finishEmptyCancellations();
      return;
    }
    var original = ops.getFirst();
    if ("ABORTED".equals(original.get("phase"))) {
      finishEmptyCancellations();
      return;
    }
    if (!"SYNCED".equals(original.get("phase"))) throw pending();
    long origin = n(original, "id"), amount = n(w, "amount"), before = n(attempt, "before_balance");
    Protocol.State state = sync.financialState(card, stored);
    if (state.debitId() != origin
        || state.debitBeforeBalance() != before
        || state.debitAmount() != amount) throw conflict();
    if (state.financialHold() == 1) {
      Long reverse = (Long) attempt.get("reversal_wallet_id");
      if (reverse == null) {
        reverse =
            repo.tx.execute(
                tx -> {
                  stations.lockInvoiceSession(invoice, false);
                  repo.db.queryForList(
                      "SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", stored.id());
                  repo.db.queryForList("SELECT id FROM invoices WHERE id=? FOR UPDATE", invoice);
                  if (!"CANCEL"
                      .equals(
                          repo.db.queryForObject(
                              "SELECT outcome FROM invoices WHERE id=?", String.class, invoice)))
                    throw conflict();
                  var prior =
                      repo.db.queryForList(
                          "SELECT id FROM wallet_operations WHERE parent_id=?", Long.class, debit);
                  long id =
                      prior.isEmpty()
                          ? repo.db.queryForObject(
                              "INSERT INTO"
                                  + " wallet_operations(card_id,kind,amount,invoice_id,parent_id,status)"
                                  + " VALUES(?,'REVERSAL',?,?,?,'PENDING') RETURNING id",
                              Long.class,
                              stored.id(),
                              amount,
                              invoice,
                              debit)
                          : prior.getFirst();
                  repo.db.update(
                      "UPDATE payment_attempts SET reversal_wallet_id=? WHERE invoice_id=?",
                      id,
                      invoice);
                  return id;
                });
      }
      var reverseOp =
          repo.db.queryForList(
              "SELECT phase FROM card_operations WHERE wallet_operation_id=?", reverse);
      if (reverseOp.isEmpty() || "ABORTED".equals(reverseOp.getFirst().get("phase"))) {
        repo.db.update(
            "UPDATE wallet_operations SET status='PENDING' WHERE id=? AND status='FAILED'",
            reverse);
        // An aborted card-operation row must release its one-to-one wallet link before a retry.
        repo.db.update(
            "UPDATE card_operations SET wallet_operation_id=NULL WHERE wallet_operation_id=? AND"
                + " phase='ABORTED'",
            reverse);
        sync.run(
            card,
            stored,
            Wire.REVERSAL,
            Wire.operation(origin, amount),
            null,
            null,
            reverse,
            () -> {});
      } else sync.recover(card, stored);
      sync.checkpoint("REVERSAL_APPLIED");
      state = sync.financialState(card, stored);
    }
    // RELEASE may have succeeded just before a crash; balance + the retained debit ID still prove
    // it.
    if (state.balance() != before || state.financialHold() == 1) throw conflict();
    long mirror =
        repo.db.queryForObject(
            "SELECT balance FROM card_wallets WHERE card_id=?", Long.class, stored.id());
    long reversed =
        repo.db.queryForObject(
            "SELECT count(*) FROM wallet_operations WHERE parent_id=? AND kind='REVERSAL' AND"
                + " status='APPLIED'",
            Long.class,
            debit);
    if (mirror != before || reversed != 1) throw conflict();
    card.complete(Protocol.RELEASE, stored.id(), origin);
    sync.checkpoint("REVERSAL_RELEASED");
    repo.tx.executeWithoutResult(
        tx -> {
          stations.lockInvoiceSession(invoice, false);
          repo.db.queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", stored.id());
          repo.db.queryForList("SELECT id FROM invoices WHERE id=? FOR UPDATE", invoice);
          repo.db.update(
              "UPDATE payment_attempts SET decision='CANCEL',released=true WHERE invoice_id=?",
              invoice);
          repo.db.update(
              "UPDATE invoices SET"
                  + " status='CANCELLED',outcome='CANCEL',cancelled_at=clock_timestamp(),revision=revision+1"
                  + " WHERE id=? AND outcome='CANCEL'",
              invoice);
        });
  }

  private void release(CardClient card, long invoice) {
    var rows =
        repo.db.queryForList(
            "SELECT c.id,c.card_id FROM payment_attempts a JOIN card_operations c ON"
                + " c.wallet_operation_id=a.debit_wallet_id WHERE a.invoice_id=? AND"
                + " c.phase='SYNCED'",
            invoice);
    if (rows.isEmpty()) throw conflict();
    var op = rows.getFirst();
    Protocol.State state = card.state();
    if (state.debitId() != n(op, "id") || state.financialHold() == 2) throw conflict();
    card.complete(Protocol.RELEASE, (String) op.get("card_id"), n(op, "id"));
    sync.checkpoint("PAYMENT_RELEASED");
    repo.db.update(
        "UPDATE payment_attempts SET released=true WHERE invoice_id=? AND decision='SUCCESS'",
        invoice);
  }

  private static Failure closed() {
    return new Failure(
        409, "INVOICE_CLOSED", "Hóa đơn không còn sẵn sàng cho thao tác này. Hãy kiểm tra lại trạng thái.");
  }

  private static Failure pending() {
    return new Failure(
        409,
        "CANCEL_PENDING",
        "Kết quả thanh toán đang chờ đối soát. Kết nối đúng thẻ và chọn Đồng bộ lại; không thanh toán"
            + " lại.");
  }

  private static Failure conflict() {
    return new Failure(
        409,
        "SYNC_CONFLICT",
        "Không xác định được khoản trừ/hoàn bù gốc. Giữ nguyên số dư để đối soát.");
  }
}
