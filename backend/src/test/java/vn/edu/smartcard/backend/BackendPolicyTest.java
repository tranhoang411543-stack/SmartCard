package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import vn.edu.smartcard.shared.*;

/** V3 policy against PostgreSQL and the real applet, including decision races. */
class BackendPolicyTest extends DatabaseFixture {
  private static final AtomicInteger SERIAL = new AtomicInteger();

  private Profile fresh() throws Exception {
    int n = SERIAL.incrementAndGet();
    return enroll(
        "AT4001" + String.format("%02d", n), String.format("%012d", 700000000000L + n), 2026);
  }

  private Protocol.State state() {
    return hub.use(TERMINAL, CardClient::state);
  }

  private void credit(Profile p, long amount) {
    cards.login(TERMINAL, "123456");
    var t = wallet.createTopup(TERMINAL, amount);
    wallet.bankEvent(bank.event(UUID.randomUUID().toString(), t.reference(), amount));
    wallet.apply(TERMINAL, t.id());
  }

  private Api.Invoice invoice(Profile p) {
    return wallet.createInvoice(
        new Api.CreateInvoice(p.studentCode(), List.of(new Api.OrderItem(1, 1))), TERMINAL);
  }

  private CardClient.Transport transport(CardClient c) throws Exception {
    var f = CardClient.class.getDeclaredField("transport");
    f.setAccessible(true);
    return (CardClient.Transport) f.get(c);
  }

  @Test
  void lostPinResponseNeverGrantsSessionOrGuessesFailureCount() throws Exception {
    Profile p = fresh();
    cards.prepareStudent(TERMINAL);
    CardClient actual = hub.use(TERMINAL, c -> c);
    var transport = transport(actual);
    CardClient interrupted =
        new CardClient(
            command -> {
              byte[] reply = transport.exchange(command);
              if ((command[1] & 255) == Wire.VERIFY_PIN)
                throw new Failure(409, "CARD_REMOVED", "injected lost response");
              return reply;
            });
    Api.Pin attempt = new Api.Pin("123456");
    assertEquals(
        "CARD_REMOVED",
        assertThrows(
                Failure.class,
                () ->
                    cards.verifyStudentPin(
                        TERMINAL, interrupted, repo.find(p.cardId()), attempt, "MANAGEMENT"))
            .code);
    assertEquals(0, repo.lifecycle(p.cardId()).pinFailures());
    assertEquals(
        "UNKNOWN",
        repo.db.queryForObject(
            "SELECT result FROM pin_attempts WHERE attempt_id=?::uuid",
            String.class,
            attempt.attemptId()));
    assertThrows(Failure.class, () -> cards.home(TERMINAL));
    assertEquals("NONE", hub.status(TERMINAL).role());
  }

  @Test
  void concurrentAdminBlockWinsOverCorrectPinResult() throws Exception {
    Profile p = fresh();
    repo.db.update("UPDATE cards SET pin_failures=2 WHERE card_id=?", p.cardId());
    cards.prepareStudent(TERMINAL);
    long actor = admin().id();
    var transport = transport(hub.use(TERMINAL, c -> c));
    CardClient racing =
        new CardClient(
            command -> {
              byte[] reply = transport.exchange(command);
              if ((command[1] & 255) == Wire.VERIFY_PIN) cards.block(p.cardId(), actor);
              return reply;
            });
    assertEquals(
        "CARD_BLOCKED",
        assertThrows(
                Failure.class,
                () ->
                    cards.verifyStudentPin(
                        TERMINAL,
                        racing,
                        repo.find(p.cardId()),
                        new Api.Pin("123456"),
                        "MANAGEMENT"))
            .code);
    assertEquals(2, repo.lifecycle(p.cardId()).pinFailures());
    assertEquals("BLOCKED", repo.lifecycle(p.cardId()).status());
    assertEquals("NONE", hub.status(TERMINAL).role());
  }

