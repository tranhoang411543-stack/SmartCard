package vn.edu.smartcard.ttm_cuoiky;

import java.awt.*;
import java.awt.event.*;
import java.util.Arrays;
import javax.swing.*;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

final class SixDigitEntry extends JPanel {
  private static final int DIGIT_WIDTH = 64;
  private static final int DIGIT_HEIGHT = 80;
  private static final int GAP = 16;
  private static final int MESSAGE_HEIGHT = 24;
  private static final int OTP_MESSAGE_GAP = 32;
  private static final int KEYBOARD_SIZE = 64;
  private static final int KEYBOARD_INSET = 8;
  private static final Dimension SUBMIT_SIZE = new Dimension(240, 48);
  private static final Icon KEYBOARD_ICON =
      DesktopIcons.svg("/icons/keyboard-svgrepo-com.svg", 32, "biểu tượng bàn phím số");
  private static final Icon BACK_ICON =
      DesktopIcons.svg("/icons/back-svgrepo-com.svg", 32, "biểu tượng quay lại");
  private static final Icon CLEAR_ICON =
      DesktopIcons.svg("/icons/clear-inverse-reflect-horizontal.svg", 48, "biểu tượng xóa mã");
  private static final Icon DONE_ICON =
      DesktopIcons.svg("/icons/ok-svgrepo-com.svg", 32, "biểu tượng hoàn tất");

  enum Mode { PIN, OTP }

  private final Mode mode;
  private final Timer otpFeedbackTimeout;
  private final JLabel title = new JLabel("", SwingConstants.CENTER);
  private final JLabel description = new JLabel("", SwingConstants.CENTER);
  private final JLabel feedback = new JLabel("", SwingConstants.CENTER);
  private final JLabel warning = new JLabel("", SwingConstants.CENTER);
  private final JPasswordField[] digits = new JPasswordField[6];
  private final JPanel digitRow;
  private final int digitWidth, digitHeight, gap;
  private final Dimension submitSize;
  private final JButton submit;
  private final JButton keyboardButton;
  private final JPopupMenu keyboard = new JPopupMenu();
  private final Runnable onEdit;
  private boolean keypadInput;
  private JButton backButton;
  private String backLabel;
  private boolean roundBackButton;

  SixDigitEntry(Mode mode, String titleText, String actionText,
      Runnable onSubmit, Runnable onEdit) {
    this(mode, titleText, actionText, onSubmit, onEdit, false);
  }

