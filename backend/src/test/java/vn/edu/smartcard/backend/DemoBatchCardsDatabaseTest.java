package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import vn.edu.smartcard.shared.Api;

/** Uses a newly created schema in ttm_test; never truncates the shared test schema. */
class DemoBatchCardsDatabaseTest {
  @TempDir Path work;

  @Test
  void createsFiftyCardsThroughTheRealEnrollmentAndPinFlow() throws Exception {
    Path testConfig = Path.of(System.getProperty("ttm.test.config",
        Path.of("../.local/test/server.properties").toAbsolutePath().toString()));
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(testConfig)) { properties.load(input); }
    String url = properties.getProperty("db.url");
    assertEquals("jdbc:postgresql://127.0.0.1:5548/ttm_test", url);
    String schema = "ttm_seed_test_" + UUID.randomUUID().toString().replace("-", "");
    assertTrue(schema.matches("ttm_seed_test_[0-9a-f]{32}"));

    try (HikariDataSource admin = source(url, properties)) {
      JdbcTemplate root = new JdbcTemplate(admin);
      root.execute("CREATE SCHEMA " + schema);
      try (HikariDataSource isolated = source(url + "?currentSchema=" + schema, properties)) {
        Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").load().migrate();
        JdbcTemplate db = new JdbcTemplate(isolated);
        db.update("INSERT INTO admins(username,email,password_hash) VALUES (?,?,?)",
            "fixture", "fixture@example.invalid", "fixture-hash");
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encodedKey = Base64.getEncoder().encodeToString(key);
        LocalConfig config = new LocalConfig() {
          @Override public Path root() { return work; }
          @Override public String get(String name, String fallback) {
            return name.equals("master.key") ? encodedKey : fallback;
          }
        };
        SecurityMaterial security = new SecurityMaterial(config);
        ReaderHub original = new ReaderHub(security, config, false);
        original.newNamedCard("SIM0001");
        byte[] existing = Files.readAllBytes(work.resolve("cards/SIM0001.card"));

        DemoBatchCards.create(config, isolated);

        assertArrayEquals(existing, Files.readAllBytes(work.resolve("cards/SIM0001.card")));
        assertEquals(50, Files.readAllLines(work.resolve(DemoBatchCards.MANIFEST)).size());
        assertEquals(50L, db.queryForObject("SELECT count(*) FROM cards WHERE status='ACTIVE'", Long.class));
        assertEquals(50L, db.queryForObject("SELECT count(DISTINCT public_key) FROM cards", Long.class));
        assertEquals(50L, db.queryForObject("SELECT count(DISTINCT backend_share) FROM card_secrets", Long.class));
        assertEquals(50L, db.queryForObject("SELECT count(*) FROM card_wallets WHERE balance=0", Long.class));
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM wallet_operations", Long.class));
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM topups", Long.class));
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM invoices", Long.class));
        for (int n = DemoBatchCards.FIRST; n <= DemoBatchCards.LAST; n++)
          assertTrue(Files.isRegularFile(work.resolve("cards/" + DemoBatchCards.alias(n) + ".card")));

        CardRepository repo = new CardRepository(db, new DataSourceTransactionManager(isolated), security);
        Api.CardListPage firstPage = repo.searchWithStatus("", "", null, "", "CARD_ID", 0);
        Api.CardListPage secondPage = repo.searchWithStatus("", "", null, "", "CARD_ID", 1);
        Api.CardListPage lastPage = repo.searchWithStatus("", "", null, "", "CARD_ID", 4);
        assertEquals(10, firstPage.pageSize());
        assertEquals(50, firstPage.total());
        assertEquals(10, firstPage.items().size());
        assertEquals("TTM000001", firstPage.items().getFirst().cardId());
        assertEquals("TTM000010", firstPage.items().getLast().cardId());
        assertEquals("TTM000011", secondPage.items().getFirst().cardId());
        assertEquals("TTM000050", lastPage.items().getLast().cardId());
        assertEquals(10, lastPage.lifecycleStatuses().size());
        assertEquals(1, repo.searchWithStatus("TTM000035", "", null, "", "CARD_ID", 0).total());
        assertEquals(20, repo.search("", "", null, "", "NEWEST", 0).pageSize());

        ReaderHub reloaded = new ReaderHub(security, config, false);
        CardService service = new CardService(reloaded, repo, security);
        String terminal = UUID.randomUUID().toString();
        for (String alias : List.of("SIM0011", "SIM0035", "SIM0060")) {
          Api.ReaderStatus status = reloaded.connect(terminal, alias);
          assertTrue(status.initialized());
          Api.StudentHome home = service.login(terminal, DemoBatchCards.PIN);
          assertEquals(0, home.balance());
          assertTrue(home.profile().studentCode().matches("(AT|CT|DT)[0-9]{6}"));
          assertTrue(service.transactions(terminal, null).items().isEmpty());
          reloaded.eject(terminal);
        }
        assertThrows(IllegalStateException.class, () -> DemoBatchCards.create(config, isolated));
        assertEquals(50L, db.queryForObject("SELECT count(*) FROM cards", Long.class));
      } finally {
        root.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }

  private static HikariDataSource source(String url, Properties properties) {
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(url);
    ds.setUsername(properties.getProperty("db.user"));
    ds.setPassword(properties.getProperty("db.password"));
    ds.setConnectionTimeout(5000);
    return ds;
  }
}
