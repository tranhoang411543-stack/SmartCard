package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import vn.edu.smartcard.shared.*;

class AppletFlowTest extends CardTestFixture {
  @Test
  void selectionGuardRejectsOldConnectionsAndDifferentCardIds() throws Exception {
    var profile = init();
    String oldConnection = hub.status(TERMINAL).connectionId();
    assertEquals("READER_IN_USE", assertThrows(Failure.class,
        () -> hub.connect(TERMINAL, alias)).code);
    hub.eject(TERMINAL);
    var current = hub.connect(TERMINAL, alias);
    assertNotEquals(oldConnection, current.connectionId());

    Failure stale = assertThrows(Failure.class, () -> hub.use(
        TERMINAL, oldConnection, profile.cardId(), card -> fail("Old connection reached mutation")));
    assertEquals("CARD_CHANGED", stale.code);
    Failure different = assertThrows(Failure.class, () -> hub.use(
        TERMINAL, current.connectionId(), "TTM999999", card -> fail("Different card reached mutation")));
    assertEquals("CARD_CHANGED", different.code);
    assertEquals(profile.cardId(), hub.use(
        TERMINAL, current.connectionId(), profile.cardId(), card -> card.status("").cardId()));
  }

  @Test
  void eccIdentityIsP256AndRejectsAnotherRegisteredKey() throws Exception {
    init();
    var key = use(CardClient::publicKey);
    assertEquals("EC", key.getAlgorithm());
    assertEquals(
        256,
        ((java.security.interfaces.ECPublicKey) key)
            .getParams()
            .getCurve()
            .getField()
            .getFieldSize());
    use(c -> {
      c.authenticate(key, "TTM000001", 2, 45, true);
      return null;
    });
    String other = hub.newCard();
    hub.eject(TERMINAL);
    hub.connect(TERMINAL, other);
    var wrong = use(CardClient::publicKey);
    hub.eject(TERMINAL);
    hub.connect(TERMINAL, alias);
    assertEquals(
        "CARD_AUTH",
        assertThrows(
                Failure.class,
                () ->
                    use(c -> {
                      c.authenticate(wrong, "TTM000001", 2, 45, true);
                      return null;
                    }))
            .code);
  }

  @Test
  void identityProofIncludesFreshChallengePurposeAndInvoice() throws Exception {
    init();
    byte[] payload =
        Wire.concat(security.randomBytes(32), java.nio.ByteBuffer.allocate(8).putLong(12).array());
    byte[] a =
        use(c -> {
          c.command(Wire.PROVE, 2, 0, payload);
          return c.command(0x14);
        });
    payload[0] ^= 1;
    byte[] b =
        use(c -> {
          c.command(Wire.PROVE, 2, 0, payload);
          return c.command(0x14);
        });
    assertFalse(Arrays.equals(a, b));
    assertEquals(2, a[9]);
    assertEquals(12, java.nio.ByteBuffer.wrap(a, 74, 8).getLong());
  }

  @Test
  void pinIsCheckedByCardWithoutPersistentCounter() throws Exception {
    init();
    use(c -> {
      c.unlock("TTM000001", share, false);
      for (int i = 0; i < 8; i++) assertFalse(c.verifyPin("000000"));
      assertTrue(c.verifyPin("123456"));
      return null;
    });
  }

  @Test
  void wrongBackendSecretIsCryptoFailureNotWrongPin() throws Exception {
    init();
    assertEquals(
        "CARD_CRYPTO",
        assertThrows(
                Failure.class,
                () ->
                    use(c -> {
                      c.unlock("TTM000001", security.randomBytes(32), false);
                      return null;
                    }))
            .code);
  }

  @Test
  void readsRequireKeysAndStudentBalanceRequiresPin() throws Exception {
    init();
    use(c -> {
      c.logout();
      assertThrows(Failure.class, c::profile);
      assertThrows(Failure.class, c::balance);
      c.unlock("TTM000001", share, true);
      assertNotNull(c.profile());
      assertThrows(Failure.class, c::balance);
      return null;
    });
    login("123456");
    assertEquals(0, use(CardClient::balance));
  }

  @Test
  void reconnectClearsAuthenticationButPreservesIdentityAndCiphertext() throws Exception {
    Profile p = init();
    login("123456");
    byte[] key = use(c -> c.publicKey().getEncoded()), hash = state().cipherHash();
    hub.eject(TERMINAL);
    hub = new ReaderHub(security, config);
    hub.connect(TERMINAL, alias);
    assertThrows(Failure.class, () -> use(CardClient::profile));
    assertArrayEquals(key, use(c -> c.publicKey().getEncoded()));
    use(c -> {
      c.authenticate(c.publicKey(), "TTM000001", 3, 0, true);
      return null;
    });
    assertArrayEquals(hash, state().cipherHash());
    login("123456");
    assertEquals(p, use(CardClient::profile));
  }

  @Test
  void staleVersionsAndInsufficientFundsCannotChangeActiveData() throws Exception {
    Profile p = init();
    login("123456");
    var before = state();
    byte[] req = Protocol.request(Wire.CHANGE_PIN, 0, 1, 9, 0, pin("654321"));
    assertThrows(
        Failure.class,
        () ->
            use(c -> {
              c.prepare(p.cardId(), 77, req, security.randomBytes(32), () -> {});
              return null;
            }));
    assertThrows(Failure.class, () -> prepare(Wire.DEBIT, Wire.operation(5, 1)));
    assertEquals(0, state().balance());
    assertArrayEquals(before.cipherHash(), state().cipherHash());
  }

  @Test
  void snapshotContainsNoBackendSharePinOrRuntimeKeys() throws Exception {
    Profile p = init();
    login("123456");
    byte[] bytes = Files.readAllBytes(config.root().resolve("cards/" + alias + ".card"));
    assertTrue(ReaderHub.SnapshotRuntime.isV3(bytes));
    assertEquals(-1, index(bytes, share));
    assertEquals(-1, index(bytes, pin("123456")));
    assertFalse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains(p.fullName()));
    assertFalse(ReaderHub.SnapshotRuntime.PERSISTENT.contains("root"));
    assertFalse(ReaderHub.SnapshotRuntime.PERSISTENT.contains("aes"));
    assertFalse(ReaderHub.SnapshotRuntime.PERSISTENT.contains("context"));
  }

  @Test
  void unsupportedLegacyIssuerAndRsaCommandsAreRemoved() throws Exception {
    init();
    assertThrows(Failure.class, () -> use(c -> c.command(1)));
    assertThrows(Failure.class, () -> use(c -> c.command(0x32)));
    assertThrows(Failure.class, () -> use(c -> c.command(0x35)));
  }

  @Test
  void unrecognizedSimulatorFilesArePreservedAndReported() throws Exception {
    hub.eject(TERMINAL);
    Path f = config.root().resolve("cards/SIM0002.card");
    Files.write(f, new byte[] {'T', 'T', 'M', 2, 1});
    Files.write(f.resolveSibling("SIM0002.card.v1.backup"), new byte[] {1});
    assertThrows(IllegalStateException.class, () -> new ReaderHub(security, config));
    assertArrayEquals(new byte[] {'T', 'T', 'M', 2, 1}, Files.readAllBytes(f));
    assertTrue(Files.exists(f.resolveSibling("SIM0002.card.v1.backup")));
  }

  private static int index(byte[] bytes, byte[] part) {
    outer:
    for (int i = 0; i <= bytes.length - part.length; i++) {
      for (int j = 0; j < part.length; j++) if (bytes[i + j] != part[j]) continue outer;
      return i;
    }
    return -1;
  }
}
