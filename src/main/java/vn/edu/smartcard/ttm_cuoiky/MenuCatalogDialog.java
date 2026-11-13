package vn.edu.smartcard.ttm_cuoiky;

import com.formdev.flatlaf.ui.FlatComboBoxUI;
import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.table.*;
import vn.edu.smartcard.shared.*;

/** Catalog and category management; the sales grid remains available at the counter. */
final class MenuCatalogDialog extends JDialog {
  private static final int CATEGORY_ROW_HEIGHT = 32;
  private static final int CATEGORY_VISIBLE_ROWS = 4;
  private static final int IMAGE_COLUMN_WIDTH = 80;
  private static final int ACTION_COLUMN_WIDTH = 128;
  private final CanteenFrame frame;
  private final DishImages images;
  private final MenuDialogs forms;
  private final Runnable reload;
  private final JComboBox<Api.MenuCategory> categories = new JComboBox<>();
  private final JTextField search = new JTextField();
  private final JComboBox<String> sort =
      new JComboBox<>(new String[] {"Mới -> Cũ", "Bán chạy", "Giá bán"});
  private final JLabel results = new JLabel(" ");
  private final CatalogTable model = new CatalogTable();
  private final JTable table = new JTable(model) {
    @Override
    public void doLayout() {
      if (getColumnCount() == 5 && getWidth() >= IMAGE_COLUMN_WIDTH + ACTION_COLUMN_WIDTH + 45) {
        int remaining = getWidth() - IMAGE_COLUMN_WIDTH - ACTION_COLUMN_WIDTH;
        for (int i = 0; i < 3; i++)
          getColumnModel().getColumn(i + 1).setPreferredWidth(remaining / 3 + (i < remaining % 3 ? 1 : 0));
        getColumnModel().getColumn(4).setPreferredWidth(ACTION_COLUMN_WIDTH);
      }
      super.doLayout();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      graphics.setColor(getGridColor());
      Rectangle clip = graphics.getClipBounds();
      for (int row = 0; row < getRowCount() - 1; row++) {
        Rectangle cell = getCellRect(row, 0, true);
        int y = cell.y + cell.height - 1;
        if (clip == null || (y >= clip.y && y < clip.y + clip.height))
          graphics.drawLine(0, y, getWidth() - 1, y);
      }
    }
  };
  private final Map<String, ImageIcon> icons = new HashMap<>();
  private final Set<String> requestedImages = new HashSet<>();
  private final Icon editIcon = DesktopIcons.svg("/icons/pen-square-svgrepo-com.svg", 20, "biểu tượng sửa món");
  private final Icon deleteIcon = DesktopIcons.svg("/icons/trash-alt-svgrepo-com.svg", 20, "biểu tượng xóa món");
  private Api.MenuCategory[] categoryItems = new Api.MenuCategory[0];
  private Api.MenuItem[] items = new Api.MenuItem[0];
  private Map<Long, Long> sales;
  private boolean updatingCategories;
  private final javax.swing.Timer searchDebounce = new javax.swing.Timer(500, e -> searchBackend());
  private SwingWorker<Api.MenuSearchResult, Void> searchWorker;
  private long searchGeneration;
  private List<Api.MenuItem> searchResults;
  private int totalItems;
  private boolean searchPending;
  private String searchError;

