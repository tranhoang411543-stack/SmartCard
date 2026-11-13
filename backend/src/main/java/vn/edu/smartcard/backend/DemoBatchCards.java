package vn.edu.smartcard.backend;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import vn.edu.smartcard.shared.*;

/** Offline demo tool: uses the same card enrollment service as the Admin screen. */
final class DemoBatchCards {
  static final int FIRST = 11, LAST = 60;
  static final String PIN = "123123";
  static final String MANIFEST = "demo-cards-SIM0011-SIM0060.jsonl";

  private DemoBatchCards() {}

  static void create(LocalConfig config) throws Exception {
    if (!"jdbc:postgresql://127.0.0.1:5548/ttm_cantin".equals(config.required("db.url")))
      throw new IllegalStateException("Chỉ dùng CSDL ttm_cantin cục bộ của project.");
    try (var source = (com.zaxxer.hikari.HikariDataSource) config.dataSource()) {
      // Validate only: this tool must not perform migrations or unrelated cleanup.
      Flyway.configure().dataSource(source).locations("classpath:db/migration")
          .validateMigrationNaming(true).load().validate();
      create(config, source);
    }
  }

  static void create(LocalConfig config, DataSource source) throws Exception {
    Path root = config.root().toAbsolutePath().normalize();
    Path cardsDir = root.resolve("cards");
    Path manifest = root.resolve(MANIFEST);
    if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalStateException("Danh sách kết quả đã tồn tại: " + MANIFEST);
    for (int n = FIRST; n <= LAST; n++) {
      String name = alias(n);
      if (Files.exists(cardsDir.resolve(name + ".card"), LinkOption.NOFOLLOW_LINKS))
        throw new IllegalStateException("Thẻ " + name + " đã tồn tại; không tạo hoặc ghi đè.");
    }

    JdbcTemplate db = new JdbcTemplate(source);
    var admins = db.queryForList("SELECT id FROM admins ORDER BY id LIMIT 1", Long.class);
    if (admins.isEmpty()) throw new IllegalStateException("Hãy khởi tạo tài khoản admin trước.");
    long actor = admins.getFirst();
    var profiles = profiles(db);
    var security = new SecurityMaterial(config);
    var repo = new CardRepository(db, new DataSourceTransactionManager(source), security);
    var hub = new ReaderHub(security, config, false);
    var service = new CardService(hub, repo, security);
    String terminal = UUID.randomUUID().toString();
    int completed = 0;
    String current = "";
    boolean connected = false;

    try (BufferedWriter out = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      AccessContext.admin(() -> {}, actor);
      try {
        for (int n = FIRST; n <= LAST; n++) {
          current = alias(n);
          hub.newNamedCard(current);
          hub.connect(terminal, current);
          connected = true;
          Profile p = service.initialize(terminal, new Api.Enrollment(profiles.get(n - FIRST), PIN), actor);
          Api.ReaderStatus status = hub.status(terminal);
          if (!status.initialized() || !p.cardId().equals(status.cardId()))
            throw new IllegalStateException("Trạng thái thẻ sau khởi tạo không khớp.");
          out.write(security.encode(Map.of("alias", current, "cardId", p.cardId(),
              "studentCode", p.studentCode(), "fullName", p.fullName())));
          out.newLine();
          out.flush();
          completed++;
          System.out.println("Đã tạo " + current + " · " + p.cardId() + " · " + p.studentCode());
          hub.eject(terminal);
          connected = false;
        }
      } catch (Exception e) {
        throw new IllegalStateException("Đã dừng tại " + current + " sau " + completed
            + " thẻ. Kiểm tra trạng thái thẻ và đối soát trước khi chạy lại. Lỗi: "
            + e.getClass().getSimpleName());
      } finally {
        try {
          if (connected) hub.eject(terminal);
        } finally {
          AccessContext.clear();
        }
      }
    }
    System.out.println("Đã tạo đủ 50 thẻ. Danh sách: " + manifest);
  }

  static String alias(int number) {
    return String.format(Locale.ROOT, "SIM%04d", number);
  }

  private static java.util.List<Profile> profiles(JdbcTemplate db) throws Exception {
    String program = null;
    int cohort = 0;
    for (String candidate : new String[] {"CT", "AT", "DT"}) {
      for (int year = 99; year >= 1; year--) {
        String prefix = String.format(Locale.ROOT, "%s%02d", candidate, year);
        int usedCohorts = Objects.requireNonNull(db.queryForObject(
            "SELECT count(*) FROM cohorts WHERE program=? AND cohort=?", Integer.class, candidate, year));
        int usedCodes = Objects.requireNonNull(db.queryForObject(
            "SELECT count(*) FROM cards WHERE student_code LIKE ?", Integer.class, prefix + "%"));
        if (usedCohorts == 0 && usedCodes == 0) {
          program = candidate;
          cohort = year;
          break;
        }
      }
      if (program != null) break;
    }
    if (program == null) throw new IllegalStateException("Không còn khóa sinh viên mẫu trống.");

    String[] surnames = {"Nguyễn", "Trần", "Lê", "Phạm", "Hoàng", "Phan", "Vũ", "Đặng", "Bùi", "Đỗ"};
    String[] middle = {"Minh", "Ngọc", "Hoàng", "Thị", "Văn"};
    String[] given = {"An", "Bình", "Châu", "Dũng", "Giang", "Hà", "Khánh", "Linh", "Nam", "Trang"};
    String photo = portrait();
    SecureRandom random = new SecureRandom();
    Set<String> citizens = new HashSet<>();
    var result = new ArrayList<Profile>();
    for (int n = FIRST; n <= LAST; n++) {
      int index = n - FIRST;
      String citizen = null;
      for (int attempt = 0; attempt < 1000; attempt++) {
        String candidate = String.format(Locale.ROOT, "99%010d", random.nextLong(10_000_000_000L));
        if (citizens.add(candidate) && Objects.requireNonNull(db.queryForObject(
            "SELECT count(*) FROM cards WHERE citizen_id=?", Integer.class, candidate)) == 0) {
          citizen = candidate;
          break;
        }
      }
      if (citizen == null) throw new IllegalStateException("Không tạo được CCCD mẫu duy nhất.");
      String code = String.format(Locale.ROOT, "%s%02d01%02d", program, cohort, n);
      String name = surnames[index % 10] + " " + middle[index / 10] + " " + given[index % 10];
      LocalDate birthday = LocalDate.of(2005, index % 12 + 1, index % 28 + 1);
      String phone = String.format(Locale.ROOT, "09%08d", 90_000_000 + n);
      result.add(new Profile("", code, name, citizen,
          birthday.format(DateTimeFormatter.ofPattern("dd/MM/uuuu")), "", phone,
          String.format(Locale.ROOT, "demo.sim%04d@example.invalid", n),
          LocalDate.now().getYear(), photo, 0).normalized());
    }
    return result;
  }

  private static String portrait() throws Exception {
    BufferedImage image = new BufferedImage(480, 640, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setColor(new Color(225, 235, 245));
      g.fillRect(0, 0, 480, 640);
      g.setColor(new Color(55, 85, 125));
      g.fillOval(160, 100, 160, 180);
      g.fillRoundRect(90, 300, 300, 300, 120, 120);
    } finally {
      g.dispose();
    }
    return Base64.getEncoder().encodeToString(Portraits.jpeg(image, .7f));
  }
}
