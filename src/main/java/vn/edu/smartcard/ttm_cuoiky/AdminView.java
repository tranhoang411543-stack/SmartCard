package vn.edu.smartcard.ttm_cuoiky;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.icons.FlatRevealIcon;
import com.formdev.flatlaf.ui.FlatUIUtils;
import java.awt.*;
import java.awt.event.*;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import vn.edu.smartcard.shared.*;

final class AdminView extends JPanel {

  private static final int CREDENTIAL_FORM_WIDTH = 320;
  private static final int CARD_PAGE_SIZE = 10;
  private static final int CARD_ROW_HEIGHT = 40;
  private static final int CARD_SEPARATOR_HEIGHT = 1;
  private static final int CARD_BORDER_WIDTH = 1;
  private static final int CARD_TABLE_HEIGHT =
      (CARD_PAGE_SIZE + 1) * CARD_ROW_HEIGHT + CARD_PAGE_SIZE * CARD_SEPARATOR_HEIGHT
          + 2 * CARD_BORDER_WIDTH;
  private final CanteenFrame frame;
  private final CardLayout pages = new CardLayout();
  private final JPanel body = new JPanel(pages);
  private final JTextField username = new JTextField();
  private final JPasswordField password = new JPasswordField();
  private final JToggleButton passwordVisibility = new JToggleButton();
  private final SixDigitEntry recoveryOtp;
  private final JPasswordField newPassword = new JPasswordField();
  private final JPasswordField confirmPassword = new JPasswordField();
  private final JLabel resetFeedback = new JLabel(" ", SwingConstants.CENTER);
  private final Timer resetFeedbackTimeout = new Timer(5_000, e -> resetFeedback.setText(" "));
  private final JButton finishRecovery, recoveryBack;
  private String recoveryIdentity, recoveryGrant;
  private final JLabel account = new JLabel("Quản trị hệ thống");
  private final JTextField query = new JTextField(18),
      classFilter = new JTextField(7),
      yearFilter = new JTextField(7);
  private final JComboBox<String>
      program = new JComboBox<>(new String[] {"Tất cả", "AT", "CT", "DT"}),
      sort =
          new JComboBox<>(
              new String[] {
                "ID thẻ", "Năm nhập học mới → cũ", "Năm nhập học cũ → mới", "Tên A → Z", "Tên Z → A"
              });
  private final DefaultTableModel model =
      new DefaultTableModel(
          new String[] {
            "ID thẻ", "Mã sinh viên", "Họ và tên", "Lớp", "Ngày sinh", "Số điện thoại", "Nhập học",
            "Tình trạng"
          },
          0) {
        @Override
        public boolean isCellEditable(int r, int c) {
          return false;
        }
      };
  private final JTable table = new JTable(model) {
    @Override public void changeSelection(int row, int column, boolean toggle, boolean extend) {
      if (row < 0 || row >= getRowCount()) return;
      Object cardId = getModel().getValueAt(convertRowIndexToModel(row), 0);
      if (!(cardId instanceof String text) || text.isBlank()) {
        clearSelection();
        return;
      }
      super.changeSelection(row, column, toggle, extend);
    }

    @Override protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      Graphics lines = graphics.create();
      try {
        lines.setColor(getGridColor());
        for (int row = 0; row < getRowCount() - 1; row++) {
          Rectangle bounds = getCellRect(row, 0, true);
          int y = bounds.y + bounds.height - CARD_SEPARATOR_HEIGHT;
          lines.drawLine(0, y, getWidth() - 1, y);
        }
      } finally {
        lines.dispose();
      }
    }
  };
  private final JScrollPane tableScroll = new JScrollPane(table);
  private Api.CardListPage pageData;
  private int page;
  private final JLabel resultCount = new JLabel("Hiển thị 0 trên tổng số 0 kết quả");
  private final PaginationBar pagination;
  private final Timer searchDebounce = new Timer(500, e -> liveSearch());
  private SwingWorker<Api.CardListPage, Void> searchWorker;
  private long searchRevision;
  private boolean searchPending;
  private final AdminCardView cardView;
  private final CardStatusButton cardButton;
  private final JMenuItem block, restore, delete;
  private final JPopupMenu statusMenu = new JPopupMenu();
  private String contextCardId;
  private Api.Login session;
  private boolean tableDirty;

  AdminView(CanteenFrame frame) {
    super(new BorderLayout());
    this.frame = frame;
    setOpaque(false);
    body.setOpaque(false);
    add(body);
    JPanel entry = new JPanel(new GridBagLayout());
    entry.setBackground(Color.WHITE);
    JPanel login = new JPanel(new GridBagLayout());
    login.setOpaque(false);
    int loginWidth = CREDENTIAL_FORM_WIDTH;
    Font formFont = login.getFont().deriveFont(Font.PLAIN, 16f);
    Dimension controlSize = new Dimension(loginWidth, 40);
    JButton loginButton = CanteenFrame.button("Đăng nhập", e -> login());
    JButton forgotButton = CanteenFrame.button("Quên mật khẩu?", e -> forgot());
    for (JComponent control : new JComponent[] {username, password, loginButton, forgotButton}) {
      control.setFont(formFont);
      control.setPreferredSize(controlSize);
    }
    username.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Nhập username");
    password.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Nhập password");
    configurePasswordVisibility();
    password.addActionListener(e -> login());

    GridBagConstraints row = new GridBagConstraints();
    row.gridx = 0;
    row.fill = GridBagConstraints.HORIZONTAL;

    JLabel title = new JLabel("Đăng nhập", SwingConstants.CENTER);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 32f));
    title.setPreferredSize(new Dimension(loginWidth, 43));
    login.add(title, row);

    JLabel usernameLabel = new JLabel("Username");
    usernameLabel.setFont(formFont);
    usernameLabel.setLabelFor(username);
    row.insets.top = 32;
    login.add(usernameLabel, row);
    row.insets.top = 4;
    login.add(username, row);

    JLabel passwordLabel = new JLabel("Password");
    passwordLabel.setFont(formFont);
    passwordLabel.setLabelFor(password);
    row.insets.top = 16;
    login.add(passwordLabel, row);
    row.insets.top = 4;
    login.add(password, row);

    row.insets.top = 32;
    login.add(loginButton, row);
    row.insets.top = 8;
    login.add(forgotButton, row);
    entry.add(login);
    body.add(entry, "login");
    recoveryOtp = new SixDigitEntry(SixDigitEntry.Mode.OTP, "Nhập mã OTP", "Tiếp tục",
        this::verifyRecoveryOtp, () -> {});
    recoveryOtp.addBackButton(this::discardRecovery);
    body.add(recoveryOtp, "recoveryOtp");
    finishRecovery = CanteenFrame.button("Hoàn tất", e -> completeRecovery());
    recoveryBack = CanteenFrame.button("Quay lại đăng nhập", e -> discardRecovery());
    resetFeedbackTimeout.setRepeats(false);
    body.add(createRecoveryPasswordPage(), "recoveryPassword");
    cardView = new AdminCardView(frame, this::backToList, () -> tableDirty = true);
    cardButton = new CardStatusButton(e -> openCard());
    block = new JMenuItem("Khóa thẻ");
    restore = new JMenuItem("Mở khoá thẻ");
    delete = new JMenuItem("Xoá thẻ");
    block.addActionListener(e -> blockSelected());
    restore.addActionListener(e -> restoreSelected());
    delete.addActionListener(e -> deleteSelected());
    statusMenu.add(block);
    statusMenu.add(restore);
    statusMenu.add(delete);
    JPanel dashboard = new JPanel(new BorderLayout(0, 16));
    dashboard.setBackground(Color.WHITE);
    dashboard.setBorder(BorderFactory.createEmptyBorder(0, 20, 20, 20));
    JPanel top = new JPanel(new BorderLayout());
    top.setOpaque(false);
    top.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xD0D0D0)));
    account.setFont(account.getFont().deriveFont(Font.BOLD, 16));
    account.setForeground(TTM_CuoiKy.DEFAULT_TEXT_COLOR);
    account.setBorder(BorderFactory.createEmptyBorder(12, 0, 12, 0));
    top.add(account, BorderLayout.WEST);
    JPanel actions = new JPanel(new GridBagLayout());
    actions.setOpaque(false);
    JButton bankReceiptsButton = CanteenFrame.button("Giao dịch", e -> bankReceipts());
    for (JComponent button : new JComponent[] {bankReceiptsButton, cardButton}) {
      setFixedSize(button, 128, 32);
    }
    Box actionButtons = Box.createHorizontalBox();
    actionButtons.add(bankReceiptsButton);
    actionButtons.add(Box.createHorizontalStrut(8));
    actionButtons.add(cardButton);
    actions.add(actionButtons);
    top.add(actions, BorderLayout.EAST);
    dashboard.add(top, BorderLayout.NORTH);
    JPanel list = new JPanel(new GridBagLayout());
    list.setOpaque(false);
    GridBagConstraints listRow = new GridBagConstraints();
    listRow.gridx = 0;
    listRow.gridy = 0;
    listRow.weightx = 1;
    listRow.fill = GridBagConstraints.HORIZONTAL;
    JPanel search = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    search.setOpaque(false);
    search.add(new JLabel("Tìm kiếm"));
    search.add(Box.createHorizontalStrut(8));
    query.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
        "Tìm kiếm theo ID thẻ, Mã SV, Họ tên, Số ĐT");
    query.setColumns(0);
    setFixedSize(query, 320, 40);
    search.add(query);
    list.add(search, listRow);
    JPanel choices = new JPanel(new BorderLayout());
    choices.setOpaque(false);
    classFilter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Nhập lớp");
    yearFilter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Nhập năm");
    classFilter.setColumns(0);
    yearFilter.setColumns(0);
    setFixedSize(classFilter, 120, 40);
    setFixedSize(yearFilter, 120, 40);
    setFixedSize(program, 120, 40);
    setFixedSize(sort, 240, 40);
    Box filterChoices = Box.createHorizontalBox();
    filterChoices.add(new JLabel("Lọc theo :"));
    filterChoices.add(Box.createHorizontalStrut(8));
    filterChoices.add(new JLabel("Lớp"));
    filterChoices.add(Box.createHorizontalStrut(8));
    filterChoices.add(classFilter);
    filterChoices.add(Box.createHorizontalStrut(16));
    filterChoices.add(new JLabel("Năm sinh"));
    filterChoices.add(Box.createHorizontalStrut(8));
    filterChoices.add(yearFilter);
    filterChoices.add(Box.createHorizontalStrut(16));
    filterChoices.add(new JLabel("Chương trình"));
    filterChoices.add(Box.createHorizontalStrut(8));
    filterChoices.add(program);
    Box sortChoice = Box.createHorizontalBox();
    sortChoice.add(new JLabel("Sắp xếp theo"));
    sortChoice.add(Box.createHorizontalStrut(8));
    sortChoice.add(sort);
    choices.add(filterChoices, BorderLayout.WEST);
    choices.add(sortChoice, BorderLayout.EAST);
    listRow.gridy = 1;
    listRow.weighty = 1;
    listRow.anchor = GridBagConstraints.SOUTH;
    listRow.insets = new Insets(16, 0, 0, 0);
    list.add(choices, listRow);
    searchDebounce.setRepeats(false);
    DocumentListener filterListener = new DocumentListener() {
      public void insertUpdate(DocumentEvent e) { queueSearch(); }
      public void removeUpdate(DocumentEvent e) { queueSearch(); }
      public void changedUpdate(DocumentEvent e) { queueSearch(); }
    };
    query.getDocument().addDocumentListener(filterListener);
    classFilter.getDocument().addDocumentListener(filterListener);
    yearFilter.getDocument().addDocumentListener(filterListener);
    query.addActionListener(e -> {
      if (logged()) {
        searchDebounce.stop();
        liveSearch();
      }
    });
    program.addActionListener(e -> queueSearch());
    sort.addActionListener(e -> queueSearch());
    table.setFont(table.getFont().deriveFont(Font.PLAIN, 16f));
    table.setRowHeight(CARD_ROW_HEIGHT + CARD_SEPARATOR_HEIGHT);
    table.setRowMargin(0);
    table.setShowHorizontalLines(false);
    table.setShowVerticalLines(false);
    table.setGridColor(new Color(0xD0D0D0));
    table.setFillsViewportHeight(true);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    JTableHeader header = table.getTableHeader();
    header.setReorderingAllowed(false);
    header.setFont(header.getFont().deriveFont(Font.BOLD, 16f));
    header.setBackground(new Color(0xAAAAAA));
    header.setForeground(new Color(0x333333));
    header.setBorder(BorderFactory.createMatteBorder(0, 0, CARD_SEPARATOR_HEIGHT, 0, new Color(0xD0D0D0)));
    header.setPreferredSize(new Dimension(header.getPreferredSize().width,
        CARD_ROW_HEIGHT + CARD_SEPARATOR_HEIGHT));
    table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
    table.getSelectionModel().addListSelectionListener(e -> {
      if (statusMenu.isVisible() && !Objects.equals(contextCardId, selectedCardId())) {
        statusMenu.setVisible(false);
        contextCardId = null;
      }
      updateSelectedActions();
    });
    DefaultTableCellRenderer plain = new DefaultTableCellRenderer() {
      @Override protected void setValue(Object value) {
        String text = value == null ? "" : value.toString();
        setText(text.isBlank() ? "--" : text);
      }
    };
    plain.putClientProperty("html.disable", Boolean.TRUE);
    plain.setHorizontalAlignment(SwingConstants.CENTER);
    table.setDefaultRenderer(Object.class, plain);
    table.getColumnModel().getColumn(7).setCellRenderer(new StatusRenderer());
    int[] widths = {112, 125, 175, 80, 116, 135, 90, 130};
    for (int i = 0; i < widths.length; i++) {
      table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    }
    table.addMouseListener(
        new MouseAdapter() {
          @Override public void mousePressed(MouseEvent e) { showStatusMenu(e); }
          @Override public void mouseReleased(MouseEvent e) { showStatusMenu(e); }

          @Override
          public void mouseClicked(MouseEvent e) {
            int row = table.rowAtPoint(e.getPoint());
            if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && isCardRow(row)) {
              table.setRowSelectionInterval(row, row);
              preview();
            }
          }
        });
    table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), "cardStatusMenu");
    table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK),
        "cardStatusMenu");
    table.getActionMap().put("cardStatusMenu", new AbstractAction() {
      @Override public void actionPerformed(ActionEvent e) {
        int row = table.getSelectedRow();
        if (row < 0 || searchPending) return;
        int column = table.convertColumnIndexToView(7);
        Rectangle cell = table.getCellRect(row, column, true);
        openStatusMenu(row, cell.x, cell.y + cell.height);
      }
    });
    table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "preview");
    table
        .getActionMap()
        .put(
            "preview",
            new AbstractAction() {
              public void actionPerformed(ActionEvent e) {
                preview();
              }
            });
    tableScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
    tableScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    tableScroll.setBorder(BorderFactory.createLineBorder(new Color(0xC2C2C2), CARD_BORDER_WIDTH));
    tableScroll.setPreferredSize(new Dimension(0, CARD_TABLE_HEIGHT));
    JPanel headerCorner = new JPanel();
    headerCorner.setBackground(header.getBackground());
    tableScroll.setCorner(JScrollPane.UPPER_TRAILING_CORNER, headerCorner);
    JPanel paging = new JPanel(new BorderLayout());
    paging.setBackground(Color.WHITE);
    pagination = new PaginationBar(this::changePage);
    paging.add(resultCount, BorderLayout.WEST);
    paging.add(pagination, BorderLayout.EAST);
    JPanel results = new JPanel(new BorderLayout(0, 8));
    results.setOpaque(false);
    results.add(tableScroll, BorderLayout.CENTER);
    results.add(paging, BorderLayout.SOUTH);
    listRow.gridy = 2;
    list.add(results, listRow);
    dashboard.add(list, BorderLayout.CENTER);
    body.add(dashboard, "dashboard");
    body.add(cardView, "card");
    updateConnectedCard(frame.status());
    updateSelectedActions();
    updatePaging();
  }

  boolean logged() {
    return session != null;
  }

  boolean confirmDiscard() {
    return cardView.confirmDiscard();
  }

  boolean confirmReaderChange() {
    return cardView.confirmReaderChange();
  }

  void expired() {
    frame.invalidatePrivateState();
    invalidateSearch();
    searchPending = false;
    session = null;
    frame.api.token(null);
    model.setRowCount(0);
    pageData = null;
    tableDirty = false;
    cardView.close();
    clearPassword();
    discardRecovery();
    pages.show(body, "login");
    updateConnectedCard(frame.status());
    updateSelectedActions();
    updatePaging();
  }

  void suspendCard() {
    if (cardView.isOpen() && !cardView.connectionUnavailable()) {
      pages.show(body, "dashboard");
    }
    updateConnectedCard(frame.status());
  }

  void syncChanged() {
    cardView.syncChanged();
  }

  private void configurePasswordVisibility() {
    char maskedEchoChar = password.getEchoChar();
    Icon revealIcon = new FlatRevealIcon();
    passwordVisibility.setIcon(revealIcon);
    passwordVisibility.setSelectedIcon(
        new FlatRevealIcon() {
          @Override
          protected void paintIcon(Component component, Graphics2D graphics) {
            super.paintIcon(component, graphics);
            graphics.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.drawLine(2, 14, 14, 2);
          }
        });
    int iconPadding = Math.max(0, 8 - FlatUIUtils.getBorderFocusAndLineWidth(password));
    passwordVisibility.setPreferredSize(
        new Dimension(revealIcon.getIconWidth() + 2 * iconPadding, revealIcon.getIconHeight()));
    passwordVisibility.putClientProperty(FlatClientProperties.STYLE, "focusable: true");
    passwordVisibility.getAccessibleContext().setAccessibleName("Hiện mật khẩu");
    passwordVisibility.setToolTipText("Hiện mật khẩu");
    passwordVisibility.addItemListener(
        e -> {
          boolean revealed = passwordVisibility.isSelected();
          password.setEchoChar(revealed ? '\0' : maskedEchoChar);
          passwordVisibility.setToolTipText(revealed ? "Ẩn mật khẩu" : "Hiện mật khẩu");
        });
    password.putClientProperty(
        FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, passwordVisibility);
    password.addHierarchyListener(
        e -> {
          if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !password.isShowing()) {
            passwordVisibility.setSelected(false);
          }
        });
  }

  private void clearPassword() {
    passwordVisibility.setSelected(false);
    password.setText("");
  }

  private void login() {
    String user = username.getText().strip(), pass = new String(password.getPassword());
    clearPassword();
    frame.run(
        "Đang đăng nhập…",
        () -> frame.api.post("/admin/login", new Api.Credentials(user, pass), Api.Login.class),
        r -> {
          frame.student.clear();
          session = r;
          frame.api.token(r.token());
          updateConnectedCard(frame.status());
          page = 0;
          search(() -> {
            if (logged()) pages.show(body, "dashboard");
          });
        },
        failure -> frame.error(failure, "Đăng nhập thất bại"));
  }

  void onCard(Api.ReaderStatus s) {
    onCard(s, () -> {});
  }

  void onCard(Api.ReaderStatus s, Runnable ready) {
    if (cardView.isOpen() && !cardView.onReaderChanged(s)) pages.show(body, "dashboard");
    if (s.present() && s.initialized()) updateCardStatus(s.cardId(), s.lifecycleStatus());
    updateConnectedCard(s);
    ready.run();
  }

  private void updateConnectedCard(Api.ReaderStatus status) {
    String id = Objects.toString(status.cardId(), "").strip();
    cardButton.setText(status.present() && !id.isBlank() ? id : "------------");
    cardButton.setConnected(status.present());
    cardButton.setEnabled(logged() && status.present());
    String description = !status.present() ? "Chưa có thẻ kết nối"
        : id.isBlank() ? "Đã kết nối thẻ, chưa có ID" : "Thẻ đang kết nối: " + id;
    cardButton.setToolTipText(description);
    cardButton.getAccessibleContext().setAccessibleName(description);
  }

  private static void setFixedSize(JComponent component, int width, int height) {
    Dimension size = new Dimension(width, height);
    component.setMinimumSize(size);
    component.setPreferredSize(size);
    component.setMaximumSize(size);
  }

  private static final class CardStatusButton extends JButton {
    private boolean connected;

    CardStatusButton(ActionListener action) {
      super("------------");
      addActionListener(action);
      setHorizontalAlignment(SwingConstants.LEFT);
      setMargin(new Insets(0, 8, 0, 16));
    }

    void setConnected(boolean connected) {
      this.connected = connected;
      repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      Graphics2D g = (Graphics2D) graphics.create();
      try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(connected ? new Color(0x208A46) : new Color(0xA0A0A0));
        int diameter = 12;
        g.fillOval(getWidth() - 8 - diameter, (getHeight() - diameter) / 2, diameter, diameter);
      } finally {
        g.dispose();
      }
    }
  }

  private void openCard() {
    Api.ReaderStatus status = frame.status();
    if (frame.isBusy() || !logged() || !status.present()) return;
    String path = AdminCardView.cardPath("/admin/card", status, null);
    frame.run("Đang mở thông tin thẻ…", () -> frame.api.get(path, Api.AdminCard.class),
        result -> {
          if (!status.connectionId().equals(result.status().connectionId())) {
            throw new ApiClient.ApiError(409, "CARD_CHANGED",
                "Thẻ đang kết nối đã thay đổi. Vui lòng chọn lại thẻ.");
          }
          if (!result.status().initialized()) {
            frame.info("Thẻ chưa được khởi tạo, cần khởi tạo trước khi thẻ được sử dụng");
          }
          if (logged() && status.connectionId().equals(frame.status().connectionId())) {
            if (searchPending) {
              invalidateSearch();
              tableDirty = true;
            }
            cardView.open(result);
            pages.show(body, "card");
          }
        }, this::cardError);
  }

  private void backToList() {
    Runnable show = () -> {
      cardView.close();
      pages.show(body, "dashboard");
    };
    if (tableDirty) search(show);
    else show.run();
  }

  void cardError(Throwable failure) {
    boolean stale = failure instanceof ApiClient.ApiError error
        && Set.of("CARD_CHANGED", "CARD_REMOVED", "NO_CARD").contains(error.code);
    if (stale) suspendCard();
    frame.error(failure);
    if (stale) frame.refreshCard();
  }

  private String selectedCardId() {
    int row = table.getSelectedRow();
    return isCardRow(row) ? (String) model.getValueAt(table.convertRowIndexToModel(row), 0) : null;
  }

  private boolean isCardRow(int row) {
    if (row < 0 || row >= table.getRowCount()) return false;
    Object id = model.getValueAt(table.convertRowIndexToModel(row), 0);
    return id instanceof String text && !text.isBlank();
  }

  private void showStatusMenu(MouseEvent event) {
    if (!event.isPopupTrigger() || searchPending) return;
    int row = table.rowAtPoint(event.getPoint());
    int column = table.columnAtPoint(event.getPoint());
    if (row < 0 || column < 0 || table.convertColumnIndexToModel(column) != 7) return;
    openStatusMenu(row, event.getX(), event.getY());
  }

  private void openStatusMenu(int viewRow, int x, int y) {
    if (searchPending || !isCardRow(viewRow)) return;
    table.setRowSelectionInterval(viewRow, viewRow);
    contextCardId = selectedCardId();
    updateSelectedActions();
    Rectangle visible = table.getVisibleRect();
    Dimension size = statusMenu.getPreferredSize();
    int menuX = Math.max(visible.x, Math.min(x, visible.x + visible.width - size.width));
    int menuY = Math.max(visible.y, Math.min(y, visible.y + visible.height - size.height));
    statusMenu.show(table, menuX, menuY);
  }

  private String contextTarget(String requiredStatus) {
    int selected = table.getSelectedRow();
    if (searchPending || selected < 0 || contextCardId == null
        || !contextCardId.equals(selectedCardId())) return null;
    Object status = model.getValueAt(table.convertRowIndexToModel(selected), 7);
    return requiredStatus.equals(status) ? contextCardId : null;
  }

  private void updateSelectedActions() {
    int row = table.getSelectedRow();
    Object status = row < 0 ? null : model.getValueAt(table.convertRowIndexToModel(row), 7);
    boolean selected = !searchPending && isCardRow(row);
    block.setEnabled(selected && "ACTIVE".equals(status));
    restore.setEnabled(selected && "BLOCKED".equals(status));
    delete.setEnabled(selected && "BLOCKED".equals(status));
  }

  private void updatePaging() {
    if (pageData == null) {
      resultCount.setText("Hiển thị 0 trên tổng số 0 kết quả");
      pagination.setPage(0, 0, CARD_PAGE_SIZE, searchPending);
      return;
    }
    int count = pageData.items().size();
    long first = (long) pageData.page() * pageData.pageSize() + 1;
    String shown = count == 0 ? "0" : count == 1 ? Long.toString(first) : first + "–" + (first + count - 1);
    resultCount.setText("Hiển thị " + shown + " trên tổng số " + pageData.total() + " kết quả");
    pagination.setPage(pageData.page(), pageData.total(), pageData.pageSize(), searchPending);
  }

  private void changePage(int requested) {
    if (!logged() || searchPending || frame.isBusy() || pageData == null || pageData.pageSize() <= 0) return;
    long totalPages = ((long) pageData.total() + pageData.pageSize() - 1) / pageData.pageSize();
    if (requested < 0 || requested >= totalPages || requested == pageData.page()) return;
    int previous = pageData.page();
    page = requested;
    search(() -> {}, () -> {
      page = previous;
      searchPending = false;
      updateSelectedActions();
      updatePaging();
    });
  }

  private void updateCardStatus(String id, String status) {
    if (!"ACTIVE".equals(status) && !"BLOCKED".equals(status)) return;
    for (int row = 0; row < model.getRowCount(); row++) {
      if (id.equals(model.getValueAt(row, 0))) {
        model.setValueAt(status, row, 7);
        updateSelectedActions();
        return;
      }
    }
  }

  private void blockSelected() {
    String id = contextTarget("ACTIVE");
    if (id == null || frame.isBusy()) return;
    if (JOptionPane.showConfirmDialog(
            this,
            "Tạm khóa thẻ " + id + "?\nCác thao tác mới sẽ bị chặn ngay.",
            "Khóa thẻ",
            JOptionPane.YES_NO_OPTION)
        != JOptionPane.YES_OPTION) {
      return;
    }
    frame.run(
        "Đang tạm khóa thẻ…",
        () -> frame.api.post("/admin/cards/" + id + "/block", null, Api.Result.class),
        r -> {
          updateCardStatus(id, "BLOCKED");
          frame.info(r.message());
          frame.refreshCard();
        },
        failure -> {
          if (failure instanceof ApiClient.ApiError error && error.code.equals("CARD_ALREADY_BLOCKED")) {
            updateCardStatus(id, "BLOCKED");
          }
          frame.error(failure);
        });
  }

  private Api.ReaderStatus connectedSelection() {
    String id = contextTarget("BLOCKED");
    if (id == null || frame.isBusy()) return null;
    Api.ReaderStatus status = frame.status();
    if (status.present() && id.equals(status.cardId())) return status;
    frame.info("Vui lòng kết nối đúng thẻ " + id + " đã chọn trong bảng.");
    return null;
  }

  private void restoreSelected() {
    Api.ReaderStatus card = connectedSelection();
    if (card == null || JOptionPane.showConfirmDialog(this, "Khôi phục thẻ " + card.cardId() + "?",
        "Khôi phục thẻ", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
    String path = AdminCardView.cardPath("/admin/card/unblock", card, card.cardId());
    frame.run("Đang khôi phục thẻ…", () -> frame.api.post(path, null, Api.Result.class),
        result -> {
          updateCardStatus(card.cardId(), "ACTIVE");
          frame.info(result.message());
          frame.refreshCard();
        }, this::cardError);
  }

  private void deleteSelected() {
    Api.ReaderStatus card = connectedSelection();
    if (card == null) return;
    String preview = AdminCardView.cardPath("/admin/card/delete-preview", card, card.cardId());
    frame.run(
        "Đang kiểm tra trước khi xóa…",
        () -> frame.api.get(preview, Api.DeletePreview.class),
        p -> {
          if (!card.cardId().equals(p.cardId())) {
            throw new ApiClient.ApiError(409, "CARD_CHANGED",
                "Thẻ đang kết nối đã thay đổi. Vui lòng chọn lại thẻ.");
          }
          if (p.hasBalance()
              && JOptionPane.showConfirmDialog(
                      this,
                      "Thẻ vẫn còn số dư! Xác nhận xoá?\n" + p.cardId(),
                      "Số dư trên thẻ",
                      JOptionPane.YES_NO_OPTION,
                      JOptionPane.WARNING_MESSAGE)
                  != JOptionPane.YES_OPTION) {
            return;
          }
          if (p.unsyncedCount() > 0
              && JOptionPane.showConfirmDialog(
                      this,
                      "Thẻ vẫn còn "
                          + p.unsyncedCount()
                          + " giao dịch chưa đồng bộ! Tiếp tục xoá?\n"
                          + "Chứng từ được giữ riêng để đối chiếu/hoàn tiền sau này.",
                      "Khoản nạp chưa đồng bộ",
                      JOptionPane.YES_NO_OPTION,
                      JOptionPane.WARNING_MESSAGE)
                  != JOptionPane.YES_OPTION) {
            return;
          }
          Object[] options = {"Xóa cứng", "Giữ lại"};
          if (JOptionPane.showOptionDialog(
                  this,
                  "Xóa cứng đăng ký thẻ "
                      + p.cardId()
                      + "?\nKhông thể khôi phục thẻ. Chứng từ nạp chờ được giữ để đối chiếu.",
                  "Xóa cứng thẻ",
                  JOptionPane.DEFAULT_OPTION,
                  JOptionPane.WARNING_MESSAGE,
                  null,
                  options,
                  options[1])
              != 0) {
            return;
          }
          frame.run(
              "Đang xóa thẻ…",
              () ->
                  frame.api.post(
                      AdminCardView.cardPath("/admin/card/delete", card, card.cardId()),
                      new Api.DeleteCard(
                          p.cardId(),
                          p.hasBalance(),
                          p.counter(),
                          p.walletVersion(),
                          p.unsyncedCount() > 0,
                          p.previewToken()),
                      Api.Result.class),
              r -> {
                frame.info(r.message());
                frame.reloadDevices();
                search(frame::refreshCard);
              }, this::cardError);
        }, this::cardError);
  }

  private void bankReceipts() {
    frame.run(" ", () -> {
      Api.AdminTransactionPage page = frame.api.get("/admin/transactions?page=0", Api.AdminTransactionPage.class);
      if (!AdminTransactionsView.validPage(page, 0))
        throw new ApiClient.ApiError(502, "INVALID_RESPONSE", "Dữ liệu giao dịch không hợp lệ.");
      return page;
    }, page -> {
      AdminTransactionsView view = new AdminTransactionsView(frame, page);
      JDialog dialog = new JDialog(frame, "Giao dịch", true);
      dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
      dialog.setResizable(false);
      JPanel content = new JPanel(new BorderLayout(0, 16));
      content.setBackground(new Color(0xF2F2F2));
      content.setBorder(BorderFactory.createEmptyBorder(8, 16, 16, 16));
      content.add(view, BorderLayout.CENTER);
      dialog.setContentPane(content);
      dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
          KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
      dialog.pack();
      dialog.setSize(1280, dialog.getHeight());
      dialog.setLocationRelativeTo(frame);
      try {
        dialog.setVisible(true);
      } finally {
        view.dispose();
        dialog.dispose();
      }
    });
  }

  private void search(Runnable ready) {
    search(ready, () -> {});
  }

  private void search(Runnable ready, Runnable onFailure) {
    if (!logged()) return;
    invalidateSearch();
    searchPending = true;
    table.clearSelection();
    updateSelectedActions();
    updatePaging();
    if (!validYear()) {
      Toast.showMessage(this, "Năm sinh phải gồm 4 chữ số.", true);
      onFailure.run();
      ready.run();
      return;
    }
    String path = searchPath();
    frame.run(
        " ",
        () -> loadPage(path),
        r -> {
          showSearchResults(r);
          ready.run();
        },
        failure -> {
          onFailure.run();
          searchPending = false;
          updateSelectedActions();
          updatePaging();
          String message = failure instanceof ApiClient.ApiError error && error.code.equals("BACKEND_OLD_PAGE_SIZE")
              ? error.getMessage() : "Chưa tải được danh sách hồ sơ. Hãy thử lại.";
          ready.run();
          frame.error(failure);
          if (logged()) Toast.showMessage(this, message, true);
        });
  }

  private boolean validYear() {
    String year = yearFilter.getText().strip();
    return year.isEmpty() || year.matches("[0-9]{4}");
  }

  private String searchPath() {
    String yr = yearFilter.getText().strip();
    String[] sorts = {"CARD_ID", "NEWEST", "OLDEST", "NAME_ASC", "NAME_DESC"};
    return "/admin/cards/overview?q="
            + ApiClient.query(query.getText())
            + "&group="
            + ApiClient.query(classFilter.getText().strip())
            + "&program="
            + (program.getSelectedIndex() == 0 ? "" : program.getSelectedItem())
            + "&sort="
            + sorts[sort.getSelectedIndex()]
            + "&page="
            + page
            + (yr.isEmpty() ? "" : "&year=" + yr);
  }

  private Api.CardListPage loadPage(String path) {
    for (int attempt = 0; attempt < 3; attempt++) {
      Api.CardListPage result = frame.api.get(path, Api.CardListPage.class);
      if (result.pageSize() != CARD_PAGE_SIZE) {
        throw new ApiClient.ApiError(409, "BACKEND_OLD_PAGE_SIZE",
            "Backend chưa được cập nhật. Vui lòng chạy 09-Cap-nhat-backend.cmd.");
      }
      if (result.items() == null || result.items().size() > CARD_PAGE_SIZE
          || result.page() < 0 || result.total() < 0) {
        throw new ApiClient.ApiError(502, "REQUEST_FAILED", "Không đọc được trang kết quả.");
      }
      int last = result.total() == 0 ? 0 : (result.total() - 1) / result.pageSize();
      if (result.page() <= last) return result;
      int pageStart = path.lastIndexOf("&page=") + "&page=".length();
      int pageEnd = path.indexOf('&', pageStart);
      if (pageEnd < 0) pageEnd = path.length();
      path = path.substring(0, pageStart) + last + path.substring(pageEnd);
    }
    throw new ApiClient.ApiError(409, "REQUEST_FAILED", "Danh sách đã thay đổi. Vui lòng thử lại.");
  }

  private void invalidateSearch() {
    searchRevision++;
    searchDebounce.stop();
    statusMenu.setVisible(false);
    contextCardId = null;
    if (searchWorker != null) {
      SwingWorker<Api.CardListPage, Void> previous = searchWorker;
      searchWorker = null;
      previous.cancel(true);
    }
  }

  private void queueSearch() {
    if (!logged()) return;
    invalidateSearch();
    page = 0;
    searchPending = true;
    table.clearSelection();
    updateSelectedActions();
    updatePaging();
    searchDebounce.restart();
  }

  private void liveSearch() {
    if (!logged()) return;
    invalidateSearch();
    searchPending = true;
    table.clearSelection();
    updateSelectedActions();
    updatePaging();
    if (!validYear()) {
      Toast.showMessage(this, "Năm sinh phải gồm 4 chữ số.", true);
      return;
    }
    String path = searchPath();
    long revision = searchRevision;
    Api.Login expectedSession = session;
    searchWorker = new SwingWorker<>() {
      @Override protected Api.CardListPage doInBackground() {
        return loadPage(path);
      }

      @Override protected void done() {
        if (searchWorker != this || revision != searchRevision || session != expectedSession) return;
        searchWorker = null;
        try {
          showSearchResults(get());
        } catch (Exception e) {
          if (e instanceof InterruptedException) Thread.currentThread().interrupt();
          Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
          if (cause instanceof ApiClient.ApiError error && error.code.startsWith("ADMIN_")) {
            expired();
          } else {
            searchPending = false;
            updateSelectedActions();
            updatePaging();
            String message = cause instanceof ApiClient.ApiError error && error.code.equals("BACKEND_OLD_PAGE_SIZE")
                ? error.getMessage() : "Chưa tải được danh sách. Nhấn Enter để thử lại.";
            Toast.showMessage(AdminView.this, message, true);
          }
        }
      }
    };
    searchWorker.execute();
  }

  private void showSearchResults(Api.CardListPage result) {
    pageData = result;
    page = result.page();
    tableDirty = false;
    Map<String, String> statuses = result.lifecycleStatuses() == null
        ? Map.of() : result.lifecycleStatuses();
    model.setRowCount(0);
    for (Profile p : result.items()) {
      model.addRow(new Object[] {
          p.cardId(), p.studentCode(), p.fullName(), p.className(), p.birthDate(), p.phone(),
          p.intakeYear(), statuses.getOrDefault(p.cardId(), "")
      });
    }
    model.setRowCount(CARD_PAGE_SIZE);
    for (int row = 0; row < CARD_PAGE_SIZE; row++) {
      int height = CARD_ROW_HEIGHT + (row < CARD_PAGE_SIZE - 1 ? CARD_SEPARATOR_HEIGHT : 0);
      if (table.getRowHeight(row) != height) table.setRowHeight(row, height);
    }
    searchPending = false;
    updateSelectedActions();
    updatePaging();
  }

  private static final class StatusRenderer extends JPanel implements TableCellRenderer {
    private final JLabel placeholder = new JLabel("--", SwingConstants.CENTER);
    private final JLabel pill = new JLabel("", SwingConstants.CENTER) {
      @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
          g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
          g.setColor(getBackground());
          g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
        } finally {
          g.dispose();
        }
        super.paintComponent(graphics);
      }
    };

    StatusRenderer() {
      super(new GridBagLayout());
      pill.setOpaque(false);
      setFixedSize(pill, 96, 24);
      pill.setVerticalAlignment(SwingConstants.CENTER);
      pill.putClientProperty("html.disable", Boolean.TRUE);
      add(pill);
    }

    @Override public Component getTableCellRendererComponent(
        JTable table, Object value, boolean selected, boolean focus, int row, int column) {
      if (value == null) {
        placeholder.setOpaque(true);
        placeholder.setBackground(selected ? table.getSelectionBackground() : table.getBackground());
        placeholder.setForeground(table.getForeground());
        placeholder.setFont(table.getFont());
        return placeholder;
      }
      setBackground(selected ? table.getSelectionBackground() : table.getBackground());
      pill.setFont(table.getFont());
      if ("ACTIVE".equals(value)) {
        pill.setText("Hoạt động");
        pill.setForeground(new Color(0x176B36));
        pill.setBackground(new Color(0xDEF3E4));
      } else if ("BLOCKED".equals(value)) {
        pill.setText("Tạm khoá");
        pill.setForeground(new Color(0x505050));
        pill.setBackground(new Color(0xEAEAEA));
      } else {
        pill.setText("--");
        pill.setForeground(new Color(0x606060));
        pill.setBackground(new Color(0xF2F2F2));
      }
      return this;
    }
  }

  private void preview() {
    int row = table.getSelectedRow();
    if (!isCardRow(row) || searchPending) {
      return;
    }
    int modelRow = table.convertRowIndexToModel(row);
    String id = (String) model.getValueAt(modelRow, 0);
    String lifecycle = Objects.toString(model.getValueAt(modelRow, 7), "");
    frame.run(
        "Đang mở hồ sơ…",
        () -> frame.api.get("/admin/cards/" + ApiClient.query(id), Profile.class),
        p -> {
          if (!id.equals(p.cardId())) {
            throw new ApiClient.ApiError(409, "CARD_CHANGED", "Không tìm thấy đúng hồ sơ thẻ đã chọn.");
          }
          cardView.openProfile(p, lifecycle, frame.status());
          pages.show(body, "card");
        });
  }

  private void forgot() {
    JDialog dialog = createForgotPasswordDialog();
    dialog.setLocationRelativeTo(this);
    dialog.setVisible(true);
  }

  private JDialog createForgotPasswordDialog() {
    JDialog dialog = new JDialog(frame, "Quên mật khẩu admin", true);
    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    dialog.setResizable(false);

    Dimension fieldSize = new Dimension(320, 32);
    JTextField identity = new JTextField(username.getText());
    identity.setPreferredSize(fieldSize);
    identity.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Nhập username hoặc email");
    identity.putClientProperty(
        FlatClientProperties.STYLE, Map.of("inactiveBackground", identity.getBackground()));
    JLabel prompt = new JLabel("Username hoặc Email đã đăng ký");
    prompt.setPreferredSize(fieldSize);
    prompt.setLabelFor(identity);

    JLabel error = new JLabel(" ");
    error.setForeground(new Color(0xC62828));
    error.setPreferredSize(new Dimension(fieldSize.width, error.getPreferredSize().height));
    error.setText("");

    JButton ok = new JButton("OK");
    JButton cancel = CanteenFrame.button("Cancel", e -> dialog.dispose());
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

    JPanel body = new JPanel(new GridBagLayout());
    body.setBackground(UIManager.getColor("OptionPane.background"));
    body.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
    GridBagConstraints row = new GridBagConstraints();
    row.gridx = 0;
    row.fill = GridBagConstraints.HORIZONTAL;
    body.add(prompt, row);
    row.insets.top = 4;
    body.add(identity, row);
    row.insets.top = 8;
    body.add(error, row);
    row.insets.top = 16;
    body.add(actions, row);

    ok.addActionListener(
        e -> {
          if (frame.isBusy()) return;
          String who = identity.getText().strip();
          identity.setEditable(false);
          ok.setEnabled(false);
          cancel.setEnabled(false);
          dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
          frame.runWithoutOverlay(
              () ->
                  frame.api.post(
                      "/admin/forgot?reportMissing=true", new Api.Forgot(who), Api.Result.class),
              result -> {
                if (!dialog.isShowing()) return;
                dialog.dispose();
                showRecoveryOtp(who);
              },
              failure -> {
                if (!dialog.isShowing()) return;
                identity.setEditable(true);
                ok.setEnabled(true);
                cancel.setEnabled(true);
                dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                if (failure instanceof ApiClient.ApiError apiError
                    && apiError.code.equals("RESET_IDENTITY_NOT_FOUND")) {
                  error.setText(apiError.getMessage());
                  identity.getAccessibleContext().setAccessibleDescription(apiError.getMessage());
                } else {
                  error.setText("");
                  identity.getAccessibleContext().setAccessibleDescription(null);
                  JOptionPane.showMessageDialog(
                      dialog,
                      CanteenFrame.html(failure.getMessage()),
                      "Chưa thực hiện được thao tác",
                      JOptionPane.WARNING_MESSAGE);
                }
                identity.requestFocusInWindow();
              });
        });
    dialog.setContentPane(body);
    dialog.getRootPane().setDefaultButton(ok);
    dialog.getRootPane().registerKeyboardAction(
        e -> cancel.doClick(0),
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
        JComponent.WHEN_IN_FOCUSED_WINDOW);
    dialog.pack();
    SwingUtilities.invokeLater(identity::requestFocusInWindow);
    return dialog;
  }

  private JPanel createRecoveryPasswordPage() {
    JPanel form = new JPanel(new GridBagLayout());
    form.setOpaque(false);
    Dimension controlSize = new Dimension(CREDENTIAL_FORM_WIDTH, 40);
    Font formFont = form.getFont().deriveFont(Font.PLAIN, 16f);
    for (JComponent control : new JComponent[] {newPassword, confirmPassword, finishRecovery}) {
      control.setFont(formFont);
      control.setPreferredSize(controlSize);
    }
    newPassword.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Nhập mật khẩu mới");
    confirmPassword.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
        "Xác nhận mật khẩu mới");
    newPassword.addActionListener(e -> confirmPassword.requestFocusInWindow());
    confirmPassword.addActionListener(e -> finishRecovery.doClick(0));
    JLabel title = new JLabel("Đặt lại mật khẩu", SwingConstants.CENTER);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 32f));
    title.setPreferredSize(new Dimension(CREDENTIAL_FORM_WIDTH, 43));
    JLabel newLabel = new JLabel("Mật khẩu mới");
    newLabel.setLabelFor(newPassword);
    JLabel confirmLabel = new JLabel("Xác nhận mật khẩu mới");
    confirmLabel.setLabelFor(confirmPassword);
    resetFeedback.setFont(formFont);
    resetFeedback.setForeground(TTM_CuoiKy.ERROR_TEXT_COLOR);
    resetFeedback.setPreferredSize(new Dimension(CREDENTIAL_FORM_WIDTH, 22));

    GridBagConstraints row = new GridBagConstraints();
    row.gridx = 0;
    row.fill = GridBagConstraints.HORIZONTAL;
    form.add(title, row);
    row.insets.top = 32;
    form.add(newLabel, row);
    row.insets.top = 4;
    form.add(newPassword, row);
    row.insets.top = 16;
    form.add(confirmLabel, row);
    row.insets.top = 4;
    form.add(confirmPassword, row);
    row.insets.top = 16;
    form.add(resetFeedback, row);
    row.insets.top = 16;
    form.add(finishRecovery, row);

    JPanel page = new JPanel(new BorderLayout());
    page.setBackground(Color.WHITE);
    JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 16));
    header.setOpaque(false);
    header.add(recoveryBack);
    page.add(header, BorderLayout.NORTH);
    JPanel center = new JPanel(new GridBagLayout());
    center.setOpaque(false);
    center.add(form);
    page.add(center, BorderLayout.CENTER);
    page.registerKeyboardAction(e -> recoveryBack.doClick(0),
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    return page;
  }

  private void showRecoveryOtp(String who) {
    clearPassword();
    recoveryIdentity = who;
    recoveryGrant = null;
    recoveryOtp.clear();
    recoveryOtp.setDescription("Mã OTP đã được gửi đến email đã đăng ký.");
    recoveryOtp.setInputEnabled(true);
    pages.show(body, "recoveryOtp");
    SwingUtilities.invokeLater(recoveryOtp::focusInput);
  }

  private void verifyRecoveryOtp() {
    String who = recoveryIdentity;
    if (who == null || recoveryGrant != null || !recoveryOtp.inputEnabled() || frame.isBusy()) return;
    char[] digits = recoveryOtp.readDigits();
    if (digits == null) {
      recoveryOtp.setFeedback("Vui lòng nhập đủ 6 chữ số OTP.");
      recoveryOtp.focusInput();
      return;
    }
    Api.VerifyRecoveryOtp request;
    try {
      request = new Api.VerifyRecoveryOtp(who, new String(digits));
    } finally {
      Arrays.fill(digits, '\0');
    }
    recoveryOtp.clearDigits();
    recoveryOtp.setInputEnabled(false);
    frame.run(
        "Đang kiểm tra mã…",
        () -> frame.api.post("/admin/recover/verify", request, Api.RecoveryGrant.class),
        result -> {
          if (!who.equals(recoveryIdentity)) return;
          if (result == null || result.grant() == null || result.grant().isBlank()) {
            showOtpFailure("Không nhận được quyền đặt lại mật khẩu. Vui lòng yêu cầu mã OTP mới.");
            return;
          }
          recoveryGrant = result.grant();
          recoveryOtp.setFeedback("");
          clearResetFeedback();
          setResetControlsEnabled(true);
          pages.show(body, "recoveryPassword");
          SwingUtilities.invokeLater(newPassword::requestFocusInWindow);
        },
        failure -> {
          if (!who.equals(recoveryIdentity)) return;
          String message =
              failure instanceof ApiClient.ApiError error && error.code.equals("RESET_OTP_INVALID")
                  ? "Mã OTP không đúng hoặc đã hết hạn!"
                  : failure.getMessage();
          showOtpFailure(message);
        });
  }

  private void showOtpFailure(String message) {
    recoveryOtp.setInputEnabled(true);
    recoveryOtp.setFeedback(CanteenFrame.html(message));
    recoveryOtp.focusInput();
  }

  private void completeRecovery() {
    String who = recoveryIdentity;
    String grant = recoveryGrant;
    if (who == null || grant == null || !finishRecovery.isEnabled() || frame.isBusy()) return;
    char[] value = newPassword.getPassword();
    char[] confirmation = confirmPassword.getPassword();
    Api.CompleteRecovery request;
    try {
      if (!AdminPasswordPolicy.acceptsLength(value.length)) {
        showResetFeedback(AdminPasswordPolicy.LENGTH_ERROR);
        newPassword.requestFocusInWindow();
        return;
      }
      if (!Arrays.equals(value, confirmation)) {
        showResetFeedback("Xác nhận mật khẩu mới không khớp");
        confirmPassword.requestFocusInWindow();
        return;
      }
      request = new Api.CompleteRecovery(who, grant, new String(value));
    } finally {
      Arrays.fill(value, '\0');
      Arrays.fill(confirmation, '\0');
    }
    setResetControlsEnabled(false);
    frame.run(
        "Đang đặt lại mật khẩu…",
        () -> frame.api.post("/admin/recover/complete", request, Api.Result.class),
        result -> {
          if (!grant.equals(recoveryGrant)) return;
          expired();
          frame.info("Đặt lại mật khẩu thành công!");
        },
        failure -> {
          if (!grant.equals(recoveryGrant)) return;
          if (failure instanceof ApiClient.ApiError error && error.code.equals("RESET_GRANT_INVALID")) {
            discardRecovery();
          } else {
            setResetControlsEnabled(true);
          }
          frame.info(failure.getMessage());
        });
  }

  private void setResetControlsEnabled(boolean enabled) {
    newPassword.setEnabled(enabled);
    confirmPassword.setEnabled(enabled);
    finishRecovery.setEnabled(enabled);
    recoveryBack.setEnabled(enabled);
  }

  private void showResetFeedback(String message) {
    resetFeedback.setText(message);
    resetFeedbackTimeout.restart();
  }

  private void clearResetFeedback() {
    resetFeedbackTimeout.stop();
    resetFeedback.setText(" ");
  }

  void discardRecovery() {
    if (recoveryIdentity == null) return;
    recoveryIdentity = null;
    recoveryGrant = null;
    recoveryOtp.clear();
    recoveryOtp.setInputEnabled(false);
    newPassword.setText("");
    confirmPassword.setText("");
    clearResetFeedback();
    setResetControlsEnabled(false);
    pages.show(body, "login");
  }
}
