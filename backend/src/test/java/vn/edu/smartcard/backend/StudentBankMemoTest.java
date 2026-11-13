package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import vn.edu.smartcard.shared.*;

class StudentBankMemoTest extends DatabaseFixture {
  private static final AtomicInteger SERIAL = new AtomicInteger();
  private static final AtomicLong EVENT = new AtomicLong(900000);

  @BeforeEach
  void sepay() {
    var scoped =
        new LocalConfig() {
          @Override
          public String get(String key, String fallback) {
            return switch (key) {
              case "bank.provider" -> "sepay";
              case "bank.account" -> "000000000000";
              case "bank.code" -> "BIDV";
              case "sepay.webhook.api.key" -> SePayGatewayTest.KEY;
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
              public String qr(long amount, String memo) {
                assertTrue(memo.matches("(?:TT|NT)(?:AT|CT|DT)[0-9]{14}"));
                return "data:image/png;base64,";
              }

              @Override
              public String fullQr(long amount, String memo) {
                return qr(amount, memo);
              }
            },
            security);
    TERMINAL = UUID.randomUUID().toString();
    wallet.openStation(new Api.OpenStation(UUID.randomUUID().toString(), TERMINAL));
  }

  private Profile student() throws Exception {
    int n = SERIAL.incrementAndGet();
    return enroll(
        "AT6601" + String.format("%02d", n), String.format("%012d", 890000000000L + n), 2026);
  }

  private Api.Topup topup() {
    cards.login(TERMINAL, "123456");
    return wallet.createTopup(TERMINAL, 50000);
  }

  private Api.Invoice invoice(Profile p) {
    return wallet.createInvoice(
        new Api.CreateInvoice(p.studentCode(), List.of(new Api.OrderItem(1, 1))), TERMINAL);
  }

  private Map<String, Object> notice(String memo, long amount, Instant time) {
    long id = EVENT.incrementAndGet();
    var e = new HashMap<String, Object>();
    e.put("id", id);
    e.put("referenceCode", "STUDENT-" + id);
    e.put("gateway", "BIDV");
    e.put("accountNumber", "000000000000");
    e.put("subAccount", "");
    e.put("content", memo + " chuyen tien");
    e.put("code", memo);
    e.put("transferType", "in");
    e.put("transferAmount", amount);
    e.put(
        "transactionDate",
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
            .format(time));
    return e;
  }

  private void send(Map<String, Object> e) throws Exception {
    wallet.sepayEvent(
        SecurityMaterial.JSON.writeValueAsString(e), "Apikey " + SePayGatewayTest.KEY);
  }

  private int received(String memo, String result) {
    return repo.db.queryForObject(
        "SELECT count(*) FROM bank_events WHERE payment_code=? AND result=?",
        Integer.class,
        memo,
        result);
  }

  @Test
  void fullStudentIdentitySeparatesBothTypesAndAllPrograms() {
    for (String program : List.of("AT", "CT", "DT")) {
      assertEquals(
          "NT" + program + "01020300000125", SePayGateway.paymentCode(program + "010203", 125));
      assertEquals(
          "TT" + program + "01020300000125", SePayGateway.invoiceCode(program + "010203", 125));
    }
    assertEquals("NTCT07035200000001", SePayGateway.paymentCode(" ct070352 ", 1));
    assertThrows(Failure.class, () -> SePayGateway.invoiceCode("", 1));
    var anonymous = wallet.createInvoice(
        new Api.CreateInvoice("", List.of(new Api.OrderItem(1, 1))), TERMINAL);
    assertEquals("", anonymous.studentCode());
    assertEquals(SePayGateway.invoiceCode(anonymous.id()), anonymous.paymentReference());
  }

  @Test
  void equalNumericSuffixAcrossProgramsCanTopupIndependently() throws Exception {
    var a = student();
    var ta = topup();
    String suffix = a.studentCode().substring(2);
    enroll("CT" + suffix, String.format("%012d", 895000000000L + SERIAL.get()), 2026);
    var tb = topup();
    assertEquals(SePayGateway.paymentCode(a.studentCode(), ta.id()), ta.reference());
    assertEquals(SePayGateway.paymentCode("CT" + suffix, tb.id()), tb.reference());
    send(notice(ta.reference(), ta.amount(), Instant.now()));
    assertEquals("BANK_RECEIVED", wallet.topup(ta.id()).status());
    assertEquals("WAITING_BANK", wallet.topup(tb.id()).status());
    send(notice(tb.reference(), tb.amount(), Instant.now()));
    assertEquals("BANK_RECEIVED", wallet.topup(tb.id()).status());
  }

  @Test
  void paymentAndTopupAndProgramsCannotCrossAllocateMoney() throws Exception {
    var a = student();
    var ia = invoice(a);
    String suffix = a.studentCode().substring(2);
    var b = enroll("CT" + suffix, String.format("%012d", 896000000000L + SERIAL.get()), 2026);
    String second = UUID.randomUUID().toString();
    wallet.openStation(new Api.OpenStation(UUID.randomUUID().toString(), second));
    var ib = wallet.createInvoice(
        new Api.CreateInvoice(b.studentCode(), List.of(new Api.OrderItem(1, 1))), second);
    var nt = topup();
    send(notice(ia.paymentReference(), ia.total(), Instant.now()));
    assertEquals("PAID", wallet.invoice(ia.id()).status());
    assertEquals("OPEN", wallet.invoice(ib.id()).status());
    assertEquals("WAITING_BANK", wallet.topup(nt.id()).status());
    send(notice(nt.reference(), nt.amount(), Instant.now()));
    assertEquals("OPEN", wallet.invoice(ib.id()).status());
    send(notice(ib.paymentReference(), ib.total(), Instant.now()));
    assertEquals("PAID", wallet.invoice(ib.id()).status());
    assertEquals(
        0,
        repo.db.queryForObject(
            "SELECT count(*) FROM wallet_operations WHERE kind='DEBIT'", Integer.class));
  }

  @Test
  void sameStudentCanHaveDistinctInvoicesAndRetryKeepsTheOriginalMemo() throws Exception {
    var p = student();
    var request = new Api.CreateInvoice(p.studentCode(), List.of(new Api.OrderItem(1, 1)));
    var first = wallet.createInvoice(request, TERMINAL);
    var retry = wallet.createInvoice(request, TERMINAL);
    assertEquals(first.id(), retry.id());
    assertEquals(first.paymentReference(), retry.paymentReference());
    assertEquals(
        first.paymentReference(), wallet.refreshQr(TERMINAL, first.id()).paymentReference());
    String second = UUID.randomUUID().toString();
    wallet.openStation(new Api.OpenStation(UUID.randomUUID().toString(), second));
    var other =
        wallet.createInvoice(
            new Api.CreateInvoice(p.studentCode(), List.of(new Api.OrderItem(1, 1))), second);
    assertNotEquals(first.paymentReference(), other.paymentReference());
    send(notice(other.paymentReference(), other.total(), Instant.now()));
    assertEquals("PAID", wallet.invoice(other.id()).status());
    assertEquals("OPEN", wallet.invoice(first.id()).status());
  }

  @Test
  void parallelCreationAllocatesDifferentMemosForTheSameStudent() throws Exception {
    var p = student();
    String second = UUID.randomUUID().toString();
    wallet.openStation(new Api.OpenStation(UUID.randomUUID().toString(), second));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      java.util.function.Function<String, String> create =
          terminal -> {
            try {
              gate.await();
              var created =
                  wallet.createInvoice(
                      new Api.CreateInvoice(p.studentCode(), List.of(new Api.OrderItem(1, 1))),
                      terminal);
              return created.paymentReference();
            } catch (Failure f) {
              return f.code;
            } catch (InterruptedException e) {
              throw new IllegalStateException(e);
            }
          };
      var a = pool.submit(() -> create.apply(TERMINAL));
      var b = pool.submit(() -> create.apply(second));
      gate.countDown();
      String ma = a.get(10, TimeUnit.SECONDS), mb = b.get(10, TimeUnit.SECONDS);
      assertTrue(SePayGateway.validMemo(ma));
      assertTrue(SePayGateway.validMemo(mb));
      assertNotEquals(ma, mb);
    }
  }

