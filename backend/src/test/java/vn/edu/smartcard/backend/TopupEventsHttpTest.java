package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.smartcard.shared.*;

/** Real HTTP and PostgreSQL, isolated in a throwaway schema in ttm_test. */
class TopupEventsHttpTest {
  @Test
  void eventsDriveCreditWithoutPollingAndRecoverAfterDisconnect() throws Exception {
    Path source = Path.of(System.getProperty("ttm.test.config",
        Path.of("../.local/test/server.properties").toAbsolutePath().toString()));
    Properties properties = new Properties();
    try (var in = Files.newInputStream(source)) { properties.load(in); }
    assertEquals("jdbc:postgresql://127.0.0.1:5548/ttm_test", properties.getProperty("db.url"));
    Path base = Path.of("target/test-work");
    Files.createDirectories(base);
    Path file = Files.createTempDirectory(base, "topup-events-http-").resolve("server.properties");
    String schema = "topupevent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    System.setProperty("ttm.config", source.toAbsolutePath().toString());
    LocalConfig initial = new LocalConfig();
    try (var ds = (com.zaxxer.hikari.HikariDataSource) initial.dataSource()) {
      new JdbcTemplate(ds).execute("CREATE SCHEMA " + schema);
    }
    properties.setProperty("db.url", properties.getProperty("db.url") + "?currentSchema=" + schema);
    properties.setProperty("bank.provider", "sepay");
    properties.setProperty("bank.account", "000000000000");
    properties.setProperty("bank.code", "BIDV");
    properties.setProperty("bank.main.account", "");
    properties.setProperty("sepay.webhook.api.key", SePayGatewayTest.KEY);
    try (var out = Files.newOutputStream(file)) { properties.store(out, "isolated topup event test"); }
    System.setProperty("ttm.config", file.toAbsolutePath().toString());
    SpringApplication app = new SpringApplication(BackendApplication.class);
    app.addInitializers(context -> ((GenericApplicationContext) context).registerBean(
        DatabaseFixture.RecordingMail.class,
        () -> new DatabaseFixture.RecordingMail(context.getBean(LocalConfig.class)),
        bean -> bean.setPrimary(true)));
    app.addInitializers(context -> ((GenericApplicationContext) context).registerBean(
        DatabaseFixture.FakeBank.class,
        () -> new DatabaseFixture.FakeBank(context.getBean(LocalConfig.class)),
        bean -> bean.setPrimary(true)));
    try (ConfigurableApplicationContext context = app.run("--server.port=0", "--logging.level.root=ERROR")) {
      Client client = new Client("http://127.0.0.1:"
          + context.getEnvironment().getProperty("local.server.port"), properties.getProperty("device.key"));
      var setup = new Api.SetupOtp(properties.getProperty("setup.token"), "events_admin", "events@example.invalid");
      String password = "Events-fixture-2026";
      client.post("/api/setup/otp", setup);
      String otp = context.getBean(DatabaseFixture.RecordingMail.class).setupCode;
      client.post("/api/setup", new Api.Setup(setup.token(), setup.username(), setup.email(), password, otp));
      client.token = client.post("/api/admin/login", new Api.Credentials(setup.username(), password))
          .path("token").asText();
      String alias = client.post("/api/readers/new", Map.of()).path("id").asText();
      client.post("/api/reader/connect", new Api.Connect(alias, ""));
      Profile profile = new Profile("", "AT210101", "Trần Văn An", "012345679999", "01/01/2005",
          "AT21A", "0912345678", "events@example.invalid", 2025,
          Base64.getEncoder().encodeToString(
              Portraits.jpeg(new BufferedImage(480, 640, BufferedImage.TYPE_INT_RGB), .7f)), 0);
      String cardId = client.post("/api/admin/card/init", new Api.Enrollment(profile, "123456"))
          .path("cardId").asText();
      client.post("/api/student/login", new Api.Pin("123456"));
      JdbcTemplate db = context.getBean(JdbcTemplate.class);
      CardService cards = context.getBean(CardService.class);

      assertNull(WalletService.class.getMethod("processReceivedCredits")
          .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class));
      JsonNode first = client.post("/api/topups", new Api.Amount(40000));
      long id = first.path("id").asLong();
      String memo = first.path("reference").asText();
      HttpResponse<String> unauthorized = client.call("GET", "/api/topups/" + id + "/events", null,
          UUID.randomUUID().toString());
      assertEquals(409, unauthorized.statusCode(), unauthorized.body());
      CountDownLatch committed = new CountDownLatch(1), acknowledge = new CountDownLatch(1);
      cards.sync.fault = point -> {
        if (point.equals("DB_COMMITTED")) {
          committed.countDown();
          try {
            if (!acknowledge.await(8, TimeUnit.SECONDS)) throw new AssertionError("acknowledgement timeout");
          } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
      };
      try (Watch watch = client.watch(id)) {
        Api.TopupEvent initialEvent = watch.nextStatus();
        assertEquals("WAITING_BANK", initialEvent.status());
        assertEquals(0, initialEvent.revision());
        client.webhook(920001, memo, 40000);
        try {
          assertTrue(committed.await(6, TimeUnit.SECONDS), "credit did not start from the webhook event");
          assertEquals("BANK_RECEIVED", db.queryForObject("SELECT status FROM topups WHERE id=?", String.class, id));
          assertEquals("WAIT_CARD", db.queryForObject("SELECT status FROM admin_transaction_journal"
              + " WHERE source_key='BANK:SEPAY:920001'", String.class));
          assertEquals("BANK_RECEIVED", watch.until("BANK_RECEIVED").status());
        } finally { acknowledge.countDown(); }
        Api.TopupEvent done = watch.until("CREDITED");
        assertTrue(done.revision() >= 2);
        assertEquals(1, db.queryForObject("SELECT count(*) FROM wallet_operations"
            + " WHERE topup_id=? AND status='APPLIED'", Integer.class, id));
        assertEquals("CREDITED", db.queryForObject("SELECT status FROM admin_transaction_journal"
            + " WHERE source_key='BANK:SEPAY:920001'", String.class));
        client.webhook(920001, memo, 40000);
        client.webhook(920002, memo, 40000);
        assertEquals("UNKNOWN", db.queryForObject("SELECT kind FROM admin_transaction_journal"
            + " WHERE source_key='BANK:SEPAY:920002'", String.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM wallet_operations"
            + " WHERE topup_id=? AND status='APPLIED'", Integer.class, id));
      } finally { acknowledge.countDown(); cards.sync.fault = point -> {}; }

      // A completed event that happened while the client was offline is sent as the first snapshot.
      JsonNode second = client.post("/api/topups", new Api.Amount(25000));
      long secondId = second.path("id").asLong();
      try (Watch disconnected = client.watch(secondId)) {
        assertEquals("WAITING_BANK", disconnected.nextStatus().status());
      }
      client.webhook(920003, second.path("reference").asText(), 25000);
      await(() -> "CREDITED".equals(db.queryForObject("SELECT status FROM topups WHERE id=?", String.class, secondId)));
      try (Watch reconnected = client.watch(secondId)) {
        assertEquals("CREDITED", reconnected.nextStatus().status());
      }
      assertEquals(65000L, db.queryForObject("SELECT balance FROM card_wallets WHERE card_id=?", Long.class, cardId));

      // Expiration is a one-shot server timer and releases the reader without a status request.
      JsonNode third = client.post("/api/topups", new Api.Amount(15000));
      long thirdId = third.path("id").asLong();
      try (Watch expiry = client.watch(thirdId)) {
        assertEquals("WAITING_BANK", expiry.nextStatus().status());
        db.update("UPDATE topups SET expires_at=clock_timestamp()+interval '1 second' WHERE id=?", thirdId);
        db.execute("SELECT pg_notify('ttm_topup_status','" + thirdId + "')");
        assertEquals("EXPIRED", expiry.until("EXPIRED").status());
        assertFalse(SecurityMaterial.JSON.readTree(client.call("GET", "/api/reader/activity", null,
            client.terminal).body()).path("topupReserved").asBoolean());
      }

      // Closing PostgreSQL's dedicated listener must not lose a paid request.
      JsonNode reconnecting = client.post("/api/topups", new Api.Amount(12000));
      long reconnectingId = reconnecting.path("id").asLong();
      try (Watch old = client.watch(reconnectingId)) {
        assertEquals("WAITING_BANK", old.nextStatus().status());
        assertEquals(Boolean.TRUE, db.queryForObject(
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity"
                + " WHERE datname=current_database() AND application_name=?",
            Boolean.class, "ttm-topup-events:" + schema));
        assertThrows(ExecutionException.class, old::nextStatus);
      }
      client.webhook(920004, reconnecting.path("reference").asText(), 12000);
      await(() -> "CREDITED".equals(db.queryForObject(
          "SELECT status FROM topups WHERE id=?", String.class, reconnectingId)));
      try (Watch recovered = client.watch(reconnectingId)) {
        assertEquals("CREDITED", recovered.nextStatus().status());
      }

      // Wrong amounts and wrong references are kept for reconciliation, never credited.
      JsonNode mismatch = client.post("/api/topups", new Api.Amount(30000));
      long mismatchId = mismatch.path("id").asLong();
      try (Watch review = client.watch(mismatchId)) {
        assertEquals("WAITING_BANK", review.nextStatus().status());
        client.webhook(920005, mismatch.path("reference").asText(), 15000);
        assertEquals("REVIEW", review.until("REVIEW").status());
      }
      assertEquals("UNKNOWN", db.queryForObject("SELECT kind FROM admin_transaction_journal"
          + " WHERE source_key='BANK:SEPAY:920005'", String.class));
      assertEquals("AMOUNT_MISMATCH", db.queryForObject("SELECT review_reason FROM bank_events"
          + " WHERE provider_id='SEPAY:920005'", String.class));
      assertEquals(0, db.queryForObject("SELECT count(*) FROM wallet_operations WHERE topup_id=?",
          Integer.class, mismatchId));

      JsonNode wrongMemo = client.post("/api/topups", new Api.Amount(18000));
      long wrongMemoId = wrongMemo.path("id").asLong();
      client.webhook(920006, "INVALID", 18000);
      assertEquals("INVALID_TRANSFER_CODE", db.queryForObject("SELECT review_reason FROM bank_events"
          + " WHERE provider_id='SEPAY:920006'", String.class));
      assertEquals("WAITING_BANK", db.queryForObject("SELECT status FROM topups WHERE id=?",
          String.class, wrongMemoId));
      assertEquals("EXPIRED", client.post("/api/topups/" + wrongMemoId + "/cancel?includeStatus=true",
          Map.of()).path("status").asText());

      // Cancellation and webhook compete for the same request; exactly one outcome wins.
      JsonNode racing = client.post("/api/topups", new Api.Amount(22000));
      long racingId = racing.path("id").asLong();
      CountDownLatch start = new CountDownLatch(1);
      CompletableFuture<Void> payment = CompletableFuture.runAsync(() -> {
        try { start.await(); client.webhook(920007, racing.path("reference").asText(), 22000); }
        catch (Exception failure) { throw new CompletionException(failure); }
      });
      CompletableFuture<JsonNode> cancellation = CompletableFuture.supplyAsync(() -> {
        try { start.await(); return client.post("/api/topups/" + racingId + "/cancel?includeStatus=true", Map.of()); }
        catch (Exception failure) { throw new CompletionException(failure); }
      });
      start.countDown();
      payment.get(15, TimeUnit.SECONDS);
      String cancellationStatus = cancellation.get(15, TimeUnit.SECONDS).path("status").asText();
      if (cancellationStatus.equals("EXPIRED")) {
        assertEquals("EXPIRED", db.queryForObject("SELECT status FROM topups WHERE id=?", String.class, racingId));
        assertEquals("UNKNOWN", db.queryForObject("SELECT kind FROM admin_transaction_journal"
            + " WHERE source_key='BANK:SEPAY:920007'", String.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM wallet_operations WHERE topup_id=?",
            Integer.class, racingId));
      } else {
        assertTrue(Set.of("BANK_RECEIVED", "CREDITED").contains(cancellationStatus), cancellationStatus);
        await(() -> "CREDITED".equals(db.queryForObject("SELECT status FROM topups WHERE id=?",
            String.class, racingId)));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM wallet_operations"
            + " WHERE topup_id=? AND status='APPLIED'", Integer.class, racingId));
      }
    } finally {
      System.setProperty("ttm.config", source.toAbsolutePath().toString());
      LocalConfig cleanup = new LocalConfig();
      try (var ds = (com.zaxxer.hikari.HikariDataSource) cleanup.dataSource()) {
        new JdbcTemplate(ds).execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }

  private static void await(Callable<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (System.nanoTime() < deadline) {
      if (condition.call()) return;
      Thread.sleep(40);
    }
    fail("Timed out waiting for event-driven processing");
  }

  private static final class Client {
    final String base, key, terminal = UUID.randomUUID().toString();
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    String token;
    Client(String base, String key) { this.base = base; this.key = key; }
    HttpResponse<String> call(String method, String path, Object body, String term) throws Exception {
      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
          .header("X-Device-Key", key).header("X-Terminal-ID", term);
      if (token != null) b.header("Authorization", "Bearer " + token);
      if (method.equals("GET")) b.GET();
      else b.header("Content-Type", "application/json").POST(
          HttpRequest.BodyPublishers.ofString(SecurityMaterial.JSON.writeValueAsString(body)));
      return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode post(String path, Object body) throws Exception {
      HttpResponse<String> response = call("POST", path, body, terminal);
      assertEquals(200, response.statusCode(), response.body());
      return SecurityMaterial.JSON.readTree(response.body());
    }
    void webhook(long event, String memo, long amount) throws Exception {
      Map<String, Object> body = SePayGatewayTest.payload(event, memo, amount);
      body.put("subAccount", "000000000000");
      HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/webhook"))
          .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
          .header("Authorization", "Apikey " + SePayGatewayTest.KEY)
          .POST(HttpRequest.BodyPublishers.ofString(SecurityMaterial.JSON.writeValueAsString(body))).build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
    }
    Watch watch(long id) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
      while (System.nanoTime() < deadline) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/topups/" + id + "/events"))
            .header("X-Device-Key", key).header("X-Terminal-ID", terminal)
            .header("Accept", "text/event-stream").GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 200) return new Watch(response.body());
        try (InputStream body = response.body()) {
          String error = new String(body.readNBytes(8192), StandardCharsets.UTF_8);
          assertEquals(503, response.statusCode(), error);
        }
        Thread.sleep(50);
      }
      throw new AssertionError("SSE listener did not become ready");
    }
  }

  private static final class Watch implements AutoCloseable {
    final InputStream body;
    final BufferedReader reader;
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    Watch(InputStream body) {
      this.body = body;
      reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
    }
    Api.TopupEvent nextStatus() throws Exception {
      Future<Api.TopupEvent> next = executor.submit(() -> {
        String name = "", line;
        StringBuilder data = new StringBuilder();
        while ((line = reader.readLine()) != null) {
          if (line.isEmpty()) {
            if (name.equals("status") && !data.isEmpty())
              return SecurityMaterial.JSON.readValue(data.toString(), Api.TopupEvent.class);
            name = ""; data.setLength(0);
          } else if (line.startsWith("event:")) name = line.substring(6).strip();
          else if (line.startsWith("data:")) data.append(line.substring(5).stripLeading());
        }
        throw new EOFException("Event stream closed");
      });
      try { return next.get(10, TimeUnit.SECONDS); }
      catch (TimeoutException timeout) { body.close(); throw timeout; }
    }
    Api.TopupEvent until(String status) throws Exception {
      for (int i = 0; i < 5; i++) {
        Api.TopupEvent event = nextStatus();
        if (event.status().equals(status)) return event;
      }
      throw new AssertionError("Expected status " + status);
    }
    @Override public void close() throws IOException { body.close(); executor.shutdownNow(); }
  }
}
