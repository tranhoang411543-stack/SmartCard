package vn.edu.smartcard.ttm_cuoiky;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.ui.FlatUIUtils;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.ObjLongConsumer;
import javax.imageio.ImageIO;
import javax.swing.*;
import vn.edu.smartcard.shared.*;

final class StudentView extends JPanel {

  private static final int MAX_PIN_TRIES = 5;
  private static final long MAX_TOPUP_AMOUNT = 1_000_000;
  private static final String TOPUP_EXPIRED_MESSAGE = "Yêu cầu đã hết hạn!";
  private static final String TOPUP_RETRY_WARNING = "Đã nhận tiền. Đang thử ghi lại an toàn…";
  private static final String PIN_LOCK_WARNING =
      "Nếu nhập sai quá số lần cho phép, thẻ sẽ bị khóa!";

  private enum PinStep {
    LOGIN("Nhập mã PIN", "Xác thực"),
    CURRENT("Nhập mã PIN hiện tại", "Tiếp tục"),
    NEW("Nhập mã PIN mới", "Tiếp tục"),
    CONFIRM("Xác nhận mã PIN", "Hoàn tất");

    final String title;
    final String action;

    PinStep(String title, String action) {
      this.title = title;
      this.action = action;
    }
  }

  private final CanteenFrame frame;
  private final LongFunction<Api.Topup> requestTopupState;
  private final LongFunction<Api.TopupCancellation> requestTopupCancellation;
  @FunctionalInterface interface TopupWatcher {
    AutoCloseable watch(long id, Consumer<Api.TopupEvent> status, Consumer<String> warning,
        Consumer<Boolean> connection, Consumer<ApiClient.ApiError> fatal);
  }
  private final TopupWatcher watchTopup;
  private final Function<Api.Pin, Api.StudentHome> requestPinVerification;
  private final Function<Api.PinChange, Api.Result> requestPinChange;
  private final CardLayout pages = new CardLayout();
  private final JPanel body = new JPanel(pages);
  private final Icon cardReaderIcon =
      DesktopIcons.svg("/icons/card-remove-1-svgrepo-com.svg", 128, "biểu tượng chưa phát hiện thẻ");
  private final JLabel idleTitle = new JLabel("Không phát hiện thẻ", SwingConstants.CENTER),
      idleText = new JLabel("Vui lòng đưa thẻ vào đầu đọc.", SwingConstants.CENTER);
  private final SixDigitEntry pinEntry;
  private final ProfileForm profile;
  private final CardTransactionsView transactions;
  private final JLabel welcome = new JLabel("Thông tin thẻ");
  private final JLabel qrStatus = new JLabel(" ", SwingConstants.CENTER);
  private Api.StudentHome home;
  private Api.Topup activeTopup;
  private JPanel qrPage;
  private JLabel qrImage;
  private Icon originalQrIcon;
  private javax.swing.Timer qrCountdown;
  private javax.swing.Timer pendingEventTimer;
  private javax.swing.Timer homeRetryTimer;
  private AutoCloseable topupStream;
  private JButton cancelTopupButton;
  private Api.TopupEvent deferredTopupEvent;
  private long lastTopupRevision = -1;
  private String topupWarning;
  private boolean topupDisconnected;
  private boolean finishingTopup, receivedTopup, creditedTopup;
  private int homeRetryCount;
  private String eccConnection = "";
  private boolean pinFailureVisible;
  private PinStep pinStep = PinStep.LOGIN;
  private char[] pendingNewPin;

  StudentView(CanteenFrame frame) {
    this(frame, id -> frame.api.get("/topups/" + id, Api.Topup.class), id -> {
      String path = "/topups/" + id;
      Api.TopupCancellation result = frame.api.post(
          path + "/cancel?includeStatus=true", null, Api.TopupCancellation.class);
      if (result.status() != null) return result;
      Api.Topup current = frame.api.get(path, Api.Topup.class);
      return new Api.TopupCancellation(current.status(), current.message());
    }, input -> frame.api.post("/student/login", input, Api.StudentHome.class),
        input -> frame.api.post("/student/change-pin", input, Api.Result.class), frame.api::watchTopup);
  }

  StudentView(CanteenFrame frame, LongFunction<Api.Topup> requestTopupState,
      LongFunction<Api.TopupCancellation> requestTopupCancellation) {
    this(frame, requestTopupState, requestTopupCancellation,
        input -> frame.api.post("/student/login", input, Api.StudentHome.class),
        input -> frame.api.post("/student/change-pin", input, Api.Result.class),
        (id, status, warning, connection, fatal) -> () -> {});
  }

  StudentView(CanteenFrame frame, LongFunction<Api.Topup> requestTopupState,
      LongFunction<Api.TopupCancellation> requestTopupCancellation,
      Function<Api.Pin, Api.StudentHome> requestPinVerification,
      Function<Api.PinChange, Api.Result> requestPinChange) {
    this(frame, requestTopupState, requestTopupCancellation, requestPinVerification, requestPinChange,
        (id, status, warning, connection, fatal) -> () -> {});
  }

