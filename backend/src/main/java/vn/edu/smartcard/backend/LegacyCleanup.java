package vn.edu.smartcard.backend;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

/** User-approved V2 demo reset, run after Flyway succeeds in the project root. */
final class LegacyCleanup {
  static void clean(Path configuredRoot) {
    Path root = configuredRoot.toAbsolutePath().normalize();
    if (!root.equals(Path.of(".local").toAbsolutePath().normalize())) return;
    try {
      remove(root, root.resolve("issuer.enc"));
      remove(root, root.resolve("before-sync-v2"));
      Path diagnostics = root.resolve("diagnostics");
      if (Files.isDirectory(diagnostics))
        try (var entries = Files.list(diagnostics)) {
          for (Path dir :
              entries.filter(p -> p.getFileName().toString().matches("card-[0-9]+")).toList()) {
            boolean old = false;
            try (var files = Files.walk(dir)) {
              for (Path file :
                  files
                      .filter(p -> p.getFileName().toString().matches("SIM[0-9]{4}\\.card"))
                      .toList())
                if (!ReaderHub.SnapshotRuntime.isV3(Files.readAllBytes(file))) old = true;
            }
            if (old) remove(diagnostics, dir);
          }
        }
    } catch (IOException e) {
      throw new IllegalStateException("Chưa dọn được bộ nhớ/sao lưu demo V2 trong .local.", e);
    }
  }

  private static void remove(Path expectedParent, Path target) throws IOException {
    Path parent = expectedParent.toAbsolutePath().normalize(),
        checked = target.toAbsolutePath().normalize();
    if (!checked.getParent().equals(parent) || checked.equals(parent))
      throw new IOException("Unexpected cleanup target");
    if (!Files.exists(checked, LinkOption.NOFOLLOW_LINKS)) return;
    // Default walk does not follow directory symlinks outside the checked target.
    try (var paths = Files.walk(checked)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }
}
