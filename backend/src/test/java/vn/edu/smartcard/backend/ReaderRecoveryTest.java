package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import vn.edu.smartcard.shared.*;

/** Run only in DatabaseFixture's isolated PostgreSQL test schema. */
class ReaderRecoveryTest extends DatabaseFixture {
  @Test
  void idleRecoveryInspectionDoesNotMarkReaderAsBusy() throws Exception {
    Profile profile = enroll("AT310101", "931000000001", 2026);
    Field sessions = ReaderHub.class.getDeclaredField("byTerminal");
    sessions.setAccessible(true);
    Object session = ((java.util.Map<?, ?>) sessions.get(hub)).get(TERMINAL);
    Field readerClient = session.getClass().getDeclaredField("client");
    readerClient.setAccessible(true);
    Field transportField = CardClient.class.getDeclaredField("transport");
    transportField.setAccessible(true);
    CardClient original = (CardClient) readerClient.get(session);
    CardClient.Transport transport = (CardClient.Transport) transportField.get(original);
    CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicBoolean once = new AtomicBoolean();
    CardClient wrapped = new CardClient(command -> {
      if ((command[1] & 0xff) == Wire.STATUS && once.compareAndSet(false, true)) {
        reading.countDown();
        try {
          if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("inspection timed out");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
      }
      return transport.exchange(command);
    });
    synchronized (session) { readerClient.set(session, wrapped); }
    try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
      Future<?> inspection = worker.submit(wallet::recoverPayments);
      try {
        assertTrue(reading.await(5, TimeUnit.SECONDS));
        assertFalse(hub.activity(TERMINAL).operating(), "Idle recovery checks are read-only");
      } finally { release.countDown(); }
      inspection.get(5, TimeUnit.SECONDS);
    }
    assertEquals("SYNCED", cards.sync.status(profile.cardId()).phase());
  }

  @Test
  void schedulerCompletesInterruptedTopupExactlyOnce() throws Exception {
    Profile profile = enroll("AT310102", "931000000002", 2026);
    String alias = hub.status(TERMINAL).alias();
    cards.login(TERMINAL, "123456");
    Api.Topup topup = wallet.createTopup(TERMINAL, 35000);
    wallet.bankEvent(bank.event("reader-recovery-topup", topup.reference(), 35000));
    cards.sync.fault = point -> { if (point.equals("DB_COMMITTED")) throw new AssertionError("fixture loss"); };
    assertThrows(AssertionError.class, () -> wallet.apply(TERMINAL, topup.id()));
    assertEquals("BANK_RECEIVED", wallet.topup(topup.id()).status());
    simulatePhysicalDisconnect();
    hub.connect(TERMINAL, alias);
    CountDownLatch writing = new CountDownLatch(1), allowWrite = new CountDownLatch(1);
    cards.sync.fault = point -> {
      if (point.equals("DB_COMMITTED")) {
        writing.countDown();
        try {
          if (!allowWrite.await(8, TimeUnit.SECONDS)) throw new AssertionError("recovery timed out");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
      }
    };
    try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
      Future<?> recovery = worker.submit(wallet::recoverPayments);
      try {
        assertTrue(writing.await(5, TimeUnit.SECONDS));
        assertTrue(hub.activity(TERMINAL).operating(), "Recovery that writes the card is critical");
        assertEquals("READER_BUSY", assertThrows(Failure.class, () -> hub.eject(TERMINAL)).code);
      } finally { allowWrite.countDown(); }
      recovery.get(5, TimeUnit.SECONDS);
    }
    cards.sync.fault = point -> {};
    wallet.recoverPayments();
    assertEquals("CREDITED", wallet.topup(topup.id()).status());
    assertEquals(35000L, cards.login(TERMINAL, "123456").balance());
    assertEquals(1, repo.db.queryForObject("SELECT count(*) FROM wallet_operations"
        + " WHERE topup_id=? AND status='APPLIED'", Integer.class, topup.id()));
    assertEquals("SYNCED", cards.sync.status(profile.cardId()).phase());
  }

