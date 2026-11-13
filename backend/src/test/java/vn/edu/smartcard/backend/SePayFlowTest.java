package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.edu.smartcard.shared.*;

class SePayFlowTest extends DatabaseFixture {
  private static final AtomicInteger SERIAL = new AtomicInteger();
  private static final String KEY = SePayGatewayTest.KEY;

  @BeforeEach
  void sepayWallet() {
    LocalConfig scoped =
        new LocalConfig() {
          @Override
          public String get(String key, String fallback) {
            return switch (key) {
              case "bank.provider" -> "sepay";
              case "bank.account" -> "96247Y6CZZ";
              case "bank.code" -> "BIDV";
              case "sepay.webhook.api.key" -> KEY;
              default -> super.get(key, fallback);
            };
          }
        };
    wallet =
        new WalletService(
            cards,
            repo,
            new BankGateway(scoped) {
              @Override
              public String qr(long amount, String reference) {
                assertTrue(reference.matches("NT(AT|CT|DT)[0-9]{14}"));
                return InvoiceQr.image(1, amount, 0);
              }

              @Override
              public String fullQr(long amount, String reference) {
                return qr(amount, reference);
              }
            },
            security);
  }

  private Profile fresh() throws Exception {
    int n = SERIAL.incrementAndGet();
    return enroll(
        "AT5501" + String.format("%02d", n), String.format("%012d", 860000000000L + n), 2026);
  }

  private Api.Topup request(long amount) {
    cards.login(TERMINAL, "123456");
    Api.Topup topup = wallet.createTopup(TERMINAL, amount);
    backdate(topup.id(), 20);
    return topup;
  }

  private void backdate(long id, int seconds) {
    repo.db.update(
        "UPDATE sepay_topup_requests SET opened_at=clock_timestamp()-make_interval(secs=>?)"
            + " WHERE reference=(SELECT reference FROM topups WHERE id=?)",
        seconds,
        id);
  }

  private Map<String, Object> event(long id, Api.Topup topup, long amount) {
    return SePayGatewayTest.payload(id, topup.reference(), amount);
  }

  private void send(Map<String, Object> event) throws Exception {
    wallet.sepayEvent(SecurityMaterial.JSON.writeValueAsString(event), "Apikey " + KEY);
  }

  private long balance() {
    return hub.useTopup(TERMINAL, CardClient::balance);
  }

  @Test
  void topupQrHasFiveMinutesAndStatusReadClosesOnlyTheExpiredWaitingRequest() throws Exception {
    fresh();
    cards.login(TERMINAL, "123456");
    var mvc = MockMvcBuilders
        .standaloneSetup(new ApiController(auth, hub, cards, repo, wallet, bank))
        .build();
    var response = mvc.perform(post("/api/topups").param("includeExpiry", "true")
            .header("X-Terminal-ID", TERMINAL).contentType("application/json")
            .content("{\"amount\":50000}"))
        .andExpect(status().isOk()).andReturn().getResponse();
    Api.TopupQr offer = SecurityMaterial.JSON.readValue(
        response.getContentAsByteArray(), Api.TopupQr.class);
    Api.Topup first = offer.topup();
    assertEquals("WAITING_BANK", first.status());
    assertTrue(offer.remainingMillis() > 0 && offer.remainingMillis() <= 300_000);
    double lifetime = repo.db.queryForObject(
        "SELECT EXTRACT(EPOCH FROM (expires_at-created_at)) FROM topups WHERE id=?",
        Double.class, first.id());
    assertEquals(300, lifetime, 2);
    var legacy = mvc.perform(post("/api/topups").header("X-Terminal-ID", TERMINAL)
            .contentType("application/json").content("{\"amount\":50000}"))
        .andExpect(status().isOk()).andReturn().getResponse();
    assertEquals(first.id(), SecurityMaterial.JSON.readValue(
        legacy.getContentAsByteArray(), Api.Topup.class).id());
    var fullImageResponse = mvc.perform(post("/api/topups").param("includeExpiry", "true")
            .param("fullImage", "true").header("X-Terminal-ID", TERMINAL)
            .contentType("application/json").content("{\"amount\":50000}"))
        .andExpect(status().isOk()).andReturn().getResponse();
    Api.TopupQr fullImageOffer = SecurityMaterial.JSON.readValue(
        fullImageResponse.getContentAsByteArray(), Api.TopupQr.class);
    assertEquals(first.id(), fullImageOffer.topup().id());
    assertEquals(first.reference(), fullImageOffer.topup().reference());
    assertEquals(first.amount(), fullImageOffer.topup().amount());
    assertEquals(InvoiceQr.image(1, first.amount(), 0), fullImageOffer.topup().qrDataUrl());
    assertTrue(fullImageOffer.remainingMillis() > 0 && fullImageOffer.remainingMillis() <= 300_000);

    repo.db.update("UPDATE topups SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
        first.id());
    var expired = mvc.perform(get("/api/topups/{id}", first.id()).header("X-Terminal-ID", TERMINAL))
        .andExpect(status().isOk()).andReturn().getResponse();
    assertEquals("EXPIRED", SecurityMaterial.JSON.readValue(
        expired.getContentAsByteArray(), Api.Topup.class).status());
    assertEquals("EXPIRED", wallet.topup(first.id()).status());

    Api.Topup replacement = request(first.amount());
    assertNotEquals(first.reference(), replacement.reference());
    send(event(9810, replacement, replacement.amount()));
    repo.db.update("UPDATE topups SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
        replacement.id());
    assertEquals("BANK_RECEIVED", wallet.state(TERMINAL, replacement.id()).status());
  }

