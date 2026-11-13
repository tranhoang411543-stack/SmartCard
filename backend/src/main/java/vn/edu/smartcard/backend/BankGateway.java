package vn.edu.smartcard.backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class BankGateway {
  private final LocalConfig config;
  private final SePayGateway sepay;
  private HttpClient http;

  private synchronized HttpClient http() {
    if (http == null)
      http =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(5))
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();
    return http;
  }

  public BankGateway(LocalConfig config) {
    this.config = config;
    this.sepay = new SePayGateway(config);
  }

  SePayGateway sepay() { return sepay; }

  public boolean ready() {
    if (sepay.enabled()) return sepay.ready();
    return config.get("bank.account", "").matches("[0-9]{6,19}")
        && config.get("bank.bin", "").matches("[0-9]{6}");
  }

  public boolean confirmationReady() {
    if (sepay.enabled()) return sepay.confirmationReady();
    return !config.get("casso.webhook.key", "").isBlank();
  }

  public String qr(long amount, String reference) {
    return qr(amount, reference, false);
  }

  public String fullQr(long amount, String reference) {
    return qr(amount, reference, true);
  }

  private String qr(long amount, String reference, boolean fullImage) {
    if (!ready())
      throw new Failure(
          503,
          "BANK_UNAVAILABLE",
          "Chức năng nạp tiền ngân hàng chưa sẵn sàng. Vui lòng liên hệ quản trị hệ thống.");
    try {
      if (sepay.enabled()) {
        HttpResponse<byte[]> image = http().send(
            HttpRequest.newBuilder(sepay.qrUri(amount, reference, fullImage)).timeout(Duration.ofSeconds(8))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (image.statusCode() != 200) throw new Exception("QR HTTP response");
        byte[] bytes = BankImage.png(image.body(), image.headers().firstValue("Content-Type").orElse(""));
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
      }
      if (config.get("vietqr.client.id", "").isBlank()
          || config.get("vietqr.api.key", "").isBlank()) {
        String endpoint =
            "https://img.vietqr.io/image/"
                + config.get("bank.bin", "")
                + "-"
                + config.get("bank.account", "")
                + "-compact2.svg?amount="
                + amount
                + "&addInfo="
                + java.net.URLEncoder.encode(reference, StandardCharsets.UTF_8);
        if (!config.get("bank.name", "").isBlank())
          endpoint +=
              "&accountName="
                  + java.net.URLEncoder.encode(config.get("bank.name", ""), StandardCharsets.UTF_8);
        HttpResponse<byte[]> image =
            http()
                .send(
                    HttpRequest.newBuilder(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(8))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        if (image.statusCode() != 200) throw new Exception("QR HTTP response");
        byte[] bytes =
            BankImage.png(image.body(), image.headers().firstValue("Content-Type").orElse(""));
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
      }
      String json =
          SecurityMaterial.JSON.writeValueAsString(
              Map.of(
                  "accountNo",
                  config.get("bank.account", ""),
                  "accountName",
                  config.get("bank.name", ""),
                  "acqId",
                  Integer.parseInt(config.get("bank.bin", "")),
                  "amount",
                  amount,
                  "addInfo",
                  reference,
                  "template",
                  "compact2"));
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(config.get("vietqr.url", "https://api.vietqr.io/v2/generate")))
              .timeout(Duration.ofSeconds(8))
              .header("Content-Type", "application/json")
              .header("x-client-id", config.get("vietqr.client.id", ""))
              .header("x-api-key", config.get("vietqr.api.key", ""))
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();
      HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200 || response.body().length() > 2_000_000)
        throw new Exception("QR response");
      JsonNode tree = SecurityMaterial.JSON.readTree(response.body());
      String result = tree.path("data").path("qrDataURL").asText();
      if (!tree.path("code").asText().equals("00") || !result.startsWith("data:image/png;base64,"))
        throw new Exception("QR payload");
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Failure(503, "BANK_UNAVAILABLE", "Yêu cầu tạo QR bị gián đoạn. Vui lòng thử lại.");
    } catch (Exception e) {
      throw new Failure(
          503, "BANK_UNAVAILABLE", "Chưa tạo được QR ngân hàng. Vui lòng thử lại sau.");
    }
  }

  public Map<String, Object> verify(String raw, String header) {
    if (sepay.enabled()) throw new Failure(403, "BANK_PROVIDER", "Nguồn xác nhận ngân hàng không được bật.");
    String secret = config.get("casso.webhook.key", "");
    if (secret.isBlank())
      throw new Failure(503, "BANK_UNAVAILABLE", "Chưa cấu hình nguồn xác nhận ngân hàng.");
    if (raw == null || raw.length() > 65536 || header == null) throw Failure.denied();
    try {
      Map<String, Object> body =
          SecurityMaterial.JSON.readValue(raw, new TypeReference<Map<String, Object>>() {});
      Map<String, String> parts = new HashMap<>();
      for (String p : header.split(",")) {
        String[] pair = p.strip().split("=", 2);
        if (pair.length == 2) parts.put(pair[0], pair[1]);
      }
      long timestamp = Long.parseLong(parts.get("t"));
      long age = Instant.now().toEpochMilli() - timestamp;
      if (age < -300_000
          || age > Long.parseLong(config.get("casso.signature.max.age.seconds", "46800")) * 1000L)
        throw Failure.denied();
      String canonical = SecurityMaterial.JSON.writeValueAsString(sorted(body));
      Mac mac = Mac.getInstance("HmacSHA512");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
      byte[] expected = mac.doFinal((timestamp + "." + canonical).getBytes(StandardCharsets.UTF_8));
      byte[] actual = HexFormat.of().parseHex(parts.get("v1"));
      if (!MessageDigest.isEqual(expected, actual)) throw Failure.denied();
      if (!(body.get("error") instanceof Number n)
          || n.intValue() != 0
          || !(body.get("data") instanceof Map))
        throw Failure.bad("Thông báo ngân hàng không hợp lệ.");
      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) body.get("data");
      try {
        if (new java.math.BigDecimal(Objects.toString(data.get("id"), "0")).longValueExact() <= 0)
          throw Failure.bad("Không chấp nhận giao dịch thử của nhà cung cấp để ghi tiền vào thẻ.");
      } catch (NumberFormatException | ArithmeticException e) {
        throw Failure.bad("Mã giao dịch ngân hàng không hợp lệ.");
      }
      return data;
    } catch (Failure e) {
      throw e;
    } catch (Exception e) {
      throw Failure.denied();
    }
  }

  private Object sorted(Object input) {
    if (input instanceof Map<?, ?> m) {
      Map<String, Object> result = new TreeMap<>();
      m.forEach((k, v) -> result.put(k.toString(), sorted(v)));
      return result;
    }
    if (input instanceof List<?> l) return l.stream().map(this::sorted).toList();
    return input;
  }

  public String account() {
    if (sepay.enabled()) return sepay.account();
    return config.get("bank.account", "");
  }
}
