package vn.edu.smartcard.backend;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Service;
import vn.edu.smartcard.shared.*;

@Service
public class WalletService {
  private final CardService cards;
  private final CardRepository repo;
  private final BankGateway bank;
  private final SecurityMaterial security;
  private final SePayTopups sepay;
  final MenuService catalog;
  final InvoiceBankPayments invoiceBank;

  public WalletService(
      CardService cards, CardRepository repo, BankGateway bank, SecurityMaterial security) {
    this.cards = cards;
    this.repo = repo;
    this.bank = bank;
    this.security = security;
    this.catalog = new MenuService(repo, cards.stations);
    this.invoiceBank = new InvoiceBankPayments(repo, security, cards.stations);
    this.sepay = new SePayTopups(repo, security, bank.sepay(), invoiceBank);
    cards.stations.restart();
    cards.payments.finishEmptyCancellations();
  }

  public List<Api.MenuItem> menu() {
    return catalog.items(null, "");
  }

  public List<Api.BankReceipt> bankReceipts() {
    return repo.db.query(
        "SELECT e.provider_id,e.bank_reference,e.amount,e.description,e.result,e.review_reason,"
            + " COALESCE(i.amount,t.amount,s.amount) AS expected_amount,"
            + " COALESCE(e.bank_transaction_at,e.created_at) AS received_at, CASE WHEN"
            + " e.result='MATCHED_INVOICE' THEN i.invoice_number END AS linked_invoice, CASE WHEN"
            + " e.result='MATCHED' THEN COALESCE( CASE WHEN length(s.bank_memo)=18 THEN s.bank_memo"
            + " END,t.reference,e.topup_reference) END AS linked_topup FROM bank_events e LEFT JOIN"
            + " invoice_payment_references i ON i.reference=e.invoice_reference LEFT JOIN topups t"
            + " ON t.id=e.topup_id LEFT JOIN sepay_topup_requests s ON"
            + " s.reference=COALESCE(e.topup_reference,t.reference) WHERE e.amount>0 ORDER BY"
            + " e.created_at DESC,e.provider_id DESC LIMIT 200",
        (r, n) ->
            new Api.BankReceipt(
                r.getString("provider_id"),
                r.getString("bank_reference"),
                r.getLong("amount"),
                r.getObject("expected_amount", Long.class),
                r.getString("description"),
                r.getString("result"),
                Objects.toString(r.getString("review_reason"), ""),
                r.getTimestamp("received_at").toInstant().toString(),
                Objects.toString(r.getString("linked_invoice"), ""),
                Objects.toString(r.getString("linked_topup"), "")));
  }

  public Api.BankReceiptPage bankReceiptPage(String query, int page) {
    final int pageSize = 10;
    String search = query == null ? "" : query.trim();
    if (page < 0 || search.length() > 150) throw Failure.bad("Tìm kiếm hoặc trang không hợp lệ.");
    // Escape wildcard characters so a search term is always interpreted literally.
    String pattern = "%" + search.replace("!", "!!").replace("%", "!%")
        .replace("_", "!_") + "%";
    String sql = "WITH filtered AS (SELECT e.provider_id,e.bank_reference,e.amount,e.description,"
        + "e.result,e.review_reason,COALESCE(i.amount,t.amount,s.amount) AS expected_amount,"
        + "COALESCE(e.bank_transaction_at,e.created_at) AS received_at,"
        + "CASE WHEN e.result='MATCHED_INVOICE' THEN i.invoice_number END AS linked_invoice,"
        + "CASE WHEN e.result='MATCHED' THEN COALESCE(CASE WHEN length(s.bank_memo)=18"
        + " THEN s.bank_memo END,t.reference,e.topup_reference) END AS linked_topup"
        + " FROM bank_events e LEFT JOIN invoice_payment_references i ON i.reference=e.invoice_reference"
        + " LEFT JOIN invoices inv ON inv.id=i.invoice_id LEFT JOIN topups t ON t.id=e.topup_id"
        + " LEFT JOIN sepay_topup_requests s ON s.reference=COALESCE(e.topup_reference,t.reference)"
        + " LEFT JOIN sepay_payment_codes p ON p.payment_code=COALESCE(s.payment_code,e.payment_code)"
        + " LEFT JOIN cards c ON c.card_id=COALESCE(e.card_id,t.card_id,s.original_card_id)"
        + " LEFT JOIN refund_items f ON f.id=e.refund_item_id"
        + " LEFT JOIN refund_cases a ON a.id=f.case_id"
        + " LEFT JOIN retired_topup_references r ON r.reference=COALESCE(e.topup_reference,t.reference)"
        + " WHERE e.amount>0 AND (?='' OR concat_ws(' ',e.provider_id,e.bank_reference,e.payment_code,"
        + "e.topup_reference,e.invoice_reference,i.payment_code,i.invoice_number,t.reference,s.bank_memo,"
        + "e.card_id,t.card_id,s.original_card_id,p.original_card_id,a.original_card_id,r.original_card_id,"
        + "inv.card_id,c.student_code,p.student_code,a.student_code,r.student_code,inv.student_code)"
        + " ILIKE ? ESCAPE '!')) SELECT totals.total,receipt.* FROM"
        + " (SELECT count(*) AS total FROM filtered) totals LEFT JOIN LATERAL"
        + " (SELECT * FROM filtered ORDER BY received_at DESC,provider_id DESC LIMIT ? OFFSET ?) receipt"
        + " ON TRUE ORDER BY receipt.received_at DESC,receipt.provider_id DESC";
    var result = repo.db.query(sql, (r, n) -> new Object[] {
        r.getLong("total"), r.getString("provider_id") == null ? null : new Api.BankReceipt(
            r.getString("provider_id"), r.getString("bank_reference"), r.getLong("amount"),
            r.getObject("expected_amount", Long.class), r.getString("description"), r.getString("result"),
            Objects.toString(r.getString("review_reason"), ""), r.getTimestamp("received_at").toInstant().toString(),
            Objects.toString(r.getString("linked_invoice"), ""), Objects.toString(r.getString("linked_topup"), ""))
        }, search, pattern, pageSize, (long) page * pageSize);
    int total = Math.toIntExact((Long) result.getFirst()[0]);
    List<Api.BankReceipt> items = result.stream().map(row -> (Api.BankReceipt) row[1])
        .filter(Objects::nonNull).toList();
    return new Api.BankReceiptPage(items, total, page, pageSize);
  }

