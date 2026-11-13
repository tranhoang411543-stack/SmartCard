package vn.edu.smartcard.backend;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.Pattern;

/** SePay's JSON/API-Key contract; no bank request or card write happens here. */
final class SePayGateway {
  private static final Pattern CODE =
      Pattern.compile("(?<![A-Z0-9])(?:TT|NT)(?:AT|CT|DT)[0-9]{14}(?![A-Z0-9])");
  private static final DateTimeFormatter BANK_TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
  private final LocalConfig config;

  SePayGateway(LocalConfig config) {
    this.config = config;
  }

  boolean enabled() {
    return config.get("bank.provider", "casso").equalsIgnoreCase("sepay");
  }

  String account() {
    return config.get("bank.account", "96247Y6CZZ").strip();
  }

  boolean ready() {
    return account().matches("[A-Za-z0-9]{6,34}")
        && config.get("bank.code", "BIDV").matches("[A-Za-z0-9]{2,20}");
  }

  boolean confirmationReady() {
    return config.get("sepay.webhook.api.key", "").length() >= 32;
  }

  static String paymentCode(String studentCode, long topupId) {
    return requestCode("NT", studentCode, topupId);
  }

  static String invoiceCode(String studentCode, long invoiceId) {
    return requestCode("TT", studentCode, invoiceId);
  }

  static String invoiceCode(long invoiceId) {
    // Keep the existing SePay TTAT pattern; 000000 is a reserved marker, not a student.
    return requestCode("TT", "AT000000", invoiceId);
  }

  private static String requestCode(String kind, String studentCode, long id) {
    if (id < 1 || id > 99_999_999L)
      throw new Failure(
          409,
          "PAYMENT_CODE_EXHAUSTED",
          "Đã hết dải số yêu cầu chuyển khoản. Vui lòng liên hệ quản trị hệ thống.");
    return kind + studentCode(studentCode) + String.format(Locale.ROOT, "%08d", id);
  }

  static boolean validMemo(String memo) {
    return memo != null && memo.matches("(?:TT|NT)(?:AT|CT|DT)[0-9]{14}");
  }

  static String studentCode(String studentCode) {
    String value = Objects.toString(studentCode, "").strip().toUpperCase(Locale.ROOT);
    if (!value.matches("(AT|CT|DT)[0-9]{6}"))
      throw Failure.bad("Mã sinh viên không hợp lệ để tạo nội dung chuyển khoản.");
    return value;
  }

  URI qrUri(long amount, String reference) {
    return qrUri(amount, reference, false);
  }

  URI qrUri(long amount, String reference, boolean ignoredFullImage) {
    if (!ready() || amount <= 0 || !validMemo(reference))
      throw Failure.bad("Thông tin tạo QR chuyển khoản không hợp lệ.");
    // Both top-up display modes and invoice payments request the same QR-only template.
    return URI.create(
        "https://vietqr.app/img?bank="
            + encode(config.get("bank.code", "BIDV"))
            + "&acc="
            + encode(account())
            + "&template=qronly&amount="
            + amount
            + "&des="
            + encode(reference)
            + "&showinfo=true&holder="
            + encode(config.get("bank.name", "TRAN HOANG THU")));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  record Notice(
      String providerId,
      String bankReference,
      String paymentCode,
      long amount,
      Instant transactionTime,
      String description,
      String account,
      boolean probe) {}

  Notice verify(String raw, String authorization) {
    if (!enabled() || !confirmationReady())
      throw new Failure(503, "SEPAY_NOT_CONFIGURED", "Webhook SePay chưa được cấu hình.");
    String secret = config.get("sepay.webhook.api.key", "");
    if (authorization == null
        || authorization.length() > 512
        || !authorization.regionMatches(true, 0, "Apikey ", 0, 7)
        || !MessageDigest.isEqual(
            secret.getBytes(StandardCharsets.UTF_8),
            authorization.substring(7).getBytes(StandardCharsets.UTF_8)))
      throw new Failure(401, "SEPAY_AUTH", "Không xác thực được webhook SePay.");
    if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 65536)
      throw Failure.bad("Payload SePay vượt giới hạn cho phép.");
    try {
      JsonNode body = SecurityMaterial.JSON.readTree(raw);
      if (body == null
          || !body.isObject()
          || !body.path("id").isIntegralNumber()
          || !body.path("id").canConvertToLong()) throw Failure.bad("ID SePay không hợp lệ.");
      long id = body.get("id").longValue();
      // SePay's send-test payload can use id=0. Acknowledge connectivity, never funds.
      if (id == 0) return new Notice("", "", null, 0, Instant.EPOCH, "", "", true);
      if (id < 1) throw Failure.bad("ID SePay không hợp lệ.");
      if (!text(body, "gateway", 40).equalsIgnoreCase(config.get("bank.code", "BIDV")))
        throw Failure.bad("Webhook không thuộc ngân hàng nhận đã cấu hình.");
      String main = text(body, "accountNumber", 34);
      String sub = optional(body, "subAccount", 34);
      // A nonempty VA must match; the main account must never override another VA.
      if (!(sub.isEmpty() ? main : sub).equalsIgnoreCase(account()))
        throw Failure.bad("Webhook không thuộc tài khoản VA nhận tiền.");
      String configuredMain = config.get("bank.main.account", "");
      if (!configuredMain.isBlank() && !main.equals(configuredMain))
        throw Failure.bad("Tài khoản chính của VA không khớp.");
      String direction = text(body, "transferType", 8);
      if (!Set.of("in", "out").contains(direction))
        throw Failure.bad("Loại giao dịch SePay không hợp lệ.");
      JsonNode value = body.get("transferAmount");
      if (value == null || !(value.isNumber() || value.isTextual()))
        throw Failure.bad("Số tiền SePay không hợp lệ.");
      long amount = new BigDecimal(value.asText()).longValueExact();
      if (amount <= 0 || amount > Integer.MAX_VALUE)
        throw Failure.bad("Số tiền SePay không hợp lệ.");
      Instant time =
          LocalDateTime.parse(text(body, "transactionDate", 19), BANK_TIME)
              .atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
              .toInstant();
      if (time.isAfter(Instant.now().plusSeconds(300)))
        throw Failure.bad("Thời gian giao dịch SePay không hợp lệ.");
      String content = optional(body, "content", 500);
      String extracted = optional(body, "code", 40).toUpperCase(Locale.ROOT);
      Set<String> codes = new HashSet<>();
      var matcher = CODE.matcher(content.toUpperCase(Locale.ROOT));
      while (matcher.find()) codes.add(matcher.group());
      String code = codes.size() == 1 ? codes.iterator().next() : null;
      if (!extracted.isEmpty() && !extracted.equals(code)) code = null;
      return new Notice(
          "SEPAY:" + id,
          "SEPAY:" + text(body, "referenceCode", 140),
          code,
          direction.equals("in") ? amount : -amount,
          time,
          content,
          account(),
          false);
    } catch (Failure e) {
      throw e;
    } catch (Exception e) {
      throw Failure.bad("Dữ liệu webhook SePay không hợp lệ.");
    }
  }

  private static String text(JsonNode body, String key, int max) {
    String value = optional(body, key, max);
    if (value.isBlank()) throw Failure.bad("Thiếu trường SePay: " + key);
    return value;
  }

  private static String optional(JsonNode body, String key, int max) {
    JsonNode node = body.get(key);
    if (node == null || node.isNull()) return "";
    if (!node.isTextual() || node.textValue().length() > max)
      throw Failure.bad("Trường SePay không hợp lệ: " + key);
    return node.textValue();
  }
}
