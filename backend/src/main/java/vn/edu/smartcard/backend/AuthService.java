package vn.edu.smartcard.backend;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;
import vn.edu.smartcard.shared.AdminPasswordPolicy;
import vn.edu.smartcard.shared.Api;

@Service
public class AuthService {
  private static final int SETUP_MAX_ATTEMPTS = 5;
  private static final int RESET_MAX_ATTEMPTS = 5;
  private static final String RESET_OTP_ERROR =
      "Mã OTP không đúng, đã hết hạn hoặc đã dùng hết số lần thử.";
  private static final SecureRandom RANDOM = new SecureRandom();
  private final CardRepository repo;
  private final SecurityMaterial security;
  private final LocalConfig config;
  private final MailGateway mail;
  private final Pbkdf2PasswordEncoder encoder =
      new Pbkdf2PasswordEncoder(
          "", 16, 600000, Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);

  public record Admin(long id, String username, String email) {}

  private record SetupIdentity(String username, String email) {}

  private record SetupChallenge(UUID requestId, String username, String email, String codeHash,
      int attempts, boolean active) {}

  public AuthService(
      CardRepository repo, SecurityMaterial security, LocalConfig config, MailGateway mail) {
    this.repo = repo;
    this.security = security;
    this.config = config;
    this.mail = mail;
  }

  public boolean setupNeeded() {
    return repo.db.queryForObject("SELECT count(*) FROM admins", Long.class) == 0;
  }

  private void password(String value) {
    if (value == null || !AdminPasswordPolicy.acceptsLength(value.length()))
      throw Failure.bad(AdminPasswordPolicy.LENGTH_ERROR);
  }

  private void requireSetupToken(String token) {
    if (!MessageDigest.isEqual(
        Objects.toString(token, "").getBytes(StandardCharsets.UTF_8),
        config.required("setup.token").getBytes(StandardCharsets.UTF_8))) throw Failure.denied();
  }

  private static SetupIdentity setupIdentity(String requestedUsername, String requestedEmail) {
    String username = Objects.toString(requestedUsername, "").strip();
    String email = Objects.toString(requestedEmail, "").strip().toLowerCase(Locale.ROOT);
    if (!username.matches("[a-zA-Z0-9_.-]{3,60}") || !validSetupEmail(email))
      throw Failure.bad("Tên đăng nhập hoặc email không hợp lệ.");
    return new SetupIdentity(username, email);
  }

  private static boolean validSetupEmail(String email) {
    if (email.length() > 180 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) return false;
    try {
      var address = new InternetAddress(email, true);
      address.validate();
      return !address.isGroup() && email.equals(address.getAddress());
    } catch (AddressException e) {
      return false;
    }
  }

  private void lockFirstSetup() {
    repo.db.execute("SELECT pg_advisory_xact_lock(20261103)");
    if (!setupNeeded())
      throw new Failure(409, "SETUP_ALREADY_DONE",
          "Hệ thống đã có tài khoản admin. Vui lòng đăng nhập tại quầy quản lý.");
  }

  private static String randomCode() {
    return String.format(Locale.ROOT, "%08d", RANDOM.nextInt(100_000_000));
  }

  private String setupCodeHash(UUID requestId, String code) {
    return security.keyed("admin-setup:" + requestId + ":" + code);
  }

  public void requestSetupOtp(Api.SetupOtp input) {
    requireSetupToken(input.token());
    var identity = setupIdentity(input.username(), input.email());
    UUID requestId = UUID.randomUUID();
    String code = randomCode();
    repo.tx.executeWithoutResult(t -> {
      lockFirstSetup();
      requireMail();
      int changed = repo.db.update(
          "INSERT INTO admin_setup_codes(id,request_id,username,email,code_hash,expires_at,sent_at,attempts)"
              + " VALUES (1,?,?,?,?,clock_timestamp()+interval '10 minutes',clock_timestamp(),0)"
              + " ON CONFLICT(id) DO UPDATE SET request_id=excluded.request_id,username=excluded.username,"
              + " email=excluded.email,code_hash=excluded.code_hash,expires_at=excluded.expires_at,"
              + " sent_at=excluded.sent_at,attempts=0"
              + " WHERE admin_setup_codes.sent_at<=clock_timestamp()-interval '1 minute'",
          requestId, identity.username(), identity.email(), setupCodeHash(requestId, code));
      if (changed == 0)
        throw new Failure(429, "SETUP_OTP_LIMIT", "Vui lòng chờ một phút trước khi yêu cầu mã mới.");
    });
    try {
      mail.setupOtp(identity.email(), code);
    } catch (RuntimeException e) {
      repo.db.update("DELETE FROM admin_setup_codes WHERE request_id=?", requestId);
      throw e;
    }
  }

