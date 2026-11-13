package vn.edu.smartcard.backend;

import java.sql.Timestamp;
import java.util.*;
import vn.edu.smartcard.shared.Api;

/** DB-only lifecycle decisions. Never wait for a reader/APDU while closing a station. */
final class StationSessions {
  private final CardRepository repo;

  StationSessions(CardRepository repo) {
    this.repo = repo;
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (Exception e) {
      throw Failure.bad("Định danh phiên quầy không hợp lệ.");
    }
  }

  Api.StationSession open(Api.OpenStation input) {
    UUID station = uuid(input.stationId()), startup = uuid(input.startupId());
    return repo.tx.execute(tx -> {
      repo.db.queryForObject(
          "SELECT pg_advisory_xact_lock(hashtextextended(?::text,7334)) IS NULL",
          Boolean.class,
          station.toString());
      var prior =
          repo.db.queryForList(
              "SELECT *,lease_until>clock_timestamp() AS valid FROM station_sessions WHERE"
                  + " station_id=? AND startup_id=? FOR UPDATE",
              station,
              startup);
      if (!prior.isEmpty()) {
        var row = prior.getFirst();
        if (!"ACTIVE".equals(row.get("status")) || !Boolean.TRUE.equals(row.get("valid")))
          throw expired();
        return dto(row);
      }
      for (UUID old :
          repo.db.queryForList(
              "SELECT session_id FROM station_sessions WHERE station_id=? AND status='ACTIVE' ORDER"
                  + " BY epoch FOR UPDATE",
              UUID.class,
              station)) closeLocked(old, "CLOSED", "STATION_REOPENED");
      long epoch =
          repo.db.queryForObject(
              "SELECT COALESCE(max(epoch),0)+1 FROM station_sessions WHERE station_id=?",
              Long.class,
              station);
      repo.db.update(
          "INSERT INTO station_sessions(session_id,station_id,startup_id,epoch,status,lease_until)"
              + " VALUES(?,?,?,?,'ACTIVE',clock_timestamp()+interval '15 seconds')",
          startup,
          station,
          startup,
          epoch);
      repo.db.update(
          "UPDATE invoices i SET station_session_id=?,terminal_id=?,revision=revision+1"
              + " WHERE i.station_session_id IN (SELECT session_id FROM station_sessions"
              + " WHERE station_id=? AND session_id<>?) AND"
              + " ((i.outcome IS NULL AND i.status IN ('OPEN','PAYMENT_PENDING')"
              + " AND (i.expires_at>clock_timestamp() OR i.payment_phase='CARD_PROCESSING'))"
              + " OR i.status='CANCEL_PENDING')",
          startup, startup.toString(), station, startup);
      return dto(repo.db.queryForMap("SELECT * FROM station_sessions WHERE session_id=?", startup));
    });
  }

  Api.StationSession heartbeat(String terminal) {
    var rows =
        repo.db.queryForList(
            "UPDATE station_sessions SET"
                + " heartbeat_at=clock_timestamp(),lease_until=clock_timestamp()+interval '15"
                + " seconds' WHERE session_id=? AND status='ACTIVE' AND"
                + " lease_until>clock_timestamp() RETURNING *",
            uuid(terminal));
    if (rows.isEmpty()) throw expired();
    return dto(rows.getFirst());
  }

  void close(String terminal) {
    repo.tx.executeWithoutResult(tx -> {
      var rows =
          repo.db.queryForList(
              "SELECT session_id FROM station_sessions WHERE session_id=? FOR UPDATE",
              uuid(terminal));
      if (!rows.isEmpty()) closeLocked(uuid(terminal), "CLOSED", "STATION_CLOSED");
    });
  }

  void restart() {
    repo.tx.executeWithoutResult(tx -> {
      for (UUID id :
          repo.db.queryForList(
              "SELECT session_id FROM station_sessions WHERE status='ACTIVE' ORDER BY"
                  + " station_id,epoch FOR UPDATE",
              UUID.class)) closeLocked(id, "LOST", "BACKEND_RESTART");
    });
  }

  void expire() {
    repo.tx.executeWithoutResult(tx -> {
      for (UUID id :
          repo.db.queryForList(
              "SELECT session_id FROM station_sessions WHERE status='ACTIVE' AND"
                  + " lease_until<=clock_timestamp() ORDER BY station_id,epoch FOR UPDATE",
              UUID.class)) closeLocked(id, "LOST", "STATION_LOST");
    });
  }

