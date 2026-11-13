package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.smartcard.shared.*;

/** These checks require the isolated, real ttm_test PostgreSQL database. */
class InvoiceOutcomeTest extends DatabaseFixture {
  private static final AtomicInteger SERIAL = new AtomicInteger();

  private Profile fresh() throws Exception {
    int n = SERIAL.incrementAndGet();
    return enroll(
        "AT4501" + String.format("%02d", n), String.format("%012d", 850000000000L + n), 2026);
  }

  private String alias() {
    return hub.status(TERMINAL).alias();
  }

  private void reconnectAfterUnexpectedTopupDisconnect() {
    String currentAlias = alias();
    assertEquals("TOPUP_IN_PROGRESS", assertThrows(Failure.class, () -> hub.eject(TERMINAL)).code);
    simulatePhysicalDisconnect();
    hub.connect(TERMINAL, currentAlias);
  }

  private Protocol.State state() {
    return hub.use(TERMINAL, CardClient::state);
  }

  private void funds(long amount) {
    cards.login(TERMINAL, "123456");
    var t = wallet.createTopup(TERMINAL, amount);
    wallet.bankEvent(bank.event(UUID.randomUUID().toString(), t.reference(), amount));
    wallet.apply(TERMINAL, t.id());
  }

  private Api.Invoice invoice(Profile p) {
    var i =
        wallet.createInvoice(
            new Api.CreateInvoice(p.studentCode(), List.of(new Api.OrderItem(1, 1))), TERMINAL);
    String currentAlias = alias();
    hub.eject(TERMINAL);
    hub.connect(TERMINAL, currentAlias);
    return i;
  }

  private Api.DeleteCard deletion(Api.DeletePreview p, boolean balance, boolean pending) {
    return new Api.DeleteCard(
        p.cardId(), balance, p.counter(), p.walletVersion(), pending, p.previewToken());
  }

  private void balance(Profile p, long expected) {
    assertEquals(expected, state().balance());
    assertEquals(
        expected,
        repo.db.queryForObject(
            "SELECT balance FROM card_wallets WHERE card_id=?", Long.class, p.cardId()));
    assertEquals(0, state().financialHold());
  }

  @Test
  void adminResetClearsCounterExactlyOnceAndDoesNotUnblock() throws Exception {
    var p = fresh();
    for (int i = 0; i < 4; i++) assertThrows(Failure.class, () -> cards.login(TERMINAL, "000000"));
    cards.resetPin(TERMINAL, "654321", admin().id());
    assertEquals(0, repo.lifecycle(p.cardId()).pinFailures());
    assertThrows(Failure.class, () -> cards.login(TERMINAL, "000000"));
    cards.reconcile(TERMINAL);
    assertEquals(1, repo.lifecycle(p.cardId()).pinFailures());
    assertEquals(
        5,
        repo.db.queryForObject(
            "SELECT count(*) FROM pin_attempts WHERE card_id=?", Integer.class, p.cardId()));
  }

  @Test
  void failedAdminResetKeepsOldPinAndCounter() throws Exception {
    var p = fresh();
    repo.db.update("UPDATE cards SET pin_failures=4 WHERE card_id=?", p.cardId());
    AtomicBoolean once = new AtomicBoolean();
    cards.sync.fault =
        step -> {
          if (step.equals("CARD_PREPARED") && once.compareAndSet(false, true))
            throw new IllegalStateException("injected before decision");
        };
    assertThrows(RuntimeException.class, () -> cards.resetPin(TERMINAL, "654321", admin().id()));
    assertEquals(4, repo.lifecycle(p.cardId()).pinFailures());
    assertEquals(p, cards.login(TERMINAL, "123456").profile());
  }