  public void setup(Api.Setup input) {
    requireSetupToken(input.token());
    var identity = setupIdentity(input.username(), input.email());
    password(input.password());
    if (input.code() == null || !input.code().matches("[0-9]{8}"))
      throw new Failure(400, "SETUP_OTP_REQUIRED", "Vui lòng nhập mã OTP gồm 8 chữ số.");

    // Return the error from the transaction so an incorrect attempt is committed.
    Failure failure = repo.tx.execute(t -> {
      lockFirstSetup();
      var rows = repo.db.query(
          "SELECT request_id,username,email,code_hash,attempts,expires_at>clock_timestamp() AS active"
              + " FROM admin_setup_codes WHERE id=1 FOR UPDATE",
          (r, n) -> new SetupChallenge(r.getObject("request_id", UUID.class), r.getString("username"),
              r.getString("email"), r.getString("code_hash"), r.getInt("attempts"), r.getBoolean("active")));
      if (rows.isEmpty()) return setupOtpUnavailable();
      var challenge = rows.getFirst();
      if (!challenge.active() || challenge.attempts() >= SETUP_MAX_ATTEMPTS
          || !challenge.username().equals(identity.username())
          || !challenge.email().equals(identity.email())) return setupOtpUnavailable();
      if (!MessageDigest.isEqual(challenge.codeHash().getBytes(StandardCharsets.US_ASCII),
          setupCodeHash(challenge.requestId(), input.code()).getBytes(StandardCharsets.US_ASCII))) {
        repo.db.update("UPDATE admin_setup_codes SET attempts=attempts+1 WHERE id=1");
        int remaining = SETUP_MAX_ATTEMPTS - challenge.attempts() - 1;
        return remaining == 0 ? setupOtpUnavailable() : new Failure(400, "SETUP_OTP_WRONG",
            "Mã OTP không đúng. Còn " + remaining + " lần thử.");
      }
      repo.db.update("INSERT INTO admins(username,email,password_hash) VALUES (?,?,?)",
          identity.username(), identity.email(), encoder.encode(input.password()));
      repo.db.update("DELETE FROM admin_setup_codes WHERE id=1");
      return null;
    });
    if (failure != null) throw failure;
  }

  private static Failure setupOtpUnavailable() {
    return new Failure(400, "SETUP_OTP_UNAVAILABLE",
        "Mã OTP đã hết hạn, không còn hiệu lực hoặc đã dùng hết số lần thử. Hãy yêu cầu mã mới.");
  }

  public Api.Login login(Api.Credentials input) {
    String identity = Objects.toString(input.username(), "").strip();
    if (identity.length() > 180 || input.password() == null || input.password().length() > 128)
      throw Failure.bad("Thông tin đăng nhập không hợp lệ.");
    String limit = security.keyed("login:" + identity.toLowerCase(Locale.ROOT));
    List<Map<String, Object>> limits =
        repo.db.queryForList(
            "SELECT failures,updated_at FROM auth_limits WHERE identity_hash=?", limit);
    if (!limits.isEmpty()
        && (int) limits.getFirst().get("failures") >= 5
        && ((Timestamp) limits.getFirst().get("updated_at"))
            .toInstant()
            .isAfter(Instant.now().minusSeconds(600)))
      throw new Failure(429, "LOGIN_LIMIT", "Đã nhập sai nhiều lần. Vui lòng thử lại sau 10 phút.");
    List<Map<String, Object>> rows =
        repo.db.queryForList("SELECT * FROM admins WHERE lower(username)=lower(?)", identity);
    if (rows.isEmpty()
        || !encoder.matches(input.password(), (String) rows.getFirst().get("password_hash"))) {
      repo.db.update(
          "INSERT INTO auth_limits(identity_hash,failures,updated_at) VALUES (?,1,now()) ON"
              + " CONFLICT(identity_hash) DO UPDATE SET failures=CASE WHEN"
              + " auth_limits.updated_at<now()-interval '10 minutes' THEN 1 ELSE"
              + " auth_limits.failures+1 END,updated_at=now()",
          limit);
      throw new Failure(401, "LOGIN_FAILED", "Tên đăng nhập hoặc mật khẩu không đúng.");
    }
    repo.db.update("DELETE FROM auth_limits WHERE identity_hash=?", limit);
    Map<String, Object> row = rows.getFirst();
    String token = security.randomToken();
    repo.db.update(
        "INSERT INTO admin_sessions(token_hash,admin_id,expires_at) VALUES (?,?,?)",
        security.keyed("session:" + token),
        row.get("id"),
        Timestamp.from(Instant.now().plusSeconds(1800)));
    return new Api.Login(token, (String) row.get("username"), (String) row.get("email"));
  }

