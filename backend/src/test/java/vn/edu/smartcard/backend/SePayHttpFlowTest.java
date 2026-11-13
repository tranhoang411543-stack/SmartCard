package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import vn.edu.smartcard.shared.Api;

class SePayHttpFlowTest extends DatabaseFixture {
  private void configureWebhook(String name) throws Exception {
    Path file = config.root().resolve(name + ".properties");
    Files.writeString(
        file,
        Files.readString(config.root().resolve("server.properties"))
            + "\nbank.provider=sepay\nbank.account=96247Y6CZZ\nsepay.webhook.api.key="
            + SePayGatewayTest.KEY
            + "\n");
    System.setProperty("ttm.config", file.toAbsolutePath().toString());
  }

  @Test
  void expiredRequestAcknowledgesWebhookWithoutCreditingMoney() throws Exception {
    var p = enroll("AT680101", "898000000001", 2026);
    long id =
        repo.db.queryForObject(
            "INSERT INTO topups(card_id,reference,amount,status,expires_at)"
                + " VALUES(?,'HTTP-EXPIRED-NT',50000,'WAITING_BANK',clock_timestamp()-interval '5"
                + " seconds') RETURNING id",
            Long.class,
            p.cardId());
    String memo = SePayGateway.paymentCode(p.studentCode(), id);
    repo.db.update(
        "INSERT INTO"
            + " sepay_topup_requests(reference,bank_memo,original_card_id,amount,opened_at,expires_at)"
            + " VALUES('HTTP-EXPIRED-NT',?,?,50000,clock_timestamp()-interval '60"
            + " seconds',clock_timestamp()-interval '5 seconds')",
        memo,
        p.cardId());
    var payload = SePayGatewayTest.payload(880001, memo, 50000);
    payload.put(
        "transactionDate",
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
            .format(java.time.Instant.now().minusSeconds(10)));
    configureWebhook("expired-sepay-http");
    try (var app =
        SpringApplication.run(
            BackendApplication.class, "--server.port=0", "--logging.level.root=ERROR")) {
      String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
      var request =
          HttpRequest.newBuilder(URI.create(base + "/webhook"))
              .header("Content-Type", "application/json")
              .header("Authorization", "Apikey " + SePayGatewayTest.KEY)
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      SecurityMaterial.JSON.writeValueAsString(payload)))
              .build();
      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertTrue(SecurityMaterial.JSON.readTree(response.body()).path("success").asBoolean());
      assertEquals(
          "EXPIRED",
          repo.db.queryForObject("SELECT status FROM topups WHERE id=?", String.class, id));
      assertEquals(
          0,
          repo.db.queryForObject(
              "SELECT count(*) FROM wallet_operations WHERE topup_id=?", Integer.class, id));
      assertEquals(
          "REVIEW",
          repo.db.queryForObject(
              "SELECT result FROM bank_events WHERE provider_id='SEPAY:880001'", String.class));
    }
  }

  @Test
  void webhookAuthenticatesWithoutDeviceKeyAndProbeDoesNotCreateMoney() throws Exception {
    configureWebhook("sepay-http");
    try (ConfigurableApplicationContext app =
        SpringApplication.run(
            BackendApplication.class, "--server.port=0", "--logging.level.root=ERROR")) {
      String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
      var http = HttpClient.newHttpClient();
      long before = repo.db.queryForObject("SELECT count(*) FROM bank_events", Long.class);
      var denied =
          HttpRequest.newBuilder(URI.create(base + "/webhook"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("{\"id\":0}"))
              .build();
      assertEquals(401, http.send(denied, HttpResponse.BodyHandlers.ofString()).statusCode());
      var accepted =
          HttpRequest.newBuilder(URI.create(base + "/webhook"))
              .header("Content-Type", "application/json")
              .header("Authorization", "Apikey " + SePayGatewayTest.KEY)
              .POST(HttpRequest.BodyPublishers.ofString("{\"id\":0}"))
              .build();
      var response = http.send(accepted, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertTrue(SecurityMaterial.JSON.readTree(response.body()).path("success").asBoolean());
      var tooLarge =
          HttpRequest.newBuilder(URI.create(base + "/webhook"))
              .header("Content-Type", "application/json")
              .header("Authorization", "Apikey " + SePayGatewayTest.KEY)
              .POST(
                  HttpRequest.BodyPublishers.ofInputStream(
                      () -> new ByteArrayInputStream(new byte[65537])))
              .build();
      assertEquals(413, http.send(tooLarge, HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(before, repo.db.queryForObject("SELECT count(*) FROM bank_events", Long.class));
    }
  }

  @Test
  void missingAndInvalidMemoReceiptsAreUnlinkedAndRequireAdminOverHttp() throws Exception {
    configureWebhook("review-sepay-http");
    try (var app =
        SpringApplication.run(
            BackendApplication.class, "--server.port=0", "--logging.level.root=ERROR")) {
      String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
      var http = HttpClient.newHttpClient();
      var missing = SePayGatewayTest.payload(880002, null, 50000);
      missing.remove("content");
      var invalid = SePayGatewayTest.payload(880003, null, 45000);
      invalid.put("content", "CHUYEN TIEN KHONG CO MA");
      for (var payload : java.util.List.of(missing, invalid, invalid)) {
        var post =
            HttpRequest.newBuilder(URI.create(base + "/webhook"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Apikey " + SePayGatewayTest.KEY)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        SecurityMaterial.JSON.writeValueAsString(payload)))
                .build();
        var ack = http.send(post, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ack.statusCode());
        assertTrue(SecurityMaterial.JSON.readTree(ack.body()).path("success").asBoolean());
      }

      var list =
          HttpRequest.newBuilder(URI.create(base + "/api/admin/bank-receipts"))
              .header("X-Device-Key", config.required("device.key"))
              .header("X-Terminal-ID", TERMINAL);
      assertEquals(
          401, http.send(list.build(), HttpResponse.BodyHandlers.discarding()).statusCode());
      String token = auth.login(new Api.Credentials("fixture_admin", TEST_PASSWORD)).token();
      var response =
          http.send(
              list.header("Authorization", "Bearer " + token).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      var rows =
          java.util.Arrays.stream(
                  SecurityMaterial.JSON.readValue(response.body(), Api.BankReceipt[].class))
              .filter(
                  r -> java.util.Set.of("SEPAY:880002", "SEPAY:880003").contains(r.providerId()))
              .toList();
      assertEquals(2, rows.size());
      for (var row : rows) {
        assertEquals("REVIEW", row.result());
        assertEquals("INVALID_TRANSFER_CODE", row.reason());
        assertEquals("", row.linkedInvoice());
        assertEquals("", row.linkedTopup());
        assertNull(row.expectedAmount());
        assertEquals(
            row.providerId().equals("SEPAY:880002") ? "" : "CHUYEN TIEN KHONG CO MA",
            row.content());
      }
      assertEquals(
          0, repo.db.queryForObject("SELECT count(*) FROM wallet_operations", Integer.class));
    }
  }
}
