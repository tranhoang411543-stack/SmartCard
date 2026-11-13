package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import vn.edu.smartcard.shared.*;

class CatalogAndInvoicePaymentsTest extends DatabaseFixture {
  private static final AtomicInteger SERIAL = new AtomicInteger();
  private static final AtomicLong RECEIPT = new AtomicLong(700000);
  private MenuService menu;
  private String bankStudentCode;

  @BeforeEach
  void configureBank() {
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
              public String qr(long amount, String ref) {
                assertTrue(ref.matches("(?:TT|NT)(?:AT|CT|DT)[0-9]{14}"));
                return InvoiceQr.image(
                    1, amount, 1); // Image stub; payment routing is verified independently.
              }

              @Override
              public String fullQr(long amount, String ref) {
                return qr(amount, ref);
              }
            },
            security);
    TERMINAL = UUID.randomUUID().toString();
    wallet.openStation(new Api.OpenStation(UUID.randomUUID().toString(), TERMINAL));
    menu = wallet.catalog;
    AccessContext.clear();
  }

  private String image(int w, int h) throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
    return Base64.getEncoder().encodeToString(out.toByteArray());
  }

  private Api.MenuCategory category() {
    return menu.createCategory(
        TERMINAL,
        new Api.CategoryInput("Kiểm thử " + UUID.randomUUID(), UUID.randomUUID().toString()));
  }

  private Api.MenuItem dish(long category) throws Exception {
    return menu.createItem(
        TERMINAL,
        new Api.MenuInput(
            category, "Món kiểm thử", 25000, image(256, 256), 0, UUID.randomUUID().toString()));
  }

  private String bankCode() {
    if (bankStudentCode == null) {
      try {
        student();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    return bankStudentCode;
  }

  private Api.Invoice guest() {
    return wallet.createInvoice(
        new Api.CreateInvoice(bankCode(), List.of(new Api.OrderItem(1, 1))), TERMINAL);
  }

  private Profile student() throws Exception {
    int n = SERIAL.incrementAndGet();
    var p =
        enroll(
            "AT6501" + String.format("%02d", n), String.format("%012d", 880000000000L + n), 2026);
    bankStudentCode = p.studentCode();
    return p;
  }

  private Map<String, Object> event(String reference, long amount) {
    Map<String, Object> e = new HashMap<>();
    long id = RECEIPT.incrementAndGet();
    e.put("id", id);
    e.put("gateway", "BIDV");
    e.put("accountNumber", "000000000000");
    e.put("subAccount", "");
    e.put("transferType", "in");
    e.put("transferAmount", amount);
    e.put("referenceCode", "CAT-" + id);
    e.put("content", reference + " thanh toan");
    e.put("code", reference);
    e.put(
        "transactionDate",
        LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    return e;
  }

  private void send(Map<String, Object> e) {
    try {
      wallet.sepayEvent(
          SecurityMaterial.JSON.writeValueAsString(e), "Apikey " + SePayGatewayTest.KEY);
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private void fund(long amount) {
    cards.login(TERMINAL, "123456");
    var t = wallet.createTopup(TERMINAL, amount);
    send(event(t.reference(), amount));
    wallet.apply(TERMINAL, t.id());
  }

  private long balance() {
    return hub.use(TERMINAL, CardClient::state).balance();
  }

  private int events(String reference, String result) {
    return repo.db.queryForObject(
        "SELECT count(*) FROM bank_events WHERE payment_code=? AND result=?",
        Integer.class,
        reference,
        result);
  }

  private long sold(long itemId) {
    return menu.sales().stream().filter(s -> s.itemId() == itemId)
        .findFirst().orElseThrow().soldQuantity();
  }

  @Test
  void anonymousInvoiceMatchesBankReceiptAndRetriesWithoutStudentOrCard() {
    var request = new Api.CreateInvoice(List.of(new Api.OrderItem(1, 2)));
    var invoice = wallet.createInvoice(request, TERMINAL);
    assertEquals("", invoice.studentCode());
    assertEquals("", invoice.cardId());
    assertEquals(SePayGateway.invoiceCode(invoice.id()), invoice.paymentReference());
    assertTrue(invoice.paymentReference().matches("TTAT000000[0-9]{8}"));
    assertFalse(invoice.qrDataUrl().isBlank());
    assertEquals(invoice.id(), wallet.createInvoice(request, TERMINAL).id());
    assertEquals("INVOICE_ACTIVE", assertThrows(Failure.class, () -> wallet.createInvoice(
        new Api.CreateInvoice(List.of(new Api.OrderItem(1, 1))), TERMINAL)).code);
    var receipt = event(invoice.paymentReference(), invoice.total());
    send(receipt);
    send(receipt);
    assertEquals("PAID", wallet.invoice(invoice.id()).status());
    assertEquals(1, events(invoice.paymentReference(), "MATCHED_INVOICE"));
  }

  @Test
  void receiptArrivingAfterCancellationIsUnknownAndCannotPayNextInvoice() {
    var first = wallet.createInvoice(new Api.CreateInvoice(List.of(new Api.OrderItem(1, 1))), TERMINAL);
    var receipt = event(first.paymentReference(), first.total());
    wallet.cancelInvoice(TERMINAL, first.id());
    var next = wallet.createInvoice(new Api.CreateInvoice(List.of(new Api.OrderItem(1, 1))), TERMINAL);
    assertNotEquals(first.paymentReference(), next.paymentReference());
    send(receipt);
    send(receipt);
    assertEquals("CANCELLED", wallet.invoice(first.id()).status());
    assertEquals("OPEN", wallet.invoice(next.id()).status());
    assertEquals(1, events(first.paymentReference(), "REVIEW"));
    assertEquals("UNKNOWN", repo.db.queryForObject(
        "SELECT kind FROM admin_transaction_journal WHERE source_key=?", String.class,
        "BANK:SEPAY:" + receipt.get("id")));
    send(event(next.paymentReference(), next.total()));
    wallet.cancelInvoice(TERMINAL, next.id());
    assertEquals("PAID", wallet.invoice(next.id()).status());
  }

  @Test
  void anonymousCardPaymentChecksEccAndPinAfterInvoiceCreation() throws Exception {
    student();
    fund(100000);
    String alias = hub.status(TERMINAL).alias();
    hub.eject(TERMINAL);
    var invoice = wallet.createInvoice(new Api.CreateInvoice(List.of(new Api.OrderItem(1, 1))), TERMINAL);
    assertEquals("", invoice.cardId());
    int attempts = repo.db.queryForObject("SELECT count(*) FROM pin_attempts", Integer.class);
    hub.connect(TERMINAL, alias);
    assertTrue(cards.prepareCheckout(TERMINAL).present());
    assertEquals(attempts, repo.db.queryForObject("SELECT count(*) FROM pin_attempts", Integer.class));
    assertThrows(Failure.class, () -> hub.requireStudent(TERMINAL));
    wallet.preparePayment(TERMINAL, invoice.id());
    var paid = wallet.pay(TERMINAL, invoice.id(), "123456");
    assertEquals("PAID", paid.status());
    assertEquals("CARD", paid.paymentMethod());
    assertEquals(bankCode(), paid.studentCode());
    assertEquals(65000, balance());
  }

  @Test
  void userCannotCancelWhileCardDebitIsProcessing() throws Exception {
    student();
    fund(100000);
    var invoice = wallet.createInvoice(new Api.CreateInvoice(List.of(new Api.OrderItem(1, 1))), TERMINAL);
    AtomicInteger checked = new AtomicInteger();
    cards.sync.fault = step -> {
      if (step.equals("PAYMENT_PIN_STARTED") || step.equals("PAYMENT_READY")) {
        Failure error = assertThrows(Failure.class, () -> wallet.cancelInvoice(TERMINAL, invoice.id()));
        assertEquals("CARD_PROCESSING", error.code);
        checked.incrementAndGet();
      }
    };
    assertEquals("PAID", wallet.pay(TERMINAL, invoice.id(), "123456").status());
    assertEquals(2, checked.get());
    assertEquals(65000, balance());
  }

  @Test
  void bankReceiptDuringPinProcessingWaitsForTheCardOutcome() throws Exception {
    student();
    fund(100000);
    var invoice = wallet.createInvoice(new Api.CreateInvoice(List.of(new Api.OrderItem(1, 1))), TERMINAL);
    AtomicBoolean received = new AtomicBoolean();
    cards.sync.fault = step -> {
      if (step.equals("PAYMENT_PIN_STARTED") && received.compareAndSet(false, true)) {
        send(event(invoice.paymentReference(), invoice.total()));
        assertEquals(1, events(invoice.paymentReference(), "PENDING_CARD"));
      }
    };
    assertEquals("CARD", wallet.pay(TERMINAL, invoice.id(), "123456").paymentMethod());
    assertTrue(received.get());
    wallet.invoiceBank.reconcile();
    assertEquals(1, events(invoice.paymentReference(), "REVIEW"));
    assertEquals(0, events(invoice.paymentReference(), "MATCHED_INVOICE"));
    assertEquals(65000, balance());
  }

  @Test
  void concurrentInvoiceCreationAllowsOnlyOneActiveInvoicePerStation() throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<String> create = () -> {
        start.await();
        try {
          return "ID:" + wallet.createInvoice(
              new Api.CreateInvoice(List.of(new Api.OrderItem(1, 1))), TERMINAL).id();
        } catch (Failure error) {
          return error.code;
        }
      };
      var a = pool.submit(create);
      var b = pool.submit(create);
      start.countDown();
      var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
      assertEquals(1, results.stream().filter(s -> s.startsWith("ID:")).count());
      assertEquals(1, results.stream().filter("INVOICE_ACTIVE"::equals).count());
    }
  }

  @Test
  void bankSaleCountsQuantitiesOnceAndIgnoresCancelledInvoices() throws Exception {
    var d = dish(category().id());
    var invoice = wallet.createInvoice(new Api.CreateInvoice(bankCode(),
        List.of(new Api.OrderItem(d.id(), 3, d.revision()))), TERMINAL);
    assertEquals(0, sold(d.id()));
    var receipt = event(invoice.paymentReference(), invoice.total());
    send(receipt);
    send(receipt);
    assertEquals("PAID", wallet.invoice(invoice.id()).status());
    assertEquals(3, sold(d.id()));
    var cancelled = wallet.createInvoice(new Api.CreateInvoice(bankCode(),
        List.of(new Api.OrderItem(d.id(), 4, d.revision()))), TERMINAL);
    wallet.cancelInvoice(TERMINAL, cancelled.id());
    assertEquals(3, sold(d.id()));
    repo.db.update("DELETE FROM invoice_lines WHERE invoice_id=?", invoice.id());
    assertEquals(3, sold(d.id()));
  }

  @Test
  void cardSaleCountsTheNumberOfDishes() throws Exception {
    student();
    fund(150000);
    var d = dish(category().id());
    var invoice = wallet.createInvoice(new Api.CreateInvoice(bankCode(),
        List.of(new Api.OrderItem(d.id(), 2, d.revision()))), TERMINAL);
    assertEquals(0, sold(d.id()));
    assertEquals("CARD", wallet.pay(TERMINAL, invoice.id(), "123456").paymentMethod());
    assertEquals(2, sold(d.id()));
  }

  @Test
  void categoryAndDishCrudAreAvailableWithoutAdminAndIdempotent() throws Exception {
    String legacyName = "ĐẶC BIỆT " + UUID.randomUUID();
    repo.db.update("INSERT INTO menu_categories(name,name_key) VALUES(?,lower(?))",
        legacyName, legacyName);
    assertThrows(Failure.class, () -> menu.createCategory(TERMINAL,
        new Api.CategoryInput(legacyName.toLowerCase(Locale.ROOT), UUID.randomUUID().toString())));
    var request =
        new Api.CategoryInput("  Cà phê " + UUID.randomUUID() + "  ", UUID.randomUUID().toString());
    var c = menu.createCategory(TERMINAL, request);
    assertEquals(c.id(), menu.createCategory(TERMINAL, request).id());
    assertThrows(
        Failure.class,
        () ->
            menu.createCategory(
                TERMINAL,
                new Api.CategoryInput(
                    c.name().toUpperCase(Locale.ROOT), UUID.randomUUID().toString())));
    var input =
        new Api.MenuInput(
            c.id(), "Cà phê sữa", 12000, image(256, 256), 0, UUID.randomUUID().toString());
    var d = menu.createItem(TERMINAL, input);
    assertEquals(d.id(), menu.createItem(TERMINAL, input).id());
    assertFalse(menu.image(d.id()).base64().isEmpty());
    assertEquals(1, menu.items(c.id(), "sữa").size());
    var c2 = category();
    var changed =
        menu.updateItem(
            TERMINAL,
            d.id(),
            new Api.MenuInput(c2.id(), "Cà phê đá", 15000, "", d.revision(), null));
    assertEquals(c2.id(), changed.categoryId());
    assertEquals(15000, changed.price());
    assertThrows(
        Failure.class, () -> menu.deleteItem(TERMINAL, d.id(), new Api.MenuDelete(d.revision())));
    menu.deleteItem(TERMINAL, d.id(), new Api.MenuDelete(changed.revision()));
    assertThrows(Failure.class, () -> menu.image(d.id()));
    menu.deleteCategory(TERMINAL, c.id(), new Api.MenuDelete(menu.category(c.id()).revision()));
    menu.deleteCategory(TERMINAL, c2.id(), new Api.MenuDelete(menu.category(c2.id()).revision()));
  }

  @Test
  void searchReturnsNameMatchesAndTotalFromTheCurrentCatalog() throws Exception {
    int before = menu.items(null, "").size();
    var c = category();
    String key = "Món riêng " + UUID.randomUUID();
    var first = menu.createItem(TERMINAL,
        new Api.MenuInput(c.id(), key + " trà", 10000, image(256, 256), 0, UUID.randomUUID().toString()));
    menu.createItem(TERMINAL,
        new Api.MenuInput(c.id(), "Món khác " + UUID.randomUUID(), 12000, image(256, 256), 0,
            UUID.randomUUID().toString()));
    Api.MenuSearchResult found = menu.search(key.toUpperCase(Locale.ROOT));
    assertEquals(before + 2, found.total());
    assertEquals(List.of(first.id()), found.items().stream().map(Api.MenuItem::id).toList());
    assertTrue(menu.search(c.name()).items().isEmpty(), "Category names must not match the name search");
    assertEquals(found.total(), menu.search("").items().size());
  }

  @Test
  void updatingDishPreservesOrReplacesImageAtomically() throws Exception {
    var c = category();
    var dish = menu.createItem(TERMINAL,
        new Api.MenuInput(c.id(), "Món ban đầu", 10000, image(256, 256), 0,
            UUID.randomUUID().toString()));
    String original = menu.image(dish.id()).base64();
    var withoutImage = menu.updateItem(TERMINAL, dish.id(),
        new Api.MenuInput(c.id(), "Giữ ảnh", 12000, "", dish.revision(), null));
    assertEquals(original, menu.image(dish.id()).base64());

    BufferedImage red = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
    red.setRGB(0, 0, 0xff0000);
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(red, "png", bytes);
    String replacement = Base64.getEncoder().encodeToString(bytes.toByteArray());
    var changed = menu.updateItem(TERMINAL, dish.id(),
        new Api.MenuInput(c.id(), "Ảnh mới", 15000, replacement, withoutImage.revision(), null));
    String currentImage = menu.image(dish.id()).base64();
    assertNotEquals(original, currentImage);
    assertEquals(replacement, currentImage);
    assertThrows(Failure.class, () -> menu.updateItem(TERMINAL, dish.id(),
        new Api.MenuInput(c.id(), "Không được lưu", 20000, "YWJj", changed.revision(), null)));
    assertThrows(Failure.class, () -> menu.updateItem(TERMINAL, dish.id(),
        new Api.MenuInput(c.id(), "Phiên bản cũ", 20000, original, withoutImage.revision(), null)));
    assertEquals("Ảnh mới", menu.item(dish.id()).name());
    assertEquals(15000, menu.item(dish.id()).price());
    assertEquals(changed.revision(), menu.item(dish.id()).revision());
    assertEquals(currentImage, menu.image(dish.id()).base64());
  }

  @Test
  void imagesMustBeExactly256SquareAndRealJpegOrPng() throws Exception {
    var c = category();
    for (int[] size : new int[][] {{255, 256}, {256, 255}, {512, 512}}) {
      String photo = image(size[0], size[1]);
      assertThrows(
          Failure.class,
          () ->
              menu.createItem(
                  TERMINAL,
                  new Api.MenuInput(
                      c.id(), "Sai ảnh", 1000, photo, 0, UUID.randomUUID().toString())));
    }
    assertThrows(
        Failure.class,
        () ->
            menu.createItem(
                TERMINAL,
                new Api.MenuInput(
                    c.id(), "Giả ảnh", 1000, "YWJj", 0, UUID.randomUUID().toString())));
    assertThrows(IllegalArgumentException.class, () -> MenuImages.decode("a".repeat(1_500_000)));
  }

  @Test
  void deletePreviewDetectsChangedMembershipEvenWithSameCount() throws Exception {
    var c = category();
    var other = category();
    var d = dish(c.id());
    var preview = menu.category(c.id());
    menu.updateItem(
        TERMINAL,
        d.id(),
        new Api.MenuInput(other.id(), d.name(), d.price(), "", d.revision(), null));
    dish(c.id());
    assertEquals(preview.itemCount(), menu.category(c.id()).itemCount());
    assertThrows(
        Failure.class,
        () -> menu.deleteCategory(TERMINAL, c.id(), new Api.MenuDelete(preview.revision())));
    assertEquals(1, menu.category(c.id()).itemCount());
  }

  @Test
  void hardDeleteKeepsInvoiceSnapshotsAndInvoiceStillPays() throws Exception {
    var c = category();
    var d = dish(c.id());
    var i =
        wallet.createInvoice(
            new Api.CreateInvoice(bankCode(), List.of(new Api.OrderItem(d.id(), 2, d.revision()))),
            TERMINAL);
    menu.deleteCategory(TERMINAL, c.id(), new Api.MenuDelete(menu.category(c.id()).revision()));
    assertNull(
        repo.db
            .queryForMap("SELECT item_id FROM invoice_lines WHERE invoice_id=?", i.id())
            .get("item_id"));
    var read = wallet.invoice(i.id());
    assertEquals(d.name(), read.items().getFirst().name());
    assertEquals(50000, read.total());
    send(event(i.paymentReference(), i.total()));
    assertEquals("PAID", wallet.invoice(i.id()).status());
    assertEquals(
        0,
        repo.db.queryForObject(
            "SELECT count(*) FROM menu_items WHERE id=?", Integer.class, d.id()));
  }

  @Test
  void priceChangedAfterCartRequiresReview() throws Exception {
    var c = category();
    var d = dish(c.id());
    menu.updateItem(
        TERMINAL, d.id(), new Api.MenuInput(c.id(), d.name(), 30000, "", d.revision(), null));
    var failure =
        assertThrows(
            Failure.class,
            () ->
                wallet.createInvoice(
                    new Api.CreateInvoice(
                        bankCode(), List.of(new Api.OrderItem(d.id(), 1, d.revision()))),
                    TERMINAL));
    assertEquals("MENU_CHANGED", failure.code);
  }

  @Test
  void catalogMutationsRequireAnActiveStation() {
    wallet.closeStation(TERMINAL);
    assertThrows(Failure.class, () -> category());
  }

  @Test
  void invoiceCreationRetryKeepsSameInvoiceAndBankReference() {
    var request = new Api.CreateInvoice(bankCode(), List.of(new Api.OrderItem(1, 1)));
    var a = wallet.createInvoice(request, TERMINAL);
    var b = wallet.createInvoice(request, TERMINAL);
    assertEquals(a.id(), b.id());
    assertEquals(a.paymentReference(), b.paymentReference());
    assertTrue(a.paymentReference().matches("TT(AT|CT|DT)[0-9]{14}"));
    assertFalse(a.qrDataUrl().isBlank());
    assertThrows(
        Failure.class,
        () ->
            wallet.createInvoice(
                new Api.CreateInvoice(
                    bankCode(), List.of(new Api.OrderItem(1, 2)), request.requestId()),
                TERMINAL));
  }

  @Test
  void bankPaysRegisteredStudentWithoutConnectedCardAndReplayHasOneEffect() {
    var i = guest();
    hub.eject(TERMINAL);
    var e = event(i.paymentReference(), i.total());
    send(e);
    send(e);
    var paid = wallet.invoice(i.id());
    assertEquals("PAID", paid.status());
    assertEquals("BANK_TRANSFER", paid.paymentMethod());
    assertEquals("", paid.qrDataUrl());
    assertEquals(1, events(i.paymentReference(), "MATCHED_INVOICE"));
    assertEquals(
        0,
        repo.db.queryForObject(
            "SELECT count(*) FROM wallet_operations WHERE invoice_id=?", Integer.class, i.id()));
  }

  @Test
  void wrongAmountsLeaveInvoiceOpenAndRetainUnlinkedEvidence() {
    for (long difference : new long[] {-1, 1}) {
      var invoice = guest();
      var wrong = event(invoice.paymentReference(), invoice.total() + difference);
      send(wrong);
      send(wrong);
      assertEquals("OPEN", wallet.invoice(invoice.id()).status());
      assertEquals(1, events(invoice.paymentReference(), "REVIEW"));
      var receipt =
          wallet.bankReceipts().stream()
              .filter(r -> r.providerId().equals("SEPAY:" + wrong.get("id")))
              .findFirst()
              .orElseThrow();
      assertEquals("AMOUNT_MISMATCH", receipt.reason());
      assertEquals(invoice.total() + difference, receipt.amount());
      assertEquals(invoice.total(), receipt.expectedAmount());
      assertEquals("", receipt.linkedInvoice());
      assertEquals("", receipt.linkedTopup());
      send(event(invoice.paymentReference(), invoice.total()));
      assertEquals("PAID", wallet.invoice(invoice.id()).status());
      assertEquals(1, events(invoice.paymentReference(), "MATCHED_INVOICE"));
    }

    var paid = guest();
    send(event(paid.paymentReference(), paid.total()));
    send(event(paid.paymentReference(), paid.total() + 1));
    assertEquals("PAID", wallet.invoice(paid.id()).status());
    assertEquals(1, events(paid.paymentReference(), "MATCHED_INVOICE"));
    assertEquals(1, events(paid.paymentReference(), "REVIEW"));
    assertTrue(
        wallet.bankReceipts().stream().anyMatch(r -> paid.number().equals(r.linkedInvoice())));
  }

  @Test
  void cancelledOrExpiredInvoiceNeverResurrects() {
    var i = guest();
    wallet.cancelInvoice(TERMINAL, i.id());
    send(event(i.paymentReference(), i.total()));
    assertEquals("CANCELLED", wallet.invoice(i.id()).status());
    assertEquals(1, events(i.paymentReference(), "REVIEW"));
    var expired = guest();
    repo.db.update(
        "UPDATE invoices SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
        expired.id());
    wallet.expireInvoices();
    send(event(expired.paymentReference(), expired.total()));
    assertEquals("CANCELLED", wallet.invoice(expired.id()).status());
  }

  @Test
  void cardConnectedBeforeInvoicePaysAndNewInvoiceRequiresFreshPinWithoutReconnect()
      throws Exception {
    student();
    fund(150000);
    String connection = hub.status(TERMINAL).connectionId();
    var i = guest();
    assertEquals("CARD", wallet.pay(TERMINAL, i.id(), "123456").paymentMethod());
    assertEquals(connection, hub.status(TERMINAL).connectionId());
    var next = guest();
    assertThrows(Failure.class, () -> wallet.pay(TERMINAL, next.id(), "000000"));
    assertEquals("PAYMENT_PENDING", wallet.invoice(next.id()).status());
    assertEquals("CARD_AUTH", wallet.invoice(next.id()).paymentPhase());
    assertEquals(115000, balance());
  }

  @Test
  void bankWinsWhileWaitingForPinAndCardNeverDebits() throws Exception {
    student();
    fund(100000);
    var i = guest();
    wallet.preparePayment(TERMINAL, i.id());
    assertEquals("CARD_AUTH", wallet.invoice(i.id()).paymentPhase());
    send(event(i.paymentReference(), i.total()));
    assertEquals("BANK_TRANSFER", wallet.pay(TERMINAL, i.id(), "123456").paymentMethod());
    assertEquals(100000, balance());
    assertEquals(
        0,
        repo.db.queryForObject(
            "SELECT count(*) FROM wallet_operations WHERE invoice_id=?", Integer.class, i.id()));
  }

  @Test
  void bankDuringDebitWaitsThenBecomesExtraReceiptExactlyOnce() throws Exception {
    student();
    fund(100000);
    var i = guest();
    AtomicBoolean once = new AtomicBoolean();
    cards.sync.fault =
        step -> {
          if (step.equals("PAYMENT_READY") && once.compareAndSet(false, true)) {
            send(event(i.paymentReference(), i.total()));
            assertEquals(1, events(i.paymentReference(), "PENDING_CARD"));
          }
        };
    assertEquals("CARD", wallet.pay(TERMINAL, i.id(), "123456").paymentMethod());
    assertTrue(once.get());
    wallet.invoiceBank.reconcile();
    wallet.invoiceBank.reconcile();
    assertEquals(1, events(i.paymentReference(), "REVIEW"));
    assertEquals(65000, balance());
  }

  @Test
  void bankDuringInterruptedCommittedDebitStaysReviewAfterRecovery() throws Exception {
    student();
    fund(100000);
    var i = guest();
    AtomicBoolean once = new AtomicBoolean();
    cards.sync.fault =
        step -> {
          if (step.equals("PAYMENT_READY") && once.compareAndSet(false, true)) {
            send(event(i.paymentReference(), i.total()));
            throw new IllegalStateException("injected interruption");
          }
        };
    assertThrows(RuntimeException.class, () -> wallet.pay(TERMINAL, i.id(), "123456"));
    cards.sync.fault = step -> {};
    cards.reconcile(TERMINAL);
    wallet.invoiceBank.reconcile();
    assertEquals("PAID", wallet.invoice(i.id()).status());
    assertEquals("CARD", wallet.invoice(i.id()).paymentMethod());
    assertEquals(65000, balance());
    assertEquals(1, events(i.paymentReference(), "REVIEW"));
  }

  @Test
  void wrongBankAmountDuringDebitIsReviewedWithoutReversingTheCardPayment() throws Exception {
    student();
    fund(100000);
    var invoice = guest();
    var wrong = event(invoice.paymentReference(), invoice.total() - 1);
    AtomicBoolean once = new AtomicBoolean();
    cards.sync.fault =
        step -> {
          if (step.equals("PAYMENT_READY") && once.compareAndSet(false, true)) {
            send(wrong);
            assertEquals("PAYMENT_PENDING", wallet.invoice(invoice.id()).status());
          }
        };
    assertEquals("CARD", wallet.pay(TERMINAL, invoice.id(), "123456").paymentMethod());
    assertTrue(once.get());
    cards.sync.fault = step -> {};
    cards.reconcile(TERMINAL);
    send(wrong);
    assertEquals("PAID", wallet.invoice(invoice.id()).status());
    assertEquals(65000, balance());
    assertEquals(1, events(invoice.paymentReference(), "REVIEW"));
    assertEquals(
        0,
        repo.db.queryForObject(
            "SELECT count(*) FROM wallet_operations WHERE invoice_id=? AND kind='REVERSAL'"
                + " AND status='APPLIED'",
            Integer.class,
            invoice.id()));
  }

  @Test
  void concurrentWebhookDeliveriesHaveOneReceipt() throws Exception {
    var i = guest();
    var e = event(i.paymentReference(), i.total());
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var a =
          pool.submit(
              () -> {
                try {
                  gate.await();
                  send(e);
                } catch (InterruptedException x) {
                  throw new RuntimeException(x);
                }
              });
      var b =
          pool.submit(
              () -> {
                try {
                  gate.await();
                  send(e);
                } catch (InterruptedException x) {
                  throw new RuntimeException(x);
                }
              });
      gate.countDown();
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
    }
    assertEquals(1, events(i.paymentReference(), "MATCHED_INVOICE"));
    assertEquals("PAID", wallet.invoice(i.id()).status());
  }

  @Test
  void bankEvidenceSurvivesDeletionAndLateTransferCannotPayAnotherInvoice() {
    var i = guest();
    Instant originalBankTime = Instant.now();
    send(event(i.paymentReference(), i.total()));
    repo.db.update("DELETE FROM invoice_lines WHERE invoice_id=?", i.id());
    repo.db.update("DELETE FROM invoices WHERE id=?", i.id());
    var reference =
        repo.db.queryForMap(
            "SELECT * FROM invoice_payment_references WHERE payment_code=? AND invoice_number=?",
            i.paymentReference(),
            i.number());
    assertNull(reference.get("invoice_id"));
    assertEquals("SUCCESS", reference.get("final_outcome"));
    var next = guest();
    var delayed = event(i.paymentReference(), i.total());
    delayed.put(
        "transactionDate",
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
            .format(originalBankTime));
    send(delayed);
    assertEquals("OPEN", wallet.invoice(next.id()).status());
    assertEquals(1, events(i.paymentReference(), "REVIEW"));
  }
}
