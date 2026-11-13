package vn.edu.smartcard.backend;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vn.edu.smartcard.shared.*;

@Service
public class CardService {
  final ReaderHub readers;
  final CardRepository repo;
  final SecurityMaterial security;
  final CardSynchronizer sync;
  final StationSessions stations;
  final PaymentCoordinator payments;
  final RefundStore refunds;

  @Autowired
  public CardService(
      ReaderHub readers, CardRepository repo, SecurityMaterial security, CardSynchronizer sync) {
    this.readers = readers;
    this.repo = repo;
    this.security = security;
    this.sync = sync;
    this.stations = new StationSessions(repo);
    this.payments = new PaymentCoordinator(repo, readers, sync, stations);
    this.refunds = new RefundStore(repo.db);
    readers.lifecycle(repo::lifecycle);
  }

  public CardService(ReaderHub readers, CardRepository repo, SecurityMaterial security) {
    this(readers, repo, security, new CardSynchronizer(repo, security));
  }

  public CardRepository.Stored registered(CardClient card) {
    var status = card.status("");
    if (!status.initialized()) throw Failure.bad("Thẻ chưa được đăng ký. Vui lòng liên hệ admin.");
    var stored = repo.find(status.cardId());
    if (stored == null)
      throw new Failure(409, "UNKNOWN_CARD", "Thẻ chưa có hồ sơ tương ứng trong hệ thống.");
    card.authenticate(repo.key(stored), stored.id(), 1, 0, false);
    payments.recover(card, stored);
    sync.settled(card, stored);
    sync.repairWalletRecords(stored.id());
    return repo.find(stored.id());
  }

  void authenticateConnection(CardClient card, Api.ReaderStatus status) {
    CardRepository.Stored stored = status.initialized() ? repo.find(status.cardId()) : null;
    if (status.initialized() && stored == null) throw connectionAuthenticationFailed();
    try {
      if (stored == null) {
        // A blank card can prove possession of its key, but has no enrolled identity yet.
        card.authenticate(card.publicKey(), "", 0, 0, true);
      } else {
        card.authenticate(repo.key(stored), stored.id(), 1, 0, true);
      }
    } catch (RuntimeException failure) {
      throw connectionAuthenticationFailed();
    }
  }

  private static Failure connectionAuthenticationFailed() {
    return new Failure(403, "CARD_AUTH", "Xác thực thẻ không thành công!");
  }

  public CardRepository.Stored student(CardClient card) {
    var stored = registered(card);
    repo.requireActive(stored.id());
    readers.requireStudentCard(card);
    if (!stored.status().equals("ACTIVE"))
      throw new Failure(409, "SYNC_REQUIRED", "Thẻ đang chờ đồng bộ.");
    if (!card.status("").role().equals("STUDENT"))
      throw new Failure(401, "PIN_SESSION", "Vui lòng xác thực PIN.");
    return stored;
  }

  private void unlock(CardClient card, CardRepository.Stored stored, boolean admin) {
    byte[] share = repo.share(stored.id());
    try {
      card.unlock(stored.id(), share, admin);
    } finally {
      Arrays.fill(share, (byte) 0);
    }
  }

  public Api.ReaderStatus prepareStudent(String terminal) {
    return readers.useTopup(
        terminal,
        c -> {
          var s = registered(c);
          repo.requireActive(s.id());
          if (!readers.managementPrepared(terminal)) {
            unlock(c, s, false);
            readers.markManagementPrepared(terminal);
          }
          return readers.status(terminal);
        });
  }

  public Api.ReaderStatus prepareCheckout(String terminal) {
    stations.requireActive(terminal, false);
    return readers.use(terminal, card -> {
      var stored = registered(card);
      repo.requireActive(stored.id());
      return readers.status(terminal);
    });
  }

  public Api.StudentHome login(String terminal, Api.Pin input) {
    Api.StudentHome home = readers.useTopup(
        terminal,
        card -> {
          var stored = registered(card);
          repo.requireActive(stored.id());
          if (!readers.managementPrepared(terminal)) {
            unlock(card, stored, false);
            readers.markManagementPrepared(terminal);
          }
          readers.beginPinAttempt(terminal);
          verifyStudentPin(terminal, card, stored, input, "MANAGEMENT");
          readers.studentSession(terminal);
          return new Api.StudentHome(card.profile(), card.balance());
        });
    readers.signalStudentReady();
    return home;
  }

