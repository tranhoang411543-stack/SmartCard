package vn.edu.smartcard.backend;

import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.edu.smartcard.shared.*;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final AuthService auth;
  private final ReaderHub readers;
  private final CardService cards;
  private final CardRepository repo;
  private final WalletService wallet;
  private final BankGateway bank;
  private TopupEvents topupEvents;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void topupEvents(TopupEvents events) { this.topupEvents = events; }

  public ApiController(
      AuthService auth,
      ReaderHub readers,
      CardService cards,
      CardRepository repo,
      WalletService wallet,
      BankGateway bank) {
    this.auth = auth;
    this.readers = readers;
    this.cards = cards;
    this.repo = repo;
    this.wallet = wallet;
    this.bank = bank;
  }

  private String terminal(HttpServletRequest request) {
    String id = request.getHeader("X-Terminal-ID");
    try {
      UUID.fromString(id);
    } catch (Exception e) {
      throw Failure.bad("Phiên làm việc của quầy không hợp lệ.");
    }
    return id;
  }

  private String bearer(HttpServletRequest request) {
    String value = request.getHeader("Authorization");
    return value != null && value.startsWith("Bearer ") ? value.substring(7) : null;
  }

  private AuthService.Admin admin(HttpServletRequest request) {
    return auth.require(bearer(request));
  }

  private Api.Result ok(String message) {
    return new Api.Result(message);
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    repo.db.queryForObject("SELECT 1", Integer.class);
    return Map.of(
        "status",
        "UP",
        "application",
        "ttm-cantin",
        "protocol",
        "4",
        "bankWebhook",
        "sepay-v1",
        "bankProvider",
        bank.sepay().enabled() ? "sepay" : "casso");
  }

  @GetMapping("/setup/status")
  public Map<String, Boolean> setup() {
    return Map.of("needed", auth.setupNeeded());
  }

  @PostMapping("/setup/otp")
  public Api.Result setupOtp(@RequestBody Api.SetupOtp input) {
    auth.requestSetupOtp(input);
    return ok("Đã gửi mã OTP khởi tạo admin.");
  }

  @PostMapping("/setup")
  public Api.Result setup(@RequestBody Api.Setup input) {
    auth.setup(input);
    return ok("Khởi tạo admin thành công!");
  }

  @PostMapping("/admin/login")
  public Api.Login login(@RequestBody Api.Credentials input, HttpServletRequest r) {
    String id = terminal(r);
    readers.endStudent(id);
    return auth.login(input);
  }

  @PostMapping("/admin/logout")
  public Api.Result logout(HttpServletRequest r) {
    auth.logout(bearer(r));
    String id = terminal(r);
    if (readers.status(id).present()) readers.endStudent(id);
    return ok("Đã đăng xuất admin.");
  }

  @GetMapping("/admin/me")
  public AuthService.Admin me(HttpServletRequest r) {
    return admin(r);
  }

  @PostMapping("/admin/forgot")
  public Api.Result forgot(
      @RequestBody Api.Forgot input,
      @RequestParam(name = "reportMissing", defaultValue = "false") boolean reportMissing) {
    boolean sent = auth.forgot(input.identity());
    if (reportMissing && !sent)
      throw new Failure(404, "RESET_IDENTITY_NOT_FOUND", "Không tìm thấy email hoặc username");
    return ok("Nếu thông tin phù hợp, mã đặt lại mật khẩu sẽ được gửi tới email đã đăng ký.");
  }

  @PostMapping("/admin/recover")
  public Api.Result recover(@RequestBody Api.Recover input) {
    auth.recover(input);
    return ok("Đã đặt lại mật khẩu. Vui lòng đăng nhập lại.");
  }

  @PostMapping("/admin/recover/verify")
  public ResponseEntity<Api.RecoveryGrant> verifyRecoveryOtp(
      @RequestBody Api.VerifyRecoveryOtp input) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.verifyRecoveryOtp(input));
  }

  @PostMapping("/admin/recover/complete")
  public Api.Result completeRecovery(@RequestBody Api.CompleteRecovery input) {
    auth.completeRecovery(input);
    return ok("Đặt lại mật khẩu thành công!");
  }

  @GetMapping("/readers")
  public List<Api.DeviceChoice> readers() {
    return readers.choices();
  }

  @PostMapping("/readers/new")
  public Map<String, String> newCard(HttpServletRequest r) {
    admin(r);
    return Map.of("id", readers.newCard());
  }

  @PostMapping("/reader/connect")
  public Api.ReaderStatus connect(@RequestBody Api.Connect input, HttpServletRequest r) {
    return readers.connect(terminal(r), input.reader(), cards::authenticateConnection);
  }

  @PostMapping("/reader/eject")
  public Api.Result eject(HttpServletRequest r) {
    readers.eject(terminal(r));
    return ok("Đã ngắt kết nối thẻ.");
  }

  @GetMapping("/reader/status")
  public Api.ReaderStatus status(HttpServletRequest r) {
    return readers.status(terminal(r));
  }

  @GetMapping("/reader/activity")
  public ResponseEntity<Api.ReaderActivity> readerActivity(
      @RequestParam(name = "selected", required = false) String selected, HttpServletRequest r) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(readers.activity(terminal(r), selected));
  }

  @GetMapping("/reader/sync")
  public Api.SyncStatus syncStatus(HttpServletRequest r) {
    return cards.syncStatus(terminal(r));
  }

  @PostMapping("/reader/reconcile")
  public Api.Result reconcile(HttpServletRequest r) {
    return cards.reconcile(terminal(r));
  }

  @PostMapping("/student/prepare")
  public Api.ReaderStatus studentPrepare(HttpServletRequest r) {
    return cards.prepareStudent(terminal(r));
  }

  @PostMapping("/checkout/reader/prepare")
  public Api.ReaderStatus checkoutReaderPrepare(HttpServletRequest r) {
    return cards.prepareCheckout(terminal(r));
  }

  @PostMapping("/student/login")
  public Api.StudentHome studentLogin(@RequestBody Api.Pin input, HttpServletRequest r) {
    return cards.login(terminal(r), input);
  }

  @GetMapping("/student/home")
  public Api.StudentHome studentHome(HttpServletRequest r) {
    return cards.home(terminal(r));
  }

  @GetMapping("/student/transactions")
  public ResponseEntity<Api.CardTransactionPage> studentTransactions(
      @RequestParam(name = "beforeId", required = false) Long beforeId, HttpServletRequest r) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(cards.transactions(terminal(r), beforeId));
  }

  @GetMapping("/student/transactions/pages")
  public ResponseEntity<Api.CardTransactionListPage> studentTransactionPage(
      @RequestParam(name = "page", defaultValue = "0") int page, HttpServletRequest r) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(cards.transactionPage(terminal(r), page));
  }

  @PostMapping("/student/logout")
  public Api.Result studentLogout(HttpServletRequest r) {
    readers.endStudent(terminal(r));
    return ok("Đã kết thúc phiên sinh viên.");
  }

  @PostMapping("/student/change-pin")
  public Api.Result pin(@RequestBody Api.PinChange input, HttpServletRequest r) {
    cards.changePin(terminal(r), input);
    return ok("Đã đổi PIN và mã hóa lại hồ sơ. Phiên quản lý tiếp tục hoạt động.");
  }

  @GetMapping("/admin/cards")
  public Api.CardPage search(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String group,
      @RequestParam(required = false) Integer year,
      @RequestParam(defaultValue = "") String program,
      @RequestParam(defaultValue = "NEWEST") String sort,
      @RequestParam(defaultValue = "0") int page,
      HttpServletRequest r) {
    admin(r);
    return repo.search(q, group, year, program, sort, page);
  }

  @GetMapping("/admin/cards/overview")
  public Api.CardListPage searchWithStatus(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String group,
      @RequestParam(required = false) Integer year,
      @RequestParam(defaultValue = "") String program,
      @RequestParam(defaultValue = "CARD_ID") String sort,
      @RequestParam(defaultValue = "0") int page,
      HttpServletRequest r) {
    admin(r);
    return repo.searchWithStatus(q, group, year, program, sort, page);
  }

  @GetMapping("/admin/cards/{id}")
  public Profile detail(@PathVariable String id, HttpServletRequest r) {
    admin(r);
    CardRepository.Stored stored = repo.find(id);
    if (stored == null || stored.current() == null) throw Failure.missing("Không tìm thấy hồ sơ.");
    return stored.current();
  }

  @GetMapping("/admin/cards/{id}/transactions/pages")
  public ResponseEntity<Api.CardTransactionListPage> adminCardTransactionPage(
      @PathVariable String id, @RequestParam(name = "page", defaultValue = "0") int page,
      HttpServletRequest r) {
    admin(r);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(cards.adminTransactionPage(id, page));
  }

  @GetMapping("/admin/card")
  public Api.AdminCard adminCard(HttpServletRequest r) {
    admin(r);
    return onSelectedCard(r, () -> cards.adminCard(terminal(r)));
  }

  @PostMapping("/admin/card/init")
  public Profile init(@RequestBody Api.Enrollment input, HttpServletRequest r) {
    AuthService.Admin a = admin(r);
    return onSelectedCard(r, () -> cards.initialize(terminal(r), input, a.id()));
  }

  @PostMapping("/admin/card/update")
  public Profile update(@RequestBody Api.CardMutation input, HttpServletRequest r) {
    AuthService.Admin a = admin(r);
    return onSelectedCard(r, () -> cards.update(terminal(r), input.profile(), a.id()));
  }

  @PostMapping("/admin/card/reset-pin")
  public Api.Result resetPin(@RequestBody Api.Pin input, HttpServletRequest r) {
    AuthService.Admin a = admin(r);
    return onSelectedCard(r, () -> {
      cards.resetPin(terminal(r), input.pin(), a.id());
      return ok("Đã đặt lại PIN và mã hóa lại hồ sơ.");
    });
  }

  @PostMapping("/admin/card/unblock")
  public Api.Result unblock(HttpServletRequest r) {
    AuthService.Admin a = admin(r);
    return onSelectedCard(r, () -> {
      cards.unblock(terminal(r), a.id());
      return ok("Đã khôi phục thẻ; sinh viên tiếp tục dùng PIN hiện tại.");
    });
  }

  @PostMapping("/admin/cards/{id}/block")
  public Api.Result block(@PathVariable String id, HttpServletRequest r) {
    var a = admin(r);
    cards.block(id, a.id());
    return ok("Đã tạm khóa thẻ.");
  }

  @GetMapping("/admin/card/delete-preview")
  public Api.DeletePreview deletePreview(HttpServletRequest r) {
    admin(r);
    return onSelectedCard(r, () -> cards.deletePreview(terminal(r)));
  }

  @PostMapping("/admin/card/delete")
  public Api.Result delete(@RequestBody Api.DeleteCard input, HttpServletRequest r) {
    var a = admin(r);
    return onSelectedCard(r, () -> {
      cards.delete(terminal(r), input, a.id());
      return ok(
          "Đã xóa cứng đăng ký thẻ. Các khoản nạp chưa xử lý được giữ riêng để đối chiếu, không thể"
              + " khôi phục thẻ.");
    });
  }

  private <T> T onSelectedCard(HttpServletRequest request, Supplier<T> work) {
    String connection = request.getParameter("connectionId");
    String card = request.getParameter("cardId");
    if (connection == null && card == null) return work.get();
    return readers.use(terminal(request), connection, card, ignored -> work.get());
  }

  @GetMapping("/admin/cards/{id}/lifecycle")
  public Api.CardLifecycle lifecycle(@PathVariable String id, HttpServletRequest r) {
    admin(r);
    return repo.lifecycle(id);
  }

  @GetMapping("/admin/refunds")
  public List<Api.RefundItem> refunds(HttpServletRequest r) {
    admin(r);
    return cards.refunds.list();
  }

  @PostMapping("/station/open")
  public Api.StationSession openStation(@RequestBody Api.OpenStation input) {
    return wallet.openStation(input);
  }

  @PostMapping("/station/heartbeat")
  public Api.StationSession heartbeat(HttpServletRequest r) {
    return wallet.heartbeat(terminal(r));
  }

  @PostMapping("/station/close")
  public Api.Result closeStation(HttpServletRequest r) {
    wallet.closeStation(terminal(r));
    return ok("Đã kết thúc phiên quầy; hóa đơn chưa thành công đã được yêu cầu hủy.");
  }

  @GetMapping("/menu")
  public List<Api.MenuItem> menu(
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) String query) {
    return wallet.catalog.items(categoryId, query);
  }

  @GetMapping("/admin/invoice-bank-receipts")
  public List<Api.InvoiceBankReceipt> invoiceReceipts(HttpServletRequest r) {
    admin(r);
    return wallet.invoiceBank.reviews();
  }

  @GetMapping("/admin/bank-receipts")
  public List<Api.BankReceipt> bankReceipts(HttpServletRequest r) {
    admin(r);
    return wallet.bankReceipts();
  }

  @GetMapping("/admin/bank-receipts/pages")
  public ResponseEntity<Api.BankReceiptPage> bankReceiptPage(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      HttpServletRequest r) {
    admin(r);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(wallet.bankReceiptPage(q, page));
  }

  @GetMapping("/admin/transactions")
  public ResponseEntity<Api.AdminTransactionPage> adminTransactions(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      HttpServletRequest r) {
    admin(r);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(wallet.adminTransactions(q, page));
  }

  @GetMapping("/admin/transactions/filter")
  public ResponseEntity<Api.AdminTransactionPage> filteredAdminTransactions(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to,
      @RequestParam(name = "amount", required = false) Long amount,
      HttpServletRequest r) {
    admin(r);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(wallet.adminTransactions(q, page, from, to, amount));
  }

  @PostMapping("/invoices/{id}/qr")
  public Api.Invoice invoiceQr(@PathVariable long id, HttpServletRequest r) {
    return wallet.refreshQr(terminal(r), id);
  }

  @PostMapping("/invoices")
  public Api.Invoice invoice(@RequestBody Api.CreateInvoice input, HttpServletRequest r) {
    return wallet.createInvoice(input, terminal(r));
  }

  @GetMapping("/invoices")
  public List<Api.Invoice> invoices() {
    return wallet.recent();
  }

  @GetMapping("/invoices/pages")
  public ResponseEntity<Api.InvoicePage> invoicePage(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to,
      @RequestParam(name = "amount", required = false) Long amount,
      HttpServletRequest r) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(wallet.invoicePage(terminal(r), q, page, from, to, amount));
  }

  @GetMapping("/invoices/current")
  public ResponseEntity<List<Api.Invoice>> currentInvoices(HttpServletRequest r) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(wallet.currentInvoices(terminal(r)));
  }

  @GetMapping("/invoices/{id}")
  public Api.Invoice invoice(@PathVariable long id) {
    return wallet.invoice(id);
  }

  @PostMapping("/invoices/{id}/prepare")
  public Api.ReaderStatus preparePayment(@PathVariable long id, HttpServletRequest r) {
    return wallet.preparePayment(terminal(r), id);
  }

  @PostMapping("/invoices/{id}/end-auth")
  public Api.Result endPayment(@PathVariable long id, HttpServletRequest r) {
    wallet.endPayment(terminal(r), id);
    return ok("Đã kết thúc xác thực thẻ. Hóa đơn vẫn chờ thanh toán.");
  }

  @PostMapping("/invoices/{id}/pay")
  public Api.Invoice pay(@PathVariable long id, @RequestBody Api.Pin input, HttpServletRequest r) {
    return wallet.pay(terminal(r), id, input);
  }

  @PostMapping("/invoices/{id}/cancel")
  public Api.Result cancelInvoice(@PathVariable long id, HttpServletRequest r) {
    wallet.cancelInvoice(terminal(r), id);
    return ok("Đã tiếp nhận yêu cầu hủy. Nếu đang chờ đối soát, cần kết nối lại đúng thẻ.");
  }

  @PostMapping("/topups")
  public Api.Topup topup(@RequestBody Api.Amount input, HttpServletRequest r) {
    return wallet.createTopup(terminal(r), input.amount());
  }

  @PostMapping(value = "/topups", params = "includeExpiry=true")
  public Api.TopupQr topupQr(@RequestBody Api.Amount input, HttpServletRequest r,
      @RequestParam(name = "fullImage", defaultValue = "false") boolean fullImage) {
    return wallet.createTopupQr(terminal(r), input.amount(), fullImage);
  }

  @GetMapping("/topups")
  public List<Api.Topup> topups(HttpServletRequest r) {
    return wallet.pending(terminal(r));
  }

  @GetMapping("/topups/{id}")
  public Api.Topup topupState(@PathVariable long id, HttpServletRequest r) {
    return wallet.state(terminal(r), id);
  }

  @GetMapping(value = "/topups/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> topupEvents(@PathVariable long id, HttpServletRequest r) {
    if (topupEvents == null)
      throw new Failure(503, "TOPUP_EVENTS_OFFLINE", "Chưa kết nối được trạng thái khoản nạp.");
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .contentType(MediaType.TEXT_EVENT_STREAM).body(topupEvents.subscribe(terminal(r), id));
  }

  @PostMapping("/topups/{id}/apply")
  public Api.Topup apply(@PathVariable long id, HttpServletRequest r) {
    return wallet.apply(terminal(r), id);
  }

  @PostMapping("/topups/{id}/cancel")
  public Api.Result cancelTopup(@PathVariable long id, HttpServletRequest r) {
    return ok(wallet.cancelTopup(terminal(r), id).message());
  }

  @PostMapping(value = "/topups/{id}/cancel", params = "includeStatus=true")
  public Api.TopupCancellation cancelTopupWithStatus(@PathVariable long id, HttpServletRequest r) {
    return wallet.cancelTopup(terminal(r), id);
  }

  @PostMapping("/bank/casso")
  public Map<String, Object> bank(
      @RequestBody String body,
      @RequestHeader(value = "X-Casso-Signature", required = false) String signature) {
    wallet.bankEvent(bank.verify(body, signature));
    return Map.of("success", true);
  }
}