  @Test
  void eccFailureNeverReplacesRegisteredKeyOrCountsAsWrongPin() throws Exception {
    Profile p = fresh();
    cards.login(TERMINAL, "123456");
    String original = repo.find(p.cardId()).publicKey();
    var generator = java.security.KeyPairGenerator.getInstance("EC");
    generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
    String changed =
        Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
    repo.db.update("UPDATE cards SET public_key=? WHERE card_id=?", changed, p.cardId());
    assertEquals(
        "CARD_AUTH", assertThrows(Failure.class, () -> cards.login(TERMINAL, "123456")).code);
    assertEquals(changed, repo.find(p.cardId()).publicKey());
    assertEquals(0, repo.lifecycle(p.cardId()).pinFailures());
    repo.db.update("UPDATE cards SET public_key=? WHERE card_id=?", original, p.cardId());
  }

  @Test
  void fifthWrongPinBlocksBothStationsAndPersistsAcrossRestart() throws Exception {
    Profile p = fresh();
    var invoice = invoice(p);
    String alias = hub.status(TERMINAL).alias();
    for (int i = 1; i <= 5; i++) {
      var failure = assertThrows(Failure.class, () -> cards.login(TERMINAL, "000000"));
      assertEquals(i == 5 ? "CARD_BLOCKED" : "PIN_WRONG", failure.code);
      assertEquals(i, repo.lifecycle(p.cardId()).pinFailures());
    }
    assertEquals(
        "CARD_BLOCKED",
        assertThrows(Failure.class, () -> wallet.preparePayment(TERMINAL, invoice.id())).code);
    hub.eject(TERMINAL);
    hub = new ReaderHub(security, config);
    cards = new CardService(hub, repo, security);
    wallet = new WalletService(cards, repo, bank, security);
    hub.connect(TERMINAL, alias);
    assertEquals("BLOCKED", hub.status(TERMINAL).lifecycleStatus());
    assertEquals("CARD_BLOCKED", assertThrows(Failure.class, () -> cards.home(TERMINAL)).code);
    assertEquals(0, hub.status(TERMINAL).tries());
    assertEquals(
        CardRepository.BLOCKED_MESSAGE,
        assertThrows(Failure.class, () -> cards.prepareStudent(TERMINAL)).getMessage());
    assertEquals(
        "STATION_EXPIRED",
        assertThrows(Failure.class, () -> wallet.preparePayment(TERMINAL, invoice.id())).code);
    assertEquals(
        "CARD_BLOCKED", assertThrows(Failure.class, () -> cards.login(TERMINAL, "123456")).code);
  }

  @Test
  void correctPinResetsFailuresButNotAnAdminBlock() throws Exception {
    Profile p = fresh();
    assertThrows(Failure.class, () -> cards.login(TERMINAL, "000000"));
    cards.login(TERMINAL, "123456");
    assertEquals(0, repo.lifecycle(p.cardId()).pinFailures());
    cards.block(p.cardId(), admin().id());
    assertEquals(
        "CARD_BLOCKED", assertThrows(Failure.class, () -> cards.login(TERMINAL, "123456")).code);
    assertEquals("NONE", hub.status(TERMINAL).role());
    cards.unblock(TERMINAL, admin().id());
    assertEquals(0, repo.lifecycle(p.cardId()).pinFailures());
    assertEquals(p, cards.login(TERMINAL, "123456").profile());
  }

  @Test
  void duplicatePinAttemptIsCountedOnce() throws Exception {
    Profile p = fresh();
    var attempt = new Api.Pin("000000");
    assertThrows(Failure.class, () -> cards.login(TERMINAL, attempt));
    assertThrows(Failure.class, () -> cards.login(TERMINAL, attempt));
    assertEquals(1, repo.lifecycle(p.cardId()).pinFailures());
    assertEquals(
        1,
        repo.db.queryForObject(
            "SELECT count(*) FROM pin_attempts WHERE card_id=?", Integer.class, p.cardId()));
  }