  @Test
  void cancellationBeforeReceiptPreservesLatePaymentForReview() throws Exception {
    fresh();
    Api.Topup topup = request(50000);
    var mvc = MockMvcBuilders
        .standaloneSetup(new ApiController(auth, hub, cards, repo, wallet, bank))
        .build();
    var response = mvc.perform(post("/api/topups/{id}/cancel", topup.id())
            .param("includeStatus", "true")
            .header("X-Terminal-ID", TERMINAL))
        .andExpect(status().isOk())
        .andReturn().getResponse();
    Api.TopupCancellation result = SecurityMaterial.JSON.readValue(
        response.getContentAsByteArray(), Api.TopupCancellation.class);
    assertEquals("EXPIRED", result.status());
    assertTrue(result.message().startsWith("Đã hủy yêu cầu nạp"));

    var legacyResponse = mvc.perform(post("/api/topups/{id}/cancel", topup.id())
            .header("X-Terminal-ID", TERMINAL))
        .andExpect(status().isOk())
        .andReturn().getResponse();
    var legacyBody = SecurityMaterial.JSON.readTree(legacyResponse.getContentAsByteArray());
    assertEquals(1, legacyBody.size());
    assertTrue(legacyBody.hasNonNull("message"));
    assertEquals("EXPIRED", wallet.topup(topup.id()).status());

    Api.Topup replacement = wallet.createTopup(TERMINAL, topup.amount());
    assertNotEquals(topup.reference(), replacement.reference());
    send(event(9801, topup, topup.amount()));
    wallet.processReceivedCredits();
    assertEquals("EXPIRED", wallet.topup(topup.id()).status());
    assertEquals("WAITING_BANK", wallet.topup(replacement.id()).status());
    var receipt = wallet.bankReceipts().stream()
        .filter(r -> r.providerId().equals("SEPAY:9801"))
        .findFirst().orElseThrow();
    assertEquals("REVIEW", receipt.result());
    assertEquals("REQUEST_CLOSED", receipt.reason());
    assertEquals(0, balance());
  }

  @Test
  void cancellationReportsReceivedOrCreditedPaymentWithoutReversingIt() throws Exception {
    fresh();
    Api.Topup topup = request(50000);
    send(event(9802, topup, topup.amount()));
    assertEquals("BANK_RECEIVED", wallet.cancelTopup(TERMINAL, topup.id()).status());
    assertEquals(0, balance());

    wallet.apply(TERMINAL, topup.id());
    Api.TopupCancellation result = wallet.cancelTopup(TERMINAL, topup.id());
    assertEquals("CREDITED", result.status());
    assertEquals("Đã nạp tiền vào thẻ.", result.message());
    wallet.processReceivedCredits();
    assertEquals(topup.amount(), balance());
  }

