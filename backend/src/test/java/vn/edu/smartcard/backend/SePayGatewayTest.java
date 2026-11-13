package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;

class SePayGatewayTest {
  static final String KEY = "fixture-sepay-key-32-characters-not-live";
  private SePayGateway gateway;

  @BeforeEach
  void setup() throws Exception {
    Path dir = Files.createTempDirectory(Path.of("target"), "sepay-contract-");
    Path file = dir.resolve("server.properties");
    Files.writeString(
        file,
        "bank.provider=sepay\nbank.account=96247Y6CZZ\nbank.code=BIDV\n"
            + "bank.name=TRAN HOANG THU\nsepay.webhook.api.key="
            + KEY
            + "\n");
    System.setProperty("ttm.config", file.toAbsolutePath().toString());
    gateway = new SePayGateway(new LocalConfig());
  }

  static Map<String, Object> payload(long id, String code, long amount) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", id);
    body.put("gateway", "BIDV");
    body.put(
        "transactionDate",
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .format(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"))));
    body.put("accountNumber", "000000000000");
    body.put("subAccount", "96247Y6CZZ");
    body.put("code", code);
    body.put("content", "NAP " + code + " CHUYEN TIEN");
    body.put("transferType", "in");
    body.put("transferAmount", amount);
    body.put("referenceCode", "FIXTURE-REF-" + id);
    return body;
  }

  private SePayGateway.Notice verify(Map<String, Object> body) throws Exception {
    return gateway.verify(SecurityMaterial.JSON.writeValueAsString(body), "Apikey " + KEY);
  }

  @Test
  void qrUsesRequestedApiVirtualAccountAmountAndUniqueRequestMemo() {
    assertEquals("NTCT07035200000001", SePayGateway.paymentCode("CT070352", 1));
    String uri =
        URLDecoder.decode(
            gateway.qrUri(50000, "NTCT07035200000001").toString(), StandardCharsets.UTF_8);
    assertTrue(uri.startsWith("https://vietqr.app/img?"));
    for (String field :
        List.of(
            "bank=BIDV",
            "acc=96247Y6CZZ",
            "amount=50000",
            "des=NTCT07035200000001",
            "template=qronly",
            "showinfo=true",
            "holder=TRAN HOANG THU")) assertTrue(uri.contains(field), field);
    assertFalse(uri.contains("fullacc="));
  }

  @Test
  void topupQrUsesTheSameQrOnlyTemplateForBothImageOptions() {
    String uri = URLDecoder.decode(
        gateway.qrUri(50000, "NTCT07035200000001", true).toString(), StandardCharsets.UTF_8);
    assertTrue(uri.startsWith("https://vietqr.app/img?"));
    for (String field : List.of("bank=BIDV", "acc=96247Y6CZZ", "template=qronly&amount=50000",
        "des=NTCT07035200000001", "showinfo=true", "holder=TRAN HOANG THU")) {
      assertTrue(uri.contains(field), field);
    }
    assertEquals(gateway.qrUri(50000, "NTCT07035200000001"),
        gateway.qrUri(50000, "NTCT07035200000001", true));
    assertThrows(Failure.class, () -> gateway.qrUri(50000, "INVALID", true));
  }

  @Test
  void requestNumbersNeverWrapOrLoseDigits() {
    assertEquals("TTDT01020399999999", SePayGateway.invoiceCode("DT010203", 99_999_999));
    for (long id : new long[] {-1, 0, 100_000_000, Long.MAX_VALUE}) {
      assertEquals(
          "PAYMENT_CODE_EXHAUSTED",
          assertThrows(Failure.class, () -> SePayGateway.invoiceCode("AT010203", id)).code);
      assertThrows(Failure.class, () -> SePayGateway.paymentCode("AT010203", id));
    }
  }

  @Test
  void legacyTruncatedAndEmbeddedMemosAreNeverAcceptedAsFullRequestCodes() throws Exception {
    for (String code :
        List.of(
            "TTAT010203",
            "NTCT070352",
            "TTAT0102030000000",
            "XTTAT01020300000125",
            "TTAT01020300000125X",
            "TTM070352",
            "CTHD000000000125")) {
      assertNull(verify(payload(73, code, 35000)).paymentCode(), code);
      assertThrows(Failure.class, () -> gateway.qrUri(35000, code));
    }
    var extractedOnlyStudent = payload(73, "TTAT01020300000125", 35000);
    extractedOnlyStudent.put("code", "TTAT010203");
    assertNull(verify(extractedOnlyStudent).paymentCode());
  }

  @Test
  void invoiceReferenceUsesSameBankConfigurationAndDistinctMemo() throws Exception {
    String reference = "TTAT01020300000001";
    String uri =
        URLDecoder.decode(gateway.qrUri(35000, reference, true).toString(), StandardCharsets.UTF_8);
    assertTrue(uri.contains("template=qronly&amount=35000"));
    assertFalse(uri.contains("template=compact"));
    assertEquals(gateway.qrUri(35000, reference), gateway.qrUri(35000, reference, true));
    assertTrue(uri.contains("des=" + reference));
    assertTrue(uri.contains("amount=35000"));
    var body = payload(991, reference, 35000);
    assertEquals(reference, verify(body).paymentCode());
    body.put("code", null);
    assertEquals(reference, verify(body).paymentCode());
    body.put("content", reference + " NTCT07035200000001");
    assertNull(verify(body).paymentCode());
    body.put("content", reference);
    body.put("code", "NTCT07035200000001");
    assertNull(verify(body).paymentCode());
    assertThrows(Failure.class, () -> gateway.qrUri(35000, "TTAT010203000000014"));
  }

  @Test
  void authenticatesApiKeyAndAcceptsVaInSubAccountOrAccountNumber() throws Exception {
    var body = payload(73, "NTCT07035200000001", 50000);
    var notice = verify(body);
    assertEquals("SEPAY:73", notice.providerId());
    assertEquals("NTCT07035200000001", notice.paymentCode());
    assertEquals(50000, notice.amount());
    body.put("subAccount", "");
    body.put("accountNumber", "96247Y6CZZ");
    assertEquals(50000, verify(body).amount());
    String raw = SecurityMaterial.JSON.writeValueAsString(body);
    assertEquals(401, assertThrows(Failure.class, () -> gateway.verify(raw, null)).status);
    assertThrows(Failure.class, () -> gateway.verify(raw, "Apikey wrong-key"));
    assertThrows(Failure.class, () -> gateway.verify(raw, "Bearer " + KEY));
  }

  @Test
  void wrongVaCannotBeOverriddenByMainAccount() throws Exception {
    var body = payload(73, "NTCT07035200000001", 50000);
    body.put("accountNumber", "96247Y6CZZ");
    body.put("subAccount", "DIFFERENTVA");
    assertThrows(Failure.class, () -> verify(body));
    body.put("subAccount", "96247Y6CZZ");
    body.put("gateway", "MBBank");
    assertThrows(Failure.class, () -> verify(body));
  }

  @Test
  void memoBoundariesNullCodeAndConflictingCodesAreHandledWithoutGuessing() throws Exception {
    var body = payload(73, "NTCT07035200000001", 50000);
    body.put("code", null);
    assertEquals("NTCT07035200000001", verify(body).paymentCode());
    body.put("content", "NTCT070352000000019");
    assertNull(verify(body).paymentCode());
    body.put("content", "NTCT07035200000001 NTAT45010100000001");
    assertNull(verify(body).paymentCode());
    body.put("content", "NTCT07035200000001");
    body.put("code", "NTAT45010100000001");
    assertNull(verify(body).paymentCode());
  }

  @Test
  void outgoingAndSendTestNeverBecomeIncomingFunds() throws Exception {
    var body = payload(73, "NTCT07035200000001", 50000);
    body.put("transferType", "out");
    assertEquals(-50000, verify(body).amount());
    assertTrue(verify(Map.of("id", 0)).probe());
    body.put("transferAmount", 50000.25);
    assertThrows(Failure.class, () -> verify(body));
    body.put("id", -1);
    assertThrows(Failure.class, () -> verify(body));
  }

  @Test
  void malformedOversizedAndInvalidTimeAreRejected() throws Exception {
    assertThrows(Failure.class, () -> gateway.verify("{", "Apikey " + KEY));
    assertThrows(Failure.class, () -> gateway.verify("x".repeat(65537), "Apikey " + KEY));
    var body = payload(73, "NTCT07035200000001", 50000);
    body.put("transactionDate", "2026-02-30 12:00:00");
    assertThrows(Failure.class, () -> verify(body));
  }

  @Test
  void tunnelHostOnlyAllowsExactPostWebhookAndBlocksTheApplicationApi() throws Exception {
    var filter = new SePayIngressFilter();
    for (String path :
        List.of("/", "/api/health", "/api/admin/cards", "/webhook/", "/webhook/../api/health")) {
      var request = new MockHttpServletRequest("POST", path);
      request.addHeader("Host", SePayIngressFilter.TUNNEL_HOST);
      var response = new MockHttpServletResponse();
      filter.doFilter(request, response, (req, res) -> fail("Public API was reached: " + path));
      assertEquals(404, response.getStatus());
    }
    var request = new MockHttpServletRequest("POST", "/webhook");
    request.addHeader("Host", SePayIngressFilter.TUNNEL_HOST);
    var response = new MockHttpServletResponse();
    boolean[] reached = {false};
    filter.doFilter(request, response, (req, res) -> reached[0] = true);
    assertTrue(reached[0]);
  }

  @Test
  void forwardedTrafficWithoutHostRewriteOnlyReachesPostWebhook() throws Exception {
    var filter = new SePayIngressFilter();
    var health = new MockHttpServletRequest("GET", "/api/health");
    health.addHeader("Host", "public.example");
    health.addHeader("X-Forwarded-For", "203.0.113.10");
    var rejected = new MockHttpServletResponse();
    filter.doFilter(health, rejected, (req, res) -> fail("Forwarded application API was reached"));
    assertEquals(404, rejected.getStatus());

    var webhook = new MockHttpServletRequest("POST", "/webhook");
    webhook.addHeader("Host", "public.example");
    webhook.addHeader("X-Forwarded-For", "203.0.113.10");
    var response = new MockHttpServletResponse();
    boolean[] reached = {false};
    filter.doFilter(webhook, response, (req, res) -> reached[0] = true);
    assertTrue(reached[0]);
  }
}