  @Test
  void invalidPinFormatAndCryptoFailureDoNotCountAsWrongPin() throws Exception {
    Profile p = fresh();
    assertThrows(IllegalArgumentException.class, () -> cards.login(TERMINAL, "123"));
    byte[] correct = repo.share(p.cardId());
    repo.db.update(
        "UPDATE card_secrets SET backend_share=? WHERE card_id=?",
        security.randomBytes(32),
        p.cardId());
    // A new connection reloads S_backend; the previous prepared context still has its old keys.
    String alias = hub.status(TERMINAL).alias();
    hub.eject(TERMINAL);
    hub.connect(TERMINAL, alias);
    assertEquals(
        "CARD_CRYPTO", assertThrows(Failure.class, () -> cards.login(TERMINAL, "123456")).code);
    repo.db.update("UPDATE card_secrets SET backend_share=? WHERE card_id=?", correct, p.cardId());
    assertEquals(0, repo.lifecycle(p.cardId()).pinFailures());
    // Unlock rejects the wrong backend share before VERIFY_PIN starts an attempt.
    assertEquals(
        0,
        repo.db.queryForObject(
            "SELECT count(*) FROM pin_attempts WHERE card_id=?", Integer.class, p.cardId()));
  }

  @Test
  void profileIsPlaintextInDatabaseAndSharesAreUniquePerCard() throws Exception {
    Profile p = fresh();
    byte[] one = repo.share(p.cardId());
    Profile other = fresh();
    assertFalse(Arrays.equals(one, repo.share(other.cardId())));
    assertTrue(
        repo.db
            .queryForObject(
                "SELECT profile_json FROM cards WHERE card_id=?", String.class, p.cardId())
            .contains(p.fullName()));
    assertEquals(p, repo.find(p.cardId()).current());
  }

  @Test
  void pinChangeKeepsSessionAndDoesNotChangeRootsOrIdentity() throws Exception {
    Profile p = fresh();
    cards.login(TERMINAL, "123456");
    byte[] share = repo.share(p.cardId());
    String pub = repo.find(p.cardId()).publicKey();
    byte[] cipher = state().cipherHash();
    cards.changePin(TERMINAL, new Api.PinChange("654321"));
    assertEquals(p, cards.home(TERMINAL).profile());
    assertArrayEquals(share, repo.share(p.cardId()));
    assertEquals(pub, repo.find(p.cardId()).publicKey());
    assertFalse(Arrays.equals(cipher, state().cipherHash()));
    assertEquals(
        1,
        repo.db.queryForObject(
            "SELECT count(*) FROM pin_attempts WHERE card_id=?", Integer.class, p.cardId()));
    assertThrows(Failure.class, () -> cards.login(TERMINAL, "123456"));
    assertEquals(p, cards.login(TERMINAL, "654321").profile());
  }

  @Test
  void managementCanCreditTwiceWithoutAnotherPin() throws Exception {
    Profile p = fresh();
    credit(p, 50000);
    var second = wallet.createTopup(TERMINAL, 50000);
    wallet.bankEvent(bank.event("second-" + p.cardId(), second.reference(), 50000));
    wallet.apply(TERMINAL, second.id());
    assertEquals(100000, cards.home(TERMINAL).balance());
    assertEquals(
        1,
        repo.db.queryForObject(
            "SELECT count(*) FROM pin_attempts WHERE card_id=?", Integer.class, p.cardId()));
  }

  @Test
  void expiredInvoiceCancelsWithoutChargingOrPinAttempt() throws Exception {
    Profile p = fresh();
    credit(p, 100000);
    var i = invoice(p);
    repo.db.update("UPDATE invoices SET expires_at=now()-interval '1 second' WHERE id=?", i.id());
    assertEquals(
        "QR_EXPIRED",
        assertThrows(Failure.class, () -> wallet.pay(TERMINAL, i.id(), "123456")).code);
    assertEquals("CANCELLED", wallet.invoice(i.id()).status());
    assertEquals("QR_EXPIRED", wallet.invoice(i.id()).cancelReason());
    assertEquals(100000, state().balance());
  }