  public Api.StudentHome login(String terminal, String pin) {
    return login(terminal, new Api.Pin(pin));
  }

  public Api.StudentHome home(String terminal) {
    return readers.use(
        terminal,
        card -> {
          student(card);
          return new Api.StudentHome(card.profile(), card.balance());
        });
  }

  public Api.CardTransactionPage transactions(String terminal, Long beforeId) {
    if (beforeId != null && beforeId <= 0) throw Failure.bad("Mốc lịch sử giao dịch không hợp lệ.");
    return withStudentTransactions(terminal, cardId -> repo.transactions(cardId, beforeId));
  }

  public Api.CardTransactionListPage transactionPage(String terminal, int page) {
    if (page < 0) throw Failure.bad("Trang lịch sử giao dịch không hợp lệ.");
    return withStudentTransactions(terminal, cardId -> repo.transactionPage(cardId, page));
  }

  public Api.CardTransactionListPage adminTransactionPage(String cardId, int page) {
    AccessContext.requireAdmin();
    if (page < 0) throw Failure.bad("Trang lịch sử giao dịch không hợp lệ.");
    var stored = repo.find(cardId);
    if (stored == null || stored.current() == null) throw Failure.missing("Không tìm thấy hồ sơ.");
    return repo.transactionPage(stored.id(), page);
  }

  private <T> T withStudentTransactions(String terminal, Function<String, T> fetch) {
    return readers.use(
        terminal,
        ignored -> {
          Api.ReaderStatus status = readers.requireStudent(terminal);
          if (!readers.isCurrentStudent(terminal)) throw Failure.denied();
          repo.requireActive(status.cardId());
          return fetch.apply(status.cardId());
        });
  }

  /** Card produces the result; SQL counts each attempt exactly once. No PIN is persisted. */
  void verifyStudentPin(
      String terminal,
      CardClient card,
      CardRepository.Stored stored,
      Api.Pin input,
      String purpose) {
    Wire.pin(input.pin());
    UUID attempt;
    try {
      attempt = UUID.fromString(input.attemptId());
    } catch (Exception e) {
      throw Failure.bad("Mã lần xác thực PIN không hợp lệ.");
    }
    String connection = readers.connection(terminal);
    String prior =
        repo.tx.execute(t -> {
          repo.db.queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", stored.id());
          repo.requireActive(stored.id());
          var rows = repo.db.queryForList("SELECT * FROM pin_attempts WHERE attempt_id=?", attempt);
          if (!rows.isEmpty()) {
            var row = rows.getFirst();
            if (!stored.id().equals(row.get("card_id"))
                || !terminal.equals(row.get("terminal_id"))
                || !connection.equals(row.get("connection_id").toString())
                || !purpose.equals(row.get("purpose"))) throw Failure.denied();
            return (String) row.get("result");
          }
          repo.db.update(
              "INSERT INTO"
                  + " pin_attempts(attempt_id,card_id,connection_id,terminal_id,purpose,result)"
                  + " VALUES (?,?,?::uuid,?,?,'STARTED')",
              attempt,
              stored.id(),
              connection,
              terminal,
              purpose);
          return null;
        });
    if (prior != null) {
      if (prior.equals("WRONG")) throw wrong(stored.id());
      if (prior.equals("OK") && card.status("").role().equals("STUDENT")) return;
      throw new Failure(
          409,
          "PIN_RETRY",
          "Không xác định được ngữ cảnh lần thử cũ. Vui lòng thực hiện lần xác thực mới.");
    }
    boolean ok;
    try {
      ok = card.verifyPin(input.pin());
    } catch (RuntimeException e) {
      repo.db.update(
          "UPDATE pin_attempts SET result='UNKNOWN' WHERE attempt_id=? AND result='STARTED'",
          attempt);
      try {
        readers.endStudent(terminal);
      } catch (RuntimeException ignored) {
      }
      throw e;
    }
    boolean active =
        repo.tx.execute(t -> {
          repo.db.queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", stored.id());
          Api.CardLifecycle policy = repo.lifecycle(stored.id());
          repo.db.update(
              "UPDATE pin_attempts SET result=?,applied=true WHERE attempt_id=? AND applied=false",
              ok ? "OK" : "WRONG",
              attempt);
          if (!policy.status().equals("ACTIVE")) return false;
          if (ok) repo.db.update("UPDATE cards SET pin_failures=0 WHERE card_id=?", stored.id());
          else {
            int count = Math.min(5, policy.pinFailures() + 1);
            repo.db.update(
                "UPDATE cards SET pin_failures=?,lifecycle_status=?,block_reason=?,blocked_at=CASE"
                    + " WHEN ? THEN now() ELSE NULL END WHERE card_id=?",
                count,
                count == 5 ? "BLOCKED" : "ACTIVE",
                count == 5 ? "PIN_FAILURES" : null,
                count == 5,
                stored.id());
          }
          return repo.lifecycle(stored.id()).status().equals("ACTIVE");
        });
    if (!active) {
      readers.invalidate(stored.id());
      throw new Failure(423, "CARD_BLOCKED", CardRepository.BLOCKED_MESSAGE);
    }
    if (!ok) throw wrong(stored.id());
  }

