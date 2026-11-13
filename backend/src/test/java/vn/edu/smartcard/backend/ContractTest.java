package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import vn.edu.smartcard.shared.*;

class ContractTest {
  @Test
  void bankQrCropKeepsTheSamePayloadAndRemovesTheDecoration() throws Exception {
    String url = InvoiceQr.image(51, 35000, 1800000000000L);
    var code = javax.imageio.ImageIO.read(new ByteArrayInputStream(
        Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1))));
    var decorated = new BufferedImage(460, 520, BufferedImage.TYPE_INT_ARGB);
    var graphics = decorated.createGraphics();
    try {
      graphics.setColor(Color.BLUE);
      graphics.fillRect(120, 18, 220, 30);
      graphics.drawImage(code, 50, 62, null);
      graphics.setColor(Color.RED);
      graphics.fillRect(90, 448, 280, 28);
    } finally {
      graphics.dispose();
    }
    var output = new ByteArrayOutputStream();
    javax.imageio.ImageIO.write(decorated, "png", output);
    String qrOnly = BankImage.qrOnly("data:image/png;base64,"
        + Base64.getEncoder().encodeToString(output.toByteArray()));
    var cropped = javax.imageio.ImageIO.read(new ByteArrayInputStream(
        Base64.getDecoder().decode(qrOnly.substring(qrOnly.indexOf(',') + 1))));
    int width = cropped.getWidth(), height = cropped.getHeight();
    assertEquals(width, height);
    assertTrue(width <= code.getWidth());
    int[] pixels = cropped.getRGB(0, 0, width, height, null, 0, width);
    assertTrue(Arrays.stream(pixels).allMatch(pixel -> pixel == Color.WHITE.getRGB()
        || pixel == Color.BLACK.getRGB()));
    var source = new com.google.zxing.RGBLuminanceSource(width, height, pixels);
    var result = new com.google.zxing.qrcode.QRCodeReader().decode(new com.google.zxing.BinaryBitmap(
        new com.google.zxing.common.HybridBinarizer(source)));
    assertEquals(InvoiceQr.payload(51, 35000, 1800000000000L), result.getText());
  }

  @Test
  void invoiceQrRoundTripsWithoutCallingBank() throws Exception {
    String url = InvoiceQr.image(51, 35000, 1800000000000L);
    byte[] png = Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1));
    var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
    int[] pixels =
        image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    var source =
        new com.google.zxing.RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels);
    var result =
        new com.google.zxing.MultiFormatReader()
            .decode(
                new com.google.zxing.BinaryBitmap(
                    new com.google.zxing.common.HybridBinarizer(source)));
    assertEquals(InvoiceQr.payload(51, 35000, 1800000000000L), result.getText());
    Path out = Path.of("target/test-work/invoice-qr.png");
    Files.createDirectories(out.getParent());
    Files.write(out, png);
  }

  private LocalConfig config;
  private SecurityMaterial security;

  @BeforeEach
  void config() throws Exception {
    Path base = Path.of("target/test-work");
    Files.createDirectories(base);
    Path dir = Files.createTempDirectory(base, "contract-");
    byte[] key = new byte[32];
    new java.security.SecureRandom().nextBytes(key);
    Path file = dir.resolve("server.properties");
    Files.writeString(
        file,
        "master.key="
            + Base64.getEncoder().encodeToString(key)
            + "\n"
            + "casso.webhook.key=fixture-webhook-key-not-live\n"
            + "bank.account=000000000000\n"
            + "bank.bin=970418\n");
    System.setProperty("ttm.config", file.toAbsolutePath().toString());
    config = new LocalConfig();
    security = new SecurityMaterial(config);
  }

  private Profile profile() throws Exception {
    return new Profile(
        "TTM000001",
        "CT070352",
        "  TRẦN  văn AN ",
        "012345678901",
        "03/02/2005",
        "CT7C",
        "0912345678",
        "student@example.invalid",
        2023,
        Base64.getEncoder()
            .encodeToString(
                Portraits.jpeg(new BufferedImage(480, 640, BufferedImage.TYPE_INT_RGB), .7f)),
        1);
  }

  @Test
  void normalizesVietnameseCodeAndPreservesLeadingZeros() throws Exception {
    Profile p = profile().normalized();
    assertEquals("Trần Văn An", p.fullName());
    assertEquals("012345678901", p.citizenId());
    assertEquals("CT7C", p.className());
    assertEquals("tran van an", Profile.searchable(p.fullName()));
    assertEquals(p, ProfileCodec.decode(ProfileCodec.encode(p)));
  }

  @Test
  void rejectsImpossibleStudentClassAndDate() throws Exception {
    Profile p = profile();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Profile(
                    p.cardId(),
                    "CT070052",
                    p.fullName(),
                    p.citizenId(),
                    p.birthDate(),
                    "",
                    p.phone(),
                    p.email(),
                    p.intakeYear(),
                    p.photoBase64(),
                    1)
                .normalized());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Profile(
                    p.cardId(),
                    p.studentCode(),
                    p.fullName(),
                    p.citizenId(),
                    "31/02/2005",
                    p.className(),
                    p.phone(),
                    p.email(),
                    p.intakeYear(),
                    p.photoBase64(),
                    1)
                .normalized());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Profile(
                    p.cardId(),
                    p.studentCode(),
                    p.fullName(),
                    p.citizenId(),
                    p.birthDate(),
                    "CT7B",
                    p.phone(),
                    p.email(),
                    p.intakeYear(),
                    p.photoBase64(),
                    1)
                .normalized());
  }

  @Test
  void rendersCurrentVietQrSvgAsPngWithoutExternalEntities() throws Exception {
    byte[] svg =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"50\"><rect width=\"40\" height=\"50\" fill=\"#112233\"/></svg>"
            .getBytes(StandardCharsets.UTF_8);
    byte[] png = BankImage.png(svg, "image/svg+xml; charset=utf-8");
    var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
    assertEquals(40, image.getWidth());
    assertEquals(50, image.getHeight());
    assertEquals(0x112233, image.getRGB(20, 20) & 0xffffff);
    assertArrayEquals(png, BankImage.png(png, "image/png"));
    assertThrows(
        java.io.IOException.class,
        () ->
            BankImage.png(
                "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///untrusted'>]><svg/>"
                    .getBytes(StandardCharsets.UTF_8),
                "image/svg+xml"));
    assertThrows(
        java.io.IOException.class,
        () ->
            BankImage.png(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"90000\" height=\"90000\"/>"
                    .getBytes(StandardCharsets.UTF_8),
                "image/svg+xml"));
  }

  @Test
  void verifiesCassoCanonicalSignatureAndRejectsForgery() throws Exception {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("reference", "TEST-BANK-001");
    data.put("description", "TTMNAP N00000001");
    data.put("amount", 50000);
    data.put("id", 1);
    data.put("accountNumber", "000000000000");
    Map<String, Object> body = Map.of("error", 0, "data", data);
    String raw = SecurityMaterial.JSON.writeValueAsString(body);
    long now = Instant.now().toEpochMilli();
    String header = signature(body, now);
    BankGateway gateway = new BankGateway(config);
    assertEquals(50000, gateway.verify(raw, header).get("amount"));
    assertThrows(Failure.class, () -> gateway.verify(raw.replace("50000", "50001"), header));
    assertThrows(Failure.class, () -> gateway.verify(raw, "t=" + now + ",v1=" + "0".repeat(128)));
  }

  @Test
  void signedWebhookRetryRemainsValidWithinProviderRetryWindow() throws Exception {
    Map<String, Object> body = Map.of("error", 0, "data", Map.of("id", 1));
    BankGateway bank = new BankGateway(config);
    long retryTime = Instant.now().minusSeconds(3600).toEpochMilli();
    assertNotNull(
        bank.verify(SecurityMaterial.JSON.writeValueAsString(body), signature(body, retryTime)));
    long old = Instant.now().minusSeconds(14 * 3600).toEpochMilli();
    assertThrows(
        Failure.class,
        () -> bank.verify(SecurityMaterial.JSON.writeValueAsString(body), signature(body, old)));
  }

  private String signature(Map<String, Object> body, long timestamp) throws Exception {
    Map<String, Object> sorted = new TreeMap<>();
    body.forEach((k, v) -> {
      if (v instanceof Map<?, ?> m) {
        Map<String, Object> inner = new TreeMap<>();
        m.forEach((a, b) -> inner.put(a.toString(), b));
        sorted.put(k, inner);
      } else sorted.put(k, v);
    });
    String text = timestamp + "." + SecurityMaterial.JSON.writeValueAsString(sorted);
    Mac mac = Mac.getInstance("HmacSHA512");
    mac.init(
        new SecretKeySpec(
            "fixture-webhook-key-not-live".getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
    return "t="
        + timestamp
        + ",v1="
        + HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
  }
}
