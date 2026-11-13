package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.smartcard.shared.*;

class LegacyCardCompatibilityTest extends CardTestFixture {
  private void load(String name) throws Exception {
    hub.eject(TERMINAL);
    Arrays.fill(share, (byte) 0x4d);
    Files.copy(Path.of("src/test/resources/card-legacy/" + name),
        config.root().resolve("cards/" + alias + ".card"), StandardCopyOption.REPLACE_EXISTING);
    hub = new ReaderHub(security, config);
    hub.connect(TERMINAL, alias);
    assertEquals(Files.readString(Path.of("src/test/resources/card-legacy/public-key.txt")),
        Base64.getEncoder().encodeToString(use(c -> c.publicKey().getEncoded())));
    assertThrows(Failure.class, () -> use(CardClient::profile));
  }

  @ParameterizedTest @ValueSource(strings={"active-v3.card", "active-v4.card"})
  void originalAppletSnapshotsRetainKeysPinAndProfile(String file) throws Exception {
    load(file);
    assertEquals(0, state().balance());
    login("123456");
    assertEquals(profile(), use(CardClient::profile));
    byte[] snapshot = Files.readAllBytes(config.root().resolve("cards/" + alias + ".card"));
    assertEquals(5, snapshot[3]);
  }

  @Test void originalPreparedUpdateCanCommitWithoutRestoringSession() throws Exception {
    load("prepared-v4.card");
    assertEquals(1, state().phase());
    assertEquals(2, state().pendingId());
    complete(Protocol.COMMIT, 2);
    complete(Protocol.ACK, 2);
    login("123456");
    assertEquals(profile().withCard("TTM000001", 2), use(CardClient::profile));
  }

  @Test void originalDebitHoldSurvivesUpgradeAndReversesOnce() throws Exception {
    load("hold-v4.card");
    var before = state();
    assertEquals(65000, before.balance());
    assertEquals(1, before.financialHold());
    operation = before.lastId();
    long reverse = prepare(Wire.REVERSAL, Wire.operation(before.debitId(), 35000));
    complete(Protocol.COMMIT, reverse);
    complete(Protocol.ACK, reverse);
    complete(Protocol.RELEASE, before.debitId());
    login("123456");
    assertEquals(100000, use(CardClient::balance));
  }
}
