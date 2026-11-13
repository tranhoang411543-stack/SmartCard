package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.edu.smartcard.shared.Api;

/** Separate cards, real PostgreSQL and jCardSim exercise the actual financial paths. */
class ReaderMultiStationDatabaseTest extends DatabaseFixture {
  private void fund(long amount) {
    cards.login(TERMINAL, "123456");
    Api.Topup topup = wallet.createTopup(TERMINAL, amount);
    wallet.bankEvent(bank.event(UUID.randomUUID().toString(), topup.reference(), amount));
    wallet.apply(TERMINAL, topup.id());
  }

  @Test
  void twoCardsKeepPinPaymentAndTopupSessionsIndependent() throws Exception {
    var student = enroll("AT930101", "930000000001", 2026);
    String studentReader = hub.status(TERMINAL).alias();
    fund(100_000);
    var payer = enroll("AT930102", "930000000002", 2026);
    String payerReader = hub.status(TERMINAL).alias();
    fund(100_000);
    hub.eject(TERMINAL);
    String management = UUID.randomUUID().toString();
    hub.connect(management, studentReader);
    hub.connect(TERMINAL, payerReader);

    var mvc = MockMvcBuilders.standaloneSetup(new ApiController(auth, hub, cards, repo, wallet, bank)).build();
    String busy = mvc.perform(get("/api/reader/activity").header("X-Terminal-ID", management)
        .param("selected", payerReader)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertTrue(SecurityMaterial.JSON.readTree(busy).path("selectedOwnedByOther").asBoolean());
    String own = mvc.perform(get("/api/reader/activity").header("X-Terminal-ID", management)
        .param("selected", studentReader)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertFalse(SecurityMaterial.JSON.readTree(own).path("selectedOwnedByOther").asBoolean());

    cards.login(management, "123456");
    Api.Topup waiting = wallet.createTopup(management, 20_000);
    assertEquals("WAITING_BANK", waiting.status());
    assertTrue(hub.activity(management, payerReader).topupReserved());
    assertFalse(hub.activity(TERMINAL, studentReader).topupReserved());
    var invoice = wallet.createInvoice(new Api.CreateInvoice(List.of(new Api.OrderItem(1, 1))), TERMINAL);
    wallet.preparePayment(TERMINAL, invoice.id());
    assertEquals("PIN_WRONG", assertThrows(Failure.class,
        () -> wallet.pay(TERMINAL, invoice.id(), "000000")).code);
    assertTrue(hub.requireStudent(management).role().equals("STUDENT"));
    CountDownLatch paying = new CountDownLatch(1), release = new CountDownLatch(1);
    cards.sync.fault = step -> {
      if (step.equals("PAYMENT_PIN_STARTED")) {
        paying.countDown();
        try { if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("Payment timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
      }
    };
    try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
      Future<Api.Invoice> payment = worker.submit(() -> wallet.pay(TERMINAL, invoice.id(), "123456"));
      try {
        assertTrue(paying.await(5, TimeUnit.SECONDS));
        wallet.bankEvent(bank.event(UUID.randomUUID().toString(), waiting.reference(), waiting.amount()));
        wallet.processReceivedCredits();
        assertEquals("CREDITED", wallet.topup(waiting.id()).status(),
            "A payment on another card must not block this top-up");
        assertEquals(120_000, hub.useTopup(management, CardClient::state).balance());
      } finally { release.countDown(); }
      assertEquals("PAID", payment.get(8, TimeUnit.SECONDS).status());
    }
    cards.sync.fault = step -> {};
    assertTrue(hub.requireStudent(management).role().equals("STUDENT"));
    assertEquals(65_000, hub.use(TERMINAL, CardClient::state).balance());
    assertEquals(120_000, hub.useTopup(management, CardClient::state).balance());
    assertEquals(65_000, hub.use(TERMINAL, CardClient::state).balance());
    assertEquals(student.cardId(), hub.status(management).cardId());
    assertEquals(payer.cardId(), hub.status(TERMINAL).cardId());
    cards.block(student.cardId(), admin().id());
    assertEquals("BLOCKED", hub.status(management).lifecycleStatus());
    assertEquals("ACTIVE", hub.status(TERMINAL).lifecycleStatus());
    assertEquals(65_000, hub.use(TERMINAL, CardClient::state).balance());
    hub.eject(management);
    assertTrue(hub.status(TERMINAL).present());
  }

  @Test
  void schedulerRecoversTheCorrectCardWhenAnotherStationIsConnected() throws Exception {
    var student = enroll("AT930103", "930000000003", 2026);
    String studentReader = hub.status(TERMINAL).alias();
    var payer = enroll("AT930104", "930000000004", 2026);
    String payerReader = hub.status(TERMINAL).alias();
    fund(100_000);
    hub.eject(TERMINAL);
    String management = UUID.randomUUID().toString();
    hub.connect(management, studentReader);
    hub.connect(TERMINAL, payerReader);
    cards.login(management, "123456");
    var invoice = wallet.createInvoice(new Api.CreateInvoice(List.of(new Api.OrderItem(1, 1))), TERMINAL);
    var injected = new java.util.concurrent.atomic.AtomicBoolean();
    cards.sync.fault = step -> {
      if (step.equals("CARD_COMMITTED") && injected.compareAndSet(false, true))
        throw new IllegalStateException("injected interruption");
    };
    assertThrows(RuntimeException.class, () -> wallet.pay(TERMINAL, invoice.id(), "123456"));
    cards.sync.fault = step -> {};
    assertEquals("PAYMENT_PENDING", wallet.invoice(invoice.id()).status());
    wallet.recoverPayments();
    assertEquals("PAID", wallet.invoice(invoice.id()).status());
    assertEquals(65_000, hub.use(TERMINAL, CardClient::state).balance());
    assertEquals(student.cardId(), hub.requireStudent(management).cardId());
    assertEquals(payer.cardId(), hub.status(TERMINAL).cardId());
    hub.eject(management);
  }
}
