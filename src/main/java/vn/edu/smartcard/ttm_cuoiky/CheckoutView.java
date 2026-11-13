package vn.edu.smartcard.ttm_cuoiky;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import vn.edu.smartcard.shared.*;

/**
 * Staff order tab + customer display. Card authentication starts only from the explicit payment
 * action.
 */
final class CheckoutView extends JPanel {
  private static final int DISH_ROW_HEIGHT = 96;
  private static final int DISH_IMAGE_SIZE = 80;
  private static final int DISH_ADD_ICON_SIZE = 48;
  private static final int DISH_ADD_ICON_RIGHT = 24;
  private static final int DISH_ADD_ICON_TEXT_GAP = 8;
  private static final int DISH_ROW_GAP = 8;
  private static final int DISH_LIST_PADDING = 8;
  private static final int CUSTOMER_VISIBLE_DISHES = 5;
  private static final int CUSTOMER_DISH_ROW_HEIGHT = 96;
  private static final int CUSTOMER_DISH_IMAGE_SIZE = 80;
  private static final int CART_ROW_HEIGHT = 96;
  private static final int CART_VISIBLE_ROWS = 5;
  private static final int CART_HEADER_HEIGHT = 36;
  private static final int CART_LOADING_SIZE = 64;
  private static final int CART_LOADING_TEXT_GAP = 16;
  private static final String PIN_LOCK_WARNING =
      "Nếu nhập sai quá số lần cho phép, thẻ sẽ bị khóa!";
  private static final Color CART_HEADER_BACKGROUND = new Color(0xE5E5E5);
  private static final Color CART_EMPTY_BACKGROUND = new Color(0xF2F2F2);
  private static final Color CART_SEPARATOR_COLOR = new Color(0xDDDDDD);
  private static final Color CART_BORDER_COLOR = new Color(0xC2C2C2);

