package vn.edu.smartcard.backend;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import vn.edu.smartcard.shared.*;

/** Explicit local CLI, never an HTTP backdoor. Does not fabricate bank receipts. */
final class DemoCards {
  static void create(LocalConfig config) throws Exception {
    if (!config.required("db.url").matches("jdbc:postgresql://127\\.0\\.0\\.1:5548/ttm_cantin"))
      throw new IllegalStateException("Chỉ tạo demo trong CSDL ttm_cantin cục bộ của project.");
    try (var ds = (com.zaxxer.hikari.HikariDataSource) config.dataSource()) {
      config.flyway(ds).migrate();
      var db = new JdbcTemplate(ds);
      if (db.queryForObject("SELECT count(*) FROM cards", Long.class) != 0)
        throw Failure.bad("CSDL đã có thẻ. Công cụ demo không ghi đè hoặc xóa thẻ.");
      var admins = db.queryForList("SELECT id FROM admins ORDER BY id LIMIT 1", Long.class);
      if (admins.isEmpty())
        throw Failure.bad("Hãy thiết lập tài khoản admin tại quầy quản lý trước khi tạo bộ demo.");
      long actor = admins.getFirst();
      var security = new SecurityMaterial(config);
      var repo = new CardRepository(db, new DataSourceTransactionManager(ds), security);
      var hub = new ReaderHub(security, config);
      var service = new CardService(hub, repo, security);
      String terminal = UUID.randomUUID().toString();
      var manifest = new ArrayList<Map<String, String>>();
      String[] codes = {"AT400101", "CT400101", "DT400101"},
          names = {"Nguyễn Văn An", "Lê Thị Bình", "Trần Minh Châu"};
      AccessContext.admin(() -> {}, actor);
      try {
        for (int i = 0; i < codes.length; i++) {
          String alias = hub.newCard();
          hub.connect(terminal, alias);
          BufferedImage image = new BufferedImage(480, 640, BufferedImage.TYPE_INT_RGB);
          Graphics2D g = image.createGraphics();
          g.setColor(new Color(225, 235, 245));
          g.fillRect(0, 0, 480, 640);
          g.setColor(new Color(55, 85, 125));
          g.fillOval(160, 100, 160, 180);
          g.fillRoundRect(90, 300, 300, 300, 120, 120);
          g.dispose();
          String photo = Base64.getEncoder().encodeToString(Portraits.jpeg(image, .7f));
          Profile input =
              new Profile(
                  "",
                  codes[i],
                  names[i],
                  String.format("99000000000%d", i + 1),
                  "03/02/2005",
                  "",
                  "0912345678",
                  "demo" + (i + 1) + "@example.invalid",
                  2026,
                  photo,
                  0);
          Profile p = service.initialize(terminal, new Api.Enrollment(input, "123456"), actor);
          manifest.add(
              Map.of(
                  "alias",
                  alias,
                  "cardId",
                  p.cardId(),
                  "studentCode",
                  p.studentCode(),
                  "fullName",
                  p.fullName()));
          hub.eject(terminal);
        }
      } finally {
        AccessContext.clear();
      }
      Files.writeString(config.root().resolve("demo-cards.json"), security.encode(manifest));
      System.out.println(
          "Đã tạo 3 thẻ demo V3. Hồ sơ: .local/demo-cards.json. Số dư ban đầu: 0 đồng.");
    }
  }
}