  @Test
  void expiryBeforeCommitAbortsBothMirrors() throws Exception {
    Profile p = fresh();
    credit(p, 100000);
    var i = invoice(p);
    cards.sync.fault =
        step -> {
          if (step.equals("CARD_PREPARED"))
            repo.db.update(
                "UPDATE invoices SET expires_at=now()-interval '1 second' WHERE id=?", i.id());
        };
    assertThrows(Failure.class, () -> wallet.pay(TERMINAL, i.id(), "123456"));
    assertEquals(100000, state().balance());
    assertEquals(0, state().phase());
    assertEquals("CANCELLED", wallet.invoice(i.id()).status());
    assertEquals(
        100000,
        repo.db.queryForObject(
            "SELECT balance FROM card_wallets WHERE card_id=?", Long.class, p.cardId()));
  }

  @Test
  void expiryAfterCommitDecisionFinishesExactlyOnce() throws Exception {
    Profile p = fresh();
    credit(p, 100000);
    var i = invoice(p);
    cards.sync.fault =
        step -> {
          if (step.equals("COMMIT_DECIDED")) {
            repo.db.update(
                "UPDATE invoices SET expires_at=now()-interval '1 second' WHERE id=?", i.id());
            wallet.expireInvoices();
            assertEquals("PAYMENT_PENDING", wallet.invoice(i.id()).status());
          }
        };
    assertEquals("PAID", wallet.pay(TERMINAL, i.id(), "123456").status());
    assertEquals("PAID", wallet.pay(TERMINAL, i.id(), "123456").status());
    assertEquals(65000, state().balance());
    assertEquals("NONE", hub.status(TERMINAL).role());
  }

  @Test
  void sameConnectionCanPayNextInvoiceOnlyWithFreshAuthenticationAndPin() throws Exception {
    Profile p = fresh();
    credit(p, 100000);
    String connection = hub.status(TERMINAL).connectionId();
    assertEquals("PAID", wallet.pay(TERMINAL, invoice(p).id(), "123456").status());
    assertEquals("NONE", hub.status(TERMINAL).role());
    var next = invoice(p);
    wallet.preparePayment(TERMINAL, next.id());
    assertTrue(hub.paymentPrepared(TERMINAL, next.id()));
    assertEquals("NONE", hub.status(TERMINAL).role());
    assertThrows(IllegalArgumentException.class, () -> wallet.pay(TERMINAL, next.id(), ""));
    assertEquals("PAID", wallet.pay(TERMINAL, next.id(), "123456").status());
    assertEquals(connection, hub.status(TERMINAL).connectionId());
    assertEquals(30000, state().balance());
  }

  @Test
  void blockBeforeCommitAbortsAndBlockAfterDecisionFinishes() throws Exception {
    Profile p = fresh();
    credit(p, 100000);
    var first = invoice(p);
    long actor = admin().id();
    cards.sync.fault =
        step -> {
          if (step.equals("CARD_PREPARED")) cards.block(p.cardId(), actor);
        };
    assertThrows(Failure.class, () -> wallet.pay(TERMINAL, first.id(), "123456"));
    assertEquals(100000, state().balance());
    cards.sync.fault = step -> {};
    cards.unblock(TERMINAL, actor);
    String reconnectAlias = hub.status(TERMINAL).alias();
    hub.eject(TERMINAL);
    hub.connect(TERMINAL, reconnectAlias);
    cards.sync.fault =
        step -> {
          if (step.equals("COMMIT_DECIDED")) cards.block(p.cardId(), actor);
        };
    assertEquals("PAID", wallet.pay(TERMINAL, invoice(p).id(), "123456").status());
    assertEquals(65000, state().balance());
    assertEquals("BLOCKED", repo.lifecycle(p.cardId()).status());
    assertEquals(
        "CARD_BLOCKED", assertThrows(Failure.class, () -> cards.login(TERMINAL, "123456")).code);
  }

