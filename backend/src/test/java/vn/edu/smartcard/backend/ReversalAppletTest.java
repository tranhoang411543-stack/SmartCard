package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import vn.edu.smartcard.shared.*;

class ReversalAppletTest extends CardTestFixture {
  private long debit() throws Exception {
    init();
    login("123456");
    mutate(Wire.CREDIT, Wire.operation(10, 100000));
    long id = prepare(Wire.DEBIT, Wire.operation(11, 35000));
    complete(Protocol.COMMIT, id);
    complete(Protocol.ACK, id);
    return id;
  }

  @Test
  void ackRetainsDebitUntilFinalOutcome() throws Exception {
    long id = debit();
    var s = state();
    assertEquals(65000, s.balance());
    assertEquals(1, s.financialHold());
    assertEquals(id, s.debitId());
    assertEquals(100000, s.debitBeforeBalance());
    assertEquals(35000, s.debitAmount());
    assertThrows(Failure.class, () -> prepare(Wire.CREDIT, Wire.operation(12, 1)));
    assertThrows(Failure.class, () -> use(c -> c.verifyPin("123456")));
    complete(Protocol.RELEASE, id);
    complete(Protocol.RELEASE, id);
    assertEquals(0, state().financialHold());
    login("123456");
    assertEquals(65000, use(CardClient::balance));
  }

  @Test
  void correctCardCanReverseAfterRestartWithoutNewPin() throws Exception {
    long original = debit();
    byte[] cipher = state().cipherHash();
    long beforeCounter = state().counter();
    hub.eject(TERMINAL);
    hub = new ReaderHub(security, config);
    hub.connect(TERMINAL, alias);
    assertEquals("NONE", hub.status(TERMINAL).role());
    assertEquals(1, state().financialHold());
    use(c -> {
      c.authenticate(c.publicKey(), "TTM000001", 3, original, true);
      return null;
    });
    long reversed = prepare(Wire.REVERSAL, Wire.operation(original, 35000));
    complete(Protocol.COMMIT, reversed);
    complete(Protocol.COMMIT, reversed);
    complete(Protocol.ACK, reversed);
    complete(Protocol.ACK, reversed);
    assertEquals(100000, state().balance());
    assertEquals(2, state().financialHold());
    assertEquals(beforeCounter + 1, state().counter());
    assertArrayEquals(cipher, state().cipherHash());
    assertThrows(Failure.class, () -> prepare(Wire.REVERSAL, Wire.operation(original, 35000)));
    complete(Protocol.RELEASE, original);
    complete(Protocol.RELEASE, original);
    login("123456");
    assertEquals(100000, use(CardClient::balance));
  }

  @Test
  void wrongParentOrAmountCannotCreateMoney() throws Exception {
    long original = debit();
    assertThrows(Failure.class, () -> prepare(Wire.REVERSAL, Wire.operation(original + 1, 35000)));
    assertThrows(Failure.class, () -> prepare(Wire.REVERSAL, Wire.operation(original, 35001)));
    assertEquals(65000, state().balance());
    assertEquals(1, state().financialHold());
    long reverse = prepare(Wire.REVERSAL, Wire.operation(original, 35000));
    complete(Protocol.ABORT, reverse);
    assertEquals(65000, state().balance());
    assertEquals(1, state().financialHold());
    reverse = prepare(Wire.REVERSAL, Wire.operation(original, 35000));
    complete(Protocol.COMMIT, reverse);
    complete(Protocol.ACK, reverse);
    assertEquals(100000, state().balance());
  }

  @Test
  void abortedDebitNeverCreatesFinancialHold() throws Exception {
    init();
    login("123456");
    mutate(Wire.CREDIT, Wire.operation(1, 50000));
    long op = prepare(Wire.DEBIT, Wire.operation(2, 35000));
    complete(Protocol.ABORT, op);
    assertEquals(0, state().financialHold());
    assertEquals(50000, state().balance());
  }

  @Test
  void legacySnapshotUpgradePreservesIdentityAndPin() throws Exception {
    var p = init();
    byte[] fingerprint = use(c -> Protocol.fingerprint(c.publicKey()));
    hub.eject(TERMINAL);
    var runtime = new ReaderHub.SnapshotRuntime();
    var aid = new javacard.framework.AID(Wire.AID, (short) 0, (byte) Wire.AID.length);
    runtime.restore(
        aid, java.nio.file.Files.readAllBytes(config.root().resolve("cards/" + alias + ".card")));
    var serializer = ReaderHub.SnapshotRuntime.class.getDeclaredMethod("serializer", boolean.class);
    serializer.setAccessible(true);
    serializer.invoke(runtime, true);
    byte[] old = runtime.snapshot(aid);
    old[3] = 3;
    java.nio.file.Files.write(config.root().resolve("cards/" + alias + ".card"), old);
    hub = new ReaderHub(security, config);
    hub.connect(TERMINAL, alias);
    assertArrayEquals(fingerprint, use(c -> Protocol.fingerprint(c.publicKey())));
    assertEquals(0, state().financialHold());
    login("123456");
    assertEquals(p, use(CardClient::profile));
  }
}
