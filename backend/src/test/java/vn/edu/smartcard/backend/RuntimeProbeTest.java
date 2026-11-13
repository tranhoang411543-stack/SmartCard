package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class RuntimeProbeTest {
  @Test
  void simulatorCheckSwitchesCardsOnlyAfterExplicitDisconnect() throws Exception {
    Path base = Path.of("target/test-work");
    Files.createDirectories(base);
    Path directory = Files.createTempDirectory(base, "runtime-probe-");
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    Path config = directory.resolve("server.properties");
    Files.writeString(config, "master.key=" + Base64.getEncoder().encodeToString(key) + "\n");
    String previous = System.getProperty("ttm.config");
    System.setProperty("ttm.config", config.toAbsolutePath().toString());
    try {
      LocalConfig scoped = new LocalConfig() {
        @Override public Path root() { return directory; }
      };
      assertDoesNotThrow(() -> RuntimeProbe.run(scoped));
    } finally {
      if (previous == null) System.clearProperty("ttm.config");
      else System.setProperty("ttm.config", previous);
    }
  }
}
