package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.nio.file.ExtendedOpenOption;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;

@EnabledOnOs(OS.WINDOWS)
class SnapshotPersistenceTest {
  @Test
  void transientWindowsDeleteLockCanReleaseBeforeOneAtomicRetry() throws Exception {
    Path dir = Files.createTempDirectory(Path.of("target"), "snapshot-lock-");
    Path target = Files.writeString(dir.resolve("card"), "old");
    Path source = Files.writeString(dir.resolve("card.tmp"), "new");
    var executor = Executors.newSingleThreadScheduledExecutor();
    try (var held = FileChannel.open(target, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
      var released = executor.schedule(() -> { held.close(); return true; }, 30, TimeUnit.MILLISECONDS);
      ReaderHub.replaceSnapshot(source, target);
      assertTrue(released.get(1, TimeUnit.SECONDS));
      assertEquals("new", Files.readString(target));
      assertFalse(Files.exists(source));
    } finally { executor.shutdownNow(); }
  }

  @Test
  void persistentWindowsLockStillFailsAndPreservesOldSnapshot() throws Exception {
    Path dir = Files.createTempDirectory(Path.of("target"), "snapshot-locked-");
    Path target = Files.writeString(dir.resolve("card"), "old");
    Path source = Files.writeString(dir.resolve("card.tmp"), "new");
    try (var held = FileChannel.open(target, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
      assertThrows(AccessDeniedException.class, () -> ReaderHub.replaceSnapshot(source, target));
      assertEquals("old", Files.readString(target));
      assertEquals("new", Files.readString(source));
    }
  }
}