  @Test
  void matchedReceiptIsDurableThenWorkerCreditsExactlyOnce() throws Exception {
    Profile p = fresh();
    Api.Topup t = request(100000);
    assertEquals(SePayGateway.paymentCode(p.studentCode(), t.id()), t.reference());
    var event = event(1001, t, t.amount());
    send(event);
    assertEquals("BANK_RECEIVED", wallet.topup(t.id()).status());
    var receipt =
        wallet.bankReceipts().stream()
            .filter(r -> r.providerId().equals("SEPAY:1001"))
            .findFirst()
            .orElseThrow();
    assertEquals(t.reference(), receipt.linkedTopup());
    assertEquals("", receipt.linkedInvoice());
    assertEquals(0, balance());
    wallet.processReceivedCredits();
    assertEquals("CREDITED", wallet.topup(t.id()).status());
    assertEquals(100000, balance());
    send(event);
    var duplicateReference = new LinkedHashMap<>(event);
    duplicateReference.put("id", 1002);
    send(duplicateReference);
    wallet.processReceivedCredits();
    assertEquals(100000, balance());
    assertEquals(
        1L,
        repo.db.queryForObject(
            "SELECT count(*) FROM bank_events WHERE topup_id=?", Long.class, t.id()));
    assertEquals(
        100000L,
        repo.db.queryForObject(
            "SELECT balance FROM card_wallets WHERE card_id=?", Long.class, p.cardId()));
  }

  @Test
  void receiptAfterRemovalWaitsForSameCardAndFreshPin() throws Exception {
    fresh();
    Api.Topup t = request(50000);
    String alias = hub.status(TERMINAL).alias();
    assertEquals("TOPUP_IN_PROGRESS", assertThrows(Failure.class, () -> hub.eject(TERMINAL)).code);
    simulatePhysicalDisconnect();
    send(event(2001, t, t.amount()));
    wallet.processReceivedCredits();
    assertEquals("BANK_RECEIVED", wallet.topup(t.id()).status());
    hub.connect(TERMINAL, alias);
    wallet.processReceivedCredits();
    assertEquals("BANK_RECEIVED", wallet.topup(t.id()).status());
    cards.login(TERMINAL, "123456");
    wallet.processReceivedCredits();
    assertEquals(50000, balance());
  }

  @Test
  void wrongAmountOutgoingAndForgeryCannotCredit() throws Exception {
    fresh();
    Api.Topup t = request(50000);
    assertThrows(
        Failure.class,
        () ->
            wallet.sepayEvent(
                SecurityMaterial.JSON.writeValueAsString(event(3000, t, t.amount())),
                "Apikey wrong"));
    var outgoing = event(3001, t, t.amount());
    outgoing.put("transferType", "out");
    send(outgoing);
    assertEquals("WAITING_BANK", wallet.topup(t.id()).status());
    var wrong = event(3002, t, 49999);
    send(wrong);
    send(wrong);
    send(event(3003, t, 50000));
    wallet.processReceivedCredits();
    assertEquals("REVIEW", wallet.topup(t.id()).status());
    assertTrue(wallet.topup(t.id()).message().contains("đã hủy"));
    var receipt =
        wallet.bankReceipts().stream()
            .filter(r -> r.providerId().equals("SEPAY:3002"))
            .findFirst()
            .orElseThrow();
    assertEquals("AMOUNT_MISMATCH", receipt.reason());
    assertEquals("", receipt.linkedTopup());
    assertEquals("", receipt.linkedInvoice());
    assertEquals(
        1,
        repo.db.queryForObject(
            "SELECT count(*) FROM bank_events WHERE provider_id='SEPAY:3002'", Integer.class));
    assertEquals(0, balance());
    assertEquals(
        0L,
        repo.db.queryForObject(
            "SELECT count(*) FROM wallet_operations WHERE topup_id=?", Long.class, t.id()));
  }

  @Test
  void delayedOldReceiptNeverCreditsANewerRequestWithTheSameStudentAndAmount() throws Exception {
    fresh();
    Api.Topup first = request(50000);
    backdate(first.id(), 120);
    var firstEvent = event(4001, first, first.amount());
    firstEvent.put(
        "transactionDate",
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .format(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusSeconds(100)));
    send(firstEvent);
    wallet.processReceivedCredits();
    Api.Topup second = wallet.createTopup(TERMINAL, 50000);
    assertNotEquals(first.reference(), second.reference());
    backdate(second.id(), 20);
    var old = new LinkedHashMap<>(firstEvent);
    old.put("id", 4002);
    old.put("referenceCode", "FIXTURE-LATE-4002");
    send(old);
    wallet.processReceivedCredits();
    assertEquals("WAITING_BANK", wallet.topup(second.id()).status());
    assertEquals(50000, balance());
    send(event(4003, second, second.amount()));
    wallet.processReceivedCredits();
    assertEquals(100000, balance());
  }

