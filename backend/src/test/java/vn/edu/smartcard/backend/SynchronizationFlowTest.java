package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import vn.edu.smartcard.shared.*;

/** Real PostgreSQL plus persistent jCardSim; failures are injected at durable boundaries. */
class SynchronizationFlowTest extends DatabaseFixture {
  private static final AtomicInteger SERIAL = new AtomicInteger();

  private Profile fresh() throws Exception {
    int n = SERIAL.incrementAndGet();
    return enroll(
        "CT3001" + String.format("%02d", n), String.format("%012d", 600000000000L + n), 2026);
  }

  private Protocol.State state() {
    return hub.use(TERMINAL, c -> c.state());
  }

  private void interrupt(String point) {
    cards.sync.fault =
        step -> {
          if (step.equals(point)) throw new AssertionError("injected process loss");
        };
  }

  private void restart() {
    String alias = hub.status(TERMINAL).alias();
    simulatePhysicalDisconnect();
    hub = new ReaderHub(security, config);
    cards = new CardService(hub, repo, security);
    wallet = new WalletService(cards, repo, bank, security);
    hub.connect(TERMINAL, alias);
    AccessContext.clear();
  }

  private void exactMirror(Profile profile) {
    Protocol.State actual = state();
    var mirror =
        repo.db.queryForMap("SELECT * FROM card_wallets WHERE card_id=?", profile.cardId());
    assertEquals(actual.balance(), ((Number) mirror.get("balance")).longValue());
    assertEquals(actual.counter(), ((Number) mirror.get("state_counter")).longValue());
    assertEquals(actual.walletVersion(), ((Number) mirror.get("wallet_version")).longValue());
    byte[] blob =
        repo.db.queryForObject(
            "SELECT cipher_blob FROM card_cipher_snapshots WHERE card_id=?",
            byte[].class,
            profile.cardId());
    assertArrayEquals(actual.cipherHash(), Wire.hash(blob));
    assertArrayEquals(blob, hub.use(TERMINAL, c -> c.cipher(actual, false)));
    assertEquals("SYNCED", cards.sync.status(profile.cardId()).phase());
  }

