package vn.edu.smartcard.ttm_cuoiky;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.util.Arrays;
import java.util.Objects;
import javax.swing.*;
import vn.edu.smartcard.shared.Api;
import vn.edu.smartcard.shared.Profile;

final class AdminCardView extends JPanel {
  private enum Step { VIEW, EDIT, ENROLL, NEW_PIN, CONFIRM_PIN }
  private static final String BLOCKED_MESSAGE =
      "Thẻ đang tạm khóa. Khôi phục thẻ trước khi chỉnh sửa hoặc đặt lại PIN.";
  private static final String RECONNECT_MESSAGE = "Kết nối đúng thẻ để lưu thay đổi.";
  private static final String SYNC_MESSAGE = "Thẻ đang chờ đồng bộ. Vui lòng đồng bộ trước khi lưu.";

  private final CanteenFrame frame;
  private final Runnable backToList;
  private final Runnable profileChanged;
  private final CardLayout pages = new CardLayout();
  private final ProfileForm form = ProfileForm.forAdminDetails();
  private final SixDigitEntry pinEntry;
  private final JLabel feedback = new JLabel(" ", SwingConstants.CENTER);
  private final JLabel connectionIndicator = new JLabel(new ConnectionIcon());
  private final JLabel connectedCardId = new JLabel("------------");
  private final JButton connectedCard = new JButton();
  private final JPanel content = new JPanel(new BorderLayout());
  private final JPanel bottom = new JPanel(new BorderLayout());
  private final JPanel nextRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 8));
  private final JButton edit, reset, save, cancel, next;
  private CardTransactionsView transactions;
  private String historyCardId = "";
  private Api.ReaderStatus card;
  private Profile saved, draft;
  private String displayedStatus = "";
  private boolean opened;
  private Step step = Step.VIEW;
  private char[] firstPin;

  AdminCardView(CanteenFrame frame, Runnable backToList, Runnable profileChanged) {
    this.frame = frame;
    this.backToList = backToList;
    this.profileChanged = profileChanged;
    setLayout(pages);
    setOpaque(false);

    JButton back = CanteenFrame.button("", e -> leave());
    back.setIcon(DesktopIcons.svg("/icons/back-svgrepo-com.svg", 24, "biểu tượng quay lại"));
    back.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
    back.setBorderPainted(false);
    back.setMargin(new Insets(0, 0, 0, 0));
    back.setPreferredSize(new Dimension(32, 32));
    back.setToolTipText("Quay lại danh sách");
    back.getAccessibleContext().setAccessibleName("Quay lại danh sách");

    JLabel title = new JLabel("Thông tin thẻ");
    title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
    title.setBorder(BorderFactory.createEmptyBorder(12, 0, 12, 0));
    connectedCard.setLayout(new BorderLayout());
    connectedCard.setMargin(new Insets(0, 8, 0, 8));
    connectedCard.setPreferredSize(new Dimension(128, 32));
    connectedCard.setFocusable(false);
    connectedCard.setRequestFocusEnabled(false);
    connectedCard.add(connectedCardId, BorderLayout.WEST);
    connectedCard.add(connectionIndicator, BorderLayout.EAST);
    edit = headerButton("Chỉnh sửa", () -> { if (canEdit()) showProfile(Step.EDIT); });
    reset = headerButton("Đặt lại PIN", () -> { if (canEdit()) showPin(Step.NEW_PIN); });
    save = headerButton("Lưu", this::save);
    cancel = headerButton("Huỷ bỏ", () -> confirmDiscard());
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    actions.setOpaque(false);
    actions.add(edit);
    actions.add(reset);
    actions.add(save);
    actions.add(cancel);
    JPanel actionArea = new JPanel(new GridBagLayout());
    actionArea.setOpaque(false);
    actionArea.add(actions);
    JPanel heading = new JPanel(new BorderLayout());
    heading.setOpaque(false);
    heading.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 8));
    JPanel headingTitle = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    headingTitle.setOpaque(false);
    headingTitle.add(back);
    headingTitle.add(Box.createHorizontalStrut(8));
    headingTitle.add(title);
    headingTitle.add(Box.createHorizontalStrut(16));
    headingTitle.add(connectedCard);
    heading.add(headingTitle, BorderLayout.WEST);
    heading.add(feedback, BorderLayout.CENTER);
    heading.add(actionArea, BorderLayout.EAST);
    JPanel divider = new JPanel();
    divider.setBackground(new Color(0xD0D0D0));
    divider.setPreferredSize(new Dimension(0, 1));
    heading.add(divider, BorderLayout.SOUTH);

    next = CanteenFrame.button("Tiếp tục", e -> enroll());
    next.setPreferredSize(new Dimension(240, 48));
    nextRow.setOpaque(false);
    nextRow.add(next);
    bottom.setOpaque(false);
    bottom.add(nextRow, BorderLayout.SOUTH);
    JPanel profileSection = new JPanel(new BorderLayout());
    profileSection.setBackground(Color.WHITE);
    profileSection.add(form, BorderLayout.NORTH);
    profileSection.add(bottom, BorderLayout.CENTER);
    content.setBackground(Color.WHITE);
    content.add(profileSection, BorderLayout.NORTH);
    JScrollPane scroll = new JScrollPane(content);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.getViewport().setBackground(Color.WHITE);
    scroll.getVerticalScrollBar().setUnitIncrement(16);

    JPanel profile = new JPanel(new BorderLayout());
    profile.setBackground(Color.WHITE);
    profile.add(heading, BorderLayout.NORTH);
    profile.add(scroll, BorderLayout.CENTER);
    profile.registerKeyboardAction(e -> leave(), KeyStroke.getKeyStroke("ESCAPE"),
        JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
    add(profile, "profile");

    pinEntry = new SixDigitEntry(SixDigitEntry.Mode.PIN, "", "", this::submitPin,
        this::clearPinFeedback);
    pinEntry.addBackButton("Quay lại", this::backFromPin);
    add(pinEntry, "pin");
    form.cardId.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Cấp sau khi khởi tạo");
    close();
  }

  private static JButton headerButton(String label, Runnable action) {
    JButton button = CanteenFrame.button(label, e -> action.run());
    button.setPreferredSize(new Dimension(128, 32));
    return button;
  }

  void open(Api.AdminCard result) {
    opened = true;
    card = result.status();
    saved = result.profile();
    displayedStatus = card.lifecycleStatus();
    form.show(saved);
    showTransactions(saved);
    showProfile(saved == null ? Step.ENROLL : Step.VIEW);
    if (saved == null) SwingUtilities.invokeLater(form.code::requestFocusInWindow);
  }

  void openProfile(Profile profile, String lifecycleStatus, Api.ReaderStatus current) {
    opened = true;
    saved = profile;
    displayedStatus = Objects.toString(lifecycleStatus, "");
    card = matchesProfile(current) ? current : null;
    if (card != null && "BLOCKED".equals(card.lifecycleStatus())) displayedStatus = "BLOCKED";
    form.show(saved);
    showTransactions(saved);
    showProfile(Step.VIEW);
  }

  private void showTransactions(Profile profile) {
    String cardId = profile == null ? "" : Objects.toString(profile.cardId(), "");
    if (!cardId.isBlank() && cardId.equals(historyCardId) && transactions != null) return;
    if (transactions != null) {
      transactions.clear();
      content.remove(transactions);
      transactions = null;
    }
    historyCardId = cardId;
    if (!cardId.isBlank()) {
      transactions = CardTransactionsView.forAdmin(frame, cardId);
      content.add(transactions, BorderLayout.CENTER);
      transactions.reload();
    }
    content.revalidate();
    content.repaint();
  }

  boolean isOpen() {
    return opened;
  }

  private boolean matchesProfile(Api.ReaderStatus current) {
    return saved != null && current != null && current.present() && current.initialized()
        && saved.cardId().equals(current.cardId()) && !current.connectionId().isBlank();
  }

  boolean onReaderChanged(Api.ReaderStatus current) {
    if (!isOpen()) return true;
    String previousConnection = card == null ? "" : card.connectionId();
    if (saved == null) {
      if (card == null || !current.present() || current.initialized()
          || !previousConnection.equals(current.connectionId())) {
        close();
        return false;
      }
      card = current;
    } else {
      card = matchesProfile(current) ? current : null;
      if (card != null) displayedStatus = card.lifecycleStatus();
    }
    boolean pinStep = step == Step.NEW_PIN || step == Step.CONFIRM_PIN;
    if (pinStep && (!canWrite() || !previousConnection.equals(card.connectionId()))) {
      if (saved != null) form.show(saved);
      showProfile(saved == null ? Step.ENROLL : Step.VIEW);
    } else {
      refreshControls();
    }
    return true;
  }

  boolean connectionUnavailable() {
    if (!isOpen()) return true;
    if (saved == null) {
      close();
      return false;
    }
    card = null;
    if (step == Step.NEW_PIN || step == Step.CONFIRM_PIN) {
      form.show(saved);
      showProfile(Step.VIEW);
    } else {
      refreshControls();
    }
    return true;
  }

  void syncChanged() {
    if (!isOpen()) return;
    if ((step == Step.NEW_PIN || step == Step.CONFIRM_PIN) && !canWrite()) {
      if (saved != null) form.show(saved);
      showProfile(saved == null ? Step.ENROLL : Step.VIEW);
    } else {
      refreshControls();
    }
  }

  void close() {
    opened = false;
    card = null;
    saved = null;
    displayedStatus = "";
    showTransactions(null);
    form.show(null);
    showProfile(Step.VIEW);
  }

  private void showProfile(Step nextStep) {
    forgetPin();
    draft = null;
    pinEntry.clear();
    step = nextStep;
    edit.setVisible(nextStep == Step.VIEW);
    reset.setVisible(nextStep == Step.VIEW);
    save.setVisible(nextStep == Step.EDIT);
    cancel.setVisible(nextStep == Step.EDIT);
    next.setVisible(nextStep == Step.ENROLL);
    form.setEditable(nextStep == Step.EDIT || nextStep == Step.ENROLL);
    TTM_CuoiKy.statusText(feedback, " ", false);
    refreshControls();
    pages.show(this, "profile");
  }

  private boolean canEdit() {
    return isOpen() && saved != null && card != null
        && "ACTIVE".equals(displayedStatus) && "ACTIVE".equals(card.lifecycleStatus())
        && frame.cardReady();
  }

  private boolean canWrite() {
    return isOpen() && card != null && frame.cardReady()
        && (saved == null ? !card.initialized()
            : "ACTIVE".equals(displayedStatus) && "ACTIVE".equals(card.lifecycleStatus()));
  }

  private void refreshControls() {
    boolean connected = isOpen() && card != null;
    connectionIndicator.setForeground(connected ? new Color(0x208A46) : new Color(0xA0A0A0));
    String cardId = connected && card.initialized() ? Objects.toString(card.cardId(), "").strip() : "";
    connectedCardId.setText(cardId.isBlank() ? "------------" : cardId);
    String connectionText = connected ? "Đã kết nối đúng thẻ" : "Chưa kết nối đúng thẻ";
    connectionIndicator.setToolTipText(connectionText);
    connectionIndicator.getAccessibleContext().setAccessibleName(connectionText);
    connectedCard.setToolTipText(connectionText);
    connectedCard.getAccessibleContext().setAccessibleName(connectionText);
    connectionIndicator.repaint();
    boolean editable = canEdit();
    edit.setEnabled(editable);
    reset.setEnabled(editable);
    save.setEnabled(step == Step.EDIT && editable);
    next.setEnabled(step == Step.ENROLL && canWrite());
    boolean blocked = isOpen() && "BLOCKED".equals(displayedStatus);
    if (blocked) {
      TTM_CuoiKy.statusText(feedback, BLOCKED_MESSAGE, true);
    } else if (step == Step.EDIT && !editable) {
      TTM_CuoiKy.statusText(feedback, card != null && !frame.cardReady() ? SYNC_MESSAGE : RECONNECT_MESSAGE, true);
    } else if (BLOCKED_MESSAGE.equals(feedback.getText()) || RECONNECT_MESSAGE.equals(feedback.getText())
        || SYNC_MESSAGE.equals(feedback.getText())) {
      TTM_CuoiKy.statusText(feedback, " ", false);
    }
    nextRow.setVisible(step == Step.ENROLL);
    bottom.setVisible(step == Step.ENROLL);
    bottom.revalidate();
  }

  static final class ConnectionIcon implements Icon {
    @Override public int getIconWidth() { return 12; }
    @Override public int getIconHeight() { return 12; }
    @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
      Graphics2D g = (Graphics2D) graphics.create();
      try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(component.getForeground());
        g.fillOval(x, y, getIconWidth(), getIconHeight());
      } finally {
        g.dispose();
      }
    }
  }

  boolean confirmDiscard() {
    if (!isOpen() || step == Step.VIEW) return true;
    Object[] options = {"Huỷ bỏ", "Sửa tiếp"};
    int choice = JOptionPane.showOptionDialog(this, "Huỷ bỏ các thay đổi chưa lưu?",
        "Thay đổi chưa lưu", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
        null, options, options[1]);
    if (choice != 0) return false;
    form.show(saved);
    showProfile(saved == null ? Step.ENROLL : Step.VIEW);
    return true;
  }

  boolean confirmReaderChange() {
    if (isOpen() && saved != null && (step == Step.VIEW || step == Step.EDIT)) return true;
    return confirmDiscard();
  }

  private void leave() {
    if (!frame.isBusy() && confirmDiscard()) backToList.run();
  }

  private Profile readForm() {
    try {
      return form.value();
    } catch (IllegalArgumentException invalid) {
      TTM_CuoiKy.statusText(feedback, CanteenFrame.html(invalid.getMessage()), true);
      return null;
    }
  }

  private void enroll() {
    if (step != Step.ENROLL || frame.isBusy() || !canWrite()) return;
    draft = readForm();
    if (draft != null) showPin(Step.NEW_PIN);
  }

  private void showPin(Step nextStep) {
    if (nextStep == Step.NEW_PIN) forgetPin();
    step = nextStep;
    pinEntry.clear();
    pinEntry.setRoundBackButton(saved != null);
    boolean confirm = nextStep == Step.CONFIRM_PIN;
    String title = confirm ? "Xác nhận mã PIN" : saved == null ? "Thiết lập mã PIN" : "Nhập mã PIN mới";
    pinEntry.setPrompt(title, confirm ? "Hoàn tất" : "Tiếp tục");
    pinEntry.setInputEnabled(true);
    pages.show(this, "pin");
    SwingUtilities.invokeLater(pinEntry::focusInput);
  }

  private void clearPinFeedback() {
    pinEntry.setFeedback("");
  }

  private void backFromPin() {
    if (frame.isBusy()) return;
    if (step == Step.CONFIRM_PIN) showPin(Step.NEW_PIN);
    else showProfile(saved == null ? Step.ENROLL : Step.VIEW);
  }

  private void submitPin() {
    if (frame.isBusy() || !canWrite() || (step != Step.NEW_PIN && step != Step.CONFIRM_PIN)) return;
    char[] digits = pinEntry.readDigits();
    if (digits == null) {
      pinEntry.setFeedback("Vui lòng nhập đủ 6 chữ số PIN.");
      pinEntry.focusInput();
      return;
    }
    try {
      if (step == Step.NEW_PIN) {
        firstPin = digits.clone();
        showPin(Step.CONFIRM_PIN);
      } else if (!Arrays.equals(firstPin, digits)) {
        pinEntry.clear();
        pinEntry.setFeedback("PIN mới và xác nhận không khớp.");
        pinEntry.focusInput();
      } else {
        String value = new String(digits);
        forgetPin();
        pinEntry.clear();
        if (saved == null) initialize(value);
        else reset(value);
      }
    } finally {
      Arrays.fill(digits, '\0');
    }
  }

  private void forgetPin() {
    if (firstPin != null) {
      Arrays.fill(firstPin, '\0');
      firstPin = null;
    }
  }

  private void initialize(String pin) {
    String path = cardPath("/admin/card/init", card, null);
    Api.Enrollment request = new Api.Enrollment(draft, pin);
    frame.run("Đang khởi tạo thẻ…", () -> frame.api.post(path, request, Profile.class),
        profile -> {
          showSaved(profile);
          frame.info("Khởi tạo thẻ thành công!");
          frame.refreshCard();
        },
        failure -> {
          showProfile(Step.ENROLL);
          frame.admin.cardError(failure);
        });
  }

  private void reset(String pin) {
    String path = cardPath("/admin/card/reset-pin", card, saved.cardId());
    frame.run("Đang đặt lại mã PIN…", () -> frame.api.post(path, new Api.Pin(pin), Api.Result.class),
        result -> {
          showProfile(Step.VIEW);
          frame.info("Đặt lại PIN thành công!");
          frame.refreshCard();
        },
        failure -> {
          showPin(Step.NEW_PIN);
          frame.admin.cardError(failure);
        });
  }

  private void save() {
    if (step != Step.EDIT || frame.isBusy() || !canEdit()) return;
    Profile profile = readForm();
    if (profile == null) return;
    String path = cardPath("/admin/card/update", card, saved.cardId());
    frame.run("Đang lưu hồ sơ…", () -> frame.api.post(path, new Api.CardMutation(profile), Profile.class),
        result -> {
          showSaved(result);
          frame.info("Thay đổi thông tin thẻ thành công!");
        }, frame.admin::cardError);
  }

  private void showSaved(Profile profile) {
    saved = profile;
    if (!matchesProfile(card)) card = null;
    form.show(saved);
    showTransactions(saved);
    showProfile(Step.VIEW);
    profileChanged.run();
  }

  static String cardPath(String path, Api.ReaderStatus card, String id) {
    String bound = path + "?connectionId=" + ApiClient.query(card.connectionId());
    return id == null ? bound : bound + "&cardId=" + ApiClient.query(id);
  }
}