  @Test
  void fiveWrongPinsBlockTheCardAndKeepTheSameInvoiceWaiting() throws Exception {
    var p = fresh();
    var i = invoice(p);
    for (int n = 1; n <= 5; n++) {
      Failure failure = assertThrows(Failure.class, () -> wallet.pay(TERMINAL, i.id(), "000000"));
      assertEquals(n == 5 ? "CARD_BLOCKED" : "PIN_WRONG", failure.code);
      assertEquals(n == 5 ? "OPEN" : "PAYMENT_PENDING", wallet.invoice(i.id()).status());
      assertEquals(n, repo.lifecycle(p.cardId()).pinFailures());
    }
    assertEquals("BLOCKED", repo.lifecycle(p.cardId()).status());
    assertThrows(Failure.class, () -> wallet.pay(TERMINAL, i.id(), "123456"));
    assertEquals("OPEN", wallet.invoice(i.id()).status());
    assertEquals(0, state().balance());
  }

  @Test
  void insufficientFundsKeepsInvoiceForAnotherPaymentAttempt() throws Exception {
    var p = fresh();
    var i = invoice(p);
    Failure failure = assertThrows(Failure.class, () -> wallet.pay(TERMINAL, i.id(), "123456"));
    assertEquals("FUNDS", failure.code);
    assertEquals("Số dư trên thẻ không đủ!", failure.getMessage());
    assertEquals("OPEN", wallet.invoice(i.id()).status());
    assertEquals("FUNDS", assertThrows(Failure.class,
        () -> wallet.pay(TERMINAL, i.id(), "123456")).code);
    balance(p, 0);
    assertEquals(
        0,
        repo.db.queryForObject(
            "SELECT count(*) FROM payment_attempts WHERE invoice_id=?", Integer.class, i.id()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CREATED",
        "CARD_PREPARED",
        "COMMIT_DECIDED",
        "CARD_COMMITTED",
        "DB_COMMITTED",
        "ACK",
        "PAYMENT_READY"
      })
  void interruptedPaymentRecoversItsCommittedDecisionWithoutCancelling(String boundary) throws Exception {
    var p = fresh();
    funds(100000);
    var i = invoice(p);
    AtomicBoolean once = new AtomicBoolean();
    cards.sync.fault =
        step -> {
          if (step.equals(boundary) && once.compareAndSet(false, true))
            throw new IllegalStateException("injected payment interruption");
        };
    assertThrows(RuntimeException.class, () -> wallet.pay(TERMINAL, i.id(), "123456"));
    cards.reconcile(TERMINAL);
    cards.reconcile(TERMINAL);
    boolean beforeCommit = boundary.equals("CREATED") || boundary.equals("CARD_PREPARED");
    balance(p, beforeCommit ? 100000 : 65000);
    assertEquals(beforeCommit ? "PAYMENT_PENDING" : "PAID", wallet.invoice(i.id()).status());
    assertEquals(
        beforeCommit ? 0 : -35000,
        repo.db.queryForObject(
            "SELECT COALESCE(sum(CASE WHEN kind='DEBIT' THEN -amount ELSE amount END),0) FROM"
                + " wallet_operations WHERE invoice_id=? AND status='APPLIED'",
            Long.class,
            i.id()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"COMMIT_DECIDED", "CARD_COMMITTED", "DB_COMMITTED", "ACK", "PAYMENT_READY"})
  void processLossAndStationRestartFinishACommittedPaymentOnce(String boundary) throws Exception {
    var p = fresh();
    funds(100000);
    var i = invoice(p);
    String sim = alias();
    cards.sync.fault =
        step -> {
          if (step.equals(boundary)) throw new AssertionError("simulated process loss");
        };
    assertThrows(AssertionError.class, () -> wallet.pay(TERMINAL, i.id(), "123456"));
    hub.eject(TERMINAL);
    hub = new ReaderHub(security, config);
    cards = new CardService(hub, repo, security);
    wallet = new WalletService(cards, repo, bank, security);
    assertEquals("PAYMENT_PENDING", wallet.invoice(i.id()).status());
    hub.connect(TERMINAL, sim);
    cards.reconcile(TERMINAL);
    cards.reconcile(TERMINAL);
    balance(p, 65000);
    assertEquals("PAID", wallet.invoice(i.id()).status());
    assertEquals(
        0,
        repo.db.queryForObject(
            "SELECT count(*) FROM wallet_operations WHERE invoice_id=? AND kind='REVERSAL' AND"
                + " status='APPLIED'",
            Integer.class,
            i.id()));
  }

  @Test
  void removalAfterDebitStaysPendingUntilCorrectCardReturns() throws Exception {
    var p = fresh();
    funds(100000);
    var i = invoice(p);
    String sim = alias();
    AtomicBoolean once = new AtomicBoolean();
    cards.sync.fault =
        step -> {
          if (step.equals("CARD_COMMITTED") && once.compareAndSet(false, true)) {
            assertEquals("READER_BUSY", assertThrows(Failure.class, () -> hub.eject(TERMINAL)).code);
            simulatePhysicalDisconnect();
            throw new IllegalStateException("removed after debit");
          }
        };
    assertThrows(RuntimeException.class, () -> wallet.pay(TERMINAL, i.id(), "123456"));
    assertEquals("PAYMENT_PENDING", wallet.invoice(i.id()).status());
    hub.connect(TERMINAL, sim);
    cards.reconcile(TERMINAL);
    balance(p, 65000);
    assertEquals("PAID", wallet.invoice(i.id()).status());
  }

  @Test
  void stationCloseIsRejectedWhileProcessingAndCommittedPaymentCompletes() throws Exception {
    var p = fresh();
    funds(100000);
    var i = invoice(p);
    cards.sync.fault =
        step -> {
          if (step.equals("PAYMENT_READY")) wallet.closeStation(TERMINAL);
        };
    assertEquals("READER_BUSY", assertThrows(Failure.class,
        () -> wallet.pay(TERMINAL, i.id(), "123456")).code);
    cards.reconcile(TERMINAL);
    balance(p, 65000);
    assertEquals("PAID", wallet.invoice(i.id()).status());
  }

  @Test
  void alreadyPaidStaysPaidAcrossLostResponseCloseAndRestart() throws Exception {
    var p = fresh();
    funds(100000);
    var i = invoice(p);
    AtomicBoolean once = new AtomicBoolean();
    cards.sync.fault =
        step -> {
          if (step.equals("PAYMENT_SUCCESS") && once.compareAndSet(false, true))
            throw new IllegalStateException("response lost");
        };
    assertEquals("PAID", wallet.pay(TERMINAL, i.id(), "123456").status());
    String currentAlias = alias();
    wallet.closeStation(TERMINAL);
    hub.connect(TERMINAL, currentAlias);
    cards.reconcile(TERMINAL);
    balance(p, 65000);
    assertEquals("PAID", wallet.invoice(i.id()).status());
    assertEquals(
        0,
        repo.db.queryForObject(
            "SELECT count(*) FROM wallet_operations WHERE invoice_id=? AND kind='REVERSAL'",
            Integer.class,
            i.id()));
  }

  @Test
  void reopenTransfersWaitingInvoicesOnlyFromTheSameStation() throws Exception {
    var p = fresh();
    String station = UUID.randomUUID().toString(),
        other = UUID.randomUUID().toString(),
        old = UUID.randomUUID().toString(),
        next = UUID.randomUUID().toString(),
        elsewhere = UUID.randomUUID().toString();
    wallet.openStation(new Api.OpenStation(station, old));
    wallet.openStation(new Api.OpenStation(other, elsewhere));
    var request = new Api.CreateInvoice(p.studentCode(), List.of(new Api.OrderItem(1, 1)));
    var otherStudent = fresh();
    var a = wallet.createInvoice(request, old);
    var b =
        wallet.createInvoice(
            new Api.CreateInvoice(otherStudent.studentCode(), request.items()), elsewhere);
    wallet.openStation(new Api.OpenStation(station, next));
    assertEquals("OPEN", wallet.invoice(a.id()).status());
    assertEquals("OPEN", wallet.invoice(b.id()).status());
    assertThrows(Failure.class, () -> wallet.heartbeat(old));
    assertEquals(a.id(), wallet.currentInvoices(next).getFirst().id());
    assertEquals(b.id(), wallet.currentInvoices(elsewhere).getFirst().id());
  }

  @Test
  void leaseExpiryCannotBeRevivedByOldHeartbeat() throws Exception {
    var p = fresh();
    var i = invoice(p);
    repo.db.update(
        "UPDATE station_sessions SET lease_until=clock_timestamp()-interval '1 second' WHERE"
            + " session_id=?::uuid",
        TERMINAL);
    wallet.expireInvoices();
    assertEquals("OPEN", wallet.invoice(i.id()).status());
    assertThrows(Failure.class, () -> wallet.heartbeat(TERMINAL));
    assertThrows(Failure.class, () -> invoice(p));
  }

  @Test
  void deletionKeepsReceivedTopupAndLateCallbackDedup() throws Exception {
    var p = fresh();
    cards.login(TERMINAL, "123456");
    var t = wallet.createTopup(TERMINAL, 30000);
    wallet.bankEvent(bank.event("retained-" + p.cardId(), t.reference(), 30000));
    reconnectAfterUnexpectedTopupDisconnect();
    long actor = admin().id();
    cards.block(p.cardId(), actor);
    var preview = cards.deletePreview(TERMINAL);
    assertFalse(preview.hasBalance());
    assertEquals(1, preview.unsyncedCount());
    assertEquals(
        "UNSYNCED_CONFIRM",
        assertThrows(
                Failure.class, () -> cards.delete(TERMINAL, deletion(preview, false, false), actor))
            .code);
    cards.delete(TERMINAL, deletion(preview, false, true), actor);
    assertNull(repo.find(p.cardId()));
    var item = cards.refunds.list().getFirst();
    assertEquals(30000, item.receivedAmount());
    assertEquals(30000, item.refundableAmount());
    assertEquals("REFUND_PENDING", item.status());
    wallet.bankEvent(bank.event("retained-" + p.cardId(), t.reference(), 30000));
    assertEquals(30000, cards.refunds.list().getFirst().receivedAmount());
    wallet.bankEvent(bank.event("late-extra-" + p.cardId(), t.reference(), 10000));
    assertEquals(40000, cards.refunds.list().getFirst().receivedAmount());
    assertEquals("REVIEW_REQUIRED", cards.refunds.list().getFirst().status());
    assertEquals(
        0,
        repo.db.queryForObject(
            "SELECT count(*) FROM card_secrets WHERE card_id=?", Integer.class, p.cardId()));
  }

  @Test
  void unreceivedTopupBecomesRefundOnlyWhenVerifiedMoneyArrives() throws Exception {
    var p = fresh();
    cards.login(TERMINAL, "123456");
    var t = wallet.createTopup(TERMINAL, 30000);
    reconnectAfterUnexpectedTopupDisconnect();
    long actor = admin().id();
    cards.block(p.cardId(), actor);
    var preview = cards.deletePreview(TERMINAL);
    cards.delete(TERMINAL, deletion(preview, false, true), actor);
    assertEquals("WAITING_BANK", cards.refunds.list().getFirst().status());
    assertEquals(0, cards.refunds.list().getFirst().refundableAmount());
    var replacement =
        enroll(
            p.studentCode(),
            String.format("%012d", 860000000000L + SERIAL.incrementAndGet()),
            2026);
    wallet.bankEvent(bank.event("late-valid-" + p.cardId(), t.reference(), 30000));
    assertEquals("REFUND_PENDING", cards.refunds.list().getFirst().status());
    balance(replacement, 0);
    assertNull(repo.find(p.cardId()));
  }

  @Test
  void stalePreviewIsRejectedEvenIfCountDidNotChange() throws Exception {
    var p = fresh();
    cards.login(TERMINAL, "123456");
    var t = wallet.createTopup(TERMINAL, 30000);
    reconnectAfterUnexpectedTopupDisconnect();
    long actor = admin().id();
    cards.block(p.cardId(), actor);
    var preview = cards.deletePreview(TERMINAL);
    repo.db.update("UPDATE topups SET amount=31000 WHERE id=?", t.id());
    assertEquals(
        "DELETE_STALE",
        assertThrows(
                Failure.class, () -> cards.delete(TERMINAL, deletion(preview, false, true), actor))
            .code);
    assertNotNull(repo.find(p.cardId()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "COMMIT_DECIDED",
        "CARD_COMMITTED",
        "DB_COMMITTED",
        "ACK",
        "REVERSAL_APPLIED",
        "REVERSAL_RELEASED"
      })
  void interruptedReversalResumesWithoutRefundingTwice(String boundary) throws Exception {
    var p = fresh();
    funds(100000);
    var i = invoice(p);
    AtomicBoolean original = new AtomicBoolean(), reversal = new AtomicBoolean();
    cards.sync.fault =
        step -> {
          // An expiry decision racing with the already-started debit must still be reversed safely.
          if (step.equals("PAYMENT_READY") && original.compareAndSet(false, true))
            cards.stations.cancel(i.id(), "QR_EXPIRED");
          if (step.equals(boundary)
              && repo.db.queryForObject(
                      "SELECT count(*) FROM card_operations WHERE card_id=? AND kind=9",
                      Long.class,
                      p.cardId())
                  > 0
              && reversal.compareAndSet(false, true))
            throw new IllegalStateException("interrupt reversal");
        };
    assertThrows(RuntimeException.class, () -> wallet.pay(TERMINAL, i.id(), "123456"));
    assertTrue(reversal.get(), "The injected reversal boundary must be reached");
    cards.reconcile(TERMINAL);
    cards.reconcile(TERMINAL);
    balance(p, 100000);
    assertEquals("CANCELLED", wallet.invoice(i.id()).status());
    assertEquals(
        1,
        repo.db.queryForObject(
            "SELECT count(*) FROM wallet_operations WHERE invoice_id=? AND kind='REVERSAL' AND"
                + " status='APPLIED'",
            Integer.class,
            i.id()));
  }

  @Test
  void bankCallbackWaitingForDeleteLockFindsArchivedReference() throws Exception {
    var p = fresh();
    cards.login(TERMINAL, "123456");
    var t = wallet.createTopup(TERMINAL, 30000);
    reconnectAfterUnexpectedTopupDisconnect();
    long actor = admin().id();
    cards.block(p.cardId(), actor);
    var preview = cards.deletePreview(TERMINAL);
    CountDownLatch archived = new CountDownLatch(1), callbackStarted = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    cards.sync.fault =
        step -> {
          if (step.equals("DELETE_ARCHIVED")) {
            archived.countDown();
            try {
              assertTrue(callbackStarted.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
          }
        };
    var callback =
        pool.submit(
            () -> {
              try {
                assertTrue(archived.await(3, TimeUnit.SECONDS));
                callbackStarted.countDown();
                wallet.bankEvent(bank.event("race-" + p.cardId(), t.reference(), 30000));
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            });
    try {
      cards.delete(TERMINAL, deletion(preview, false, true), actor);
      callback.get(5, TimeUnit.SECONDS);
      assertNull(repo.find(p.cardId()));
      assertEquals(30000, cards.refunds.list().getFirst().receivedAmount());
      assertEquals(
          1,
          repo.db.queryForObject(
              "SELECT count(*) FROM bank_events WHERE provider_id=? AND refund_item_id IS NOT NULL",
              Integer.class,
              "race-" + p.cardId()));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void archiveAndRegistrationDeleteRollbackTogether() throws Exception {
    var p = fresh();
    cards.login(TERMINAL, "123456");
    wallet.createTopup(TERMINAL, 30000);
    reconnectAfterUnexpectedTopupDisconnect();
    long actor = admin().id();
    cards.block(p.cardId(), actor);
    var preview = cards.deletePreview(TERMINAL);
    cards.sync.fault =
        step -> {
          if (step.equals("DELETE_ARCHIVED"))
            throw new IllegalStateException("injected archive/delete failure");
        };
    assertThrows(
        RuntimeException.class,
        () -> cards.delete(TERMINAL, deletion(preview, false, true), actor));
    assertNotNull(repo.find(p.cardId()));
    assertEquals(
        0,
        repo.db.queryForObject(
            "SELECT count(*) FROM refund_cases WHERE original_card_id=?",
            Integer.class,
            p.cardId()));
    assertEquals(
        1,
        repo.db.queryForObject(
            "SELECT count(*) FROM topups WHERE card_id=?", Integer.class, p.cardId()));
  }
}
