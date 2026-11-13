package vn.edu.smartcard.backend;

import java.nio.file.*;
import java.util.*;

/** Exercises the packaged jar/class-loader without touching application cards or SQL data. */
final class RuntimeProbe {
  static void run(LocalConfig original) {
    try {
      Path base = original.root().resolve("diagnostics");
      Files.createDirectories(base);
      final Path root = Files.createTempDirectory(base, "card-");
      LocalConfig scoped =
          new LocalConfig() {
            @Override
            public Path root() {
              return root;
            }
          };
      SecurityMaterial material = new SecurityMaterial(scoped);
      ReaderHub readers = new ReaderHub(material, scoped);
      String terminal = UUID.randomUUID().toString(), one = readers.newCard();
      readers.connect(terminal, one);
      byte[] key1 = readers.use(terminal, c -> c.publicKey().getEncoded());
      String two = readers.newCard();
      readers.eject(terminal);
      readers.connect(terminal, two);
      byte[] key2 = readers.use(terminal, c -> c.publicKey().getEncoded());
      if (Arrays.equals(key1, key2)) throw new IllegalStateException("Keys must differ");
      readers.eject(terminal);
      ReaderHub restored = new ReaderHub(material, scoped);
      restored.connect(terminal, one);
      byte[] key3 = restored.use(terminal, c -> c.publicKey().getEncoded());
      if (!Arrays.equals(key1, key3)) throw new IllegalStateException("Key changed after restart");
      System.out.println("SIMULATOR_ECC_ISOLATION: PASS");
      System.out.println("SIMULATOR_PERSISTENCE: PASS");
    } catch (Exception e) {
      throw new IllegalStateException(
          "Packaged simulator verification failed (" + e.getClass().getSimpleName() + ")", e);
    }
  }
}
