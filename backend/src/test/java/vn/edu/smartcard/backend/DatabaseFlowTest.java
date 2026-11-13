package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import vn.edu.smartcard.shared.*;

class DatabaseFlowTest extends DatabaseFixture {
  @Test
  void accountRecoveryIsSingleUseAndInvalidatesSessions() {
    Api.Login logged = auth.login(new Api.Credentials("fixture_admin", TEST_PASSWORD));
    auth.forgot("fixture_admin");
    assertNotNull(mail.code);
    assertThrows(
        Failure.class,
        () ->
            auth.recover(
                new Api.Recover("fixture_admin", "00000000", TEST_PASSWORD)));
    auth.recover(new Api.Recover("fixture_admin", mail.code, TEST_PASSWORD));
    assertThrows(Failure.class, () -> auth.require(logged.token()));
    assertThrows(
        Failure.class,
        () -> auth.recover(new Api.Recover("fixture_admin", mail.code, TEST_PASSWORD)));
    assertNotNull(auth.login(new Api.Credentials("fixture_admin", TEST_PASSWORD)));
  }

  @Test
  void recoveryOtpIsSixDigitsAndExchangesForASingleUseGrant() {
    String username = "fixture_admin";
    String email = "fixture@example.invalid";
    Api.Login logged = auth.login(new Api.Credentials(username, TEST_PASSWORD));
    assertTrue(auth.forgot(email));
    String otp = mail.code;
    assertTrue(otp.matches("[0-9]{6}"));
    String wrong = otp.equals("000000") ? "000001" : "000000";
    assertEquals("RESET_OTP_INVALID", assertThrows(Failure.class,
        () -> auth.verifyRecoveryOtp(new Api.VerifyRecoveryOtp(username, wrong))).code);
    assertEquals("RESET_GRANT_INVALID", assertThrows(Failure.class,
        () -> auth.completeRecovery(new Api.CompleteRecovery(
            username, security.randomToken(), TEST_PASSWORD))).code);

    String grant = auth.verifyRecoveryOtp(new Api.VerifyRecoveryOtp(username, otp)).grant();
    assertTrue(grant.matches("[A-Za-z0-9_-]{43}"));
    assertNotNull(auth.require(logged.token()));
    assertEquals("RESET_OTP_INVALID", assertThrows(Failure.class,
        () -> auth.verifyRecoveryOtp(new Api.VerifyRecoveryOtp(username, otp))).code);
    assertThrows(Failure.class, () -> auth.recover(new Api.Recover(email, otp, TEST_PASSWORD)));
    assertThrows(Failure.class,
        () -> auth.completeRecovery(new Api.CompleteRecovery(email, grant, "too-short")));
    auth.completeRecovery(new Api.CompleteRecovery(email, grant, TEST_PASSWORD));
    assertThrows(Failure.class, () -> auth.require(logged.token()));
    assertEquals("RESET_GRANT_INVALID", assertThrows(Failure.class,
        () -> auth.completeRecovery(new Api.CompleteRecovery(email, grant, TEST_PASSWORD))).code);
    assertNotNull(auth.login(new Api.Credentials(username, TEST_PASSWORD)));
  }

  @Test
  void recoveryOtpStopsAtFiveFailuresAndRejectsExpiredCodes() {
    String username = "fixture_admin";
    assertTrue(auth.forgot(username));
    String otp = mail.code;
    String wrong = otp.equals("000000") ? "000001" : "000000";
    for (int i = 0; i < 5; i++)
      assertEquals("RESET_OTP_INVALID", assertThrows(Failure.class,
          () -> auth.verifyRecoveryOtp(new Api.VerifyRecoveryOtp(username, wrong))).code);
    assertEquals("RESET_OTP_INVALID", assertThrows(Failure.class,
        () -> auth.verifyRecoveryOtp(new Api.VerifyRecoveryOtp(username, otp))).code);
    assertEquals(5, repo.db.queryForObject("SELECT attempts FROM reset_codes", Integer.class));

    repo.db.update("UPDATE reset_codes SET sent_at=now()-interval '2 minutes'");
    assertTrue(auth.forgot(username));
    String expired = mail.code;
    repo.db.update("UPDATE reset_codes SET expires_at=now()-interval '1 second'");
    assertEquals("RESET_OTP_INVALID", assertThrows(Failure.class,
        () -> auth.verifyRecoveryOtp(new Api.VerifyRecoveryOtp(username, expired))).code);
    repo.db.update("DELETE FROM reset_codes");
  }

