package vn.edu.smartcard.backend;

import java.io.ByteArrayInputStream;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Collator;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.smartcard.shared.*;

// JdbcTemplate already translates SQL exceptions. No repository AOP proxy is
// needed for this package-private shared transaction context.
@Component
@DependsOn("flyway")
public class CardRepository {
  private static final int TRANSACTION_PAGE_SIZE = 20;
  private static final int STUDENT_TRANSACTION_PAGE_SIZE = 4;
  final JdbcTemplate db;
  final TransactionTemplate tx;
  final SecurityMaterial security;

  public CardRepository(
      JdbcTemplate db, PlatformTransactionManager manager, SecurityMaterial security) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.security = security;
  }

  public record Stored(
      String id, String publicKey, String status, Profile current, Profile pending) {}

  public Stored find(String id) {
    List<Stored> rows =
        db.query(
            "SELECT * FROM cards WHERE card_id=?",
            (r, n) ->
                new Stored(
                    r.getString("card_id"),
                    r.getString("public_key"),
                    r.getString("status"),
                    r.getString("profile_json") == null
                        ? null
                        : load(r.getString("profile_json"), r.getString("photo_base64")),
                    r.getString("pending_json") == null
                        ? null
                        : security.decode(r.getString("pending_json"), Profile.class)),
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Stored byPublicKey(PublicKey key) {
    List<String> ids =
        db.queryForList(
            "SELECT card_id FROM cards WHERE public_key=?",
            String.class,
            Base64.getEncoder().encodeToString(key.getEncoded()));
    return ids.isEmpty() ? null : find(ids.getFirst());
  }

  private Profile load(String json, String photo) {
    Profile p = security.decode(json, Profile.class);
    return new Profile(
        p.cardId(),
        p.studentCode(),
        p.fullName(),
        p.citizenId(),
        p.birthDate(),
        p.className(),
        p.phone(),
        p.email(),
        p.intakeYear(),
        photo == null ? "" : photo,
        p.version());
  }

  public PublicKey key(Stored stored) {
    try {
      return KeyFactory.getInstance("EC")
          .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(stored.publicKey())));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public Profile validate(Profile input) {
    Profile p = input.normalized();
    try {
      var image =
          ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(p.photoBase64())));
      if (image == null || image.getWidth() != 480 || image.getHeight() != 640)
        throw Failure.bad("Ảnh thẻ phải được chuẩn hóa thành 480 × 640 pixel.");
    } catch (java.io.IOException e) {
      throw Failure.bad("Không đọc được ảnh đại diện.");
    }
    return p;
  }

  private void cohort(Profile p) {
    db.update(
        "INSERT INTO cohorts(program,cohort,intake_year) VALUES (?,?,?) ON CONFLICT DO NOTHING",
        p.program(),
        p.cohort(),
        p.intakeYear());
    Integer year =
        db.queryForObject(
            "SELECT intake_year FROM cohorts WHERE program=? AND cohort=?",
            Integer.class,
            p.program(),
            p.cohort());
    if (year == null || year != p.intakeYear())
      throw Failure.bad("Năm nhập học không khớp danh mục khóa đã đăng ký.");
  }

  public Profile reserve(Profile input, PublicKey key) {
    Profile valid = validate(input);
    return tx.execute(t -> {
      Stored existing = byPublicKey(key);
      if (existing != null) {
        if (!existing.status().equals("PROVISIONING"))
          throw Failure.bad("Thẻ này đã được đăng ký.");
        cohort(valid);
        Profile replacement = valid.withCard(existing.id(), 1);
        db.update(
            "UPDATE cards SET student_code=?,citizen_id=?,pending_json=? WHERE card_id=?",
            replacement.studentCode(),
            replacement.citizenId(),
            security.encode(replacement),
            existing.id());
        return replacement;
      }
      cohort(valid);
      long number =
          Objects.requireNonNull(db.queryForObject("SELECT nextval('card_number')", Long.class));
      if (number > 999999) throw Failure.bad("Đã hết dải ID thẻ demo.");
      Profile p = valid.withCard(String.format("TTM%06d", number), 1);
      db.update(
          "INSERT INTO cards(card_id,public_key,student_code,citizen_id,pending_json,status) VALUES"
              + " (?,?,?,?,?,'PROVISIONING')",
          p.cardId(),
          Base64.getEncoder().encodeToString(key.getEncoded()),
          p.studentCode(),
          p.citizenId(),
          security.encode(p));
      db.update(
          "INSERT INTO card_secrets(card_id,backend_share) VALUES (?,?)",
          p.cardId(),
          security.randomBytes(32));
      return p;
    });
  }

  public Profile stage(Profile input) {
    return tx.execute(t -> {
      db.queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", input.cardId());
      requireActive(input.cardId());
      Stored old = find(input.cardId());
      if (old == null || old.current() == null) throw Failure.missing("Không tìm thấy hồ sơ thẻ.");
      if (!old.status().equals("ACTIVE"))
        throw Failure.bad("Thẻ đang chờ đồng bộ. Vui lòng đọc lại thẻ trước khi sửa.");
      if (input.version() != old.current().version())
        throw new Failure(409, "STALE_PROFILE", "Hồ sơ đã thay đổi. Vui lòng đọc lại thông tin.");
      Profile p = validate(input).withCard(input.cardId(), old.current().version() + 1);
      cohort(p);
      db.update(
          "UPDATE cards SET student_code=?,citizen_id=?,pending_json=?,status='PENDING_UPDATE'"
              + " WHERE card_id=?",
          p.studentCode(),
          p.citizenId(),
          security.encode(p),
          p.cardId());
      return p;
    });
  }

  public void finish(Profile profile) {
    tx.executeWithoutResult(t -> {
      Stored old = find(profile.cardId());
      if (old == null || old.pending() == null) return;
      if (!Arrays.equals(ProfileCodec.encode(old.pending()), ProfileCodec.encode(profile)))
        throw new Failure(409, "SYNC_MISMATCH", "Dữ liệu trên thẻ chưa khớp hồ sơ đang chờ.");
      db.update(
          "UPDATE cards SET"
              + " student_code=?,citizen_id=?,profile_json=?,photo_base64=?,class_name=?,program=?,cohort=?,intake_year=?,birth_year=?,profile_version=?,pending_json=NULL,status='ACTIVE'"
              + " WHERE card_id=?",
          profile.studentCode(),
          profile.citizenId(),
          security.encode(profile.withoutPhoto()),
          profile.photoBase64(),
          profile.className(),
          profile.program(),
          profile.cohort(),
          profile.intakeYear(),
          profile.birthYear(),
          profile.version(),
          profile.cardId());
    });
  }

  public void cancelStageWithoutOperation(String id) {
    tx.executeWithoutResult(t -> {
      db.queryForList("SELECT card_id FROM cards WHERE card_id=? FOR UPDATE", id);
      long count =
          db.queryForObject(
              "SELECT count(*) FROM card_operations WHERE card_id=? AND phase NOT IN"
                  + " ('SYNCED','ABORTED')",
              Long.class,
              id);
      if (count == 0) {
        Stored stored = find(id);
        if (stored != null) cancelUnapplied(stored);
      }
    });
  }

  public void cancelUnapplied(Stored stored) {
    if (stored.current() == null) return;
    Profile p = stored.current();
    db.update(
        "UPDATE cards SET student_code=?,citizen_id=?,pending_json=NULL,status='ACTIVE' WHERE"
            + " card_id=?",
        p.studentCode(),
        p.citizenId(),
        p.cardId());
  }

  public Api.CardPage search(
      String text, String group, Integer year, String program, String sort, int page) {
    Api.CardListPage result = searchWithStatus(text, group, year, program, sort, page, 20);
    return new Api.CardPage(result.items(), result.total(), result.page(), result.pageSize());
  }

  private record SearchRow(Profile profile, String lifecycleStatus) {}

  public Api.CardListPage searchWithStatus(
      String text, String group, Integer year, String program, String sort, int page) {
    return searchWithStatus(text, group, year, program, sort, page, 10);
  }

  private Api.CardListPage searchWithStatus(
      String text, String group, Integer year, String program, String sort, int page, int pageSize) {
    if (page < 0 || page > 10000) throw Failure.bad("Trang không hợp lệ.");
    StringBuilder sql =
        new StringBuilder("SELECT profile_json, lifecycle_status FROM cards WHERE profile_json IS NOT NULL");
    List<Object> args = new ArrayList<>();
    if (group != null && !group.isBlank()) {
      sql.append(" AND class_name=?");
      args.add(group.toUpperCase(Locale.ROOT));
    }
    if (year != null) {
      sql.append(" AND birth_year=?");
      args.add(year);
    }
    if (program != null && !program.isBlank()) {
      if (!Set.of("AT", "CT", "DT").contains(program))
        throw Failure.bad("Chương trình không hợp lệ.");
      sql.append(" AND program=?");
      args.add(program);
    }
    sql.append(" ORDER BY card_id LIMIT 10001");
    List<SearchRow> all =
        db.query(
            sql.toString(),
            (r, n) -> new SearchRow(security.decode(r.getString(1), Profile.class), r.getString(2)),
            args.toArray());
    if (all.size() > 10000) throw Failure.bad("Vui lòng bổ sung bộ lọc để giới hạn kết quả.");
    String query = Profile.searchable(text);
    all.removeIf(row ->
        !query.isEmpty()
            && !Profile.searchable(
                    String.join(
                        " ", row.profile().cardId(), row.profile().studentCode(),
                        row.profile().fullName(), row.profile().citizenId(), row.profile().phone()))
                .contains(query));
    Collator vi = Collator.getInstance(Locale.forLanguageTag("vi-VN"));
    Comparator<Profile> order;
    if ("CARD_ID".equals(sort)) {
      order = Comparator.comparing(Profile::cardId);
    } else if ("NAME_ASC".equals(sort) || "NAME_DESC".equals(sort)) {
      order =
          Comparator.comparing(
                  (Profile p) -> p.fullName().substring(p.fullName().lastIndexOf(' ') + 1), vi)
              .thenComparing(Profile::fullName, vi);
      if ("NAME_DESC".equals(sort)) order = order.reversed();
    } else {
      order = Comparator.comparingInt(Profile::intakeYear);
      if (!"OLDEST".equals(sort)) order = order.reversed();
      order =
          order
              .thenComparing(Profile::program)
              .thenComparing(Profile::className)
              .thenComparing(Profile::studentCode);
    }
    all.sort(Comparator.comparing(SearchRow::profile, order.thenComparing(Profile::cardId)));
    int total = all.size(), from = Math.min(total, page * pageSize);
    List<SearchRow> selected = all.subList(from, Math.min(total, from + pageSize));
    Map<String, String> statuses = new LinkedHashMap<>();
    for (SearchRow row : selected) statuses.put(row.profile().cardId(), row.lifecycleStatus());
    return new Api.CardListPage(
        selected.stream().map(SearchRow::profile).toList(), total, page, pageSize, statuses);
  }

  Api.CardTransactionPage transactions(String cardId, Long beforeId) {
    String cursor = beforeId == null ? "" : " AND w.id<?";
    Object[] args =
        beforeId == null
            ? new Object[] {cardId, TRANSACTION_PAGE_SIZE + 1}
            : new Object[] {cardId, beforeId, TRANSACTION_PAGE_SIZE + 1};
    List<Api.CardTransaction> rows =
        db.query(
            """
            SELECT w.id, w.kind, w.amount, w.created_at, w.invoice_id,
                   COALESCE(s.bank_memo,s.payment_code,t.reference) AS reference
            FROM wallet_operations w
            LEFT JOIN topups t ON t.id=w.topup_id AND t.card_id=w.card_id
            LEFT JOIN sepay_topup_requests s ON s.reference=t.reference AND s.original_card_id=w.card_id
            WHERE w.card_id=? AND w.status='APPLIED'
            """
                + cursor + " ORDER BY w.id DESC LIMIT ?",
            (r, n) -> transactionRow(r),
            args);
    if (rows.size() <= TRANSACTION_PAGE_SIZE) return new Api.CardTransactionPage(rows, null);
    return new Api.CardTransactionPage(
        List.copyOf(rows.subList(0, TRANSACTION_PAGE_SIZE)),
        rows.get(TRANSACTION_PAGE_SIZE - 1).id());
  }

  Api.CardTransactionListPage transactionPage(String cardId, int page) {
    long offset = (long) page * STUDENT_TRANSACTION_PAGE_SIZE;
    // Count and rows share one database snapshot, including when a page is empty.
    return db.query(
        """
        SELECT totals.total, w.id, w.kind, w.amount, w.created_at, w.invoice_id,
               COALESCE(s.bank_memo,s.payment_code,t.reference) AS reference
        FROM (SELECT COUNT(*) AS total FROM wallet_operations
              WHERE card_id=? AND status='APPLIED') totals
        LEFT JOIN LATERAL (
          SELECT id, kind, amount, created_at, invoice_id, topup_id, card_id
          FROM wallet_operations WHERE card_id=? AND status='APPLIED'
          ORDER BY id DESC LIMIT ? OFFSET ?
        ) w ON TRUE
        LEFT JOIN topups t ON t.id=w.topup_id AND t.card_id=w.card_id
        LEFT JOIN sepay_topup_requests s ON s.reference=t.reference AND s.original_card_id=w.card_id
        ORDER BY w.id DESC
        """,
        r -> {
          if (!r.next()) throw new IllegalStateException("Thiếu kết quả đếm lịch sử giao dịch.");
          int total = Math.toIntExact(r.getLong("total"));
          List<Api.CardTransaction> items = new ArrayList<>();
          do {
            if (r.getObject("id") != null) items.add(transactionRow(r));
          } while (r.next());
          return new Api.CardTransactionListPage(List.copyOf(items), total, page,
              STUDENT_TRANSACTION_PAGE_SIZE);
        },
        cardId, cardId, STUDENT_TRANSACTION_PAGE_SIZE, offset);
  }

  private static Api.CardTransaction transactionRow(ResultSet r) throws SQLException {
    long id = r.getLong("id");
    Long invoice = r.getObject("invoice_id", Long.class);
    String reference = r.getString("reference");
    if (invoice != null) reference = String.format(Locale.ROOT, "HD%08d", invoice);
    if (reference == null) reference = "GD #" + id;
    return new Api.CardTransaction(id, r.getString("kind"), r.getLong("amount"),
        r.getTimestamp("created_at").toInstant().toString(), reference);
  }

  public Profile student(String code) {
    List<String> ids =
        db.queryForList(
            "SELECT card_id FROM cards WHERE student_code=? AND status='ACTIVE'",
            String.class,
            Profile.text(code).toUpperCase(Locale.ROOT));
    if (ids.isEmpty())
      throw Failure.missing("Mã sinh viên chưa được đăng ký hoặc thẻ đang chờ đồng bộ.");
    return find(ids.getFirst()).current();
  }

  public static final String BLOCKED_MESSAGE =
      "Thẻ hiện đang bị tạm khoá! Vui lòng liên hệ với admin";

  public Api.CardLifecycle lifecycle(String id) {
    var rows =
        db.query(
            "SELECT lifecycle_status,pin_failures FROM cards WHERE card_id=?",
            (r, n) -> new Api.CardLifecycle(id, r.getString(1), r.getInt(2)),
            id);
    if (rows.isEmpty()) throw Failure.missing("Thẻ chưa đăng ký trong hệ thống.");
    return rows.getFirst();
  }

  public void requireActive(String id) {
    if (!lifecycle(id).status().equals("ACTIVE"))
      throw new Failure(423, "CARD_BLOCKED", BLOCKED_MESSAGE);
  }

  public byte[] share(String id) {
    return db.queryForObject(
        "SELECT backend_share FROM card_secrets WHERE card_id=?", byte[].class, id);
  }

  public void audit(long admin, String card, String action, String result) {
    db.update(
        "INSERT INTO audit_log(admin_id,card_id,action,result) VALUES (?,?,?,?)",
        admin,
        card,
        action,
        result);
  }
}