  private Failure wrong(String id) {
    return new Failure(
        401,
        "PIN_WRONG",
        "Mã PIN không đúng. Còn "
            + (5 - repo.lifecycle(id).pinFailures())
            + " lần thử. Quên mã PIN? Liên hệ admin để được hỗ trợ.");
  }

  public void changePin(String terminal, Api.PinChange input) {
    Wire.pin(input.newPin());
    readers.use(
        terminal,
        card -> {
          var stored = student(card);
          byte[] data = input.newPin().getBytes(StandardCharsets.US_ASCII);
          try {
            sync.run(
                card,
                stored,
                Wire.CHANGE_PIN,
                data,
                null,
                null,
                null,
                () -> {
                  repo.requireActive(stored.id());
                  readers.requireStudent(terminal);
                });
          } finally {
            Arrays.fill(data, (byte) 0);
          }
          return null;
        });
  }

  public Api.AdminCard adminCard(String terminal) {
    Runnable check = AccessContext.requireAdmin();
    readers.endStudent(terminal);
    return readers.use(
        terminal,
        card -> {
          check.run();
          if (!card.status("").initialized()) {
            var reserved = repo.byPublicKey(card.publicKey());
            if (reserved != null) sync.recover(card, reserved);
            if (!card.status("").initialized())
              return new Api.AdminCard(
                  readers.status(terminal), null, false, "Thẻ mới. Nhập thông tin để khởi tạo.");
          }
          var stored = registered(card);
          unlock(card, stored, true);
          return new Api.AdminCard(
              readers.status(terminal),
              card.profile(),
              true,
              repo.lifecycle(stored.id()).status().equals("BLOCKED")
                  ? "Thẻ đang khóa. Khôi phục trước khi sửa; hoặc xóa cứng."
                  : "Đã xác thực ECC và đồng bộ. Bấm Chỉnh sửa để thay đổi.");
        });
  }

  public Profile initialize(String terminal, Api.Enrollment request, long admin) {
    Runnable check = AccessContext.requireAdmin();
    readers.endStudent(terminal);
    Wire.pin(request.pin());
    return readers.use(
        terminal,
        card -> {
          check.run();
          if (card.status("").initialized()) throw Failure.bad("Thẻ đã được khởi tạo.");
          PublicKey key = card.publicKey();
          card.authenticate(key, "", 0, 0, true);
          var existing = repo.byPublicKey(key);
          if (existing != null) sync.recover(card, existing);
          Profile p = repo.reserve(request.profile(), key);
          var stored = repo.find(p.cardId());
          unlock(card, stored, true);
          byte[] data =
              Wire.concat(
                  request.pin().getBytes(StandardCharsets.US_ASCII), ProfileCodec.encode(p));
          try {
            sync.run(card, stored, Wire.INIT, data, p, admin, null, check);
          } finally {
            Arrays.fill(data, (byte) 0);
          }
          readers.rememberCardId(terminal, p.cardId());
          repo.audit(admin, p.cardId(), "INIT", "SUCCESS");
          return p;
        });
  }

