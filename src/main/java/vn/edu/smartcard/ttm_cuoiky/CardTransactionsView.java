package vn.edu.smartcard.ttm_cuoiky;

import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import vn.edu.smartcard.shared.Api;

final class CardTransactionsView extends JPanel {
  private static final int ROW_HEIGHT = 40;
  private static final int VISIBLE_ROWS = 4;
  private static final int SEPARATOR_HEIGHT = 1;
  private static final int BORDER_WIDTH = 1;
  private static final int TABLE_HEIGHT = (VISIBLE_ROWS + 1) * ROW_HEIGHT
      + VISIBLE_ROWS * SEPARATOR_HEIGHT + 2 * BORDER_WIDTH;
  private static final Color DIVIDER_COLOR = new Color(0xD0D0D0);
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

  private final CanteenFrame frame;
  private final Function<Integer, Api.CardTransactionListPage> fetchPage;
  private final boolean adminHistory;
  private final DefaultTableModel rows =
      new DefaultTableModel(
          new String[] {"Thời gian", "Giao dịch", "Mã tham chiếu", "Tiền vào", "Tiền ra"}, VISIBLE_ROWS) {
        @Override
        public boolean isCellEditable(int row, int column) {
          return false;
        }
      };
  private final JTable table = new JTable(rows) {
    @Override protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      Graphics lines = graphics.create();
      try {
        lines.setColor(getGridColor());
        for (int row = 0; row < getRowCount() - 1; row++) {
          Rectangle bounds = getCellRect(row, 0, true);
          int y = bounds.y + bounds.height - SEPARATOR_HEIGHT;
          lines.drawLine(0, y, getWidth() - 1, y);
        }
      } finally {
        lines.dispose();
      }
    }
  };
  private final JLabel status = new JLabel(" ");
  private final JLabel resultCount = new JLabel("Hiển thị 0 trên tổng số 0 kết quả");
  private final JScrollPane tableScroll;
  private final PaginationBar pagination;
  private Api.CardTransactionListPage pageData;
  private SwingWorker<Api.CardTransactionListPage, Void> loading;

  CardTransactionsView(CanteenFrame frame) {
    this(frame, page -> frame.api.get(
        "/student/transactions/pages?page=" + page, Api.CardTransactionListPage.class));
  }

  CardTransactionsView(CanteenFrame frame, Function<Integer, Api.CardTransactionListPage> fetchPage) {
    this(frame, fetchPage, false);
  }

  static CardTransactionsView forAdmin(CanteenFrame frame, String cardId) {
    String path = "/admin/cards/" + ApiClient.query(cardId) + "/transactions/pages?page=";
    return new CardTransactionsView(frame,
        page -> frame.api.get(path + page, Api.CardTransactionListPage.class), true);
  }

  private CardTransactionsView(CanteenFrame frame,
      Function<Integer, Api.CardTransactionListPage> fetchPage, boolean adminHistory) {
    super(new BorderLayout());
    this.frame = frame;
    this.fetchPage = fetchPage;
    this.adminHistory = adminHistory;
    setBackground(Color.WHITE);
    setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));

    JLabel title = new JLabel("Lịch sử giao dịch");
    title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
    title.setBorder(BorderFactory.createEmptyBorder(12, 0, 12, 0));
    status.setFont(status.getFont().deriveFont(Font.PLAIN, 16f));
    JPanel header = new JPanel(new BorderLayout(8, 0));
    header.setOpaque(false);
    header.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, DIVIDER_COLOR));
    header.add(title, BorderLayout.WEST);
    header.add(status, BorderLayout.CENTER);
    add(header, BorderLayout.NORTH);

    table.setFont(table.getFont().deriveFont(Font.PLAIN, 16f));
    table.setRowHeight(ROW_HEIGHT + SEPARATOR_HEIGHT);
    table.setRowHeight(VISIBLE_ROWS - 1, ROW_HEIGHT);
    table.setRowMargin(0);
    table.setShowHorizontalLines(false);
    table.setShowVerticalLines(false);
    table.setGridColor(DIVIDER_COLOR);
    table.setFillsViewportHeight(true);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setSelectionBackground(table.getBackground());
    table.setSelectionForeground(table.getForeground());
    table.getAccessibleContext().setAccessibleName(title.getText());
    DefaultTableCellRenderer plain = new DefaultTableCellRenderer() {
      @Override
      protected void setValue(Object value) {
        String text = value == null ? "" : value.toString();
        setText(text.isBlank() ? "--" : text);
      }
    };
    plain.putClientProperty("html.disable", Boolean.TRUE);
    plain.setHorizontalAlignment(SwingConstants.CENTER);
    table.setDefaultRenderer(Object.class, plain);
    int[] widths = {190, 205, 255, 145, 145};
    for (int c = 0; c < widths.length; c++) {
      table.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);
    }
    var columnHeader = table.getTableHeader();
    columnHeader.setReorderingAllowed(false);
    columnHeader.setFont(columnHeader.getFont().deriveFont(Font.BOLD, 16f));
    columnHeader.setBackground(new Color(0xAAAAAA));
    columnHeader.setForeground(new Color(0x333333));
    columnHeader.setBorder(BorderFactory.createMatteBorder(0, 0, SEPARATOR_HEIGHT, 0, DIVIDER_COLOR));
    columnHeader.setPreferredSize(new Dimension(columnHeader.getPreferredSize().width,
        ROW_HEIGHT + SEPARATOR_HEIGHT));
    table.setPreferredScrollableViewportSize(new Dimension(940,
        VISIBLE_ROWS * ROW_HEIGHT + (VISIBLE_ROWS - 1) * SEPARATOR_HEIGHT));
    tableScroll = new JScrollPane(table);
    tableScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
    tableScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    tableScroll.setBorder(BorderFactory.createLineBorder(new Color(0xC2C2C2), BORDER_WIDTH));
    tableScroll.setPreferredSize(new Dimension(0, TABLE_HEIGHT));
    tableScroll.setMinimumSize(new Dimension(0, TABLE_HEIGHT));
    JPanel headerCorner = new JPanel();
    headerCorner.setBackground(columnHeader.getBackground());
    tableScroll.setCorner(JScrollPane.UPPER_TRAILING_CORNER, headerCorner);
    pagination = new PaginationBar(this::changePage);
    pagination.getAccessibleContext().setAccessibleName("Phân trang lịch sử giao dịch");
    JPanel paging = new JPanel(new BorderLayout());
    paging.setBackground(Color.WHITE);
    paging.add(resultCount, BorderLayout.WEST);
    paging.add(pagination, BorderLayout.EAST);
    JPanel results = new JPanel(new BorderLayout(0, 8));
    results.setOpaque(false);
    results.add(tableScroll, BorderLayout.NORTH);
    results.add(paging, BorderLayout.CENTER);
    JPanel tableArea = new JPanel(new BorderLayout());
    tableArea.setOpaque(false);
    tableArea.add(results, BorderLayout.NORTH);
    add(tableArea, BorderLayout.CENTER);
    clear();
  }

  void reload() {
    clear();
    load(0);
  }

  void clear() {
    cancelLoad();
    pageData = null;
    displayRows(List.of());
    TTM_CuoiKy.statusText(status, " ", false);
    updatePaging();
  }

  private void cancelLoad() {
    if (loading != null) {
      var previous = loading;
      loading = null;
      previous.cancel(true);
    }
  }

  private void changePage(int requested) {
    if (loading != null || pageData == null || requested < 0 || requested == pageData.page()) return;
    long pages = ((long) pageData.total() + VISIBLE_ROWS - 1) / VISIBLE_ROWS;
    if (requested < pages) load(requested);
  }

  private void load(int requested) {
    if (loading != null) return;
    long epoch = frame.sessionEpoch();
    TTM_CuoiKy.statusText(status, "Đang tải lịch sử giao dịch…", false);
    loading = new SwingWorker<>() {
      @Override
      protected Api.CardTransactionListPage doInBackground() {
        return fetchValidPage(requested);
      }

      @Override
      protected void done() {
        if (loading != this) return;
        loading = null;
        try {
          if ((!adminHistory && epoch != frame.sessionEpoch())
              || (adminHistory && (frame.admin == null || !frame.admin.logged()))) {
            clear();
            return;
          }
          var page = get();
          var displayed = page.items().stream().map(CardTransactionsView::displayRow).toList();
          displayRows(displayed);
          pageData = page;
          TTM_CuoiKy.statusText(status, " ", false);
        } catch (Exception e) {
          if (e instanceof InterruptedException) Thread.currentThread().interrupt();
          Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
          if (cause instanceof ApiClient.ApiError error) {
            if (adminHistory && (error.status == 401 || error.code.startsWith("ADMIN_"))) {
              clear();
              frame.admin.expired();
              return;
            }
            if (adminHistory && error.status == 403) {
              clear();
              TTM_CuoiKy.statusText(status, "Không có quyền xem lịch sử giao dịch.", true);
              return;
            }
            if (!adminHistory && (error.status == 401 || error.status == 403 || error.status == 423
                || error.code.equals("NO_CARD"))) {
              clear();
              TTM_CuoiKy.statusText(status, "Kết nối thẻ và xác thực PIN để xem lịch sử.", true);
              frame.poll();
              return;
            }
          }
          TTM_CuoiKy.statusText(status, pageData == null
              ? (adminHistory ? "Chưa tải được lịch sử. Mở lại thông tin thẻ để thử lại."
                  : "Chưa tải được lịch sử. Mở lại tab Sinh viên để thử lại.")
              : "Chưa tải được trang giao dịch. Vui lòng thử lại.",
              true);
        } finally {
          updatePaging();
        }
      }
    };
    updatePaging();
    loading.execute();
  }

  private void displayRows(List<Object[]> displayed) {
    for (int row = 0; row < VISIBLE_ROWS; row++) {
      for (int column = 0; column < rows.getColumnCount(); column++) {
        rows.setValueAt(row < displayed.size() ? displayed.get(row)[column] : null, row, column);
      }
    }
  }

  private Api.CardTransactionListPage fetchValidPage(int requested) {
    for (int attempt = 0; attempt < 3; attempt++) {
      Api.CardTransactionListPage page = fetchPage.apply(requested);
      if (page == null || page.items() == null || page.page() != requested
          || page.pageSize() != VISIBLE_ROWS || page.total() < 0
          || page.items().size() > VISIBLE_ROWS) {
        throw new IllegalStateException("Không đọc được trang giao dịch.");
      }
      int last = page.total() == 0 ? 0 : (page.total() - 1) / VISIBLE_ROWS;
      if (requested <= last) {
        int expected = (int) Math.min(VISIBLE_ROWS,
            Math.max(0L, (long) page.total() - (long) requested * VISIBLE_ROWS));
        if (page.items().size() != expected) throw new IllegalStateException("Trang giao dịch không đầy đủ.");
        return page;
      }
      requested = last;
    }
    throw new IllegalStateException("Danh sách giao dịch đã thay đổi.");
  }

  private void updatePaging() {
    if (pageData == null) {
      resultCount.setText("Hiển thị 0 trên tổng số 0 kết quả");
      pagination.setPage(0, 0, VISIBLE_ROWS, loading != null);
      return;
    }
    int count = pageData.items().size();
    long first = (long) pageData.page() * VISIBLE_ROWS + 1;
    String shown = count == 0 ? "0" : count == 1 ? Long.toString(first) : first + "–" + (first + count - 1);
    resultCount.setText("Hiển thị " + shown + " trên tổng số " + pageData.total() + " kết quả");
    pagination.setPage(pageData.page(), pageData.total(), VISIBLE_ROWS, loading != null);
  }

  private static Object[] displayRow(Api.CardTransaction transaction) {
    String type = switch (transaction.kind()) {
      case "CREDIT" -> "Nạp tiền";
      case "DEBIT" -> "Trừ tiền hóa đơn";
      case "REVERSAL" -> "Hoàn tiền vào thẻ";
      default -> throw new IllegalArgumentException("Loại giao dịch không hợp lệ.");
    };
    boolean debit = "DEBIT".equals(transaction.kind());
    String amount = CanteenFrame.money(transaction.amount());
    return new Object[] {
      TIME.format(Instant.parse(transaction.createdAt())),
      type,
      transaction.reference(),
      debit ? "" : "+" + amount,
      debit ? "−" + amount : ""
    };
  }
}