  @Test
  void hardDeleteRequiresBlockAndBalanceConfirmationThenRemovesOnlyThatCard() throws Exception {
    Profile survivor = fresh();
    Profile p = fresh();
    credit(p, 50000);
    String alias = hub.status(TERMINAL).alias();
    assertThrows(Failure.class, () -> cards.deletePreview(TERMINAL));
    long actor = admin().id();
    cards.block(p.cardId(), actor);
    var preview = cards.deletePreview(TERMINAL);
    assertTrue(preview.hasBalance());
    assertEquals(
        "BALANCE_CONFIRM",
        assertThrows(
                Failure.class,
                () ->
                    cards.delete(
                        TERMINAL,
                        new Api.DeleteCard(
                            p.cardId(),
                            false,
                            preview.counter(),
                            preview.walletVersion(),
                            false,
                            preview.previewToken()),
                        actor))
            .code);
    cards.delete(
        TERMINAL,
        new Api.DeleteCard(
            p.cardId(),
            true,
            preview.counter(),
            preview.walletVersion(),
            false,
            preview.previewToken()),
        actor);
    assertNull(repo.find(p.cardId()));
    assertNotNull(repo.find(survivor.cardId()));
    for (String table :
        List.of(
            "card_secrets",
            "pin_attempts",
            "card_wallets",
            "card_operations",
            "wallet_operations",
            "invoices",
            "topups",
            "bank_events",
            "audit_log",
            "card_cipher_snapshots"))
      assertEquals(
          0,
          repo.db.queryForObject(
              "SELECT count(*) FROM " + table + " WHERE card_id=?", Integer.class, p.cardId()));
    assertFalse(Files.exists(config.root().resolve("cards/" + alias + ".card")));
    assertThrows(Failure.class, () -> cards.unblock(TERMINAL, actor));
  }

  @Test
  void invoiceUsesFullStudentIdentityInBankMemo() throws Exception {
    Profile p = fresh();
    bank.fullQrCalls = 0;
    var request = new Api.CreateInvoice(p.studentCode(), List.of(new Api.OrderItem(1, 1)));
    var i = wallet.createInvoice(request, TERMINAL);
    assertEquals(
        300000,
        i.expiresAt()
            - repo.db
                .queryForObject(
                    "SELECT created_at FROM invoices WHERE id=?", java.sql.Timestamp.class, i.id())
                .getTime(),
        1000);
    assertTrue(i.paymentReference().matches("TT(AT|CT|DT)[0-9]{14}"));
    assertEquals(1, bank.fullQrCalls);
    assertEquals(i.id(), wallet.createInvoice(request, TERMINAL).id());
    assertEquals(1, bank.fullQrCalls, "Retrying invoice creation should reuse the cached QR");
    assertEquals(i.total(), bank.qrAmount);
    assertEquals(i.paymentReference(), bank.qrReference);
    assertEquals(SePayGateway.invoiceCode(p.studentCode(), i.id()), i.paymentReference());
    assertTrue(i.qrDataUrl().startsWith("data:image/png;base64,"));
    repo.db.update("UPDATE invoice_payment_references SET qr_data_url=? WHERE invoice_id=?",
        "data:image/png;base64,old-image", i.id());
    Api.Invoice refreshed = wallet.refreshQr(TERMINAL, i.id());
    assertEquals(i.paymentReference(), refreshed.paymentReference());
    assertEquals(i.total(), refreshed.total());
    assertEquals(i.expiresAt(), refreshed.expiresAt());
    assertEquals(i.qrDataUrl(), refreshed.qrDataUrl());
    assertEquals(2, bank.fullQrCalls, "An explicit reload must replace the old QR image");
    bank.failFullQr = true;
    try {
      Api.Invoice unavailable = wallet.refreshQr(TERMINAL, i.id());
      assertEquals(refreshed.qrDataUrl(), unavailable.qrDataUrl());
      assertEquals(refreshed.paymentReference(), unavailable.paymentReference());
    } finally {
      bank.failFullQr = false;
    }
  }
}