  @ParameterizedTest
  @ValueSource(strings = {"CARD_PREPARED", "COMMIT_DECIDED", "ACK"})
  void interruptedEnrollmentFindsReservedCardAndRecoversWithoutPin(String point) throws Exception {
    int n = SERIAL.incrementAndGet();
    hub.connect(TERMINAL, hub.newCard());
    Profile input =
        profile(
            "CT3001" + String.format("%02d", n), String.format("%012d", 600000000000L + n), 2026);
    long actor = admin().id();
    interrupt(point);
    assertThrows(
        AssertionError.class,
        () -> cards.initialize(TERMINAL, new Api.Enrollment(input, "123456"), actor));
    assertNotEquals("UNREGISTERED", cards.syncStatus(TERMINAL).phase());
    restart();
    cards.reconcile(TERMINAL);
    boolean applied = !point.equals("CARD_PREPARED");
    assertEquals(applied, hub.status(TERMINAL).initialized());
    if (!applied) cards.initialize(TERMINAL, new Api.Enrollment(input, "654321"), admin().id());
    Profile actual = cards.login(TERMINAL, applied ? "123456" : "654321").profile();
    assertEquals(input.studentCode(), actual.studentCode());
    assertEquals(1, actual.version());
    exactMirror(actual);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CREATED",
        "CARD_PREPARED",
        "COMMIT_DECIDED",
        "CARD_COMMITTED",
        "DB_COMMITTED",
        "ACK"
      })
  void resetPinRecoversAcrossEveryBoundaryWithoutLosingData(String point) throws Exception {
    Profile p = fresh();
    byte[] old = state().cipherHash();
    interrupt(point);
    assertThrows(AssertionError.class, () -> cards.resetPin(TERMINAL, "654321", admin().id()));
    assertNotEquals("SYNCED", cards.sync.status(p.cardId()).phase());
    restart();
    cards.reconcile(TERMINAL);
    boolean applied = !Set.of("CREATED", "CARD_PREPARED").contains(point);
    assertEquals("SYNCED", cards.sync.status(p.cardId()).phase());
    cards.reconcile(TERMINAL);
    assertEquals(0, state().phase());
    assertEquals(applied ? 2 : 1, state().cryptoVersion());
    assertEquals(!applied, Arrays.equals(old, state().cipherHash()));
    assertEquals(p, cards.login(TERMINAL, applied ? "654321" : "123456").profile());
    exactMirror(p);
    assertEquals(
        1,
        repo.db.queryForObject(
            "SELECT count(*) FROM card_operations WHERE card_id=? AND kind=3 AND phase=?",
            Integer.class,
            p.cardId(),
            applied ? "SYNCED" : "ABORTED"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"CARD_PREPARED", "COMMIT_DECIDED", "DB_COMMITTED", "ACK"})
  void profileUpdateRecoversTogetherWithSearchableProfileAndCipher(String point) throws Exception {
    Profile p = fresh();
    Profile edited =
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
            p.version());
    interrupt(point);
    assertThrows(AssertionError.class, () -> cards.update(TERMINAL, edited, admin().id()));
    restart();
    cards.reconcile(TERMINAL);
    boolean applied = !point.equals("CARD_PREPARED");
    Profile actual = cards.login(TERMINAL, "123456").profile();
    assertEquals(applied ? "Lê Thị Bình" : p.fullName(), actual.fullName());
    assertEquals(actual, repo.find(p.cardId()).current());
    assertEquals(p.photoBase64(), actual.photoBase64());
    exactMirror(actual);
  }

  @ParameterizedTest
  @CsvSource({
    "CREDIT,CREATED",
    "CREDIT,CARD_PREPARED",
    "CREDIT,COMMIT_DECIDED",
    "CREDIT,CARD_COMMITTED",
    "CREDIT,DB_COMMITTED",
    "CREDIT,ACK",
    "DEBIT,CREATED",
    "DEBIT,CARD_PREPARED",
    "DEBIT,COMMIT_DECIDED",
    "DEBIT,CARD_COMMITTED",
    "DEBIT,DB_COMMITTED",
    "DEBIT,ACK"
  })
  void moneyRecoveryNeverCreditsOrDebitsTwice(String kind, String point) throws Exception {
    Profile p = fresh();
    cards.login(TERMINAL, "123456");
    Api.Topup funds = wallet.createTopup(TERMINAL, 100000);
    wallet.bankEvent(bank.event("seed-" + p.cardId(), funds.reference(), 100000));
    wallet.apply(TERMINAL, funds.id());
    cards.login(TERMINAL, "123456");
    boolean credit = kind.equals("CREDIT");
    Api.Topup topup = credit ? wallet.createTopup(TERMINAL, 35000) : null;
    Api.Invoice invoice =
        credit
            ? null
            : wallet.createInvoice(
                new Api.CreateInvoice(p.studentCode(), List.of(new Api.OrderItem(1, 1))), TERMINAL);
    if (credit) wallet.bankEvent(bank.event("fault-" + p.cardId(), topup.reference(), 35000));
    interrupt(point);
    assertThrows(
        AssertionError.class,
        () -> {
          if (credit) wallet.apply(TERMINAL, topup.id());
          else wallet.pay(TERMINAL, invoice.id(), "123456");
        });
    restart();
    cards.reconcile(TERMINAL);
    cards.reconcile(TERMINAL);
    boolean applied = !Set.of("CREATED", "CARD_PREPARED").contains(point);
    long expected = 100000 + (applied && credit ? 35000 : 0);
    assertEquals(expected, cards.login(TERMINAL, "123456").balance());
    exactMirror(p);
    if (credit) {
      assertEquals(applied ? "CREDITED" : "BANK_RECEIVED", wallet.topup(topup.id()).status());
      if (applied) wallet.apply(TERMINAL, topup.id());
    } else {
      assertEquals("CANCELLED", wallet.invoice(invoice.id()).status());
    }
    assertEquals(expected, cards.home(TERMINAL).balance());
  }

  @Test
  void stagedProfileWithoutJournalIsSafelyDiscarded() throws Exception {
    Profile p = fresh();
    repo.stage(
        new Profile(
            p.cardId(),
            p.studentCode(),
            "Hồ Thị An",
            p.citizenId(),
            p.birthDate(),
            p.className(),
            p.phone(),
            p.email(),
            p.intakeYear(),
            p.photoBase64(),
            p.version()));
    restart();
    cards.reconcile(TERMINAL);
    assertNull(repo.find(p.cardId()).pending());
    assertEquals(p, cards.login(TERMINAL, "123456").profile());
    exactMirror(p);
  }

  @Test
  void mirrorMismatchStopsNewOperationsAndDoesNotOverwriteCard() throws Exception {
    Profile p = fresh();
    repo.db.update("UPDATE card_wallets SET balance=123 WHERE card_id=?", p.cardId());
    Failure error = assertThrows(Failure.class, () -> cards.reconcile(TERMINAL));
    assertEquals("SYNC_CONFLICT", error.code);
    assertEquals(0, state().balance());
    assertEquals("CONFLICT", cards.sync.status(p.cardId()).phase());
    assertThrows(Failure.class, () -> cards.login(TERMINAL, "123456"));
  }

  @Test
  void tamperedCipherCannotBeAcceptedAsSynchronized() throws Exception {
    Profile p = fresh();
    repo.db.update(
        "UPDATE card_cipher_snapshots SET cipher_blob=? WHERE card_id=?",
        new byte[] {1, 2, 3},
        p.cardId());
    assertEquals(
        "SYNC_CONFLICT", assertThrows(Failure.class, () -> cards.reconcile(TERMINAL)).code);
    assertEquals(
        p,
        hub.use(
            TERMINAL,
            c -> {
              c.unlock(p.cardId(), repo.share(p.cardId()), true);
              return c.profile();
            }));
  }

  @Test
  void corruptedCandidateIsRejectedBeforeCommit() throws Exception {
    Profile p = fresh();
    interrupt("COMMIT_DECIDED");
    assertThrows(AssertionError.class, () -> cards.resetPin(TERMINAL, "654321", admin().id()));
    repo.db.update(
        "UPDATE card_operations SET candidate_blob=? WHERE card_id=? AND phase='COMMIT_DECIDED'",
        new byte[] {1, 2, 3},
        p.cardId());
    cards.sync.fault = step -> {};
    assertEquals(
        "SYNC_CONFLICT", assertThrows(Failure.class, () -> cards.reconcile(TERMINAL)).code);
    assertEquals(1, state().phase());
    assertEquals(1, state().cryptoVersion());
    assertEquals("CONFLICT", cards.sync.status(p.cardId()).phase());
  }

  @Test
  void adminAuthorizationIsRequiredAtServiceBoundaryAndCanExpireMidOperation() throws Exception {
    Profile p = fresh();
    AccessContext.clear();
    assertThrows(Failure.class, () -> cards.adminCard(TERMINAL));
    AtomicInteger checks = new AtomicInteger();
    AccessContext.admin(() -> {
      if (checks.incrementAndGet() > 4) throw new Failure(401, "ADMIN_EXPIRED", "fixture expired");
    });
    assertEquals(
        "ADMIN_EXPIRED",
        assertThrows(Failure.class, () -> cards.resetPin(TERMINAL, "654321", 1)).code);
    AccessContext.clear();
    cards.reconcile(TERMINAL);
    assertEquals(p, cards.login(TERMINAL, "123456").profile());
    assertEquals(1, state().cryptoVersion());
  }
}