  public Api.AdminTransactionPage adminTransactions(String query, int page) {
    return adminTransactions(query, page, null, null, null);
  }

  public Api.AdminTransactionPage adminTransactions(
      String query, int page, String from, String to, Long amount) {
    final int pageSize = 10;
    String search = query == null ? "" : query.trim();
    if (page < 0 || search.length() > 150) throw Failure.bad("Tìm kiếm hoặc trang không hợp lệ.");
    LocalDate start = transactionDate(from), end = transactionDate(to);
    if (start != null && end != null && start.isAfter(end))
      throw Failure.bad("Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.");
    if (amount != null && amount < 0) throw Failure.bad("Số tiền lọc không hợp lệ.");
    String pattern = "%" + search.replace("!", "!!").replace("%", "!%")
        .replace("_", "!_") + "%";
    StringBuilder sql = new StringBuilder("WITH filtered AS (SELECT * FROM admin_transaction_journal WHERE (?='' OR"
        + " concat_ws(' ',reference,bank_reference,provider_id,card_id,student_code,"
        + " CASE WHEN simulated THEN 'DEMO' END) ILIKE ? ESCAPE '!')");
    List<Object> args = new ArrayList<>(List.of(search, pattern));
    ZoneId vietnam = ZoneId.of("Asia/Ho_Chi_Minh");
    if (start != null) {
      sql.append(" AND occurred_at>=?");
      args.add(Timestamp.from(start.atStartOfDay(vietnam).toInstant()));
    }
    if (end != null) {
      sql.append(" AND occurred_at<?");
      args.add(Timestamp.from(end.plusDays(1).atStartOfDay(vietnam).toInstant()));
    } else if (start != null) {
      sql.append(" AND occurred_at<=CURRENT_TIMESTAMP");
    }
    if (amount != null) {
      sql.append(" AND amount=?");
      args.add(amount);
    }
    sql.append(") SELECT totals.total,entry.* FROM (SELECT count(*) AS total FROM filtered) totals"
        + " LEFT JOIN LATERAL (SELECT * FROM filtered ORDER BY occurred_at DESC,source_key DESC"
        + " LIMIT ? OFFSET ?) entry ON TRUE ORDER BY entry.occurred_at DESC,entry.source_key DESC");
    args.add(pageSize);
    args.add((long) page * pageSize);
    var rows = repo.db.query(sql.toString(), (r, n) -> new Object[] {r.getLong("total"),
        r.getString("source_key") == null ? null : new Api.AdminTransaction(
            r.getString("source_key"), r.getString("kind"), r.getTimestamp("occurred_at").toInstant().toString(),
            Objects.toString(r.getString("reference"), ""), Objects.toString(r.getString("card_id"), ""),
            Objects.toString(r.getString("student_code"), ""), r.getLong("amount"),
            Objects.toString(r.getString("status"), ""), Objects.toString(r.getString("reason"), ""),
            r.getBoolean("simulated"))}, args.toArray());
    int total = Math.toIntExact((Long) rows.getFirst()[0]);
    List<Api.AdminTransaction> items = rows.stream().map(row -> (Api.AdminTransaction) row[1])
        .filter(Objects::nonNull).toList();
    return new Api.AdminTransactionPage(items, total, page, pageSize);
  }