  private final CanteenFrame frame;
  private final JTabbedPane tabs = new JTabbedPane();
  private final Icon addDishIcon = DesktopIcons.svg(
      "/icons/add-square-svgrepo-com.svg", DISH_ADD_ICON_SIZE, "biểu tượng thêm món");
  private final Icon addDishOutlineIcon = DesktopIcons.svg(
      "/icons/add-square-outline-svgrepo-com.svg", DISH_ADD_ICON_SIZE, "biểu tượng thêm món viền");
  private final JPanel menu = new JPanel(new GridLayout(0, 1, 0, DISH_ROW_GAP));
  private final JPanel menuWrap = new JPanel(new BorderLayout());
  private final JTextField search = new JTextField();
  private final JComboBox<String> menuSort =
      new JComboBox<>(new String[] {"Mới -> Cũ", "Bán chạy", "Giá bán"});
  private final JLabel menuResults = new JLabel("Đã hiển thị 0 trên tổng số 0 món ăn.");
  private final javax.swing.Timer searchDebounce = new javax.swing.Timer(500, e -> searchBackend());
  private SwingWorker<Api.MenuSearchResult, Void> searchWorker;
  private long searchGeneration;
  private List<Api.MenuItem> searchResults;
  private boolean searchPending, updatingCategories;
  private String searchError;
  private int totalActiveItems;
  private Map<Long, Long> menuSales;
  private SwingWorker<Api.MenuSales[], Void> salesWorker;
  private long salesGeneration;
  private static DefaultTableModel invoiceTableModel() {
    return new DefaultTableModel(new String[] {"Món ăn", "SL", "Đơn giá", "Thành tiền"}, 0) {
        public boolean isCellEditable(int r, int c) {
          return false;
        }
      };
  }
  private DefaultTableModel cartTableModel() {
    return new DefaultTableModel(new String[] {"Ảnh", "Tên món ăn", "SL", "Giá bán", "Thành tiền", ""}, 0) {
      @Override public Class<?> getColumnClass(int column) {
        return switch (column) {
          case 0 -> Icon.class;
          case 2 -> Integer.class;
          case 5 -> Long.class;
          default -> String.class;
        };
      }

      @Override public boolean isCellEditable(int row, int column) {
        return (column == 2 || column == 5) && canEditCart()
            && getValueAt(row, 5) instanceof Long;
      }
    };
  }
  private final DefaultTableModel cartModel = cartTableModel();
  private final DefaultTableModel customerModel = invoiceTableModel();
  private final JTable cartTable = new JTable(cartModel) {
    @Override protected void paintChildren(Graphics graphics) {
      super.paintChildren(graphics);
      Graphics lines = graphics.create();
      try {
        lines.setColor(CART_SEPARATOR_COLOR);
        for (int row = 0; row < getRowCount() - 1; row++) {
          Rectangle cell = getCellRect(row, 0, true);
          int y = cell.y + cell.height - 1;
          lines.drawLine(0, y, getWidth() - 1, y);
        }
      } finally {
        lines.dispose();
      }
    }
  };
  private final JLabel cartOverlayText = new JLabel("", SwingConstants.CENTER);
  private double cartLoadingAngle;
  private final JLabel cartLoadingIcon = new JLabel(
      DesktopIcons.svg("/icons/invoice-loading.svg", CART_LOADING_SIZE, "biểu tượng chờ thanh toán"),
      SwingConstants.CENTER) {
    @Override protected void paintComponent(Graphics graphics) {
      Graphics2D rotated = (Graphics2D) graphics.create();
      try {
        rotated.rotate(cartLoadingAngle, getWidth() / 2.0, getHeight() / 2.0);
        super.paintComponent(rotated);
      } finally {
        rotated.dispose();
      }
    }
  };
  private final javax.swing.Timer cartLoadingTimer = new javax.swing.Timer(30, e -> {
    cartLoadingAngle = (cartLoadingAngle + 2 * Math.PI / 25) % (2 * Math.PI);
    cartLoadingIcon.repaint();
  });
  private final JPanel cartOverlay = new JPanel(null) {
    @Override public void doLayout() {
      int iconX = (getWidth() - CART_LOADING_SIZE) / 2;
      int iconY = (getHeight() - CART_LOADING_SIZE) / 2;
      cartLoadingIcon.setBounds(iconX, iconY, CART_LOADING_SIZE, CART_LOADING_SIZE);
      Dimension textSize = cartOverlayText.getPreferredSize();
      int textWidth = Math.min(textSize.width, Math.max(0, getWidth() - 16));
      cartOverlayText.setBounds((getWidth() - textWidth) / 2,
          iconY + CART_LOADING_SIZE + CART_LOADING_TEXT_GAP, textWidth, textSize.height);
    }

    @Override protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      graphics.setColor(new Color(220, 220, 220, 205));
      graphics.fillRect(0, 0, getWidth(), getHeight());
    }
  };
  private final LinkedHashMap<Long, Api.OrderItem> cart = new LinkedHashMap<>();
  private List<Api.MenuItem> invoiceMenuSnapshot = List.of();
  private long cartRenderSequence;
  private Api.MenuItem[] items = new Api.MenuItem[0];
  private Api.MenuCategory[] categoryItems = new Api.MenuCategory[0];
  private final JComboBox<Api.MenuCategory> category = new JComboBox<>();
  private final DishImages images = new DishImages();
  private final Icon deleteCartIcon = DesktopIcons.svg(
      "/icons/invoice-trash-white.svg", 24, "biểu tượng xóa món khỏi hóa đơn");
  private final Icon minusCartIcon = DesktopIcons.svg(
      "/icons/invoice-minus.svg", 16, "biểu tượng giảm số lượng");
  private final Icon plusCartIcon = DesktopIcons.svg(
      "/icons/invoice-add.svg", 16, "biểu tượng tăng số lượng");
  private MenuDialogs menuDialogs;
  private MenuCatalogDialog catalogDialog;
  private Api.CreateInvoice creationRequest;
  private Long cancellationRequestedInvoiceId;
  private final JLabel customerTotal = new JLabel("0 ₫"),
      customerReader = new JLabel("Chưa kết nối thẻ");
  private final JButton customerPay, customerBank, qrRetry;
  private final CardLayout customerPages = new CardLayout(), customerSidePages = new CardLayout();
  private final JPanel customerPage = new JPanel(customerPages), customerSide = new JPanel(customerSidePages);
  private final JPanel customerItems = new JPanel(new GridLayout(0, 1, 0, DISH_ROW_GAP));
  private final JLabel customerEmpty = new JLabel("Hiện chưa có hoá đơn.", SwingConstants.CENTER);
  private final JLabel cardTitle = new JLabel("Không phát hiện thẻ", SwingConstants.CENTER);
  private final JLabel cardHelp = new JLabel("Vui lòng kết nối thẻ để tiếp tục.", SwingConstants.CENTER);
  private final JLabel cardPicture = new JLabel(DesktopIcons.svg(
      "/icons/card-remove-1-svgrepo-com.svg", 128, "biểu tượng chưa phát hiện thẻ"));
  private final Icon cardPresentIcon = DesktopIcons.svg(
      "/icons/card-tick-1-svgrepo-com.svg", 128, "thẻ đã kết nối");
  private final Icon cardMissingIcon = cardPicture.getIcon();
  private final JLabel connectedCardId = new JLabel("------------"),
      connectionIndicator = new JLabel(new AdminCardView.ConnectionIcon());
  private final JButton connectedCard = new JButton();
  private final JLabel qrAmount = new JLabel("0 ₫"), qrReference = new JLabel(" "),
      qrStatus = new JLabel(" ", SwingConstants.CENTER);
  private final SixDigitEntry customerPin;
  private long customerRenderSequence, pinInvoiceId, pinFeedbackRevision;
  private long customerRenderedInvoiceId = -1;
  private List<Api.InvoiceLine> customerRenderedLines = List.of();
  private boolean customerRenderedEmpty = true;
  private boolean pinOpen, showingQrPage;

  private record MenuData(Api.MenuItem[] items, Api.MenuCategory[] categories) {}

  private final JLabel total = new JLabel("0 ₫"),
      invoiceInfo = new JLabel("Chưa tạo hóa đơn"),
      readerInfo = new JLabel("Kết nối thẻ trước hoặc sau khi tạo hóa đơn.");
  private final JLabel customerTitle = new JLabel(" ", SwingConstants.CENTER),
      customerHelp = new JLabel(" ", SwingConstants.CENTER),
      qr = new AnimatedBankQr(),
      countdown = new JLabel(" ", SwingConstants.CENTER);
  private final JButton invoiceAction;
  private Api.Invoice invoice;
  private String qrImage = "";
  private boolean authenticating, pollingInvoice, settling, cardPaymentInFlight;
  private String authConnection = "";
  private long serverAtReply, replyNanos, viewSequence;
  private boolean ended;
  private final javax.swing.Timer timer;

  CheckoutView(CanteenFrame frame) {
    super(new BorderLayout(12, 12));
    this.frame = frame;
    this.menuDialogs = new MenuDialogs(frame, this::loadMenu);
    setOpaque(false);
    JPanel left = spaceBetweenPanel();
    left.setBackground(Color.WHITE);
    left.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
    searchDebounce.setRepeats(false);
    search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Tìm kiếm tên món ăn");
    search.getAccessibleContext().setAccessibleName("Tìm kiếm tên món ăn");
    search.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent e) { queueSearch(); }
      public void removeUpdate(DocumentEvent e) { queueSearch(); }
      public void changedUpdate(DocumentEvent e) { queueSearch(); }
    });
    JButton manageMenu = CanteenFrame.button("Quản lý thực đơn", e -> showCatalog());
    manageMenu.setPreferredSize(new Dimension(416, 36));
    left.add(manageMenu);
    JPanel filters = new JPanel(new BorderLayout(8, 0));
    filters.setOpaque(false);
    JLabel categoryLabel = new JLabel("Danh mục");
    categoryLabel.setFont(categoryLabel.getFont().deriveFont(Font.PLAIN, 16f));
    categoryLabel.setLabelFor(category);
    filters.add(categoryLabel, BorderLayout.WEST);
    category.setPreferredSize(new Dimension(320, 36));
    filters.add(category, BorderLayout.CENTER);
    category.addActionListener(e -> { if (!updatingCategories) renderMenu(); });
    left.add(filters);
    JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    searchRow.setOpaque(false);
    JLabel searchLabel = new JLabel("Tìm kiếm");
    searchLabel.setFont(searchLabel.getFont().deriveFont(Font.PLAIN, 16f));
    searchLabel.setLabelFor(search);
    searchRow.add(searchLabel);
    searchRow.add(Box.createHorizontalStrut(8));
    search.setPreferredSize(new Dimension(240, 36));
    searchRow.add(search);
    left.add(searchRow);
    JPanel sortRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    sortRow.setOpaque(false);
    JLabel sortLabel = new JLabel("Sắp xếp");
    sortLabel.setFont(sortLabel.getFont().deriveFont(Font.PLAIN, 16f));
    sortLabel.setLabelFor(menuSort);
    sortRow.add(sortLabel);
    sortRow.add(Box.createHorizontalStrut(8));
    menuSort.setPreferredSize(new Dimension(160, 36));
    menuSort.addActionListener(e -> {
      if (menuSort.getSelectedIndex() == 1 && salesWorker == null
          && (menuSales == null || missingSales(matchingItems()))) refreshSales();
      else renderMenu();
    });
    sortRow.add(menuSort);
    left.add(sortRow);
    JLabel menuTitle = new JLabel("Danh sách các món ăn");
    menuTitle.setFont(menuTitle.getFont().deriveFont(Font.BOLD, 20f));
    JPanel menuSection = new JPanel(new BorderLayout(0, 8));
    menuSection.setOpaque(false);
    menuSection.add(menuTitle, BorderLayout.NORTH);
    menu.setOpaque(false);
    menu.setBorder(BorderFactory.createEmptyBorder(DISH_LIST_PADDING, DISH_LIST_PADDING,
        DISH_LIST_PADDING, DISH_LIST_PADDING));
    menuWrap.setOpaque(false);
    menuWrap.add(menu, BorderLayout.NORTH);
    JScrollPane menuScroll = new JScrollPane(menuWrap);
    menuScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    menuScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
    menuScroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
    Insets scrollBorder = menuScroll.getInsets();
    int listHeight = 4 * DISH_ROW_HEIGHT + 3 * DISH_ROW_GAP + 2 * DISH_LIST_PADDING;
    menuScroll.setPreferredSize(new Dimension(0, listHeight + scrollBorder.top + scrollBorder.bottom));
    menuSection.add(menuScroll, BorderLayout.CENTER);
    left.add(menuSection);
    menuResults.setFont(menuResults.getFont().deriveFont(Font.PLAIN, 16f));
    left.add(menuResults);
    JPanel right = spaceBetweenPanel();
    right.setBackground(Color.WHITE);
    right.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
    JPanel head = new JPanel();
    head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
    head.setOpaque(false);
    JLabel title = new JLabel("HÓA ĐƠN CĂN TIN");
    title.setFont(title.getFont().deriveFont(Font.BOLD, 24));
    JPanel titleRow = new JPanel(new BorderLayout(8, 0));
    titleRow.setOpaque(false);
    titleRow.add(title, BorderLayout.WEST);
    JButton recentInvoices = CanteenFrame.button("Hóa đơn gần đây", e -> recent());
    recentInvoices.setFont(recentInvoices.getFont().deriveFont(Font.PLAIN, 16f));
    recentInvoices.setPreferredSize(new Dimension(180, 36));
    titleRow.add(recentInvoices, BorderLayout.EAST);
    head.add(titleRow);
    for (Component c : head.getComponents()) {
      if (c instanceof JComponent j) {
        j.setAlignmentX(Component.LEFT_ALIGNMENT);
      }
    }
    right.add(head);
    cartTable.setRowHeight(CART_ROW_HEIGHT);
    cartTable.setIntercellSpacing(new Dimension(0, 0));
    cartTable.setShowGrid(false);
    cartTable.setFillsViewportHeight(true);
    cartTable.setBackground(CART_EMPTY_BACKGROUND);
    cartTable.setSelectionBackground(Color.WHITE);
    cartTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    cartTable.getTableHeader().setReorderingAllowed(false);
    configureCartTable();
    cartOverlay.setOpaque(false);
    cartOverlayText.setFont(cartOverlayText.getFont().deriveFont(Font.BOLD, 18f));
    cartOverlay.add(cartLoadingIcon);
    cartOverlay.add(cartOverlayText);
    cartOverlay.addHierarchyListener(e -> {
      if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) {
        if (cartOverlay.isShowing()) cartLoadingTimer.start();
        else cartLoadingTimer.stop();
      }
    });
    JScrollPane cartScroll = new JScrollPane(cartTable);
    cartScroll.setBorder(BorderFactory.createLineBorder(CART_BORDER_COLOR));
    cartScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    cartScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
    cartScroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
    int cartHeight = CART_HEADER_HEIGHT + 1 + CART_VISIBLE_ROWS * CART_ROW_HEIGHT
        + CART_VISIBLE_ROWS - 1 + 2;
    cartScroll.setPreferredSize(new Dimension(0, cartHeight));
    cartScroll.getViewport().setBackground(CART_EMPTY_BACKGROUND);
    JPanel headerCorner = new JPanel();
    headerCorner.setBackground(CART_HEADER_BACKGROUND);
    headerCorner.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, CART_SEPARATOR_COLOR));
    cartScroll.setCorner(JScrollPane.UPPER_TRAILING_CORNER, headerCorner);
    JLayer<JScrollPane> cartLayer = new JLayer<>(cartScroll);
    cartLayer.setGlassPane(cartOverlay);
    cartOverlay.setVisible(false);
    right.add(cartLayer);
    JPanel totalDivider = new JPanel();
    totalDivider.setBackground(CART_BORDER_COLOR);
    totalDivider.setPreferredSize(new Dimension(0, 1));
    right.add(totalDivider);
    JPanel totalRow = new JPanel(new BorderLayout(8, 0));
    totalRow.setOpaque(false);
    JLabel totalLabel = new JLabel("Tổng tiền");
    totalLabel.setFont(totalLabel.getFont().deriveFont(Font.PLAIN, 24f));
    totalRow.add(totalLabel, BorderLayout.WEST);
    total.setFont(total.getFont().deriveFont(Font.BOLD, 24));
    total.setForeground(TTM_CuoiKy.DEFAULT_TEXT_COLOR);
    totalRow.add(total, BorderLayout.EAST);
    right.add(totalRow);
    invoiceAction = CanteenFrame.button("Tạo hóa đơn", e -> {
      if (open() || pending()) cancel();
      else create();
    });
    invoiceAction.setPreferredSize(new Dimension(144, 36));
    JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    actionRow.setOpaque(false);
    actionRow.add(invoiceAction);
    right.add(actionRow);
    JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
    left.setMinimumSize(new Dimension(400, 120));
    right.setMinimumSize(new Dimension(420, 120));
    split.setDividerSize(4);
    split.setEnabled(false);
    split.putClientProperty(FlatClientProperties.STYLE, "gripDotCount: 0");
    split.setResizeWeight(0);
    split.setDividerLocation(400);
    tabs.addTab("Nhân viên bán hàng", split);
    customerPay = CanteenFrame.button("Tiến hành thanh toán", e -> beginAuthentication());
    customerBank = CanteenFrame.button("Thanh toán bằng chuyển khoản", e -> showBankQr());
    qrRetry = CanteenFrame.button("Tải lại QR", e -> retryQr());
    customerPin = new SixDigitEntry(SixDigitEntry.Mode.PIN, "Nhập mã PIN", "Xác nhận thanh toán",
        this::submitCustomerPin, () -> customerPinFeedback(""), true);
    customerPin.addBackButton("Quay lại", this::cancelPinEntry);
    customerPin.setRoundBackButton(true);
    tabs.addTab("Màn hình QR cho khách", buildCustomerPage());
    add(tabs);
    timer = new javax.swing.Timer(1000, e -> tick());
    buttons();
    addHierarchyListener(
        e -> {
          if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
            if (isDisplayable()) {
              timer.start();
            } else {
              timer.stop();
              cancelSearch();
              cancelSales();
              closePin();
            }
          }
        });
  }

  private JComponent buildCustomerPage() {
    JPanel invoicePanel = spaceBetweenPanel();
    invoicePanel.setBackground(Color.WHITE);
    invoicePanel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
    JLabel title = new JLabel("Chi tiết hoá đơn");
    title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
    invoicePanel.add(title);
    JPanel titleDivider = new JPanel();
    titleDivider.setBackground(CART_BORDER_COLOR);
    titleDivider.setPreferredSize(new Dimension(0, 1));
    invoicePanel.add(titleDivider);
    customerItems.setOpaque(false);
    customerItems.setBorder(BorderFactory.createEmptyBorder(
        DISH_LIST_PADDING, DISH_LIST_PADDING, DISH_LIST_PADDING, DISH_LIST_PADDING));
    customerItems.setVisible(false);
    customerEmpty.setFont(customerEmpty.getFont().deriveFont(Font.PLAIN, 16f));
    JPanel itemsWrap = new JPanel(new BorderLayout());
    itemsWrap.setOpaque(false);
    itemsWrap.add(customerItems, BorderLayout.NORTH);
    itemsWrap.add(customerEmpty, BorderLayout.CENTER);
    JScrollPane itemsScroll = new JScrollPane(itemsWrap) {
      @Override public void paint(Graphics graphics) {
        super.paint(graphics);
        Graphics border = graphics.create();
        try {
          border.setColor(CART_BORDER_COLOR);
          border.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
        } finally {
          border.dispose();
        }
      }
    };
    // Paint the outline without consuming viewport space needed by the rows and padding.
    itemsScroll.setBorder(BorderFactory.createEmptyBorder());
    int listHeight = 2 * DISH_LIST_PADDING + CUSTOMER_VISIBLE_DISHES * CUSTOMER_DISH_ROW_HEIGHT
        + (CUSTOMER_VISIBLE_DISHES - 1) * DISH_ROW_GAP;
    itemsScroll.setPreferredSize(new Dimension(0, listHeight));
    itemsScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    itemsScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
    itemsScroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
    invoicePanel.add(itemsScroll);
    JPanel divider = new JPanel();
    divider.setBackground(CART_BORDER_COLOR);
    divider.setPreferredSize(new Dimension(0, 1));
    invoicePanel.add(divider);
    JPanel totalRow = new JPanel(new BorderLayout(8, 0));
    totalRow.setOpaque(false);
    JLabel label = new JLabel("Tổng tiền");
    label.setFont(label.getFont().deriveFont(Font.PLAIN, 24f));
    customerTotal.setFont(customerTotal.getFont().deriveFont(Font.BOLD, 24f));
    totalRow.add(label, BorderLayout.WEST);
    totalRow.add(customerTotal, BorderLayout.EAST);
    invoicePanel.add(totalRow);

    JPanel choice = new JPanel(new BorderLayout());
    choice.setBackground(Color.WHITE);
    choice.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
    connectedCard.setLayout(new BorderLayout());
    connectedCard.setMargin(new Insets(0, 8, 0, 8));
    connectedCard.setPreferredSize(new Dimension(128, 32));
    connectedCard.setFocusable(false);
    connectedCard.setRequestFocusEnabled(false);
    connectedCard.add(connectedCardId, BorderLayout.WEST);
    connectedCard.add(connectionIndicator, BorderLayout.EAST);
    JPanel cardHeader = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    cardHeader.setOpaque(false);
    cardHeader.add(connectedCard);
    choice.add(cardHeader, BorderLayout.NORTH);
    JPanel content = new JPanel();
    content.setOpaque(false);
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    cardTitle.setFont(cardTitle.getFont().deriveFont(Font.BOLD, 28f));
    cardHelp.setFont(cardHelp.getFont().deriveFont(Font.PLAIN, 16f));
    customerTitle.setFont(customerTitle.getFont().deriveFont(Font.BOLD, 16f));
    customerHelp.setFont(customerHelp.getFont().deriveFont(Font.PLAIN, 14f));
    for (JComponent component : new JComponent[] {
        cardTitle, cardPicture, cardHelp, customerPay, customerBank, customerTitle, customerHelp})
      component.setAlignmentX(Component.CENTER_ALIGNMENT);
    for (JButton button : new JButton[] {customerPay, customerBank}) {
      button.setFont(button.getFont().deriveFont(Font.PLAIN, 16f));
      button.setPreferredSize(new Dimension(320, 40));
      button.setMaximumSize(new Dimension(320, 40));
    }
    JLabel or = new JLabel("Hoặc", SwingConstants.CENTER);
    or.setAlignmentX(Component.CENTER_ALIGNMENT);
    content.add(cardTitle);
    content.add(Box.createVerticalStrut(16));
    content.add(cardPicture);
    content.add(Box.createVerticalStrut(16));
    content.add(cardHelp);
    content.add(Box.createVerticalStrut(32));
    content.add(customerPay);
    content.add(Box.createVerticalStrut(12));
    content.add(or);
    content.add(Box.createVerticalStrut(12));
    content.add(customerBank);
    content.add(Box.createVerticalStrut(20));
    content.add(customerTitle);
    content.add(customerHelp);
    JPanel centered = new JPanel(new GridBagLayout());
    centered.setOpaque(false);
    centered.add(content);
    choice.add(centered, BorderLayout.CENTER);
    customerSide.add(choice, "choice");
    customerSide.add(customerPin, "pin");

    JSplitPane customerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, invoicePanel, customerSide);
    invoicePanel.setMinimumSize(new Dimension(400, 120));
    customerSide.setMinimumSize(new Dimension(420, 120));
    customerSplit.setDividerSize(4);
    customerSplit.setEnabled(false);
    customerSplit.putClientProperty(FlatClientProperties.STYLE, "gripDotCount: 0");
    customerSplit.setResizeWeight(0);
    customerSplit.setDividerLocation(400);
    customerPage.add(customerSplit, "choice");
    customerPage.add(buildBankPage(), "bank");
    customerPages.show(customerPage, "choice");
    customerSidePages.show(customerSide, "choice");
    return customerPage;
  }

  private JComponent buildBankPage() {
    JPanel page = new JPanel(new BorderLayout(0, 16));
    page.setBackground(Color.WHITE);
    page.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
    JButton back = CanteenFrame.button("Quay lại", e -> showCustomerChoice());
    back.setPreferredSize(new Dimension(128, 32));
    JPanel backRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    backRow.setOpaque(false);
    backRow.add(back);
    page.add(backRow, BorderLayout.NORTH);
    qr.setPreferredSize(new Dimension(420, 480));
    qr.getAccessibleContext().setAccessibleName("Mã QR chuyển khoản thanh toán hóa đơn");
    JPanel qrColumn = new JPanel(new GridBagLayout());
    qrColumn.setOpaque(false);
    qrColumn.add(qr);
    JPanel details = new JPanel(new GridBagLayout());
    details.setOpaque(false);
    GridBagConstraints row = new GridBagConstraints();
    row.gridx = 0;
    row.gridy = 0;
    row.weightx = 1;
    row.fill = GridBagConstraints.HORIZONTAL;
    JLabel title = new JLabel("Thông tin thanh toán", SwingConstants.CENTER);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 32f));
    details.add(title, row);
    row.gridy++;
    row.insets = new Insets(36, 0, 0, 0);
    details.add(new JLabel("Số tiền thanh toán"), row);
    row.gridy++;
    row.insets = new Insets(4, 0, 0, 0);
    qrAmount.setFont(qrAmount.getFont().deriveFont(Font.BOLD, 24f));
    details.add(qrAmount, row);
    row.gridy++;
    row.insets = new Insets(16, 0, 0, 0);
    details.add(new JLabel("Mã tham chiếu"), row);
    row.gridy++;
    row.insets = new Insets(4, 0, 0, 0);
    qrReference.setFont(qrReference.getFont().deriveFont(Font.BOLD, 16f));
    details.add(qrReference, row);
    row.gridy++;
    row.insets = new Insets(16, 0, 0, 0);
    JPanel divider = new JPanel();
    divider.setBackground(new Color(0xD0D0D0));
    divider.setPreferredSize(new Dimension(0, 1));
    details.add(divider, row);
    row.gridy++;
    details.add(new JLabel("Mở ứng dụng ngân hàng/ví điện tử để tiến hành thanh toán"), row);
    for (String warning : new String[] {"Lưu ý:", "Không chỉnh sửa nội dung chuyển khoản!",
        "Chuyển đúng số tiền thanh toán!"}) {
      row.gridy++;
      row.insets = new Insets(4, 0, 0, 0);
      JLabel text = new JLabel(warning);
      text.setForeground(TTM_CuoiKy.ERROR_TEXT_COLOR);
      details.add(text, row);
    }
    row.gridy++;
    row.insets = new Insets(32, 0, 0, 0);
    details.add(new JLabel("Hóa đơn sẽ hết hạn trong", SwingConstants.CENTER), row);
    row.gridy++;
    row.insets = new Insets(4, 0, 0, 0);
    countdown.setFont(countdown.getFont().deriveFont(Font.PLAIN, 32f));
    countdown.setForeground(Color.BLACK);
    details.add(countdown, row);
    row.gridy++;
    row.insets = new Insets(32, 0, 0, 0);
    row.fill = GridBagConstraints.NONE;
    row.anchor = GridBagConstraints.CENTER;
    qrRetry.setPreferredSize(new Dimension(320, 40));
    details.add(qrRetry, row);
    row.gridy++;
    row.insets = new Insets(16, 0, 0, 0);
    row.fill = GridBagConstraints.HORIZONTAL;
    qrStatus.setPreferredSize(new Dimension(0, 32));
    details.add(qrStatus, row);
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
    return page;
  }

  private void configureCartTable() {
    cartTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
    cartTable.setSelectionForeground(TTM_CuoiKy.DEFAULT_TEXT_COLOR);
    DefaultTableCellRenderer centered = new CartCellRenderer();
    centered.setHorizontalAlignment(SwingConstants.CENTER);
    for (int column : new int[] {1, 3, 4}) {
      cartTable.getColumnModel().getColumn(column).setCellRenderer(centered);
    }
    DefaultTableCellRenderer imageRenderer = new CartCellRenderer() {
      @Override protected void setValue(Object value) {
        setIcon(value instanceof Icon icon ? icon : null);
        setText("");
      }
    };
    imageRenderer.setHorizontalAlignment(SwingConstants.CENTER);
    cartTable.getColumnModel().getColumn(0).setCellRenderer(imageRenderer);
    cartTable.getColumnModel().getColumn(2).setCellRenderer(new QuantityRenderer());
    cartTable.getColumnModel().getColumn(2).setCellEditor(new QuantityEditor());
    cartTable.getColumnModel().getColumn(5).setCellRenderer(new DeleteRenderer());
    cartTable.getColumnModel().getColumn(5).setCellEditor(new DeleteEditor());
    fixedCartColumn(0, 96);
    fixedCartColumn(2, 96);
    fixedCartColumn(5, 80);
    cartTable.getColumnModel().getColumn(1).setPreferredWidth(200);
    cartTable.getColumnModel().getColumn(3).setPreferredWidth(150);
    cartTable.getColumnModel().getColumn(4).setPreferredWidth(150);
    cartTable.getTableHeader().setPreferredSize(new Dimension(0, CART_HEADER_HEIGHT + 1));
    cartTable.getTableHeader().setBackground(CART_HEADER_BACKGROUND);
    cartTable.getTableHeader().setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, CART_SEPARATOR_COLOR));
    TableCellRenderer originalHeader = cartTable.getTableHeader().getDefaultRenderer();
    cartTable.getTableHeader().setDefaultRenderer((table, value, selected, focused, row, column) -> {
      Component component = originalHeader.getTableCellRendererComponent(
          table, value, selected, focused, row, column);
      if (component instanceof JLabel label) {
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setBackground(CART_HEADER_BACKGROUND);
        label.setOpaque(true);
        int rightBorder = column < table.getColumnCount() - 1 ? 1 : 0;
        label.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, rightBorder, CART_BORDER_COLOR),
            BorderFactory.createEmptyBorder(0, 4, 0, 4)));
      }
      return component;
    });
  }

  private static class CartCellRenderer extends DefaultTableCellRenderer {
    @Override public Component getTableCellRendererComponent(JTable table, Object value,
        boolean selected, boolean focused, int row, int column) {
      Component component = super.getTableCellRendererComponent(table, value, selected, focused, row, column);
      component.setBackground(Color.WHITE);
      return component;
    }
  }

  private void fixedCartColumn(int index, int width) {
    TableColumn column = cartTable.getColumnModel().getColumn(index);
    column.setMinWidth(width);
    column.setMaxWidth(width);
    column.setPreferredWidth(width);
  }

  private static JButton squareButton(String text, Icon icon, String description) {
    JButton button = new JButton(text, icon);
    Dimension size = new Dimension(32, 32);
    button.setPreferredSize(size);
    button.setMinimumSize(size);
    button.setMaximumSize(size);
    button.setMargin(new Insets(0, 0, 0, 0));
    button.putClientProperty(FlatClientProperties.STYLE, "arc: 4; minimumWidth: 0");
    button.setToolTipText(description);
    button.getAccessibleContext().setAccessibleName(description);
    return button;
  }

  private static JButton quantityButton(Icon icon, String description) {
    JButton button = squareButton("", icon, description);
    button.setBackground(Color.WHITE);
    button.setForeground(TTM_CuoiKy.DEFAULT_TEXT_COLOR);
    button.putClientProperty(FlatClientProperties.STYLE,
        "arc: 4; minimumWidth: 0; disabledBackground: #e5e5e5; hoverBackground: #ffffff; "
            + "pressedBackground: #ffffff; focusedBackground: #ffffff; focusWidth: 0");
    return button;
  }

  private static JButton deleteCartButton(Icon icon) {
    JButton button = squareButton("", icon, "Xóa món khỏi hóa đơn");
    button.setBorderPainted(false);
    button.setBackground(TTM_CuoiKy.ERROR_TEXT_COLOR);
    button.setDisabledIcon(icon);
    button.putClientProperty(FlatClientProperties.STYLE,
        "arc: 4; minimumWidth: 0; focusWidth: 0; disabledBackground: #c62828");
    return button;
  }

  private static JPanel centeredControls(int gap, JComponent... controls) {
    JPanel panel = new JPanel(new GridBagLayout());
    JPanel content = new JPanel(new FlowLayout(FlowLayout.CENTER, gap, 0));
    content.setOpaque(false);
    for (JComponent control : controls) content.add(control);
    panel.add(content);
    return panel;
  }

  private static JLabel quantityLabel() {
    JLabel label = new JLabel("", SwingConstants.CENTER);
    label.setPreferredSize(new Dimension(32, 32));
    return label;
  }

  private Long cartRowId(JTable table, int row) {
    Object value = table.getModel().getValueAt(table.convertRowIndexToModel(row), 5);
    return value instanceof Long id ? id : null;
  }

  private final class QuantityRenderer implements TableCellRenderer {
    private final JButton minus = quantityButton(minusCartIcon, "Giảm số lượng");
    private final JLabel quantity = quantityLabel();
    private final JButton plus = quantityButton(plusCartIcon, "Tăng số lượng");
    private final JPanel panel = centeredControls(0, minus, quantity, plus);

    @Override public Component getTableCellRendererComponent(JTable table, Object value,
        boolean selected, boolean focused, int row, int column) {
      int count = value instanceof Number number ? number.intValue() : 0;
      quantity.setText(Integer.toString(count));
      boolean editable = table.isEnabled() && canEditCart();
      minus.setEnabled(editable && count > 1);
      plus.setEnabled(editable && count < 99);
      panel.setBackground(Color.WHITE);
      return panel;
    }
  }

  private final class QuantityEditor extends AbstractCellEditor implements TableCellEditor {
    private final JButton minus = quantityButton(minusCartIcon, "Giảm số lượng");
    private final JLabel quantity = quantityLabel();
    private final JButton plus = quantityButton(plusCartIcon, "Tăng số lượng");
    private final JPanel panel = centeredControls(0, minus, quantity, plus);
    private Long selectedId;
    private int count;

    QuantityEditor() {
      minus.addActionListener(e -> change(-1));
      plus.addActionListener(e -> change(1));
    }

    private void change(int delta) {
      Long id = selectedId;
      fireEditingStopped();
      changeQuantity(id, delta);
    }

    @Override public Object getCellEditorValue() { return count; }

    @Override public Component getTableCellEditorComponent(JTable table, Object value,
        boolean selected, int row, int column) {
      selectedId = cartRowId(table, row);
      count = value instanceof Number number ? number.intValue() : 0;
      quantity.setText(Integer.toString(count));
      minus.setEnabled(count > 1 && canEditCart());
      plus.setEnabled(count < 99 && canEditCart());
      panel.setBackground(Color.WHITE);
      return panel;
    }
  }

  private final class DeleteRenderer implements TableCellRenderer {
    private final JButton delete = deleteCartButton(deleteCartIcon);
    private final JPanel panel = centeredControls(4, delete);

    @Override public Component getTableCellRendererComponent(JTable table, Object value,
        boolean selected, boolean focused, int row, int column) {
      delete.setEnabled(table.isEnabled() && canEditCart());
      panel.setBackground(Color.WHITE);
      return panel;
    }
  }

  private final class DeleteEditor extends AbstractCellEditor implements TableCellEditor {
    private final JButton delete = deleteCartButton(deleteCartIcon);
    private final JPanel panel = centeredControls(4, delete);
    private Long selectedId;

    DeleteEditor() {
      delete.addActionListener(e -> {
        Long id = selectedId;
        fireEditingStopped();
        removeDish(id);
      });
    }

    @Override public Object getCellEditorValue() { return selectedId; }

    @Override public Component getTableCellEditorComponent(JTable table, Object value,
        boolean selected, int row, int column) {
      selectedId = cartRowId(table, row);
      delete.setEnabled(canEditCart());
      panel.setBackground(Color.WHITE);
      return panel;
    }
  }

  private static JPanel spaceBetweenPanel() {
    return new JPanel(null) {
      @Override
      public Dimension getPreferredSize() {
        Insets insets = getInsets();
        int width = 0, height = 0;
        for (Component child : getComponents()) {
          Dimension size = child.getPreferredSize();
          width = Math.max(width, size.width);
          height += size.height;
        }
        return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
      }

      @Override
      public void doLayout() {
        Component[] rows = getComponents();
        if (rows.length == 0) return;
        Insets insets = getInsets();
        int used = 0;
        for (Component row : rows) used += row.getPreferredSize().height;
        int spare = Math.max(0, getHeight() - insets.top - insets.bottom - used);
        int width = Math.max(0, getWidth() - insets.left - insets.right);
        int precedingHeight = 0;
        for (int i = 0; i < rows.length; i++) {
          int height = rows[i].getPreferredSize().height;
          int offset = rows.length == 1 ? 0 : (int) Math.round((double) i * spare / (rows.length - 1));
          rows[i].setBounds(insets.left, insets.top + precedingHeight + offset, width, height);
          precedingHeight += height;
        }
      }
    };
  }

  void showNotice(String text, boolean error) {
    Toast.showMessage(this, text, error);
  }

  void loadMenu() {
    if (frame.isBusy()) return;
    long selected = selectedCategory();
    MenuCatalogDialog source = catalogDialog != null && catalogDialog.isShowing() ? catalogDialog : null;
    cancelSales();
    if (menuSort.getSelectedIndex() == 1) renderMenu();
    if (source != null) source.invalidateSales();
    frame.run(
        source,
        "Đang tải thực đơn…",
        () ->
            new MenuData(
                frame.api.get("/menu", Api.MenuItem[].class),
                frame.api.get("/menu/categories", Api.MenuCategory[].class)),
        data -> {
          boolean refreshSearch = searchPending || !search.getText().isEmpty();
          cancelSearch();
          searchResults = null;
          searchError = null;
          items = data.items();
          if (invoice != null && (open() || pending()) && invoiceMenuSnapshot.isEmpty()) {
            // Restored invoice lines do not contain a menu ID. Use an image only when the
            // current menu has one unambiguous match for the saved name and price.
            invoiceMenuSnapshot = invoice.items().stream().map(line -> {
              var matches = Arrays.stream(items)
                  .filter(item -> item.name().equals(line.name()) && item.price() == line.unitPrice())
                  .toList();
              return matches.size() == 1 ? matches.getFirst() : null;
            }).toList();
            customerRenderedInvoiceId = -1;
            renderInvoice();
          }
          totalActiveItems = items.length;
          categoryItems = data.categories();
          updatingCategories = true;
          category.removeAllItems();
          category.addItem(new Api.MenuCategory(0, "Tất cả", items.length, 0));
          for (var c : categoryItems) {
            category.addItem(c);
            if (c.id() == selected) category.setSelectedItem(c);
          }
          updatingCategories = false;
          if (!open() && !pending() && creationRequest == null) {
            boolean changed = false;
            for (var entry : new ArrayList<>(cart.entrySet())) {
              var found = Arrays.stream(items).filter(m -> m.id() == entry.getKey()).findFirst();
              if (found.isEmpty()) {
                cart.remove(entry.getKey());
                changed = true;
              } else if (!Objects.equals(entry.getValue().revision(), found.get().revision())) {
                cart.put(
                    entry.getKey(),
                    new Api.OrderItem(
                        entry.getKey(), entry.getValue().quantity(), found.get().revision()));
                changed = true;
              }
            }
            renderCart();
            if (changed)
              frame.info(
                  "Thực đơn đã thay đổi. Món đã xóa được bỏ khỏi giỏ; hãy kiểm tra lại giá và tổng"
                      + " tiền.");
          }
          if (refreshSearch) queueSearch();
          else renderMenu();
          if (source != null && source == catalogDialog && source.isShowing()) {
            source.setCatalog(items, categoryItems, null);
          }
          refreshSales();
        },
        failure -> frame.error(source, failure));
  }

  private long selectedCategory() {
    var c = (Api.MenuCategory) category.getSelectedItem();
    return c == null ? 0 : c.id();
  }

  private void queueSearch() {
    cancelSearch();
    searchPending = true;
    searchResults = null;
    searchError = null;
    renderMenu();
    searchDebounce.restart();
  }

  private void cancelSearch() {
    searchGeneration++;
    searchDebounce.stop();
    if (searchWorker != null) searchWorker.cancel(true);
    searchWorker = null;
    searchPending = false;
  }

  private void searchBackend() {
    String query = search.getText();
    long generation = searchGeneration;
    SwingWorker<Api.MenuSearchResult, Void> worker = new SwingWorker<>() {
      protected Api.MenuSearchResult doInBackground() {
        return frame.api.get("/menu/search?query=" + ApiClient.query(query),
            Api.MenuSearchResult.class);
      }

      protected void done() {
        if (generation != searchGeneration || !isDisplayable()) return;
        searchWorker = null;
        searchPending = false;
        try {
          Api.MenuSearchResult result = get();
          searchResults = List.copyOf(result.items());
          totalActiveItems = result.total();
          searchError = null;
        } catch (Exception failure) {
          searchResults = null;
          searchError = "Không tìm được món ăn. Hãy thử nhập lại.";
        }
        if (searchError == null && menuSort.getSelectedIndex() == 1 && salesWorker == null && menuSales != null
            && missingSales(matchingItems())) refreshSales();
        else renderMenu();
      }
    };
    searchWorker = worker;
    worker.execute();
  }

  private void cancelSales() {
    salesGeneration++;
    if (salesWorker != null) salesWorker.cancel(true);
    salesWorker = null;
    menuSales = null;
  }

  private void refreshSales() {
    cancelSales();
    long generation = salesGeneration;
    SwingWorker<Api.MenuSales[], Void> worker = new SwingWorker<>() {
      protected Api.MenuSales[] doInBackground() {
        return frame.api.get("/menu/sales", Api.MenuSales[].class);
      }

      protected void done() {
        if (generation != salesGeneration || !isDisplayable()) return;
        salesWorker = null;
        try {
          Api.MenuSales[] values = get();
          Map<Long, Long> counts = new HashMap<>();
          for (Api.MenuSales value : values) counts.put(value.itemId(), value.soldQuantity());
          menuSales = counts;
          if (catalogDialog != null && catalogDialog.isShowing())
            catalogDialog.updateSales(values);
        } catch (Exception failure) {
          menuSales = null;
        }
        renderMenu();
      }
    };
    salesWorker = worker;
    if (menuSort.getSelectedIndex() == 1) renderMenu();
    worker.execute();
  }

  private void showCatalog() {
    if (catalogDialog != null && catalogDialog.isShowing()) {
      catalogDialog.toFront();
      return;
    }
    catalogDialog = new MenuCatalogDialog(frame, images, menuDialogs, this::loadMenu);
    catalogDialog.setCatalog(items, categoryItems, null);
    catalogDialog.setVisible(true);
    loadMenu();
  }

  private void retryQr() {
    if (invoice == null || !pinAllowed() || frame.isBusy()) return;
    long id = invoice.id();
    frame.run(
        "Đang tải mã chuyển khoản…",
        () -> frame.api.post("/invoices/" + id + "/qr", null, Api.Invoice.class),
        this::accept);
  }

  private void showBankQr() {
    if (!customerBank.isEnabled()) return;
    showingQrPage = true;
    customerPages.show(customerPage, "bank");
  }

  private void showCustomerChoice() {
    showingQrPage = false;
    customerPages.show(customerPage, "choice");
  }

  private void recent() {
    frame.run(
        " ",
        () -> {
          Api.InvoicePage page = frame.api.get("/invoices/pages?page=0&q=", Api.InvoicePage.class);
          if (!InvoiceHistoryView.validPage(page, 0))
            throw new ApiClient.ApiError(502, "INVALID_RESPONSE", "Dữ liệu hóa đơn không hợp lệ.");
          return page;
        },
        page -> {
          InvoiceHistoryView view = new InvoiceHistoryView(frame, page);
          JDialog dialog = new JDialog(frame, "Hóa đơn gần đây", true);
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

  void stationEnded() {
    ended = true;
    settling = false;
    viewSequence++;
    closePin(true);
    qr.setIcon(null);
    qr.setText("");
    qrImage = "";
    if (showingQrPage) showCustomerChoice();
    TTM_CuoiKy.statusText(customerTitle, "Phiên quầy đã kết thúc", true);
    setCustomerHelp("Mở lại ứng dụng để tiếp tục.", true);
    buttons();
  }

  void refreshInvoice() {
    pollInvoice();
  }

  void restoreCurrentInvoice(Api.Invoice[] invoices) {
    if (invoices == null || invoices.length == 0) return;
    accept(invoices[0]);
    tabs.setSelectedIndex(1);
  }

  void onCard(Api.ReaderStatus s) {
    if (!authConnection.equals(s.connectionId())) {
      authenticating = false;
    }
    boolean invalid = s.present() && (!s.initialized() || s.lifecycleStatus().equals("BLOCKED"));
    TTM_CuoiKy.statusText(readerInfo,
        CanteenFrame.html(
            !s.present()
                ? "Kết nối thẻ trước hoặc sau khi tạo hóa đơn."
                : s.lifecycleStatus().equals("BLOCKED")
                    ? "Thẻ hiện đang bị tạm khoá! Vui lòng liên hệ với admin"
                    : !s.initialized()
                        ? "Thẻ chưa đăng ký."
                        : "Đã kết nối " + s.cardId() + " · Còn " + s.tries() + " lần thử PIN"),
        invalid);
    cardTitle.setText(s.present() ? "Đã phát hiện thẻ" : "Không phát hiện thẻ");
    cardPicture.setIcon(s.present() ? cardPresentIcon : cardMissingIcon);
    cardHelp.setText(!s.present() ? "Vui lòng kết nối thẻ để tiếp tục."
        : s.lifecycleStatus().equals("BLOCKED") ? "Thẻ đang tạm khóa. Vui lòng liên hệ admin."
        : !s.initialized() ? "Thẻ chưa được khởi tạo. Vui lòng liên hệ admin."
        : "Thẻ đã phát hiện, hãy tiếp tục thanh toán.");
    String cardId = s.present() ? Objects.toString(s.cardId(), "").strip() : "";
    connectedCardId.setText(cardId.isBlank() ? "------------" : cardId);
    connectionIndicator.setForeground(s.present() ? new Color(0x208A46) : new Color(0xA0A0A0));
    String connectionText = s.present() ? "Đã kết nối thẻ " + connectedCardId.getText() : "Chưa kết nối thẻ";
    connectedCard.setToolTipText(connectionText);
    connectedCard.getAccessibleContext().setAccessibleName(connectionText);
    connectionIndicator.setToolTipText(connectionText);
    connectionIndicator.repaint();
    if (!s.present() || s.lifecycleStatus().equals("BLOCKED")) {
      closePin();
    } else if (pinOpen && !authConnection.equals(s.connectionId())) {
      closePin();
    }
    buttons();
    TTM_CuoiKy.statusText(customerReader, readerInfo.getText(), invalid);
  }

  void cardValidated(Api.ReaderStatus status) {
    onCard(status);
  }

  private List<Api.MenuItem> matchingItems() {
    String query = Profile.searchable(search.getText());
    long selected = selectedCategory();
    List<Api.MenuItem> candidates = searchResults == null ? Arrays.asList(items) : searchResults;
    return candidates.stream().filter(Api.MenuItem::available)
        .filter(m -> (selected == 0 || m.categoryId() == selected)
            && Profile.searchable(m.name()).contains(query)).toList();
  }

  private boolean missingSales(List<Api.MenuItem> matching) {
    return menuSales != null && matching.stream().anyMatch(item -> !menuSales.containsKey(item.id()));
  }

  private void renderMenu() {
    menu.removeAll();
    int displayed = 0;
    List<Api.MenuItem> matching = matchingItems();
    if (searchPending || searchError != null) {
      if (!searchPending) menu.add(new JLabel(searchError));
    } else if (menuSort.getSelectedIndex() == 1 && (menuSales == null || missingSales(matching))) {
      menu.add(new JLabel(salesWorker != null ? "Đang tải số lượng bán…"
          : "Chưa tải được số lượng bán. Hãy chọn lại Bán chạy."));
    } else {
      Comparator<Api.MenuItem> newest = Comparator.comparingLong(Api.MenuItem::id).reversed();
      Comparator<Api.MenuItem> order = switch (menuSort.getSelectedIndex()) {
        case 1 -> Comparator.<Api.MenuItem>comparingLong(m -> menuSales.getOrDefault(m.id(), 0L))
            .reversed().thenComparing(newest);
        case 2 -> Comparator.comparingLong(Api.MenuItem::price).reversed().thenComparing(newest);
        default -> newest;
      };
      for (Api.MenuItem m : matching.stream().sorted(order).toList()) {
        String name = CanteenFrame.html(m.name());
        String text = name.substring(0, name.length() - "</html>".length())
            + "<br><b>" + CanteenFrame.money(m.price()) + "</b></html>";
        JButton button = new JButton(text) {
          @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Icon icon = getModel().isRollover() ? addDishIcon : addDishOutlineIcon;
            int x = getWidth() - DISH_ADD_ICON_RIGHT - icon.getIconWidth();
            int y = (getHeight() - icon.getIconHeight()) / 2;
            icon.paintIcon(this, graphics, x, y);
          }
        };
        button.setPreferredSize(new Dimension(180, DISH_ROW_HEIGHT));
        button.setRolloverEnabled(true);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setHorizontalTextPosition(SwingConstants.RIGHT);
        button.setVerticalTextPosition(SwingConstants.CENTER);
        button.setIconTextGap(8);
        button.setMargin(new Insets(0, 0, 0, 0));
        Insets border = button.getInsets();
        int rightInset = DISH_ADD_ICON_SIZE + DISH_ADD_ICON_RIGHT + DISH_ADD_ICON_TEXT_GAP;
        button.setBorder(BorderFactory.createCompoundBorder(button.getBorder(),
            BorderFactory.createEmptyBorder(0, Math.max(0, 8 - border.left),
                0, Math.max(0, rightInset - border.right))));
        button.setIcon(images.placeholder(DISH_IMAGE_SIZE));
        button.setToolTipText(m.name());
        button.getAccessibleContext().setAccessibleName(m.name() + " · " + CanteenFrame.money(m.price()));
        button.addActionListener(e -> add(m));
        images.load(frame.api, m, DISH_IMAGE_SIZE, icon -> {
          if (icon != null) button.setIcon(icon);
          else button.setToolTipText(m.name() + " · Chưa tải được ảnh, vẫn có thể chọn món.");
        });
        menu.add(button);
        displayed++;
      }
    }
    boolean empty = !searchPending && menu.getComponentCount() == 0;
    if (empty) {
      String message = items.length == 0 && selectedCategory() == 0 && search.getText().isBlank()
          ? "Chưa có món. Hãy thêm danh mục và món."
          : "Không tìm thấy món ăn phù hợp.";
      menu.add(new JLabel(message, SwingConstants.CENTER));
    }
    String position = empty ? BorderLayout.CENTER : BorderLayout.NORTH;
    if (!position.equals(((BorderLayout) menuWrap.getLayout()).getConstraints(menu))) {
      menuWrap.remove(menu);
      menuWrap.add(menu, position);
      menuWrap.revalidate();
    }
    menuResults.setText("Đã hiển thị " + displayed + " trên tổng số " + totalActiveItems + " món ăn.");
    menu.revalidate();
    menu.repaint();
  }

  private boolean editable() {
    if (open() || pending() || creationRequest != null) {
      frame.info(
          "Hóa đơn đang chờ xử lý. Hãy hoàn tất hoặc hủy trước khi thay đổi món.");
      return false;
    }
    return true;
  }

  private boolean canEditCart() {
    return !ended && !settling && !open() && !pending() && creationRequest == null && !frame.isBusy();
  }

  private void add(Api.MenuItem m) {
    if (!editable()) {
      return;
    }
    Api.MenuItem current = Arrays.stream(items).filter(item -> item.id() == m.id()).findFirst().orElse(null);
    boolean changedSinceSearch = current != null && current.revision() > m.revision();
    Api.MenuItem selected = changedSinceSearch ? current : m;
    Api.OrderItem old = cart.get(m.id());
    int count = old == null ? 1 : old.quantity() + 1;
    if (count > 99) {
      return;
    }
    boolean changedInCart = old != null && !Objects.equals(old.revision(), selected.revision());
    rememberMenuItem(selected);
    cart.put(selected.id(), new Api.OrderItem(selected.id(), count, selected.revision()));
    renderCart();
    if (changedSinceSearch || changedInCart)
      frame.message("Thông tin món đã thay đổi. Vui lòng kiểm tra lại giá và tổng tiền.");
  }

  private void rememberMenuItem(Api.MenuItem selected) {
    for (int i = 0; i < items.length; i++) {
      if (items[i].id() == selected.id()) {
        if (!items[i].equals(selected)) {
          items = items.clone();
          items[i] = selected;
        }
        return;
      }
    }
    items = Arrays.copyOf(items, items.length + 1);
    items[items.length - 1] = selected;
  }

  private Api.MenuItem item(long id) {
    return Arrays.stream(items).filter(m -> m.id() == id).findFirst().orElseThrow();
  }

  private void renderCart() {
    beginCartRows();
    long value = 0;
    for (Api.OrderItem order : cart.values()) {
      Api.MenuItem m = item(order.itemId());
      long amount = m.price() * order.quantity();
      value += amount;
      appendCartRow(m, m.name(), order.quantity(), m.price(), amount, order.itemId());
    }
    updateCartRowHeights();
    total.setText(CanteenFrame.money(value));
    buttons();
  }

  private void beginCartRows() {
    if (cartTable.isEditing()) cartTable.getCellEditor().cancelCellEditing();
    cartRenderSequence++;
    cartModel.setRowCount(0);
  }

  private void updateCartRowHeights() {
    for (int row = 0; row < cartTable.getRowCount(); row++) {
      int height = CART_ROW_HEIGHT + (row + 1 < cartTable.getRowCount() ? 1 : 0);
      if (cartTable.getRowHeight(row) != height) cartTable.setRowHeight(row, height);
    }
  }

  private void appendCartRow(Api.MenuItem item, String name, int quantity, long price, long amount, Long id) {
    int row = cartModel.getRowCount();
    long sequence = cartRenderSequence;
    cartModel.addRow(new Object[] {images.placeholder(80), name, quantity,
        CanteenFrame.money(price), CanteenFrame.money(amount), id});
    if (item != null && item.hasImage()) {
      images.load(frame.api, item, 80, icon -> {
        if (icon != null && sequence == cartRenderSequence && row < cartModel.getRowCount())
          cartModel.setValueAt(icon, row, 0);
      });
    }
  }

  private void renderCustomerItems(Api.Invoice current) {
    long currentId = current == null ? -1 : current.id();
    boolean empty = current == null || Set.of("PAID", "CANCELLED").contains(current.status());
    List<Api.InvoiceLine> lines = empty ? List.of() : current.items();
    if (customerRenderedInvoiceId == currentId && customerRenderedLines.equals(lines)
        && customerRenderedEmpty == empty) return;
    customerRenderedInvoiceId = currentId;
    customerRenderedLines = List.copyOf(lines);
    customerRenderedEmpty = empty;
    customerEmpty.setVisible(empty);
    customerItems.setVisible(!empty);
    long sequence = ++customerRenderSequence;
    customerItems.removeAll();
    if (current != null) {
      for (int index = 0; index < lines.size(); index++) {
        Api.InvoiceLine line = lines.get(index);
        Api.MenuItem photo = index < invoiceMenuSnapshot.size() ? invoiceMenuSnapshot.get(index) : null;
        if (photo != null && (!photo.name().equals(line.name()) || photo.price() != line.unitPrice()))
          photo = null;
        String escaped = CanteenFrame.html(line.name());
        String caption = escaped.substring(0, escaped.length() - "</html>".length())
            + "<br><b>" + CanteenFrame.money(line.unitPrice()) + "</b></html>";
        JLabel dish = new JLabel(caption, images.placeholder(CUSTOMER_DISH_IMAGE_SIZE), SwingConstants.LEFT);
        dish.setIconTextGap(8);
        JLabel quantity = new JLabel(Integer.toString(line.quantity()), SwingConstants.CENTER);
        quantity.setFont(quantity.getFont().deriveFont(Font.BOLD, 24f));
        quantity.setPreferredSize(new Dimension(DISH_ADD_ICON_SIZE, DISH_ADD_ICON_SIZE));
        quantity.getAccessibleContext().setAccessibleName("Số lượng " + line.quantity());
        JPanel itemRow = new JPanel(new BorderLayout(8, 0));
        itemRow.setBackground(Color.WHITE);
        itemRow.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(CART_BORDER_COLOR),
            BorderFactory.createEmptyBorder(7, 7, 7, DISH_ADD_ICON_RIGHT)));
        itemRow.setPreferredSize(new Dimension(0, CUSTOMER_DISH_ROW_HEIGHT));
        itemRow.add(dish, BorderLayout.CENTER);
        itemRow.add(quantity, BorderLayout.EAST);
        customerItems.add(itemRow);
        if (photo != null && photo.hasImage()) {
          images.load(frame.api, photo, CUSTOMER_DISH_IMAGE_SIZE, icon -> {
            if (icon != null && sequence == customerRenderSequence) dish.setIcon(icon);
          });
        }
      }
    }
    customerItems.revalidate();
    customerItems.repaint();
  }

  private void changeQuantity(Long id, int delta) {
    if (id == null || !canEditCart()) return;
    Api.OrderItem old = cart.get(id);
    if (old == null) return;
    int count = old.quantity() + delta;
    if (count < 1 || count > 99) return;
    cart.put(id, new Api.OrderItem(id, count, old.revision()));
    renderCart();
  }

  private void removeDish(Long id) {
    if (id == null || !canEditCart()) return;
    if (cart.remove(id) != null) renderCart();
  }

  private void create() {
    if (ended || open() || pending() || frame.isBusy()) return;
    if (cart.isEmpty()) {
      frame.info("Hãy chọn ít nhất một món trước khi tạo hóa đơn.");
      return;
    }
    if (creationRequest == null) {
      var requested = new ArrayList<>(cart.values());
      creationRequest = new Api.CreateInvoice(requested);
      invoiceMenuSnapshot = requested.stream().map(order -> item(order.itemId())).toList();
    }
    buttons();
    frame.run(
        "Đang tạo hóa đơn…",
        () -> frame.api.post("/invoices", creationRequest, Api.Invoice.class),
        i -> {
          creationRequest = null;
          accept(i);
          tabs.setSelectedIndex(1);
        },
        failure -> {
          if (failure instanceof ApiClient.ApiError a && a.status >= 400 && a.status < 500) {
            creationRequest = null;
            invoiceMenuSnapshot = List.of();
          }
          buttons();
          if (creationRequest != null) {
            frame.errorMessage(
                "Chưa rõ kết quả tạo hóa đơn. Nhấn Tạo hóa đơn để kiểm tra lại cùng yêu cầu; không"
                    + " lập đơn khác.");
          } else {
            frame.error(failure);
          }
          if (failure instanceof ApiClient.ApiError a && a.code.equals("MENU_CHANGED")) loadMenu();
        });
  }

  private boolean open() {
    return !ended && invoice != null && invoice.status().equals("OPEN");
  }

  private boolean pending() {
    return invoice != null
        && Set.of("PAYMENT_PENDING", "CANCEL_PENDING").contains(invoice.status());
  }

  private boolean pinAllowed() {
    return !ended
        && invoice != null
        && Set.of("OPEN", "PAYMENT_PENDING").contains(invoice.status());
  }

  private String state(Api.Invoice i) {
    if (i.status().equals("CANCEL_PENDING")) {
      return "ĐANG HỦY · CHỜ ĐỐI SOÁT/HOÀN BÙ";
    }
    if (i.status().equals("PAYMENT_PENDING")) {
      return "ĐANG XÁC THỰC/THANH TOÁN";
    }
    return i.status().equals("PAID")
        ? "ĐÃ THANH TOÁN"
        : i.status().equals("CANCELLED")
            ? (i.cancelReason().equals("QR_EXPIRED") ? "ĐÃ HỦY · QR HẾT HẠN" : "ĐÃ HỦY")
            : "CHỜ THANH TOÁN";
  }

  private void accept(Api.Invoice i) {
    if (ended) {
      return;
    }
    if (invoice != null && invoice.id() == i.id() && i.revision() < invoice.revision()) {
      return;
    }
    boolean becamePaid = "PAID".equals(i.status())
        && (invoice == null || invoice.id() != i.id() || !"PAID".equals(invoice.status()));
    boolean terminal = Set.of("PAID", "CANCELLED").contains(i.status());
    boolean becameTerminal = terminal && (invoice == null || invoice.id() != i.id()
        || !Set.of("PAID", "CANCELLED").contains(invoice.status()));
    if (pinOpen && pinInvoiceId != i.id()) closePin(true);
    viewSequence++;
    invoice = i;
    serverAtReply = i.serverTime();
    replyNanos = System.nanoTime();
    renderInvoice();
    if (becameTerminal) {
      cart.clear();
      invoiceMenuSnapshot = List.of();
      TTM_CuoiKy.statusText(invoiceInfo, "Chưa tạo hóa đơn", false);
      renderCart();
      if ("PAID".equals(i.status())) {
        Toast.showSuccess(this, "Thanh toán hoá đơn thành công!");
      } else if ("QR_EXPIRED".equals(i.cancelReason())) {
        frame.errorMessage("Hoá đơn đã hết hạn.");
      } else if ("USER_CANCELLED".equals(i.cancelReason())) {
        frame.message("Hoá đơn đã bị huỷ.");
      } else if ("BANK_AMOUNT_MISMATCH".equals(i.cancelReason())) {
        frame.errorMessage("Hóa đơn đã hủy vì số tiền chuyển khoản không khớp. Liên hệ quản trị viên để đối soát.");
      } else {
        frame.errorMessage("Hóa đơn đã hủy. Nhân viên cần tạo hóa đơn mới.");
      }
    }
    if (becamePaid) {
      if (catalogDialog != null && catalogDialog.isShowing()) loadMenu();
      refreshSales();
    }
    if (terminal && Objects.equals(cancellationRequestedInvoiceId, i.id())) {
      cancellationRequestedInvoiceId = null;
    }
  }

  private void renderInvoice() {
    boolean failed = failedInvoice(invoice);
    boolean active = open() || pending();
    boolean finished = Set.of("PAID", "CANCELLED").contains(invoice.status());
    if (active) {
      TTM_CuoiKy.statusText(invoiceInfo,
          CanteenFrame.html(invoice.number() + "\n" + state(invoice)), failed);
      beginCartRows();
    }
    customerModel.setRowCount(0);
    for (int index = 0; index < invoice.items().size(); index++) {
      var line = invoice.items().get(index);
      Object[] row = {line.name(), line.quantity(), CanteenFrame.money(line.unitPrice()),
          CanteenFrame.money(line.total())};
      if (!finished) customerModel.addRow(row);
      if (active) {
        Api.MenuItem photo = index < invoiceMenuSnapshot.size() ? invoiceMenuSnapshot.get(index) : null;
        if (photo != null && (!photo.name().equals(line.name()) || photo.price() != line.unitPrice()))
          photo = null;
        appendCartRow(photo, line.name(), line.quantity(), line.unitPrice(), line.total(), null);
      }
    }
    if (active) updateCartRowHeights();
    if (active) total.setText(CanteenFrame.money(invoice.total()));
    renderCustomerItems(invoice);
    customerTotal.setText(finished ? "0 ₫" : CanteenFrame.money(invoice.total()));
    qrAmount.setText(CanteenFrame.money(invoice.total()));
    qrReference.setText(invoice.paymentReference().isBlank() ? "Chưa có mã tham chiếu" : invoice.paymentReference());
    if (pinAllowed() && !invoice.paymentPhase().equals("CARD_PROCESSING")) {
      long left = remaining();
      countdown.setText(
          left > 0
              ? String.format("%02d:%02d", (left + 999) / 60000, ((left + 999) / 1000) % 60)
              : "Đang xác định trạng thái");
      TTM_CuoiKy.statusText(customerTitle, " ", false);
      setCustomerHelp(" ", false);
      if (left == 0) {
        qr.setIcon(null);
        qr.setText("");
        qrImage = "";
        setCustomerHelp("Đang xác định kết quả với máy chủ; không thanh toán lại.", false);
      } else if (invoice.qrDataUrl().isEmpty()) {
        qr.setIcon(null);
        TTM_CuoiKy.statusText(qr, "Chưa tải được QR. Nhấn Tải lại QR hoặc thanh toán bằng thẻ.", true);
      } else if (!qrImage.equals(invoice.qrDataUrl())) {
        qrImage = invoice.qrDataUrl();
        try {
          qr.setIcon(
              new ImageIcon(
                  Base64.getDecoder().decode(qrImage.substring(qrImage.indexOf(',') + 1))));
          qr.setText("");
        } catch (RuntimeException e) {
          qr.setIcon(null);
          TTM_CuoiKy.statusText(qr, "Chưa hiển thị được QR. Hãy tải lại hóa đơn.", true);
        }
      }
    } else if (pending()) {
      if (invoice.status().equals("CANCEL_PENDING")) {
        closePin(true);
      }
      qr.setIcon(null);
      qr.setText("");
      qrImage = "";
      countdown.setText(" ");
      TTM_CuoiKy.statusText(customerTitle, state(invoice), false);
      setCustomerHelp(
          invoice.status().equals("CANCEL_PENDING")
              ? "Giữ hoặc kết nối lại đúng thẻ để hoàn tất hủy; không thanh toán lại."
              : "Đang xử lý thanh toán bằng thẻ. Chờ kết quả; không chuyển khoản thêm.", false);
    } else {
      closePin(true);
      qr.setText("");
      qr.setIcon(null);
      qrImage = "";
      if (showingQrPage) showCustomerChoice();
      TTM_CuoiKy.statusText(customerTitle, " ", false);
      setCustomerHelp(" ", false);
      countdown.setText(" ");
    }
    buttons();
  }

  private void setCustomerHelp(String message, boolean failed) {
    TTM_CuoiKy.statusText(customerHelp, message, failed);
    TTM_CuoiKy.statusText(qrStatus, message, failed);
  }

  private static boolean failedInvoice(Api.Invoice i) {
    return i.status().equals("CANCELLED") && !i.cancelReason().equals("USER_CANCELLED");
  }

  private long remaining() {
    return invoice == null
        ? 0
        : Math.max(
            0, invoice.expiresAt() - serverAtReply - (System.nanoTime() - replyNanos) / 1_000_000);
  }

  private void tick() {
    if (settling) return;
    if (pending()) {
      buttons();
      pollInvoice();
      return;
    }
    if (!open()) {
      return;
    }
    long left = remaining();
    if (left == 0) {
      closePin();
      qr.setIcon(null);
      qr.setText("");
      qrImage = "";
      setCustomerHelp("Hết thời gian · Đang kiểm tra kết quả với máy chủ…", false);
    }
    countdown.setText(
        left > 0
            ? String.format("%02d:%02d", (left + 999) / 60000, ((left + 999) / 1000) % 60)
            : "Đang xác định trạng thái");
    buttons();
    pollInvoice();
  }

  private void pollInvoice() {
    if (invoice == null || settling || pollingInvoice) {
      return;
    }
    pollingInvoice = true;
    long id = invoice.id(), sequence = viewSequence, epoch = frame.sessionEpoch();
    new SwingWorker<Api.Invoice, Void>() {
      protected Api.Invoice doInBackground() {
        return frame.api.get("/invoices/" + id, Api.Invoice.class);
      }

      protected void done() {
        pollingInvoice = false;
        if (ended
            || settling
            || invoice == null
            || invoice.id() != id
            || sequence != viewSequence
            || epoch != frame.sessionEpoch()) {
          return;
        }
        try {
          accept(get());
        } catch (Exception e) {
          setCustomerHelp("Chưa kết nối được máy chủ. Chờ xác định kết quả; không thanh toán lại.", true);
        }
      }
    }.execute();
  }

  private void beginAuthentication() {
    if (!customerPay.isEnabled()
        || !pinAllowed()
        || remaining() == 0
        || authenticating
        || frame.isBusy()
        || !frame.status().present()) {
      return;
    }
    authenticating = true;
    buttons();
    long id = invoice.id();
    String connection = frame.status().connectionId();
    authConnection = connection;
    TTM_CuoiKy.statusText(customerTitle, "Đang xác thực thẻ…", false);
    frame.runWithoutOverlay(
        () -> frame.api.post("/invoices/" + id + "/prepare", null, Api.ReaderStatus.class),
        s -> {
          authenticating = false;
          TTM_CuoiKy.statusText(customerTitle, " ", false);
          if (!pinAllowed()
              || invoice.id() != id
              || remaining() == 0
              || !connection.equals(frame.status().connectionId())) {
            buttons();
            return;
          }
          showPin(id, connection);
        },
        e -> {
          authenticating = false;
          TTM_CuoiKy.statusText(customerTitle, " ", false);
          buttons();
          frame.error(e);
          frame.poll();
          pollInvoice();
        });
  }

  private void showPin(long id, String connection) {
    if (pinOpen) return;
    pinOpen = true;
    pinInvoiceId = id;
    authConnection = connection;
    customerPin.clear();
    customerPin.setDescription("");
    customerPin.setInputEnabled(true);
    customerSidePages.show(customerSide, "pin");
    buttons();
    SwingUtilities.invokeLater(customerPin::focusInput);
  }

  private void customerPinFeedback(String text) {
    pinFeedbackRevision++;
    customerPin.setFeedback(text);
    customerPin.setWarning("");
  }

  private void showWrongPinFeedback(long invoiceId, String connection) {
    frame.errorMessage("Mã PIN không đúng!");
    customerPin.setWarning(PIN_LOCK_WARNING);
    long revision = ++pinFeedbackRevision;
    new SwingWorker<Api.ReaderStatus, Void>() {
      @Override protected Api.ReaderStatus doInBackground() {
        return frame.api.get("/reader/status", Api.ReaderStatus.class);
      }

      @Override protected void done() {
        if (revision != pinFeedbackRevision || !pinOpen || invoice == null
            || invoice.id() != invoiceId || !connection.equals(authConnection)
            || !connection.equals(frame.status().connectionId())) return;
        try {
          Api.ReaderStatus status = get();
          if (status.present() && status.initialized() && connection.equals(status.connectionId())
              && !status.lifecycleStatus().equals("BLOCKED") && status.tries() > 0
              && status.tries() < 5) {
            customerPin.setFeedback("Bạn còn " + status.tries() + " lần nhập sai!");
          }
        } catch (Exception ignored) {
          // Keep the warning, but never guess the remaining attempts when the server is unavailable.
        }
      }
    }.execute();
  }

  private void submitCustomerPin() {
    if (!pinOpen || !customerPin.inputEnabled() || frame.isBusy()) return;
    char[] digits = customerPin.readDigits();
    if (digits == null) {
      customerPin.setFeedback("Vui lòng nhập đủ 6 chữ số PIN.");
      customerPin.focusInput();
      return;
    }
    Api.Pin request;
    try {
      String value = new String(digits);
      Wire.pin(value);
      request = new Api.Pin(value);
    } finally {
      Arrays.fill(digits, '\0');
    }
    long id = pinInvoiceId;
    String connection = authConnection;
    if (!pinAllowed() || invoice.id() != id || remaining() == 0
        || !connection.equals(frame.status().connectionId())) {
      closePin();
      return;
    }
    customerPin.clear();
    customerPin.setDescription("Đang xử lý thanh toán…");
    customerPin.setInputEnabled(false);
    settling = true;
    cardPaymentInFlight = true;
    viewSequence++;
    qr.setIcon(null);
    qrImage = "";
    buttons();
    frame.runWithoutOverlay(
        () -> frame.api.post("/invoices/" + id + "/pay", request, Api.Invoice.class),
        result -> {
          settling = false;
          cardPaymentInFlight = false;
          closePin(true);
          accept(result);
          frame.poll();
        },
        failure -> {
          settling = false;
          cardPaymentInFlight = false;
          boolean wrong = failure instanceof ApiClient.ApiError error && error.code.equals("PIN_WRONG");
          if (wrong && pinOpen && invoice != null && invoice.id() == id
              && connection.equals(frame.status().connectionId())) {
            customerPin.setDescription("");
            showWrongPinFeedback(id, connection);
            customerPin.setInputEnabled(true);
            customerPin.focusInput();
          } else {
            closePin(true);
            frame.error(failure);
          }
          buttons();
          frame.poll();
          pollInvoice();
        });
  }

  private void cancelPinEntry() {
    if (frame.isBusy() || !pinOpen || invoice == null) return;
    long id = pinInvoiceId;
    customerPin.setDescription("Đang quay lại…");
    customerPin.setInputEnabled(false);
    settling = true;
    buttons();
    frame.runWithoutOverlay(() -> {
      frame.api.post("/invoices/" + id + "/end-auth", null, Api.Result.class);
      return frame.api.get("/invoices/" + id, Api.Invoice.class);
    }, result -> {
      settling = false;
      closePin(true);
      accept(result);
    }, failure -> {
      settling = false;
      customerPin.setDescription("");
      customerPin.setInputEnabled(true);
      buttons();
      frame.error(failure);
      pollInvoice();
    });
  }

  private void closePin() {
    closePin(false);
  }

  private void closePin(boolean keepContext) {
    boolean wasOpen = pinOpen;
    long id = pinInvoiceId;
    pinFeedbackRevision++;
    pinOpen = false;
    pinInvoiceId = 0;
    customerPin.clear();
    customerPin.setDescription("");
    customerSidePages.show(customerSide, "choice");
    if (wasOpen && !keepContext && invoice != null && invoice.id() == id) {
      new SwingWorker<Void, Void>() {
        protected Void doInBackground() {
          try {
            frame.api.post("/invoices/" + id + "/end-auth", null, Api.Result.class);
          } catch (RuntimeException ignored) {
          }
          return null;
        }
        protected void done() {
          if (!ended && isDisplayable()) pollInvoice();
        }
      }.execute();
    }
  }

  private void cancel() {
    if (frame.isBusy() || !invoiceAction.isEnabled()) {
      return;
    }
    if (JOptionPane.showConfirmDialog(
            this,
            "Hóa đơn vẫn đang chờ thanh toán, xác nhận hủy?\n" + invoice.number(),
            "Hủy hóa đơn",
            JOptionPane.OK_CANCEL_OPTION)
        != JOptionPane.OK_OPTION) {
      return;
    }
    cancellationRequestedInvoiceId = invoice.id();
    cancelInvoice(null, "/cancel");
  }

  private void cancelInvoice(JDialog source, String action) {
    String path = "/invoices/" + invoice.id();
    settleInvoice(source, "Đang hủy hóa đơn…", () -> {
      frame.api.post(path + action, null, Api.Result.class);
      return frame.api.get(path, Api.Invoice.class);
    }, result -> {});
  }

  private void settleInvoice(JDialog source, String text, Callable<Api.Invoice> request,
      Consumer<Api.Invoice> done) {
    settling = true;
    viewSequence++;
    qr.setIcon(null);
    qrImage = "";
    buttons();
    frame.run(source, text, request, result -> {
      settling = false;
      cardPaymentInFlight = false;
      closePin(true);
      accept(result);
      done.accept(result);
      frame.poll();
    }, failure -> {
      settling = false;
      cardPaymentInFlight = false;
      closePin(true);
      buttons();
      frame.error(source, failure);
      frame.poll();
      pollInvoice();
    });
  }

  private void buttons() {
    boolean waiting = open();
    boolean locked = waiting || pending();
    boolean processing = cardPaymentInFlight || invoice != null
        && invoice.status().equals("PAYMENT_PENDING")
        && invoice.paymentPhase().equals("CARD_PROCESSING");
    cartOverlayText.setText(processing ? "Đang tiến hành thanh toán bằng thẻ!"
        : invoice != null && invoice.status().equals("CANCEL_PENDING")
            ? "Đang hủy và đối soát hóa đơn…" : "Hóa đơn đang chờ thanh toán!");
    cartOverlay.setVisible(locked);
    cartTable.setEnabled(!locked);
    if (!canEditCart() && cartTable.isEditing()) cartTable.getCellEditor().cancelCellEditing();
    cartTable.repaint();
    invoiceAction.setText(locked ? "Hủy hóa đơn" : "Tạo hóa đơn");
    boolean canCancel = !ended && !settling && !processing
        && (waiting || invoice != null && invoice.status().equals("PAYMENT_PENDING"));
    invoiceAction.setEnabled(locked ? canCancel : !ended && !settling);
    customerPay.setEnabled(
        pinAllowed()
            && remaining() > 0
            && !invoice.paymentPhase().equals("CARD_PROCESSING")
            && frame.status().present()
            && frame.status().initialized()
            && !frame.status().lifecycleStatus().equals("BLOCKED")
            && frame.cardReady()
            && !authenticating
            && !settling
            && !pinOpen);
    customerBank.setEnabled(pinAllowed() && remaining() > 0
        && !invoice.paymentPhase().equals("CARD_PROCESSING")
        && !authenticating && !settling && !pinOpen && !ended);
    qrRetry.setEnabled(
        pinAllowed() && remaining() > 0 && !invoice.paymentPhase().equals("CARD_PROCESSING"));
  }
}