  public Admin require(String token) {
    if (token == null || token.length() > 200)
      throw new Failure(401, "ADMIN_LOGIN", "Vui lòng đăng nhập admin.");
    List<Admin> rows =
        repo.db.query(
            "SELECT a.id,a.username,a.email FROM admins a JOIN admin_sessions s ON s.admin_id=a.id"
                + " WHERE s.token_hash=? AND s.expires_at>now()",
            (r, n) -> new Admin(r.getLong(1), r.getString(2), r.getString(3)),
            security.keyed("session:" + token));
    if (rows.isEmpty())
      throw new Failure(401, "ADMIN_EXPIRED", "Phiên admin đã hết hạn. Vui lòng đăng nhập lại.");
    return rows.getFirst();
  }

  public void logout(String token) {
    if (token != null)
      repo.db.update(
          "DELETE FROM admin_sessions WHERE token_hash=?", security.keyed("session:" + token));
  }

  private void requireMail() {
    if (!mail.ready())
      throw new Failure(
          503,
          "EMAIL_UNAVAILABLE",
          "Chức năng gửi email hiện chưa sẵn sàng. Vui lòng liên hệ quản trị hệ thống.");
  }

  public boolean forgot(String identity) {
    requireMail();
    String value = recoveryIdentity(identity);
    List<Map<String, Object>> rows =
        repo.db.queryForList(
            "SELECT id,email FROM admins WHERE lower(username)=lower(?) OR lower(email)=lower(?)"
                + " ORDER BY id LIMIT 1",
            value,
            value);
    if (rows.isEmpty()) return false;
    long id = ((Number) rows.getFirst().get("id")).longValue();
    String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    String codeHash = resetOtpHash(id, code);
    repo.tx.executeWithoutResult(t -> {
      repo.db.queryForList("SELECT id FROM admins WHERE id=? FOR UPDATE", id);
      List<Timestamp> sent =
          repo.db.queryForList(
              "SELECT sent_at FROM reset_codes WHERE admin_id=?", Timestamp.class, id);
      if (!sent.isEmpty() && sent.getFirst().toInstant().isAfter(Instant.now().minusSeconds(60)))
        throw new Failure(429, "RESET_LIMIT", "Vui lòng chờ một phút trước khi yêu cầu mã mới.");
      repo.db.update(
          "INSERT INTO reset_codes(admin_id,code_hash,expires_at,sent_at,attempts) VALUES"
              + " (?,?,?,now(),0) ON CONFLICT(admin_id) DO UPDATE SET"
              + " code_hash=excluded.code_hash,expires_at=excluded.expires_at,sent_at=now(),attempts=0",
          id,
          codeHash,
          Timestamp.from(Instant.now().plusSeconds(600)));
    });
    try {
      mail.recovery((String) rows.getFirst().get("email"), code);
    } catch (Failure e) {
      repo.db.update(
          "DELETE FROM reset_codes WHERE admin_id=? AND code_hash=?",
          id,
          codeHash);
      throw e;
    }
    return true;
  }

  public void recover(Api.Recover input) {
    password(input.password());
    String identity = recoveryIdentity(input.identity());
    String code = Objects.toString(input.code(), "");
    // The previous desktop can still complete recovery with an outstanding 8-digit code.
    if (!code.matches("[0-9]{6}(?:[0-9]{2})?"))
      throw Failure.bad(RESET_OTP_ERROR);
    String hash = encoder.encode(input.password());
    Boolean ok = repo.tx.execute(t -> {
      ResetChallenge challenge = activeResetChallenge(identity);
      if (!matchesResetOtp(challenge, code)) return false;
      applyPasswordReset(challenge.adminId(), hash);
      return true;
    });
    if (!Boolean.TRUE.equals(ok)) throw Failure.bad(RESET_OTP_ERROR);
  }

