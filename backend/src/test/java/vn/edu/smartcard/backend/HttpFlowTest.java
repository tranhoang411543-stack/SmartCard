package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.smartcard.shared.*;

class HttpFlowTest {
  private static final String HISTORY = "/student/transactions";
  private static final String HISTORY_PAGES = HISTORY + "/pages";
  private static final String TOPUP_REFERENCE = "NTAT21010100000001";

  @Test
  void realHttpServerEnforcesRolesAndConnectsSwingContractsToApplet() throws Exception {
    Path source =
        Path.of(
            System.getProperty(
                "ttm.test.config",
                Path.of("../.local/test/server.properties").toAbsolutePath().toString()));
    Properties p = new Properties();
    try (var in = Files.newInputStream(source)) {
      p.load(in);
    }
    assertTrue(p.getProperty("db.url").endsWith("/ttm_test"));
    Path base = Path.of("target/test-work");
    Files.createDirectories(base);
    Path file = Files.createTempDirectory(base, "http-").resolve("server.properties");
    String schema = "http_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String original = p.getProperty("db.url");
    System.setProperty("ttm.config", source.toAbsolutePath().toString());
    LocalConfig initial = new LocalConfig();
    try (var ds = (com.zaxxer.hikari.HikariDataSource) initial.dataSource()) {
      new JdbcTemplate(ds).execute("CREATE SCHEMA " + schema);
    }
    p.setProperty("db.url", original + "?currentSchema=" + schema);
    p.setProperty("casso.webhook.key", "fixture-webhook-key");
    try (var out = Files.newOutputStream(file)) {
      p.store(out, "isolated HTTP test");
    }
    System.setProperty("ttm.config", file.toAbsolutePath().toString());
    SpringApplication application = new SpringApplication(BackendApplication.class);
    application.addInitializers(context -> ((GenericApplicationContext) context).registerBean(
        DatabaseFixture.RecordingMail.class,
        () -> new DatabaseFixture.RecordingMail(context.getBean(LocalConfig.class)),
        bean -> bean.setPrimary(true)));
    try (ConfigurableApplicationContext context =
        application.run("--server.port=0", "--logging.level.root=ERROR")) {
      String baseUrl =
          "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port") + "/api";
      HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
      String terminal = UUID.randomUUID().toString();
      String key = p.getProperty("device.key");
      class Client {
        String token;

        HttpResponse<String> call(String method, String path, Object body, boolean device)
            throws Exception {
          HttpRequest.Builder b =
              HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20));
          if (device) b.header("X-Device-Key", key).header("X-Terminal-ID", terminal);
          if (token != null) b.header("Authorization", "Bearer " + token);
          if (method.equals("GET")) b.GET();
          else
            b.header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        SecurityMaterial.JSON.writeValueAsString(body == null ? Map.of() : body)));
          return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }

        JsonNode post(String path, Object body) throws Exception {
          HttpResponse<String> r = call("POST", path, body, true);
          assertEquals(200, r.statusCode(), r.body());
          return SecurityMaterial.JSON.readTree(r.body());
        }
      }
      Client c = new Client();
      assertEquals(200, c.call("GET", "/health", null, false).statusCode());
      assertEquals(403, c.call("GET", "/menu", null, false).statusCode());
      assertEquals(403, c.call("GET", HISTORY, null, false).statusCode());
      assertEquals(401, c.call("GET", "/admin/cards", null, true).statusCode());
      var firstAdmin = new Api.SetupOtp(p.getProperty("setup.token"), "http_admin", "http@example.invalid");
      String password = "HTTP-fixture-2026";
      assertFailure(c.call("POST", "/setup", Map.of("token", firstAdmin.token(),
          "username", firstAdmin.username(), "email", firstAdmin.email(), "password", password), true),
          400, "SETUP_OTP_REQUIRED");
      assertEquals(403, c.call("POST", "/setup/otp", firstAdmin, false).statusCode());
      assertEquals(403, c.call("POST", "/setup/otp",
          new Api.SetupOtp("invalid", firstAdmin.username(), firstAdmin.email()), true).statusCode());
      c.post("/setup/otp", firstAdmin);
      var mail = context.getBean(DatabaseFixture.RecordingMail.class);
      assertEquals(firstAdmin.email(), mail.setupAddress);
      assertTrue(mail.setupCode.matches("[0-9]{8}"));
      assertTrue(setupNeeded(c.call("GET", "/setup/status", null, true)));
      assertFailure(c.call("POST", "/setup/otp", firstAdmin, true), 429, "SETUP_OTP_LIMIT");
      assertFailure(c.call("POST", "/setup", new Api.Setup(firstAdmin.token(), firstAdmin.username(),
          "different@example.invalid", password, mail.setupCode), true), 400, "SETUP_OTP_UNAVAILABLE");

      String wrongCode = "00000000".equals(mail.setupCode) ? "00000001" : "00000000";
      var wrongSetup = new Api.Setup(firstAdmin.token(), firstAdmin.username(), firstAdmin.email(), password, wrongCode);
      for (int i = 0; i < 4; i++) {
        assertFailure(c.call("POST", "/setup", wrongSetup, true), 400, "SETUP_OTP_WRONG");
      }
      assertFailure(c.call("POST", "/setup", wrongSetup, true), 400, "SETUP_OTP_UNAVAILABLE");
      var spentSetup = new Api.Setup(firstAdmin.token(), firstAdmin.username(), firstAdmin.email(), password, mail.setupCode);
      assertFailure(c.call("POST", "/setup", spentSetup, true), 400, "SETUP_OTP_UNAVAILABLE");

      var setupDb = context.getBean(JdbcTemplate.class);
      setupDb.update("UPDATE admin_setup_codes SET sent_at=clock_timestamp()-interval '2 minutes'");
      c.post("/setup/otp", firstAdmin);
      setupDb.update("UPDATE admin_setup_codes SET expires_at=clock_timestamp()-interval '1 second'");
      var expiredSetup = new Api.Setup(firstAdmin.token(), firstAdmin.username(), firstAdmin.email(), password, mail.setupCode);
      assertFailure(c.call("POST", "/setup", expiredSetup, true), 400, "SETUP_OTP_UNAVAILABLE");
      assertTrue(setupNeeded(c.call("GET", "/setup/status", null, true)));

      setupDb.update("UPDATE admin_setup_codes SET sent_at=clock_timestamp()-interval '2 minutes'");
      c.post("/setup/otp", firstAdmin);
      var verifiedSetup = new Api.Setup(firstAdmin.token(), firstAdmin.username(), firstAdmin.email(), password, mail.setupCode);
      assertEquals("Khởi tạo admin thành công!", c.post("/setup", verifiedSetup).path("message").asText());
      assertFalse(setupNeeded(c.call("GET", "/setup/status", null, true)));
      assertFailure(c.call("POST", "/setup", verifiedSetup, true), 409, "SETUP_ALREADY_DONE");
      assertFailure(c.call("POST", "/setup/otp", firstAdmin, true), 409, "SETUP_ALREADY_DONE");
      c.token =
          c.post("/admin/login", new Api.Credentials(firstAdmin.username(), password))
              .path("token")
              .asText();
      String alias = c.post("/readers/new", Map.of()).path("id").asText();
      c.post("/reader/connect", new Api.Connect(alias, ""));
      Profile profile =
          new Profile(
              "",
              "AT210101",
              "Trần Văn An",
              "012345679999",
              "01/01/2005",
              "AT21A",
              "0912345678",
              "http@example.invalid",
              2025,
              Base64.getEncoder()
                  .encodeToString(
                      Portraits.jpeg(new BufferedImage(480, 640, BufferedImage.TYPE_INT_RGB), .7f)),
              0);
      JsonNode created = c.post("/admin/card/init", new Api.Enrollment(profile, "123456"));
      assertTrue(created.path("cardId").asText().startsWith("TTM"));
      String createdId = created.path("cardId").asText();
      JsonNode overview = SecurityMaterial.JSON.readTree(c.call("GET", "/admin/cards/overview", null, true).body());
      assertEquals("ACTIVE", overview.path("lifecycleStatuses").path(createdId).asText());
      assertFalse(SecurityMaterial.JSON.readTree(c.call("GET", "/admin/cards", null, true).body())
          .has("lifecycleStatuses"));
      c.post("/admin/cards/" + createdId + "/block", Map.of());
      overview = SecurityMaterial.JSON.readTree(c.call("GET", "/admin/cards/overview", null, true).body());
      assertEquals("BLOCKED", overview.path("lifecycleStatuses").path(createdId).asText());
      assertFailure(c.call("POST", "/admin/cards/" + createdId + "/block", Map.of(), true),
          409, "CARD_ALREADY_BLOCKED");
      c.post("/admin/card/unblock", Map.of());
      overview = SecurityMaterial.JSON.readTree(c.call("GET", "/admin/cards/overview", null, true).body());
      assertEquals("ACTIVE", overview.path("lifecycleStatuses").path(createdId).asText());
      HttpResponse<String> administrative = c.call("GET", "/admin/card", null, true);
      assertEquals(200, administrative.statusCode());
      assertFalse(administrative.body().contains("\"balance\""));
      assertEquals(
          "ADMIN",
          SecurityMaterial.JSON
              .readTree(administrative.body())
              .path("status")
              .path("role")
              .asText());
      assertEquals(
          "SYNCED",
          SecurityMaterial.JSON
              .readTree(c.call("GET", "/reader/sync", null, true).body())
              .path("phase")
              .asText());
      assertFailure(c.call("GET", "/student/home", null, true), 401, "PIN_SESSION");
      assertFailure(c.call("GET", HISTORY, null, true), 401, "PIN_SESSION");
      assertEquals(200, c.call("POST", "/student/login", new Api.Pin("123456"), true).statusCode());
      assertEquals(200, c.call("GET", "/admin/me", null, true).statusCode());
      assertEquals(200, c.call("GET", "/student/home", null, true).statusCode());
      c.post("/admin/logout", Map.of());
      c.token = null;
      assertEquals(401, c.call("POST", "/admin/card/unblock", Map.of(), true).statusCode());
      assertEquals(401, c.call("GET", HISTORY, null, true).statusCode());
      JsonNode home = c.post("/student/login", new Api.Pin("123456"));
      assertEquals(0, home.path("balance").asLong());
      assertEquals("Trần Văn An", home.path("profile").path("fullName").asText());
      assertTrue(historyPage(c.call("GET", HISTORY, null, true)).items().isEmpty());
      JsonNode recovery = c.post("/reader/reconcile", Map.of());
      assertEquals(
          Set.of("message"), SecurityMaterial.JSON.convertValue(recovery, Map.class).keySet());
      assertEquals(401, c.call("GET", "/student/home", null, true).statusCode());
      c.post("/student/login", new Api.Pin("123456"));
      assertEquals(
          400,
          c.call("POST", "/student/login", Map.of("pin", "123456", "admin", true), true)
              .statusCode());
      assertEquals(
          403,
          c.call("POST", "/bank/casso", Map.of("error", 0, "data", Map.of("id", 1)), false)
              .statusCode());
      assertEquals(
          400,
          c.call(
                  "POST",
                  "/invoices",
                  Map.of(
                      "studentCode",
                      "AT210101",
                      "items",
                      List.of(Map.of("itemId", 1, "quantity", 1.5))),
                  true)
              .statusCode());
      c.post("/student/change-pin", new Api.PinChange("654321"));
      assertEquals(401, c.call("POST", "/student/login", new Api.Pin("123456"), true).statusCode());
      assertEquals(200, c.call("POST", "/student/login", new Api.Pin("654321"), true).statusCode());
      assertEquals(400, c.call("GET", HISTORY + "?beforeId=0", null, true).statusCode());

      String invoice =
          seedCardTransactions(context.getBean(JdbcTemplate.class), created.path("cardId").asText());
      Api.CardTransactionPage first = historyPage(c.call("GET", HISTORY, null, true));
      assertEquals(20, first.items().size());
      assertEquals(
          List.of("REVERSAL", "DEBIT", "CREDIT"),
          first.items().subList(0, 3).stream().map(Api.CardTransaction::kind).toList());
      assertEquals(35000, first.items().get(0).amount());
      assertEquals(invoice, first.items().get(0).reference());
      assertEquals(invoice, first.items().get(1).reference());
      assertEquals(100000, first.items().get(2).amount());
      assertEquals(TOPUP_REFERENCE, first.items().get(2).reference());
      assertEquals(first.items().getLast().id(), first.nextBeforeId());
      Api.CardTransactionPage last = historyPage(
          c.call("GET", HISTORY + "?beforeId=" + first.nextBeforeId(), null, true));
      assertEquals(4, last.items().size());
      assertTrue(last.items().stream().allMatch(row -> row.id() < first.nextBeforeId()));
      assertNull(last.nextBeforeId());
      c.post("/student/logout", Map.of());
      assertEquals(401, c.call("GET", HISTORY, null, true).statusCode());
      c.post("/reader/eject", Map.of());
      assertEquals(409, c.call("GET", HISTORY, null, true).statusCode());
    } finally {
      System.setProperty("ttm.config", source.toAbsolutePath().toString());
      LocalConfig cleanup = new LocalConfig();
      try (var ds = (com.zaxxer.hikari.HikariDataSource) cleanup.dataSource()) {
        new JdbcTemplate(ds).execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }

  @Test
  void studentTransactionPagesAreBoundedAndScopedToTheAuthenticatedCard() throws Exception {
    Path source = Path.of(System.getProperty("ttm.test.config",
        Path.of("../.local/test/server.properties").toAbsolutePath().toString()));
    Properties properties = new Properties();
    try (var in = Files.newInputStream(source)) {
      properties.load(in);
    }
    assertTrue(properties.getProperty("db.url").endsWith("/ttm_test"));
    Path base = Path.of("target/test-work");
    Files.createDirectories(base);
    Path file = Files.createTempDirectory(base, "history-http-").resolve("server.properties");
    String schema = "history_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String original = properties.getProperty("db.url");
    System.setProperty("ttm.config", source.toAbsolutePath().toString());
    LocalConfig initial = new LocalConfig();
    try (var ds = (com.zaxxer.hikari.HikariDataSource) initial.dataSource()) {
      new JdbcTemplate(ds).execute("CREATE SCHEMA " + schema);
    }
    properties.setProperty("db.url", original + "?currentSchema=" + schema);
    try (var out = Files.newOutputStream(file)) {
      properties.store(out, "isolated student history HTTP test");
    }
    System.setProperty("ttm.config", file.toAbsolutePath().toString());
    SpringApplication application = new SpringApplication(BackendApplication.class);
    application.addInitializers(context -> ((GenericApplicationContext) context).registerBean(
        DatabaseFixture.RecordingMail.class,
        () -> new DatabaseFixture.RecordingMail(context.getBean(LocalConfig.class)),
        bean -> bean.setPrimary(true)));
    application.addInitializers(context -> ((GenericApplicationContext) context).registerBean(
        DatabaseFixture.FakeBank.class,
        () -> new DatabaseFixture.FakeBank(context.getBean(LocalConfig.class)),
        bean -> bean.setPrimary(true)));
    try (ConfigurableApplicationContext context =
        application.run("--server.port=0", "--logging.level.root=ERROR")) {
      String baseUrl = "http://127.0.0.1:"
          + context.getEnvironment().getProperty("local.server.port") + "/api";
      HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
      String terminal = UUID.randomUUID().toString();
      String key = properties.getProperty("device.key");
      class Client {
        String token;
        HttpResponse<String> call(String method, String path, Object body, String deviceTerminal)
            throws Exception {
          HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
              .timeout(Duration.ofSeconds(20));
          if (deviceTerminal != null) {
            request.header("X-Device-Key", key).header("X-Terminal-ID", deviceTerminal);
          }
          if (token != null) request.header("Authorization", "Bearer " + token);
          if ("GET".equals(method)) request.GET();
          else request.header("Content-Type", "application/json").POST(
              HttpRequest.BodyPublishers.ofString(SecurityMaterial.JSON.writeValueAsString(body)));
          return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        JsonNode post(String path, Object body) throws Exception {
          HttpResponse<String> response = call("POST", path, body, terminal);
          assertEquals(200, response.statusCode(), response.body());
          return SecurityMaterial.JSON.readTree(response.body());
        }
      }
      Client client = new Client();
      assertEquals(403, client.call("GET", HISTORY_PAGES, null, null).statusCode());
      var setup = new Api.SetupOtp(properties.getProperty("setup.token"), "history_admin", "history@example.invalid");
      String password = "History-fixture-2026";
      client.post("/setup/otp", setup);
      String otp = context.getBean(DatabaseFixture.RecordingMail.class).setupCode;
      client.post("/setup", new Api.Setup(setup.token(), setup.username(), setup.email(), password, otp));
      client.token = client.post("/admin/login", new Api.Credentials(setup.username(), password))
          .path("token").asText();
      String alias = client.post("/readers/new", Map.of()).path("id").asText();
      client.post("/reader/connect", new Api.Connect(alias, ""));
      Profile profile = new Profile("", "AT210101", "Trần Văn An", "012345679999", "01/01/2005",
          "AT21A", "0912345678", "history@example.invalid", 2025,
          Base64.getEncoder().encodeToString(
              Portraits.jpeg(new BufferedImage(480, 640, BufferedImage.TYPE_INT_RGB), .7f)), 0);
      String cardId = client.post("/admin/card/init", new Api.Enrollment(profile, "123456"))
          .path("cardId").asText();
      String otherTerminal = UUID.randomUUID().toString();
      assertFailure(client.call("POST", "/reader/connect", new Api.Connect(alias, ""), otherTerminal),
          409, "READER_IN_USE");
      assertFailure(client.call("POST", "/reader/eject", Map.of(), otherTerminal), 409, "READER_IN_USE");
      assertTrue(SecurityMaterial.JSON.readTree(client.call("GET", "/reader/status", null, terminal).body())
          .path("present").asBoolean());
      ReaderHub reader = context.getBean(ReaderHub.class);
      var reading = new java.util.concurrent.CountDownLatch(1);
      var releaseReading = new java.util.concurrent.CountDownLatch(1);
      var blockStatusOnce = new java.util.concurrent.atomic.AtomicBoolean();
      CardRepository cardRepository = context.getBean(CardRepository.class);
      reader.lifecycle(id -> {
        if (blockStatusOnce.compareAndSet(false, true)) {
          reading.countDown();
          try {
            if (!releaseReading.await(8, java.util.concurrent.TimeUnit.SECONDS))
              throw new AssertionError("status inspection timed out");
          } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
        return cardRepository.lifecycle(id);
      });
      try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var status = pool.submit(() -> client.call("GET", "/reader/status", null, terminal));
        try {
          assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS));
          JsonNode idle = SecurityMaterial.JSON.readTree(
              client.call("GET", "/reader/activity", null, terminal).body());
          assertTrue(idle.path("connected").asBoolean());
          assertFalse(idle.path("operating").asBoolean());
          var eject = pool.submit(() -> client.call("POST", "/reader/eject", Map.of(), terminal));
          long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
          while (!reader.activity(terminal).operating() && System.nanoTime() < deadline) Thread.sleep(5);
          assertTrue(reader.activity(terminal).operating());
          assertFalse(eject.isDone());
          assertFailure(client.call("POST", "/reader/eject", Map.of(), otherTerminal), 409, "READER_IN_USE");
          releaseReading.countDown();
          assertEquals(200, status.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode());
          assertEquals(200, eject.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode());
          assertFalse(reader.activity(terminal).connected());
        } finally { releaseReading.countDown(); }
      } finally { reader.lifecycle(cardRepository::lifecycle); }
      client.post("/reader/connect", new Api.Connect(alias, ""));
      var operationStarted = new java.util.concurrent.CountDownLatch(1);
      var releaseOperation = new java.util.concurrent.CountDownLatch(1);
      try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        var operation = pool.submit(() -> reader.use(terminal, card -> {
          operationStarted.countDown();
          try {
            if (!releaseOperation.await(5, java.util.concurrent.TimeUnit.SECONDS))
              throw new AssertionError("reader operation timed out");
          } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
          return null;
        }));
        try {
          assertTrue(operationStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
          assertTrue(SecurityMaterial.JSON.readTree(client.call("GET", "/reader/activity", null, terminal).body())
              .path("operating").asBoolean());
          assertFailure(client.call("POST", "/reader/eject", Map.of(), terminal), 409, "READER_BUSY");
          assertFailure(client.call("POST", "/station/close", Map.of(), terminal), 409, "READER_BUSY");
        } finally { releaseOperation.countDown(); }
        operation.get(5, java.util.concurrent.TimeUnit.SECONDS);
      }
      String adminHistory = "/admin/cards/" + cardId + "/transactions/pages";
      String adminToken = client.token;
      client.token = null;
      assertFailure(client.call("GET", adminHistory, null, terminal), 401, "ADMIN_LOGIN");
      client.token = "invalid";
      assertFailure(client.call("GET", adminHistory, null, terminal), 401, "ADMIN_EXPIRED");
      client.token = adminToken;
      assertEquals(403, client.call("GET", adminHistory, null, null).statusCode());
      Api.CardTransactionListPage emptyAdmin = historyListPage(
          client.call("GET", adminHistory, null, terminal));
      assertEquals(0, emptyAdmin.total());
      assertTrue(emptyAdmin.items().isEmpty());
      assertEquals(400, client.call("GET", adminHistory + "?page=-1", null, terminal).statusCode());
      assertEquals(400, client.call("GET", adminHistory + "?page=abc", null, terminal).statusCode());
      assertEquals(404, client.call("GET", "/admin/cards/TTM999999/transactions/pages", null, terminal)
          .statusCode());
      assertFailure(client.call("GET", HISTORY_PAGES, null, terminal), 401, "PIN_SESSION");
      client.post("/student/login", new Api.Pin("123456"));
      Api.CardTransactionListPage empty = historyListPage(client.call("GET", HISTORY_PAGES, null, terminal));
      assertEquals(0, empty.total());
      assertEquals(0, empty.page());
      assertEquals(4, empty.pageSize());
      assertTrue(empty.items().isEmpty());
      assertEquals(400, client.call("GET", HISTORY_PAGES + "?page=-1", null, terminal).statusCode());
      assertEquals(400, client.call("GET", HISTORY_PAGES + "?page=abc", null, terminal).statusCode());
      seedCardTransactions(context.getBean(JdbcTemplate.class), cardId);
      Api.CardTransactionPage first = historyPage(client.call("GET", HISTORY, null, terminal));
      Api.CardTransactionPage last = historyPage(
          client.call("GET", HISTORY + "?beforeId=" + first.nextBeforeId(), null, terminal));
      Api.CardTransactionListPage firstFour = historyListPage(client.call("GET", HISTORY_PAGES, null, terminal));
      assertEquals(24, firstFour.total());
      assertEquals(first.items().subList(0, 4), firstFour.items());
      Api.CardTransactionListPage secondFour = historyListPage(
          client.call("GET", HISTORY_PAGES + "?page=1", null, terminal));
      assertEquals(first.items().subList(4, 8), secondFour.items());
      assertEquals(firstFour, historyListPage(client.call("GET", adminHistory, null, terminal)));
      assertEquals(secondFour, historyListPage(client.call("GET", adminHistory + "?page=1", null, terminal)));
      Api.CardTransactionListPage lastFour = historyListPage(
          client.call("GET", HISTORY_PAGES + "?page=5", null, terminal));
      assertEquals(last.items(), lastFour.items());
      Api.CardTransactionListPage beyond = historyListPage(
          client.call("GET", HISTORY_PAGES + "?page=100", null, terminal));
      assertEquals(24, beyond.total());
      assertEquals(100, beyond.page());
      assertTrue(beyond.items().isEmpty());
      context.getBean(JdbcTemplate.class).update(
          "INSERT INTO wallet_operations(card_id,kind,amount,status) VALUES (?,'CREDIT',1,'APPLIED')", cardId);
      Api.CardTransactionListPage partial = historyListPage(
          client.call("GET", HISTORY_PAGES + "?page=6", null, terminal));
      assertEquals(25, partial.total());
      assertEquals(1, partial.items().size());
      assertEquals(409, client.call("GET", HISTORY_PAGES, null, UUID.randomUUID().toString()).statusCode());
      client.post("/student/logout", Map.of());
      assertEquals(401, client.call("GET", HISTORY_PAGES, null, terminal).statusCode());
      client.post("/reader/eject", Map.of());
      assertEquals(409, client.call("GET", HISTORY_PAGES, null, terminal).statusCode());
      assertEquals(25, historyListPage(client.call("GET", adminHistory, null, terminal)).total());

      String otherAlias = client.post("/readers/new", Map.of()).path("id").asText();
      client.post("/reader/connect", new Api.Connect(otherAlias, ""));
      Profile otherProfile = new Profile("", "AT210102", "Trần Văn Bình", "012345679998",
          "02/01/2005", "AT21A", "0912345679", "other@example.invalid", 2025,
          profile.photoBase64(), 0);
      String otherId = client.post("/admin/card/init", new Api.Enrollment(otherProfile, "123456"))
          .path("cardId").asText();
      assertNotEquals(cardId, otherId);
      assertEquals(0, historyListPage(client.call("GET", "/admin/cards/" + otherId
          + "/transactions/pages", null, terminal)).total());
      assertEquals(25, historyListPage(client.call("GET", adminHistory, null, terminal)).total());

      String receipts = "/admin/bank-receipts/pages";
      client.token = null;
      assertFailure(client.call("GET", receipts, null, terminal), 401, "ADMIN_LOGIN");
      client.token = adminToken;
      assertEquals(403, client.call("GET", receipts, null, null).statusCode());
      assertEquals(400, client.call("GET", receipts + "?page=-1", null, terminal).statusCode());
      JdbcTemplate db = context.getBean(JdbcTemplate.class);
      for (int n = 0; n < 205; n++) {
        db.update("INSERT INTO bank_events(provider_id,bank_reference,account_token,amount,description,"
                + "result,card_id,created_at,bank_transaction_at) VALUES (?,?,?,10000,'fixture','REVIEW',?,"
                + "TIMESTAMPTZ '2026-01-01 00:00:00+00' + (? * INTERVAL '1 minute'),"
                + "TIMESTAMPTZ '2026-01-01 00:00:00+00' + (? * INTERVAL '1 minute'))",
            "fixture-" + String.format("%03d", n), "BANK-" + String.format("%03d", n),
            "0".repeat(64), n % 2 == 0 ? cardId : otherId, n, 205 - n);
      }
      long archivedCase = Objects.requireNonNull(db.queryForObject(
          "INSERT INTO refund_cases(original_card_id,student_code) VALUES ('TTM999991','CT240101') RETURNING id",
          Long.class));
      long archivedItem = Objects.requireNonNull(db.queryForObject(
          "INSERT INTO refund_items(case_id,reference,requested_amount,received_amount,status)"
              + " VALUES (?,'TOPUP-ARCH',10000,10000,'REFUND_PENDING') RETURNING id", Long.class, archivedCase));
      db.update("INSERT INTO sepay_topup_requests(reference,original_card_id,amount,opened_at,expires_at,bank_memo)"
          + " VALUES ('TOPUP-ARCH','TTM999991',10000,now(),now()+interval '5 minutes','NTCT24010100000001')");
      db.update("INSERT INTO retired_topup_references(reference,original_card_id,student_code,requested_amount,"
          + "received_amount,refund_item_id) VALUES ('TOPUP-ARCH','TTM999991','CT240101',10000,10000,?)", archivedItem);
      db.update("INSERT INTO bank_events(provider_id,bank_reference,account_token,amount,description,result,"
          + "topup_reference,refund_item_id,created_at) VALUES ('fixture-arch','BANK-ARCH',?,10000,'fixture',"
          + "'REVIEW','TOPUP-ARCH',?,TIMESTAMPTZ '2025-01-01 00:00:00+00')", "0".repeat(64), archivedItem);

      Api.BankReceiptPage firstReceipts = receiptPage(client.call("GET", receipts, null, terminal));
      assertEquals(206, firstReceipts.total());
      assertEquals(10, firstReceipts.items().size());
      assertEquals("fixture-000", firstReceipts.items().getFirst().providerId());
      assertEquals("fixture-009", firstReceipts.items().getLast().providerId());
      assertEquals("fixture-010", receiptPage(client.call("GET", receipts + "?page=1", null, terminal))
          .items().getFirst().providerId());
      assertEquals(6, receiptPage(client.call("GET", receipts + "?page=20", null, terminal)).items().size());
      assertEquals(0, receiptPage(client.call("GET", receipts + "?page=100", null, terminal)).items().size());
      assertEquals("fixture-204", receiptPage(client.call("GET", receipts + "?q=BANK-204", null, terminal))
          .items().getFirst().providerId());
      assertEquals(103, receiptPage(client.call("GET", receipts + "?q=" + cardId, null, terminal)).total());
      assertEquals(103, receiptPage(client.call("GET", receipts + "?q=at210101", null, terminal)).total());
      assertEquals(102, receiptPage(client.call("GET", receipts + "?q=" + otherId, null, terminal)).total());
      assertEquals("fixture-arch", receiptPage(client.call("GET", receipts + "?q=TTM999991", null, terminal))
          .items().getFirst().providerId());
      assertEquals("fixture-arch", receiptPage(client.call("GET", receipts + "?q=CT240101", null, terminal))
          .items().getFirst().providerId());
      assertEquals("fixture-arch", receiptPage(client.call("GET", receipts + "?q=NTCT24010100000001", null, terminal))
          .items().getFirst().providerId());
      assertEquals(0, receiptPage(client.call("GET", receipts + "?q=%25", null, terminal)).total());
      assertEquals(0, receiptPage(client.call("GET", receipts + "?q=_", null, terminal)).total());
      String unified = "/admin/transactions";
      client.token = null;
      assertFailure(client.call("GET", unified, null, terminal), 401, "ADMIN_LOGIN");
      client.token = adminToken;
      assertEquals(403, client.call("GET", unified, null, null).statusCode());
      assertEquals(400, client.call("GET", unified + "?page=-1", null, terminal).statusCode());
      Api.AdminTransactionPage firstUnified = adminTransactionPage(client.call("GET", unified, null, terminal));
      assertEquals(206, firstUnified.total());
      assertEquals(10, firstUnified.items().size());
      assertEquals("BANK:fixture-000", firstUnified.items().getFirst().sourceKey());
      assertEquals("UNKNOWN", firstUnified.items().getFirst().kind());
      assertEquals(103, adminTransactionPage(client.call("GET", unified + "?q=" + cardId, null, terminal)).total());
      assertEquals("BANK:fixture-204", adminTransactionPage(
          client.call("GET", unified + "?q=BANK-204", null, terminal)).items().getFirst().sourceKey());
      assertEquals(0, adminTransactionPage(client.call("GET", unified + "?q=%25", null, terminal)).total());
      assertEquals(0, adminTransactionPage(client.call("GET", unified + "?q=_", null, terminal)).total());

      String[] filterTimes = {"2019-12-31 16:59:59.999+00", "2019-12-31 17:00:00+00",
          "2020-01-01 16:59:59.999+00", "2020-01-01 17:00:00+00", "2100-01-01 00:00:00+00"};
      long[] filterAmounts = {10000, 10000, 20000, 20000, 50000};
      for (int n = 0; n < filterTimes.length; n++) {
        db.update("INSERT INTO admin_transaction_journal(source_key,kind,occurred_at,reference,"
                + "card_id,student_code,amount,status) VALUES (?,'UNKNOWN',?::timestamptz,"
                + "'FILTER_CASE','TTM999900','CTFILTER',?,'REVIEW')",
            "FILTER:" + n, filterTimes[n], filterAmounts[n]);
      }
      String filtered = unified + "/filter";
      client.token = null;
      assertFailure(client.call("GET", filtered + "?from=2020-01-01", null, terminal), 401, "ADMIN_LOGIN");
      client.token = adminToken;
      assertEquals(5, adminTransactionPage(client.call("GET", filtered + "?q=FILTER_CASE", null, terminal)).total());
      assertEquals(3, adminTransactionPage(client.call("GET", filtered
          + "?q=FILTER_CASE&from=2020-01-01", null, terminal)).total());
      assertEquals(3, adminTransactionPage(client.call("GET", filtered
          + "?q=FILTER_CASE&to=2020-01-01", null, terminal)).total());
      Api.AdminTransactionPage wholeDay = adminTransactionPage(client.call("GET", filtered
          + "?q=FILTER_CASE&from=2020-01-01&to=2020-01-01", null, terminal));
      assertEquals(2, wholeDay.total());
      assertEquals(List.of("FILTER:2", "FILTER:1"), wholeDay.items().stream()
          .map(Api.AdminTransaction::sourceKey).toList());
      Api.AdminTransactionPage combined = adminTransactionPage(client.call("GET", filtered
          + "?q=TTM999900&from=2020-01-01&to=2020-01-01&amount=20000", null, terminal));
      assertEquals(1, combined.total());
      assertEquals("FILTER:2", combined.items().getFirst().sourceKey());
      assertEquals(0, adminTransactionPage(client.call("GET", filtered
          + "?q=CTFILTER&from=2020-01-01&to=2020-01-01&amount=12345", null, terminal)).total());
      Api.AdminTransactionPage filteredPage = adminTransactionPage(client.call("GET", filtered
          + "?from=2026-01-01&to=2026-01-01&page=20", null, terminal));
      assertEquals(205, filteredPage.total());
      assertEquals(5, filteredPage.items().size());
      assertEquals(400, client.call("GET", filtered + "?from=2020-02-30", null, terminal).statusCode());
      assertEquals(400, client.call("GET", filtered + "?from=0000-01-01", null, terminal).statusCode());
      assertEquals(400, client.call("GET", filtered + "?from=2020-01-02&to=2020-01-01", null, terminal).statusCode());
      assertEquals(400, client.call("GET", filtered + "?amount=-1", null, terminal).statusCode());
      assertEquals(400, client.call("GET", filtered + "?amount=abc", null, terminal).statusCode());

      client.post("/student/login", new Api.Pin("123456"));
      long cancelledTopup = client.post("/topups", new Api.Amount(30000)).path("id").asLong();
      assertTrue(SecurityMaterial.JSON.readTree(client.call("GET", "/reader/activity", null, terminal).body())
          .path("topupReserved").asBoolean());
      assertFailure(client.call("POST", "/reader/eject", Map.of(), terminal), 409, "TOPUP_IN_PROGRESS");
      assertFailure(client.call("POST", "/station/close", Map.of(), terminal), 409, "TOPUP_IN_PROGRESS");
      assertFailure(client.call("POST", "/student/logout", Map.of(), terminal), 409, "TOPUP_IN_PROGRESS");
      assertFailure(client.call("POST", "/student/login", new Api.Pin("123456"), terminal), 409, "TOPUP_IN_PROGRESS");
      assertFailure(client.call("POST", "/reader/reconcile", Map.of(), terminal), 409, "TOPUP_IN_PROGRESS");
      assertEquals("STUDENT", SecurityMaterial.JSON.readTree(
          client.call("GET", "/reader/status", null, terminal).body()).path("role").asText());
      assertFailure(client.call("POST", "/admin/cards/" + otherId + "/block", Map.of(), terminal),
          409, "TOPUP_IN_PROGRESS");
      assertEquals("EXPIRED", client.post("/topups/" + cancelledTopup + "/cancel?includeStatus=true", Map.of())
          .path("status").asText());
      assertFalse(SecurityMaterial.JSON.readTree(client.call("GET", "/reader/activity", null, terminal).body())
          .path("topupReserved").asBoolean());

      WalletService wallet = context.getBean(WalletService.class);
      String cancelledReference = db.queryForObject("SELECT reference FROM topups WHERE id=?", String.class, cancelledTopup);
      wallet.bankEvent(Map.of("id", "fixture-late", "reference", "BANK-FIXTURE-LATE", "amount", 30000,
          "description", cancelledReference, "accountNumber", "000000000000"));
      assertEquals("UNKNOWN", db.queryForObject("SELECT kind FROM admin_transaction_journal"
          + " WHERE source_key='BANK:fixture-late'", String.class));
      assertEquals(0, db.queryForObject("SELECT count(*) FROM wallet_operations WHERE topup_id=?",
          Integer.class, cancelledTopup));

      long wrongTopup = client.post("/topups", new Api.Amount(30000)).path("id").asLong();
      String wrongReference = db.queryForObject("SELECT reference FROM topups WHERE id=?", String.class, wrongTopup);
      wallet.bankEvent(Map.of("id", "fixture-wrong", "reference", "BANK-FIXTURE-WRONG", "amount", 15000,
          "description", wrongReference, "accountNumber", "000000000000"));
      HttpResponse<String> wrongState = client.call("GET", "/topups/" + wrongTopup, null, terminal);
      assertEquals(200, wrongState.statusCode());
      assertEquals("REVIEW", SecurityMaterial.JSON.readTree(wrongState.body()).path("status").asText());
      assertEquals("UNKNOWN", db.queryForObject("SELECT kind FROM admin_transaction_journal"
          + " WHERE source_key='BANK:fixture-wrong'", String.class));
      assertEquals("AMOUNT_MISMATCH", db.queryForObject("SELECT reason FROM admin_transaction_journal"
          + " WHERE source_key='BANK:fixture-wrong'", String.class));
      assertEquals(0, db.queryForObject("SELECT count(*) FROM wallet_operations WHERE topup_id=?",
          Integer.class, wrongTopup));

      wallet.bankEvent(Map.of("id", "fixture-unmatched", "reference", "BANK-FIXTURE-UNMATCHED", "amount", 15000,
          "description", "NO VALID REFERENCE", "accountNumber", "000000000000"));
      assertEquals("INVALID_TRANSFER_CODE", db.queryForObject("SELECT reason FROM admin_transaction_journal"
          + " WHERE source_key='BANK:fixture-unmatched'", String.class));

      long validTopup = client.post("/topups", new Api.Amount(40000)).path("id").asLong();
      String internalReference = db.queryForObject("SELECT reference FROM topups WHERE id=?", String.class, validTopup);
      reader.useTopup(terminal, card -> {
        wallet.bankEvent(Map.of("id", "fixture-topup", "reference", "BANK-FIXTURE-TOPUP", "amount", 40000,
            "description", internalReference, "accountNumber", "000000000000"));
        assertEquals("WAIT_CARD", db.queryForObject("SELECT status FROM admin_transaction_journal"
            + " WHERE source_key='BANK:fixture-topup'", String.class));
        return null;
      });
      client.post("/topups/" + validTopup + "/apply", Map.of());
      assertEquals("CREDITED", db.queryForObject("SELECT status FROM admin_transaction_journal"
          + " WHERE source_key='BANK:fixture-topup'", String.class));
      assertEquals(1, db.queryForObject("SELECT count(*) FROM admin_transaction_journal"
          + " WHERE source_key='BANK:fixture-topup'", Integer.class));
      wallet.bankEvent(Map.of("id", "fixture-topup", "reference", "BANK-FIXTURE-TOPUP", "amount", 40000,
          "description", internalReference, "accountNumber", "000000000000"));
      assertEquals(1, db.queryForObject("SELECT count(*) FROM bank_events WHERE provider_id='fixture-topup'", Integer.class));
      assertFalse(SecurityMaterial.JSON.readTree(client.call("GET", "/reader/activity", null, terminal).body())
          .path("topupReserved").asBoolean());

      long recoverableTopup = client.post("/topups", new Api.Amount(36000)).path("id").asLong();
      String recoverableReference = db.queryForObject("SELECT reference FROM topups WHERE id=?",
          String.class, recoverableTopup);
      reader.useTopup(terminal, card -> {
        wallet.bankEvent(Map.of("id", "fixture-recovery", "reference", "BANK-FIXTURE-RECOVERY",
            "amount", 36000, "description", recoverableReference, "accountNumber", "000000000000"));
        assertEquals("BANK_RECEIVED", wallet.topup(recoverableTopup).status());
        CardService cardService = context.getBean(CardService.class);
        cardService.reconcile(terminal);
        cardService.login(terminal, "123456");
        assertEquals("CREDITED", wallet.apply(terminal, recoverableTopup).status());
        return null;
      });

      long invoiceId = Objects.requireNonNull(db.queryForObject("INSERT INTO invoices(card_id,student_code,total,status,"
          + "payment_method) VALUES (?, ?, 12000, 'OPEN', 'CARD') RETURNING id", Long.class, otherId, "AT210102"));
      long debitId = Objects.requireNonNull(db.queryForObject("INSERT INTO wallet_operations(card_id,kind,amount,invoice_id,status)"
          + " VALUES (?,'DEBIT',12000,?,'APPLIED') RETURNING id", Long.class, otherId, invoiceId));
      db.update("UPDATE invoices SET status='PAID',payment_method='CARD' WHERE id=?", invoiceId);
      assertEquals("CARD_INVOICE", adminTransactionPage(client.call("GET", unified + "?q=HD"
          + String.format("%08d", invoiceId), null, terminal)).items().getFirst().kind());
      db.update("DELETE FROM wallet_operations WHERE id=?", debitId);
      db.update("DELETE FROM invoices WHERE id=?", invoiceId);
      assertEquals(1, db.queryForObject("SELECT count(*) FROM admin_transaction_journal WHERE source_key=?",
          Integer.class, "CARD:" + debitId));
      db.update("INSERT INTO invoice_payment_references(reference,invoice_number,amount,opened_at,expires_at)"
          + " VALUES ('CTHD999999999999','HD-FIXTURE-BANK',12000,now()-interval '1 minute',now()+interval '5 minutes')");
      db.update("INSERT INTO bank_events(provider_id,bank_reference,account_token,amount,description,result,invoice_reference)"
          + " VALUES ('fixture-bank-invoice','BANK-FIXTURE-INVOICE',?,12000,'fixture','MATCHED_INVOICE','CTHD999999999999')",
          "0".repeat(64));
      assertEquals("BANK_INVOICE", db.queryForObject("SELECT kind FROM admin_transaction_journal"
          + " WHERE source_key='BANK:fixture-bank-invoice'", String.class));

      long raceTopup = client.post("/topups", new Api.Amount(25000)).path("id").asLong();
      String raceReference = db.queryForObject("SELECT reference FROM topups WHERE id=?", String.class, raceTopup);
      var raceStart = new java.util.concurrent.CountDownLatch(1);
      try (var racePool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var cancel = racePool.submit(() -> {
          raceStart.await();
          return client.call("POST", "/topups/" + raceTopup + "/cancel?includeStatus=true", Map.of(), terminal);
        });
        var incoming = racePool.submit(() -> {
          raceStart.await();
          wallet.bankEvent(Map.of("id", "fixture-race", "reference", "BANK-FIXTURE-RACE", "amount", 25000,
              "description", raceReference, "accountNumber", "000000000000"));
          return null;
        });
        raceStart.countDown();
        HttpResponse<String> cancellation = cancel.get(10, java.util.concurrent.TimeUnit.SECONDS);
        incoming.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(200, cancellation.statusCode(), cancellation.body());
        String cancellationStatus = SecurityMaterial.JSON.readTree(cancellation.body()).path("status").asText();
        String kind = db.queryForObject("SELECT kind FROM admin_transaction_journal"
            + " WHERE source_key='BANK:fixture-race'", String.class);
        if ("EXPIRED".equals(cancellationStatus)) {
          assertEquals("UNKNOWN", kind);
          assertEquals(0, db.queryForObject("SELECT count(*) FROM wallet_operations WHERE topup_id=? AND status='APPLIED'",
              Integer.class, raceTopup));
        } else {
          assertTrue(Set.of("BANK_RECEIVED", "CREDITED").contains(cancellationStatus));
          assertEquals("TOPUP", kind);
          client.post("/topups/" + raceTopup + "/apply", Map.of());
          assertEquals(1, db.queryForObject("SELECT count(*) FROM wallet_operations WHERE topup_id=? AND status='APPLIED'",
              Integer.class, raceTopup));
        }
      }
      assertEquals(1, db.queryForObject("SELECT count(*) FROM admin_transaction_journal"
          + " WHERE source_key='BANK:fixture-race'", Integer.class));

      // Exercise the actual SePay authentication, full memo and matching path with
      // synthetic notices against this test's private PostgreSQL schema.
      LocalConfig sepayConfig = new LocalConfig() {
        @Override public String get(String key, String fallback) {
          return switch (key) {
            case "bank.provider" -> "sepay";
            case "bank.account" -> "000000000000";
            case "bank.code" -> "BIDV";
            case "sepay.webhook.api.key" -> SePayGatewayTest.KEY;
            case "bank.main.account" -> "";
            default -> super.get(key, fallback);
          };
        }
      };
      SePayTopups sepay = new SePayTopups(context.getBean(CardRepository.class),
          context.getBean(SecurityMaterial.class), new SePayGateway(sepayConfig), wallet.invoiceBank);
      String sepayAuth = "Apikey " + SePayGatewayTest.KEY;
      long sepayTopup = client.post("/topups", new Api.Amount(32000)).path("id").asLong();
      sepay.register(sepayTopup, otherId, "AT210102");
      String sepayMemo = SePayGateway.paymentCode("AT210102", sepayTopup);
      Map<String, Object> goodNotice = SePayGatewayTest.payload(910001, sepayMemo, 32000);
      goodNotice.put("subAccount", "000000000000");
      String goodBody = SecurityMaterial.JSON.writeValueAsString(goodNotice);
      assertEquals("SEPAY_AUTH", assertThrows(Failure.class,
          () -> sepay.receive(goodBody, "Apikey wrong")).code);
      assertEquals(0, db.queryForObject("SELECT count(*) FROM bank_events WHERE provider_id='SEPAY:910001'",
          Integer.class));
      reader.useTopup(terminal, card -> {
        sepay.receive(goodBody, sepayAuth);
        assertEquals("WAIT_CARD", db.queryForObject("SELECT status FROM admin_transaction_journal"
            + " WHERE source_key='BANK:SEPAY:910001'", String.class));
        return null;
      });
      client.post("/topups/" + sepayTopup + "/apply", Map.of());
      sepay.receive(goodBody, sepayAuth);
      assertEquals("CREDITED", db.queryForObject("SELECT status FROM admin_transaction_journal"
          + " WHERE source_key='BANK:SEPAY:910001'", String.class));
      assertEquals(1, db.queryForObject("SELECT count(*) FROM wallet_operations WHERE topup_id=? AND status='APPLIED'",
          Integer.class, sepayTopup));
      assertEquals(1, db.queryForObject("SELECT count(*) FROM admin_transaction_journal"
          + " WHERE source_key='BANK:SEPAY:910001'", Integer.class));
      Map<String, Object> secondTopupNotice = SePayGatewayTest.payload(910006, sepayMemo, 32000);
      secondTopupNotice.put("subAccount", "000000000000");
      sepay.receive(SecurityMaterial.JSON.writeValueAsString(secondTopupNotice), sepayAuth);
      assertEquals("UNKNOWN", db.queryForObject("SELECT kind FROM admin_transaction_journal"
          + " WHERE source_key='BANK:SEPAY:910006'", String.class));
      assertEquals("REQUEST_CLOSED", db.queryForObject("SELECT reason FROM admin_transaction_journal"
          + " WHERE source_key='BANK:SEPAY:910006'", String.class));
      assertEquals(1, db.queryForObject("SELECT count(*) FROM wallet_operations WHERE topup_id=? AND status='APPLIED'",
          Integer.class, sepayTopup));

      long mismatchedTopup = client.post("/topups", new Api.Amount(35000)).path("id").asLong();
      sepay.register(mismatchedTopup, otherId, "AT210102");
      Map<String, Object> wrongNotice = SePayGatewayTest.payload(910002,
          SePayGateway.paymentCode("AT210102", mismatchedTopup), 15000);
      wrongNotice.put("subAccount", "000000000000");
      sepay.receive(SecurityMaterial.JSON.writeValueAsString(wrongNotice), sepayAuth);
      assertEquals("REVIEW", SecurityMaterial.JSON.readTree(
          client.call("GET", "/topups/" + mismatchedTopup, null, terminal).body()).path("status").asText());
      assertEquals("UNKNOWN", db.queryForObject("SELECT kind FROM admin_transaction_journal"
          + " WHERE source_key='BANK:SEPAY:910002'", String.class));
      assertEquals("AMOUNT_MISMATCH", db.queryForObject("SELECT reason FROM admin_transaction_journal"
          + " WHERE source_key='BANK:SEPAY:910002'", String.class));
      assertEquals(0, db.queryForObject("SELECT count(*) FROM wallet_operations WHERE topup_id=?",
          Integer.class, mismatchedTopup));

      long expiredTopup = client.post("/topups", new Api.Amount(37000)).path("id").asLong();
      sepay.register(expiredTopup, otherId, "AT210102");
      db.update("UPDATE topups SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?", expiredTopup);
      assertEquals("EXPIRED", SecurityMaterial.JSON.readTree(
          client.call("GET", "/topups/" + expiredTopup, null, terminal).body()).path("status").asText());
      assertFalse(SecurityMaterial.JSON.readTree(client.call("GET", "/reader/activity", null, terminal).body())
          .path("topupReserved").asBoolean());
      Map<String, Object> expiredNotice = SePayGatewayTest.payload(910003,
          SePayGateway.paymentCode("AT210102", expiredTopup), 37000);
      expiredNotice.put("subAccount", "000000000000");
      sepay.receive(SecurityMaterial.JSON.writeValueAsString(expiredNotice), sepayAuth);
      assertEquals("UNKNOWN", db.queryForObject("SELECT kind FROM admin_transaction_journal"
          + " WHERE source_key='BANK:SEPAY:910003'", String.class));
      assertEquals(0, db.queryForObject("SELECT count(*) FROM wallet_operations WHERE topup_id=?",
          Integer.class, expiredTopup));

      String checkoutTerminal = UUID.randomUUID().toString();
      wallet.openStation(new Api.OpenStation(UUID.randomUUID().toString(), checkoutTerminal));
      long sepayInvoice = Objects.requireNonNull(db.queryForObject("INSERT INTO invoices(card_id,student_code,total,status,"
          + "station_session_id,expires_at) VALUES (?, ?, 23000, 'OPEN', ?, clock_timestamp()+interval '5 minutes') RETURNING id",
          Long.class, otherId, "AT210102", UUID.fromString(checkoutTerminal)));
      wallet.invoiceBank.register(sepayInvoice);
      Map<String, Object> invoiceNotice = SePayGatewayTest.payload(910004,
          SePayGateway.invoiceCode("AT210102", sepayInvoice), 23000);
      invoiceNotice.put("subAccount", "000000000000");
      sepay.receive(SecurityMaterial.JSON.writeValueAsString(invoiceNotice), sepayAuth);
      assertEquals("PAID", db.queryForObject("SELECT status FROM invoices WHERE id=?", String.class, sepayInvoice));
      assertEquals("BANK_INVOICE", db.queryForObject("SELECT kind FROM admin_transaction_journal"
          + " WHERE source_key='BANK:SEPAY:910004'", String.class));
      Map<String, Object> secondInvoiceNotice = SePayGatewayTest.payload(910005,
          SePayGateway.invoiceCode("AT210102", sepayInvoice), 23000);
      secondInvoiceNotice.put("subAccount", "000000000000");
      sepay.receive(SecurityMaterial.JSON.writeValueAsString(secondInvoiceNotice), sepayAuth);
      assertEquals("UNKNOWN", db.queryForObject("SELECT kind FROM admin_transaction_journal"
          + " WHERE source_key='BANK:SEPAY:910005'", String.class));
      assertEquals("ALREADY_PAID", db.queryForObject("SELECT reason FROM admin_transaction_journal"
          + " WHERE source_key='BANK:SEPAY:910005'", String.class));

      client.post("/admin/cards/" + cardId + "/block", Map.of());
      assertEquals(25, historyListPage(client.call("GET", adminHistory, null, terminal)).total());
      client.post("/student/logout", Map.of());
      HttpResponse<String> disconnected = null;
      for (int attempt = 0; attempt < 10; attempt++) {
        disconnected = client.call("POST", "/reader/eject", Map.of(), terminal);
        if (disconnected.statusCode() == 200) break;
        assertFailure(disconnected, 409, "READER_BUSY");
        Thread.sleep(50);
      }
      assertNotNull(disconnected);
      assertEquals(200, disconnected.statusCode());
      assertEquals(200, client.call("POST", "/reader/connect", new Api.Connect(otherAlias, ""), otherTerminal)
          .statusCode());
      assertTrue(SecurityMaterial.JSON.readTree(client.call("GET", "/reader/activity", null, otherTerminal).body())
          .path("ownedByCaller").asBoolean());
      assertEquals(200, client.call("POST", "/station/close", Map.of(), otherTerminal).statusCode());
      assertFalse(SecurityMaterial.JSON.readTree(client.call("GET", "/reader/activity", null, otherTerminal).body())
          .path("connected").asBoolean());
      var connectStart = new java.util.concurrent.CountDownLatch(1);
      try (var connectPool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var firstConnect = connectPool.submit(() -> {
          connectStart.await();
          return client.call("POST", "/reader/connect", new Api.Connect(alias, ""), terminal);
        });
        var secondConnect = connectPool.submit(() -> {
          connectStart.await();
          return client.call("POST", "/reader/connect", new Api.Connect(otherAlias, ""), otherTerminal);
        });
        connectStart.countDown();
        HttpResponse<String> firstResult = firstConnect.get(10, java.util.concurrent.TimeUnit.SECONDS);
        HttpResponse<String> secondResult = secondConnect.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(200, firstResult.statusCode(), firstResult.body());
        assertEquals(200, secondResult.statusCode(), secondResult.body());
        assertTrue(SecurityMaterial.JSON.readTree(client.call("GET", "/reader/activity", null, terminal).body())
            .path("ownedByCaller").asBoolean());
        assertTrue(SecurityMaterial.JSON.readTree(client.call("GET", "/reader/activity", null, otherTerminal).body())
            .path("ownedByCaller").asBoolean());
        assertTrue(SecurityMaterial.JSON.readTree(client.call("GET", "/reader/activity?selected=" + otherAlias,
            null, terminal).body()).path("selectedOwnedByOther").asBoolean());
        assertTrue(SecurityMaterial.JSON.readTree(client.call("GET", "/reader/activity?selected=" + alias,
            null, otherTerminal).body()).path("selectedOwnedByOther").asBoolean());
      }
    } finally {
      System.setProperty("ttm.config", source.toAbsolutePath().toString());
      LocalConfig cleanup = new LocalConfig();
      try (var ds = (com.zaxxer.hikari.HikariDataSource) cleanup.dataSource()) {
        new JdbcTemplate(ds).execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }

  private static void assertFailure(HttpResponse<String> response, int status, String code) throws Exception {
    assertEquals(status, response.statusCode(), response.body());
    assertEquals(code, SecurityMaterial.JSON.readTree(response.body()).path("code").asText());
  }

  private static Api.BankReceiptPage receiptPage(HttpResponse<String> response) throws Exception {
    assertEquals(200, response.statusCode(), response.body());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    return SecurityMaterial.JSON.readValue(response.body(), Api.BankReceiptPage.class);
  }

  private static Api.AdminTransactionPage adminTransactionPage(HttpResponse<String> response) throws Exception {
    assertEquals(200, response.statusCode(), response.body());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    return SecurityMaterial.JSON.readValue(response.body(), Api.AdminTransactionPage.class);
  }

  private static boolean setupNeeded(HttpResponse<String> response) throws Exception {
    assertEquals(200, response.statusCode(), response.body());
    return SecurityMaterial.JSON.readTree(response.body()).required("needed").booleanValue();
  }

  private static Api.CardTransactionPage historyPage(HttpResponse<String> response) throws Exception {
    assertEquals(200, response.statusCode(), response.body());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    return SecurityMaterial.JSON.readValue(response.body(), Api.CardTransactionPage.class);
  }

  private static Api.CardTransactionListPage historyListPage(HttpResponse<String> response) throws Exception {
    assertEquals(200, response.statusCode(), response.body());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    return SecurityMaterial.JSON.readValue(response.body(), Api.CardTransactionListPage.class);
  }

  private static String seedCardTransactions(JdbcTemplate db, String cardId) {
    db.update("""
        INSERT INTO wallet_operations(card_id,kind,amount,status)
        SELECT ?, 'CREDIT', n, 'APPLIED' FROM generate_series(1,21) AS n
        """, cardId);
    String internalReference = "TTMNAP N00000001";
    long topup = db.queryForObject("""
        INSERT INTO topups(card_id,reference,amount,status,expires_at)
        VALUES (?,?,100000,'CREDITED',now()) RETURNING id
        """, Long.class, cardId, internalReference);
    db.update("""
        INSERT INTO sepay_topup_requests(reference,original_card_id,amount,expires_at,bank_memo)
        VALUES (?,?,100000,now()+interval '5 minutes',?)
        """, internalReference, cardId, TOPUP_REFERENCE);
    db.update("""
        INSERT INTO wallet_operations(card_id,kind,amount,topup_id,status)
        VALUES (?,'CREDIT',100000,?,'APPLIED')
        """, cardId, topup);
    long invoice = db.queryForObject("""
        INSERT INTO invoices(card_id,total,status,outcome,payment_phase)
        VALUES (?,35000,'CANCELLED','CANCEL','FINISHED') RETURNING id
        """, Long.class, cardId);
    long debit = db.queryForObject("""
        INSERT INTO wallet_operations(card_id,kind,amount,invoice_id,status)
        VALUES (?,'DEBIT',35000,?,'APPLIED') RETURNING id
        """, Long.class, cardId, invoice);
    db.update("""
        INSERT INTO wallet_operations(card_id,kind,amount,invoice_id,parent_id,status)
        VALUES (?,'REVERSAL',35000,?,?,'APPLIED')
        """, cardId, invoice, debit);
    db.update("""
        INSERT INTO wallet_operations(card_id,kind,amount,status)
        VALUES (?,'CREDIT',25000,'PENDING'), (?,'DEBIT',25000,'FAILED')
        """, cardId, cardId);
    db.update("""
        INSERT INTO invoices(card_id,total,status,outcome,payment_method,payment_phase)
        VALUES (?,40000,'PAID','SUCCESS','BANK_TRANSFER','FINISHED')
        """, cardId);
    db.update("""
        INSERT INTO cards(card_id,public_key,status)
        VALUES ('TTM999999','other-fixture-public-key','ACTIVE')
        """);
    db.update("""
        INSERT INTO wallet_operations(card_id,kind,amount,status)
        VALUES ('TTM999999','CREDIT',200000,'APPLIED')
        """);
    return String.format(Locale.ROOT, "HD%08d", invoice);
  }
}