  @Test
  void lateDeliveryWithBankTimeBeforeExpiryStillCannotTopup() throws Exception {
    student();
    var t = topup();
    repo.db.update(
        "UPDATE topups SET expires_at=clock_timestamp()-interval '5 seconds' WHERE id=?", t.id());
    repo.db.update(
        "UPDATE sepay_topup_requests SET opened_at=clock_timestamp()-interval '60"
            + " seconds',expires_at=clock_timestamp()-interval '5 seconds' WHERE bank_memo=?",
        t.reference());
    send(notice(t.reference(), t.amount(), Instant.now().minusSeconds(10)));
    assertEquals("EXPIRED", wallet.topup(t.id()).status());
    assertEquals(1, received(t.reference(), "REVIEW"));
    assertEquals(0, hub.use(TERMINAL, CardClient::balance).longValue());
  }

  @Test
  void cancelledTopupAndReplayCannotCredit() throws Exception {
    student();
    var t = topup();
    wallet.cancelTopup(TERMINAL, t.id());
    var e = notice(t.reference(), t.amount(), Instant.now());
    send(e);
    send(e);
    assertEquals("EXPIRED", wallet.topup(t.id()).status());
    assertEquals(1, received(t.reference(), "REVIEW"));
    assertEquals(0, hub.use(TERMINAL, CardClient::balance).longValue());
  }

