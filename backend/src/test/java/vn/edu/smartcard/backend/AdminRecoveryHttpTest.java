package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import vn.edu.smartcard.shared.Api;

class AdminRecoveryHttpTest {
  private static final String DEVICE_KEY = "recovery-test-device";

  @Test
  void reportsMissingIdentityOnlyForAnOptedInDeviceRequest() throws Exception {
    AuthService auth = new AuthService(null, null, null, null) {
      @Override
      public boolean forgot(String identity) {
        return switch (identity) {
          case "known@example.invalid" -> true;
          case "mail-error@example.invalid" ->
              throw new Failure(503, "EMAIL_UNAVAILABLE", "Chức năng gửi email chưa sẵn sàng.");
          default -> false;
        };
      }
    };
    var mvc = recoveryMvc(auth);

    mvc.perform(request("missing").param("reportMissing", "true"))
        .andExpect(status().isForbidden());
    String neutral = mvc.perform(request("missing").header("X-Device-Key", DEVICE_KEY))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    mvc.perform(request("known@example.invalid").header("X-Device-Key", DEVICE_KEY))
        .andExpect(status().isOk())
        .andExpect(content().json(neutral));
    mvc.perform(request("missing").header("X-Device-Key", DEVICE_KEY).param("reportMissing", "true"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESET_IDENTITY_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Không tìm thấy email hoặc username"));
    mvc.perform(request("known@example.invalid").header("X-Device-Key", DEVICE_KEY)
            .param("reportMissing", "true"))
        .andExpect(status().isOk());
    mvc.perform(request("mail-error@example.invalid").header("X-Device-Key", DEVICE_KEY)
            .param("reportMissing", "true"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("EMAIL_UNAVAILABLE"));
  }

  @Test
  void stagedRecoveryRequiresADeviceAndDoesNotCacheTheGrant() throws Exception {
    String grant = "g".repeat(43);
    AuthService auth = new AuthService(null, null, null, null) {
      @Override
      public Api.RecoveryGrant verifyRecoveryOtp(Api.VerifyRecoveryOtp input) {
        if (!input.identity().equals("fixture") || !input.otp().equals("012345"))
          throw new Failure(400, "RESET_OTP_INVALID", "Mã OTP không đúng.");
        return new Api.RecoveryGrant(grant);
      }

      @Override
      public void completeRecovery(Api.CompleteRecovery input) {
        if (!input.identity().equals("fixture") || !grant.equals(input.grant()))
          throw new Failure(400, "RESET_GRANT_INVALID", "Phiên đặt lại mật khẩu đã hết hạn.");
      }
    };
    MockMvc mvc = recoveryMvc(auth);
    String otp = "{\"identity\":\"fixture\",\"otp\":\"012345\"}";
    String password = "Fixture-new-password";
    String complete = "{\"identity\":\"fixture\",\"grant\":\"" + grant
        + "\",\"password\":\"" + password + "\"}";
    mvc.perform(recoveryRequest("/verify", otp))
        .andExpect(status().isForbidden());
    mvc.perform(recoveryRequest("/complete", complete))
        .andExpect(status().isForbidden());
    mvc.perform(recoveryRequest("/verify", otp.replace("012345", "000000"))
            .header("X-Device-Key", DEVICE_KEY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RESET_OTP_INVALID"));
    mvc.perform(recoveryRequest("/verify", otp).header("X-Device-Key", DEVICE_KEY))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.grant").value(grant));
    mvc.perform(recoveryRequest("/complete", complete.replace(grant, "012345"))
            .header("X-Device-Key", DEVICE_KEY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RESET_GRANT_INVALID"));
    mvc.perform(recoveryRequest("/complete", complete).header("X-Device-Key", DEVICE_KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("Đặt lại mật khẩu thành công!"));
  }

  @Test
  void newPasswordLengthIsValidatedForSetupAndBothRecoveryRoutes() {
    String token = "fixture-setup-token";
    AuthService auth = new AuthService(null, null, new LocalConfig() {
      @Override
      public String required(String key) {
        return token;
      }
    }, null);
    String message = "Mật khẩu mới phải chứa từ 12 đến 20 ký tự";
    for (int length : new int[] {11, 21}) {
      String invalid = "x".repeat(length);
      assertEquals(message, assertThrows(Failure.class, () -> auth.setup(
          new Api.Setup(token, "fixture", "fixture@example.invalid", invalid, null))).getMessage());
      assertEquals(message, assertThrows(Failure.class, () -> auth.recover(
          new Api.Recover("fixture", "012345", invalid))).getMessage());
      assertEquals(message, assertThrows(Failure.class, () -> auth.completeRecovery(
          new Api.CompleteRecovery("fixture", "g".repeat(43), invalid))).getMessage());
    }
    for (int length : new int[] {12, 20}) {
      String valid = "x".repeat(length);
      assertEquals("SETUP_OTP_REQUIRED", assertThrows(Failure.class, () -> auth.setup(
          new Api.Setup(token, "fixture", "fixture@example.invalid", valid, null))).code);
    }
  }

  private static MockMvc recoveryMvc(AuthService auth) {
    LocalConfig config = new LocalConfig() {
      @Override
      public String required(String key) {
        return DEVICE_KEY;
      }
    };
    var registry = new InterceptorRegistry() {
      HandlerInterceptor[] registered() {
        return getInterceptors().toArray(HandlerInterceptor[]::new);
      }
    };
    new ApiAccess(config, auth).addInterceptors(registry);
    return MockMvcBuilders
        .standaloneSetup(new ApiController(auth, null, null, null, null, null))
        .addInterceptors(registry.registered())
        .setControllerAdvice(new ApiErrors())
        .build();
  }

  private static MockHttpServletRequestBuilder recoveryRequest(String suffix, String json) {
    return post("/api/admin/recover" + suffix).contentType(MediaType.APPLICATION_JSON).content(json);
  }

  private static MockHttpServletRequestBuilder request(String identity) {
    return post("/api/admin/forgot")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"identity\":\"" + identity + "\"}");
  }
}
