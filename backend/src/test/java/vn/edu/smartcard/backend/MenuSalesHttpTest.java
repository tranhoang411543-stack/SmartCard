package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.smartcard.shared.Api;

class MenuSalesHttpTest {
  @TempDir Path work;

  @Test
  void salesEndpointUsesDeviceProtectionAndLeavesExistingMenuContractCompatible() throws Exception {
    Path config = Path.of(System.getProperty("ttm.test.config",
        Path.of("../.local/test/server.properties").toAbsolutePath().toString()));
    Properties props = new Properties();
    try (var in = Files.newInputStream(config)) { props.load(in); }
    assertTrue(props.getProperty("db.url").matches("jdbc:postgresql://127\\.0\\.0\\.1:5548/ttm_test"
        + "(?:\\?currentSchema=ttm_it_[a-z0-9]{8,32})?"));
    String baseUrl = "jdbc:postgresql://127.0.0.1:5548/ttm_test";
    String schema = "ttm_sales_http_" + UUID.randomUUID().toString().replace("-", "");
    assertTrue(schema.matches("ttm_sales_http_[0-9a-f]{32}"));
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
          long item = db.queryForObject("SELECT min(id) FROM menu_items", Long.class);
          db.update("UPDATE menu_items SET sold_quantity=17 WHERE id=?", item);
          String httpBase = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port") + "/api";
          HttpClient http = HttpClient.newHttpClient();
          HttpRequest anonymous = HttpRequest.newBuilder(URI.create(httpBase + "/menu/sales")).GET().build();
          assertEquals(403, http.send(anonymous, HttpResponse.BodyHandlers.ofString()).statusCode());
          HttpRequest salesRequest = HttpRequest.newBuilder(URI.create(httpBase + "/menu/sales"))
              .header("X-Device-Key", props.getProperty("device.key"))
              .header("X-Terminal-ID", UUID.randomUUID().toString()).GET().build();
          var response = http.send(salesRequest, HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode());
          JsonNode counts = SecurityMaterial.JSON.readTree(response.body());
          assertTrue(counts.isArray());
          assertEquals(item, counts.get(0).path("itemId").asLong());
          assertEquals(17, counts.get(0).path("soldQuantity").asLong());
          HttpRequest oldMenu = HttpRequest.newBuilder(URI.create(httpBase + "/menu"))
              .header("X-Device-Key", props.getProperty("device.key"))
              .header("X-Terminal-ID", UUID.randomUUID().toString()).GET().build();
          var oldResponse = http.send(oldMenu, HttpResponse.BodyHandlers.ofString());
          assertEquals(200, oldResponse.statusCode());
          assertTrue(SecurityMaterial.JSON.readValue(oldResponse.body(), Api.MenuItem[].class).length > 0);
          assertFalse(SecurityMaterial.JSON.readTree(oldResponse.body()).get(0).has("soldQuantity"));
        }
      } finally {
        root.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    } finally {
      if (previous == null) System.clearProperty("ttm.config");
      else System.setProperty("ttm.config", previous);
    }
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