  SixDigitEntry(Mode mode, String titleText, String actionText,
      Runnable onSubmit, Runnable onEdit, boolean compact) {
    super(null);
    this.mode = mode;
    digitWidth = compact ? 48 : DIGIT_WIDTH;
    digitHeight = compact ? 60 : DIGIT_HEIGHT;
    gap = compact ? 10 : GAP;
    submitSize = compact ? new Dimension(220, 42) : SUBMIT_SIZE;
    digitRow = new JPanel(new GridLayout(1, digits.length, gap, 0));
    otpFeedbackTimeout = mode == Mode.OTP ? new Timer(5_000, e -> setFeedback("")) : null;
    if (otpFeedbackTimeout != null) otpFeedbackTimeout.setRepeats(false);
    this.onEdit = onEdit;
    setBackground(Color.WHITE);
    title.setText(titleText);
    title.setFont(title.getFont().deriveFont(Font.BOLD, compact ? 24f : 32f));
    title.setForeground(TTM_CuoiKy.DEFAULT_TEXT_COLOR);
    feedback.setFont(feedback.getFont().deriveFont(mode == Mode.OTP ? Font.PLAIN : Font.BOLD, 16f));
    feedback.setForeground(TTM_CuoiKy.ERROR_TEXT_COLOR);
    warning.setFont(warning.getFont().deriveFont(16f));
    warning.setForeground(TTM_CuoiKy.ERROR_TEXT_COLOR);
    description.setVisible(false);
    feedback.setVisible(false);
    warning.setVisible(false);
    digitRow.setOpaque(false);
    keyboard.putClientProperty(DesktopTextFocus.POPUP_INPUT_GROUP, digitRow);
    for (int i = 0; i < digits.length; i++) {
      int index = i;
      JPasswordField digit = new JPasswordField(1);
      digits[i] = digit;
      digit.setEchoChar(mode == Mode.PIN ? '*' : '\0');
      digit.setHorizontalAlignment(SwingConstants.CENTER);
      digit.setFont(digit.getFont().deriveFont(Font.BOLD, compact ? 24f : 32f));
      digit.setForeground(TTM_CuoiKy.DEFAULT_TEXT_COLOR);
      digit.getAccessibleContext().setAccessibleName("Chữ số " + mode.name() + " thứ " + (i + 1));
      Dimension size = new Dimension(digitWidth, digitHeight);
      digit.setMinimumSize(size);
      digit.setPreferredSize(size);
      digit.setMaximumSize(size);
      ((AbstractDocument) digit.getDocument()).setDocumentFilter(new DigitFilter());
      digit.addFocusListener(new FocusAdapter() {
        @Override
        public void focusGained(FocusEvent e) {
          int next = firstEmptyDigit();
          if (next >= 0 && next != index)
            SwingUtilities.invokeLater(() -> digits[next].requestFocusInWindow());
        }
      });
      digit.addKeyListener(new KeyAdapter() {
        @Override
        public void keyPressed(KeyEvent e) {
          if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
            eraseFrom(index);
            e.consume();
          } else if (e.getKeyCode() == KeyEvent.VK_DELETE) {
            digit.setText("");
            e.consume();
          }
        }
      });
      digit.addActionListener(e -> onSubmit.run());
      digitRow.add(digit);
    }
    submit = CanteenFrame.button(actionText, e -> onSubmit.run());
    submit.setFont(submit.getFont().deriveFont(Font.BOLD, 16f));
    submit.setMinimumSize(submitSize);
    submit.setPreferredSize(submitSize);
    submit.setMaximumSize(submitSize);
    keyboardButton = createKeyboardButton();
    keyboardButton.addActionListener(e -> toggleKeyboard());
    configureKeyboard(mode.name());
    add(keyboardButton);
    add(title);
    add(description);
    add(digitRow);
    add(feedback);
    if (mode == Mode.PIN) add(warning);
    add(submit);
  }

  void addBackButton(Runnable onBack) {
    addBackButton("Quay lại đăng nhập", onBack);
  }

  void addBackButton(String label, Runnable onBack) {
    backLabel = label;
    backButton = new JButton(label) {
      @Override protected void paintComponent(Graphics graphics) {
        if (roundBackButton) paintRoundButton(this, graphics, BACK_ICON);
        else super.paintComponent(graphics);
      }
    };
    backButton.addActionListener(e -> onBack.run());
    backButton.setFont(backButton.getFont().deriveFont(Font.PLAIN, 16f));
    backButton.setPreferredSize(new Dimension(192, 32));
    add(backButton, 0);
    registerKeyboardAction(e -> backButton.doClick(0),
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), WHEN_IN_FOCUSED_WINDOW);
  }

  void setRoundBackButton(boolean round) {
    if (backButton == null || roundBackButton == round) return;
    roundBackButton = round;
    backButton.setText(round ? "" : backLabel);
    backButton.setIcon(round ? BACK_ICON : null);
    backButton.setToolTipText(round ? backLabel : null);
    backButton.getAccessibleContext().setAccessibleName(backLabel);
    backButton.setBorderPainted(!round);
    backButton.setContentAreaFilled(!round);
    backButton.setFocusPainted(!round);
    backButton.setRolloverEnabled(!round);
    backButton.setPreferredSize(round ? new Dimension(KEYBOARD_SIZE, KEYBOARD_SIZE) : new Dimension(192, 32));
    revalidate();
    repaint();
  }

  void setBackButtonVisible(boolean visible) {
    if (backButton != null) backButton.setVisible(visible);
  }

  @Override
  public void doLayout() {
    int centerX = getWidth() / 2;
    int rowWidth = digits.length * digitWidth + (digits.length - 1) * gap;
    int rowTop = Math.max(0, (getHeight() - digitHeight) / 2);
    Dimension titleSize = title.getPreferredSize();
    title.setBounds(centerX - titleSize.width / 2,
        Math.max(0, rowTop - 32 - titleSize.height), titleSize.width, titleSize.height);
    digitRow.setBounds(centerX - rowWidth / 2, rowTop, rowWidth, digitHeight);
    int feedbackTop = rowTop + digitHeight + (mode == Mode.OTP ? OTP_MESSAGE_GAP : gap);
    description.setBounds(0, feedbackTop, getWidth(), MESSAGE_HEIGHT);
    feedback.setBounds(0, feedbackTop, getWidth(), MESSAGE_HEIGHT);
    int submitTop = feedbackTop + MESSAGE_HEIGHT + OTP_MESSAGE_GAP;
    if (mode == Mode.PIN) {
      int warningTop = feedbackTop + MESSAGE_HEIGHT + gap;
      warning.setBounds(0, warningTop, getWidth(), MESSAGE_HEIGHT);
      submitTop = warningTop + MESSAGE_HEIGHT + gap;
    }
    submit.setBounds(centerX - submitSize.width / 2, submitTop,
        submitSize.width, submitSize.height);
    keyboardButton.setBounds(KEYBOARD_INSET,
        Math.max(KEYBOARD_INSET, getHeight() - KEYBOARD_SIZE - KEYBOARD_INSET),
        KEYBOARD_SIZE, KEYBOARD_SIZE);
    if (backButton != null) {
      Dimension size = backButton.getPreferredSize();
      backButton.setBounds(16, 16, size.width, size.height);
    }
  }

  void setPrompt(String titleText, String actionText) {
    setTitle(titleText);
    submit.setText(actionText);
  }

  void setTitle(String text) {
    setTitle(text, false);
  }

  void setTitle(String text, boolean failed) {
    TTM_CuoiKy.statusText(title, text, failed);
    revalidate();
  }

  void setDescription(String text) {
    setDescription(text, false);
  }

  void setDescription(String text, boolean failed) {
    TTM_CuoiKy.statusText(description, text, failed);
    description.setVisible(!feedback.isVisible() && !text.isBlank());
  }

  void setFeedback(String text) {
    boolean hasFeedback = !text.isBlank();
    if (otpFeedbackTimeout != null) {
      if (hasFeedback) {
        description.setText("");
        otpFeedbackTimeout.restart();
      } else {
        otpFeedbackTimeout.stop();
      }
    }
    feedback.setText(hasFeedback ? text : " ");
    feedback.setVisible(hasFeedback);
    description.setVisible(!hasFeedback && !description.getText().isBlank());
  }

  void setWarning(String text) {
    warning.setText(text);
    warning.setVisible(!text.isBlank());
  }

  void setInputEnabled(boolean enabled) {
    for (JPasswordField digit : digits) digit.setEnabled(enabled);
    submit.setEnabled(enabled);
    keyboardButton.setVisible(enabled);
    keyboardButton.setEnabled(enabled);
    if (backButton != null) backButton.setEnabled(enabled);
    if (!enabled) keyboard.setVisible(false);
    repaint();
  }

  boolean inputEnabled() {
    return submit.isEnabled();
  }

  void clear() {
    clearDigits();
    setFeedback("");
    setWarning("");
    keyboard.setVisible(false);
  }

  void focusInput() {
    int next = firstEmptyDigit();
    digits[next < 0 ? digits.length - 1 : next].requestFocusInWindow();
  }

  char[] readDigits() {
    char[] value = new char[digits.length];
    for (int i = 0; i < digits.length; i++) {
      char[] digit = digits[i].getPassword();
      if (digit.length != 1) {
        Arrays.fill(digit, '\0');
        Arrays.fill(value, '\0');
        return null;
      }
      value[i] = digit[0];
      Arrays.fill(digit, '\0');
    }
    return value;
  }

  void clearDigits() {
    for (JPasswordField digit : digits) digit.setText("");
  }

  private int firstEmptyDigit() {
    for (int i = 0; i < digits.length; i++) {
      if (digits[i].getDocument().getLength() == 0) return i;
    }
    return -1;
  }

  private void eraseFrom(int index) {
    for (int i = index; i >= 0; i--) {
      if (digits[i].getDocument().getLength() > 0) {
        digits[i].setText("");
        digits[i].requestFocusInWindow();
        return;
      }
    }
  }

  private void toggleKeyboard() {
    if (keyboard.isVisible()) {
      keyboard.setVisible(false);
      return;
    }
    keyboard.pack();
    keyboard.show(keyboardButton, 0, -keyboard.getPreferredSize().height - 8);
  }

  private void finishKeypadEntry() {
    keyboard.setVisible(false);
    if (firstEmptyDigit() >= 0) focusInput();
    else submit.requestFocusInWindow();
  }

  private void appendDigit(char value) {
    int next = firstEmptyDigit();
    if (!inputEnabled() || next < 0) return;
    keypadInput = true;
    try {
      digits[next].setText(String.valueOf(value));
    } finally {
      keypadInput = false;
    }
  }

  private JButton createKeyboardButton() {
    JButton button = new JButton(KEYBOARD_ICON) {
      @Override
      protected void paintComponent(Graphics graphics) {
        paintRoundButton(this, graphics, KEYBOARD_ICON);
      }
    };
    Dimension size = new Dimension(KEYBOARD_SIZE, KEYBOARD_SIZE);
    button.setMinimumSize(size);
    button.setPreferredSize(size);
    button.setMaximumSize(size);
    button.setToolTipText("Mở bàn phím số");
    button.getAccessibleContext().setAccessibleName("Mở bàn phím số");
    button.setBorder(null);
    button.setBorderPainted(false);
    button.setContentAreaFilled(false);
    button.setFocusPainted(false);
    button.setOpaque(false);
    button.setRolloverEnabled(false);
    return button;
  }

  private static void paintRoundButton(JButton button, Graphics graphics, Icon icon) {
    Graphics2D canvas = (Graphics2D) graphics.create();
    try {
      canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      canvas.setColor(Color.WHITE);
      canvas.fillOval(0, 0, button.getWidth(), button.getHeight());
      canvas.setColor(new Color(189, 189, 189));
      canvas.drawOval(0, 0, button.getWidth() - 1, button.getHeight() - 1);
      icon.paintIcon(button, canvas,
          (button.getWidth() - icon.getIconWidth()) / 2,
          (button.getHeight() - icon.getIconHeight()) / 2);
    } finally {
      canvas.dispose();
    }
  }

  private void configureKeyboard(String codeName) {
    JPanel keys = new JPanel(new GridLayout(4, 3, 4, 4));
    keys.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
    keys.setBackground(new Color(245, 245, 245));
    for (String label : new String[] {"7", "8", "9", "4", "5", "6", "1", "2", "3", "Xóa", "0", "OK"}) {
      JButton key;
      if (label.equals("Xóa")) {
        key = new JButton(CLEAR_ICON);
        key.setToolTipText("Xóa " + codeName);
        key.getAccessibleContext().setAccessibleName("Xóa " + codeName);
        key.addActionListener(e -> clearDigits());
      } else if (label.equals("OK")) {
        key = new JButton(DONE_ICON);
        key.setToolTipText("Đóng bàn phím và hoàn tất nhập");
        key.getAccessibleContext().setAccessibleName("OK");
        key.addActionListener(e -> finishKeypadEntry());
      } else {
        key = CanteenFrame.button(label, e -> appendDigit(label.charAt(0)));
        key.setFont(new Font("Segoe UI", Font.PLAIN, 32));
      }
      key.setForeground(TTM_CuoiKy.DEFAULT_TEXT_COLOR);
      Dimension size = new Dimension(80, 64);
      key.setMinimumSize(size);
      key.setPreferredSize(size);
      key.setMaximumSize(size);
      key.setFocusable(false);
      key.setBorderPainted(false);
      key.setFocusPainted(false);
      keys.add(key);
    }
    keyboard.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(new Color(190, 203, 220)),
        BorderFactory.createEmptyBorder(2, 2, 2, 2)));
    keyboard.setBackground(new Color(245, 245, 245));
    keyboard.add(keys);
  }

  private final class DigitFilter extends DocumentFilter {
    @Override
    public void insertString(FilterBypass bypass, int offset, String text, AttributeSet attributes)
        throws BadLocationException {
      replace(bypass, offset, 0, text, attributes);
    }

    @Override
    public void replace(
        FilterBypass bypass, int offset, int length, String text, AttributeSet attributes)
        throws BadLocationException {
      if (text == null || text.isEmpty()) {
        bypass.replace(offset, length, "", attributes);
      } else {
        if (text.length() != 1 || text.charAt(0) < '0' || text.charAt(0) > '9') return;
        bypass.replace(0, bypass.getDocument().getLength(), text, attributes);
        if (!keypadInput) {
          SwingUtilities.invokeLater(() -> {
            int next = firstEmptyDigit();
            if (next >= 0) digits[next].requestFocusInWindow();
          });
        }
      }
      onEdit.run();
    }
  }
}
