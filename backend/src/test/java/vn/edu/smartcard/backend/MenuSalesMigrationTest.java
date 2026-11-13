package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises migration and payment transitions in a disposable PostgreSQL test schema. */
class MenuSalesMigrationTest {
  @Test
  void backfillsPaidQuantitiesAndCountsOnlyCommittedTransitions() throws Exception {
    Path config = Path.of(System.getProperty("ttm.test.config",
        Path.of("../.local/test/server.properties").toAbsolutePath().toString()));
    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(config)) { props.load(in); }
    String configuredUrl = props.getProperty("db.url");
    assertTrue(configuredUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:5548/ttm_test"
        + "(?:\\?currentSchema=ttm_it_[a-z0-9]{8,32})?"));
    String url = "jdbc:postgresql://127.0.0.1:5548/ttm_test";
    String schema = "ttm_sales_" + UUID.randomUUID().toString().replace("-", "");
    assertTrue(schema.matches("ttm_sales_[0-9a-f]{32}"));
    try (HikariDataSource rootSource = source(url, props)) {
      JdbcTemplate root = new JdbcTemplate(rootSource);
      root.execute("CREATE SCHEMA " + schema);
      try (HikariDataSource isolated = source(url + "?currentSchema=" + schema, props)) {
        Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").target("13").load().migrate();
        JdbcTemplate db = new JdbcTemplate(isolated);
        long item1 = db.queryForObject("SELECT min(id) FROM menu_items", Long.class);
        long item2 = db.queryForObject("SELECT max(id) FROM menu_items", Long.class);
        long historical = invoice(db, "PAID", item1, 3);
        invoice(db, "CANCELLED", item1, 4);
        long open = invoice(db, "OPEN", item1, 2);
        Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").load().migrate();
        assertEquals(3, count(db, item1));
        assertEquals(0, count(db, item2));

        new TransactionTemplate(new DataSourceTransactionManager(isolated)).executeWithoutResult(tx -> {
          db.update("UPDATE invoices SET status='PAID' WHERE id=?", open);
          assertEquals(5, count(db, item1));
          tx.setRollbackOnly();
        });
        assertEquals(3, count(db, item1));
        db.update("UPDATE invoices SET status='PAID' WHERE id=?", open);
        db.update("UPDATE invoices SET status='PAID' WHERE id=?", open);
        assertEquals(5, count(db, item1));
        db.update("DELETE FROM invoice_lines WHERE invoice_id=?", historical);
        assertEquals(5, count(db, item1));

        long first = invoice(db, "OPEN", item1, 2);
        long second = invoice(db, "OPEN", item2, 3);
        line(db, first, item2, 1);
        line(db, second, item1, 4);
        try (var pool = Executors.newFixedThreadPool(2)) {
          CountDownLatch start = new CountDownLatch(1);
          var a = pool.submit(() -> { start.await(); db.update("UPDATE invoices SET status='PAID' WHERE id=?", first); return null; });
          var b = pool.submit(() -> { start.await(); db.update("UPDATE invoices SET status='PAID' WHERE id=?", second); return null; });
          start.countDown();
          a.get(10, TimeUnit.SECONDS);
          b.get(10, TimeUnit.SECONDS);
        }
        assertEquals(11, count(db, item1));
        assertEquals(4, count(db, item2));
      } finally {
        root.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }

  private static long invoice(JdbcTemplate db, String status, long item, int quantity) {
    long id = db.queryForObject("INSERT INTO invoices(total,status) VALUES(10000,?) RETURNING id",
        Long.class, status);
    line(db, id, item, quantity);
    return id;
  }

  private static void line(JdbcTemplate db, long invoice, long item, int quantity) {
    db.update("INSERT INTO invoice_lines(invoice_id,item_id,name,quantity,unit_price) VALUES(?,?,'Fixture',?,1000)",
        invoice, item, quantity);
  }

  private static long count(JdbcTemplate db, long item) {
    return db.queryForObject("SELECT sold_quantity FROM menu_items WHERE id=?", Long.class, item);
  }

  private static HikariDataSource source(String url, Properties props) {
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(url);
    ds.setUsername(props.getProperty("db.user"));
    ds.setPassword(props.getProperty("db.password"));
    ds.setConnectionTimeout(5000);
    return ds;
  }
}