  @Test
  void profileSearchCombinesFiltersAndUsesIntakeYear() throws Exception {
    Profile at = enroll("AT190105", "012345678901", 2023);
    Profile ct = enroll("CT070352", "012345678902", 2023);
    Profile dt = enroll("DT060101", "012345678903", 2023);
    enroll("AT180101", "012345678904", 2022);
    Api.CardPage result = repo.search("", "", null, "", "NEWEST", 0);
    List<String> codes =
        result.items().stream()
            .map(Profile::studentCode)
            .filter(c ->
                Set.of(at.studentCode(), ct.studentCode(), dt.studentCode(), "AT180101")
                    .contains(c))
            .toList();
    assertEquals(List.of("AT190105", "CT070352", "DT060101", "AT180101"), codes);
    assertEquals(1, repo.search("nguyen", "CT7C", 2005, "CT", "NEWEST", 0).total());
    String encrypted =
        repo.db.queryForObject(
            "SELECT profile_json FROM cards WHERE card_id=?", String.class, ct.cardId());
    assertTrue(encrypted.contains("Nguyễn"));
    assertThrows(Failure.class, () -> cards.student(hub.use(TERMINAL, c -> c)));
  }

  @Test
  void bankConfirmationIsRequiredDeduplicatedAndBoundToCard() throws Exception {
    Profile p = enroll("CT080101", "012345678911", 2024);
    cards.login(TERMINAL, "123456");
    Api.Topup t = wallet.createTopup(TERMINAL, 200000);
    assertThrows(Failure.class, () -> wallet.apply(TERMINAL, t.id()));
    Map<String, Object> event = bank.event("deposit-1", t.reference(), 200000);
    wallet.bankEvent(event);
    wallet.bankEvent(event);
    assertEquals("BANK_RECEIVED", wallet.topup(t.id()).status());
    assertEquals("CREDITED", wallet.apply(TERMINAL, t.id()).status());
    assertEquals("CREDITED", wallet.apply(TERMINAL, t.id()).status());
    assertEquals(200000, cards.home(TERMINAL).balance());
    assertEquals(
        1,
        repo.db.queryForObject(
            "SELECT count(*) FROM wallet_operations WHERE topup_id=? AND status='APPLIED'",
            Integer.class,
            t.id()));
    Api.Invoice invoice =
        wallet.createInvoice(
            new Api.CreateInvoice(
                p.studentCode(), List.of(new Api.OrderItem(1, 1), new Api.OrderItem(7, 1))),
            TERMINAL);
    assertEquals(45000, invoice.total());
    assertEquals("PAID", wallet.pay(TERMINAL, invoice.id(), "123456").status());
    assertEquals("PAID", wallet.pay(TERMINAL, invoice.id(), "123456").status());
    cards.login(TERMINAL, "123456");
    assertEquals(155000, cards.home(TERMINAL).balance());
  }

  @Test
  void mismatchAndBankAmountErrorCannotChangeBalance() throws Exception {
    Profile p = enroll("DT070101", "012345678921", 2024);
    cards.login(TERMINAL, "123456");
    Api.Topup t = wallet.createTopup(TERMINAL, 50000);
    wallet.bankEvent(bank.event("deposit-wrong", t.reference(), 49999));
    assertEquals("REVIEW", wallet.topup(t.id()).status());
    assertThrows(Failure.class, () -> wallet.apply(TERMINAL, t.id()));
    assertEquals(0, cards.home(TERMINAL).balance());
    Api.Invoice invoice =
        wallet.createInvoice(
            new Api.CreateInvoice(p.studentCode(), List.of(new Api.OrderItem(1, 1))), TERMINAL);
    enroll("AT200101", "012345678922", 2024);
    assertEquals(
        "WRONG_STUDENT",
        assertThrows(Failure.class, () -> wallet.pay(TERMINAL, invoice.id(), "123456")).code);
    assertEquals("CANCELLED", wallet.invoice(invoice.id()).status());
  }

