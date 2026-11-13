package vn.edu.smartcard.backend;

import com.zaxxer.hikari.HikariDataSource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LocalConfig {
  private final Properties values = new Properties();
  private final Path root;

  public LocalConfig() {
    Path file =
        Path.of(System.getProperty("ttm.config", ".local/server.properties")).toAbsolutePath();
    root = file.getParent();
    if (Files.exists(file))
      try (InputStream in = Files.newInputStream(file)) {
        values.load(in);
      } catch (IOException e) {
        throw new IllegalStateException("Không đọc được cấu hình máy chủ.", e);
      }
  }

  public String get(String key, String fallback) {
    String env = System.getenv("TTM_" + key.toUpperCase(Locale.ROOT).replace('.', '_'));
    return env != null ? env : values.getProperty(key, fallback);
  }

  public String required(String key) {
    String v = get(key, "");
    if (v.isBlank())
      throw new IllegalStateException(
          "Thiếu cấu hình bắt buộc: " + key + ". Chạy 01-Chuan-bi.ps1.");
    return v;
  }

  public Path root() {
    return root;
  }

  @Bean
  public DataSource dataSource() {
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(required("db.url"));
    ds.setUsername(required("db.user"));
    ds.setPassword(required("db.password"));
    ds.setMaximumPoolSize(8);
    ds.setConnectionTimeout(5000);
    return ds;
  }

  @Bean(initMethod = "migrate")
  public Flyway flyway(DataSource ds) {
    FinancialRetention.beforeMigration(ds);
    return Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration")
        .validateMigrationNaming(true)
        .callbacks(FinancialRetention.guard())
        .load();
  }
}