  public Profile update(String terminal, Profile request, long admin) {
    Runnable check = AccessContext.requireAdmin();
    readers.endStudent(terminal);
    return readers.use(
        terminal,
        card -> {
          check.run();
          var stored = registered(card);
          repo.requireActive(stored.id());
          if (!stored.id().equals(request.cardId()))
            throw Failure.bad("Thẻ đang cắm không khớp hồ sơ muốn sửa.");
          unlock(card, stored, true);
          Profile p = repo.stage(request);
          byte[] data = ProfileCodec.encode(p);
          try {
            sync.run(
                card,
                stored,
                Wire.UPDATE,
                data,
                p,
                admin,
                null,
                () -> {
                  check.run();
                  repo.requireActive(stored.id());
                });
          } catch (RuntimeException e) {
            repo.cancelStageWithoutOperation(stored.id());
            throw e;
          } finally {
            Arrays.fill(data, (byte) 0);
          }
          repo.audit(admin, p.cardId(), "UPDATE_PROFILE", "SUCCESS");
          return p;
        });
  }

  public void resetPin(String terminal, String pin, long admin) {
    Runnable check = AccessContext.requireAdmin();
    readers.endStudent(terminal);
    Wire.pin(pin);
    readers.use(
        terminal,
        card -> {
          check.run();
          var stored = registered(card);
          repo.requireActive(stored.id());
          unlock(card, stored, true);
          byte[] data = pin.getBytes(StandardCharsets.US_ASCII);
          try {
            sync.run(
                card,
                stored,
                Wire.RESET_PIN,
                data,
                null,
                admin,
                null,
                () -> {
                  check.run();
                  repo.requireActive(stored.id());
                });
          } finally {
            Arrays.fill(data, (byte) 0);
          }
          readers.endStudent(terminal);
          repo.audit(admin, stored.id(), "RESET_PIN", "SUCCESS");
          return null;
        });
  }

  public void block(String id, long admin) {
    Runnable check = AccessContext.requireAdmin();
    check.run();
    readers.guardCardMutation(id, () -> {
      repo.tx.executeWithoutResult(t -> {
        repo.db.queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", id);
        if (repo.find(id) == null) throw Failure.missing("Không tìm thấy thẻ.");
        if (!repo.lifecycle(id).status().equals("ACTIVE"))
          throw new Failure(409, "CARD_ALREADY_BLOCKED", "Thẻ đã được tạm khóa.");
        repo.db.update(
            "UPDATE cards SET lifecycle_status='BLOCKED',block_reason='ADMIN',blocked_at=now() WHERE"
                + " card_id=?",
            id);
        repo.audit(admin, id, "BLOCK", "SUCCESS");
      });
      readers.invalidate(id);
    });
  }

  public void unblock(String terminal, long admin) {
    Runnable check = AccessContext.requireAdmin();
    readers.endStudent(terminal);
    readers.use(
        terminal,
        c -> {
          check.run();
          var stored = registered(c);
          repo.tx.executeWithoutResult(t -> {
            repo.db.queryForList(
                "SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", stored.id());
            if (!repo.lifecycle(stored.id()).status().equals("BLOCKED"))
              throw Failure.bad("Thẻ chưa bị khóa.");
            repo.db.update(
                "UPDATE cards SET"
                    + " lifecycle_status='ACTIVE',pin_failures=0,block_reason=NULL,blocked_at=NULL"
                    + " WHERE card_id=?",
                stored.id());
            repo.audit(admin, stored.id(), "RESTORE", "SUCCESS");
          });
          return null;
        });
  }

  private Api.DeletePreview preview(String terminal, CardClient c) {
    var stored = registered(c);
    if (!repo.lifecycle(stored.id()).status().equals("BLOCKED"))
      throw Failure.bad("Chỉ được xóa thẻ đang khóa.");
    var state = sync.settled(c, stored);
    var pending = refunds.preview(stored.id());
    String token =
        security.keyed(
            "DELETE|"
                + stored.id()
                + "|"
                + readers.connection(terminal)
                + "|"
                + state.counter()
                + "|"
                + state.walletVersion()
                + "|"
                + state.balance()
                + "|"
                + pending.revision());
    return new Api.DeletePreview(
        stored.id(),
        state.balance() > 0,
        state.counter(),
        state.walletVersion(),
        pending.count(),
        token);
  }

