package vn.edu.smartcard.backend;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import vn.edu.smartcard.shared.*;

/** Durable coordinator. SQL transactions never remain open across an APDU. */
@Service
public class CardSynchronizer {
  private final CardRepository repo;
  private final SecurityMaterial security;
  // Package-private test fault seam; not exposed by HTTP or runtime configuration.
  Consumer<String> fault = step -> {};

  public CardSynchronizer(CardRepository repo, SecurityMaterial security) {
    this.repo = repo;
    this.security = security;
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }

  private static long number(Map<String, Object> row, String field) {
    return ((Number) row.get(field)).longValue();
  }

  void checkpoint(String point) {
    fault.accept(point);
  }

  private Map<String, Object> active(String card) {
    var rows =
        repo.db.queryForList(
            "SELECT * FROM card_operations WHERE card_id=? AND phase NOT IN ('SYNCED','ABORTED')"
                + " ORDER BY id LIMIT 1",
            card);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Protocol.State settled(CardClient card, CardRepository.Stored stored) {
    return settled(card, stored, true, false);
  }

  Protocol.State financialState(CardClient card, CardRepository.Stored stored) {
    return settled(card, stored, false, true);
  }

  private Protocol.State settled(
      CardClient card,
      CardRepository.Stored stored,
      boolean discardStaged,
      boolean allowFinancial) {
    recover(card, stored);
    Protocol.State s = card.state();
    if (s.financialHold() != 0 && !allowFinancial) throw pending();
    if (s.phase() != 0) throw conflict(stored.id(), null, "UNTRACKED_PENDING");
    if (!s.cardId().isEmpty() && !s.cardId().equals(stored.id()))
      throw conflict(stored.id(), null, "WRONG_CARD");
    var wallets = repo.db.queryForList("SELECT * FROM card_wallets WHERE card_id=?", stored.id());
    if (wallets.isEmpty()) {
      long pending =
          repo.db.queryForObject(
              "SELECT count(*) FROM wallet_operations WHERE card_id=? AND status='PENDING'",
              Long.class,
              stored.id());
      long ledger =
          repo.db.queryForObject(
              "SELECT COALESCE(sum(CASE WHEN kind IN ('CREDIT','REVERSAL') THEN amount ELSE -amount"
                  + " END),0) FROM wallet_operations WHERE card_id=? AND status='APPLIED'",
              Long.class,
              stored.id());
      if (stored.current() != null
          || s.profileVersion() != 0
          || s.balance() != 0
          || s.counter() != 0
          || pending != 0
          || ledger != 0) throw conflict(stored.id(), null, "MISSING_WALLET");
      byte[] blob = s.profileVersion() > 0 ? card.cipher(s, false) : null;
      if (stored.current() != null && stored.current().version() != s.profileVersion())
        throw conflict(stored.id(), null, "PROFILE_VERSION");
      repo.tx.executeWithoutResult(t -> {
        repo.db.queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", stored.id());
        repo.db.update(
            "INSERT INTO"
                + " card_wallets(card_id,balance,wallet_version,state_counter,last_operation_id)"
                + " VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
            stored.id(),
            s.balance(),
            s.walletVersion(),
            s.counter(),
            s.lastId());
        if (blob != null) saveSnapshot(stored.id(), s, blob);
        repo.db.update("UPDATE cards SET sync_status='SYNCED' WHERE card_id=?", stored.id());
      });
    } else {
      Map<String, Object> w = wallets.getFirst();
      if (number(w, "balance") != s.balance()
          || number(w, "wallet_version") != s.walletVersion()
          || number(w, "state_counter") != s.counter())
        throw conflict(stored.id(), null, "WALLET_STATE");
      if (s.profileVersion() > 0) {
        var snapshots =
            repo.db.queryForList(
                "SELECT * FROM card_cipher_snapshots WHERE card_id=?", stored.id());
        if (snapshots.isEmpty()
            || number(snapshots.getFirst(), "profile_version") != s.profileVersion()
            || number(snapshots.getFirst(), "crypto_version") != s.cryptoVersion()
            || !snapshots.getFirst().get("cipher_hash").equals(hex(s.cipherHash()))
            || !hex(Wire.hash((byte[]) snapshots.getFirst().get("cipher_blob")))
                .equals(hex(s.cipherHash()))) throw conflict(stored.id(), null, "CIPHER_STATE");
      }
    }
    // A crash can happen after staging a profile but before creating its journal.
    // Only discard that uncommitted edit after the active card and mirror agree.
    CardRepository.Stored latest = repo.find(stored.id());
    if (latest.current() != null && latest.current().version() != s.profileVersion())
      throw conflict(stored.id(), null, "PROFILE_VERSION");
    if (discardStaged
        && latest.current() != null
        && latest.pending() != null
        && active(stored.id()) == null) repo.cancelUnapplied(latest);
    return s;
  }

  public long run(
      CardClient card,
      CardRepository.Stored stored,
      int kind,
      byte[] data,
      Profile profile,
      Long actor,
      Long walletOperation,
      Runnable authorize) {
    authorize.run();
    Protocol.State before = settled(card, stored, false, kind == Wire.REVERSAL);
    if (before.counter() >= 0xffffffffL) throw Failure.bad("Thẻ đã hết bộ đếm thao tác.");
    long p = kind == Wire.INIT ? 1 : before.profileVersion() + (kind == Wire.UPDATE ? 1 : 0),
        crypto =
            kind == Wire.INIT
                ? 1
                : before.cryptoVersion()
                    + ((kind == Wire.RESET_PIN || kind == Wire.CHANGE_PIN) ? 1 : 0),
        wallet =
            before.walletVersion()
                + ((kind == Wire.CREDIT || kind == Wire.DEBIT || kind == Wire.REVERSAL) ? 1 : 0);
    long after = before.balance();
    if (kind == Wire.CREDIT || kind == Wire.DEBIT || kind == Wire.REVERSAL) {
      long amount = Integer.toUnsignedLong(ByteBuffer.wrap(data).getInt(8));
      after = kind != Wire.DEBIT ? Math.addExact(after, amount) : after - amount;
      if (after < 0 || after > Integer.MAX_VALUE)
        throw new Failure(409, "FUNDS", "Không đủ số dư hoặc số tiền vượt giới hạn của thẻ.");
    }
    byte[] request = Protocol.request(kind, before.balance(), p, crypto, wallet, data);
    byte[] token = security.randomBytes(32);
    String digest = hex(token);
    long nextBalance = after;
    long id =
        repo.tx.execute(t -> {
          lockFinancialSession(walletOperation, kind == Wire.DEBIT);
          repo.db.queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", stored.id());
          guardFinancialInvoice(walletOperation, kind);
          if (active(stored.id()) != null) throw pending();
          long result =
              repo.db.queryForObject(
                  "INSERT INTO"
                      + " card_operations(card_id,kind,phase,before_counter,before_balance,before_profile,before_crypto,before_wallet,next_profile,next_crypto,next_wallet,next_balance,operation_token,profile_pending_json,actor_id,wallet_operation_id)"
                      + " VALUES (?,?,'CREATED',?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                  Long.class,
                  stored.id(),
                  kind,
                  before.counter(),
                  before.balance(),
                  before.profileVersion(),
                  before.cryptoVersion(),
                  before.walletVersion(),
                  p,
                  crypto,
                  wallet,
                  nextBalance,
                  digest,
                  profile == null ? null : security.encode(profile),
                  actor,
                  walletOperation);
          repo.db.update("UPDATE cards SET sync_status='PENDING' WHERE card_id=?", stored.id());
          return result;
        });
    try {
      checkpoint("CREATED");
      card.prepare(stored.id(), id, request, token, authorize);
      checkpoint("CARD_PREPARED");
      Protocol.State prepared = card.state();
      Map<String, Object> row = operation(id);
      validatePrepared(row, prepared);
      byte[] candidate = card.cipher(prepared, true);
      repo.db.update(
          "UPDATE card_operations SET"
              + " phase='CARD_PREPARED',candidate_blob=?,candidate_hash=?,proof=?,updated_at=now()"
              + " WHERE id=?",
          candidate,
          hex(Wire.hash(candidate)),
          prepared.encoded(),
          id);
      authorize.run();
      repo.tx.executeWithoutResult(t -> {
        lockFinancialSession(walletOperation, kind == Wire.DEBIT);
        repo.db.queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", stored.id());
        if (kind != Wire.INIT && kind != Wire.REVERSAL) repo.requireActive(stored.id());
        guardFinancialInvoice(walletOperation, kind);
        repo.db.update(
            "UPDATE card_operations SET phase='COMMIT_DECIDED',updated_at=now() WHERE id=? AND"
                + " phase='CARD_PREPARED'",
            id);
      });
      checkpoint("COMMIT_DECIDED");
      finish(card, operation(id));
      return id;
    } catch (RuntimeException e) {
      Map<String, Object> row = operation(id);
      String phase = (String) row.get("phase");
      if (Set.of("CREATED", "CARD_PREPARED").contains(phase)) {
        boolean aborted = false;
        try {
          abortUncommitted(card, row);
          aborted = true;
        } catch (RuntimeException recovery) {
          if (recovery instanceof Failure f && f.code.equals("SYNC_CONFLICT")) throw recovery;
        }
        if (aborted) throw e;
      }
      if (e instanceof Failure f && f.code.equals("SYNC_CONFLICT")) throw e;
      repo.db.update(
          "UPDATE card_operations SET last_error='INTERRUPTED',updated_at=now() WHERE id=?", id);
      throw pending();
    } finally {
      Arrays.fill(request, (byte) 0);
      Arrays.fill(token, (byte) 0);
    }
  }

  public void recover(CardClient card, CardRepository.Stored stored) {
    Map<String, Object> row = active(stored.id());
    if (row == null) return;
    String phase = (String) row.get("phase");
    if (phase.equals("CONFLICT")) throw conflict(stored.id(), number(row, "id"), "REQUIRES_REVIEW");
    if (phase.equals("CREATED") || phase.equals("CARD_PREPARED")) {
      abortUncommitted(card, row);
      return;
    }
    finish(card, row);
  }

  private Map<String, Object> operation(long id) {
    return repo.db.queryForMap("SELECT * FROM card_operations WHERE id=?", id);
  }

  private void validatePrepared(Map<String, Object> row, Protocol.State s) {
    long id = number(row, "id");
    if (s.phase() != 1
        || s.pendingId() != id
        || s.pendingKind() != number(row, "kind")
        || !s.pendingCardId().equals(row.get("card_id"))
        || s.counter() != number(row, "before_counter")
        || s.nextProfile() != number(row, "next_profile")
        || s.nextCrypto() != number(row, "next_crypto")
        || s.nextWallet() != number(row, "next_wallet")
        || s.nextBalance() != number(row, "next_balance")
        || !hex(s.pendingHash()).equals(row.get("operation_token")))
      throw conflict((String) row.get("card_id"), id, "PREPARED_STATE");
  }

  private void validateCommitted(Map<String, Object> row, Protocol.State s) {
    long id = number(row, "id");
    byte[] blob = (byte[]) row.get("candidate_blob");
    if (!s.cardId().equals(row.get("card_id"))
        || s.counter() != number(row, "before_counter") + 1
        || s.profileVersion() != number(row, "next_profile")
        || s.cryptoVersion() != number(row, "next_crypto")
        || s.walletVersion() != number(row, "next_wallet")
        || s.balance() != number(row, "next_balance")
        || s.lastId() != id
        || s.lastKind() != number(row, "kind")
        || !hex(s.lastHash()).equals(row.get("operation_token"))
        || !hex(s.cipherHash()).equals(row.get("candidate_hash"))
        || blob == null
        || !hex(Wire.hash(blob)).equals(row.get("candidate_hash")))
      throw conflict((String) row.get("card_id"), id, "COMMITTED_STATE");
  }

  private void finish(CardClient card, Map<String, Object> row) {
    String cardId = (String) row.get("card_id");
    long id = number(row, "id");
    Protocol.State s = card.state();
    byte[] candidate = (byte[]) row.get("candidate_blob");
    if (candidate == null || !hex(Wire.hash(candidate)).equals(row.get("candidate_hash")))
      throw conflict(cardId, id, "CANDIDATE_HASH");
    if (s.phase() == 1) {
      validatePrepared(row, s);
      card.complete(Protocol.COMMIT, cardId, id);
      checkpoint("CARD_COMMITTED");
      s = card.state();
    }
    if (s.phase() != 2 && !(s.phase() == 0 && s.lastAck() == id))
      throw conflict(cardId, id, "MISSING_COMMIT");
    validateCommitted(row, s);
    Protocol.State confirmed = s;
    repo.tx.executeWithoutResult(t -> {
      repo.db.queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", cardId);
      Map<String, Object> current = operation(id);
      if (Set.of("SYNCED", "DB_COMMITTED").contains(current.get("phase"))) return;
      if (current.get("profile_pending_json") != null) {
        Profile profile =
            security.decode((String) current.get("profile_pending_json"), Profile.class);
        repo.finish(profile);
      }
      if (number(current, "kind") == Wire.RESET_PIN
          && !Boolean.TRUE.equals(current.get("pin_counter_reset_applied"))) {
        repo.db.update("UPDATE cards SET pin_failures=0 WHERE card_id=?", cardId);
        repo.db.update("UPDATE card_operations SET pin_counter_reset_applied=true WHERE id=?", id);
      }
      saveSnapshot(cardId, confirmed, (byte[]) current.get("candidate_blob"));
      repo.db.update(
          "UPDATE card_wallets SET"
              + " balance=?,wallet_version=?,state_counter=?,last_operation_id=?,updated_at=now()"
              + " WHERE card_id=?",
          confirmed.balance(),
          confirmed.walletVersion(),
          confirmed.counter(),
          id,
          cardId);
      if (current.get("wallet_operation_id") != null)
        repo.db.update(
            "UPDATE wallet_operations SET status='APPLIED',receipt=? WHERE id=?",
            Base64.getEncoder().encodeToString(confirmed.encoded()),
            current.get("wallet_operation_id"));
      repo.db.update(
          "UPDATE card_operations SET phase='DB_COMMITTED',proof=?,last_error=NULL,updated_at=now()"
              + " WHERE id=?",
          confirmed.encoded(),
          id);
    });
    checkpoint("DB_COMMITTED");
    if (s.phase() == 2) card.complete(Protocol.ACK, cardId, id);
    checkpoint("ACK");
    Protocol.State acknowledged = card.state();
    validateCommitted(row, acknowledged);
    if (acknowledged.phase() != 0 || acknowledged.lastAck() != id)
      throw conflict(cardId, id, "ACK_STATE");
    repo.tx.executeWithoutResult(t -> {
      repo.db.update(
          "UPDATE card_operations SET phase='SYNCED',proof=?,updated_at=now() WHERE id=?",
          acknowledged.encoded(),
          id);
      repo.db.update("UPDATE cards SET sync_status='SYNCED' WHERE card_id=?", cardId);
      repairWalletRecord(operation(id));
    });
  }

  private void lockFinancialSession(Long wallet, boolean active) {
    if (wallet == null) return;
    var linked = repo.db.queryForMap("SELECT invoice_id FROM wallet_operations WHERE id=?", wallet);
    if (linked.get("invoice_id") != null)
      new StationSessions(repo)
          .lockInvoiceSession(((Number) linked.get("invoice_id")).longValue(), active);
  }

  private void guardFinancialInvoice(Long wallet, int kind) {
    if (wallet == null) return;
    var linked = repo.db.queryForMap("SELECT invoice_id FROM wallet_operations WHERE id=?", wallet);
    if (linked.get("invoice_id") == null) return;
    var i =
        repo.db.queryForMap(
            "SELECT status,outcome,expires_at>clock_timestamp() AS valid FROM invoices WHERE id=?"
                + " FOR UPDATE",
            linked.get("invoice_id"));
    if (kind == Wire.REVERSAL) {
      if (!"CANCEL".equals(i.get("outcome")))
        throw Failure.bad("Chỉ hoàn bù hóa đơn đã quyết định hủy.");
      return;
    }
    if (!"PAYMENT_PENDING".equals(i.get("status")) || i.get("outcome") != null)
      throw new Failure(409, "INVOICE_CLOSED", "Hóa đơn đã hủy. Vui lòng tạo hóa đơn mới.");
    if (!Boolean.TRUE.equals(i.get("valid")))
      throw new Failure(409, "QR_EXPIRED", "Hóa đơn đã hết hạn. Vui lòng tạo hóa đơn mới.");
  }

  private void saveSnapshot(String card, Protocol.State state, byte[] blob) {
    if (blob == null || !hex(Wire.hash(blob)).equals(hex(state.cipherHash())))
      throw conflict(card, null, "CIPHER_HASH");
    repo.db.update(
        "INSERT INTO"
            + " card_cipher_snapshots(card_id,profile_version,crypto_version,cipher_hash,cipher_blob,card_state)"
            + " VALUES (?,?,?,?,?,?) ON CONFLICT(card_id) DO UPDATE SET"
            + " profile_version=excluded.profile_version,crypto_version=excluded.crypto_version,cipher_hash=excluded.cipher_hash,cipher_blob=excluded.cipher_blob,card_state=excluded.card_state,updated_at=now()",
        card,
        state.profileVersion(),
        state.cryptoVersion(),
        hex(state.cipherHash()),
        blob,
        state.encoded());
  }

  private void abortUncommitted(CardClient card, Map<String, Object> row) {
    String cardId = (String) row.get("card_id");
    long id = number(row, "id");
    Protocol.State state = card.state();
    if (state.phase() == 1) {
      validatePrepared(row, state);
      card.complete(Protocol.ABORT, cardId, id);
      state = card.state();
    }
    if (state.phase() != 0
        || state.counter() != number(row, "before_counter")
        || state.balance() != number(row, "before_balance"))
      throw conflict(cardId, id, "ABORT_STATE");
    CardRepository.Stored stored = repo.find(cardId);
    repo.tx.executeWithoutResult(t -> {
      repo.db.update(
          "UPDATE card_operations SET"
              + " phase='ABORTED',profile_pending_json=NULL,last_error=NULL,updated_at=now() WHERE"
              + " id=?",
          id);
      repo.cancelUnapplied(stored);
      if (row.get("wallet_operation_id") != null)
        repo.db.update(
            "UPDATE wallet_operations SET status='FAILED' WHERE id=? AND status='PENDING'",
            row.get("wallet_operation_id"));
      repo.db.update("UPDATE cards SET sync_status='SYNCED' WHERE card_id=?", cardId);
    });
  }

  public void repairWalletRecords(String card) {
    for (var row :
        repo.db.queryForList(
            "SELECT * FROM card_operations WHERE card_id=? AND phase='SYNCED' AND"
                + " wallet_operation_id IS NOT NULL",
            card)) repairWalletRecord(row);
  }

  private void repairWalletRecord(Map<String, Object> operation) {
    Object wallet = operation.get("wallet_operation_id");
    if (wallet == null) return;
    repo.db.update(
        "UPDATE wallet_operations SET status='APPLIED',receipt=? WHERE id=?",
        Base64.getEncoder().encodeToString((byte[]) operation.get("proof")),
        wallet);
    var rows =
        repo.db.queryForList(
            "SELECT topup_id,invoice_id FROM wallet_operations WHERE id=?", wallet);
    if (rows.isEmpty()) return;
    var links = rows.getFirst();
    if (links.get("topup_id") != null)
      repo.db.update("UPDATE topups SET status='CREDITED' WHERE id=?", links.get("topup_id"));
  }

  private Failure conflict(String card, Long operation, String reason) {
    repo.db.update("UPDATE cards SET sync_status='CONFLICT' WHERE card_id=?", card);
    if (operation != null)
      repo.db.update(
          "UPDATE card_operations SET phase='CONFLICT',last_error=? WHERE id=?", reason, operation);
    return new Failure(
        409,
        "SYNC_CONFLICT",
        "Trạng thái thẻ chưa khớp hệ thống. Tạm dừng thao tác để đối soát; không ghi đè số dư hoặc"
            + " dữ liệu.");
  }

  private static Failure pending() {
    return new Failure(
        409,
        "SYNC_PENDING",
        "Thao tác đang chờ xác nhận. Giữ đúng thẻ và chọn Đồng bộ lại; không tạo thao tác mới.");
  }

  public Api.SyncStatus status(String card) {
    var rows = repo.db.queryForList("SELECT sync_status FROM cards WHERE card_id=?", card);
    if (rows.isEmpty()) return new Api.SyncStatus(card, "UNREGISTERED", 0, "Thẻ chưa đăng ký.");
    Map<String, Object> pending = active(card);
    String phase =
        pending == null
            ? (String) rows.getFirst().get("sync_status")
            : (String) pending.get("phase");
    return new Api.SyncStatus(
        card,
        phase,
        pending == null ? 0 : number(pending, "id"),
        phase.equals("SYNCED")
            ? "Đã đồng bộ"
            : phase.equals("CONFLICT") ? "Cần đối soát" : "Đang chờ đồng bộ");
  }
}
