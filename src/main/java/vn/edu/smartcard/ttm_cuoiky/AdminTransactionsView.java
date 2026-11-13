package vn.edu.smartcard.ttm_cuoiky;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import vn.edu.smartcard.shared.Api;

/** Paginated, read-only financial transactions in the admin dialog. */
final class AdminTransactionsView extends JPanel {
  private static final int PAGE_SIZE = 10;
  private static final int ROW_HEIGHT = 40;
  private static final int TABLE_HEIGHT = (PAGE_SIZE + 1) * ROW_HEIGHT + PAGE_SIZE + 2;
  private static final Color DIVIDER = new Color(0xD0D0D0);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
      .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

  private final CanteenFrame frame;
  private final JTextField query = new JTextField();
  private final JTextField startDate = TransactionDatePicker.field("Ngày bắt đầu");
  private final JTextField endDate = TransactionDatePicker.field("Ngày kết thúc");
  private final JTextField amount = InputFormats.transactionAmountField();
  private final JLabel status = new JLabel(" ");
  private final JLabel resultCount = new JLabel();
  private final DefaultTableModel rows = new DefaultTableModel(new String[] {
      "Thời điểm", "Loại giao dịch", "Mã tham chiếu", "ID thẻ", "Mã SV", "Số tiền", "Trạng thái", "Lý do"
  }, PAGE_SIZE) {
    @Override public boolean isCellEditable(int row, int column) { return false; }
  };
  private final JTable table = new JTable(rows) {
    @Override protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      Graphics lines = graphics.create();
      try {
        lines.setColor(getGridColor());
        for (int row = 0; row < getRowCount() - 1; row++) {
          Rectangle bounds = getCellRect(row, 0, true);
          int y = bounds.y + bounds.height - 1;
          lines.drawLine(0, y, getWidth() - 1, y);
        }
      } finally { lines.dispose(); }
    }

