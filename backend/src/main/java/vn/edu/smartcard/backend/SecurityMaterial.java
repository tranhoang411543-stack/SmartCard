package vn.edu.smartcard.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Server randomness, auth/webhook hashes and JSON. Card encryption stays on card. */
@Component
public class SecurityMaterial {
  public static final ObjectMapper JSON = new ObjectMapper();
  private final byte[] key;
  private final SecureRandom random = new SecureRandom();

  public SecurityMaterial(LocalConfig config) {
    key = Base64.getDecoder().decode(config.required("master.key"));
    if (key.length != 32) throw new IllegalArgumentException("Khóa máy chủ phải dài 32 byte.");
  }

  public byte[] randomBytes(int n) {
    byte[] b = new byte[n];
    random.nextBytes(b);
    return b;
  }

  public String randomToken() {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
  }

  public String keyed(String text) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public String encode(Object object) {
    try {
      return JSON.writeValueAsString(object);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public <T> T decode(String json, Class<T> type) {
    try {
      return JSON.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("Dữ liệu lưu trữ không hợp lệ.", e);
    }
  }
}
