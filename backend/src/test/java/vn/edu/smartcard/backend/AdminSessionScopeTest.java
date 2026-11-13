package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

class AdminSessionScopeTest {
  private static final String DEVICE = "fixture-device";

  @AfterEach
  void clearContext() {
    AccessContext.clear();
  }

  @Test
  void studentRoutesIgnoreAdminSessionWithoutGainingAdminAuthority() throws Exception {
    var checks = new AtomicInteger();
    HandlerInterceptor access = access(checks);
    for (String path : new String[] {
        "/api/student/home", "/api/student/login", "/api/student/transactions",
        "/api/topups", "/api/topups/7/apply"
    }) {
      var request = request(path, "Bearer expired-fixture");
      assertTrue(access.preHandle(request, new MockHttpServletResponse(), new Object()));
      assertEquals("ADMIN_LOGIN", assertThrows(Failure.class, AccessContext::requireAdmin).code);
      assertEquals(0, checks.get());
      access.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
    }

    var admin = request("/api/admin/me", "Bearer fixture");
    assertTrue(access.preHandle(admin, new MockHttpServletResponse(), new Object()));
    AccessContext.requireAdmin();
    assertEquals(2, checks.get());
    access.afterCompletion(admin, new MockHttpServletResponse(), new Object(), null);

    var malformed = request("/api/student/home", "Basic fixture");
    assertEquals(403, assertThrows(Failure.class,
        () -> access.preHandle(malformed, new MockHttpServletResponse(), new Object())).status);
    var missingDevice = new MockHttpServletRequest("GET", "/api/student/home");
    assertEquals(403, assertThrows(Failure.class,
        () -> access.preHandle(missingDevice, new MockHttpServletResponse(), new Object())).status);
  }

  private static MockHttpServletRequest request(String path, String authorization) {
    var request = new MockHttpServletRequest("GET", path);
    request.addHeader("X-Device-Key", DEVICE);
    request.addHeader("Authorization", authorization);
    return request;
  }

  private static HandlerInterceptor access(AtomicInteger checks) {
    LocalConfig config = new LocalConfig() {
      @Override public String required(String key) { return DEVICE; }
    };
    AuthService auth = new AuthService(null, null, null, null) {
      @Override public Admin require(String token) {
        checks.incrementAndGet();
        if (!token.equals("fixture")) throw new Failure(401, "ADMIN_EXPIRED", "Expired");
        return new Admin(1, "fixture", "fixture@example.invalid");
      }
    };
    var registry = new InterceptorRegistry() {
      HandlerInterceptor[] registered() {
        return getInterceptors().toArray(HandlerInterceptor[]::new);
      }
    };
    new ApiAccess(config, auth).addInterceptors(registry);
    return registry.registered()[0];
  }
}
