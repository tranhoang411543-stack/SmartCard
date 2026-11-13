package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import vn.edu.smartcard.shared.Api;

class CatalogHttpFlowTest extends DatabaseFixture {
  @Test
  void cashierCatalogAndAnonymousInvoiceUseRealHttpWithoutAdmin() throws Exception {
    enroll("AT670101", "897000000001", 2026);
    String alias = hub.status(TERMINAL).alias();
    hub.eject(TERMINAL);
    Path file = config.root().resolve("catalog-http.properties");
    Files.writeString(
        file,
        Files.readString(config.root().resolve("server.properties"))
            + "\nbank.provider=casso\nbank.account=\n");
    System.setProperty("ttm.config", file.toAbsolutePath().toString());
    try (var app =
        SpringApplication.run(
            BackendApplication.class, "--server.port=0", "--logging.level.root=ERROR")) {
      String base =
          "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port") + "/api";
      String terminal = UUID.randomUUID().toString();
      var http = HttpClient.newHttpClient();
      class Client {
        HttpResponse<String> call(String method, String path, Object input, boolean device)
            throws Exception {
          var b = HttpRequest.newBuilder(URI.create(base + path));
          if (device)
            b.header("X-Device-Key", config.required("device.key"))
                .header("X-Terminal-ID", terminal);
          if (method.equals("GET")) b.GET();
          else
            b.header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        SecurityMaterial.JSON.writeValueAsString(input)));
          return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }

        com.fasterxml.jackson.databind.JsonNode post(String path, Object input) throws Exception {
          var response = call("POST", path, input, true);
          assertEquals(200, response.statusCode(), response.body());
          return SecurityMaterial.JSON.readTree(response.body());
        }
      }
      var c = new Client();
      var create = new Api.CategoryInput("HTTP " + UUID.randomUUID(), UUID.randomUUID().toString());
      assertEquals(403, c.call("POST", "/menu/categories", create, false).statusCode());
      assertEquals(409, c.call("POST", "/menu/categories", create, true).statusCode());
      c.post("/station/open", new Api.OpenStation(UUID.randomUUID().toString(), terminal));
      assertEquals(403, c.call("POST", "/checkout/reader/prepare", Map.of(), false).statusCode());
      c.post("/reader/connect", new Api.Connect(alias, ""));
      int pinAttempts = repo.db.queryForObject("SELECT count(*) FROM pin_attempts", Integer.class);
      assertTrue(c.post("/checkout/reader/prepare", Map.of()).path("present").asBoolean());
      assertEquals(pinAttempts, repo.db.queryForObject("SELECT count(*) FROM pin_attempts", Integer.class));
      long category = c.post("/menu/categories", create).path("id").asLong();
      assertEquals(
          400,
          c.call(
                  "POST",
                  "/menu/items",
                  new Api.MenuInput(
                      category, "Sai ảnh", 5000, "YWJj", 0, UUID.randomUUID().toString()),
                  true)
              .statusCode());
      var bytes = new ByteArrayOutputStream();
      ImageIO.write(new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB), "png", bytes);
      long item =
          c.post(
                  "/menu/items",
                  new Api.MenuInput(
                      category,
                      "Món HTTP",
                      5000,
                      Base64.getEncoder().encodeToString(bytes.toByteArray()),
                      0,
                      UUID.randomUUID().toString()))
              .path("id")
              .asLong();
      var list =
          SecurityMaterial.JSON.readTree(
              c.call("GET", "/menu?categoryId=" + category, null, true).body());
      assertEquals(1, list.size());
      assertFalse(
          SecurityMaterial.JSON
              .readTree(c.call("GET", "/menu/items/" + item + "/image", null, true).body())
              .path("base64")
              .asText()
              .isBlank());
      var invoice =
          c.post(
              "/invoices",
              new Api.CreateInvoice(List.of(new Api.OrderItem(item, 2))));
      assertEquals("", invoice.path("studentCode").asText());
      assertEquals("", invoice.path("cardId").asText());
      assertTrue(invoice.path("paymentReference").asText().matches("TTAT000000[0-9]{8}"));
      var preview =
          SecurityMaterial.JSON.readTree(
              c.call("GET", "/menu/categories/" + category + "/delete-preview", null, true).body());
      assertEquals(1, preview.path("itemCount").asInt());
      c.post(
          "/menu/categories/" + category + "/delete",
          new Api.MenuDelete(preview.path("revision").asLong()));
      var read =
          SecurityMaterial.JSON.readTree(
              c.call("GET", "/invoices/" + invoice.path("id").asLong(), null, true).body());
      assertEquals("Món HTTP", read.path("items").get(0).path("name").asText());
      assertEquals(10000, read.path("total").asLong());
      assertEquals(401, c.call("GET", "/admin/invoice-bank-receipts", null, true).statusCode());
      c.post("/station/close", Map.of());
      assertEquals(
          409,
          c.call(
                  "POST",
                  "/menu/categories",
                  new Api.CategoryInput("Closed", UUID.randomUUID().toString()),
                  true)
              .statusCode());
    }
  }
}
