package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.edu.smartcard.shared.Api;

class DemoBatchCardsTest extends CardTestFixture {
  @Test
  void batchRefusesAnExistingTargetBeforeCreatingAnything() throws Exception {
    Path conflict = config.root().resolve("cards/SIM0025.card");
    byte[] previous = {1, 2, 3, 4};
    Files.write(conflict, previous);
    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> DemoBatchCards.create(config, null));
    assertTrue(error.getMessage().contains("SIM0025"));
    assertArrayEquals(previous, Files.readAllBytes(conflict));
    assertFalse(Files.exists(config.root().resolve("cards/SIM0011.card")));
    assertFalse(Files.exists(config.root().resolve(DemoBatchCards.MANIFEST)));
  }

  @Test
  void namedCardsKeepExistingSnapshotsAndSurviveReload() throws Exception {
    Path first = config.root().resolve("cards/SIM0001.card");
    byte[] original = Files.readAllBytes(first);
    assertEquals("SIM0011", hub.newNamedCard("SIM0011"));
    assertEquals("SIM0060", hub.newNamedCard("SIM0060"));
    assertThrows(RuntimeException.class, () -> hub.newNamedCard("SIM0001"));
    assertThrows(RuntimeException.class, () -> hub.newNamedCard("SIM0011"));
    assertThrows(RuntimeException.class, () -> hub.newNamedCard("../SIM0003"));
    assertArrayEquals(original, Files.readAllBytes(first));

    hub.eject(TERMINAL);
    hub.connect(TERMINAL, "SIM0011");
    byte[] key = hub.use(TERMINAL, c -> c.publicKey().getEncoded());
    hub.eject(TERMINAL);
    ReaderHub reloaded = new ReaderHub(security, config, false);
    assertEquals(List.of("SIM0001", "SIM0011", "SIM0060"), reloaded.choices().stream()
        .map(Api.DeviceChoice::id).filter(id -> id.startsWith("SIM")).toList());
    reloaded.connect(TERMINAL, "SIM0011");
    assertArrayEquals(key, reloaded.use(TERMINAL, c -> c.publicKey().getEncoded()));
    assertFalse(reloaded.status(TERMINAL).initialized());
    reloaded.eject(TERMINAL);
  }
}