  @Test
  void lateDeliveryWithBankTimeBeforeExpiryStillCannotPayInvoice() throws Exception {
    var i = invoice(student());
    repo.db.update(
        "UPDATE invoices SET expires_at=clock_timestamp()-interval '5 seconds' WHERE id=?", i.id());
    repo.db.update(
        "UPDATE invoice_payment_references SET opened_at=clock_timestamp()-interval '60"
            + " seconds',expires_at=clock_timestamp()-interval '5 seconds' WHERE invoice_id=?",
        i.id());
    send(notice(i.paymentReference(), i.total(), Instant.now().minusSeconds(10)));
    assertNotEquals("PAID", wallet.invoice(i.id()).status());
    assertEquals(1, received(i.paymentReference(), "REVIEW"));
  }

  @Test
  void cancelledInvoiceCannotBePaid() throws Exception {
    var i = invoice(student());
    wallet.cancelInvoice(TERMINAL, i.id());
    send(notice(i.paymentReference(), i.total(), Instant.now()));
    assertEquals("CANCELLED", wallet.invoice(i.id()).status());
    assertEquals(1, received(i.paymentReference(), "REVIEW"));
  }

  @Test
  void acceptedTopupReplayAfterDeadlineIsAcknowledgedWithoutAnotherCredit() throws Exception {
    student();
    var t = topup();
    var e = notice(t.reference(), t.amount(), Instant.now());
    send(e);
    wallet.apply(TERMINAL, t.id());
    repo.db.update(
        "UPDATE topups SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?", t.id());
    send(e);
    assertEquals("CREDITED", wallet.topup(t.id()).status());
    assertEquals(50000, hub.use(TERMINAL, CardClient::balance).longValue());
    assertEquals(1, received(t.reference(), "MATCHED"));
  }

  @Test
  void replayOfReceiptRejectedBeforeRequestCannotBecomeValidLater() throws Exception {
    var p = student();
    long next =
        repo.db.queryForObject("SELECT nextval(pg_get_serial_sequence('topups','id'))", Long.class)
            + 1;
    var rejected = notice(SePayGateway.paymentCode(p.studentCode(), next), 50000, Instant.now());
    send(rejected);
    var t = topup();
    assertEquals(rejected.get("code"), t.reference());
    send(rejected);
    assertEquals("WAITING_BANK", wallet.topup(t.id()).status());
    send(notice(t.reference(), t.amount(), Instant.now()));
    assertEquals("BANK_RECEIVED", wallet.topup(t.id()).status());
  }