  private static LocalDate transactionDate(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
      LocalDate date = LocalDate.parse(value);
      if (date.getYear() < 1) throw new IllegalArgumentException();
      return date;
    } catch (IllegalArgumentException | java.time.format.DateTimeParseException invalid) {
      throw Failure.bad("Ngày lọc không hợp lệ.");
    }
  }

  public Api.Invoice createInvoice(Api.CreateInvoice request, String terminal) {
    if (request == null
        || request.items() == null
        || request.items().isEmpty()
        || request.items().size() > 50) throw Failure.bad("Vui lòng chọn từ 1 đến 50 món.");
    cards.stations.requireActive(terminal, false);
    String code = Objects.toString(request.studentCode(), "").strip();
    code = code.isEmpty() ? "" : SePayGateway.studentCode(code);
    Profile student = code.isEmpty() ? null : repo.student(code);
    String fingerprintCode = code;
    UUID requestId =
        request.requestId() == null ? UUID.randomUUID() : MenuService.uuid(request.requestId());
    String fingerprint;
    try {
      fingerprint =
          security.keyed(SecurityMaterial.JSON.writeValueAsString(List.of(fingerprintCode, request.items())));
    } catch (Exception e) {
      throw Failure.bad("Dữ liệu hóa đơn không hợp lệ.");
    }
    long invoiceId =
        repo.tx.execute(
            t -> {
              cards.stations.requireActive(terminal, true);
              var existing =
                  repo.db.queryForList(
                      "SELECT id,request_payload FROM invoices WHERE station_session_id=? AND"
                          + " request_id=?",
                      MenuService.uuid(terminal),
                      requestId);
              if (!existing.isEmpty()) {
                if (!fingerprint.equals(existing.getFirst().get("request_payload")))
                  throw new Failure(409, "REQUEST_REUSED", "Mã yêu cầu đã dùng với hóa đơn khác.");
                return ((Number) existing.getFirst().get("id")).longValue();
              }
              if (Boolean.TRUE.equals(repo.db.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM invoices WHERE station_session_id=?::uuid"
                      + " AND status IN('OPEN','PAYMENT_PENDING','CANCEL_PENDING'))",
                  Boolean.class, terminal)))
                throw new Failure(409, "INVOICE_ACTIVE",
                    "Hóa đơn trước vẫn đang chờ xử lý. Hãy hoàn tất hoặc hủy trước khi tạo hóa đơn mới.");
              if (student != null) {
                repo.db.queryForList(
                    "SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", student.cardId());
                repo.requireActive(student.cardId());
              }
              catalog.lock();
              List<Api.InvoiceLine> lines = new ArrayList<>();
              Set<Long> seen = new HashSet<>();
              long total = 0;
              for (Api.OrderItem item : request.items()) {
                if (item == null
                    || item.quantity() < 1
                    || item.quantity() > 99
                    || !seen.add(item.itemId()))
                  throw Failure.bad("Số lượng hoặc món không hợp lệ.");
                var m = catalog.item(item.itemId());
                if (item.revision() != null && item.revision() != m.revision())
                  throw MenuService.conflict(
                      "Món hoặc giá đã thay đổi. Tải lại thực đơn và xem lại giỏ.");
                long value = Math.multiplyExact(m.price(), item.quantity());
                total = Math.addExact(total, value);
                lines.add(new Api.InvoiceLine(m.name(), item.quantity(), m.price(), value));
              }
              if (total > 100_000_000) throw Failure.bad("Hóa đơn vượt giới hạn số tiền.");
              long id =
                  repo.db.queryForObject(
                      "INSERT INTO"
                          + " invoices(card_id,student_code,full_name,total,status,expires_at,terminal_id,station_session_id,request_id,request_payload,created_at)"
                          + " VALUES(?,?,?,?,'OPEN',clock_timestamp()+interval '5"
                          + " minutes',?,?::uuid,?,?,clock_timestamp()) RETURNING id",
                      Long.class,
                      student == null ? null : student.cardId(),
                      student == null ? null : student.studentCode(),
                      student == null ? null : student.fullName(),
                      total,
                      terminal,
                      terminal,
                      requestId,
                      fingerprint);
              for (int n = 0; n < lines.size(); n++) {
                var line = lines.get(n);
                repo.db.update(
                    "INSERT INTO invoice_lines(invoice_id,item_id,name,quantity,unit_price)"
                        + " VALUES(?,?,?,?,?)",
                    id,
                    request.items().get(n).itemId(),
                    line.name(),
                    line.quantity(),
                    line.unitPrice());
              }
              invoiceBank.register(id);
              return id;
            });
    return refreshQr(terminal, invoiceId, false);
  }

  public Api.Invoice refreshQr(String terminal, long id) {
    return refreshQr(terminal, id, true);
  }

  private Api.Invoice refreshQr(String terminal, long id, boolean reload) {
    requireOwner(terminal, id);
    cards.stations.requireActive(terminal, false);
    var invoice = readInvoice(id);
    if (!Set.of("OPEN", "PAYMENT_PENDING").contains(invoice.status())
        || invoice.paymentPhase().equals("CARD_PROCESSING")
        || invoice.expiresAt() <= System.currentTimeMillis()) return invoice;
    if (!invoice.paymentReference().isEmpty()
        && (reload || invoice.qrDataUrl().isEmpty())
        && bank.ready()
        && bank.confirmationReady()) {
      try {
        String image = bank.fullQr(invoice.total(), invoice.paymentReference());
        repo.db.update(
            "UPDATE invoice_payment_references SET qr_data_url=? WHERE invoice_id=? AND"
                + " qr_data_url=?",
            image,
            id,
            invoice.qrDataUrl());
      } catch (Failure unavailable) {
        if (!Set.of("BANK_UNAVAILABLE", "SEPAY_NOT_CONFIGURED").contains(unavailable.code))
          throw unavailable;
      }
    }
    return readInvoice(id);
  }

  public Api.StationSession openStation(Api.OpenStation input) {
    var result = cards.stations.open(input);
    cards.payments.finishEmptyCancellations();
    return result;
  }

  public Api.StationSession heartbeat(String terminal) {
    return cards.stations.heartbeat(terminal);
  }

  public void closeStation(String terminal) {
    cards.readers.closeStation(terminal, () -> {
      cards.stations.close(terminal);
      cards.payments.finishEmptyCancellations();
    });
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
  public void expireInvoices() {
    cards.stations.expire();
    var ids =
        repo.db.queryForList(
            "SELECT i.id FROM invoices i WHERE i.status IN('OPEN','PAYMENT_PENDING') AND i.outcome"
                + " IS NULL AND i.expires_at<=clock_timestamp() AND NOT EXISTS(SELECT 1 FROM"
                + " wallet_operations w JOIN card_operations c ON c.wallet_operation_id=w.id WHERE"
                + " w.invoice_id=i.id AND c.phase"
                + " IN('COMMIT_DECIDED','CARD_COMMITTED','DB_COMMITTED','SYNCED','CONFLICT')) ORDER"
                + " BY i.id",
            Long.class);
    for (long id : ids) cards.payments.cancel(id, "QR_EXPIRED");
    cards.payments.finishEmptyCancellations();
    invoiceBank.reconcile();
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1200)
  public void recoverPayments() {
    for (String terminal : cards.readers.connectedOwners()) recoverPayments(terminal);
  }

  private void recoverPayments(String terminal) {
    try {
      cards.readers.inspectTopup(
          terminal,
          c -> {
            Api.ReaderStatus status = c.status("");
            long bound = cards.readers.currentPayment(terminal);
            if ((!status.initialized() && bound == 0)
                || !needsReaderRecovery(status.cardId(), bound)) return null;
            // Re-enter as a critical operation before any recovery can write the card.
            return cards.readers.use(terminal, card -> {
              try {
                cards.registered(card);
              } finally {
                long currentBound = cards.readers.currentPayment(terminal);
                if (currentBound > 0
                    && repo.db.queryForObject(
                            "SELECT count(*) FROM invoices WHERE id=? AND status"
                                + " IN('OPEN','PAYMENT_PENDING') AND outcome IS NULL",
                            Long.class,
                            currentBound)
                        == 0) cards.readers.endPayment(terminal, currentBound);
              }
              return null;
            });
          });
    } catch (Failure expected) {
      /* The durable outcome stays pending until the correct card is available. */
    } catch (RuntimeException e) {
      org.slf4j.LoggerFactory.getLogger(WalletService.class)
          .warn("Chưa phục hồi được thanh toán ({})", e.getClass().getSimpleName());
    }
  }

  private boolean needsReaderRecovery(String cardId, long boundInvoice) {
    return Boolean.TRUE.equals(repo.db.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM cards c WHERE c.card_id=? AND"
            + " (c.pending_json IS NOT NULL OR c.sync_status IS DISTINCT FROM 'SYNCED' OR NOT"
            + " EXISTS(SELECT 1 FROM card_wallets cw WHERE cw.card_id=c.card_id)))"
            + " OR EXISTS (SELECT 1 FROM card_operations co LEFT JOIN wallet_operations w"
            + " ON w.id=co.wallet_operation_id LEFT JOIN topups t ON t.id=w.topup_id"
            + " WHERE co.card_id=? AND (co.phase NOT IN ('SYNCED','ABORTED') OR"
            + " (co.phase='SYNCED' AND w.id IS NOT NULL AND (w.status<>'APPLIED' OR"
            + " w.receipt IS NULL OR (co.proof IS NOT NULL AND w.receipt IS DISTINCT FROM"
            + " replace(encode(co.proof,'base64'),chr(10),'')) OR"
            + " (w.topup_id IS NOT NULL AND t.status IS DISTINCT FROM 'CREDITED')))))"
            + " OR EXISTS (SELECT 1 FROM invoices i LEFT JOIN payment_attempts a ON a.invoice_id=i.id"
            + " WHERE i.card_id=? AND (i.status='CANCEL_PENDING' OR (a.released=false AND"
            + " (i.outcome IS NOT NULL OR a.decision IS NOT NULL OR a.debit_wallet_id IS NOT NULL"
            + " OR i.payment_phase='CARD_PROCESSING'))))"
            + " OR (? > 0 AND NOT EXISTS(SELECT 1 FROM invoices WHERE id=? AND"
            + " status IN('OPEN','PAYMENT_PENDING') AND outcome IS NULL))",
        Boolean.class, cardId, cardId, cardId, boundInvoice, boundInvoice));
  }

  public Api.Invoice invoice(long id) {
    expireInvoices();
    return readInvoice(id);
  }

  public List<Api.Invoice> currentInvoices(String terminal) {
    expireInvoices();
    cards.stations.requireActive(terminal, false);
    return repo.db.queryForList(
        "SELECT id FROM invoices WHERE station_session_id=?::uuid AND"
            + " status IN ('OPEN','PAYMENT_PENDING','CANCEL_PENDING') ORDER BY id",
        Long.class, terminal).stream().map(this::readInvoice).toList();
  }

  private Api.Invoice readInvoice(long id) {
    var list = repo.db.queryForList("SELECT * FROM invoices WHERE id=?", id);
    if (list.isEmpty()) throw Failure.missing("Không tìm thấy hóa đơn.");
    var r = list.getFirst();
    var lines =
        repo.db.query(
            "SELECT * FROM invoice_lines WHERE invoice_id=? ORDER BY line_id",
            (rs, n) ->
                new Api.InvoiceLine(
                    rs.getString("name"),
                    rs.getInt("quantity"),
                    rs.getLong("unit_price"),
                    rs.getInt("quantity") * rs.getLong("unit_price")),
            id);
    long total = ((Number) r.get("total")).longValue(),
        expires = ((Timestamp) r.get("expires_at")).getTime();
    String status = (String) r.get("status");
    String phase = Objects.toString(r.get("payment_phase"), "NONE");
    var refs =
        repo.db.queryForList(
            "SELECT payment_code,qr_data_url FROM invoice_payment_references WHERE invoice_id=?",
            id);
    String reference =
        refs.isEmpty() ? "" : Objects.toString(refs.getFirst().get("payment_code"), "");
    if (!SePayGateway.validMemo(reference)) reference = "";
    String image = reference.isEmpty() ? "" : (String) refs.getFirst().get("qr_data_url");
    return new Api.Invoice(
        id,
        String.format("HD%08d", id),
        Objects.toString(r.get("student_code"), ""),
        Objects.toString(r.get("full_name"), ""),
        Objects.toString(r.get("card_id"), ""),
        total,
        status,
        lines,
        Instant.now().toEpochMilli(),
        expires,
        Set.of("OPEN", "PAYMENT_PENDING").contains(status)
                && !phase.equals("CARD_PROCESSING")
                && expires > System.currentTimeMillis()
            ? image
            : "",
        Objects.toString(r.get("cancel_reason"), ""),
        ((Number) r.get("revision")).longValue(),
        Objects.toString(r.get("station_session_id"), ""),
        reference,
        Objects.toString(r.get("payment_method"), ""),
        phase);
  }

  public List<Api.Invoice> recent() {
    expireInvoices();
    return repo
        .db
        .queryForList("SELECT id FROM invoices ORDER BY id DESC LIMIT 50", Long.class)
        .stream()
        .map(this::readInvoice)
        .toList();
  }

  public Api.InvoicePage invoicePage(
      String terminal, String query, int page, String from, String to, Long amount) {
    cards.stations.requireActive(terminal, false);
    final int pageSize = 10;
    String search = query == null ? "" : query.strip();
    if (page < 0 || search.length() > 150) throw Failure.bad("Tìm kiếm hoặc trang không hợp lệ.");
    LocalDate start = transactionDate(from), end = transactionDate(to);
    if (start != null && end != null && start.isAfter(end))
      throw Failure.bad("Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.");
    if (amount != null && amount < 0) throw Failure.bad("Số tiền lọc không hợp lệ.");
    String pattern = "%" + search.replace("!", "!!").replace("%", "!%")
        .replace("_", "!_") + "%";
    String number = "('HD'||lpad(i.id::text,GREATEST(8,length(i.id::text)),'0'))";
    StringBuilder sql = new StringBuilder("WITH filtered AS (SELECT i.id,i.created_at,i.total,"
        + "i.status,i.payment_method,i.payment_phase,i.cancel_reason," + number + " AS number,"
        + "COALESCE(r.payment_code,'') AS payment_reference FROM invoices i LEFT JOIN"
        + " invoice_payment_references r ON r.invoice_id=i.id WHERE (?='' OR "
        + number + " ILIKE ? ESCAPE '!' OR COALESCE(r.payment_code,'') ILIKE ? ESCAPE '!')");
    List<Object> args = new ArrayList<>(List.of(search, pattern, pattern));
    ZoneId vietnam = ZoneId.of("Asia/Ho_Chi_Minh");
    if (start != null) {
      sql.append(" AND i.created_at>=?");
      args.add(Timestamp.from(start.atStartOfDay(vietnam).toInstant()));
    }
    if (end != null) {
      sql.append(" AND i.created_at<?");
      args.add(Timestamp.from(end.plusDays(1).atStartOfDay(vietnam).toInstant()));
    }
    if (amount != null) {
      sql.append(" AND i.total=?");
      args.add(amount);
    }
    sql.append(") SELECT totals.total AS match_total,entry.* FROM"
        + " (SELECT count(*) AS total FROM filtered) totals LEFT JOIN LATERAL"
        + " (SELECT * FROM filtered ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?) entry"
        + " ON TRUE ORDER BY entry.created_at DESC,entry.id DESC");
    args.add(pageSize);
    args.add((long) page * pageSize);
    var rows = repo.db.query(sql.toString(), (r, n) -> new Object[] {r.getLong("match_total"),
        r.getObject("id") == null ? null : new Api.InvoiceSummary(
            r.getLong("id"), r.getString("number"), r.getString("payment_reference"),
            r.getTimestamp("created_at").toInstant().toString(), r.getLong("total"),
            r.getString("status"), Objects.toString(r.getString("payment_method"), ""),
            Objects.toString(r.getString("payment_phase"), ""),
            Objects.toString(r.getString("cancel_reason"), ""))}, args.toArray());
    int total = Math.toIntExact((Long) rows.getFirst()[0]);
    List<Api.InvoiceSummary> items = rows.stream().map(row -> (Api.InvoiceSummary) row[1])
        .filter(Objects::nonNull).toList();
    return new Api.InvoicePage(items, total, page, pageSize);
  }

  private void requireOwner(String terminal, long id) {
    cards.stations.requireOwner(terminal, id);
  }

  private Api.Invoice payable(String terminal, long id) {
    requireOwner(terminal, id);
    cards.stations.requireActive(terminal, false);
    var i = invoice(id);
    if (!Set.of("OPEN", "PAYMENT_PENDING").contains(i.status()))
      throw new Failure(
          409,
          i.cancelReason().equals("QR_EXPIRED") ? "QR_EXPIRED" : "INVOICE_CLOSED",
          "Hóa đơn không còn chờ thanh toán. Vui lòng tạo hóa đơn mới.");
    return i;
  }

  public Api.ReaderStatus preparePayment(String terminal, long id) {
    requireOwner(terminal, id);
    return cards.readers.use(
        terminal,
        c -> {
            var i = payable(terminal, id);
            var stored = cards.registered(c);
            if ((!i.cardId().isEmpty() && !i.cardId().equals(stored.id()))
                || (!i.studentCode().isEmpty()
                    && !i.studentCode().equals(stored.current().studentCode())))
              throw new Failure(409, "WRONG_STUDENT", "Thẻ không thuộc sinh viên trên hóa đơn.");
            cards.preparePayment(terminal, c, stored, id);
            if (i.status().equals("OPEN")) cards.payments.begin(terminal, id, stored.id());
            else cards.payments.rebindAuthentication(terminal, id, stored.id());
            return cards.readers.status(terminal);
        });
  }

  public Api.Invoice pay(String terminal, long id, String pin) {
    return pay(terminal, id, new Api.Pin(pin));
  }

  public Api.Invoice pay(String terminal, long id, Api.Pin input) {
    Wire.pin(input.pin());
    requireOwner(terminal, id);
    return cards.readers.use(
        terminal,
        c -> {
            boolean claimed = false;
            try {
              var existing = readInvoice(id);
              if (existing.status().equals("PAID")) return existing;
              var i = payable(terminal, id);
              var stored = cards.registered(c);
              if ((!i.cardId().isEmpty() && !i.cardId().equals(stored.id()))
                  || (!i.studentCode().isEmpty()
                      && !i.studentCode().equals(stored.current().studentCode())))
                throw new Failure(409, "WRONG_STUDENT", "Thẻ không thuộc sinh viên trên hóa đơn.");
              cards.preparePayment(terminal, c, stored, id);
              if (i.status().equals("OPEN")) cards.payments.begin(terminal, id, stored.id());
              cards.payments.requireAttempt(terminal, id);
              cards.payments.claimCardProcessing(terminal, id, stored.id());
              claimed = true;
              cards.sync.checkpoint("PAYMENT_PIN_STARTED");
              cards.verifyStudentPin(terminal, c, stored, input, "PAYMENT");
              cards.readers.paymentSession(terminal);
              cards.payments.debit(terminal, c, stored, id, i.total());
              return readInvoice(id);
            } catch (RuntimeException failure) {
              if (readInvoice(id).status().equals("PAID"))
                return readInvoice(id); // A successful result survives a lost final response.
              if (claimed) {
                boolean safeToRetry = cards.payments.resumeAfterFailure(id);
                if (safeToRetry && (!(failure instanceof Failure f) || !f.code.equals("PIN_WRONG")))
                  cards.payments.endAuthentication(id);
                invoiceBank.reconcile();
                if (readInvoice(id).status().equals("PAID")) return readInvoice(id);
              }
              throw failure;
            } finally {
              // Keep cleanup inside the reader lock so it cannot log out a later operation.
              try { cards.readers.checkoutFinished(terminal); } catch (RuntimeException ignored) {}
            }
        });
  }

  public void endPayment(String terminal, long id) {
    requireOwner(terminal, id);
    cards.payments.endAuthentication(id);
    try { cards.readers.endPayment(terminal, id); } catch (RuntimeException ignored) {}
    invoiceBank.reconcile();
  }

  public void cancelInvoice(String terminal, long id) {
    requireOwner(terminal, id);
    cards.stations.cancelByUser(id);
    cards.payments.finishEmptyCancellations();
    try {
      cards.readers.use(
          terminal,
          c -> {
            cards.registered(c);
            cards.readers.endPayment(terminal, id);
            return null;
          });
    } catch (RuntimeException pending) {
    }
  }

  public Api.Topup createTopup(String terminal, long amount) {
    return createTopup(terminal, amount, false, false);
  }

  public Api.TopupQr createTopupQr(String terminal, long amount) {
    return createTopupQr(terminal, amount, false);
  }

  public Api.TopupQr createTopupQr(String terminal, long amount, boolean fullImage) {
    Api.Topup topup = createTopup(terminal, amount, !fullImage, fullImage);
    long remainingMillis = repo.db.queryForObject(
        "SELECT CASE WHEN status='WAITING_BANK' THEN"
            + " CEIL(GREATEST(0, EXTRACT(EPOCH FROM (expires_at-clock_timestamp()))*1000))::bigint"
            + " ELSE 0 END FROM topups WHERE id=?",
        Long.class, topup.id());
    return new Api.TopupQr(topup, remainingMillis);
  }

  private Api.Topup createTopup(String terminal, long amount, boolean qrOnly, boolean fullImage) {
    Wire.operation(1, amount);
    if (!bank.ready())
      throw new Failure(
          503,
          "BANK_UNAVAILABLE",
          "Chức năng nạp tiền ngân hàng chưa sẵn sàng. Vui lòng liên hệ quản trị hệ thống.");
    return cards.readers.useTopup(
        terminal,
        card -> {
          CardRepository.Stored stored = cards.student(card);
          cards.readers.requireStudent(terminal);
          releaseFinishedTopupReservation();
          long balance = cards.sync.settled(card, stored).balance();
          if (amount > Integer.MAX_VALUE - balance)
            throw Failure.bad("Số tiền nạp vượt giới hạn số dư của thẻ.");
          try {
            Api.Topup topup =
                repo.tx.execute(
                    t -> {
                      repo.db.queryForList(
                          "SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", stored.id());
                      repo.requireActive(stored.id());
                      if (!repo.db.queryForList("SELECT id FROM topups WHERE card_id=? AND"
                          + " status='BANK_RECEIVED' LIMIT 1", Long.class, stored.id()).isEmpty())
                        throw Failure.bad("Khoản nạp trước đang chờ ghi vào thẻ.");
                      repo.db.update(
                          "UPDATE topups SET status='EXPIRED' WHERE card_id=? AND"
                              + " status='WAITING_BANK' AND expires_at<=clock_timestamp()",
                          stored.id());
                      List<Long> old =
                          repo.db.queryForList(
                              "SELECT id FROM topups WHERE card_id=? AND status='WAITING_BANK'"
                                  + " ORDER BY id DESC",
                              Long.class,
                              stored.id());
                      if (!old.isEmpty()) {
                        if (bank.sepay().enabled())
                          sepay.register(
                              old.getFirst(), stored.id(), stored.current().studentCode());
                        Api.Topup pending = topup(old.getFirst());
                        if (pending.amount() != amount)
                          throw Failure.bad(
                              "Đang có yêu cầu nạp chờ chuyển khoản. Vui lòng dùng yêu cầu đó hoặc"
                                  + " hủy chờ.");
                        return pending;
                      }
                      long id =
                          repo.db.queryForObject(
                              "SELECT nextval(pg_get_serial_sequence('topups','id'))", Long.class);
                      String ref = String.format("TTMNAP N%08d", id);
                      if (ref.length() > 25) throw Failure.bad("Đã hết dải mã nạp tiền demo.");
                      repo.db.update(
                          "INSERT INTO topups(id,card_id,reference,amount,status,expires_at) VALUES"
                              + " (?,?,?,?,'WAITING_BANK',clock_timestamp()+interval '300 seconds')",
                          id,
                          stored.id(),
                          ref,
                          amount);
                      if (bank.sepay().enabled())
                        sepay.register(id, stored.id(), stored.current().studentCode());
                      return topup(id);
                    });
            String qr = fullImage
                ? bank.fullQr(topup.amount(), topup.reference())
                : bank.qr(topup.amount(), topup.reference());
            if (qrOnly) qr = BankImage.qrOnly(qr);
            else if (fullImage) BankImage.qrOnly(qr); // Validate that the full image contains a QR.

            cards.readers.reserveTopup(terminal, stored.id(), topup.id());

            return new Api.Topup(
                topup.id(),
                topup.reference(),
                topup.cardId(),
                topup.amount(),
                topup.status(),
                qr,
                bank.confirmationReady()
                    ? "Quét mã và xác nhận chuyển khoản trong ứng dụng ngân hàng."
                    : "QR chuyển khoản thật. Tự động xác nhận chưa sẵn sàng; tiền chưa được ghi vào"
                        + " thẻ cho tới khi có xác nhận ngân hàng hợp lệ.");
          } catch (RuntimeException e) {
            throw e;
          }
        });
  }

  public Api.Topup topup(long id) {
    List<Api.Topup> list =
        repo.db.query(
            "SELECT t.*,COALESCE(s.bank_memo,s.payment_code,t.reference) AS display_reference FROM"
                + " topups t LEFT JOIN sepay_topup_requests s ON s.reference=t.reference WHERE"
                + " t.id=?",
            (r, n) ->
                new Api.Topup(
                    r.getLong("id"),
                    r.getString("display_reference"),
                    r.getString("card_id"),
                    r.getLong("amount"),
                    r.getString("status"),
                    "",
                    switch (r.getString("status")) {
                      case "BANK_RECEIVED" -> "Đã nhận tiền. Đang chờ ghi vào thẻ.";
                      case "CREDITED" -> "Đã nạp tiền vào thẻ.";
                      case "EXPIRED" -> "Đã hết thời gian chờ. Giao dịch về muộn sẽ được đối soát.";
                      case "REVIEW" ->
                          "Yêu cầu nạp đã hủy. Liên hệ quản trị viên để đối soát khoản chuyển.";
                      default -> "Đang chờ chuyển khoản.";
                    }),
            id);
    if (list.isEmpty()) throw Failure.missing("Không tìm thấy yêu cầu nạp.");
    return list.getFirst();
  }

  public List<Api.Topup> pending(String terminal) {
    return cards.readers.useTopup(
        terminal,
        card -> {
          CardRepository.Stored stored = cards.student(card);
          repo.db.update(
              "UPDATE topups SET status='EXPIRED' WHERE card_id=? AND status='WAITING_BANK' AND"
                  + " expires_at<=clock_timestamp()",
              stored.id());
          List<Api.Topup> results = repo
              .db
              .queryForList(
                  "SELECT id FROM topups WHERE card_id=? AND status IN"
                      + " ('BANK_RECEIVED','WAITING_BANK','REVIEW','EXPIRED') ORDER BY id DESC"
                      + " LIMIT 20",
                  Long.class,
                  stored.id())
              .stream()
              .map(this::topup)
              .toList();
          // Only received money is resumed automatically by the student screen. An
          // old unpaid request is reserved again when its QR is explicitly reopened.
          results.stream().filter(t -> "BANK_RECEIVED".equals(t.status()))
              .findFirst().ifPresent(t -> cards.readers.reserveTopup(terminal, stored.id(), t.id()));
          return results;
        });
  }

  public Api.Topup state(String terminal, long id) {
    return cards.readers.useTopup(
        terminal,
        card -> {
          CardRepository.Stored stored = cards.student(card);
          repo.db.update(
              "UPDATE topups SET status='EXPIRED' WHERE id=? AND card_id=?"
                  + " AND status='WAITING_BANK' AND expires_at<=clock_timestamp()",
              id, stored.id());
          Api.Topup result = topup(id);
          if (!stored.id().equals(result.cardId())) throw Failure.denied();
          if (Set.of("WAITING_BANK", "BANK_RECEIVED").contains(result.status()))
            cards.readers.reserveTopup(terminal, stored.id(), id);
          else cards.readers.releaseTopup(id);
          return result;
        });
  }

  public Api.Topup apply(String terminal, long id) {
    return cards.readers.useTopup(
        terminal,
        card -> {
          CardRepository.Stored stored = cards.student(card);
          Api.Topup topup = topup(id);
          if (!stored.id().equals(topup.cardId())) throw Failure.denied();
          if (topup.status().equals("CREDITED")) {
            cards.readers.releaseTopup(id);
            return topup;
          }
          if (!topup.status().equals("BANK_RECEIVED"))
            throw Failure.bad("Chưa có xác nhận tiền vào hợp lệ.");
          cards.readers.requireStudent(terminal);
          executeCredit(terminal, card, stored, topup.amount(), id);
          Api.Topup result = topup(id);
          if (result.status().equals("CREDITED")) cards.readers.releaseTopup(id);
          return result;
        });
  }

  public void processReceivedCredits() {
    processOneReceivedCredit();
  }

  record CreditAttempt(long topupId, boolean credited, String error) {}

  CreditAttempt processOneReceivedCredit() {
    try {
      releaseFinishedTopupReservation();
      CreditAttempt firstFailure = null;
      for (String terminal : cards.readers.currentStudents()) {
        long[] attempted = {0};
        try {
          CreditAttempt result = cards.readers.useTopup(
              terminal,
              card -> {
                String id = card.status("").cardId();
                List<Long> waiting = repo.db.queryForList(
                    "SELECT id FROM topups WHERE card_id=? AND status='BANK_RECEIVED' ORDER BY id LIMIT 1",
                    Long.class, id);
                if (waiting.isEmpty()) return new CreditAttempt(0, false, "");
                attempted[0] = waiting.getFirst();
                Api.Topup topup = apply(terminal, attempted[0]);
                return topup.status().equals("CREDITED")
                    ? new CreditAttempt(attempted[0], true, "")
                    : new CreditAttempt(attempted[0], false, "SYNC_REQUIRED");
              });
          if (result.credited()) return result;
          if (result.topupId() != 0 && firstFailure == null) firstFailure = result;
        } catch (Failure expected) {
          if (firstFailure == null) firstFailure = new CreditAttempt(attempted[0], false, expected.code);
        }
      }
      return firstFailure != null ? firstFailure : new CreditAttempt(0, false, "");
    } catch (RuntimeException unexpected) {
      org.slf4j.LoggerFactory.getLogger(WalletService.class)
          .warn("Chưa xử lý được khoản nạp đang chờ ({})", unexpected.getClass().getSimpleName());
      return new CreditAttempt(0, false, "TEMPORARY");
    }
  }

  private void releaseFinishedTopupReservation() {
    for (long id : cards.readers.reservedTopups()) {
      repo.db.update("UPDATE topups SET status='EXPIRED' WHERE id=? AND status='WAITING_BANK'"
          + " AND expires_at<=clock_timestamp()", id);
      List<String> statuses = repo.db.queryForList("SELECT status FROM topups WHERE id=?", String.class, id);
      if (statuses.isEmpty() || !Set.of("WAITING_BANK", "BANK_RECEIVED").contains(statuses.getFirst()))
        cards.readers.releaseTopup(id);
    }
  }

  public Api.TopupCancellation cancelTopup(String terminal, long id) {
    return cards.readers.useTopup(
        terminal,
        card -> {
          CardRepository.Stored stored = cards.student(card);
          Api.Topup t = topup(id);
          if (!stored.id().equals(t.cardId())) throw Failure.denied();
          Api.TopupCancellation result = repo.tx.execute(
              tx -> {
                BankMemos.lock(repo, t.reference());
                repo.db.queryForList(
                    "SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", stored.id());
                int changed = repo.db.update(
                    "UPDATE topups SET status='EXPIRED'"
                        + " WHERE id=? AND card_id=? AND status='WAITING_BANK'",
                    id, stored.id());
                if (changed != 0) {
                  return new Api.TopupCancellation(
                      "EXPIRED", "Đã hủy yêu cầu nạp. Khoản chuyển về muộn sẽ được đối soát.");
                }
                Api.Topup current = topup(id);
                return new Api.TopupCancellation(current.status(), current.message());
              });
          if (!Set.of("WAITING_BANK", "BANK_RECEIVED").contains(result.status()))
            cards.readers.releaseTopup(id);
          return result;
        });
  }

  public void sepayEvent(String raw, String authorization) {
    sepay.receive(raw, authorization);
    releaseFinishedTopupReservation();
  }

  public void bankEvent(Map<String, Object> event) {
    String provider = Objects.toString(event.get("id"), ""),
        reference = Objects.toString(event.get("reference"), ""),
        account = Objects.toString(event.get("accountNumber"), ""),
        description = Objects.toString(event.get("description"), "");
    long amount;
    try {
      amount = new BigDecimal(Objects.toString(event.get("amount"), "0")).longValueExact();
    } catch (RuntimeException e) {
      throw Failure.bad("Số tiền ngân hàng không hợp lệ.");
    }
    if (provider.isBlank()
        || reference.isBlank()
        || provider.length() > 100
        || reference.length() > 150
        || description.length() > 500
        || !account.equals(bank.account()))
      throw Failure.bad("Thông báo tiền vào không khớp tài khoản nhận.");
    Matcher matcher =
        Pattern.compile("TTMNAPN([0-9]{8})(?![0-9])")
            .matcher(description.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""));
    String ref = matcher.find() ? "TTMNAP N" + matcher.group(1) : null;
    repo.tx.executeWithoutResult(
        t -> {
          List<Map<String, Object>> active =
              ref == null
                  ? List.of()
                  : repo.db.queryForList("SELECT card_id FROM topups WHERE reference=?", ref);
          String cardId = active.isEmpty() ? null : (String) active.getFirst().get("card_id");
          if (cardId != null
              && repo.db
                  .queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", cardId)
                  .isEmpty()) cardId = null;
          int inserted =
              repo.db.update(
                  "INSERT INTO"
                      + " bank_events(provider_id,bank_reference,account_token,amount,description,result,topup_reference,review_reason)"
                      + " VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                  provider,
                  reference,
                  security.keyed("account:" + account),
                  amount,
                  description,
                  amount > 0 ? "REVIEW" : "IGNORED_OUTGOING",
                  ref,
                  ref == null ? "INVALID_TRANSFER_CODE" : "NO_ACTIVE_REQUEST");
          if (inserted == 0 || amount <= 0 || ref == null) return;
          var rows =
              cardId == null
                  ? List.<Map<String, Object>>of()
                  : repo.db.queryForList("SELECT * FROM topups WHERE reference=? FOR UPDATE", ref);
          if (rows.isEmpty()) {
            cards.refunds.acceptArchived(ref, provider, amount);
            return;
          }
          Map<String, Object> row = rows.getFirst();
          repo.db.update("UPDATE bank_events SET card_id=? WHERE provider_id=?", cardId, provider);
          long id = ((Number) row.get("id")).longValue();
          String state = (String) row.get("status");
          if (!state.equals("WAITING_BANK")
              || ((Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now())
              || ((Number) row.get("amount")).longValue() != amount) {
            String reason = state.equals("EXPIRED")
                    || ((Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now())
                ? "REQUEST_EXPIRED"
                : !state.equals("WAITING_BANK") ? "REQUEST_CLOSED" : "AMOUNT_MISMATCH";
            repo.db.update("UPDATE bank_events SET review_reason=? WHERE provider_id=?", reason, provider);
            if (state.equals("WAITING_BANK") || state.equals("EXPIRED"))
              repo.db.update("UPDATE topups SET status='REVIEW' WHERE id=?", id);
            return;
          }
          repo.db.update(
              "UPDATE bank_events SET topup_id=?,result='MATCHED',review_reason=NULL WHERE provider_id=?",
              id,
              provider);
          repo.db.update("UPDATE topups SET status='BANK_RECEIVED' WHERE id=?", id);
        });
    releaseFinishedTopupReservation();
  }

  private void executeCredit(
      String terminal, CardClient card, CardRepository.Stored stored, long amount, long topup) {
    cards.sync.repairWalletRecords(stored.id());
    if (repo.db.queryForObject(
            "SELECT count(*) FROM wallet_operations WHERE topup_id=? AND status='APPLIED'",
            Long.class,
            topup)
        > 0) return;
    cards.sync.settled(card, stored);
    long id =
        repo.tx.execute(
            tx -> {
              repo.db.queryForList(
                  "SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", stored.id());
              repo.requireActive(stored.id());
              var old =
                  repo.db.queryForList(
                      "SELECT * FROM wallet_operations WHERE card_id=? AND status='PENDING'",
                      stored.id());
              if (!old.isEmpty()) {
                var row = old.getFirst();
                if (!Objects.equals(row.get("topup_id"), topup))
                  throw Failure.bad("Có giao dịch khác đang chờ kết quả. Vui lòng đồng bộ lại.");
                return ((Number) row.get("id")).longValue();
              }
              var rows =
                  repo.db.queryForList(
                      "SELECT status,card_id,amount FROM topups WHERE id=? FOR UPDATE", topup);
              if (rows.isEmpty()
                  || !rows.getFirst().get("status").equals("BANK_RECEIVED")
                  || !rows.getFirst().get("card_id").equals(stored.id())
                  || ((Number) rows.getFirst().get("amount")).longValue() != amount)
                throw Failure.bad("Yêu cầu nạp chưa có xác nhận hợp lệ.");
              return repo.db.queryForObject(
                  "INSERT INTO wallet_operations(card_id,kind,amount,topup_id,status)"
                      + " VALUES(?,'CREDIT',?,?,'PENDING') RETURNING id",
                  Long.class,
                  stored.id(),
                  amount,
                  topup);
            });
    try {
      cards.sync.run(
          card,
          stored,
          Wire.CREDIT,
          Wire.operation(id, amount),
          null,
          null,
          id,
          () -> {
            repo.requireActive(stored.id());
            cards.readers.requireStudent(terminal);
          });
    } catch (Failure failure) {
      repo.db.update(
          "UPDATE wallet_operations SET status='FAILED' WHERE id=? AND status='PENDING' AND NOT"
              + " EXISTS(SELECT 1 FROM card_operations WHERE wallet_operation_id=? AND phase NOT"
              + " IN('SYNCED','ABORTED'))",
          id,
          id);
      throw failure;
    }
  }
}