@Configuration
class ApiAccess implements WebMvcConfigurer {
  private final LocalConfig config;
  private final AuthService auth;

  ApiAccess(LocalConfig config, AuthService auth) {
    this.config = config;
    this.auth = auth;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler) {
                AccessContext.clear();
                if (request.getContentLengthLong() > 1_500_000)
                  throw new Failure(
                      413, "REQUEST_TOO_LARGE", "Dữ liệu gửi lên vượt giới hạn cho phép.");
                String expected = config.required("device.key");
                String actual = Objects.toString(request.getHeader("X-Device-Key"), "");
                if (!MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    actual.getBytes(StandardCharsets.UTF_8))) throw Failure.denied();
                String path = request.getRequestURI(),
                    authorization = request.getHeader("Authorization");
                boolean studentRoute = path.startsWith("/api/student/")
                    || path.equals("/api/topups") || path.startsWith("/api/topups/");
                if (authorization != null) {
                  if (!authorization.startsWith("Bearer ")) throw Failure.denied();
                  // Student operations still require the card's PIN session; an Admin token
                  // must neither grant Admin authority there nor invalidate that session.
                  if (!studentRoute) {
                    String token = authorization.substring(7);
                    AuthService.Admin actor = auth.require(token);
                    AccessContext.admin(() -> auth.require(token), actor.id());
                  }
                }
                return true;
              }

              @Override
              public void afterCompletion(
                  HttpServletRequest r,
                  HttpServletResponse s,
                  Object handler,
                  Exception exception) {
                AccessContext.clear();
              }
            })
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/health", "/api/bank/casso");
  }
}