  @Test
  void newTransferUsingOldInvoiceQrCannotPayTheNewInvoiceWithSameAmount() throws Exception {
    var p = student();
    var first = invoice(p);
    wallet.cancelInvoice(TERMINAL, first.id());
    repo.db.update(
        "UPDATE invoice_payment_references SET opened_at=clock_timestamp()-interval '60"
            + " seconds',closed_at=clock_timestamp()-interval '20 seconds' WHERE invoice_id=?",
        first.id());
    var next = invoice(p);
    assertNotEquals(first.paymentReference(), next.paymentReference());
    send(notice(first.paymentReference(), first.total(), Instant.now()));
    assertEquals("OPEN", wallet.invoice(next.id()).status());
    send(notice(next.paymentReference(), next.total(), Instant.now()));
    assertEquals("PAID", wallet.invoice(next.id()).status());
  }

  @Test
  void oldTopupQrCannotFundTheNewRequestEvenForANewBankTransaction() throws Exception {
    student();
    var first = topup();
    wallet.cancelTopup(TERMINAL, first.id());
    var next = topup();
    var retry = topup();
    assertEquals(next.id(), retry.id());
    assertEquals(next.reference(), retry.reference());
    assertNotEquals(first.reference(), next.reference());
    send(notice(first.reference(), first.amount(), Instant.now()));
    assertEquals("WAITING_BANK", wallet.topup(next.id()).status());
    assertEquals(1, received(first.reference(), "REVIEW"));
    assertEquals(0, hub.use(TERMINAL, CardClient::balance).longValue());
    send(notice(next.reference(), next.amount(), Instant.now()));
    wallet.apply(TERMINAL, next.id());
    assertEquals(50000, hub.use(TERMINAL, CardClient::balance).longValue());
  }

  @Test
  void changedStudentOrUnknownRequestNumberNeverFallsBackToTheOpenInvoice() throws Exception {
    var p = student();
    var i = invoice(p);
    String otherStudent =
        "TTCT" + p.studentCode().substring(2) + i.paymentReference().substring(10);
    String unknownRequest = "TT" + p.studentCode() + "99999999";
    send(notice(otherStudent, i.total(), Instant.now()));
    send(notice(unknownRequest, i.total(), Instant.now()));
    send(notice("", i.total(), Instant.now()));
    assertEquals("OPEN", wallet.invoice(i.id()).status());
    assertEquals(1, received(otherStudent, "REVIEW"));
    assertEquals(1, received(unknownRequest, "REVIEW"));
    send(notice(i.paymentReference(), i.total(), Instant.now()));
    assertEquals("PAID", wallet.invoice(i.id()).status());
  }

  @Test
  void newRequestDoesNotWaitForThePreviousRequestsClosingSecond() throws Exception {
    var p = student();
    var first = invoice(p);
    wallet.cancelInvoice(TERMINAL, first.id());
    repo.db.update(
        "UPDATE invoice_payment_references SET closed_at=clock_timestamp()+interval '2 seconds'"
            + " WHERE invoice_id=?",
        first.id());
    var next = invoice(p);
    assertNotEquals(first.id(), next.id());
    assertNotEquals(first.paymentReference(), next.paymentReference());
  }

  @Test
  void obsoleteMemoFormatsAreStoredForReviewWithoutFinancialEffect() throws Exception {
    var p = student();
    var i = invoice(p);
    var t = topup();
    long matchedBefore =
        repo.db.queryForObject(
            "SELECT count(*) FROM bank_events WHERE result IN('MATCHED','MATCHED_INVOICE')",
            Long.class);
    send(notice("TTM" + p.studentCode().substring(2), 50000, Instant.now()));
    send(notice("CTHD000000000123", i.total(), Instant.now()));
    send(notice("TT" + p.studentCode(), i.total(), Instant.now()));
    send(notice("NT" + p.studentCode(), t.amount(), Instant.now()));
    assertEquals("OPEN", wallet.invoice(i.id()).status());
    assertEquals("WAITING_BANK", wallet.topup(t.id()).status());
    assertEquals(
        matchedBefore,
        repo.db.queryForObject(
            "SELECT count(*) FROM bank_events WHERE result IN('MATCHED','MATCHED_INVOICE')",
            Long.class));
  }
}