  private void closeLocked(UUID session, String status, String reason) {
    repo.db.update(
        "UPDATE station_sessions SET status=?,closed_at=COALESCE(closed_at,clock_timestamp()) WHERE"
            + " session_id=? AND status='ACTIVE'",
        status,
        session);
  }

  void requireActive(String terminal, boolean lock) {
    var rows =
        repo.db.queryForList(
            "SELECT status,lease_until>clock_timestamp() AS valid FROM station_sessions WHERE"
                + " session_id=?"
                + (lock ? " FOR UPDATE" : ""),
            uuid(terminal));
    if (rows.isEmpty()
        || !"ACTIVE".equals(rows.getFirst().get("status"))
        || !Boolean.TRUE.equals(rows.getFirst().get("valid"))) throw expired();
  }

  /** Acquire the session before card/invoice locks, including coordinator commit. */
  void lockInvoiceSession(long invoice, boolean active) {
    var rows = repo.db.queryForList("SELECT station_session_id FROM invoices WHERE id=?", invoice);
    if (rows.isEmpty()) throw Failure.missing("Không tìm thấy hóa đơn.");
    Object session = rows.getFirst().get("station_session_id");
    if (session == null) {
      if (active) throw expired();
      return;
    }
    if (active) requireActive(session.toString(), true);
    else
      repo.db.queryForList(
          "SELECT session_id FROM station_sessions WHERE session_id=? FOR UPDATE", session);
  }

  void requireOwner(String terminal, long invoice) {
    var rows = repo.db.queryForList("SELECT station_session_id FROM invoices WHERE id=?", invoice);
    if (rows.isEmpty()) throw Failure.missing("Không tìm thấy hóa đơn.");
    if (!uuid(terminal).equals(rows.getFirst().get("station_session_id"))) throw Failure.denied();
  }

  void cancel(long invoice, String reason) {
    repo.tx.executeWithoutResult(tx -> {
      lockInvoiceSession(invoice, false);
      cancelLocked(invoice, reason);
    });
  }

  void cancelByUser(long invoice) {
    repo.tx.executeWithoutResult(tx -> {
      lockInvoiceSession(invoice, false);
      var row = repo.db.queryForMap(
          "SELECT status,payment_phase FROM invoices WHERE id=? FOR UPDATE", invoice);
      if ("PAYMENT_PENDING".equals(row.get("status"))
          && "CARD_PROCESSING".equals(row.get("payment_phase")))
        throw new Failure(409, "CARD_PROCESSING",
            "Đang thanh toán bằng thẻ. Vui lòng chờ kết quả trước khi hủy.");
      cancelLocked(invoice, "USER_CANCELLED");
    });
  }

  private void cancelLocked(long invoice, String reason) {
    var row =
        repo.db.queryForMap("SELECT outcome,status FROM invoices WHERE id=? FOR UPDATE", invoice);
    if ("SUCCESS".equals(row.get("outcome")) || "PAID".equals(row.get("status"))) return;
    repo.db.update(
        "UPDATE invoices SET outcome='CANCEL',cancel_reason=COALESCE(cancel_reason,?),status=CASE"
            + " WHEN EXISTS(SELECT 1 FROM payment_attempts WHERE invoice_id=invoices.id) OR"
            + " EXISTS(SELECT 1 FROM wallet_operations WHERE invoice_id=invoices.id AND"
            + " status<>'FAILED') THEN 'CANCEL_PENDING' ELSE 'CANCELLED'"
            + " END,revision=revision+1,cancelled_at=CASE WHEN NOT EXISTS(SELECT 1 FROM"
            + " payment_attempts WHERE invoice_id=invoices.id) AND NOT EXISTS(SELECT 1 FROM"
            + " wallet_operations WHERE invoice_id=invoices.id AND status<>'FAILED') THEN"
            + " clock_timestamp() ELSE cancelled_at END WHERE id=? AND status<>'CANCELLED'",
        reason,
        invoice);
    repo.db.update(
        "UPDATE payment_attempts SET decision='CANCEL',error_code=COALESCE(error_code,?) WHERE"
            + " invoice_id=? AND decision IS DISTINCT FROM 'SUCCESS'",
        reason,
        invoice);
  }

  private static Api.StationSession dto(Map<String, Object> row) {
    return new Api.StationSession(
        row.get("session_id").toString(),
        ((Number) row.get("epoch")).longValue(),
        ((Timestamp) row.get("lease_until")).getTime());
  }

  static Failure expired() {
    return new Failure(
        409, "STATION_EXPIRED", "Phiên quầy đã kết thúc. Mở lại ứng dụng để tiếp tục xử lý hóa đơn.");
  }
}