  public Api.DeletePreview deletePreview(String terminal) {
    Runnable check = AccessContext.requireAdmin();
    return readers.use(
        terminal,
        c -> {
          check.run();
          return preview(terminal, c);
        });
  }

  public void delete(String terminal, Api.DeleteCard input, long admin) {
    Runnable check = AccessContext.requireAdmin();
    readers.use(
        terminal,
        c -> {
          check.run();
          var observed = preview(terminal, c);
          if (!observed.cardId().equals(input.cardId())
              || observed.counter() != input.counter()
              || observed.walletVersion() != input.walletVersion()
              || !observed.previewToken().equals(input.previewToken()))
            throw new Failure(
                409,
                "DELETE_STALE",
                "Thẻ hoặc khoản nạp đã thay đổi. Vui lòng kiểm tra và xác nhận lại.");
          if (observed.hasBalance() && !input.confirmBalance())
            throw new Failure(409, "BALANCE_CONFIRM", "Thẻ vẫn còn số dư! Xác nhận xoá?");
          if (observed.unsyncedCount() > 0 && !input.confirmUnsynced())
            throw new Failure(
                409,
                "UNSYNCED_CONFIRM",
                "Thẻ vẫn còn "
                    + observed.unsyncedCount()
                    + " giao dịch chưa đồng bộ! Tiếp tục xoá?");
          var stored = repo.find(input.cardId());
          var state = c.state();
          // Clear RAM before deleting the only backend share; keep the reader monitor.
          readers.endStudent(terminal);
          repo.tx.executeWithoutResult(t -> {
            String id = stored.id();
            repo.db.queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", id);
            if (!repo.lifecycle(id).status().equals("BLOCKED"))
              throw Failure.bad("Chỉ được xóa thẻ đang khóa.");
            String current =
                security.keyed(
                    "DELETE|"
                        + id
                        + "|"
                        + readers.connection(terminal)
                        + "|"
                        + state.counter()
                        + "|"
                        + state.walletVersion()
                        + "|"
                        + state.balance()
                        + "|"
                        + refunds.preview(id).revision());
            if (!current.equals(input.previewToken()))
              throw new Failure(
                  409, "DELETE_STALE", "Khoản nạp đã thay đổi. Vui lòng kiểm tra và xác nhận lại.");
            long pending =
                repo.db.queryForObject(
                    "SELECT count(*) FROM card_operations WHERE card_id=? AND phase NOT IN"
                        + " ('SYNCED','ABORTED')",
                    Long.class,
                    id);
            long financial =
                repo.db.queryForObject(
                    "SELECT count(*) FROM invoices WHERE card_id=? AND status"
                        + " IN('OPEN','PAYMENT_PENDING','CANCEL_PENDING')",
                    Long.class,
                    id);
            if (pending != 0 || financial != 0 || state.financialHold() != 0)
              throw Failure.bad("Thẻ đang chờ đối soát. Chưa thể xóa.");
            repo.db.queryForList(
                "SELECT id FROM invoices WHERE card_id=? ORDER BY id FOR UPDATE", id);
            refunds.archive(
                id,
                stored.current() == null ? null : stored.current().studentCode(),
                stored.current() == null ? "" : stored.current().fullName(),
                admin,
                state.balance(),
                state.walletVersion(),
                false);
            sync.checkpoint("DELETE_ARCHIVED");
            repo.db.update(
                "DELETE FROM payment_attempts WHERE invoice_id IN(SELECT id FROM invoices WHERE"
                    + " card_id=?)",
                id);
            repo.db.update("DELETE FROM card_operations WHERE card_id=?", id);
            repo.db.update("DELETE FROM card_cipher_snapshots WHERE card_id=?", id);
            repo.db.update("DELETE FROM card_wallets WHERE card_id=?", id);
            repo.db.update("DELETE FROM pin_attempts WHERE card_id=?", id);
            repo.db.update("DELETE FROM wallet_operations WHERE card_id=?", id);
            repo.db.update("DELETE FROM topups WHERE card_id=?", id);
            repo.db.update(
                "DELETE FROM invoice_lines WHERE invoice_id IN(SELECT id FROM invoices WHERE"
                    + " card_id=?)",
                id);
            repo.db.update("DELETE FROM invoices WHERE card_id=?", id);
            repo.db.update("DELETE FROM audit_log WHERE card_id=?", id);
            repo.db.update("DELETE FROM card_secrets WHERE card_id=?", id);
            repo.db.update("DELETE FROM cards WHERE card_id=?", id);
          });
          readers.deleteConnected(terminal);
          return null;
        });
  }