  MenuCatalogDialog(CanteenFrame frame, DishImages images, MenuDialogs forms, Runnable reload) {
    super(frame, "Danh sách món ăn", Dialog.ModalityType.MODELESS);
    this.frame = frame;
    this.images = images;
    this.forms = forms;
    this.reload = reload;

    searchDebounce.setRepeats(false);
    JPanel body = new JPanel(new BorderLayout(0, 8));
    body.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
    JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    JPanel categoryRow = row();
    JLabel categoryLabel = new JLabel("Danh mục");
    categoryLabel.setFont(categoryLabel.getFont().deriveFont(Font.PLAIN, 16f));
    categoryLabel.setLabelFor(categories);
    categoryRow.add(categoryLabel);
    categoryRow.add(Box.createHorizontalStrut(8));
    categories.setPreferredSize(new Dimension(320, 32));
    categoryRow.add(categories);
    top.add(categoryRow);
    top.add(Box.createVerticalStrut(8));
    JPanel actions = row();
    for (JButton button : new JButton[] {
        CanteenFrame.button("Thêm món ăn", e -> forms.dish(this, categoryItems, selectedCategory(), null)),
        CanteenFrame.button("Thêm danh mục", e -> forms.category(this)),
        CanteenFrame.button("Xóa danh mục", e -> deleteCategory())}) {
      if (actions.getComponentCount() > 0) actions.add(Box.createHorizontalStrut(8));
      button.setPreferredSize(new Dimension(160, 32));
      actions.add(button);
    }
    top.add(actions);
    top.add(Box.createVerticalStrut(8));
    JPanel searchRow = new JPanel(new BorderLayout());
    searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
    searchRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
    search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Tìm kiếm theo tên món ăn");
    search.getAccessibleContext().setAccessibleName("Tìm kiếm theo tên món ăn");
    search.setPreferredSize(new Dimension(320, 32));
    searchRow.add(search, BorderLayout.WEST);
    JPanel sortRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    JLabel sortLabel = new JLabel("Sắp xếp");
    sortLabel.setFont(sortLabel.getFont().deriveFont(Font.PLAIN, 16f));
    sortLabel.setLabelFor(sort);
    sortRow.add(sortLabel);
    sortRow.add(Box.createHorizontalStrut(8));
    sort.setPreferredSize(new Dimension(160, 32));
    sortRow.add(sort);
    searchRow.add(sortRow, BorderLayout.EAST);
    top.add(searchRow);
    body.add(top, BorderLayout.NORTH);

    table.setRowHeight(80);
    table.setIntercellSpacing(new Dimension(0, 0));
    table.setShowGrid(false);
    table.setGridColor(new Color(0xDDDDDD));
    table.setFillsViewportHeight(true);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.getTableHeader().setReorderingAllowed(false);
    table.getTableHeader().setResizingAllowed(false);
    table.getTableHeader().setPreferredSize(new Dimension(0, 40));
    table.getTableHeader().setBackground(new Color(0xE5E5E5));
    table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
    table.getAccessibleContext().setAccessibleName("Danh sách món ăn");
    TableColumn imageColumn = table.getColumnModel().getColumn(0);
    imageColumn.setMinWidth(IMAGE_COLUMN_WIDTH);
    imageColumn.setMaxWidth(IMAGE_COLUMN_WIDTH);
    imageColumn.setPreferredWidth(IMAGE_COLUMN_WIDTH);
    for (int i = 1; i <= 3; i++) table.getColumnModel().getColumn(i).setPreferredWidth(178);
    TableColumn operationColumn = table.getColumnModel().getColumn(4);
    operationColumn.setMinWidth(ACTION_COLUMN_WIDTH);
    operationColumn.setPreferredWidth(ACTION_COLUMN_WIDTH);
    DefaultTableCellRenderer centered = new DefaultTableCellRenderer();
    centered.setHorizontalAlignment(SwingConstants.CENTER);
    table.getColumnModel().getColumn(1).setCellRenderer(centered);
    table.getColumnModel().getColumn(2).setCellRenderer(centered);
    table.getColumnModel().getColumn(3).setCellRenderer(centered);
    operationColumn.setCellRenderer(new ActionRenderer());
    operationColumn.setCellEditor(new ActionEditor());
    table.setPreferredScrollableViewportSize(new Dimension(742, table.getRowHeight() * 4));
    JScrollPane scroll = new JScrollPane(table);
    scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
    scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
    body.add(scroll, BorderLayout.CENTER);

    JPanel footer = new JPanel(new BorderLayout());
    results.setFont(results.getFont().deriveFont(Font.PLAIN, 16f));
    footer.add(results, BorderLayout.CENTER);
    JButton close = CanteenFrame.button("Đóng", e -> dispose());
    close.setPreferredSize(new Dimension(120, 30));
    footer.add(close, BorderLayout.EAST);
    body.add(footer, BorderLayout.SOUTH);
    categories.addActionListener(e -> { if (!updatingCategories) filter(); });
    sort.addActionListener(e -> filter());
    search.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent e) { queueSearch(); }
      public void removeUpdate(DocumentEvent e) { queueSearch(); }
      public void changedUpdate(DocumentEvent e) { queueSearch(); }
    });
    getRootPane().registerKeyboardAction(e -> dispose(),
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    setContentPane(body);
    pack();
    setSize(800, getHeight());
    setResizable(false);
    setLocationRelativeTo(frame);
  }

  private static JPanel row() {
    JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    row.setAlignmentX(Component.LEFT_ALIGNMENT);
    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
    return row;
  }

  void setCatalog(Api.MenuItem[] items, Api.MenuCategory[] categories, Api.MenuSales[] counts) {
    long selected = selectedCategory();
    this.items = items;
    totalItems = items.length;
    searchResults = null;
    categoryItems = categories;
    sales = null;
    if (counts != null) {
      sales = new HashMap<>();
      for (var count : counts) sales.put(count.itemId(), count.soldQuantity());
    }
    updatingCategories = true;
    this.categories.removeAllItems();
    this.categories.addItem(new Api.MenuCategory(0, "Tất cả", items.length, 0));
    for (var category : categories) {
      this.categories.addItem(category);
      if (category.id() == selected) this.categories.setSelectedItem(category);
    }
    updatingCategories = false;
    if (!search.getText().isEmpty() || searchPending) queueSearch();
    else filter();
  }

  private void queueSearch() {
    searchGeneration++;
    if (searchWorker != null) searchWorker.cancel(true);
    searchWorker = null;
    searchPending = true;
    searchError = null;
    searchResults = null;
    filter();
    searchDebounce.restart();
  }

  private void searchBackend() {
    final String query = search.getText();
    final long generation = searchGeneration;
    SwingWorker<Api.MenuSearchResult, Void> worker = new SwingWorker<>() {
      protected Api.MenuSearchResult doInBackground() {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return frame.api.get("/menu/search?query=" + encoded, Api.MenuSearchResult.class);
      }

      protected void done() {
        if (generation != searchGeneration || !isDisplayable()) return;
        searchWorker = null;
        searchPending = false;
        try {
          Api.MenuSearchResult response = get();
          searchResults = List.copyOf(response.items());
          totalItems = response.total();
          searchError = null;
        } catch (Exception failure) {
          searchResults = null;
          searchError = "Không tìm được món ăn. Hãy thử nhập lại.";
        }
        filter();
      }
    };
    searchWorker = worker;
    worker.execute();
  }

  @Override
  public void dispose() {
    searchGeneration++;
    searchDebounce.stop();
    if (searchWorker != null) searchWorker.cancel(true);
    searchWorker = null;
    super.dispose();
  }

  void invalidateSales() {
    sales = null;
    filter();
  }

  void updateSales(Api.MenuSales[] counts) {
    sales = new HashMap<>();
    for (var count : counts) sales.put(count.itemId(), count.soldQuantity());
    filter();
  }

  private long selectedCategory() {
    var selected = (Api.MenuCategory) categories.getSelectedItem();
    return selected == null ? 0 : selected.id();
  }

  private void filter() {
    if (table.isEditing()) table.getCellEditor().cancelCellEditing();
    if (searchPending || searchError != null) {
      model.rows = List.of();
      model.fireTableDataChanged();
      results.setText(searchPending ? " " : searchError);
      return;
    }
    if (sort.getSelectedIndex() == 1 && sales == null) {
      model.rows = List.of();
      model.fireTableDataChanged();
      results.setText("Chưa tải được số lượng bán. Hãy đóng và mở lại danh sách.");
      return;
    }
    String query = Profile.searchable(search.getText());
    long selected = selectedCategory();
    Comparator<Api.MenuItem> newest = Comparator.comparingLong(Api.MenuItem::id).reversed();
    Comparator<Api.MenuItem> order = switch (sort.getSelectedIndex()) {
      case 1 -> Comparator.<Api.MenuItem>comparingLong(m -> sales.getOrDefault(m.id(), 0L))
          .reversed().thenComparing(newest);
      case 2 -> Comparator.comparingLong(Api.MenuItem::price).reversed().thenComparing(newest);
      default -> newest;
    };
    var candidates = searchResults == null ? Arrays.stream(items) : searchResults.stream();
    model.rows = candidates
        .filter(m -> (selected == 0 || m.categoryId() == selected)
            && Profile.searchable(m.name()).contains(query))
        .sorted(order).toList();
    model.fireTableDataChanged();
    results.setText("Đã hiển thị " + model.rows.size() + " trên tổng số " + totalItems + " món ăn.");
    for (var item : model.rows) {
      String key = imageKey(item);
      if (item.hasImage() && requestedImages.add(key)) {
        images.load(frame.api, item, 64, icon -> {
          if (icon != null) icons.put(key, icon);
          else requestedImages.remove(key);
          if (isDisplayable()) table.repaint();
        });
      }
    }
  }

  private String imageKey(Api.MenuItem item) {
    return item.id() + ":" + item.revision();
  }

  private void deleteCategory() {
    if (categoryItems.length == 0) {
      JOptionPane.showMessageDialog(this, "Chưa có danh mục để xóa.");
      return;
    }
    var choice = chooseCategoryToDelete();
    if (choice == null) return;
    frame.run(this, "Đang kiểm tra danh mục…",
        () -> frame.api.get("/menu/categories/" + choice.id() + "/delete-preview", Api.MenuCategory.class),
        current -> {
          if (current.itemCount() > 0) {
            if (!confirmCategoryDeletion(current)) return;
          }
          frame.run(this, "Đang xóa danh mục…",
              () -> frame.api.post("/menu/categories/" + current.id() + "/delete",
                  new Api.MenuDelete(current.revision()), Api.Result.class),
              result -> reload.run(), failure -> frame.error(this, failure));
        }, failure -> frame.error(this, failure));
  }

  private Api.MenuCategory chooseCategoryToDelete() {
    JComboBox<Api.MenuCategory> choices = new JComboBox<>(categoryItems);
    for (var c : categoryItems) if (c.id() == selectedCategory()) choices.setSelectedItem(c);
    choices.setMaximumRowCount(CATEGORY_VISIBLE_ROWS);
    choices.setUI(new FlatComboBoxUI() {
      @Override
      protected ComboPopup createPopup() {
        return new FlatComboPopup(comboBox) {
          @Override
          protected JList<Object> createList() {
            return new JList<>(comboBox.getModel()) {
              @Override
              public boolean getScrollableTracksViewportHeight() {
                return false;
              }
            };
          }

          @Override
          protected int getPopupHeightForRowCount(int maxRows) {
            super.getPopupHeightForRowCount(maxRows);
            int height = CATEGORY_VISIBLE_ROWS * CATEGORY_ROW_HEIGHT;
            for (var border : new javax.swing.border.Border[] {
                scroller.getBorder(), scroller.getViewportBorder()}) {
              if (border != null) {
                Insets insets = border.getBorderInsets(scroller);
                height += insets.top + insets.bottom;
              }
            }
            return height;
          }
        };
      }
    });
    choices.setPreferredSize(new Dimension(0, CATEGORY_ROW_HEIGHT));
    Object popup = choices.getUI().getAccessibleChild(choices, 0);
    if (popup instanceof ComboPopup comboPopup)
      comboPopup.getList().setFixedCellHeight(CATEGORY_ROW_HEIGHT);
    JLabel label = new JLabel("Chọn danh mục cần xóa");
    label.setLabelFor(choices);
    JPanel prompt = new JPanel(new BorderLayout(0, 4));
    prompt.add(label, BorderLayout.NORTH);
    prompt.add(choices, BorderLayout.CENTER);
    JDialog dialog = new JDialog(this, "Xóa danh mục", Dialog.ModalityType.DOCUMENT_MODAL);
    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    Api.MenuCategory[] result = {null};
    JButton cancel = new JButton("Cancel"), ok = new JButton("OK");
    cancel.setPreferredSize(new Dimension(90, 30));
    ok.setPreferredSize(new Dimension(90, 30));
    cancel.addActionListener(e -> dialog.dispose());
    ok.addActionListener(e -> {
      result[0] = (Api.MenuCategory) choices.getSelectedItem();
      dialog.dispose();
    });
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    actions.add(cancel);
    actions.add(Box.createHorizontalStrut(8));
    actions.add(ok);
    JPanel body = new JPanel(new BorderLayout(0, 16));
    body.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
    body.add(prompt, BorderLayout.NORTH);
    body.add(actions, BorderLayout.CENTER);
    dialog.setContentPane(body);
    dialog.getRootPane().setDefaultButton(ok);
    dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    dialog.pack();
    dialog.setSize(360, dialog.getHeight());
    dialog.setResizable(false);
    dialog.setLocationRelativeTo(this);
    dialog.setVisible(true);
    return result[0];
  }

  private boolean confirmCategoryDeletion(Api.MenuCategory category) {
    String message = "Danh mục “" + category.name() + "” hiện có " + category.itemCount()
        + " món, tiếp tục xoá?";
    JOptionPane pane = new JOptionPane(message, JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION);
    JButton cancel = new JButton("Huỷ bỏ"), delete = new JButton("Xoá");
    cancel.setPreferredSize(new Dimension(90, 30));
    delete.setPreferredSize(new Dimension(90, 30));
    cancel.addActionListener(e -> pane.setValue(JOptionPane.CANCEL_OPTION));
    delete.addActionListener(e -> pane.setValue(JOptionPane.OK_OPTION));
    pane.setOptions(new Object[] {cancel, delete});
    pane.setInitialValue(cancel);
    JDialog dialog = pane.createDialog(this, "Xác nhận xóa danh mục");
    dialog.getRootPane().setDefaultButton(cancel);
    dialog.setVisible(true);
    dialog.dispose();
    return Integer.valueOf(JOptionPane.OK_OPTION).equals(pane.getValue());
  }

  private void deleteItem(Api.MenuItem item) {
    Object[] options = {"Hủy", "Xóa món"};
    if (JOptionPane.showOptionDialog(this, "Xóa vĩnh viễn món “" + item.name() + "”?",
        "Xóa món", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
        options, options[0]) != 1) return;
    frame.run(this, "Đang xóa món…",
        () -> frame.api.post("/menu/items/" + item.id() + "/delete",
            new Api.MenuDelete(item.revision()), Api.Result.class),
        result -> reload.run(), failure -> frame.error(this, failure));
  }

  private JPanel actionPanel(JButton edit, JButton delete) {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setOpaque(true);
    JPanel buttons = new JPanel();
    buttons.setOpaque(false);
    buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
    for (JButton button : new JButton[] {edit, delete}) {
      Dimension size = new Dimension(32, 32);
      button.setPreferredSize(size);
      button.setMinimumSize(size);
      button.setMaximumSize(size);
      button.putClientProperty(FlatClientProperties.STYLE, "arc: 4; minimumWidth: 0");
      if (buttons.getComponentCount() > 0) buttons.add(Box.createHorizontalStrut(8));
      buttons.add(button);
    }
    panel.add(buttons);
    return panel;
  }

  private JButton iconButton(Icon icon, String description) {
    JButton button = new JButton(icon);
    button.setToolTipText(description);
    button.getAccessibleContext().setAccessibleName(description);
    return button;
  }

  private final class ActionRenderer implements TableCellRenderer {
    private final JPanel panel = actionPanel(iconButton(editIcon, "Sửa món"),
        iconButton(deleteIcon, "Xóa món"));

    public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
        boolean focused, int row, int column) {
      panel.setBackground(selected ? table.getSelectionBackground() : table.getBackground());
      return panel;
    }
  }

  private final class ActionEditor extends AbstractCellEditor implements TableCellEditor {
    private final JButton edit = iconButton(editIcon, "Sửa món");
    private final JButton delete = iconButton(deleteIcon, "Xóa món");
    private final JPanel panel = actionPanel(edit, delete);
    private Api.MenuItem selected;

    ActionEditor() {
      edit.addActionListener(e -> {
        Api.MenuItem item = selected;
        fireEditingStopped();
        if (item != null) forms.dish(MenuCatalogDialog.this, categoryItems, item.categoryId(), item);
      });
      delete.addActionListener(e -> {
        Api.MenuItem item = selected;
        fireEditingStopped();
        if (item != null) deleteItem(item);
      });
    }

    public Object getCellEditorValue() { return selected; }

    public Component getTableCellEditorComponent(JTable table, Object value, boolean selected,
        int row, int column) {
      this.selected = (Api.MenuItem) value;
      panel.setBackground(table.getSelectionBackground());
      return panel;
    }
  }

  private final class CatalogTable extends AbstractTableModel {
    private final String[] columns = {"Ảnh", "Tên món ăn", "Danh mục", "Giá tiền", "Thao tác"};
    private List<Api.MenuItem> rows = List.of();

    public int getRowCount() { return rows.size(); }
    public int getColumnCount() { return columns.length; }
    public String getColumnName(int column) { return columns[column]; }
    public Class<?> getColumnClass(int column) {
      return column == 0 ? Icon.class : column == 4 ? Api.MenuItem.class : String.class;
    }
    public boolean isCellEditable(int row, int column) { return column == 4; }
    public Object getValueAt(int row, int column) {
      var item = rows.get(row);
      return switch (column) {
        case 0 -> item.hasImage() ? icons.get(imageKey(item)) : images.placeholder(64);
        case 1 -> item.name();
        case 2 -> item.category();
        case 3 -> CanteenFrame.money(item.price());
        default -> item;
      };
    }
  }
}
