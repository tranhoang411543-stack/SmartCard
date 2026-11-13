package vn.edu.smartcard.ttm_cuoiky;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.ui.FlatTextBorder;
import com.formdev.flatlaf.util.UIScale;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.io.*;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import vn.edu.smartcard.shared.*;

final class ProfileForm extends JPanel {

  private static final int DETAIL_FIELD_HEIGHT = 40;
  private static final int DETAIL_TEXT_INSET = 8;
  private static final int BALANCE_VALUE_WIDTH = 160;
  private static final String BALANCE_UNIT = " VND";
  private static final String BALANCE_WIDTH_SAMPLE = "999.999.999" + BALANCE_UNIT;
  private static final String MASKED_BALANCE = "*".repeat(15) + BALANCE_UNIT;
  private static final DateTimeFormatter BIRTH_FORMAT =
      DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

  final JTextField cardId = new JTextField(),
      code = new JTextField(),
      name = new JTextField(),
      citizen = new JTextField(),
      birth = InputFormats.dateField(),
      group = new JTextField(),
      phone = new JTextField(),
      email = new JTextField();
  final JSpinner intake =
      new JSpinner(
          new SpinnerNumberModel(
              LocalDate.now().getYear(), 1990, LocalDate.now().getYear() + 1, 1));
  private final JTextField intakeDisplay = InputFormats.yearField();
  private final JComponent avatar;
  private JButton birthPicker, intakePicker;
  private final JTextField balanceField = new JTextField();
  private final JButton balanceToggle = new JButton();
  private final JPanel balanceActions = new JPanel(new BorderLayout());
  private enum Layout { COMPACT, STUDENT, ADMIN }
  private final Layout layout;
  private String photo = "";
  private Long balanceAmount;
  private long revision, photoGeneration;
  private boolean editing, photoLoading, balanceVisible;

  static ProfileForm forStudentDetails(JButton balanceAction) {
    return new ProfileForm(Layout.STUDENT, balanceAction);
  }

  static ProfileForm forAdminDetails() {
    return new ProfileForm(Layout.ADMIN, null);
  }

  ProfileForm() {
    this(Layout.COMPACT, null);
  }

