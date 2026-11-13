package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.smartcard.shared.Api;
import vn.edu.smartcard.shared.Profile;

/** Payment retries use the real PostgreSQL fixture and jCardSim card. */
class PaymentRetryPolicyTest extends DatabaseFixture {
  private static final AtomicInteger SERIAL = new AtomicInteger();

  private Profile fresh() throws Exception {
    int n = SERIAL.incrementAndGet();
    return enroll("AT8701" + String.format("%02d", n),
        String.format("%012d", 870000000000L + n), 2026);
  }

  private void fund(long amount) {
    cards.login(TERMINAL, "123456");
    var topup = wallet.createTopup(TERMINAL, amount);
    wallet.bankEvent(bank.event(UUID.randomUUID().toString(), topup.reference(), amount));
    wallet.apply(TERMINAL, topup.id());
  }

  private Api.Invoice guest() {
    return wallet.createInvoice(new Api.CreateInvoice(List.of(new Api.OrderItem(1, 1))), TERMINAL);
  }

  private void receive(Api.Invoice invoice, long amount) {
    String id = UUID.randomUUID().toString();
    wallet.invoiceBank.receive(new SePayGateway.Notice("fixture:" + id, "bank:" + id,
        invoice.paymentReference(), amount, Instant.now(), invoice.paymentReference(),
        "000000000000", false));
  }

  private long balance() {
    return hub.use(TERMINAL, CardClient::state).balance();
  }

  @Test
  void wrongPinCanBeRetriedOnSameInvoiceWithoutCountingAReplayTwice() throws Exception {
    Profile card = fresh();
    fund(100_000);
    Api.Invoice invoice = guest();
    wallet.preparePayment(TERMINAL, invoice.id());
    Api.Pin wrong = new Api.Pin("000000");
    assertEquals("PIN_WRONG", assertThrows(Failure.class,
        () -> wallet.pay(TERMINAL, invoice.id(), wrong)).code);
    assertEquals("PIN_WRONG", assertThrows(Failure.class,
        () -> wallet.pay(TERMINAL, invoice.id(), wrong)).code);
    assertEquals(1, repo.lifecycle(card.cardId()).pinFailures());
    assertEquals("PAYMENT_PENDING", wallet.invoice(invoice.id()).status());
    assertEquals("CARD_AUTH", wallet.invoice(invoice.id()).paymentPhase());
    assertEquals("PAID", wallet.pay(TERMINAL, invoice.id(), "123456").status());
    assertEquals(65_000, balance());
    assertEquals(0, repo.lifecycle(card.cardId()).pinFailures());
    assertEquals(1, repo.db.queryForObject("SELECT count(*) FROM wallet_operations"
        + " WHERE invoice_id=? AND kind='DEBIT' AND status='APPLIED'", Integer.class, invoice.id()));
    assertEquals("PAID", wallet.pay(TERMINAL, invoice.id(), "123456").status());
    assertEquals(65_000, balance());
  }