  public Api.RecoveryGrant verifyRecoveryOtp(Api.VerifyRecoveryOtp input) {
    String identity = recoveryIdentity(input.identity());
    if (input.otp() == null || !input.otp().matches("[0-9]{6}"))
      throw Failure.bad("Vui lòng nhập đủ 6 chữ số OTP.");
    Api.RecoveryGrant result = repo.tx.execute(t -> {
      ResetChallenge challenge = activeResetChallenge(identity);
      if (!matchesResetOtp(challenge, input.otp())) return null;
      long id = challenge.adminId();
      String grant = security.randomToken();
      // Replace the OTP hash and close its attempt budget atomically.
      repo.db.update(
          "UPDATE reset_codes SET code_hash=?,expires_at=?,attempts=? WHERE admin_id=?",
          resetGrantHash(id, grant),
          Timestamp.from(Instant.now().plusSeconds(300)),
          RESET_MAX_ATTEMPTS,
          id);
      return new Api.RecoveryGrant(grant);
    });
    if (result == null) throw new Failure(400, "RESET_OTP_INVALID", RESET_OTP_ERROR);
    return result;
  }

  public void completeRecovery(Api.CompleteRecovery input) {
    String identity = recoveryIdentity(input.identity());
    if (input.grant() == null || !input.grant().matches("[A-Za-z0-9_-]{43}"))
      throw invalidRecoveryGrant();
    password(input.password());
    String hash = encoder.encode(input.password());
    Boolean ok = repo.tx.execute(t -> {
      ResetChallenge challenge = activeResetChallenge(identity);
      if (challenge == null || challenge.attempts() != RESET_MAX_ATTEMPTS
          || !sameResetHash(challenge.hash(), resetGrantHash(challenge.adminId(), input.grant())))
        return false;
      applyPasswordReset(challenge.adminId(), hash);
      return true;
    });
    if (!Boolean.TRUE.equals(ok)) throw invalidRecoveryGrant();
  }

  private static String recoveryIdentity(String identity) {
    String value = Objects.toString(identity, "").strip();
    if (value.isBlank() || value.length() > 180)
      throw Failure.bad("Vui lòng nhập tên đăng nhập hoặc email.");
    return value;
  }

  private record ResetChallenge(long adminId, String hash, int attempts, Instant expiresAt) {}

  private ResetChallenge activeResetChallenge(String identity) {
    // Lock the admin before its reset row, in the same order as forgot().
    List<Long> ids = repo.db.queryForList(
        "SELECT id FROM admins WHERE lower(username)=lower(?) OR lower(email)=lower(?)"
            + " ORDER BY id LIMIT 1 FOR UPDATE",
        Long.class, identity, identity);
    if (ids.isEmpty()) return null;
    long id = ids.getFirst();
    List<ResetChallenge> rows = repo.db.query(
        "SELECT code_hash,attempts,expires_at FROM reset_codes WHERE admin_id=? FOR UPDATE",
        (r, n) -> new ResetChallenge(id, r.getString(1), r.getInt(2), r.getTimestamp(3).toInstant()),
        id);
    if (rows.isEmpty()) return null;
    ResetChallenge challenge = rows.getFirst();
    return challenge.expiresAt().isAfter(Instant.now()) ? challenge : null;
  }

  private boolean matchesResetOtp(ResetChallenge challenge, String code) {
    if (challenge == null || challenge.attempts() >= RESET_MAX_ATTEMPTS) return false;
    long id = challenge.adminId();
    if (sameResetHash(challenge.hash(), resetOtpHash(id, code))) return true;
    repo.db.update("UPDATE reset_codes SET attempts=attempts+1 WHERE admin_id=?", id);
    return false;
  }

  private String resetOtpHash(long id, String otp) {
    return security.keyed("reset:" + id + ":" + otp);
  }

  private String resetGrantHash(long id, String grant) {
    return security.keyed("reset-grant:" + id + ":" + grant);
  }

  private static boolean sameResetHash(String stored, String expected) {
    return MessageDigest.isEqual(
        stored.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII));
  }

  private static Failure invalidRecoveryGrant() {
    return new Failure(400, "RESET_GRANT_INVALID",
        "Phiên đặt lại mật khẩu không còn hiệu lực. Vui lòng yêu cầu mã OTP mới.");
  }

  private void applyPasswordReset(long id, String hash) {
    repo.db.update("UPDATE admins SET password_hash=? WHERE id=?", hash, id);
    repo.db.update("DELETE FROM admin_sessions WHERE admin_id=?", id);
    repo.db.update("DELETE FROM reset_codes WHERE admin_id=?", id);
  }
}