  private ProfileForm(Layout layout, JButton balanceAction) {
    super(new BorderLayout(layout == Layout.COMPACT ? 18 : 16, layout == Layout.COMPACT ? 12 : 16));
    this.layout = layout;
    boolean details = layout != Layout.COMPACT;
    setOpaque(details);
    if (details) {
      setBackground(Color.WHITE);
    }
    int formInset = details ? 16 : 12;
    setBorder(BorderFactory.createEmptyBorder(formInset, formInset, formInset, formInset));
    JSpinner.NumberEditor yearEditor = (JSpinner.NumberEditor) intake.getEditor();
    yearEditor.getFormat().setGroupingUsed(false);
    yearEditor.getTextField().setValue(intake.getValue());
    if (details) {
      intakeDisplay.setEditable(false);
      intakeDisplay.getAccessibleContext().setAccessibleName("Năm nhập học");
    }
    if (layout == Layout.ADMIN) {
      configureBirthPicker();
      configureIntakePicker();
    }
    JPanel portrait = new JPanel(new BorderLayout());
    portrait.setOpaque(false);
    if (layout == Layout.STUDENT) {
      avatar = new JLabel("Chưa có ảnh", SwingConstants.CENTER);
      avatar.setBorder(BorderFactory.createLineBorder(new Color(219, 226, 235)));
      avatar.setOpaque(true);
    } else {
      Icon changePhotoIcon = layout == Layout.ADMIN
          ? DesktopIcons.svg("/icons/image-user-up-svgrepo-com.svg", 64, "biểu tượng đổi ảnh")
          : null;
      JButton photoButton = new JButton("Chưa có ảnh") {
        @Override protected void paintComponent(Graphics graphics) {
          super.paintComponent(graphics);
          if (editing && isEnabled() && getModel().isRollover() && changePhotoIcon != null) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
              canvas.setColor(new Color(0xA0A0A0));
              canvas.fillRect(0, 0, getWidth(), getHeight());
              changePhotoIcon.paintIcon(this, canvas,
                  (getWidth() - changePhotoIcon.getIconWidth()) / 2,
                  (getHeight() - changePhotoIcon.getIconHeight()) / 2);
            } finally {
              canvas.dispose();
            }
          }
        }
      };
      photoButton.setRolloverEnabled(true);
      photoButton.setMargin(new Insets(0, 0, 0, 0));
      if (details) photoButton.setBorder(BorderFactory.createLineBorder(new Color(219, 226, 235)));
      photoButton.getAccessibleContext().setAccessibleName("Chọn ảnh chân dung");
      photoButton.addActionListener(e -> choosePhoto());
      avatar = photoButton;
    }
    avatar.setPreferredSize(details ? new Dimension(240, 320) : new Dimension(150, 200));
    avatar.setBackground(new Color(246, 248, 252));
    portrait.add(avatar, BorderLayout.NORTH);
    add(portrait, BorderLayout.WEST);
    JPanel fields = new JPanel(new GridBagLayout());
    fields.setOpaque(false);
    if (details) {
      addDetailRows(fields, balanceAction);
    } else {
      addEditableProfileRows(fields);
    }
    add(fields, BorderLayout.CENTER);
    code.getDocument()
        .addDocumentListener(
            new DocumentListener() {
              public void insertUpdate(DocumentEvent e) {
                derive();
              }

              public void removeUpdate(DocumentEvent e) {
                derive();
              }

              public void changedUpdate(DocumentEvent e) {
                derive();
              }
            });
    name.addFocusListener(
        new java.awt.event.FocusAdapter() {
          @Override
          public void focusLost(java.awt.event.FocusEvent e) {
            if (editing) {
              name.setText(Profile.titleCase(name.getText()));
            }
          }
        });
    setEditable(false);
  }

  private void addDetailRows(JPanel fields, JButton balanceAction) {
    String[][] labels = {
      {"ID thẻ :", "Họ và tên :"},
      {"Mã sinh viên :", "Lớp :", "Năm nhập học :"},
      {"CCCD :", "Ngày sinh :"},
      {"Số điện thoại :", "Email :"},
      {"Số dư :"}
    };
    JComponent[][] components = {
      {cardId, name},
      {code, group, intakeDisplay},
      {citizen, birth},
      {phone, email},
      {balanceField}
    };
    GridBagConstraints rowConstraints = new GridBagConstraints();
    rowConstraints.gridx = 0;
    rowConstraints.fill = GridBagConstraints.HORIZONTAL;
    rowConstraints.weightx = 1;
    int rowCount = layout == Layout.STUDENT ? labels.length : labels.length - 1;
    int topOffset = layout == Layout.ADMIN ? 1 : 0;
    if (topOffset == 1) {
      rowConstraints.gridy = 0;
      rowConstraints.weighty = 1;
      fields.add(Box.createVerticalGlue(), rowConstraints);
    }
    for (int row = 0; row < rowCount; row++) {
      if (row > 0) {
        rowConstraints.gridy = 2 * row - 1 + topOffset;
        rowConstraints.weighty = 1;
        fields.add(Box.createVerticalGlue(), rowConstraints);
      }
      rowConstraints.gridy = 2 * row + topOffset;
      rowConstraints.weighty = 0;
      fields.add(detailRow(labels[row], components[row], balanceAction), rowConstraints);
    }
    if (topOffset == 1) {
      rowConstraints.gridy = 2 * rowCount;
      rowConstraints.weighty = 1;
      fields.add(Box.createVerticalGlue(), rowConstraints);
    }
  }

  private JPanel detailRow(String[] labels, JComponent[] components, JButton balanceAction) {
    JPanel row = new JPanel(new GridBagLayout());
    row.setOpaque(false);
    GridBagConstraints c = new GridBagConstraints();
    c.gridy = 0;
    c.anchor = GridBagConstraints.LINE_START;
    Font detailFont = UIManager.getFont("defaultFont").deriveFont(Font.PLAIN, 16f);
    for (int i = 0; i < labels.length; i++) {
      JComponent field = components[i];
      JLabel label = new JLabel(labels[i]);
      label.setFont(detailFont);
      label.setLabelFor(field);
      c.gridx = i * 2;
      c.weightx = 0;
      c.fill = GridBagConstraints.NONE;
      c.insets = new Insets(0, 8, 0, 8);
      row.add(label, c);
      c.gridx = i * 2 + 1;
      c.weightx = 1;
      c.insets = new Insets(0, 0, 0, 8);
      field.setFont(detailFont);
      if (field instanceof JTextField) field.setBorder(new StudentDetailsBorder());
      if (field == balanceField) {
        configureBalanceControl();
        c.weightx = 0;
        c.insets = new Insets(0, 0, 0, 16);
        row.add(field, c);
        c.gridx++;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 0, 8);
        row.add(balanceAction, c);
      } else {
        c.fill = GridBagConstraints.HORIZONTAL;
        field.setPreferredSize(new Dimension(120, DETAIL_FIELD_HEIGHT));
        row.add(field, c);
      }
    }
    return row;
  }

  private void configureBirthPicker() {
    birthPicker = pickerButton("Chọn ngày sinh", this::chooseBirthDate);
    birth.putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, birthPicker);
  }

  private void configureIntakePicker() {
    intakePicker = pickerButton("Chọn năm nhập học", this::chooseIntakeYear);
    intakeDisplay.putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, intakePicker);
  }

  static JButton pickerButton(String title, Runnable action) {
    JButton button = new JButton(new CalendarIcon());
    button.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
    button.setPreferredSize(new Dimension(40, 40));
    button.setToolTipText(title);
    button.getAccessibleContext().setAccessibleName(title);
    button.addActionListener(e -> action.run());
    return button;
  }

  private void chooseIntakeYear() {
    if (!editing) return;
    long generationAtOpen = photoGeneration;
    SpinnerNumberModel limits = (SpinnerNumberModel) intake.getModel();
    int minimum = ((Number) limits.getMinimum()).intValue();
    int maximum = ((Number) limits.getMaximum()).intValue();
    int initial = (Integer) intake.getValue();
    String entered = intakeDisplay.getText();
    if (entered.matches("[0-9]{4}")) {
      int parsed = Integer.parseInt(entered);
      if (parsed >= minimum && parsed <= maximum) initial = parsed;
    }
    JComboBox<Integer> year = new JComboBox<>();
    for (int value = maximum; value >= minimum; value--) year.addItem(value);
    year.setSelectedItem(initial);
    year.setMaximumRowCount(12);
    year.getAccessibleContext().setAccessibleName("Năm nhập học");
    JPanel choices = new JPanel(new GridLayout(2, 1, 0, 4));
    JLabel label = new JLabel("Năm");
    label.setLabelFor(year);
    choices.add(label);
    choices.add(year);
    Object[] options = {"Chọn", "Hủy"};
    int choice = JOptionPane.showOptionDialog(this, choices, "Chọn năm nhập học",
        JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
    if (choice == 0 && editing && generationAtOpen == photoGeneration) {
      intakeDisplay.setText(year.getSelectedItem().toString());
    }
  }

  private void chooseBirthDate() {
    if (!editing) return;
    long generationAtOpen = photoGeneration;
    LocalDate initial;
    try {
      initial = LocalDate.parse(birth.getText(), BIRTH_FORMAT);
      if (initial.isBefore(LocalDate.of(1900, 1, 1)) || initial.isAfter(LocalDate.now()))
        initial = LocalDate.now();
    } catch (RuntimeException invalid) {
      initial = LocalDate.now();
    }
    int initialDay = initial.getDayOfMonth();
    JComboBox<Integer> day = new JComboBox<>();
    JComboBox<Integer> month = new JComboBox<>();
    JComboBox<Integer> year = new JComboBox<>();
    for (int value = 1; value <= 12; value++) month.addItem(value);
    for (int value = LocalDate.now().getYear(); value >= 1900; value--) year.addItem(value);
    month.setSelectedItem(initial.getMonthValue());
    year.setSelectedItem(initial.getYear());
    year.setMaximumRowCount(12);
    Runnable updateDays = () -> {
      int previous = day.getSelectedItem() instanceof Integer selected ? selected : initialDay;
      int count = YearMonth.of((Integer) year.getSelectedItem(), (Integer) month.getSelectedItem())
          .lengthOfMonth();
      DefaultComboBoxModel<Integer> days = new DefaultComboBoxModel<>();
      for (int value = 1; value <= count; value++) days.addElement(value);
      day.setModel(days);
      day.setSelectedItem(Math.min(previous, count));
    };
    updateDays.run();
    month.addActionListener(e -> updateDays.run());
    year.addActionListener(e -> updateDays.run());

    JPanel choices = new JPanel(new GridLayout(2, 3, 8, 4));
    for (String label : new String[] {"Ngày", "Tháng", "Năm"}) choices.add(new JLabel(label));
    choices.add(day);
    choices.add(month);
    choices.add(year);
    day.getAccessibleContext().setAccessibleName("Ngày sinh");
    month.getAccessibleContext().setAccessibleName("Tháng sinh");
    year.getAccessibleContext().setAccessibleName("Năm sinh");
    Object[] options = {"Chọn", "Hủy"};
    while (true) {
      int choice = JOptionPane.showOptionDialog(this, choices, "Chọn ngày sinh",
          JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
      if (choice != 0 || !editing || generationAtOpen != photoGeneration) return;
      LocalDate selected = LocalDate.of((Integer) year.getSelectedItem(),
          (Integer) month.getSelectedItem(), (Integer) day.getSelectedItem());
      if (!selected.isAfter(LocalDate.now())) {
        birth.setText(BIRTH_FORMAT.format(selected));
        return;
      }
      JOptionPane.showMessageDialog(this, "Ngày sinh không được ở tương lai.",
          "Ngày sinh không hợp lệ", JOptionPane.WARNING_MESSAGE);
    }
  }

  private static final class CalendarIcon implements Icon {
    @Override public int getIconWidth() { return 20; }
    @Override public int getIconHeight() { return 20; }

    @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
      Graphics2D canvas = (Graphics2D) graphics.create();
      try {
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        canvas.setColor(component.isEnabled() ? TTM_CuoiKy.DEFAULT_TEXT_COLOR : new Color(0xA0A0A0));
        canvas.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        canvas.drawRoundRect(x + 2, y + 4, 16, 14, 3, 3);
        canvas.drawLine(x + 2, y + 8, x + 18, y + 8);
        canvas.drawLine(x + 6, y + 2, x + 6, y + 6);
        canvas.drawLine(x + 14, y + 2, x + 14, y + 6);
        canvas.fillOval(x + 6, y + 11, 2, 2);
        canvas.fillOval(x + 10, y + 11, 2, 2);
        canvas.fillOval(x + 6, y + 14, 2, 2);
      } finally {
        canvas.dispose();
      }
    }
  }

  private void configureBalanceControl() {
    balanceField.setEditable(false);
    balanceField.setHorizontalAlignment(JTextField.RIGHT);
    // FlatLaf reduces the margin next to a trailing component. The border supplies our insets.
    balanceField.setMargin(new Insets(2, 0, 2, 0));
    balanceField.getAccessibleContext().setAccessibleName("Số dư");

    balanceToggle.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
    balanceToggle.setPreferredSize(new Dimension(40, 40));
    balanceActions.setOpaque(false);
    balanceActions.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(0xC2C2C2)));
    balanceActions.add(balanceToggle, BorderLayout.CENTER);
    balanceField.putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, balanceActions);
    balanceToggle.addActionListener(
        e -> {
          if (balanceAmount != null) {
            balanceVisible = !balanceVisible;
            updateBalanceDisplay();
          }
        });
    clearBalance();
  }

  private void updateBalanceDisplay() {
    String value =
        balanceAmount == null
            ? ""
            : balanceVisible
                ? java.text.NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN"))
                        .format(balanceAmount)
                    + BALANCE_UNIT
                : MASKED_BALANCE;
    balanceField.setText(value);
    FontMetrics metrics = balanceField.getFontMetrics(balanceField.getFont());
    int sampleWidth = Math.max(metrics.stringWidth(BALANCE_WIDTH_SAMPLE), metrics.stringWidth(MASKED_BALANCE));
    int textWidth = Math.max(sampleWidth, metrics.stringWidth(value));
    Insets textInsets = balanceField.getInsets();
    int valueWidth = Math.max(BALANCE_VALUE_WIDTH, textWidth + textInsets.left + textInsets.right);
    // The text editor reserves one pixel for the caret.
    int fieldWidth = valueWidth + balanceActions.getPreferredSize().width + 1;
    balanceField.setPreferredSize(new Dimension(fieldWidth, DETAIL_FIELD_HEIGHT));
    String actionName = balanceVisible ? "Ẩn số dư" : "Hiện số dư";
    balanceToggle.setIcon(new BalanceVisibilityIcon(!balanceVisible));
    balanceToggle.setToolTipText(actionName);
    balanceToggle.getAccessibleContext().setAccessibleName(actionName);
    balanceToggle.setEnabled(balanceAmount != null);
  }

  void setBalance(long value) {
    balanceAmount = value;
    updateBalanceDisplay();
  }

  private void clearBalance() {
    balanceAmount = null;
    balanceVisible = false;
    updateBalanceDisplay();
  }

  private static final class StudentDetailsBorder extends FlatTextBorder {
    StudentDetailsBorder() {
      arc = UIScale.unscale(8);
    }

    @Override
    public Insets getBorderInsets(Component component, Insets insets) {
      super.getBorderInsets(component, insets);
      insets.left = DETAIL_TEXT_INSET;
      insets.right = DETAIL_TEXT_INSET;
      return insets;
    }
  }

  private static final class BalanceVisibilityIcon implements Icon {
    private final boolean showAction;

    private BalanceVisibilityIcon(boolean showAction) {
      this.showAction = showAction;
    }

    @Override
    public int getIconWidth() {
      return 24;
    }

    @Override
    public int getIconHeight() {
      return 24;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
      Graphics2D g = (Graphics2D) graphics.create();
      g.translate(x, y);
      g.scale(1.2, 1.2);
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(TTM_CuoiKy.DEFAULT_TEXT_COLOR);
      g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      Path2D eye = new Path2D.Double();
      eye.moveTo(2, 10);
      eye.quadTo(10, 2, 18, 10);
      eye.quadTo(10, 18, 2, 10);
      g.draw(eye);
      g.draw(new Ellipse2D.Double(7, 7, 6, 6));
      if (!showAction) {
        g.draw(new Line2D.Double(3, 17, 17, 3));
      }
      g.dispose();
    }
  }

  private void addEditableProfileRows(JPanel fields) {
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(5, 5, 5, 5);
    c.fill = GridBagConstraints.HORIZONTAL;
    int row = 0;
    row = field(fields, c, row, "ID thẻ", cardId);
    row = field(fields, c, row, "Mã sinh viên", code);
    row = field(fields, c, row, "Họ và tên", name);
    row = field(fields, c, row, "CCCD (12 chữ số)", citizen);
    row = field(fields, c, row, "Ngày sinh (dd/MM/yyyy)", birth);
    row = field(fields, c, row, "Lớp", group);
    row = field(fields, c, row, "Năm nhập học", intake);
    row = field(fields, c, row, "Số điện thoại", phone);
    field(fields, c, row, "Email", email);
  }

  private int field(
      JPanel panel, GridBagConstraints c, int row, String text, JComponent component) {
    JLabel label = new JLabel(text);
    label.setLabelFor(component);
    c.gridy = row;
    c.gridx = 0;
    c.weightx = 0;
    panel.add(label, c);
    c.gridx = 1;
    c.weightx = 1;
    component.setPreferredSize(new Dimension(200, 32));
    panel.add(component, c);
    return row + 1;
  }

  private void derive() {
    String s = code.getText().strip().toUpperCase(Locale.ROOT);
    try {
      if (s.matches("(AT|CT|DT)[0-9]{6}")) {
        int n = Integer.parseInt(s.substring(4, 6));
        group.setText(
            n >= 1 && n <= 26
                ? s.substring(0, 2) + Integer.parseInt(s.substring(2, 4)) + (char) ('A' + n - 1)
                : "");
      } else {
        group.setText("");
      }
    } catch (RuntimeException e) {
      group.setText("");
    }
  }

  void setEditable(boolean value) {
    editing = value;
    for (JTextField f : new JTextField[] {code, name, citizen, birth, phone, email}) {
      f.setEditable(value);
    }
    cardId.setEditable(false);
    group.setEditable(false);
    intake.setEnabled(value);
    intakeDisplay.setEditable(layout == Layout.ADMIN && value);
    if (birthPicker != null) birthPicker.setEnabled(value);
    if (intakePicker != null) intakePicker.setEnabled(value);
    updateAvatarAction();
  }

  private void updateAvatarAction() {
    if (avatar instanceof JButton button) {
      boolean selectable = !photoLoading && editing;
      button.setEnabled(selectable);
      button.setCursor(selectable ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
      button.setToolTipText(selectable ? "Bấm vào ảnh để chọn tệp ảnh chân dung" : null);
    }
  }

  void show(Profile p) {
    photoGeneration++;
    photoLoading = false;
    if (layout == Layout.STUDENT) {
      clearBalance();
    }
    if (p == null) {
      cardId.setText("");
      code.setText("");
      name.setText("");
      citizen.setText("");
      birth.setText("");
      group.setText("");
      phone.setText("");
      email.setText("");
      intake.setValue(LocalDate.now().getYear());
      photo = "";
      revision = 0;
    } else {
      cardId.setText(p.cardId());
      code.setText(p.studentCode());
      name.setText(p.fullName());
      citizen.setText(p.citizenId());
      birth.setText(p.birthDate());
      group.setText(p.className());
      phone.setText(p.phone());
      email.setText(p.email());
      intake.setValue(p.intakeYear());
      photo = p.photoBase64();
      revision = p.version();
    }
    if (layout != Layout.COMPACT) {
      intakeDisplay.setText(Integer.toString((Integer) intake.getValue()));
    }
    renderPhoto();
    updateAvatarAction();
  }

  Profile value() {
    if (photoLoading) {
      throw new IllegalArgumentException("Đang xử lý ảnh. Vui lòng chờ.");
    }
    int year;
    if (layout == Layout.ADMIN) {
      String value = intakeDisplay.getText();
      if (!value.matches("[0-9]{4}"))
        throw new IllegalArgumentException("Năm nhập học phải gồm đúng 4 chữ số.");
      year = Integer.parseInt(value);
    } else {
      try {
        intake.commitEdit();
      } catch (ParseException invalidYear) {
        throw new IllegalArgumentException("Năm nhập học không hợp lệ.");
      }
      year = (Integer) intake.getValue();
    }
    if (layout == Layout.ADMIN && photo.isEmpty()) {
      throw new IllegalArgumentException("Bấm vào ảnh để chọn ảnh đại diện.");
    }
    return new Profile(
            cardId.getText(),
            code.getText(),
            name.getText(),
            citizen.getText(),
            birth.getText(),
            group.getText(),
            phone.getText(),
            email.getText(),
            year,
            photo,
            revision)
        .normalized();
  }

  private void renderPhoto() {
    if (photoLoading) {
      showAvatar(null, "Đang xử lý ảnh…", false);
      return;
    }
    try {
      if (photo.isEmpty()) {
        showAvatar(null, "Chưa có ảnh", false);
        return;
      }
      byte[] bytes = Base64.getDecoder().decode(photo);
      var image = ImageIO.read(new ByteArrayInputStream(bytes));
      Dimension avatarSize = avatar.getPreferredSize();
      showAvatar(
          new ImageIcon(
              image.getScaledInstance(avatarSize.width, avatarSize.height, Image.SCALE_SMOOTH)),
          "", false);
    } catch (IOException | RuntimeException e) {
      showAvatar(null, "Không đọc được ảnh",
          SwingUtilities.getWindowAncestor(this) instanceof CanteenFrame);
    }
  }

  private void showAvatar(Icon icon, String text, boolean failed) {
    if (avatar instanceof JLabel label) {
      label.setIcon(icon);
      TTM_CuoiKy.statusText(label, text, failed);
    } else if (avatar instanceof JButton button) {
      button.setIcon(icon);
      button.setDisabledIcon(icon);
      button.setForeground(failed ? TTM_CuoiKy.ERROR_TEXT_COLOR : TTM_CuoiKy.DEFAULT_TEXT_COLOR);
      button.putClientProperty(FlatClientProperties.STYLE,
          failed ? java.util.Map.of("disabledText", TTM_CuoiKy.ERROR_TEXT_COLOR) : null);
      button.setText(text);
    }
  }

  private void choosePhoto() {
    if (!(avatar instanceof JButton button) || !button.isEnabled()) return;
    long generationAtOpen = photoGeneration;
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Chọn ảnh chân dung");
    chooser.setFileFilter(new FileNameExtensionFilter("Ảnh JPEG, PNG", "jpg", "jpeg", "png"));
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION
        || generationAtOpen != photoGeneration || !button.isEnabled()) {
      return;
    }
    File file = chooser.getSelectedFile();
    long generation = ++photoGeneration;
    photoLoading = true;
    updateAvatarAction();
    renderPhoto();
    new SwingWorker<String, Void>() {
      protected String doInBackground() throws Exception {
        return Portraits.prepare(file);
      }

      protected void done() {
        if (generation != photoGeneration) {
          return;
        }
        photoLoading = false;
        try {
          String preparedPhoto = get();
          if (editing && generation == photoGeneration) photo = preparedPhoto;
        } catch (Exception e) {
          JOptionPane.showMessageDialog(
              ProfileForm.this,
              e.getCause() == null ? "Không đọc được ảnh." : e.getCause().getMessage(),
              "Ảnh chưa phù hợp",
              JOptionPane.WARNING_MESSAGE);
        } finally {
          renderPhoto();
          updateAvatarAction();
        }
      }
    }.execute();
  }
}