  public Api.SyncStatus syncStatus(String terminal) {
    if (!readers.status(terminal).present())
      return new Api.SyncStatus("", "NO_CARD", 0, "Không phát hiện thẻ");
    return readers.inspectTopup(
        terminal,
        c -> {
          var s = c.status("");
          var stored = s.initialized() ? repo.find(s.cardId()) : repo.byPublicKey(c.publicKey());
          if (stored == null) return new Api.SyncStatus("", "UNREGISTERED", 0, "Thẻ chưa đăng ký");
          if (payments.cancelling(stored.id()))
            return new Api.SyncStatus(
                stored.id(), "CANCEL_PENDING", 0, "Đang hủy/hoàn bù · Chọn Đồng bộ lại");
          if (repo.db.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM payment_attempts a JOIN invoices i ON i.id=a.invoice_id"
                  + " WHERE i.card_id=? AND i.outcome='SUCCESS' AND a.released=false)",
              Boolean.class,
              stored.id()))
            return new Api.SyncStatus(
                stored.id(),
                "FINALIZE_PENDING",
                0,
                "Đang chốt xác nhận trên thẻ · Chọn Đồng bộ lại");
          return sync.status(stored.id());
        });
  }

  public Api.Result reconcile(String terminal) {
    return readers.useTopup(
        terminal,
        c -> {
          long reserved = readers.reservedTopup(terminal);
          if (reserved != 0 && Boolean.TRUE.equals(repo.db.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM topups WHERE id=? AND status='WAITING_BANK'"
                  + " AND expires_at>clock_timestamp())", Boolean.class, reserved)))
            throw new Failure(409, "TOPUP_IN_PROGRESS",
                "Yêu cầu nạp còn hiệu lực. Hãy hoàn tất hoặc hủy yêu cầu trước khi đồng bộ lại.");
          readers.endStudentForRecovery(terminal);
          var status = c.status("");
          var stored =
              status.initialized() ? repo.find(status.cardId()) : repo.byPublicKey(c.publicKey());
          if (stored == null)
            return new Api.Result("Thẻ chưa đăng ký. Vui lòng khởi tạo qua admin.");
          c.authenticate(repo.key(stored), status.cardId(), 3, 0, true);
          boolean cancelling = payments.cancelling(stored.id());
          payments.recover(c, stored);
          var before = sync.status(stored.id());
          sync.settled(c, stored);
          sync.repairWalletRecords(stored.id());
          String message =
              cancelling
                  ? "Đã hủy thanh toán và đối chiếu/hoàn bù số dư. Vui lòng tạo hóa đơn mới."
                  : "Đã đối chiếu thẻ và backend. Vui lòng xác thực lại để tiếp tục.";
          if (before.operationId() > 0) {
            var row =
                repo.db.queryForMap(
                    "SELECT kind,phase FROM card_operations WHERE id=?", before.operationId());
            boolean applied = row.get("phase").equals("SYNCED");
            message =
                applied
                    ? "Đã phục hồi và đồng bộ thao tác thành công."
                    : "Thao tác chưa được chốt đã hủy an toàn; dữ liệu trước đó được giữ nguyên.";
            int kind = ((Number) row.get("kind")).intValue();
            if (kind == Wire.RESET_PIN || kind == Wire.CHANGE_PIN || kind == Wire.INIT)
              message +=
                  applied
                      ? " Sử dụng PIN mới vừa thiết lập."
                      : " Tiếp tục dùng PIN cũ; thẻ trắng cần khởi tạo lại.";
          }
          return new Api.Result(message);
        });
  }

  void preparePayment(String terminal, CardClient c, CardRepository.Stored stored, long invoice) {
    repo.requireActive(stored.id());
    readers.checkoutReady(terminal);
    if (!readers.paymentPrepared(terminal, invoice)) {
      c.authenticate(repo.key(stored), stored.id(), 2, invoice, true);
      unlock(c, stored, false);
      readers.bindPayment(terminal, invoice);
    }
  }
}