  @Test
  void schedulerCompletesInterruptedDebitOnlyOnce() throws Exception {
    Profile profile = enroll("AT310103", "931000000003", 2026);
    cards.login(TERMINAL, "123456");
    Api.Topup funds = wallet.createTopup(TERMINAL, 100000);
    wallet.bankEvent(bank.event(UUID.randomUUID().toString(), funds.reference(), 100000));
    wallet.apply(TERMINAL, funds.id());
    Api.Invoice invoice = wallet.createInvoice(
        new Api.CreateInvoice(profile.studentCode(), List.of(new Api.OrderItem(1, 1))), TERMINAL);
    String alias = hub.status(TERMINAL).alias();
    hub.eject(TERMINAL);
    hub.connect(TERMINAL, alias);
    cards.sync.fault = point -> { if (point.equals("PAYMENT_READY")) throw new AssertionError("fixture loss"); };
    assertThrows(AssertionError.class, () -> wallet.pay(TERMINAL, invoice.id(), "123456"));
    simulatePhysicalDisconnect();
    hub.connect(TERMINAL, alias);
    cards.sync.fault = point -> {};
    wallet.recoverPayments();
    wallet.recoverPayments();
    assertEquals("PAID", wallet.invoice(invoice.id()).status());
    assertEquals(65000L, hub.use(TERMINAL, CardClient::state).balance());
    assertEquals(1, repo.db.queryForObject("SELECT count(*) FROM wallet_operations"
        + " WHERE invoice_id=? AND kind='DEBIT' AND status='APPLIED'", Integer.class, invoice.id()));
    assertEquals(0, repo.db.queryForObject("SELECT count(*) FROM wallet_operations"
        + " WHERE invoice_id=? AND kind='REVERSAL'", Integer.class, invoice.id()));
  }

  @Test
  void schedulerFinishesPaidInvoiceAfterLostResponse() throws Exception {
    Profile profile = enroll("AT310104", "931000000004", 2026);
    cards.login(TERMINAL, "123456");
    Api.Topup funds = wallet.createTopup(TERMINAL, 100000);
    wallet.bankEvent(bank.event(UUID.randomUUID().toString(), funds.reference(), 100000));
    wallet.apply(TERMINAL, funds.id());
    Api.Invoice invoice = wallet.createInvoice(
        new Api.CreateInvoice(profile.studentCode(), List.of(new Api.OrderItem(1, 1))), TERMINAL);
    String alias = hub.status(TERMINAL).alias();
    hub.eject(TERMINAL);
    hub.connect(TERMINAL, alias);
    cards.sync.fault = point -> { if (point.equals("PAYMENT_SUCCESS")) throw new AssertionError("fixture loss"); };
    assertThrows(AssertionError.class, () -> wallet.pay(TERMINAL, invoice.id(), "123456"));
    simulatePhysicalDisconnect();
    hub.connect(TERMINAL, alias);
    cards.sync.fault = point -> {};
    wallet.recoverPayments();
    wallet.recoverPayments();
    assertEquals("PAID", wallet.invoice(invoice.id()).status());
    Protocol.State state = hub.use(TERMINAL, CardClient::state);
    assertEquals(65000L, state.balance());
    assertEquals(0, state.financialHold());
    assertTrue(repo.db.queryForObject("SELECT released FROM payment_attempts WHERE invoice_id=?",
        Boolean.class, invoice.id()));
    assertEquals(0, repo.db.queryForObject("SELECT count(*) FROM wallet_operations"
        + " WHERE invoice_id=? AND kind='REVERSAL'", Integer.class, invoice.id()));
  }

  @Test
  void schedulerRepairsAnAlreadySyncedCreditStillMarkedPending() throws Exception {
    Profile profile = enroll("AT310105", "931000000005", 2026);
    cards.login(TERMINAL, "123456");
    Api.Topup topup = wallet.createTopup(TERMINAL, 25000);
    wallet.bankEvent(bank.event(UUID.randomUUID().toString(), topup.reference(), 25000));
    wallet.apply(TERMINAL, topup.id());
    assertEquals("SYNCED", cards.sync.status(profile.cardId()).phase());
    repo.db.update("UPDATE topups SET status='BANK_RECEIVED' WHERE id=?", topup.id());
    wallet.recoverPayments();
    assertEquals("CREDITED", wallet.topup(topup.id()).status());
    assertEquals(1, repo.db.queryForObject("SELECT count(*) FROM wallet_operations"
        + " WHERE topup_id=? AND status='APPLIED'", Integer.class, topup.id()));
    assertEquals(25000L, hub.use(TERMINAL, CardClient::state).balance());
    String receipt = repo.db.queryForObject("SELECT receipt FROM wallet_operations WHERE topup_id=?",
        String.class, topup.id());
    repo.db.update("UPDATE wallet_operations SET receipt='fixture-corrupt' WHERE topup_id=?", topup.id());
    wallet.recoverPayments();
    assertEquals(receipt, repo.db.queryForObject("SELECT receipt FROM wallet_operations WHERE topup_id=?",
        String.class, topup.id()), "Recovery must still repair a mismatched stored receipt");
  }
}