    @Override public String getToolTipText(MouseEvent event) {
      int row = rowAtPoint(event.getPoint()), column = columnAtPoint(event.getPoint());
      if (row < 0 || column < 0) return null;
      Object value = getValueAt(row, column);
      return value == null || value.toString().isBlank() ? null : value.toString();
    }
  };
  private final PaginationBar pagination = new PaginationBar(this::changePage);
  private final Timer debounce = new Timer(500, e -> load(0, false));
  private Api.AdminTransactionPage pageData;
  private SwingWorker<Api.AdminTransactionPage, Void> loading;
  private boolean disposed;
  private final Timer refresh = new Timer(5000, e -> {
    if (!disposed && isShowing() && pageData != null && loading == null && !debounce.isRunning())
      load(pageData.page(), true);
  });

  AdminTransactionsView(CanteenFrame frame, Api.AdminTransactionPage initial) {
    super(new BorderLayout(0, 16));
    this.frame = frame;
    setBackground(Color.WHITE);
    setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
    query.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
        "Tìm kiếm theo mã tham chiếu, ID thẻ hoặc mã SV");
    query.getAccessibleContext().setAccessibleName("Tìm kiếm giao dịch");
    query.setFont(query.getFont().deriveFont(Font.PLAIN, 16f));
    query.setPreferredSize(new Dimension(400, 40));
    amount.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Nhập số tiền");
    amount.getAccessibleContext().setAccessibleName("Lọc theo số tiền");
    for (JTextField field : new JTextField[] {startDate, endDate, amount}) {
      field.setColumns(0);
      field.setFont(field.getFont().deriveFont(Font.PLAIN, 16f));
      field.setPreferredSize(new Dimension(180, 40));
      field.setMaximumSize(new Dimension(180, 40));
    }
    status.setFont(status.getFont().deriveFont(Font.PLAIN, 16f));
    JPanel search = new JPanel(new BorderLayout(8, 0));
    search.setOpaque(false);
    JPanel filters = new JPanel();
    filters.setOpaque(false);
    filters.setLayout(new BoxLayout(filters, BoxLayout.X_AXIS));
    JLabel filterLabel = new JLabel("Lọc theo :");
    filterLabel.setLabelFor(amount);
    filters.add(filterLabel);
    filters.add(Box.createHorizontalStrut(8));
    filters.add(amount);
    filters.add(Box.createHorizontalStrut(8));
    filters.add(startDate);
    filters.add(Box.createHorizontalStrut(8));
    filters.add(endDate);
    search.add(query, BorderLayout.WEST);
    search.add(status, BorderLayout.CENTER);
    search.add(filters, BorderLayout.EAST);
    add(search, BorderLayout.NORTH);

    table.setFont(table.getFont().deriveFont(Font.PLAIN, 16f));
    table.setRowHeight(ROW_HEIGHT + 1);
    table.setRowHeight(PAGE_SIZE - 1, ROW_HEIGHT);
    table.setRowMargin(0);
    table.setShowHorizontalLines(false);
    table.setShowVerticalLines(false);
    table.setGridColor(DIVIDER);
    table.setFillsViewportHeight(true);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setSelectionBackground(table.getBackground());
    table.setSelectionForeground(table.getForeground());
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    ToolTipManager.sharedInstance().registerComponent(table);
    table.getAccessibleContext().setAccessibleName("Giao dịch");
    DefaultTableCellRenderer centered = renderer(SwingConstants.CENTER);
    table.setDefaultRenderer(Object.class, centered);
    table.getColumnModel().getColumn(7).setCellRenderer(renderer(SwingConstants.LEFT));
    int[] widths = {175, 270, 190, 115, 110, 115, 150, 250};
    widths[1] = Math.max(widths[1], Math.max(
        table.getFontMetrics(table.getFont()).stringWidth("Thanh toán hóa đơn bằng số dư thẻ"),
        table.getFontMetrics(table.getFont()).stringWidth("Thanh toán hóa đơn bằng chuyển khoản")) + 20);
    for (int i = 0; i < widths.length; i++) {
      var column = table.getColumnModel().getColumn(i);
      column.setPreferredWidth(widths[i]);
      column.setWidth(widths[i]);
    }
    var header = table.getTableHeader();
    header.setReorderingAllowed(false);
    header.setFont(header.getFont().deriveFont(Font.BOLD, 16f));
    header.setBackground(new Color(0xAAAAAA));
    header.setForeground(new Color(0x333333));
    header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, DIVIDER));
    header.setPreferredSize(new Dimension(header.getPreferredSize().width, ROW_HEIGHT + 1));
    table.setPreferredScrollableViewportSize(new Dimension(1200, PAGE_SIZE * ROW_HEIGHT + PAGE_SIZE - 1));
    JScrollPane scroll = new JScrollPane(table);
    scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
    scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    scroll.setBorder(BorderFactory.createLineBorder(new Color(0xC2C2C2), 1));
    scroll.setPreferredSize(new Dimension(0, TABLE_HEIGHT + 16));
    pagination.getAccessibleContext().setAccessibleName("Phân trang giao dịch");
    JPanel paging = new JPanel(new BorderLayout());
    paging.setOpaque(false);
    paging.add(resultCount, BorderLayout.WEST);
    paging.add(pagination, BorderLayout.EAST);
    JPanel results = new JPanel(new BorderLayout(0, 8));
    results.setOpaque(false);
    results.add(scroll, BorderLayout.NORTH);
    results.add(paging, BorderLayout.CENTER);
    JPanel body = new JPanel(new BorderLayout());
    body.setOpaque(false);
    body.add(results, BorderLayout.NORTH);
    add(body, BorderLayout.CENTER);

    debounce.setRepeats(false);
    DocumentListener changed = new DocumentListener() {
      public void insertUpdate(DocumentEvent event) { queueSearch(); }
      public void removeUpdate(DocumentEvent event) { queueSearch(); }
      public void changedUpdate(DocumentEvent event) { queueSearch(); }
    };
    for (JTextField field : new JTextField[] {query, startDate, endDate, amount}) {
      field.getDocument().addDocumentListener(changed);
      field.addActionListener(e -> {
        debounce.stop();
        load(0, false);
      });
    }
    showPage(initial);
    refresh.start();
  }

  private static DefaultTableCellRenderer renderer(int alignment) {
    DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
      @Override protected void setValue(Object value) {
        String text = value == null ? "" : value.toString();
        setText(text.isBlank() ? "--" : text);
      }
    };
    renderer.putClientProperty("html.disable", Boolean.TRUE);
    renderer.setHorizontalAlignment(alignment);
    return renderer;
  }

  static boolean validPage(Api.AdminTransactionPage page, int requested) {
    if (page == null || page.items() == null || page.page() != requested || page.pageSize() != PAGE_SIZE
        || page.total() < 0 || page.items().size() > PAGE_SIZE
        || page.items().stream().anyMatch(Objects::isNull)) return false;
    int expected = (int) Math.min(PAGE_SIZE, Math.max(0L, (long) page.total() - (long) requested * PAGE_SIZE));
    return page.items().size() == expected;
  }

  private void queueSearch() {
    cancelLoad();
    pageData = null;
    displayRows(List.of());
    setStatus(" ", false);
    resultCount.setText(" ");
    pagination.setPage(0, 0, PAGE_SIZE, true);
    debounce.restart();
  }

  private void changePage(int requested) {
    if (disposed || loading != null || pageData == null || requested < 0 || requested == pageData.page()) return;
    if ((long) requested * PAGE_SIZE < pageData.total()) load(requested, false);
  }

  private record Filters(String search, LocalDate start, LocalDate end, Long amount) {
    boolean active() { return start != null || end != null || amount != null; }

    String path(int page) {
      StringBuilder path = new StringBuilder(active() ? "/admin/transactions/filter" : "/admin/transactions")
          .append("?page=").append(page).append("&q=").append(ApiClient.query(search));
      if (start != null) path.append("&from=").append(start);
      if (end != null) path.append("&to=").append(end);
      if (amount != null) path.append("&amount=").append(amount);
      return path.toString();
    }
  }

  private Filters filters() {
    String search = query.getText().trim();
    if (search.length() > 150) throw new IllegalArgumentException("Từ khóa tối đa 150 ký tự.");
    LocalDate start = parseDate(startDate, "Ngày bắt đầu"), end = parseDate(endDate, "Ngày kết thúc");
    if (start != null && end != null && start.isAfter(end))
      throw new IllegalArgumentException("Ngày bắt đầu sau ngày kết thúc.");
    String digits = InputFormats.amountDigits(amount);
    Long value = digits.isEmpty() ? null : Long.parseLong(digits);
    return new Filters(search, start, end, value);
  }

  private static LocalDate parseDate(JTextField field, String name) {
    String text = field.getText().trim();
    if (text.isEmpty()) return null;
    try {
      LocalDate date = LocalDate.parse(text, TransactionDatePicker.FORMAT);
      if (date.getYear() < 1) throw new IllegalArgumentException();
      return date;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(name + " không hợp lệ.");
    }
  }

  private void load(int requested, boolean silent) {
    if (disposed) return;
    cancelLoad();
    final Filters filters;
    try {
      filters = filters();
    } catch (IllegalArgumentException invalid) {
      setStatus(invalid.getMessage(), true);
      updatePaging();
      return;
    }
    if (!silent) setStatus(" ", false);
    loading = new SwingWorker<>() {
      @Override protected Api.AdminTransactionPage doInBackground() {
        int target = requested;
        for (int attempt = 0; attempt < 3; attempt++) {
          Api.AdminTransactionPage page = frame.api.get(filters.path(target), Api.AdminTransactionPage.class);
          if (!validPage(page, target)) throw new IllegalStateException("Dữ liệu giao dịch không hợp lệ.");
          int last = page.total() == 0 ? 0 : (page.total() - 1) / PAGE_SIZE;
          if (target <= last) return page;
          target = last;
        }
        throw new IllegalStateException("Danh sách giao dịch đã thay đổi.");
      }

      @Override protected void done() {
        if (loading != this || disposed) return;
        loading = null;
        if (frame.admin == null || !frame.admin.logged()) { dispose(); return; }
        try {
          showPage(get());
          setStatus(" ", false);
        } catch (Exception failure) {
          if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
          Throwable cause = failure instanceof ExecutionException ? failure.getCause() : failure;
          if (cause instanceof ApiClient.ApiError error && (error.status == 401 || error.code.startsWith("ADMIN_"))) {
            frame.admin.expired();
            return;
          }
          if (filters.active() && cause instanceof ApiClient.ApiError error && error.status == 404)
            setStatus("Bộ lọc cần cập nhật backend.", true);
          else setStatus("Không tải được giao dịch.", true);
          if (pageData == null) resultCount.setText(" ");
        } finally { updatePaging(); }
      }
    };
    updatePaging();
    loading.execute();
  }

  private void setStatus(String text, boolean error) {
    TTM_CuoiKy.statusText(status, text, error);
    status.setToolTipText(text.isBlank() ? null : text);
  }

  private void showPage(Api.AdminTransactionPage page) {
    displayRows(page.items().stream().map(AdminTransactionsView::displayRow).toList());
    pageData = page;
    updatePaging();
  }

  private void displayRows(List<Object[]> values) {
    for (int row = 0; row < PAGE_SIZE; row++)
      for (int column = 0; column < rows.getColumnCount(); column++)
        rows.setValueAt(row < values.size() ? values.get(row)[column] : null, row, column);
  }

  private void updatePaging() {
    if (pageData == null) {
      pagination.setPage(0, 0, PAGE_SIZE, true);
      return;
    }
    int count = pageData.items().size();
    long first = (long) pageData.page() * PAGE_SIZE + 1;
    String shown = count == 0 ? "0" : count == 1 ? Long.toString(first) : first + "–" + (first + count - 1);
    resultCount.setText("Hiển thị " + shown + " trên tổng số " + pageData.total() + " kết quả");
    pagination.setPage(pageData.page(), pageData.total(), PAGE_SIZE, loading != null);
  }

  private void cancelLoad() {
    if (loading != null) {
      SwingWorker<Api.AdminTransactionPage, Void> old = loading;
      loading = null;
      old.cancel(true);
    }
  }

  void dispose() {
    disposed = true;
    debounce.stop();
    refresh.stop();
    cancelLoad();
    pageData = null;
    displayRows(List.of());
  }

  private static Object[] displayRow(Api.AdminTransaction i) {
    String type = switch (Objects.toString(i.kind(), "")) {
      case "TOPUP" -> "Nạp tiền";
      case "CARD_INVOICE" -> "Thanh toán hóa đơn bằng số dư thẻ";
      case "BANK_INVOICE" -> "Thanh toán hóa đơn bằng chuyển khoản";
      default -> "Không rõ";
    };
    String status = switch (Objects.toString(i.status(), "")) {
      case "WAIT_CARD" -> "Chờ ghi thẻ";
      case "CREDITED" -> "Đã ghi thẻ";
      case "PAID" -> "Đã thanh toán";
      case "REVIEW" -> "Cần đối chiếu";
      default -> "";
    };
    String reason = switch (Objects.toString(i.reason(), "")) {
      case "" -> "";
      case "ALREADY_PAID" -> "Hóa đơn đã thanh toán; kiểm tra khoản dư";
      case "AMOUNT_MISMATCH" -> "Số tiền không khớp";
      case "INVALID_TRANSFER_CODE" -> "Nội dung thiếu mã hoặc mã không hợp lệ";
      case "NO_ACTIVE_REQUEST", "UNKNOWN_INVOICE" -> "Không tìm thấy yêu cầu theo mã";
      case "REQUEST_CLOSED", "INVOICE_CLOSED", "REQUEST_EXPIRED", "OUTSIDE_WINDOW", "STATION_CLOSED" ->
          "Yêu cầu hết hạn, đã hủy hoặc ngoài thời gian nhận";
      case "INVOICE_DELETED" -> "Hóa đơn đã xóa, chứng từ còn giữ";
      case "CARD_RESULT_PENDING" -> "Chưa xác định kết quả debit trên thẻ";
      case "LEGACY_UNCERTAIN" -> "Chưa xác định được kết quả dữ liệu cũ";
      case "UNMATCHED" -> "Không xác định được yêu cầu";
      default -> "Kiểm tra chứng từ ngân hàng";
    };
    String reference = Objects.toString(i.reference(), "");
    if (i.simulated()) reference = "[DEMO] " + reference;
    return new Object[] {TIME.format(Instant.parse(i.occurredAt())), type, reference,
        i.cardId(), i.studentCode(), CanteenFrame.money(i.amount()), status, reason};
  }
}