  @Test
  void sameSixDigitsAcrossProgramsUseDifferentFullStudentMemos() throws Exception {
    int n = SERIAL.incrementAndGet();
    String suffix = "5501" + String.format("%02d", n);
    enroll("AT" + suffix, String.format("%012d", 870000000000L + n), 2026);
    Api.Topup first = request(50000);
    assertEquals(SePayGateway.paymentCode("AT" + suffix, first.id()), first.reference());
    wallet.cancelTopup(TERMINAL, first.id());
    enroll("CT" + suffix, String.format("%012d", 880000000000L + n), 2026);
    cards.login(TERMINAL, "123456");
    var t = wallet.createTopup(TERMINAL, 50000);
    assertEquals(SePayGateway.paymentCode("CT" + suffix, t.id()), t.reference());
  }

  @Test
  void profileCodeCorrectionCanClaimANewMemoWithoutReassigningTheOldOne() throws Exception {
    Profile p = fresh();
    Api.Topup first = request(50000);
    wallet.cancelTopup(TERMINAL, first.id());
    String correctedCode = "AT56" + p.studentCode().substring(4);
    Profile corrected =
        new Profile(
            p.cardId(),
            correctedCode,
            p.fullName(),
            p.citizenId(),
            p.birthDate(),
            "",
            p.phone(),
            p.email(),
            p.intakeYear(),
            p.photoBase64(),
            p.version());
    cards.update(TERMINAL, corrected, admin().id());
    cards.login(TERMINAL, "123456");
    Api.Topup next = wallet.createTopup(TERMINAL, 60000);
    assertEquals(SePayGateway.paymentCode(correctedCode, next.id()), next.reference());
    assertEquals(
        2L,
        repo.db.queryForObject(
            "SELECT count(DISTINCT bank_memo) FROM sepay_topup_requests WHERE original_card_id=?",
            Long.class,
            p.cardId()));
  }

  @Test
  void bankTimeBeforeTheSelectedRequestAndExpiredRequestsRequireReview() throws Exception {
    fresh();
    Api.Topup first = request(50000);
    wallet.cancelTopup(TERMINAL, first.id());
    Api.Topup second = wallet.createTopup(TERMINAL, 50000);
    repo.db.update(
        "UPDATE sepay_topup_requests SET opened_at=date_trunc('second',clock_timestamp())-interval"
            + " '20 seconds' WHERE bank_memo=?",
        first.reference());
    var beforeRequest = event(7001, second, 50000);
    beforeRequest.put(
        "transactionDate",
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .format(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusSeconds(10)));
    send(beforeRequest);
    wallet.processReceivedCredits();
    assertEquals("WAITING_BANK", wallet.topup(second.id()).status());
    assertEquals(0, balance());
    backdate(second.id(), 120);
    repo.db.update(
        "UPDATE sepay_topup_requests SET expires_at=clock_timestamp()-interval '5 seconds'"
            + " WHERE reference=(SELECT reference FROM topups WHERE id=?)",
        second.id());
    send(event(7002, second, 50000));
    assertEquals("EXPIRED", wallet.topup(second.id()).status());
    assertEquals(0, balance());
  }

  @Test
  void deletedCardReceiptsKeepOriginalEvidenceWhileReplacementGetsANewWindow() throws Exception {
    Profile p = fresh();
    Api.Topup t = request(50000);
    String currentAlias = hub.status(TERMINAL).alias();
    assertEquals("TOPUP_IN_PROGRESS", assertThrows(Failure.class, () -> hub.eject(TERMINAL)).code);
    simulatePhysicalDisconnect();
    hub.connect(TERMINAL, currentAlias);
    cards.block(p.cardId(), admin().id());
    var preview = cards.deletePreview(TERMINAL);
    cards.delete(
        TERMINAL,
        new Api.DeleteCard(
            preview.cardId(),
            true,
            preview.counter(),
            preview.walletVersion(),
            true,
            preview.previewToken()),
        admin().id());
    send(event(6001, t, t.amount()));
    send(event(6001, t, t.amount()));
    assertNull(repo.find(p.cardId()));
    assertEquals(
        50000L,
        repo.db.queryForObject(
            "SELECT sum(received_amount) FROM refund_items WHERE case_id IN"
                + " (SELECT id FROM refund_cases WHERE original_card_id=?)",
            Long.class,
            p.cardId()));
    enroll(p.studentCode(), p.citizenId(), 2026);
    cards.login(TERMINAL, "123456");
    var replacement = wallet.createTopup(TERMINAL, 50000);
    assertEquals(
        SePayGateway.paymentCode(p.studentCode(), replacement.id()), replacement.reference());
    assertEquals("WAITING_BANK", replacement.status());
    send(event(6001, t, t.amount()));
    assertEquals("WAITING_BANK", wallet.topup(replacement.id()).status());
    assertEquals(0, balance());
  }
}