  @Test
  void depositWaitsForSameCardAfterEjection() throws Exception {
    Profile p = enroll("CT090101", "012345678931", 2025);
    String alias = hub.status(TERMINAL).alias();
    cards.login(TERMINAL, "123456");
    Api.Topup t = wallet.createTopup(TERMINAL, 100000);
    assertEquals("TOPUP_IN_PROGRESS", assertThrows(Failure.class, () -> hub.eject(TERMINAL)).code);
    simulatePhysicalDisconnect();
    wallet.bankEvent(bank.event("deposit-removed", t.reference(), 100000));
    assertEquals("BANK_RECEIVED", wallet.topup(t.id()).status());
    hub.connect(TERMINAL, alias);
    assertThrows(Failure.class, () -> wallet.apply(TERMINAL, t.id()));
    cards.login(TERMINAL, "123456");
    wallet.apply(TERMINAL, t.id());
    assertEquals(100000, cards.home(TERMINAL).balance());
  }

  @Test
  void lostDatabaseAcknowledgementIsRecoveredWithoutSecondDebit() throws Exception {
    Profile p = enroll("DT080101", "012345678941", 2025);
    cards.login(TERMINAL, "123456");
    Api.Topup t = wallet.createTopup(TERMINAL, 100000);
    wallet.bankEvent(bank.event("deposit-recovery", t.reference(), 100000));
    wallet.apply(TERMINAL, t.id());
    Api.Invoice invoice =
        wallet.createInvoice(
            new Api.CreateInvoice(p.studentCode(), List.of(new Api.OrderItem(1, 1))), TERMINAL);
    wallet.pay(TERMINAL, invoice.id(), "123456");
    assertEquals("PAID", wallet.pay(TERMINAL, invoice.id(), "123456").status());
    cards.login(TERMINAL, "123456");
    assertEquals(65000, cards.home(TERMINAL).balance());
  }

  @Test
  void neverAppliedPaymentCanBeCancelledSafely() throws Exception {
    Profile p = enroll("AT220101", "012345678951", 2025);
    Api.Invoice invoice =
        wallet.createInvoice(
            new Api.CreateInvoice(p.studentCode(), List.of(new Api.OrderItem(1, 1))), TERMINAL);
    repo.db.update(
        "INSERT INTO wallet_operations(card_id,kind,amount,invoice_id,status) VALUES"
            + " (?,'DEBIT',?,?,'PENDING')",
        p.cardId(),
        invoice.total(),
        invoice.id());
    wallet.cancelInvoice(TERMINAL, invoice.id());
    assertEquals("CANCELLED", wallet.invoice(invoice.id()).status());
    assertEquals(
        1,
        repo.db.queryForObject(
            "SELECT count(*) FROM wallet_operations WHERE invoice_id=? AND status='FAILED'",
            Integer.class,
            invoice.id()));
  }

  @Test
  void confirmedCreditWorkerRunsWithoutQrWindowAndNewPinOpensNewSession() throws Exception {
    Profile p = enroll("AT230101", "012345678961", 2026);
    cards.login(TERMINAL, "123456");
    Api.Topup first = wallet.createTopup(TERMINAL, 100000);
    wallet.bankEvent(bank.event("worker-deposit-1", first.reference(), 100000));
    wallet.processReceivedCredits();
    assertEquals("CREDITED", wallet.topup(first.id()).status());
    assertEquals(100000, cards.home(TERMINAL).balance());
    Api.Topup second = wallet.createTopup(TERMINAL, 50000);
    wallet.bankEvent(bank.event("worker-deposit-2", second.reference(), 50000));
    wallet.processReceivedCredits();
    assertEquals("CREDITED", wallet.state(TERMINAL, second.id()).status());
    assertEquals(150000, cards.home(TERMINAL).balance());
    wallet.bankEvent(bank.event("worker-withdrawal", second.reference(), -50000));
    assertEquals(150000, cards.home(TERMINAL).balance());
  }
}
