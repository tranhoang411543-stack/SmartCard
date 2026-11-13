package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import vn.edu.smartcard.shared.*;

/** Real PostgreSQL only; never falls back to an in-memory database. */
abstract class DatabaseFixture {
  static LocalConfig config;
  static SecurityMaterial security;
  static CardRepository repo;
  static ReaderHub hub;
  static CardService cards;
  static WalletService wallet;
  static AuthService auth;
  static RecordingMail mail;
  static FakeBank bank;
  static String TERMINAL = "bc4ab15b-5dca-488b-b7c8-20407aceba90";
  static com.zaxxer.hikari.HikariDataSource source;
  static final String TEST_PASSWORD = "Fixture-admin-2026";

  @BeforeAll
  static void prepare() throws Exception {
    String path =
        System.getProperty(
            "ttm.test.config",
            Path.of("../.local/test/server.properties").toAbsolutePath().toString());
    if (!Files.exists(Path.of(path)))
      throw new IllegalStateException(
          "Run scripts/Prepare.ps1 -Test first. Real PostgreSQL is required.");
    Path work = Path.of("target/test-work");
    Files.createDirectories(work);
    Path fixture = Files.createTempDirectory(work, "database-").resolve("server.properties");
    Files.copy(Path.of(path), fixture);
    System.setProperty("ttm.config", fixture.toAbsolutePath().toString());
    config = new LocalConfig();
    String testUrl = config.required("db.url");
    if (!testUrl.matches(
        "jdbc:postgresql://127\\.0\\.0\\.1:5548/ttm_test(?:\\?currentSchema=ttm_it_[a-z0-9]{8,32})?"))
      throw new IllegalStateException(
          "Destructive tests require the project's isolated ttm_test database.");
    source = (com.zaxxer.hikari.HikariDataSource) config.dataSource();
    String expectedSchema = System.getProperty("ttm.expected.test.schema", "");
    if (!expectedSchema.isEmpty()) {
      if (!expectedSchema.matches("ttm_it_[a-z0-9]{8,32}")
          || !testUrl.equals("jdbc:postgresql://127.0.0.1:5548/ttm_test?currentSchema=" + expectedSchema)
          || !expectedSchema.equals(new JdbcTemplate(source).queryForObject("SELECT current_schema()", String.class)))
        throw new IllegalStateException("Refusing to modify a different PostgreSQL test schema.");
    }
    config.flyway(source).migrate();
    JdbcTemplate db = new JdbcTemplate(source);
    db.execute(
        "TRUNCATE admin_transaction_journal,sepay_topup_requests,sepay_payment_codes,"
            + " station_sessions,retired_topup_references,refund_items,refund_cases,retention_preflight,wallet_operations,bank_events,topups,invoice_lines,invoices,audit_log,cards,cohorts,reset_codes,admin_setup_codes,admin_sessions,auth_limits,admins"
            + " RESTART IDENTITY CASCADE");
    security = new SecurityMaterial(config);
    repo = new CardRepository(db, new DataSourceTransactionManager(source), security);
    hub = new ReaderHub(security, config);
    cards = new CardService(hub, repo, security);
    mail = new RecordingMail(config);
    auth = new AuthService(repo, security, config, mail);
    bank = new FakeBank(config);
    wallet = new WalletService(cards, repo, bank, security);
    var firstAdmin = new Api.SetupOtp(config.required("setup.token"), "fixture_admin", "fixture@example.invalid");
    auth.requestSetupOtp(firstAdmin);
    auth.setup(new Api.Setup(firstAdmin.token(), firstAdmin.username(), firstAdmin.email(),
        TEST_PASSWORD, mail.setupCode));
  }

  @BeforeEach
  void openCheckoutSession() {
    TERMINAL = UUID.randomUUID().toString();
    wallet.openStation(new Api.OpenStation(UUID.randomUUID().toString(), TERMINAL));
  }

  @AfterAll
  static void close() {
    if (source != null) source.close();
  }

  static Profile profile(String code, String cccd, int year) throws Exception {
    return new Profile(
        "",
        code,
        "Nguyễn Văn An",
        cccd,
        "03/02/2005",
        "",
        "0912345678",
        "student@example.invalid",
        year,
        Base64.getEncoder()
            .encodeToString(
                Portraits.jpeg(new BufferedImage(480, 640, BufferedImage.TYPE_INT_RGB), .7f)),
        0);
  }

  static Profile enroll(String code, String cccd, int year) throws Exception {
    String sim = hub.newCard();
    if (hub.status(TERMINAL).present()) hub.eject(TERMINAL);
    hub.connect(TERMINAL, sim);
    return cards.initialize(
        TERMINAL, new Api.Enrollment(profile(code, cccd, year), "123456"), admin().id());
  }

  static void simulatePhysicalDisconnect() {
    try {
      var sessions = ReaderHub.class.getDeclaredField("byTerminal");
      sessions.setAccessible(true);
      Object session = ((Map<?, ?>) sessions.get(hub)).get(TERMINAL);
      if (session == null) return;
      var disconnect = ReaderHub.class.getDeclaredMethod("ejectInternal", session.getClass());
      disconnect.setAccessible(true);
      synchronized (session) { disconnect.invoke(hub, session); }
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Could not simulate an unexpected reader loss", failure);
    }
  }

  static AuthService.Admin admin() {
    String token = auth.login(new Api.Credentials("fixture_admin", TEST_PASSWORD)).token();
    AccessContext.admin(() -> auth.require(token));
    return auth.require(token);
  }

  @AfterEach
  void clearPermissions() {
    AccessContext.clear();
    cards.sync.fault = step -> {};
    // Tests are isolated from any live connection or unfinished QR in a prior case.
    simulatePhysicalDisconnect();
  }

  static final class RecordingMail extends MailGateway {
    String code;
    volatile String setupCode;
    volatile String setupAddress;

    RecordingMail(LocalConfig c) {
      super(c);
    }

    public boolean ready() {
      return true;
    }

    public void recovery(String address, String value) {
      code = value;
    }

    @Override
    public void setupOtp(String address, String value) {
      setupAddress = address;
      setupCode = value;
    }
  }

  static final class FakeBank extends BankGateway {
    String qrReference;
    long qrAmount;
    int fullQrCalls;
    boolean failFullQr;

    FakeBank(LocalConfig c) {
      super(c);
    }

    public boolean ready() {
      return true;
    }

    public boolean confirmationReady() {
      return true;
    }

    public String qr(long amount, String reference) {
      qrReference = reference;
      qrAmount = amount;
      return "data:image/png;base64,";
    }

    @Override
    public String fullQr(long amount, String reference) {
      fullQrCalls++;
      if (failFullQr) throw new Failure(503, "BANK_UNAVAILABLE", "QR fixture unavailable");
      return qr(amount, reference);
    }

    public String account() {
      return "000000000000";
    }

    Map<String, Object> event(String id, String ref, long amount) {
      return Map.of(
          "id",
          id,
          "reference",
          "BANK-" + id,
          "description",
          ref,
          "amount",
          amount,
          "accountNumber",
          account());
    }
  }
}
