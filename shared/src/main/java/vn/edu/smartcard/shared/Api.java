package vn.edu.smartcard.shared;

import java.util.List;
import java.util.Map;

public final class Api {
  private Api() {}

  public record ReaderStatus(
      boolean present,
      String alias,
      boolean initialized,
      String cardId,
      int tries,
      String role,
      long counter,
      int profileLength,
      String connectionId,
      String lifecycleStatus) {}

  public record ReaderActivity(boolean connected, boolean ownedByCaller, boolean operating,
      boolean topupReserved, String selectedReader, boolean selectedOwnedByOther) {
    public ReaderActivity(boolean connected, boolean ownedByCaller, boolean operating,
        boolean topupReserved) {
      this(connected, ownedByCaller, operating, topupReserved, "", false);
    }
  }

  public record SyncStatus(String cardId, String phase, long operationId, String message) {}

  public record DeviceChoice(String id, String label) {}

  public record Connect(String reader, String card) {}

  public record Credentials(String username, String password) {}

  public record SetupOtp(String token, String username, String email) {}

  public record Setup(String token, String username, String email, String password, String code) {}

  public record Login(String token, String username, String email) {}

  public record Forgot(String identity) {}

  public record Recover(String identity, String code, String password) {}

  public record VerifyRecoveryOtp(String identity, String otp) {}

  public record RecoveryGrant(String grant) {}

  public record CompleteRecovery(String identity, String grant, String password) {}

  public record Pin(String pin, String attemptId) {
    public Pin(String pin) {
      this(pin, java.util.UUID.randomUUID().toString());
    }
  }

  public record PinChange(String newPin) {}

  public record Enrollment(Profile profile, String pin) {}

  public record CardMutation(Profile profile) {}

  public record StudentHome(Profile profile, long balance) {}

  public record CardTransaction(long id, String kind, long amount, String createdAt, String reference) {}

  public record CardTransactionPage(List<CardTransaction> items, Long nextBeforeId) {}

  public record CardTransactionListPage(List<CardTransaction> items, int total, int page, int pageSize) {}

  public record CardPage(List<Profile> items, int total, int page, int pageSize) {}

  public record CardListPage(
      List<Profile> items, int total, int page, int pageSize, Map<String, String> lifecycleStatuses) {}

  public record Amount(long amount) {}

  public record Topup(
      long id,
      String reference,
      String cardId,
      long amount,
      String status,
      String qrDataUrl,
      String message) {}

  public record TopupQr(Topup topup, long remainingMillis) {}

  public record TopupEvent(long id, long revision, String status, String message) {}

  public record TopupCancellation(String status, String message) {}

  public record MenuItem(
      long id,
      String name,
      String category,
      long price,
      boolean available,
      long categoryId,
      long revision,
      boolean hasImage) {
    public MenuItem(long id, String name, String category, long price, boolean available) {
      this(id, name, category, price, available, 0, 0, false);
    }
  }

  public record MenuSales(long itemId, long soldQuantity) {}

  public record MenuSearchResult(List<MenuItem> items, int total) {}

  public record MenuCategory(long id, String name, int itemCount, long revision) {
    @Override
    public String toString() {
      return name;
    }
  }

  public record CategoryInput(String name, String requestId) {}

  public record MenuInput(
      long categoryId,
      String name,
      long price,
      String imageBase64,
      long revision,
      String requestId) {}

  public record MenuDelete(long revision) {}

  public record MenuImage(String base64, String mime, long revision) {}

  public record InvoiceBankReceipt(
      String providerId,
      String invoiceNumber,
      String reference,
      long expectedAmount,
      long receivedAmount,
      String result,
      String reason,
      String receivedAt) {}

  public record BankReceipt(
      String providerId,
      String bankReference,
      long amount,
      Long expectedAmount,
      String content,
      String result,
      String reason,
      String receivedAt,
      String linkedInvoice,
      String linkedTopup) {}

  public record BankReceiptPage(List<BankReceipt> items, int total, int page, int pageSize) {}

  public record AdminTransaction(String sourceKey, String kind, String occurredAt, String reference,
      String cardId, String studentCode, long amount, String status, String reason,
      boolean simulated) {}

  public record AdminTransactionPage(List<AdminTransaction> items, int total, int page, int pageSize) {}

  public record OrderItem(long itemId, int quantity, Long revision) {
    public OrderItem(long itemId, int quantity) {
      this(itemId, quantity, null);
    }
  }

  public record CreateInvoice(String studentCode, List<OrderItem> items, String requestId) {
    public CreateInvoice(List<OrderItem> items) {
      this(null, items, java.util.UUID.randomUUID().toString());
    }

    public CreateInvoice(String studentCode, List<OrderItem> items) {
      this(studentCode, items, java.util.UUID.randomUUID().toString());
    }
  }

  public record InvoiceLine(String name, int quantity, long unitPrice, long total) {}

  public record InvoiceSummary(long id, String number, String paymentReference, String createdAt,
      long total, String status, String paymentMethod, String paymentPhase, String cancelReason) {}

  public record InvoicePage(List<InvoiceSummary> items, int total, int page, int pageSize) {}

  public record Invoice(
      long id,
      String number,
      String studentCode,
      String fullName,
      String cardId,
      long total,
      String status,
      List<InvoiceLine> items,
      long serverTime,
      long expiresAt,
      String qrDataUrl,
      String cancelReason,
      long revision,
      String stationSessionId,
      String paymentReference,
      String paymentMethod,
      String paymentPhase) {
    public Invoice(
        long id,
        String number,
        String studentCode,
        String fullName,
        String cardId,
        long total,
        String status,
        List<InvoiceLine> items,
        long serverTime,
        long expiresAt,
        String qrDataUrl,
        String cancelReason,
        long revision,
        String stationSessionId) {
      this(
          id,
          number,
          studentCode,
          fullName,
          cardId,
          total,
          status,
          items,
          serverTime,
          expiresAt,
          qrDataUrl,
          cancelReason,
          revision,
          stationSessionId,
          "",
          "",
          "NONE");
    }

    public Invoice(
        long id,
        String number,
        String studentCode,
        String fullName,
        String cardId,
        long total,
        String status,
        List<InvoiceLine> items,
        long serverTime,
        long expiresAt,
        String qrDataUrl,
        String cancelReason) {
      this(
          id,
          number,
          studentCode,
          fullName,
          cardId,
          total,
          status,
          items,
          serverTime,
          expiresAt,
          qrDataUrl,
          cancelReason,
          0,
          "");
    }
  }

  public record DeletePreview(
      String cardId,
      boolean hasBalance,
      long counter,
      long walletVersion,
      int unsyncedCount,
      String previewToken) {}

  public record DeleteCard(
      String cardId,
      boolean confirmBalance,
      long counter,
      long walletVersion,
      boolean confirmUnsynced,
      String previewToken) {
    public DeleteCard(String cardId, boolean confirmBalance, long counter, long walletVersion) {
      this(cardId, confirmBalance, counter, walletVersion, false, "");
    }
  }

  public record OpenStation(String stationId, String startupId) {}

  public record StationSession(String sessionId, long epoch, long leaseUntil) {}

  public record RefundItem(
      long id,
      long caseId,
      String cardId,
      String studentCode,
      String fullName,
      String reference,
      long requestedAmount,
      long receivedAmount,
      Long creditedAmount,
      Long refundableAmount,
      String status) {}

  public record CardLifecycle(String cardId, String status, int pinFailures) {}

  public record Result(String message) {}

  public record AdminCard(
      ReaderStatus status, Profile profile, boolean registered, String message) {}
}
