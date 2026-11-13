package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.smartcard.shared.Api;

class InvoiceHistoryHttpTest {
  @TempDir Path work;

  @Test
  void historyUsesDeviceAndActiveStationAndFiltersAllInvoicesInTenRowPages() throws Exception {
    Path config = Path.of(System.getProperty("ttm.test.config",
        Path.of("../.local/test/server.properties").toAbsolutePath().toString()));
    Properties props = new Properties();
    try (var in = Files.newInputStream(config)) { props.load(in); }
    assertTrue(props.getProperty("db.url").matches("jdbc:postgresql://127\\.0\\.0\\.1:5548/ttm_test"
        + "(?:\\?currentSchema=ttm_it_[a-z0-9]{8,32})?"));
    String baseUrl = "jdbc:postgresql://127.0.0.1:5548/ttm_test";
    String schema = "ttm_invoice_history_" + UUID.randomUUID().toString().replace("-", "");
    assertTrue(schema.matches("ttm_invoice_history_[0-9a-f]{32}"));
    String previous = System.getProperty("ttm.config");
    try (HikariDataSource rootSource = source(baseUrl, props)) {
      JdbcTemplate root = new JdbcTemplate(rootSource);
      root.execute("CREATE SCHEMA " + schema);
      try {
        props.setProperty("db.url", baseUrl + "?currentSchema=" + schema);
        Path isolated = work.resolve("server.properties");
        try (var out = Files.newOutputStream(isolated)) { props.store(out, "isolated test"); }
        System.setProperty("ttm.config", isolated.toString());
        try (ConfigurableApplicationContext context = new SpringApplication(BackendApplication.class)
            .run("--server.port=0", "--logging.level.root=ERROR")) {
          JdbcTemplate db = context.getBean(JdbcTemplate.class);
          String[] states = {"OPEN", "PAYMENT_PENDING", "CANCEL_PENDING", "PAID", "CANCELLED"};
          Instant[] boundaries = {Instant.parse("2026-11-09T16:59:59Z"),
              Instant.parse("2026-11-09T17:00:00Z"), Instant.parse("2026-11-10T16:59:59Z"),
              Instant.parse("2026-11-10T17:00:00Z")};
          for (int i = 0; i < 13; i++) {
            String state = states[i % states.length];
            Instant created = i < boundaries.length ? boundaries[i] : Instant.parse("2026-11-11T03:00:00Z");
            long total = 10_000 + i * 1000L;
            Long id = db.queryForObject("INSERT INTO invoices(total,status,created_at,expires_at,"
                    + "payment_method,payment_phase) VALUES(?,?,?,clock_timestamp()+interval '1 year',?,?) RETURNING id",
                Long.class, total, state, Timestamp.from(created),
                "PAID".equals(state) ? "BANK_TRANSFER" : null,
                "PAID".equals(state) ? "FINISHED" : "NONE");
            db.update("INSERT INTO invoice_payment_references(reference,invoice_id,invoice_number,amount,"
                    + "opened_at,expires_at,payment_code) VALUES(?,?,?,?,?,?,?)",
                "CTHD" + String.format("%012d", id), id, String.format("HD%08d", id), total,
                Timestamp.from(created), Timestamp.from(created.plusSeconds(300)),
                "TTAT" + String.format("%014d", id));
          }
          db.update("DELETE FROM invoice_payment_references WHERE invoice_id=?", 1L);
          String base = "http://127.0.0.1:"
              + context.getEnvironment().getProperty("local.server.port") + "/api";
          HttpClient http = HttpClient.newHttpClient();
          String terminal = UUID.randomUUID().toString();
          assertEquals(403, call(http, base, "/invoices/pages", null, terminal, null).statusCode());
          assertEquals(400, call(http, base, "/invoices/pages", props, "not-a-uuid", null).statusCode());
          assertEquals(409, call(http, base, "/invoices/pages", props, terminal, null).statusCode());
          var session = new Api.OpenStation(UUID.randomUUID().toString(), terminal);
          assertEquals(200, call(http, base, "/station/open", props, terminal, session).statusCode());
          db.update("UPDATE station_sessions SET lease_until=clock_timestamp()+interval '10 minutes'"
              + " WHERE session_id=?::uuid", terminal);

          var firstResponse = call(http, base, "/invoices/pages?page=0", props, terminal, null);
          assertEquals(200, firstResponse.statusCode(), firstResponse.body());
          assertTrue(firstResponse.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
          Api.InvoicePage first = SecurityMaterial.JSON.readValue(firstResponse.body(), Api.InvoicePage.class);
          assertEquals(13, first.total());
          assertEquals(10, first.pageSize());
          assertEquals(10, first.items().size());
          assertEquals(13, first.items().get(0).id());
          assertEquals(5, first.items().get(8).id());
          assertEquals(4, first.items().get(9).id());
          assertTrue(first.items().stream().anyMatch(row -> "PAID".equals(row.status())));
          assertTrue(first.items().stream().anyMatch(row -> "CANCELLED".equals(row.status())));
          assertFalse(SecurityMaterial.JSON.readTree(firstResponse.body()).path("items").get(0).has("qrDataUrl"));

          Api.InvoicePage second = page(http, base, "/invoices/pages?page=1", props, terminal);
          assertEquals(3, second.items().size());
          assertEquals(List.of(3L, 2L, 1L), second.items().stream().map(Api.InvoiceSummary::id).toList());
          assertEquals(13, page(http, base, "/invoices/pages?page=2", props, terminal).total());
          assertTrue(page(http, base, "/invoices/pages?page=2", props, terminal).items().isEmpty());
          assertEquals(3, page(http, base, "/invoices/pages?q=hd00000003", props, terminal)
              .items().getFirst().id());
          assertEquals(3, page(http, base, "/invoices/pages?q=ttat00000000000003", props, terminal)
              .items().getFirst().id());
          assertEquals("", page(http, base, "/invoices/pages?q=HD00000001", props, terminal)
              .items().getFirst().paymentReference());
          assertEquals(0, page(http, base, "/invoices/pages?q=%25", props, terminal).total());
          assertEquals(0, page(http, base, "/invoices/pages?q=_", props, terminal).total());
          Api.InvoicePage day = page(http, base, "/invoices/pages?from=2026-11-10&to=2026-11-10",
              props, terminal);
          assertEquals(List.of(3L, 2L), day.items().stream().map(Api.InvoiceSummary::id).toList());
          assertEquals(12, page(http, base, "/invoices/pages?from=2026-11-10", props, terminal).total());
          assertEquals(3, page(http, base, "/invoices/pages?to=2026-11-10", props, terminal).total());
          Api.InvoicePage combined = page(http, base,
              "/invoices/pages?q=ttat00000000000003&from=2026-11-10&to=2026-11-10&amount=12000",
              props, terminal);
          assertEquals(1, combined.total());
          assertEquals("CANCEL_PENDING", combined.items().getFirst().status());
          assertEquals(0, page(http, base, "/invoices/pages?amount=12001", props, terminal).total());

          for (String invalid : List.of("page=-1", "amount=-1", "from=2026-02-30",
              "from=2026-11-11&to=2026-11-10", "q=" + "a".repeat(151)))
            assertEquals(400, call(http, base, "/invoices/pages?" + invalid, props, terminal, null).statusCode());
          var legacy = call(http, base, "/invoices", props, terminal, null);
          assertEquals(200, legacy.statusCode());
          JsonNode legacyRows = SecurityMaterial.JSON.readTree(legacy.body());
          assertTrue(legacyRows.isArray());
          assertEquals(13, legacyRows.size());
        }
      } finally {
        root.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    } finally {
      if (previous == null) System.clearProperty("ttm.config");
      else System.setProperty("ttm.config", previous);
    }
  }

  private static Api.InvoicePage page(HttpClient http, String base, String path, Properties props,
      String terminal) throws Exception {
    var response = call(http, base, path, props, terminal, null);
    assertEquals(200, response.statusCode(), response.body());
    return SecurityMaterial.JSON.readValue(response.body(), Api.InvoicePage.class);
  }

  private static HttpResponse<String> call(HttpClient http, String base, String path, Properties props,
      String terminal, Object body) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(base + path));
    if (props != null) request.header("X-Device-Key", props.getProperty("device.key"))
        .header("X-Terminal-ID", terminal);
    if (body == null) request.GET();
    else request.header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(SecurityMaterial.JSON.writeValueAsString(body)));
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HikariDataSource source(String url, Properties props) {
    HikariDataSource source = new HikariDataSource();
    source.setJdbcUrl(url);
    source.setUsername(props.getProperty("db.user"));
    source.setPassword(props.getProperty("db.password"));
    source.setConnectionTimeout(5000);
    return source;
  }
}