  StudentView(CanteenFrame frame, LongFunction<Api.Topup> requestTopupState,
      LongFunction<Api.TopupCancellation> requestTopupCancellation,
      Function<Api.Pin, Api.StudentHome> requestPinVerification,
      Function<Api.PinChange, Api.Result> requestPinChange, TopupWatcher watchTopup) {
    super(new BorderLayout());
    this.frame = frame;
    this.requestTopupState = requestTopupState;
    this.requestTopupCancellation = requestTopupCancellation;
    this.watchTopup = watchTopup;
    this.requestPinVerification = requestPinVerification;
    this.requestPinChange = requestPinChange;
    JButton topupButton = CanteenFrame.button("Nạp tiền", e -> topup(this::createQr));
    topupButton.setPreferredSize(new Dimension(120, 40));
    profile = ProfileForm.forStudentDetails(topupButton);
    transactions = new CardTransactionsView(frame);
    setOpaque(false);
    body.setOpaque(false);
    add(body);
    JPanel idle = new JPanel(new GridBagLayout());
    idle.setBackground(Color.WHITE);
    JPanel idleContent = new JPanel();
    idleContent.setOpaque(false);
    idleContent.setLayout(new BoxLayout(idleContent, BoxLayout.Y_AXIS));
    idleTitle.setFont(idleTitle.getFont().deriveFont(Font.BOLD, 32));
    idleTitle.setForeground(TTM_CuoiKy.DEFAULT_TEXT_COLOR);
    idleTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
    idleText.setFont(idleText.getFont().deriveFont(16f));
    idleText.setAlignmentX(Component.CENTER_ALIGNMENT);
    JLabel illustration = new JLabel(cardReaderIcon);
    illustration.setAlignmentX(Component.CENTER_ALIGNMENT);
    idleContent.add(idleTitle);
    idleContent.add(Box.createVerticalStrut(16));
    idleContent.add(illustration);
    idleContent.add(Box.createVerticalStrut(16));
    idleContent.add(idleText);
    idle.add(idleContent);
    body.add(idle, "idle");
    pinEntry = new SixDigitEntry(SixDigitEntry.Mode.PIN, PinStep.LOGIN.title, PinStep.LOGIN.action,
        this::submitPin, this::clearPinFeedback);
    pinEntry.addBackButton("Quay lại", this::backFromPinChange);
    pinEntry.setRoundBackButton(true);
    pinEntry.setBackButtonVisible(false);
    pinEntry.setInputEnabled(false);
    // Keep Escape as the existing shortcut for cancelling the entire PIN change.
    pinEntry.registerKeyboardAction(e -> cancelPinChange(),
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    body.add(pinEntry, "entry");
    JPanel dashboard = new JPanel(new BorderLayout());
    dashboard.setBackground(Color.WHITE);
    welcome.setFont(welcome.getFont().deriveFont(Font.BOLD, 16));
    welcome.setForeground(TTM_CuoiKy.DEFAULT_TEXT_COLOR);
    welcome.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
    JPanel profileHeader = new JPanel(new BorderLayout());
    profileHeader.setBackground(Color.WHITE);
    profileHeader.add(welcome, BorderLayout.CENTER);
    JPanel pinAction = new JPanel(new GridBagLayout());
    pinAction.setOpaque(false);
    pinAction.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 16));
    JButton changePin = CanteenFrame.button("Đổi mã PIN", e -> beginPinChange());
    changePin.setPreferredSize(new Dimension(128, 32));
    pinAction.add(changePin);
    profileHeader.add(pinAction, BorderLayout.EAST);
    JComponent profileDivider =
        new JComponent() {
          @Override
          protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(new Color(0xD0D0D0));
            g.drawLine(16, 0, getWidth() - 17, 0);
          }
        };
    profileDivider.setPreferredSize(new Dimension(0, 1));
    profileHeader.add(profileDivider, BorderLayout.SOUTH);
    dashboard.add(profileHeader, BorderLayout.NORTH);
    JPanel details = new JPanel(new BorderLayout());
    details.setBackground(Color.WHITE);
    details.add(profile, BorderLayout.NORTH);
    details.add(transactions, BorderLayout.CENTER);
    JScrollPane profileScroll = new JScrollPane(details);
    profileScroll.setBorder(BorderFactory.createEmptyBorder());
    profileScroll.setBackground(Color.WHITE);
    profileScroll.getViewport().setBackground(Color.WHITE);
    profileScroll.getVerticalScrollBar().setUnitIncrement(16);
    dashboard.add(profileScroll, BorderLayout.CENTER);
    body.add(dashboard, "home");
    onCard(frame.status());
  }

  void clearTransactions() {
    transactions.clear();
  }

  void clear() {
    resetCard();
    setPinStep(PinStep.LOGIN);
    pages.show(body, "entry");
  }

  void unavailable(String message) {
    clear();
    pinEntry.setTitle("Chưa thể đọc thẻ", true);
    pinEntry.setDescription(CanteenFrame.html(message), true);
  }

  private void resetCard() {
    frame.invalidatePrivateState();
    discardPinChange();
    home = null;
    profile.show(null);
    resetPinInput();
    pinEntry.setDescription("");
    pinEntry.setInputEnabled(false);
    eccConnection = "";
    closeQr();
  }

  void onCard(Api.ReaderStatus s) {
    onCard(s, () -> {});
  }

  void onCard(Api.ReaderStatus s, Runnable ready) {
    boolean hadPrivateData = home != null;
    resetCard();
    if (!s.present()) {
      pages.show(body, "idle");
      ready.run();
      return;
    }
    if (!s.initialized()) {
      pinEntry.setTitle("Thẻ chưa được đăng ký");
      pinEntry.setDescription("Vui lòng liên hệ admin để được hỗ trợ khởi tạo thẻ.");
    } else if (s.lifecycleStatus().equals("BLOCKED")) {
      blocked();
    } else {
      // A new connection must never expose the previous card's profile.
      if (hadPrivateData) pages.show(body, "idle");
      prepare(s.connectionId(), ready);
      return;
    }
    pages.show(body, "entry");
    ready.run();
  }

  private void clearPinFeedback() {
    pinFailureVisible = false;
    pinEntry.setFeedback("");
    pinEntry.setWarning("");
  }

  void updateTries(int remaining) {
    if (home != null && !frame.status().role().equals("STUDENT")) {
      onCard(frame.status());
      pinEntry.setFeedback("Phiên sinh viên đã kết thúc. Vui lòng xác thực lại.");
      return;
    }
    if (home != null || !frame.status().present() || !frame.status().initialized()) {
      return;
    }
    if (pinFailureVisible && remaining < MAX_PIN_TRIES) {
      pinEntry.setFeedback("Bạn còn " + remaining + " lần nhập sai!");
      pinEntry.setWarning(PIN_LOCK_WARNING);
    }
    pinEntry.setInputEnabled(remaining > 0 && eccConnection.equals(frame.status().connectionId()));
    if (frame.status().lifecycleStatus().equals("BLOCKED")) {
      blocked();
    }
  }

  private void blocked() {
    discardPinChange();
    clearPinFeedback();
    pinEntry.setTitle("Thẻ đang tạm khóa", true);
    pinEntry.setDescription("Thẻ hiện đang bị tạm khoá! Vui lòng liên hệ với admin", true);
    pinEntry.setInputEnabled(false);
  }

  private void prepare(String connection, Runnable ready) {
    frame.run(
        "Đang kiểm tra thẻ…",
        () -> frame.api.post("/student/prepare", null, Api.ReaderStatus.class),
        r -> {
          if (!connection.equals(frame.status().connectionId())) {
            return;
          }
          eccConnection = connection;
          pinEntry.setTitle(PinStep.LOGIN.title);
          pinEntry.setDescription("");
          updateTries(r.tries());
          pages.show(body, "entry");
          ready.run();
          pinEntry.focusInput();
        },
        e -> {
          pinEntry.setTitle("Chưa xác thực được thẻ", true);
          pinEntry.setDescription(CanteenFrame.html(e.getMessage()), true);
          pages.show(body, "entry");
          ready.run();
        });
  }

  void resume(Runnable ready) {
    if (!frame.status().present()) {
      onCard(frame.status(), ready);
      return;
    }
    frame.run(
        "Đang mở hồ sơ…",
        () -> frame.api.get("/student/home", Api.StudentHome.class),
        h -> {
          ready.run();
          showHome(h);
        },
        e -> onCard(frame.status(), ready));
  }

  private void showHome(Api.StudentHome h) {
    discardPinChange();
    home = h;
    profile.show(h.profile());
    profile.setBalance(h.balance());
    profile.setEditable(false);
    pages.show(body, qrPage != null ? "topupQr" : "home");
    transactions.reload();
  }

  private void beginPinChange() {
    if (home != null && pinStep == PinStep.LOGIN && !frame.isBusy()) {
      showPinStep(PinStep.CURRENT);
    }
  }

  private void showPinStep(PinStep step) {
    resetPinInput();
    setPinStep(step);
    pinEntry.setDescription("");
    pinEntry.setInputEnabled(true);
    pages.show(body, "entry");
    pinEntry.focusInput();
  }

  private void setPinStep(PinStep step) {
    pinStep = step;
    pinEntry.setPrompt(step.title, step.action);
    pinEntry.setBackButtonVisible(step != PinStep.LOGIN);
  }

  private void resetPinInput() {
    pinEntry.clear();
    pinFailureVisible = false;
  }

  private void clearPendingNewPin() {
    if (pendingNewPin != null) {
      Arrays.fill(pendingNewPin, '\0');
      pendingNewPin = null;
    }
  }

  void discardPinChange() {
    if (pinStep == PinStep.LOGIN) return;
    clearPendingNewPin();
    resetPinInput();
    setPinStep(PinStep.LOGIN);
  }

  private void cancelPinChange() {
    if (pinStep == PinStep.LOGIN || frame.isBusy()) return;
    if (home != null) {
      showHome(home);
    } else {
      resume(() -> {});
    }
  }

  private void backFromPinChange() {
    if (frame.isBusy()) return;
    switch (pinStep) {
      case CURRENT -> {
        resetPinInput();
        cancelPinChange();
      }
      case NEW -> {
        clearPendingNewPin();
        showPinStep(PinStep.CURRENT);
      }
      case CONFIRM -> {
        clearPendingNewPin();
        showPinStep(PinStep.NEW);
      }
      case LOGIN -> { }
    }
  }

  private void submitPin() {
    if (!pinEntry.inputEnabled() || frame.isBusy()) {
      return;
    }
    char[] digits = pinEntry.readDigits();
    if (digits == null) {
      pinEntry.setFeedback("Vui lòng nhập đủ 6 chữ số PIN.");
      pinEntry.focusInput();
      return;
    }
    try {
      switch (pinStep) {
        case LOGIN, CURRENT -> verifyPin(new String(digits));
        case NEW -> {
          pendingNewPin = digits.clone();
          showPinStep(PinStep.CONFIRM);
        }
        case CONFIRM -> confirmPinChange(digits);
      }
    } finally {
      Arrays.fill(digits, '\0');
    }
  }

  private void verifyPin(String value) {
    try {
      Wire.pin(value);
    } catch (IllegalArgumentException e) {
      pinEntry.setFeedback(e.getMessage());
      return;
    }
    PinStep submitted = pinStep;
    if (submitted == PinStep.CURRENT) home = null;
    resetPinInput();
    frame.run(
        "Đang kiểm tra mã PIN…",
        () -> requestPinVerification.apply(new Api.Pin(value)),
        h -> {
          if (pinStep != submitted) return;
          if (submitted == PinStep.CURRENT) {
            home = h;
            showPinStep(PinStep.NEW);
            return;
          }
          resumeReceived(h);
        },
        e -> {
          if (pinStep != submitted) return;
          pinFailureVisible = e instanceof ApiClient.ApiError error && error.code.equals("PIN_WRONG");
          pinEntry.setWarning("");
          if (e instanceof ApiClient.ApiError error && error.code.equals("CARD_BLOCKED")) {
            blocked();
          } else {
            pinEntry.setFeedback(CanteenFrame.html(e.getMessage()));
            pinEntry.focusInput();
          }
          frame.poll();
        });
  }

  private void confirmPinChange(char[] digits) {
    resetPinInput();
    if (!Arrays.equals(pendingNewPin, digits)) {
      pinEntry.setFeedback("PIN mới và xác nhận không khớp.");
      pinEntry.focusInput();
      return;
    }
    Api.PinChange change = new Api.PinChange(new String(digits));
    clearPendingNewPin();
    frame.run(
        "Đang đổi mã PIN…",
        () -> requestPinChange.apply(change),
        result -> {
          if (pinStep != PinStep.CONFIRM) return;
          showHome(home);
          frame.info("Đổi mã PIN thành công!");
        },
        failure -> {
          if (pinStep != PinStep.CONFIRM) return;
          frame.error(failure);
          resume(() -> {});
        });
  }

  void topup(ObjLongConsumer<JDialog> onConfirm) {
    if (home == null) {
      return;
    }
    String errorText = CanteenFrame.html(
        "Số tiền cần nạp không được vượt quá\n1.000.000 VND!");
    JLabel error = new JLabel(errorText);
    error.setForeground(new Color(0xC62828));
    error.setPreferredSize(error.getPreferredSize());
    error.setText("");

    JTextField amount = InputFormats.moneyField(
        0, MAX_TOPUP_AMOUNT, exceeded -> error.setText(exceeded ? errorText : ""));
    amount.setPreferredSize(new Dimension(320, 32));
    JLabel currency = new JLabel("VND");
    int rightPadding = Math.max(0, 8 - FlatUIUtils.getBorderFocusAndLineWidth(amount));
    currency.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, rightPadding));
    amount.putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, currency);
    JLabel prompt = new JLabel("Nhập số tiền cần nạp");
    prompt.setLabelFor(amount);

    JDialog dialog = new JDialog(frame, "Nạp tiền", true);
    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    dialog.setResizable(false);

    JPanel input = new JPanel(new BorderLayout(0, 8));
    input.setOpaque(false);
    input.add(prompt, BorderLayout.NORTH);
    input.add(amount, BorderLayout.CENTER);
    input.add(error, BorderLayout.SOUTH);

    JButton ok = CanteenFrame.button("OK", e -> {
      if (frame.isBusy()) return;
      String digits = InputFormats.amountDigits(amount);
      long number = digits.isEmpty() ? 0 : Long.parseLong(digits);
      if (number < 1 || number > MAX_TOPUP_AMOUNT) {
        amount.requestFocusInWindow();
        return;
      }
      onConfirm.accept(dialog, number);
    });
    JButton cancel = CanteenFrame.button("Cancel", e -> {
      if (!frame.isBusy()) dialog.dispose();
    });
    Dimension buttonSize = new Dimension(90, 30);
    ok.setPreferredSize(buttonSize);
    cancel.setPreferredSize(buttonSize);
    JPanel buttons = new JPanel(new GridLayout(1, 2, 8, 0));
    buttons.setOpaque(false);
    buttons.add(ok);
    buttons.add(cancel);
    JPanel actions = new JPanel(new BorderLayout());
    actions.setOpaque(false);
    actions.add(buttons, BorderLayout.EAST);

    JPanel content = new JPanel(new BorderLayout(0, 8));
    content.setBackground(UIManager.getColor("OptionPane.background"));
    content.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
    content.add(input, BorderLayout.CENTER);
    content.add(actions, BorderLayout.SOUTH);
    dialog.setContentPane(content);
    dialog.getRootPane().setDefaultButton(ok);
    dialog.getRootPane().registerKeyboardAction(
        e -> cancel.doClick(0),
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
        JComponent.WHEN_IN_FOCUSED_WINDOW);
    dialog.pack();
    dialog.setLocationRelativeTo(this);
    SwingUtilities.invokeLater(amount::requestFocusInWindow);
    dialog.setVisible(true);
  }

  private void createQr(JDialog dialog, long amount) {
    dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    frame.run(
        dialog,
        "Đang tạo mã chuyển khoản…",
        () -> frame.api.post("/topups?includeExpiry=true&fullImage=true",
            new Api.Amount(amount), Api.TopupQr.class),
        offer -> {
          dialog.dispose();
          showQr(offer);
        },
        failure -> {
          dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
          frame.error(dialog, failure);
        });
  }

  private void resumeReceived(Api.StudentHome initial) {
    frame.run(
        "Đang kiểm tra khoản nạp…",
        () -> frame.api.get("/topups", Api.Topup[].class),
        list -> {
          Api.Topup pending = Arrays.stream(list)
              .filter(t -> t.status().equals("BANK_RECEIVED"))
              .min(Comparator.comparingLong(Api.Topup::id))
              .orElse(null);
          if (pending == null) {
            // A background credit may have completed just after PIN verification.
            frame.run("Đang tải thông tin thẻ…", () -> frame.api.get("/student/home", Api.StudentHome.class),
                this::showHome, failure -> showHome(initial));
          } else showPendingTopup(pending, initial);
        },
        failure -> {
          showHome(initial);
          frame.error(failure);
        });
  }

  private void showQr(Api.TopupQr offer) {
    closeQr();
    ImageIcon icon;
    try {
      icon = topupQrIcon(offer.topup().qrDataUrl());
    } catch (IOException | RuntimeException e) {
      frame.error(e);
      return;
    }

    activeTopup = offer.topup();
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(offer.remainingMillis());
    JPanel page = new JPanel(new BorderLayout(0, 16));
    page.setBackground(Color.WHITE);
    page.setBorder(BorderFactory.createEmptyBorder(0, 20, 20, 20));
    qrPage = page;

    originalQrIcon = icon;
    qrImage = new AnimatedBankQr();
    qrImage.setIcon(icon);
    qrImage.setPreferredSize(new Dimension(icon.getIconWidth(), icon.getIconHeight()));
    qrImage.getAccessibleContext().setAccessibleName("Mã QR chuyển khoản nạp tiền");
    JPanel qrColumn = new JPanel(new GridBagLayout());
    qrColumn.setOpaque(false);
    qrColumn.add(qrImage);

    JPanel details = new JPanel(new GridBagLayout());
    details.setOpaque(false);
    GridBagConstraints row = new GridBagConstraints();
    row.gridx = 0;
    row.gridy = 0;
    row.weightx = 1;
    row.fill = GridBagConstraints.HORIZONTAL;
    row.anchor = GridBagConstraints.WEST;
    JLabel detailsTitle = new JLabel("Thông tin nạp tiền", SwingConstants.CENTER);
    detailsTitle.setFont(detailsTitle.getFont().deriveFont(Font.BOLD, 32f));
    details.add(detailsTitle, row);

    row.gridy++;
    row.insets = new Insets(36, 0, 0, 0);
    details.add(new JLabel("Số tiền nạp"), row);
    row.gridy++;
    row.insets = new Insets(4, 0, 0, 0);
    JLabel amount = new JLabel(CanteenFrame.money(activeTopup.amount()));
    amount.setFont(amount.getFont().deriveFont(Font.BOLD, 24f));
    details.add(amount, row);

    row.gridy++;
    row.insets = new Insets(16, 0, 0, 0);
    details.add(new JLabel("Mã tham chiếu"), row);
    row.gridy++;
    row.insets = new Insets(4, 0, 0, 0);
    JLabel reference = new JLabel(activeTopup.reference());
    reference.setFont(reference.getFont().deriveFont(Font.BOLD, 16f));
    details.add(reference, row);

    row.gridy++;
    row.insets = new Insets(16, 0, 0, 0);
    JPanel divider = new JPanel();
    divider.setBackground(new Color(0xD0D0D0));
    divider.setPreferredSize(new Dimension(0, 1));
    details.add(divider, row);

    row.gridy++;
    row.insets = new Insets(16, 0, 0, 0);
    details.add(new JLabel("Mở ứng dụng ngân hàng/ví điện tử để tiến hành thanh toán"), row);
    Color warningColor = new Color(0xC62828);
    row.gridy++;
    row.insets = new Insets(4, 0, 0, 0);
    JLabel warningTitle = new JLabel("Lưu ý :");
    warningTitle.setForeground(warningColor);
    details.add(warningTitle, row);
    row.gridy++;
    JLabel referenceWarning = new JLabel("Không chỉnh sửa nội dung chuyển khoản!");
    referenceWarning.setForeground(warningColor);
    details.add(referenceWarning, row);
    row.gridy++;
    JLabel amountWarning = new JLabel("Chuyển đúng số tiền cần nạp!");
    amountWarning.setForeground(warningColor);
    details.add(amountWarning, row);

    row.gridy++;
    row.insets = new Insets(32, 0, 0, 0);
    details.add(new JLabel("Yêu cầu sẽ hết hạn trong", SwingConstants.CENTER), row);
    row.gridy++;
    row.insets = new Insets(4, 0, 0, 0);
    JLabel countdown = new JLabel("00:00", SwingConstants.CENTER);
    countdown.setFont(countdown.getFont().deriveFont(Font.PLAIN, 32f));
    countdown.setForeground(Color.BLACK);
    details.add(countdown, row);

    row.gridy++;
    row.insets = new Insets(32, 0, 0, 0);
    row.fill = GridBagConstraints.NONE;
    row.anchor = GridBagConstraints.CENTER;
    JButton cancelButton = CanteenFrame.button("Hủy yêu cầu", e -> cancelQr((JButton) e.getSource()));
    cancelTopupButton = cancelButton;
    cancelButton.setPreferredSize(new Dimension(320, 40));
    details.add(cancelButton, row);
    row.gridy++;
    row.insets = new Insets(16, 0, 0, 0);
    row.fill = GridBagConstraints.HORIZONTAL;
    qrStatus.setText(" ");
    qrStatus.setHorizontalAlignment(SwingConstants.CENTER);
    qrStatus.setForeground(warningColor);
    qrStatus.setPreferredSize(new Dimension(0, 32));
    details.add(qrStatus, row);
    page.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelTopup");
    page.getActionMap().put("cancelTopup", new AbstractAction() {
      @Override public void actionPerformed(ActionEvent e) { cancelButton.doClick(0); }
    });

    JPanel columns = new JPanel(new GridBagLayout());
    columns.setBackground(Color.WHITE);
    GridBagConstraints column = new GridBagConstraints();
    column.gridx = 0;
    column.gridy = 0;
    column.weightx = 1;
    column.weighty = 1;
    columns.add(qrColumn, column);
    column.gridx = 1;
    column.insets = new Insets(0, 32, 0, 0);
    columns.add(details, column);
    JScrollPane scroll = new JScrollPane(columns);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.getViewport().setBackground(Color.WHITE);
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    page.add(scroll, BorderLayout.CENTER);
    body.add(page, "topupQr");
    pages.show(body, "topupQr");
    page.revalidate();
    page.repaint();

    updateQrCountdown(page, countdown, deadline);
    if (qrPage != page) return;
    qrCountdown = new javax.swing.Timer(200, e -> updateQrCountdown(page, countdown, deadline));
    qrCountdown.start();
    startTopupWatch(page, activeTopup.id());
    SwingUtilities.invokeLater(cancelButton::requestFocusInWindow);
  }

  private static ImageIcon topupQrIcon(String dataUrl) throws IOException {
    byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1));
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
    if (image == null) throw new IOException("Không đọc được ảnh mã QR.");
    double scale = Math.min(420.0 / image.getWidth(), 480.0 / image.getHeight());
    int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
    int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
    BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = canvas.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, width, height);
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
          RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
      graphics.drawImage(image, 0, 0, width, height, null);
    } finally {
      graphics.dispose();
    }
    return new ImageIcon(canvas);
  }

  private void updateQrCountdown(JPanel page, JLabel countdown, long deadline) {
    if (qrPage != page) return;
    long remaining = Math.max(0, deadline - System.nanoTime());
    long seconds = TimeUnit.NANOSECONDS.toSeconds(remaining + 999_999_999L);
    countdown.setText(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60));
    if (remaining > 0) return;
    qrImage.setIcon(null);
    if (frame.isBusy() || !page.isShowing()) return;
    if (qrCountdown != null) qrCountdown.stop();
    long id = activeTopup.id();
    frame.run("Đang kiểm tra khoản nạp…", () -> requestTopupState.apply(id),
        this::showTopupResult, failure -> {
          qrStatus.setText("Chưa xác nhận được thời hạn. Đang thử kiểm tra lại.");
        });
  }

  private void showTopupResult(Api.Topup topup) {
    if (activeTopup != null && activeTopup.id() == topup.id())
      showTopupStatus(topup.status(), topup.message());
  }

  private void cancelQr(JButton cancelButton) {
    JPanel page = qrPage;
    Api.Topup topup = activeTopup;
    if (page == null || topup == null || frame.isBusy()) return;
    cancelButton.setEnabled(false);
    frame.run(
        "Đang hủy yêu cầu nạp…",
        () -> requestTopupCancellation.apply(topup.id()),
        result -> {
          if (qrPage != page) return;
          switch (result.status()) {
            case "BANK_RECEIVED", "CREDITED" -> showTopupStatus(result.status(), result.message());
            case "EXPIRED", "REVIEW" -> {
              closeQr();
              frame.info(result.message());
            }
            case null, default -> {
              cancelButton.setEnabled(true);
              qrStatus.setText("Chưa xác nhận hủy. Hãy thử lại.");
            }
          }
        },
        failure -> {
          if (qrPage != page) return;
          if (failure instanceof ApiClient.ApiError error
              && (error.status == 401 || error.status == 403 || error.status == 423
                  || error.code.equals("NO_CARD") || error.code.startsWith("SYNC_"))) {
            closeQr();
            frame.error(failure);
            frame.poll();
            return;
          }
          cancelButton.setEnabled(true);
          qrStatus.setText("Chưa xác nhận hủy. Hãy thử lại.");
        });
  }

  private void showPendingTopup(Api.Topup topup, Api.StudentHome initial) {
    closeQr();
    home = initial;
    activeTopup = topup;
    receivedTopup = true;
    JPanel page = new JPanel(new GridBagLayout());
    page.setBackground(Color.WHITE);
    JPanel content = new JPanel();
    content.setOpaque(false);
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    JLabel title = new JLabel("Đã nhận tiền. Đang ghi vào thẻ…", SwingConstants.CENTER);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
    title.setAlignmentX(Component.CENTER_ALIGNMENT);
    JLabel amount = new JLabel(CanteenFrame.money(topup.amount()), SwingConstants.CENTER);
    amount.setAlignmentX(Component.CENTER_ALIGNMENT);
    qrStatus.setText("Đang kết nối trạng thái khoản nạp…");
    qrStatus.setAlignmentX(Component.CENTER_ALIGNMENT);
    content.add(title);
    content.add(Box.createVerticalStrut(12));
    content.add(amount);
    content.add(Box.createVerticalStrut(16));
    content.add(qrStatus);
    cancelTopupButton = CanteenFrame.button("Tải lại số dư", e -> finishTopup());
    cancelTopupButton.setAlignmentX(Component.CENTER_ALIGNMENT);
    cancelTopupButton.setVisible(false);
    content.add(Box.createVerticalStrut(16));
    content.add(cancelTopupButton);
    page.add(content);
    qrPage = page;
    body.add(page, "topupQr");
    pages.show(body, "topupQr");
    frame.showTopupWait("Đã nhận tiền. Đang ghi vào thẻ…");
    startTopupWatch(page, topup.id());
  }

  private void startTopupWatch(JPanel page, long id) {
    long epoch = frame.sessionEpoch();
    qrStatus.getAccessibleContext().setAccessibleName("Trạng thái yêu cầu nạp tiền");
    topupStream = watchTopup.watch(id,
        event -> SwingUtilities.invokeLater(() -> {
          if (epoch == frame.sessionEpoch() && qrPage == page && activeTopup != null
              && activeTopup.id() == id && event.id() == id) receiveTopupEvent(event);
        }),
        warning -> SwingUtilities.invokeLater(() -> {
          if (epoch == frame.sessionEpoch() && qrPage == page && !creditedTopup) {
            topupWarning = warning;
            if (receivedTopup) updateReceivedTopupWait();
            else {
              frame.hideTopupWait();
              qrStatus.setText(warning);
            }
          }
        }),
        connected -> SwingUtilities.invokeLater(() -> {
          if (epoch != frame.sessionEpoch() || qrPage != page) return;
          topupDisconnected = !connected;
          if (receivedTopup && !creditedTopup) {
            updateReceivedTopupWait();
          } else if (!connected && !creditedTopup) {
            frame.hideTopupWait();
            qrStatus.setText("Chưa kiểm tra được chuyển khoản. Đang kết nối lại.");
          } else if (connected && !creditedTopup) qrStatus.setText(" ");
        }),
        failure -> SwingUtilities.invokeLater(() -> {
          if (epoch != frame.sessionEpoch() || qrPage != page) return;
          topupDisconnected = true;
          frame.hideTopupWait();
          qrStatus.setText("Chưa theo dõi được khoản nạp. Vui lòng kiểm tra kết nối thẻ.");
          frame.poll();
        }));
  }

  private void receiveTopupEvent(Api.TopupEvent event) {
    if (event.revision() <= lastTopupRevision) return;
    if (frame.isBusy()) {
      if (deferredTopupEvent == null || event.revision() > deferredTopupEvent.revision())
        deferredTopupEvent = event;
      if (pendingEventTimer == null) {
        pendingEventTimer = new javax.swing.Timer(150, ignored -> {
          if (frame.isBusy()) return;
          pendingEventTimer.stop();
          pendingEventTimer = null;
          Api.TopupEvent pending = deferredTopupEvent;
          deferredTopupEvent = null;
          if (pending != null && activeTopup != null && pending.id() == activeTopup.id())
            receiveTopupEvent(pending);
        });
        pendingEventTimer.start();
      }
      return;
    }
    lastTopupRevision = event.revision();
    showTopupStatus(event.status(), event.message());
  }

  private void showTopupStatus(String status, String message) {
    if (qrPage == null || activeTopup == null) return;
    switch (Objects.toString(status, "")) {
      case "WAITING_BANK" -> {
        if (qrImage == null || qrImage.getIcon() == null)
          qrStatus.setText("Đang kiểm tra thời hạn yêu cầu nạp.");
      }
      case "BANK_RECEIVED" -> {
        receivedTopup = true;
        if (qrCountdown != null) qrCountdown.stop();
        if (qrImage != null && qrImage.getIcon() == null) qrImage.setIcon(originalQrIcon);
        if (cancelTopupButton != null) cancelTopupButton.setEnabled(false);
        updateReceivedTopupWait();
      }
      case "CREDITED" -> finishTopup();
      case "EXPIRED", "REVIEW" -> {
        closeQr();
        frame.info("EXPIRED".equals(status) ? TOPUP_EXPIRED_MESSAGE : message);
      }
      default -> qrStatus.setText("Chưa xác định được trạng thái khoản nạp.");
    }
  }

  private void updateReceivedTopupWait() {
    if (topupDisconnected) {
      frame.hideTopupWait();
      qrStatus.setText("Đã nhận tiền. Đang kết nối lại để kiểm tra thẻ.");
    } else if (topupWarning != null) {
      qrStatus.setText(topupWarning);
      // The existing event protocol sends warning text. Unknown warnings must remain actionable.
      if (TOPUP_RETRY_WARNING.equals(topupWarning)) frame.showTopupWait(topupWarning);
      else frame.hideTopupWait();
    } else {
      qrStatus.setText("Đã nhận tiền. Đang ghi vào thẻ…");
      frame.showTopupWait("Đã nhận tiền. Đang ghi vào thẻ…");
    }
  }

  private void finishTopup() {
    if (qrPage == null || activeTopup == null || finishingTopup) return;
    receivedTopup = true;
    creditedTopup = true;
    if (qrCountdown != null) qrCountdown.stop();
    if (qrImage != null && qrImage.getIcon() == null) qrImage.setIcon(originalQrIcon);
    frame.showTopupWait("Đang tải số dư…");
    if (frame.isBusy()) { scheduleHomeRetry(150); return; }
    finishingTopup = true;
    JPanel page = qrPage;
    qrStatus.setText("Đã ghi thẻ. Đang tải số dư…");
    frame.run("Đang tải số dư…", () -> frame.api.get("/student/home", Api.StudentHome.class),
        latest -> {
          if (qrPage != page) return;
          closeQr();
          showHome(latest);
          frame.info("Nạp tiền thành công!\nSố dư hiện tại: " + CanteenFrame.money(latest.balance()));
        },
        failure -> {
          if (qrPage != page) return;
          finishingTopup = false;
          frame.hideTopupWait();
          if (cancelTopupButton != null) {
            if (!cancelTopupButton.getText().equals("Tải lại số dư")) {
              for (var listener : cancelTopupButton.getActionListeners()) cancelTopupButton.removeActionListener(listener);
              cancelTopupButton.setText("Tải lại số dư");
              cancelTopupButton.addActionListener(e -> finishTopup());
            }
            cancelTopupButton.setVisible(true);
            cancelTopupButton.setEnabled(true);
          }
          qrStatus.setText("Đã ghi thẻ. Chưa tải được số dư; vui lòng thử lại.");
          if (++homeRetryCount <= 4) scheduleHomeRetry(1000 * (1 << (homeRetryCount - 1)));
        });
  }

  private void scheduleHomeRetry(int delay) {
    if (homeRetryTimer != null) homeRetryTimer.stop();
    homeRetryTimer = new javax.swing.Timer(delay, ignored -> {
      homeRetryTimer.stop();
      homeRetryTimer = null;
      finishTopup();
    });
    homeRetryTimer.setRepeats(false);
    homeRetryTimer.start();
  }

  private void closeQr() {
    frame.hideTopupWait();
    if (qrCountdown != null) {
      qrCountdown.stop();
      qrCountdown = null;
    }
    if (pendingEventTimer != null) { pendingEventTimer.stop(); pendingEventTimer = null; }
    if (homeRetryTimer != null) { homeRetryTimer.stop(); homeRetryTimer = null; }
    if (topupStream != null) {
      try { topupStream.close(); } catch (Exception ignored) {}
      topupStream = null;
    }
    if (qrPage != null) {
      JPanel page = qrPage;
      qrPage = null;
      body.remove(page);
      if (home != null) pages.show(body, "home");
      body.revalidate();
      body.repaint();
    }
    if (qrImage != null) qrImage.setIcon(null);
    qrImage = null;
    originalQrIcon = null;
    activeTopup = null;
    cancelTopupButton = null;
    deferredTopupEvent = null;
    lastTopupRevision = -1;
    topupWarning = null;
    topupDisconnected = false;
    finishingTopup = receivedTopup = creditedTopup = false;
    homeRetryCount = 0;
  }
}
