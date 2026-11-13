package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import vn.edu.smartcard.shared.*;

class ProtocolFlowTest extends CardTestFixture {
  @Test
  void initializationIsInvisibleBeforeCommit() throws Exception {
    Profile p = profile();
    admin();
    long op = prepare(Wire.INIT, Wire.concat(pin("123456"), ProfileCodec.encode(p)));
    var staged = state();
    assertEquals(1, staged.phase());
    assertFalse(hub.status(TERMINAL).initialized());
    assertArrayEquals(staged.nextCipherHash(), Wire.hash(use(c -> c.cipher(staged, true))));
    complete(Protocol.ABORT, op);
    assertFalse(hub.status(TERMINAL).initialized());
    assertEquals(0, state().phase());
  }

  @Test
  void commitAndAckAreIdempotent() throws Exception {
    init();
    login("123456");
    long op = prepare(Wire.CREDIT, Wire.operation(8, 100000));
    complete(Protocol.COMMIT, op);
    complete(Protocol.COMMIT, op);
    complete(Protocol.ACK, op);
    complete(Protocol.ACK, op);
    assertEquals(100000, state().balance());
    assertEquals(1, state().walletVersion());
  }

  @Test
  void pinChangeReencryptsAndKeepsManagementSession() throws Exception {
    Profile p = init();
    login("123456");
    byte[] old = state().cipherHash();
    mutate(Wire.CHANGE_PIN, pin("654321"));
    assertEquals("STUDENT", hub.status(TERMINAL).role());
    assertEquals(p, use(CardClient::profile));
    assertEquals(2, state().cryptoVersion());
    assertFalse(Arrays.equals(old, state().cipherHash()));
    assertFalse(use(c -> c.verifyPin("123456")).booleanValue());
    login("654321");
    assertEquals(p, use(CardClient::profile));
  }

  @Test
  void adminResetNeedsNoOldPinAndPreservesFunds() throws Exception {
    Profile p = init();
    login("123456");
    mutate(Wire.CREDIT, Wire.operation(6, 75000));
    admin();
    mutate(Wire.RESET_PIN, pin("654321"));
    login("654321");
    assertEquals(p, use(CardClient::profile));
    assertEquals(75000, use(CardClient::balance));
  }

  @Test
  void stagedPinChangeCanBeAborted() throws Exception {
    Profile p = init();
    login("123456");
    byte[] old = state().cipherHash();
    long op = prepare(Wire.CHANGE_PIN, pin("654321"));
    complete(Protocol.ABORT, op);
    assertArrayEquals(old, state().cipherHash());
    login("123456");
    assertEquals(p, use(CardClient::profile));
  }

  @Test
  void pendingSurvivesRestartButKeysDoNot() throws Exception {
    init();
    login("123456");
    long op = prepare(Wire.CREDIT, Wire.operation(6, 50000));
    hub.eject(TERMINAL);
    hub = new ReaderHub(security, config);
    hub.connect(TERMINAL, alias);
    assertEquals(1, state().phase());
    assertEquals("NONE", hub.status(TERMINAL).role());
    assertThrows(Failure.class, () -> use(CardClient::profile));
    complete(Protocol.COMMIT, op);
    complete(Protocol.ACK, op);
    login("123456");
    assertEquals(50000, use(CardClient::balance));
  }

  @Test
  void profileEditKeepsPinAndBalance() throws Exception {
    Profile p = init();
    login("123456");
    mutate(Wire.CREDIT, Wire.operation(6, 80000));
    admin();
    Profile next =
        new Profile(
            p.cardId(),
            p.studentCode(),
            "Lê Thị Bình",
            p.citizenId(),
            p.birthDate(),
            p.className(),
            p.phone(),
            p.email(),
            p.intakeYear(),
            p.photoBase64(),
            2);
    mutate(Wire.UPDATE, ProfileCodec.encode(next));
    login("123456");
    assertEquals(next, use(CardClient::profile));
    assertEquals(80000, use(CardClient::balance));
  }
}