  @Test
  void simultaneousRetriesCannotLogOutOrDebitTheOtherAttempt() throws Exception {
    fresh();
    fund(100_000);
    Api.Invoice invoice = guest();
    wallet.preparePayment(TERMINAL, invoice.id());
    CountDownLatch firstStarted = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
    AtomicBoolean once = new AtomicBoolean();
    cards.sync.fault = step -> {
      if (step.equals("PAYMENT_PIN_STARTED") && once.compareAndSet(false, true)) {
        firstStarted.countDown();
        try {
          if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
    };
    try (var pool = Executors.newFixedThreadPool(2)) {
      var wrong = pool.submit(() -> assertThrows(Failure.class,
          () -> wallet.pay(TERMINAL, invoice.id(), "000000")).code);
      assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
      var correct = pool.submit(() -> wallet.pay(TERMINAL, invoice.id(), "123456"));
      releaseFirst.countDown();
      assertEquals("PIN_WRONG", wrong.get(10, TimeUnit.SECONDS));
      assertEquals("PAID", correct.get(10, TimeUnit.SECONDS).status());
      assertEquals(65_000, balance());
      assertEquals(1, repo.db.queryForObject("SELECT count(*) FROM wallet_operations"
          + " WHERE invoice_id=? AND kind='DEBIT' AND status='APPLIED'", Integer.class, invoice.id()));
    } finally {
      releaseFirst.countDown();
    }
  }

  @Test
  void fifthWrongPinBlocksCardButInvoiceCanStillBePaidByBank() throws Exception {
    Profile card = fresh();
    Api.Invoice invoice = guest();
    wallet.preparePayment(TERMINAL, invoice.id());
    for (int n = 1; n <= 5; n++) {
      Failure failure = assertThrows(Failure.class, () -> wallet.pay(TERMINAL, invoice.id(), "000000"));
      assertEquals(n == 5 ? "CARD_BLOCKED" : "PIN_WRONG", failure.code);
      assertEquals(n, repo.lifecycle(card.cardId()).pinFailures());
      assertNotEquals("CANCELLED", wallet.invoice(invoice.id()).status());
    }
    assertEquals("BLOCKED", repo.lifecycle(card.cardId()).status());
    assertEquals("OPEN", wallet.invoice(invoice.id()).status());
    assertEquals("", wallet.invoice(invoice.id()).cardId());
    receive(invoice, invoice.total());
    assertEquals("PAID", wallet.invoice(invoice.id()).status());
    assertEquals("BANK_TRANSFER", wallet.invoice(invoice.id()).paymentMethod());
    assertEquals(0, repo.db.queryForObject("SELECT count(*) FROM wallet_operations"
        + " WHERE invoice_id=? AND kind='DEBIT'", Integer.class, invoice.id()));
  }

  @Test
  void insufficientFundsAndLeavingPinDoNotCancelInvoice() throws Exception {
    fresh();
    Api.Invoice invoice = guest();
    wallet.preparePayment(TERMINAL, invoice.id());
    assertEquals("FUNDS", assertThrows(Failure.class,
        () -> wallet.pay(TERMINAL, invoice.id(), "123456")).code);
    assertEquals("OPEN", wallet.invoice(invoice.id()).status());
    assertEquals(0, balance());
    wallet.preparePayment(TERMINAL, invoice.id());
    wallet.endPayment(TERMINAL, invoice.id());
    assertEquals("OPEN", wallet.invoice(invoice.id()).status());
    receive(invoice, invoice.total());
    assertEquals("PAID", wallet.invoice(invoice.id()).status());
  }

  @ParameterizedTest
  @ValueSource(strings = {"CREATED", "CARD_PREPARED", "COMMIT_DECIDED", "CARD_COMMITTED",
      "DB_COMMITTED", "ACK", "PAYMENT_READY"})
  void interruptedCardDebitIsRecoveredWithoutCancelOrDuplicate(String boundary) throws Exception {
    fresh();
    fund(100_000);
    Api.Invoice invoice = guest();
    AtomicBoolean once = new AtomicBoolean();
    cards.sync.fault = step -> {
      if (step.equals(boundary) && once.compareAndSet(false, true))
        throw new IllegalStateException("injected payment interruption");
    };
    assertThrows(RuntimeException.class, () -> wallet.pay(TERMINAL, invoice.id(), "123456"));
    cards.sync.fault = step -> {};
    cards.reconcile(TERMINAL);
    if (boundary.equals("CREATED") || boundary.equals("CARD_PREPARED")) {
      assertEquals("PAYMENT_PENDING", wallet.invoice(invoice.id()).status());
      assertEquals("CARD_AUTH", wallet.invoice(invoice.id()).paymentPhase());
      assertEquals(100_000, balance());
      wallet.preparePayment(TERMINAL, invoice.id());
      assertEquals("PAID", wallet.pay(TERMINAL, invoice.id(), "123456").status());
    } else {
      assertEquals("PAID", wallet.invoice(invoice.id()).status());
    }
    assertEquals(65_000, balance());
    assertEquals(1, repo.db.queryForObject("SELECT count(*) FROM wallet_operations"
        + " WHERE invoice_id=? AND kind='DEBIT' AND status='APPLIED'", Integer.class, invoice.id()));
    assertEquals(0, repo.db.queryForObject("SELECT count(*) FROM wallet_operations"
        + " WHERE invoice_id=? AND kind='REVERSAL'", Integer.class, invoice.id()));
  }

  @Test
  void bankReceiptDuringWrongPinWinsWithoutCardDebit() throws Exception {
    Profile card = fresh();
    fund(100_000);
    Api.Invoice invoice = guest();
    AtomicBoolean once = new AtomicBoolean();
    cards.sync.fault = step -> {
      if (step.equals("PAYMENT_PIN_STARTED") && once.compareAndSet(false, true))
        receive(invoice, invoice.total());
    };
    Api.Invoice result = wallet.pay(TERMINAL, invoice.id(), "000000");
    assertEquals("PAID", result.status());
    assertEquals("BANK_TRANSFER", result.paymentMethod());
    assertEquals(1, repo.lifecycle(card.cardId()).pinFailures());
    assertEquals(100_000, balance());
    assertEquals(0, repo.db.queryForObject("SELECT count(*) FROM wallet_operations"
        + " WHERE invoice_id=? AND kind='DEBIT'", Integer.class, invoice.id()));
  }

  @Test
  void wrongBankAmountIsHeldForReviewAndDoesNotCancelInvoice() throws Exception {
    fresh();
    Api.Invoice invoice = guest();
    receive(invoice, invoice.total() - 1);
    assertEquals("OPEN", wallet.invoice(invoice.id()).status());
    assertEquals(1, repo.db.queryForObject("SELECT count(*) FROM bank_events"
        + " WHERE payment_code=? AND result='REVIEW' AND review_reason='AMOUNT_MISMATCH'",
        Integer.class, invoice.paymentReference()));
    receive(invoice, invoice.total());
    assertEquals("PAID", wallet.invoice(invoice.id()).status());
  }

  @Test
  void stationLossKeepsInvoiceAndReopeningSameStationRestoresOwnership() throws Exception {
    fresh();
    String station = UUID.randomUUID().toString();
    String oldSession = UUID.randomUUID().toString();
    String newSession = UUID.randomUUID().toString();
    wallet.openStation(new Api.OpenStation(station, oldSession));
    Api.Invoice invoice = wallet.createInvoice(new Api.CreateInvoice(List.of(new Api.OrderItem(1, 1))), oldSession);
    cards.stations.close(oldSession);
    assertEquals("OPEN", wallet.invoice(invoice.id()).status());
    assertThrows(Failure.class, () -> wallet.currentInvoices(oldSession));
    wallet.openStation(new Api.OpenStation(station, newSession));
    assertEquals(invoice.id(), wallet.currentInvoices(newSession).getFirst().id());
    assertEquals("OPEN", wallet.invoice(invoice.id()).status());
    wallet.cancelInvoice(newSession, invoice.id());
    assertEquals("CANCELLED", wallet.invoice(invoice.id()).status());
    assertEquals("USER_CANCELLED", wallet.invoice(invoice.id()).cancelReason());
  }
}
