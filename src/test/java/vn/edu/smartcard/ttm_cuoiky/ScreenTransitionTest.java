package vn.edu.smartcard.ttm_cuoiky;

import static org.junit.jupiter.api.Assertions.*;

import com.formdev.flatlaf.FlatClientProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.smartcard.shared.Api;
import vn.edu.smartcard.shared.Profile;

class ScreenTransitionTest {
  private static final String CONNECTED = """
      {"present":true,"alias":"Fixture","initialized":true,"cardId":"fixture",
       "tries":5,"role":"NONE","counter":0,"profileLength":0,"connectionId":"fixture-connection",
       "lifecycleStatus":"ACTIVE"}
      """;

  @TempDir Path temp;

  @Test
  void addDishDialogAlignsFieldsAndSelectsImageDirectly() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
    var requests = new AtomicInteger();
    ApiClient api = api(request -> {
      requests.incrementAndGet();
      throw new AssertionError("Unexpected request: " + request.uri().getPath());
    });
    Path photo = temp.resolve("dish-256.png");
    ImageIO.write(new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB), "png", photo.toFile());
    var window = new AtomicReference<CanteenFrame>();
    var form = new AtomicReference<JDialog>();
    var preview = new AtomicReference<JButton>();
    var chooser = new AtomicReference<JFileChooser>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        CanteenFrame frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        new MenuDialogs(frame, () -> {}).dish(frame,
            new Api.MenuCategory[] {
              new Api.MenuCategory(1, "Ăn nhẹ", 0, 1),
              new Api.MenuCategory(2, "Đồ uống", 0, 1)
            }, 1, null);
        JDialog dialog = Arrays.stream(frame.getOwnedWindows())
            .filter(JDialog.class::isInstance).map(JDialog.class::cast)
            .filter(d -> "Thêm món".equals(d.getTitle())).findFirst().orElseThrow();
        form.set(dialog);
        var content = dialog.getContentPane();
        assertEquals(new java.awt.Dimension(660, 400), dialog.getSize());
        assertEquals(new java.awt.Dimension(660, 400), dialog.getMinimumSize());
        assertEquals(new java.awt.Insets(16, 16, 16, 16), ((JComponent) content).getInsets());
        JButton photoButton = components(dialog).filter(c -> c instanceof JButton b
            && "Ảnh món ăn".equals(b.getAccessibleContext().getAccessibleName()))
            .map(JButton.class::cast).findFirst().orElseThrow();
        preview.set(photoButton);
        assertEquals(new java.awt.Dimension(256, 256), photoButton.getSize());
        assertEquals(128, photoButton.getIcon().getIconWidth());
        assertEquals(128, photoButton.getIcon().getIconHeight());
        assertEquals(1, photoButton.getInsets().left);
        assertTrue(components(dialog).noneMatch(c -> c instanceof JButton b
            && "Chọn ảnh JPEG/PNG 256 × 256".equals(b.getText())));
        photoButton.getModel().setRollover(false);
        assertEquals(photoButton.getParent().getBackground().getRGB(), painted(photoButton).getRGB(10, 10));
        photoButton.getModel().setRollover(true);
        assertEquals(new java.awt.Color(0xE0E0E0).getRGB(), painted(photoButton).getRGB(10, 10));
        photoButton.getModel().setRollover(false);

        JLabel categoryLabel = label(dialog, "Danh mục");
        JLabel nameLabel = label(dialog, "Tên món");
        JLabel priceLabel = label(dialog, "Giá bán");
        assertNotNull(categoryLabel);
        assertNotNull(nameLabel);
        assertNotNull(priceLabel);
        @SuppressWarnings("unchecked")
        JComboBox<Api.MenuCategory> category = (JComboBox<Api.MenuCategory>) categoryLabel.getLabelFor();
        JTextField name = (JTextField) nameLabel.getLabelFor();
        JTextField price = (JTextField) priceLabel.getLabelFor();
        assertEquals(-1, category.getSelectedIndex());
        assertEquals(2, category.getItemCount());
        assertEquals("Ăn nhẹ", category.getItemAt(0).name());
        assertEquals("Đồ uống", category.getItemAt(1).name());
        JLabel placeholder = (JLabel) category.getRenderer().getListCellRendererComponent(
            new JList<>(), null, -1, false, false);
        assertEquals("Chọn danh mục", placeholder.getText());
        assertEquals(4, category.getMaximumRowCount());
        category.showPopup();
        Object popup = category.getUI().getAccessibleChild(category, 0);
        var categoryList = assertInstanceOf(javax.swing.plaf.basic.ComboPopup.class, popup).getList();
        JScrollPane categoryScroll = (JScrollPane) SwingUtilities.getAncestorOfClass(
            JScrollPane.class, categoryList);
        int rowHeight = categoryList.getCellBounds(0, 0).height;
        assertEquals(2 * rowHeight, categoryScroll.getViewport().getExtentSize().height);
        assertFalse(categoryScroll.getVerticalScrollBar().isVisible());
        category.hidePopup();
        for (int i = 3; i <= 5; i++)
          category.addItem(new Api.MenuCategory(i, "Danh mục " + i, 0, 1));
        assertEquals(-1, category.getSelectedIndex());
        category.showPopup();
        assertEquals(4 * rowHeight, categoryScroll.getViewport().getExtentSize().height);
        assertTrue(categoryScroll.getVerticalScrollBar().isVisible());
        categoryList.ensureIndexIsVisible(4);
        Rectangle lastCategory = categoryList.getCellBounds(4, 4);
        assertTrue(categoryScroll.getViewport().getViewRect().intersects(lastCategory));
        long now = System.currentTimeMillis();
        categoryList.dispatchEvent(new MouseEvent(categoryList, MouseEvent.MOUSE_PRESSED, now,
            InputEvent.BUTTON1_DOWN_MASK, lastCategory.x + 5, lastCategory.y + rowHeight / 2,
            1, false, MouseEvent.BUTTON1));
        categoryList.dispatchEvent(new MouseEvent(categoryList, MouseEvent.MOUSE_RELEASED, now, 0,
            lastCategory.x + 5, lastCategory.y + rowHeight / 2, 1, false, MouseEvent.BUTTON1));
        assertEquals(5, ((Api.MenuCategory) category.getSelectedItem()).id());
        category.hidePopup();
        while (category.getItemCount() > 2) category.removeItemAt(category.getItemCount() - 1);
        category.setSelectedIndex(-1);
        assertEquals("Nhập tên món", name.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT));
        assertEquals("Nhập giá bán", price.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT));
        JLabel currency = (JLabel) price.getClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT);
        assertEquals("VND", currency.getText());
        assertTrue(currency.isShowing());

        Rectangle photoBounds = boundsIn(photoButton, content);
        Rectangle first = boundsIn(categoryLabel, content);
        Rectangle middle = boundsIn(nameLabel, content);
        Rectangle last = boundsIn(priceLabel, content);
        Rectangle firstInput = boundsIn(category, content);
        Rectangle middleInput = boundsIn(name, content);
        Rectangle lastInput = boundsIn(price, content);
        assertEquals(16, photoBounds.x);
        assertEquals(16, photoBounds.y);
        assertEquals(photoBounds.x + photoBounds.width + 16, first.x);
        assertEquals(first.x, middle.x);
        assertEquals(first.x, last.x);
        for (JLabel l : new JLabel[] {categoryLabel, nameLabel, priceLabel}) {
          Rectangle labelBounds = boundsIn(l, content);
          Rectangle inputBounds = boundsIn(l.getLabelFor(), content);
          assertEquals(16, l.getFont().getSize());
          assertEquals(4, inputBounds.y - labelBounds.y - labelBounds.height);
          assertEquals(32, inputBounds.height);
          assertEquals(labelBounds.x, inputBounds.x);
        }
        int topGap = first.y - photoBounds.y;
        int firstGap = middle.y - firstInput.y - firstInput.height;
        int secondGap = last.y - middleInput.y - middleInput.height;
        int bottomGap = photoBounds.y + photoBounds.height - lastInput.y - lastInput.height;
        String gaps = "top=" + topGap + ", between=" + firstGap + "/" + secondGap + ", bottom=" + bottomGap;
        assertTrue(topGap > 0, gaps);
        assertTrue(Math.abs(topGap - bottomGap) <= 1, gaps);
        assertTrue(Math.abs(firstGap - secondGap) <= 1, gaps);
        assertTrue(Math.abs(firstGap - 2 * topGap) <= 2, gaps);
        assertTrue(Math.abs(secondGap - 2 * bottomGap) <= 2, gaps);
        JButton cancel = button(dialog, "Hủy"), save = button(dialog, "Tạo món");
        assertEquals(new java.awt.Dimension(120, 30), cancel.getSize());
        assertEquals(new java.awt.Dimension(120, 30), save.getSize());
        Rectangle cancelBounds = boundsIn(cancel, content), saveBounds = boundsIn(save, content);
        assertEquals(8, saveBounds.x - cancelBounds.x - cancelBounds.width);
        assertEquals(content.getWidth() - 16, saveBounds.x + saveBounds.width);
        captureReconciliation(dialog, "add-dish-default.png");

        name.setText("Cơm gà");
        price.setText("35000");
        assertEquals("35.000", price.getText());
        assertSame(currency, price.getClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT));
        save.doClick(0);
        assertTrue(showingLabel(dialog, CanteenFrame.html("Vui lòng chọn danh mục.")));
        assertEquals(0, requests.get());
        category.setSelectedIndex(1);
        assertEquals("Đồ uống", category.getSelectedItem().toString());
      });

      SwingUtilities.invokeLater(() -> preview.get().doClick(0));
      awaitUi(() -> {
        for (Window candidate : Window.getWindows()) {
          if (candidate instanceof JDialog dialog && dialog.isShowing()
              && "Chọn ảnh món 256 × 256".equals(dialog.getTitle())) {
            chooser.set(components(dialog).filter(JFileChooser.class::isInstance)
                .map(JFileChooser.class::cast).findFirst().orElse(null));
            return chooser.get() != null;
          }
        }
        return false;
      });
      SwingUtilities.invokeAndWait(() -> {
        chooser.get().setSelectedFile(photo.toAbsolutePath().toFile());
        chooser.get().approveSelection();
      });
      awaitUi(() -> "Ảnh món ăn đã chọn".equals(
          preview.get().getAccessibleContext().getAccessibleDescription()));
      SwingUtilities.invokeAndWait(() -> {
        assertInstanceOf(ImageIcon.class, preview.get().getIcon());
        assertEquals(256, preview.get().getIcon().getIconWidth());
        assertEquals(256, preview.get().getIcon().getIconHeight());
        captureReconciliation(form.get(), "add-dish-selected.png");
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void catalogDialogCombinesFiltersSortsAndShowsFourScrollableRows() throws Exception {
    var searchQueries = new CopyOnWriteArrayList<String>();
    var catalogViewportWidth = new AtomicInteger();
    var catalogColumnWidths = new AtomicReference<int[]>();
    var imageBytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB), "png", imageBytes);
    String dishImage = Base64.getEncoder().encodeToString(imageBytes.toByteArray());
    String dishes = """
          [{"id":1,"name":"Cơm gà","category":"Cơm","price":35000,"available":true,"categoryId":1,"revision":1,"hasImage":false},
           {"id":2,"name":"Cơm sườn","category":"Cơm","price":45000,"available":true,"categoryId":1,"revision":1,"hasImage":false},
           {"id":3,"name":"Nước suối","category":"Đồ uống","price":10000,"available":true,"categoryId":2,"revision":1,"hasImage":false},
           {"id":4,"name":"Cơm gà cay","category":"Cơm","price":50000,"available":true,"categoryId":1,"revision":1,"hasImage":true},
           {"id":5,"name":"Cơm gà canh","category":"Cơm","price":30000,"available":true,"categoryId":1,"revision":1,"hasImage":false}]
          """;
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/menu" -> dishes;
      case "/api/menu/search" -> {
        searchQueries.add(request.uri().getRawQuery());
        yield "{\"items\":" + dishes + ",\"total\":5}";
      }
      case "/api/menu/categories" -> """
          [{"id":1,"name":"Cơm","itemCount":4,"revision":1},
           {"id":2,"name":"Đồ uống","itemCount":1,"revision":1}]
          """;
      case "/api/menu/sales" -> """
          [{"itemId":1,"soldQuantity":10},{"itemId":2,"soldQuantity":1},
           {"itemId":3,"soldQuantity":50},{"itemId":4,"soldQuantity":3},
           {"itemId":5,"soldQuantity":10}]
          """;
      case "/api/menu/items/4/image" ->
          "{\"base64\":\"" + dishImage + "\",\"mime\":\"image/png\",\"revision\":1}";
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        button(frame.seller, "Quản lý thực đơn").doClick(0);
      });
      awaitIdle(window.get());
      awaitUi(() -> {
        var dialog = field(window.get().seller, "catalogDialog", MenuCatalogDialog.class);
        return field(dialog, "table", JTable.class).getValueAt(1, 0) instanceof ImageIcon;
      });
      awaitUi(() -> sellerDish(window.get().seller, "Cơm gà cay").getIcon()
          != field(window.get().seller, "images", DishImages.class).placeholder(80));
      SwingUtilities.invokeAndWait(() -> {
        var dialog = field(window.get().seller, "catalogDialog", MenuCatalogDialog.class);
        assertEquals("Danh sách món ăn", dialog.getTitle());
        assertEquals(800, dialog.getWidth());
        var table = field(dialog, "table", JTable.class);
        var scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
        assertEquals(5, table.getRowCount());
        assertEquals(4 * table.getRowHeight(), scroll.getViewport().getExtentSize().height);
        assertEquals(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, scroll.getVerticalScrollBarPolicy());
        assertTrue(scroll.getVerticalScrollBar().isVisible());
        assertEquals(8, scroll.getVerticalScrollBar().getWidth());
        catalogViewportWidth.set(scroll.getViewport().getWidth());
        catalogColumnWidths.set(java.util.stream.IntStream.range(0, table.getColumnCount())
            .map(column -> table.getColumnModel().getColumn(column).getWidth()).toArray());
        scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
        assertTrue(scroll.getViewport().getViewPosition().y > 0);
        scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMinimum());
        assertEquals(40, table.getTableHeader().getHeight());
        assertEquals(5, table.getColumnCount());
        assertNeutralTableSelection(table, 0);
        assertTrue(table.editCellAt(0, 4));
        assertEquals(table.getBackground(), table.getEditorComponent().getBackground());
        table.getCellEditor().cancelCellEditing();
        ImageIcon image = (ImageIcon) table.getValueAt(1, 0);
        assertEquals(64, image.getIconWidth());
        assertEquals(64, image.getIconHeight());
        ImageIcon placeholder = (ImageIcon) table.getValueAt(0, 0);
        assertNotNull(placeholder);
        assertEquals(64, placeholder.getIconWidth());
        assertEquals(64, placeholder.getIconHeight());
        assertNotSame(image, placeholder);
        assertSame(placeholder, table.getValueAt(2, 0));
        JPanel menu = field(window.get().seller, "menu", JPanel.class);
        JButton missingPhoto = components(menu).filter(c -> c instanceof JButton b
            && b.getText() != null && b.getText().contains("Nước suối"))
            .map(JButton.class::cast).findFirst().orElseThrow();
        assertEquals(80, missingPhoto.getIcon().getIconWidth());
        assertEquals(80, missingPhoto.getIcon().getIconHeight());
        assertEquals(96, missingPhoto.getPreferredSize().height);
        JButton withPhoto = sellerDish(window.get().seller, "Cơm gà cay");
        assertEquals(80, withPhoto.getIcon().getIconWidth());
        assertEquals(80, withPhoto.getIcon().getIconHeight());
        JLabel imageCell = (JLabel) table.prepareRenderer(table.getCellRenderer(1, 0), 1, 0);
        assertEquals(SwingConstants.CENTER, imageCell.getHorizontalAlignment());
        assertEquals(SwingConstants.CENTER, imageCell.getVerticalAlignment());
        assertCatalogColumns(table);
        JLabel nameCell = (JLabel) table.prepareRenderer(table.getCellRenderer(0, 1), 0, 1);
        assertEquals(SwingConstants.CENTER, nameCell.getHorizontalAlignment());
        assertEquals(table.getGridColor().getRGB(), painted(table).getRGB(table.getWidth() - 20, 79));
        assertEquals(new java.awt.Dimension(320, 32), field(dialog, "search", JTextField.class).getSize());
        assertEquals(new java.awt.Dimension(320, 32), field(dialog, "categories", JComboBox.class).getSize());
        var sortControl = field(dialog, "sort", JComboBox.class);
        assertEquals(new java.awt.Dimension(160, 32), sortControl.getSize());
        assertEquals("Bán chạy", sortControl.getItemAt(1));
        Rectangle sortBounds = boundsIn(sortControl, dialog.getContentPane());
        Rectangle sortLabelBounds = boundsIn(label(dialog, "Sắp xếp"), dialog.getContentPane());
        assertEquals(dialog.getContentPane().getWidth() - 16, sortBounds.x + sortBounds.width);
        assertEquals(8, sortBounds.x - sortLabelBounds.x - sortLabelBounds.width);
        for (String text : List.of("Thêm món ăn", "Thêm danh mục", "Xóa danh mục"))
          assertEquals(new java.awt.Dimension(160, 32), button(dialog, text).getSize());
        assertEquals(new java.awt.Dimension(120, 30), button(dialog, "Đóng").getSize());
        assertEquals("Cơm gà canh", table.getValueAt(0, 1));
        assertEquals("Cơm gà", table.getValueAt(4, 1));
        assertNotNull(button(dialog, "Thêm danh mục"));
        assertNotNull(button(dialog, "Xóa danh mục"));
        assertNotNull(button(dialog, "Thêm món ăn"));
        captureReconciliation(dialog, "menu-catalog-populated.png");
        var sort = field(dialog, "sort", JComboBox.class);
        sort.setSelectedIndex(1);
        assertEquals("Nước suối", table.getValueAt(0, 1));
        assertEquals("Cơm gà canh", table.getValueAt(1, 1));
        sort.setSelectedIndex(2);
        assertEquals("Cơm gà cay", table.getValueAt(0, 1));
        var category = field(dialog, "categories", JComboBox.class);
        category.setSelectedIndex(1);
        field(dialog, "search", JTextField.class).setText("COM");
      });
      Thread.sleep(180);
      SwingUtilities.invokeAndWait(() -> {
        var dialog = field(window.get().seller, "catalogDialog", MenuCatalogDialog.class);
        field(dialog, "search", JTextField.class).setText("COM GA");
      });
      Thread.sleep(250);
      assertTrue(searchQueries.isEmpty(), "Search must wait 500 ms after the last edit");
      awaitUi(() -> {
        var dialog = field(window.get().seller, "catalogDialog", MenuCatalogDialog.class);
        return field(dialog, "table", JTable.class).getRowCount() == 3;
      });
      assertEquals(List.of("query=COM+GA"), searchQueries);
      SwingUtilities.invokeAndWait(() -> {
        var dialog = field(window.get().seller, "catalogDialog", MenuCatalogDialog.class);
        var table = field(dialog, "table", JTable.class);
        var sort = field(dialog, "sort", JComboBox.class);
        var category = field(dialog, "categories", JComboBox.class);
        assertEquals(3, table.getRowCount());
        assertEquals("Cơm gà cay", table.getValueAt(0, 1));
        sort.setSelectedIndex(1);
        assertEquals("Cơm gà canh", table.getValueAt(0, 1));
        assertEquals("Cơm gà", table.getValueAt(1, 1));
        category.setSelectedIndex(2);
        assertEquals(0, table.getRowCount());
        assertTrue(showingLabel(dialog, "Đã hiển thị 0 trên tổng số 5 món ăn."));
        captureReconciliation(dialog, "menu-catalog.png");
      });
      awaitUi(() -> {
        var dialog = field(window.get().seller, "catalogDialog", MenuCatalogDialog.class);
        JTable table = field(dialog, "table", JTable.class);
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
        return scroll.getVerticalScrollBar().isVisible() && table.getWidth() == scroll.getViewport().getWidth();
      });
      SwingUtilities.invokeAndWait(() -> {
        var dialog = field(window.get().seller, "catalogDialog", MenuCatalogDialog.class);
        JTable table = field(dialog, "table", JTable.class);
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
        assertEquals(8, scroll.getVerticalScrollBar().getWidth());
        assertEquals(scroll.getVerticalScrollBar().getMinimum(),
            scroll.getVerticalScrollBar().getMaximum() - scroll.getVerticalScrollBar().getVisibleAmount());
        assertEquals(catalogViewportWidth.get(), scroll.getViewport().getWidth());
        assertArrayEquals(catalogColumnWidths.get(), java.util.stream.IntStream.range(0, table.getColumnCount())
            .map(column -> table.getColumnModel().getColumn(column).getWidth()).toArray());
        assertCatalogColumns(table);
        var category = field(dialog, "categories", JComboBox.class);
        category.setSelectedIndex(0);
        field(dialog, "search", JTextField.class).setText("");
      });
      awaitUi(() -> searchQueries.size() == 2);
      awaitUi(() -> {
        var dialog = field(window.get().seller, "catalogDialog", MenuCatalogDialog.class);
        return field(dialog, "table", JTable.class).getRowCount() == 5;
      });
      assertEquals("query=", searchQueries.get(1));
      SwingUtilities.invokeAndWait(() -> {
        var dialog = field(window.get().seller, "catalogDialog", MenuCatalogDialog.class);
        var table = field(dialog, "table", JTable.class);
        var sort = field(dialog, "sort", JComboBox.class);
        dialog.invalidateSales();
        assertEquals(0, table.getRowCount());
        assertTrue(showingLabel(dialog,
            "Chưa tải được số lượng bán. Hãy đóng và mở lại danh sách."));
        sort.setSelectedIndex(0);
        assertEquals(5, table.getRowCount());
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void catalogEditOpensPrefilledDishFormAndSubmitsReplacementImage() throws Exception {
    var original = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB), "png", original);
    String encoded = Base64.getEncoder().encodeToString(original.toByteArray());
    BufferedImage replacement = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
    replacement.setRGB(0, 0, 0xff0000);
    Path photo = temp.resolve("replacement.png");
    ImageIO.write(replacement, "png", photo.toFile());
    ObjectMapper json = new ObjectMapper();
    var submitted = new AtomicReference<JsonNode>();
    var deleted = new AtomicReference<JsonNode>();
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/menu/items/1/image" ->
          "{\"base64\":\"" + encoded + "\",\"mime\":\"image/png\",\"revision\":1}";
      case "/api/menu/items/1/update" -> {
        submitted.set(requestJson(json, request));
        yield "{\"id\":1,\"name\":\"Món đã sửa\",\"category\":\"Cơm\",\"price\":45000,"
            + "\"available\":true,\"categoryId\":1,\"revision\":2,\"hasImage\":true}";
      }
      case "/api/menu/items/1/delete" -> {
        deleted.set(requestJson(json, request));
        yield "{\"message\":\"Đã xóa món.\"}";
      }
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    var catalog = new AtomicReference<MenuCatalogDialog>();
    var form = new AtomicReference<JDialog>();
    var photoButton = new AtomicReference<JButton>();
    var chooser = new AtomicReference<JFileChooser>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        CanteenFrame frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        MenuCatalogDialog dialog = new MenuCatalogDialog(frame, new DishImages(),
            new MenuDialogs(frame, () -> {}), () -> {});
        catalog.set(dialog);
        dialog.setCatalog(new Api.MenuItem[] {
            new Api.MenuItem(1, "Cơm gà", "Cơm", 35000, true, 1, 1, true)},
            new Api.MenuCategory[] {new Api.MenuCategory(1, "Cơm", 1, 1)}, new Api.MenuSales[0]);
        dialog.setVisible(true);
      });
      SwingUtilities.invokeLater(() -> {
        JTable table = field(catalog.get(), "table", JTable.class);
        Rectangle cell = table.getCellRect(0, 4, true);
        int x = cell.x + (cell.width - 72) / 2 + 16, y = cell.y + cell.height / 2;
        long now = System.currentTimeMillis();
        table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_PRESSED, now,
            InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_RELEASED, now + 1,
            0, x, y, 1, false, MouseEvent.BUTTON1));
      });
      awaitUi(() -> {
        form.set(ownedDialog(catalog.get(), "Sửa món"));
        return form.get() != null;
      });
      SwingUtilities.invokeAndWait(() -> {
        JDialog dialog = form.get();
        assertEquals(new java.awt.Dimension(660, 400), dialog.getSize());
        assertEquals("Cơm gà", ((JTextField) label(dialog, "Tên món").getLabelFor()).getText());
        assertEquals("35.000", ((JTextField) label(dialog, "Giá bán").getLabelFor()).getText());
        JComboBox<?> categories = (JComboBox<?>) label(dialog, "Danh mục").getLabelFor();
        assertEquals("Cơm", categories.getSelectedItem().toString());
        assertNotNull(button(dialog, "Hủy"));
        assertNotNull(button(dialog, "Lưu"));
        photoButton.set(components(dialog).filter(c -> c instanceof JButton b
            && "Ảnh món ăn".equals(b.getAccessibleContext().getAccessibleName()))
            .map(JButton.class::cast).findFirst().orElseThrow());
      });
      awaitUi(() -> "Ảnh món ăn hiện tại".equals(
          photoButton.get().getAccessibleContext().getAccessibleDescription()));
      SwingUtilities.invokeAndWait(() -> {
        photoButton.get().getModel().setRollover(false);
        int before = painted(photoButton.get()).getRGB(10, 10);
        photoButton.get().getModel().setRollover(true);
        assertNotEquals(before, painted(photoButton.get()).getRGB(10, 10));
        photoButton.get().getModel().setRollover(false);
      });
      SwingUtilities.invokeLater(() -> photoButton.get().doClick(0));
      awaitUi(() -> {
        for (Window candidate : Window.getWindows()) {
          if (candidate instanceof JDialog dialog && dialog.isShowing()
              && "Chọn ảnh món 256 × 256".equals(dialog.getTitle())) {
            chooser.set(components(dialog).filter(JFileChooser.class::isInstance)
                .map(JFileChooser.class::cast).findFirst().orElse(null));
            return chooser.get() != null;
          }
        }
        return false;
      });
      SwingUtilities.invokeAndWait(() -> {
        chooser.get().setSelectedFile(photo.toFile());
        chooser.get().approveSelection();
      });
      awaitUi(() -> "Ảnh món ăn đã chọn".equals(
          photoButton.get().getAccessibleContext().getAccessibleDescription()));
      SwingUtilities.invokeAndWait(() -> {
        ((JTextField) label(form.get(), "Tên món").getLabelFor()).setText("Món đã sửa");
        ((JTextField) label(form.get(), "Giá bán").getLabelFor()).setText("45000");
        button(form.get(), "Lưu").doClick(0);
      });
      awaitUi(() -> submitted.get() != null && !window.get().isBusy());
      assertEquals("Món đã sửa", submitted.get().path("name").asText());
      assertEquals(45000, submitted.get().path("price").asLong());
      assertEquals(1, submitted.get().path("revision").asLong());
      byte[] image = Base64.getDecoder().decode(submitted.get().path("imageBase64").asText());
      assertEquals(0xff0000, ImageIO.read(new java.io.ByteArrayInputStream(image)).getRGB(0, 0) & 0xffffff);
      SwingUtilities.invokeAndWait(() -> catalog.get().setCatalog(new Api.MenuItem[] {
          new Api.MenuItem(1, "Món đã sửa", "Cơm", 45000, true, 1, 2, false)},
          new Api.MenuCategory[] {new Api.MenuCategory(1, "Cơm", 1, 1)}, new Api.MenuSales[0]));
      SwingUtilities.invokeLater(() -> {
        JTable table = field(catalog.get(), "table", JTable.class);
        Rectangle cell = table.getCellRect(0, 4, true);
        int x = cell.x + (cell.width - 72) / 2 + 56, y = cell.y + cell.height / 2;
        long now = System.currentTimeMillis();
        table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_PRESSED, now,
            InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_RELEASED, now + 1,
            0, x, y, 1, false, MouseEvent.BUTTON1));
      });
      awaitUi(() -> ownedDialog(catalog.get(), "Xóa món") != null);
      SwingUtilities.invokeAndWait(() -> button(ownedDialog(catalog.get(), "Xóa món"), "Xóa món").doClick(0));
      awaitUi(() -> deleted.get() != null && !window.get().isBusy());
      assertEquals(2, deleted.get().path("revision").asLong());
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void catalogSearchDiscardsLateResultsAndStopsWhenClosed() throws Exception {
    var firstStarted = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var searches = new CopyOnWriteArrayList<String>();
    ApiClient api = api(request -> {
      String query = request.uri().getRawQuery();
      searches.add(query);
      if ("query=C%C6%A1m".equals(query)) {
        firstStarted.countDown();
        boolean released = false;
        while (!released) {
          try { releaseFirst.await(); released = true; }
          catch (InterruptedException ignored) { /* Simulate a server response that cannot be cancelled. */ }
        }
        return "{\"total\":2,\"items\":[{\"id\":1,\"name\":\"Cơm\",\"category\":\"Món\",\"price\":10000,"
            + "\"available\":true,\"categoryId\":1,\"revision\":1,\"hasImage\":false}]}";
      }
      return "{\"total\":3,\"items\":[{\"id\":2,\"name\":\"Trà mới\",\"category\":\"Món\",\"price\":20000,"
          + "\"available\":true,\"categoryId\":1,\"revision\":2,\"hasImage\":false}]}";
    });
    var window = new AtomicReference<CanteenFrame>();
    var catalog = new AtomicReference<MenuCatalogDialog>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        CanteenFrame frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        MenuCatalogDialog dialog = new MenuCatalogDialog(frame, new DishImages(),
            new MenuDialogs(frame, () -> {}), () -> {});
        catalog.set(dialog);
        dialog.setCatalog(new Api.MenuItem[] {
            new Api.MenuItem(1, "Cơm", "Món", 10000, true, 1, 1, false),
            new Api.MenuItem(2, "Trà", "Món", 20000, true, 1, 1, false)},
            new Api.MenuCategory[] {new Api.MenuCategory(1, "Món", 2, 1)}, new Api.MenuSales[0]);
        dialog.setVisible(true);
        field(dialog, "search", JTextField.class).setText("Cơm");
        assertTrue(field(dialog, "results", JLabel.class).getText().isBlank());
      });
      assertTrue(firstStarted.await(3, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(field(catalog.get(), "results", JLabel.class).getText().isBlank());
        field(catalog.get(), "search", JTextField.class).setText("Trà");
      });
      awaitUi(() -> {
        JTable table = field(catalog.get(), "table", JTable.class);
        return table.getRowCount() == 1 && "Trà mới".equals(table.getValueAt(0, 1));
      });
      assertEquals(List.of("query=C%C6%A1m", "query=Tr%C3%A0"), searches);
      releaseFirst.countDown();
      Thread.sleep(150);
      SwingUtilities.invokeAndWait(() -> {
        JTable table = field(catalog.get(), "table", JTable.class);
        assertEquals("Trà mới", table.getValueAt(0, 1));
        assertTrue(showingLabel(catalog.get(), "Đã hiển thị 1 trên tổng số 3 món ăn."));
        field(catalog.get(), "search", JTextField.class).setText("Chưa gửi");
        catalog.get().dispose();
      });
      Thread.sleep(650);
      assertEquals(2, searches.size(), "Closing the dialog must cancel its pending debounce");
    } finally {
      releaseFirst.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void categoryDeletionConfirmsOnlyWhenItContainsDishes() throws Exception {
    var previews = new AtomicInteger();
    var deletes = new AtomicInteger();
    var creates = new AtomicInteger();
    var json = new ObjectMapper();
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/menu" -> "[]";
      case "/api/menu/categories" -> {
        if ("POST".equals(request.method())) {
          if ("Trùng".equals(requestJson(json, request).path("name").asText()))
            throw new ApiClient.ApiError(409, "CATEGORY_EXISTS", "Tên danh mục đã tồn tại.");
          creates.incrementAndGet();
          yield "{\"id\":3,\"name\":\"Mới\",\"itemCount\":0,\"revision\":1}";
        }
        yield """
            [{"id":1,"name":"Có món","itemCount":2,"revision":7},
             {"id":2,"name":"Trống","itemCount":0,"revision":3}]
            """;
      }
      case "/api/menu/sales" -> "[]";
      case "/api/menu/categories/1/delete-preview" -> {
        previews.incrementAndGet();
        yield "{\"id\":1,\"name\":\"Có món\",\"itemCount\":2,\"revision\":7}";
      }
      case "/api/menu/categories/2/delete-preview" -> {
        previews.incrementAndGet();
        yield "{\"id\":2,\"name\":\"Trống\",\"itemCount\":0,\"revision\":3}";
      }
      case "/api/menu/categories/1/delete", "/api/menu/categories/2/delete" -> {
        deletes.incrementAndGet();
        yield "{\"message\":\"Đã xóa\"}";
      }
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        button(frame.seller, "Quản lý thực đơn").doClick(0);
      });
      awaitIdle(window.get());
      var catalog = field(window.get().seller, "catalogDialog", MenuCatalogDialog.class);

      SwingUtilities.invokeLater(() -> button(catalog, "Xóa danh mục").doClick(0));
      awaitUi(() -> ownedDialog(catalog, "Xóa danh mục") != null);
      SwingUtilities.invokeAndWait(() -> {
        JDialog prompt = ownedDialog(catalog, "Xóa danh mục");
        assertEquals(360, prompt.getWidth());
        assertEquals(prompt.getPreferredSize().height, prompt.getHeight());
        assertFalse(prompt.isResizable());
        var content = prompt.getContentPane();
        assertEquals(new java.awt.Insets(16, 16, 16, 16), ((JComponent) content).getInsets());
        JLabel selectLabel = label(prompt, "Chọn danh mục cần xóa");
        assertNotNull(selectLabel);
        @SuppressWarnings("unchecked")
        JComboBox<Api.MenuCategory> choice = (JComboBox<Api.MenuCategory>) selectLabel.getLabelFor();
        Rectangle labelBounds = boundsIn(selectLabel, content), choiceBounds = boundsIn(choice, content);
        assertEquals(16, labelBounds.y);
        assertEquals(32, choiceBounds.height);
        assertEquals(content.getWidth() - 32, choiceBounds.width);
        assertEquals(4, choiceBounds.y - labelBounds.y - labelBounds.height);
        JButton cancel = button(prompt, "Cancel"), ok = button(prompt, "OK");
        assertEquals(new java.awt.Dimension(90, 30), cancel.getSize());
        assertEquals(new java.awt.Dimension(90, 30), ok.getSize());
        Rectangle cancelBounds = boundsIn(cancel, content), okBounds = boundsIn(ok, content);
        assertEquals(8, okBounds.x - cancelBounds.x - cancelBounds.width);
        assertEquals(content.getWidth() - 16, okBounds.x + okBounds.width);
        assertEquals(content.getHeight() - 16, okBounds.y + okBounds.height);
        assertEquals(16, okBounds.y - choiceBounds.y - choiceBounds.height);
        for (int count : new int[] {1, 2, 3, 4, 5}) {
          choice.removeAllItems();
          for (int i = 0; i < count; i++)
            choice.addItem(new Api.MenuCategory(i + 1, "Danh mục " + (i + 1), 0, 1));
          choice.setSelectedIndex(0);
          choice.showPopup();
          Object popup = choice.getUI().getAccessibleChild(choice, 0);
          var list = assertInstanceOf(javax.swing.plaf.basic.ComboPopup.class, popup).getList();
          JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, list);
          assertEquals(count, list.getModel().getSize());
          assertEquals(4 * 32, scroll.getViewport().getExtentSize().height);
          assertEquals(count > 4, scroll.getVerticalScrollBar().isVisible());
          for (int i = 0; i < count; i++) assertEquals(32, list.getCellBounds(i, i).height);
          if (count == 2) {
            JViewport viewport = scroll.getViewport();
            Component blank = SwingUtilities.getDeepestComponentAt(viewport, 5, 2 * 32 + 8);
            assertNotNull(blank);
            var point = SwingUtilities.convertPoint(viewport, 5, 2 * 32 + 8, blank);
            long now = System.currentTimeMillis();
            blank.dispatchEvent(new MouseEvent(blank, MouseEvent.MOUSE_MOVED, now, 0,
                point.x, point.y, 0, false, MouseEvent.NOBUTTON));
            blank.dispatchEvent(new MouseEvent(blank, MouseEvent.MOUSE_PRESSED, now,
                InputEvent.BUTTON1_DOWN_MASK, point.x, point.y, 1, false, MouseEvent.BUTTON1));
            blank.dispatchEvent(new MouseEvent(blank, MouseEvent.MOUSE_RELEASED, now, 0,
                point.x, point.y, 1, false, MouseEvent.BUTTON1));
            assertEquals(0, choice.getSelectedIndex(), "An empty row must not select a category");
          }
          if (count == 5) {
            list.ensureIndexIsVisible(4);
            Rectangle last = list.getCellBounds(4, 4);
            assertTrue(scroll.getViewport().getViewRect().intersects(last));
            long now = System.currentTimeMillis();
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_PRESSED, now,
                InputEvent.BUTTON1_DOWN_MASK, last.x + 5, last.y + 16, 1, false, MouseEvent.BUTTON1));
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, now, 0,
                last.x + 5, last.y + 16, 1, false, MouseEvent.BUTTON1));
            assertEquals(4, choice.getSelectedIndex());
          }
          choice.hidePopup();
        }
        assertTrue(components(content).filter(JLabel.class::isInstance).map(JLabel.class::cast)
            .noneMatch(l -> l.getIcon() != null));
        captureReconciliation(prompt, "category-delete.png");
        cancel.doClick(0);
      });
      awaitUi(() -> ownedDialog(catalog, "Xóa danh mục") == null);
      assertEquals(0, previews.get());
      assertEquals(0, deletes.get());

      SwingUtilities.invokeLater(() -> button(catalog, "Xóa danh mục").doClick(0));
      awaitUi(() -> ownedDialog(catalog, "Xóa danh mục") != null);
      SwingUtilities.invokeAndWait(() -> {
        JDialog prompt = ownedDialog(catalog, "Xóa danh mục");
        JComboBox<?> choice = components(prompt).filter(JComboBox.class::isInstance)
            .map(JComboBox.class::cast).findFirst().orElseThrow();
        choice.setSelectedIndex(1);
        button(prompt, "OK").doClick(0);
      });
      awaitUi(() -> deletes.get() == 1 && !window.get().isBusy());
      assertEquals(1, previews.get());

      SwingUtilities.invokeLater(() -> button(catalog, "Xóa danh mục").doClick(0));
      awaitUi(() -> ownedDialog(catalog, "Xóa danh mục") != null);
      SwingUtilities.invokeAndWait(() -> button(ownedDialog(catalog, "Xóa danh mục"), "OK").doClick(0));
      awaitUi(() -> ownedDialog(catalog, "Xác nhận xóa danh mục") != null);
      SwingUtilities.invokeAndWait(() -> {
        JDialog confirmation = ownedDialog(catalog, "Xác nhận xóa danh mục");
        assertTrue(showingLabel(confirmation, "Danh mục “Có món” hiện có 2 món, tiếp tục xoá?"));
        JButton cancel = button(confirmation, "Huỷ bỏ"), delete = button(confirmation, "Xoá");
        assertEquals(new java.awt.Dimension(90, 30), cancel.getSize());
        assertEquals(new java.awt.Dimension(90, 30), delete.getSize());
        assertSame(cancel, confirmation.getRootPane().getDefaultButton());
        captureReconciliation(confirmation, "category-delete-confirm.png");
        cancel.doClick(0);
      });
      awaitUi(() -> ownedDialog(catalog, "Xác nhận xóa danh mục") == null);
      assertEquals(2, previews.get());
      assertEquals(1, deletes.get());

      SwingUtilities.invokeLater(() -> button(catalog, "Xóa danh mục").doClick(0));
      awaitUi(() -> ownedDialog(catalog, "Xóa danh mục") != null);
      SwingUtilities.invokeAndWait(() -> button(ownedDialog(catalog, "Xóa danh mục"), "OK").doClick(0));
      awaitUi(() -> ownedDialog(catalog, "Xác nhận xóa danh mục") != null);
      SwingUtilities.invokeAndWait(() -> button(ownedDialog(catalog, "Xác nhận xóa danh mục"), "Xoá").doClick(0));
      awaitUi(() -> deletes.get() == 2 && !window.get().isBusy());
      assertEquals(3, previews.get());

      SwingUtilities.invokeLater(() -> button(catalog, "Thêm danh mục").doClick(0));
      awaitUi(() -> ownedDialog(catalog, "Thêm danh mục") != null);
      SwingUtilities.invokeAndWait(() -> {
        JDialog form = ownedDialog(catalog, "Thêm danh mục");
        assertEquals(1, components(form).filter(JTextField.class::isInstance).count());
        assertEquals(360, form.getWidth());
        assertEquals(form.getPreferredSize().height, form.getHeight());
        assertFalse(form.isResizable());
        var content = form.getContentPane();
        assertEquals(new java.awt.Insets(16, 16, 16, 16), ((JComponent) content).getInsets());
        JLabel nameLabel = label(form, "Tên danh mục");
        assertNotNull(nameLabel);
        JTextField input = (JTextField) nameLabel.getLabelFor();
        assertEquals("Nhập tên danh mục", input.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT));
        Rectangle nameBounds = boundsIn(nameLabel, content), inputBounds = boundsIn(input, content);
        assertEquals(16, nameBounds.y);
        assertEquals(4, inputBounds.y - nameBounds.y - nameBounds.height);
        assertEquals(32, inputBounds.height);
        assertEquals(content.getWidth() - 32, inputBounds.width);
        JLabel error = components(content).filter(JLabel.class::isInstance).map(JLabel.class::cast)
            .filter(l -> l.getForeground().equals(new java.awt.Color(0xC62828))).findFirst().orElseThrow();
        Rectangle errorBounds = boundsIn(error, content);
        assertEquals(" ", error.getText());
        assertEquals(22, errorBounds.height);
        assertEquals(12, errorBounds.y - inputBounds.y - inputBounds.height);
        JButton cancel = button(form, "Hủy"), save = button(form, "Tạo danh mục");
        assertEquals(new java.awt.Dimension(120, 30), cancel.getSize());
        assertEquals(new java.awt.Dimension(150, 30), save.getSize());
        Rectangle cancelBounds = boundsIn(cancel, content), saveBounds = boundsIn(save, content);
        assertEquals(8, saveBounds.x - cancelBounds.x - cancelBounds.width);
        assertEquals(content.getWidth() - 16, saveBounds.x + saveBounds.width);
        assertEquals(content.getHeight() - 16, saveBounds.y + saveBounds.height);
        assertEquals(12, saveBounds.y - errorBounds.y - errorBounds.height);
        captureReconciliation(form, "category-add.png");
        button(form, "Hủy").doClick(0);
      });
      awaitUi(() -> ownedDialog(catalog, "Thêm danh mục") == null);
      assertEquals(0, creates.get());

      SwingUtilities.invokeLater(() -> button(catalog, "Thêm danh mục").doClick(0));
      awaitUi(() -> ownedDialog(catalog, "Thêm danh mục") != null);
      SwingUtilities.invokeAndWait(() -> {
        JDialog form = ownedDialog(catalog, "Thêm danh mục");
        components(form).filter(JTextField.class::isInstance).map(JTextField.class::cast)
            .findFirst().orElseThrow().setText("Trùng");
        button(form, "Tạo danh mục").doClick(0);
      });
      awaitUi(() -> !window.get().isBusy() && showingLabel(ownedDialog(catalog, "Thêm danh mục"),
          CanteenFrame.html("Tên danh mục đã tồn tại.")));
      SwingUtilities.invokeAndWait(() -> {
        JDialog form = ownedDialog(catalog, "Thêm danh mục");
        JLabel error = label(form, CanteenFrame.html("Tên danh mục đã tồn tại."));
        assertEquals(new java.awt.Color(0xC62828), error.getForeground());
        captureReconciliation(form, "category-add-error.png");
        JTextField name = components(form).filter(JTextField.class::isInstance)
            .map(JTextField.class::cast).findFirst().orElseThrow();
        assertEquals("Trùng", name.getText());
        name.setText("Mới");
        button(form, "Tạo danh mục").doClick(0);
      });
      awaitUi(() -> creates.get() == 1 && !window.get().isBusy()
          && ownedDialog(catalog, "Thêm danh mục") == null);

      SwingUtilities.invokeLater(() -> field(catalog, "forms", MenuDialogs.class).dish(catalog,
          new Api.MenuCategory[] {new Api.MenuCategory(1, "Có món", 2, 7)}, 1,
          new Api.MenuItem(1, "Cơm gà", "Có món", 35000, true, 1, 1, false)));
      awaitUi(() -> ownedDialog(catalog, "Sửa món") != null);
      SwingUtilities.invokeAndWait(() -> {
        JDialog edit = ownedDialog(catalog, "Sửa món");
        assertNotNull(label(edit, "Tên món"));
        assertNull(label(edit, "Tên món (1–120 ký tự)"));
        edit.dispose();
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  private static JDialog ownedDialog(Window owner, String title) {
    return Arrays.stream(owner.getOwnedWindows()).filter(JDialog.class::isInstance)
        .map(JDialog.class::cast).filter(d -> d.isShowing() && title.equals(d.getTitle()))
        .findFirst().orElse(null);
  }

  private static JOptionPane optionPane(JDialog dialog) {
    return components(dialog).filter(JOptionPane.class::isInstance)
        .map(JOptionPane.class::cast).findFirst().orElseThrow();
  }

  @Test
  void checkoutMenuFiltersRowsAndKeepsInvoiceSelectionConsistent() throws Exception {
    var json = new ObjectMapper();
    var queries = new CopyOnWriteArrayList<String>();
    var created = new AtomicReference<JsonNode>();
    Api.MenuItem rice = new Api.MenuItem(1, "Cơm gà", "Cơm", 35000, true, 1, 1, false);
    Api.MenuItem water = new Api.MenuItem(2, "Nước suối", "Đồ uống", 10000, true, 2, 1, false);
    Api.MenuItem egg = new Api.MenuItem(3, "Cơm trứng", "Cơm", 25000, true, 1, 1, false);
    Api.MenuItem tea = new Api.MenuItem(4, "Trà mới", "Đồ uống", 20000, true, 2, 1, false);
    Api.MenuItem teaDish = new Api.MenuItem(5, "Trà bánh", "Cơm", 18000, true, 1, 1, false);
    Api.MenuItem updatedRice = new Api.MenuItem(1, "Cơm gà", "Cơm", 45000, true, 1, 2, false);
    Api.MenuItem[] initial = {rice, water, egg,
        new Api.MenuItem(6, "Cơm sườn", "Cơm", 35000, true, 1, 1, false),
        new Api.MenuItem(7, "Cơm thịt", "Cơm", 30000, true, 1, 1, false),
        new Api.MenuItem(8, "Cơm bò", "Cơm", 40000, true, 1, 1, false),
        new Api.MenuItem(9, "Cơm cá", "Cơm", 30000, true, 1, 1, false)};
    var categories = new Api.MenuCategory[] {
        new Api.MenuCategory(1, "Cơm", 6, 1), new Api.MenuCategory(2, "Đồ uống", 1, 1),
        new Api.MenuCategory(3, "Món trống", 0, 1)};
    long now = System.currentTimeMillis();
    var open = new Api.Invoice(7, "HD00000007", "", "", "", 155000, "OPEN",
        List.of(new Api.InvoiceLine("Cơm gà", 3, 45000, 135000),
            new Api.InvoiceLine("Trà mới", 1, 20000, 20000)), now, now + 300000, "", "", 1,
        "fixture-session", "TTAT00000000000007", "", "NONE");
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/menu" -> fixtureJson(json, initial);
      case "/api/menu/categories" -> fixtureJson(json, categories);
      case "/api/menu/sales" -> fixtureJson(json, new Api.MenuSales[] {
          new Api.MenuSales(1, 10), new Api.MenuSales(2, 7), new Api.MenuSales(3, 3),
          new Api.MenuSales(4, 11), new Api.MenuSales(5, 0), new Api.MenuSales(6, 1),
          new Api.MenuSales(7, 2), new Api.MenuSales(8, 8), new Api.MenuSales(9, 4)});
      case "/api/menu/search" -> {
        String query = request.uri().getRawQuery();
        queries.add(query);
        if ("query=Tr%C3%A0".equals(query))
          yield fixtureJson(json, new Api.MenuSearchResult(List.of(tea, teaDish), 9));
        if ("query=C%C6%A1m".equals(query))
          yield fixtureJson(json, new Api.MenuSearchResult(List.of(updatedRice, egg), 9));
        if ("query=xyz".equals(query))
          yield fixtureJson(json, new Api.MenuSearchResult(List.of(), 9));
        throw new AssertionError("Unexpected search " + query);
      }
      case "/api/invoices" -> {
        created.set(requestJson(json, request));
        yield fixtureJson(json, open);
      }
      case "/api/invoices/7" -> fixtureJson(json, open);
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        CanteenFrame frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setSize(1280, 880);
        frame.setVisible(true);
        frame.seller.loadMenu();
      });
      awaitIdle(window.get());
      awaitUi(() -> field(window.get().seller, "menuSales", Map.class) != null);
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        JPanel menu = field(seller, "menu", JPanel.class);
        assertEquals(7, menu.getComponentCount());
        assertEquals(1, ((GridLayout) menu.getLayout()).getColumns());
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, menu);
        assertEquals(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, scroll.getVerticalScrollBarPolicy());
        assertTrue(scroll.getVerticalScrollBar().isVisible());
        assertEquals(8, scroll.getVerticalScrollBar().getWidth());
        int menuViewportWidth = scroll.getViewport().getWidth();
        assertEquals(426, scroll.getHeight());
        assertEquals(424, scroll.getViewport().getHeight());
        assertEquals(new java.awt.Insets(8, 8, 8, 8), menu.getInsets());
        assertEquals(8 + 3 * (96 + 8), menu.getComponent(3).getY());
        assertEquals(416, menu.getComponent(3).getY() + menu.getComponent(3).getHeight());
        Rectangle fourthInViewport = SwingUtilities.convertRectangle(menu,
            menu.getComponent(3).getBounds(), scroll.getViewport());
        assertTrue(fourthInViewport.y >= 0
            && fourthInViewport.y + fourthInViewport.height <= scroll.getViewport().getHeight());
        JButton first = sellerDish(seller, "Cơm cá"), second = sellerDish(seller, "Cơm bò");
        int menuRowWidth = first.getWidth();
        assertEquals(first.getX(), second.getX());
        assertEquals(first.getWidth(), second.getWidth());
        assertTrue(first.getY() < second.getY());
        assertEquals(96, first.getHeight());
        assertEquals(80, first.getIcon().getIconWidth());
        assertEquals(80, first.getIcon().getIconHeight());
        assertEquals(8, first.getX());
        assertEquals(8, first.getY());
        assertEquals(8, second.getY() - first.getY() - first.getHeight());
        assertEquals(8, first.getInsets().left);
        assertEquals(80, first.getInsets().right);
        assertEquals(8, first.getIconTextGap());
        Icon addIcon = field(seller, "addDishIcon", Icon.class);
        Icon outlineIcon = field(seller, "addDishOutlineIcon", Icon.class);
        assertEquals(48, addIcon.getIconWidth());
        assertEquals(48, addIcon.getIconHeight());
        assertEquals(48, outlineIcon.getIconWidth());
        assertEquals(48, outlineIcon.getIconHeight());
        int addX = first.getWidth() - 24 - addIcon.getIconWidth();
        int addY = (first.getHeight() - addIcon.getIconHeight()) / 2;
        assertEquals(24, addY);
        first.getModel().setRollover(false);
        second.getModel().setRollover(false);
        var idleImage = painted(first);
        assertEquals(0x606060, idleImage.getRGB(addX + 24, addY + 24) & 0xffffff);
        assertNotEquals(0x606060, idleImage.getRGB(addX + 12, addY + 24) & 0xffffff);
        first.dispatchEvent(new MouseEvent(first, MouseEvent.MOUSE_ENTERED,
            System.currentTimeMillis(), 0, addX + 24, addY + 24, 0, false));
        assertTrue(first.getModel().isRollover());
        assertFalse(second.getModel().isRollover());
        var hoverImage = painted(first);
        assertEquals(0x606060, hoverImage.getRGB(addX + 12, addY + 24) & 0xffffff);
        assertNotEquals(0x606060, hoverImage.getRGB(addX + 24, addY + 24) & 0xffffff);
        assertDoesNotThrow(() -> {
          Files.createDirectories(Path.of("target/ui-check"));
          ImageIO.write(painted(window.get()), "png", Path.of("target/ui-check/checkout-menu-hover.png").toFile());
        });
        first.dispatchEvent(new MouseEvent(first, MouseEvent.MOUSE_EXITED,
            System.currentTimeMillis(), 0, -1, -1, 0, false));
        assertFalse(first.getModel().isRollover());
        assertEquals(0x606060, painted(first).getRGB(addX + 24, addY + 24) & 0xffffff);
        var insets = first.getInsets();
        var content = new Rectangle(insets.left, insets.top,
            first.getWidth() - insets.left - insets.right,
            first.getHeight() - insets.top - insets.bottom);
        var iconBounds = new Rectangle();
        var textBounds = new Rectangle();
        SwingUtilities.layoutCompoundLabel(first, first.getFontMetrics(first.getFont()), first.getText(),
            first.getIcon(), first.getVerticalAlignment(), first.getHorizontalAlignment(),
            first.getVerticalTextPosition(), first.getHorizontalTextPosition(), content, iconBounds,
            textBounds, first.getIconTextGap());
        assertEquals(8, iconBounds.x);
        assertEquals(8, iconBounds.y);
        assertEquals(8, first.getHeight() - iconBounds.y - iconBounds.height);
        assertEquals(8, textBounds.x - iconBounds.x - iconBounds.width);
        assertTrue(textBounds.x + textBounds.width <= addX - 8);
        assertTrue(first.getText().contains("<b>30.000 ₫</b>"));
        assertEquals(SwingConstants.RIGHT, first.getHorizontalTextPosition());
        assertEquals(SwingConstants.CENTER, first.getVerticalTextPosition());
        assertTrue(components(menu).noneMatch(c -> c instanceof JButton b
            && ("Sửa".equals(b.getText()) || "Xóa".equals(b.getText()))));
        assertTrue(components(seller).noneMatch(c -> c instanceof JButton b
            && ("Tải lại thực đơn".equals(b.getText()) || "Tìm món".equals(b.getText()))));
        assertNull(label(seller, "Món đang bán"));
        JButton catalog = button(seller, "Quản lý thực đơn");
        JTextField search = field(seller, "search", JTextField.class);
        JTabbedPane tabs = field(seller, "tabs", JTabbedPane.class);
        assertTrue(tabs.getBoundsAt(0).height >= tabs.getFontMetrics(tabs.getFont()).getHeight() + 4);
        JSplitPane split = (JSplitPane) tabs.getComponentAt(0);
        JPanel left = (JPanel) split.getLeftComponent();
        JPanel right = (JPanel) split.getRightComponent();
        assertFalse(split.isEnabled());
        assertEquals(4, split.getDividerSize());
        var divider = ((javax.swing.plaf.basic.BasicSplitPaneUI) split.getUI()).getDivider();
        int dividerLocation = split.getDividerLocation();
        divider.dispatchEvent(new MouseEvent(divider, MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK, 2, 20, 1, false, MouseEvent.BUTTON1));
        divider.dispatchEvent(new MouseEvent(divider, MouseEvent.MOUSE_DRAGGED,
            System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK, 82, 20, 0, false, MouseEvent.NOBUTTON));
        divider.dispatchEvent(new MouseEvent(divider, MouseEvent.MOUSE_RELEASED,
            System.currentTimeMillis(), 0, 82, 20, 1, false, MouseEvent.BUTTON1));
        assertEquals(dividerLocation, split.getDividerLocation(), "The divider must not be draggable");
        assertEquals(400, left.getWidth());
        assertEquals(new java.awt.Insets(16, 16, 16, 16), left.getInsets());
        assertEquals(new java.awt.Insets(16, 16, 16, 16), right.getInsets());
        assertEquals(new java.awt.Dimension(368, 36), catalog.getSize());
        Rectangle categoryLabelBounds = boundsIn(label(seller, "Danh mục"), left);
        Rectangle categoryBounds = boundsIn(field(seller, "category", JComboBox.class), left);
        assertEquals(36, categoryBounds.height);
        assertEquals(8, categoryBounds.x - categoryLabelBounds.x - categoryLabelBounds.width);
        assertEquals(left.getWidth() - 16, categoryBounds.x + categoryBounds.width);
        assertEquals(new java.awt.Dimension(240, 36), search.getSize());
        JComboBox<?> sortMenu = field(seller, "menuSort", JComboBox.class);
        assertEquals(new java.awt.Dimension(160, 36), sortMenu.getSize());
        assertEquals("Mới -> Cũ", sortMenu.getItemAt(0));
        assertEquals("Bán chạy", sortMenu.getItemAt(1));
        assertEquals("Giá bán", sortMenu.getItemAt(2));
        Rectangle searchBounds = boundsIn(search, left);
        JLabel searchLabel = label(seller, "Tìm kiếm");
        assertNotNull(searchLabel);
        assertSame(search, searchLabel.getLabelFor());
        Rectangle searchLabelBounds = boundsIn(searchLabel, left);
        assertEquals(16, searchLabelBounds.x);
        assertEquals(8, searchBounds.x - searchLabelBounds.x - searchLabelBounds.width);
        assertTrue(searchBounds.x + searchBounds.width <= left.getWidth() - 16);
        assertTrue(Math.abs(2 * searchBounds.y + searchBounds.height
            - 2 * searchLabelBounds.y - searchLabelBounds.height) <= 1);
        Rectangle sortLabelBounds = boundsIn(label(seller, "Sắp xếp"), left);
        Rectangle sortBounds = boundsIn(sortMenu, left);
        assertTrue(searchBounds.y + searchBounds.height <= sortLabelBounds.y);
        assertEquals(8, sortBounds.x - sortLabelBounds.x - sortLabelBounds.width);
        assertEquals(16, sortLabelBounds.x);
        JLabel menuTitle = label(seller, "Danh sách các món ăn");
        assertNotNull(menuTitle);
        assertSame(menuTitle.getParent(), scroll.getParent());
        assertEquals(8, scroll.getY() - menuTitle.getY() - menuTitle.getHeight());
        assertTrue(showingLabel(seller, "Đã hiển thị 7 trên tổng số 7 món ăn."));
        Component[] rows = left.getComponents();
        assertEquals(6, rows.length);
        assertEquals(16, rows[0].getY());
        assertEquals(left.getHeight() - 16, rows[5].getY() + rows[5].getHeight());
        int minGap = Integer.MAX_VALUE, maxGap = Integer.MIN_VALUE;
        for (int i = 0; i < rows.length - 1; i++) {
          int gap = rows[i + 1].getY() - rows[i].getY() - rows[i].getHeight();
          minGap = Math.min(minGap, gap);
          maxGap = Math.max(maxGap, gap);
        }
        assertTrue(maxGap - minGap <= 1, "Rows must use space-between");
        assertEquals("Tìm kiếm tên món ăn", search.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT));
        assertTrue(boundsIn(catalog, seller).y < boundsIn(label(seller, "Danh mục"), seller).y);
        assertTrue(boundsIn(label(seller, "Danh mục"), seller).y < boundsIn(search, seller).y);
        JLabel heading = label(seller, "HÓA ĐƠN CĂN TIN");
        JButton recent = button(seller, "Hóa đơn gần đây");
        assertSame(heading.getParent(), recent.getParent());
        assertTrue(boundsIn(recent, seller).x > boundsIn(heading, seller).x);
        assertTrue(boundsIn(recent, seller).intersects(
            new Rectangle(boundsIn(recent, seller).x, boundsIn(heading, seller).y,
                boundsIn(recent, seller).width, boundsIn(heading, seller).height)));
        first.dispatchEvent(new MouseEvent(first, MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK, addX + 12, addY + 12,
            1, false, MouseEvent.BUTTON1));
        first.dispatchEvent(new MouseEvent(first, MouseEvent.MOUSE_RELEASED,
            System.currentTimeMillis(), 0, addX + 12, addY + 12, 1, false, MouseEvent.BUTTON1));
        var iconCart = field(seller, "cartModel", javax.swing.table.DefaultTableModel.class);
        assertEquals(1, iconCart.getRowCount());
        assertEquals("Cơm cá", iconCart.getValueAt(0, 1));
        JTable invoiceTable = field(seller, "cartTable", JTable.class);
        assertEquals(6, invoiceTable.getColumnCount());
        assertEquals(List.of("Ảnh", "Tên món ăn", "SL", "Giá bán", "Thành tiền", ""),
            java.util.stream.IntStream.range(0, 6).mapToObj(invoiceTable::getColumnName).toList());
        assertEquals(96, invoiceTable.getRowHeight());
        assertEquals(96, invoiceTable.getColumnModel().getColumn(0).getWidth());
        assertEquals(96, invoiceTable.getColumnModel().getColumn(2).getWidth());
        assertEquals(80, invoiceTable.getColumnModel().getColumn(5).getWidth());
        JPanel deleteCell = (JPanel) invoiceTable.prepareRenderer(invoiceTable.getCellRenderer(0, 5), 0, 5);
        deleteCell.setSize(80, 96);
        deleteCell.doLayout();
        ((Container) deleteCell.getComponent(0)).doLayout();
        JButton delete = components(deleteCell).filter(c -> c instanceof JButton b
            && "Xóa món khỏi hóa đơn".equals(b.getAccessibleContext().getAccessibleName()))
            .map(JButton.class::cast).findFirst().orElseThrow();
        assertEquals(new Rectangle(24, 32, 32, 32), boundsIn(delete, deleteCell));
        assertFalse(delete.isBorderPainted());
        assertEquals(0xc62828, painted(delete).getRGB(8, 8) & 0xffffff);
        assertEquals(24, delete.getIcon().getIconWidth());
        assertEquals(24, delete.getIcon().getIconHeight());
        BufferedImage deleteIcon = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        var iconGraphics = deleteIcon.createGraphics();
        try { delete.getIcon().paintIcon(delete, iconGraphics, 0, 0); }
        finally { iconGraphics.dispose(); }
        int paintedIconPixels = 0;
        for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
          int pixel = deleteIcon.getRGB(x, y);
          if ((pixel >>> 24) != 0) {
            assertEquals(0xffffff, pixel & 0xffffff);
            paintedIconPixels++;
          }
        }
        assertTrue(paintedIconPixels > 0);
        assertEquals(80, ((Icon) iconCart.getValueAt(0, 0)).getIconWidth());
        assertEquals(80, ((Icon) iconCart.getValueAt(0, 0)).getIconHeight());
        for (int column : new int[] {0, 1, 3, 4}) {
          JLabel cell = (JLabel) invoiceTable.prepareRenderer(invoiceTable.getCellRenderer(0, column), 0, column);
          assertEquals(SwingConstants.CENTER, cell.getHorizontalAlignment());
        }
        assertFalse(showingLabel(seller, "Chưa tạo hóa đơn"));
        assertFalse(showingLabel(seller, "Kết nối thẻ trước hoặc sau khi tạo hóa đơn."));
        assertTrue(components(seller).noneMatch(c -> c instanceof JButton b
            && ("Sửa số lượng".equals(b.getText()) || "Bỏ món".equals(b.getText()))));
        clickCartCell(invoiceTable, 0, 2, 92);
        assertEquals(2, iconCart.getValueAt(0, 2));
        assertEquals(CanteenFrame.money(60000), iconCart.getValueAt(0, 4));
        clickCartCell(invoiceTable, 0, 2, 20);
        assertEquals(1, iconCart.getValueAt(0, 2));
        clickCartCell(invoiceTable, 0, 2, 20);
        assertEquals(1, iconCart.getValueAt(0, 2), "Số lượng tối thiểu là một");
        if (invoiceTable.isEditing()) invoiceTable.getCellEditor().cancelCellEditing();
        assertDoesNotThrow(() -> {
          Files.createDirectories(Path.of("target/ui-check"));
          ImageIO.write(painted(window.get()), "png", Path.of("target/ui-check/checkout-invoice-table.png").toFile());
        });
        clickCartCell(invoiceTable, 0, 5, 40);
        assertEquals(0, iconCart.getRowCount());
        assertDoesNotThrow(() -> {
          Files.createDirectories(Path.of("target/ui-check"));
          ImageIO.write(painted(window.get()), "png", Path.of("target/ui-check/checkout-menu-rows.png").toFile());
        });
        scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
        assertTrue(scroll.getViewport().getViewPosition().y > 0);
        field(seller, "category", JComboBox.class).setSelectedIndex(3);
        assertNoMatchingDishesCentered(window.get());
        assertTrue(scroll.getVerticalScrollBar().isVisible());
        assertEquals(menuViewportWidth, scroll.getViewport().getWidth());
        assertEquals(scroll.getVerticalScrollBar().getMinimum(),
            scroll.getVerticalScrollBar().getMaximum() - scroll.getVerticalScrollBar().getVisibleAmount());
        assertDoesNotThrow(() -> ImageIO.write(painted(window.get()), "png",
            Path.of("target/ui-check/checkout-empty-menu.png").toFile()));
        field(seller, "category", JComboBox.class).setSelectedIndex(0);
        window.get().validate();
        assertEquals(7, menu.getComponentCount(), "The dish list must return after clearing the filter");
        assertTrue(scroll.getVerticalScrollBar().isVisible());
        sortMenu.setSelectedIndex(2);
        assertSame(sellerDish(seller, "Cơm bò"), menu.getComponent(0));
        sortMenu.setSelectedIndex(1);
        assertSame(sellerDish(seller, "Cơm gà"), menu.getComponent(0));
        sortMenu.setSelectedIndex(0);
        assertSame(sellerDish(seller, "Cơm cá"), menu.getComponent(0));
        sellerDish(seller, "Cơm gà").doClick(0);
        sellerDish(seller, "Cơm gà").doClick(0);
        var cart = field(seller, "cartModel", javax.swing.table.DefaultTableModel.class);
        assertEquals(1, cart.getRowCount());
        assertEquals(2, cart.getValueAt(0, 2));
        assertEquals(CanteenFrame.money(70000), cart.getValueAt(0, 4));
        field(seller, "category", JComboBox.class).setSelectedIndex(2);
        assertEquals(1, menu.getComponentCount());
        assertNotNull(sellerDish(seller, "Nước suối"));
        window.get().validate();
        assertTrue(scroll.getVerticalScrollBar().isVisible());
        assertEquals(menuViewportWidth, scroll.getViewport().getWidth());
        assertEquals(menuRowWidth, sellerDish(seller, "Nước suối").getWidth());
        search.setText("Tr");
        assertEquals(0, menu.getComponentCount(), "Không hiện thông báo khi đang chờ tìm kiếm");
      });
      Thread.sleep(180);
      SwingUtilities.invokeAndWait(() -> field(window.get().seller, "search", JTextField.class).setText("Trà"));
      Thread.sleep(250);
      assertTrue(queries.isEmpty(), "Search must wait 500 ms after the last edit");
      awaitUi(() -> queries.size() == 1
          && components(field(window.get().seller, "menu", JPanel.class)).anyMatch(c -> c instanceof JButton b
              && b.getText() != null && b.getText().contains("Trà mới")));
      assertEquals("query=Tr%C3%A0", queries.get(0));
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        JPanel menu = field(seller, "menu", JPanel.class);
        assertEquals(1, menu.getComponentCount(), "Category must also filter backend search results");
        assertTrue(showingLabel(seller, "Đã hiển thị 1 trên tổng số 9 món ăn."));
        sellerDish(seller, "Trà mới").doClick(0);
        var cart = field(seller, "cartModel", javax.swing.table.DefaultTableModel.class);
        assertEquals(2, cart.getRowCount());
        assertEquals("Trà mới", cart.getValueAt(1, 1));
        assertEquals(CanteenFrame.money(20000), cart.getValueAt(1, 4));
        field(seller, "search", JTextField.class).setText("xyz");
      });
      awaitUi(() -> queries.size() == 2 && showingLabel(field(window.get().seller, "menu", JPanel.class),
          "Không tìm thấy món ăn phù hợp."));
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        assertNoMatchingDishesCentered(window.get());
        field(seller, "category", JComboBox.class).setSelectedIndex(0);
        field(seller, "search", JTextField.class).setText("Cơm");
      });
      awaitUi(() -> queries.size() == 3
          && components(field(window.get().seller, "menu", JPanel.class)).anyMatch(c -> c instanceof JButton b
              && b.getText() != null && b.getText().contains("Cơm gà")));
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        sellerDish(seller, "Cơm gà").doClick(0);
        var cart = field(seller, "cartModel", javax.swing.table.DefaultTableModel.class);
        assertEquals(3, cart.getValueAt(0, 2));
        assertEquals(CanteenFrame.money(45000), cart.getValueAt(0, 3));
        assertEquals(CanteenFrame.money(135000), cart.getValueAt(0, 4));
        assertEquals(CanteenFrame.money(155000), field(seller, "total", JLabel.class).getText());
        assertTrue(toastText(seller).contains("kiểm tra lại giá"));
        button(seller, "Tạo hóa đơn").doClick(0);
      });
      awaitIdle(window.get());
      assertNotNull(created.get());
      assertEquals(2, created.get().path("items").get(0).path("revision").asLong());
      assertEquals(3, created.get().path("items").get(0).path("quantity").asInt());
      assertEquals(4, created.get().path("items").get(1).path("itemId").asLong());
      assertEquals(1, created.get().path("items").get(1).path("revision").asLong());
      SwingUtilities.invokeAndWait(() -> field(window.get().seller, "tabs", JTabbedPane.class).setSelectedIndex(0));
      SwingUtilities.invokeLater(() -> sellerDish(window.get().seller, "Cơm gà").doClick(0));
      awaitUi(() -> ownedDialog(window.get(), "TTM Căn tin") != null);
      SwingUtilities.invokeAndWait(() -> optionPane(ownedDialog(window.get(), "TTM Căn tin"))
          .setValue(JOptionPane.OK_OPTION));
      awaitUi(() -> ownedDialog(window.get(), "TTM Căn tin") == null);
      SwingUtilities.invokeAndWait(() -> {
        var cart = field(window.get().seller, "cartModel", javax.swing.table.DefaultTableModel.class);
        assertEquals(3, cart.getValueAt(0, 2), "A waiting invoice must not change when a row is clicked");
        assertTrue(field(window.get().seller, "cartOverlay", JPanel.class).isVisible());
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void invoiceHistoryShowsCheckoutInvoicesAndCombinesFiltersWithPagination() throws Exception {
    var json = new ObjectMapper();
    var requests = new CopyOnWriteArrayList<String>();
    var invoiceReply = new AtomicReference<Api.Invoice>();
    var initialStarted = new CountDownLatch(1);
    var releaseInitial = new CountDownLatch(1);
    var filterStarted = new CountDownLatch(1);
    var releaseFilter = new CountDownLatch(1);
    String[] states = {"OPEN", "PAID", "CANCELLED", "PAYMENT_PENDING", "CANCEL_PENDING"};
    List<Api.InvoiceSummary> all = java.util.stream.IntStream.rangeClosed(1, 13)
        .mapToObj(index -> new Api.InvoiceSummary(index, String.format("HD%08d", index),
            "TTAT" + String.format("%014d", index), "2026-11-11T03:04:05Z", 10000 + index * 1000L,
            states[(index - 1) % states.length], index == 2 ? "BANK_TRANSFER" : "", "NONE", ""))
        .toList();
    ApiClient api = api(request -> {
      if ("/api/invoices/99".equals(request.uri().getPath())) return fixtureJson(json, invoiceReply.get());
      if ("/api/menu/sales".equals(request.uri().getPath())) return "[]";
      assertEquals("/api/invoices/pages", request.uri().getPath());
      String raw = request.uri().getRawQuery();
      requests.add(raw);
      String term = queryParameter(raw, "q"), from = queryParameter(raw, "from");
      String to = queryParameter(raw, "to"), amount = queryParameter(raw, "amount");
      int page = Integer.parseInt(queryParameter(raw, "page"));
      if (term.isBlank() && page == 0 && initialStarted.getCount() != 0) {
        initialStarted.countDown();
        await(releaseInitial);
      }
      if ("TTAT00000000000002".equals(term)) {
        filterStarted.countDown();
        await(releaseFilter);
      }
      List<Api.InvoiceSummary> matching = all.stream().filter(item ->
          (term.isBlank() || item.number().contains(term) || item.paymentReference().contains(term))
              && (amount.isBlank() || item.total() == Long.parseLong(amount))
              && (from.isBlank() || from.compareTo("2026-11-11") <= 0)
              && (to.isBlank() || to.compareTo("2026-11-11") >= 0)).toList();
      int offset = (int) Math.min((long) page * 10, matching.size());
      return fixtureJson(json, new Api.InvoicePage(
          matching.subList(offset, Math.min(offset + 10, matching.size())), matching.size(), page, 10));
    });
    var window = new AtomicReference<CanteenFrame>();
    long now = System.currentTimeMillis();
    Api.Invoice open = new Api.Invoice(99, "HD00000099", "", "", "", 12000, "OPEN",
        List.of(), now, now + 300000, "", "", 1, "fixture-session", "", "", "NONE");
    Api.Invoice paid = new Api.Invoice(99, "HD00000099", "", "", "", 12000, "PAID",
        List.of(), now, now + 300000, "", "", 2, "fixture-session", "", "BANK_TRANSFER", "FINISHED");
    invoiceReply.set(open);
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, true, false);
        window.set(frame);
        field(frame.seller, "timer", javax.swing.Timer.class).stop();
        setField(frame.seller, "invoice", open);
        frame.setVisible(true);
        button(frame.seller, "Hóa đơn gần đây").doClick(0);
      });
      await(initialStarted);
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(window.get().getGlassPane().isVisible());
        assertFalse(showingLabel(window.get().getGlassPane(), "Đang tải hóa đơn…"));
      });
      releaseInitial.countDown();
      awaitUi(() -> ownedDialog(window.get(), "Hóa đơn gần đây") != null);
      JDialog dialog = ownedDialog(window.get(), "Hóa đơn gần đây");
      InvoiceHistoryView view = components(dialog).filter(InvoiceHistoryView.class::isInstance)
          .map(InvoiceHistoryView.class::cast).findFirst().orElseThrow();
      SwingUtilities.invokeAndWait(() -> {
        JTable table = namedTable(view, "Danh sách hóa đơn");
        assertEquals(1280, dialog.getWidth());
        assertFalse(dialog.isResizable());
        assertEquals(new java.awt.Insets(8, 16, 16, 16), ((JComponent) dialog.getContentPane()).getInsets());
        assertEquals(new java.awt.Insets(16, 16, 16, 16), view.getInsets());
        assertEquals(new java.awt.Dimension(400, 40), field(view, "query", JTextField.class).getSize());
        for (String name : List.of("amount", "startDate", "endDate"))
          assertEquals(new java.awt.Dimension(180, 40), field(view, name, JTextField.class).getSize());
        assertEquals("Tìm theo mã tham chiếu của hoá đơn",
            field(view, "query", JTextField.class).getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT));
        assertEquals(10, table.getRowCount());
        assertEquals(6, table.getColumnCount());
        assertEquals(41, table.getRowHeight(0));
        assertEquals(40, table.getRowHeight(9));
        assertEquals(41, table.getTableHeader().getHeight());
        assertNeutralTableSelection(table, 0);
        assertEquals("11/11/2026 10:04:05", table.getValueAt(0, 0));
        assertEquals("HD00000001", table.getValueAt(0, 1));
        assertEquals("Chờ thanh toán", table.getValueAt(0, 4));
        assertEquals("Đã thanh toán", table.getValueAt(1, 4));
        assertEquals("Chuyển khoản", table.getValueAt(1, 5));
        assertEquals("Đã hủy", table.getValueAt(2, 4));
        assertEquals("Đang xác thực/thanh toán", table.getValueAt(3, 4));
        assertEquals("Đang hủy · chờ đối soát", table.getValueAt(4, 4));
        assertFalse(table.isCellEditable(0, 0));
        assertEquals("Hiển thị 1–10 trên tổng số 13 kết quả", field(view, "resultCount", JLabel.class).getText());
        assertEquals(99, field(window.get().seller, "invoice", Api.Invoice.class).id());
        captureReconciliation(dialog, "checkout-invoice-history.png");
        button(view, "2").doClick(0);
      });
      awaitUi(() -> {
        Api.InvoicePage page = field(view, "pageData", Api.InvoicePage.class);
        return page != null && page.page() == 1;
      });
      invoiceReply.set(paid);
      SwingUtilities.invokeAndWait(() -> window.get().seller.refreshInvoice());
      awaitUi(() -> "PAID".equals(field(window.get().seller, "invoice", Api.Invoice.class).status()));
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(dialog.isShowing(), "Invoice polling must still complete while history is open");
        assertEquals("Hiển thị 11–13 trên tổng số 13 kết quả", field(view, "resultCount", JLabel.class).getText());
        assertEquals("HD00000011", namedTable(view, "Danh sách hóa đơn").getValueAt(0, 1));
        field(view, "query", JTextField.class).setText("TTAT00000000000002");
        field(view, "amount", JTextField.class).setText("12000");
        field(view, "startDate", JTextField.class).setText("10112026");
        field(view, "endDate", JTextField.class).setText("11112026");
        assertTrue(field(view, "status", JLabel.class).getText().isBlank());
      });
      int beforeDebounce = requests.size();
      Thread.sleep(200);
      assertEquals(beforeDebounce, requests.size(), "Typing should wait before requesting filtered results");
      await(filterStarted);
      SwingUtilities.invokeAndWait(() -> assertTrue(field(view, "status", JLabel.class).getText().isBlank()));
      releaseFilter.countDown();
      awaitUi(() -> {
        Api.InvoicePage page = field(view, "pageData", Api.InvoicePage.class);
        return page != null && page.page() == 0 && page.total() == 1;
      });
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("HD00000002", namedTable(view, "Danh sách hóa đơn").getValueAt(0, 1));
        assertEquals("Hiển thị 1 trên tổng số 1 kết quả", field(view, "resultCount", JLabel.class).getText());
        assertTrue(requests.stream().anyMatch(raw -> queryParameter(raw, "q").equals("TTAT00000000000002")
            && queryParameter(raw, "amount").equals("12000")
            && queryParameter(raw, "from").equals("2026-11-10")
            && queryParameter(raw, "to").equals("2026-11-11")));
        field(view, "query", JTextField.class).setText("NOPE");
        field(view, "query", JTextField.class).postActionEvent();
      });
      awaitUi(() -> {
        Api.InvoicePage page = field(view, "pageData", Api.InvoicePage.class);
        return page != null && page.total() == 0;
      });
      SwingUtilities.invokeAndWait(() -> {
        assertNull(namedTable(view, "Danh sách hóa đơn").getValueAt(0, 1));
        assertEquals("Hiển thị 0 trên tổng số 0 kết quả", field(view, "resultCount", JLabel.class).getText());
        field(view, "startDate", JTextField.class).setText("12112026");
        field(view, "startDate", JTextField.class).postActionEvent();
        assertEquals("Ngày bắt đầu sau ngày kết thúc.", field(view, "status", JLabel.class).getText());
        dialog.dispatchEvent(new java.awt.event.WindowEvent(dialog, java.awt.event.WindowEvent.WINDOW_CLOSING));
      });
      awaitUi(() -> !dialog.isShowing());
      SwingUtilities.invokeAndWait(() -> assertTrue(field(view, "disposed", Boolean.class)));
    } finally {
      releaseInitial.countDown();
      releaseFilter.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void managementTablesKeepSelectionAndHoverVisuallyNeutral() throws Exception {
    ApiClient api = api(request -> { throw new AssertionError("Unexpected request: " + request.uri()); });
    var window = new AtomicReference<CanteenFrame>();
    var transactions = new AtomicReference<AdminTransactionsView>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, false, false);
        window.set(frame);
        JTable cards = field(frame.admin, "table", JTable.class);
        var cardsModel = field(frame.admin, "model", javax.swing.table.DefaultTableModel.class);
        cardsModel.setRowCount(1);
        cardsModel.setValueAt("CARD1", 0, 0);
        cardsModel.setValueAt("ACTIVE", 0, 7);
        cards.setSize(1000, 41);
        cards.doLayout();
        assertNeutralTableSelection(cards, 0);
        Component status = cards.prepareRenderer(cards.getCellRenderer(0, 7), 0, 7);
        assertTrue(components(status).anyMatch(c -> c instanceof JLabel l && "Hoạt động".equals(l.getText())));

        var history = field(frame.student, "transactions", CardTransactionsView.class);
        JTable cardHistory = field(history, "table", JTable.class);
        cardHistory.setValueAt("Giao dịch", 0, 1);
        cardHistory.setSize(940, 163);
        cardHistory.doLayout();
        assertNeutralTableSelection(cardHistory, 0);

        var transactionView = new AdminTransactionsView(frame, new Api.AdminTransactionPage(List.of(), 0, 0, 10));
        transactions.set(transactionView);
        JTable transactionTable = field(transactionView, "table", JTable.class);
        transactionTable.setValueAt("Giao dịch", 0, 1);
        transactionTable.setSize(1200, 409);
        transactionTable.doLayout();
        assertNeutralTableSelection(transactionTable, 0);
      });
    } finally {
      SwingUtilities.invokeAndWait(() -> {
        if (transactions.get() != null) transactions.get().dispose();
        if (window.get() != null) window.get().dispose();
      });
    }
  }

  @Test
  void checkoutInvoiceShowsFiveRowsWithGrayEmptySpaceAndRightAlignedTotals() throws Exception {
    ApiClient api = api(request -> { throw new AssertionError("Unexpected request: " + request.uri()); });
    var window = new AtomicReference<CanteenFrame>();
    Api.MenuItem[] dishes = java.util.stream.IntStream.rangeClosed(1, 6)
        .mapToObj(index -> new Api.MenuItem(index, "Món " + index, "Cơm", 10000, true, 1, 1, false))
        .toArray(Api.MenuItem[]::new);
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, true, false);
        window.set(frame);
        setField(frame.seller, "items", dishes);
        frame.setSize(1280, 880);
        frame.setVisible(true);
        frame.validate();
        CheckoutView seller = frame.seller;
        JTable table = field(seller, "cartTable", JTable.class);
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
        JTabbedPane tabs = field(seller, "tabs", JTabbedPane.class);
        JPanel right = (JPanel) ((JSplitPane) tabs.getComponentAt(0)).getRightComponent();
        assertEquals(new java.awt.Insets(16, 16, 16, 16), right.getInsets());
        assertEquals(5, right.getComponentCount());
        Component[] rows = right.getComponents();
        assertEquals(16, rows[0].getY());
        assertEquals(right.getHeight() - 16, rows[4].getY() + rows[4].getHeight());
        assertEquals(16, rows[2].getX());
        assertEquals(right.getWidth() - 32, rows[2].getWidth());
        assertEquals(1, rows[2].getHeight());
        assertEquals(new java.awt.Color(0xC2C2C2), rows[2].getBackground());
        int minGap = Integer.MAX_VALUE, maxGap = Integer.MIN_VALUE;
        for (int i = 0; i < rows.length - 1; i++) {
          int gap = rows[i + 1].getY() - rows[i].getY() - rows[i].getHeight();
          minGap = Math.min(minGap, gap);
          maxGap = Math.max(maxGap, gap);
        }
        assertTrue(minGap >= 0 && maxGap - minGap <= 1);
        JLabel title = label(seller, "HÓA ĐƠN CĂN TIN");
        assertEquals(24, title.getFont().getSize());
        assertTrue(title.getFont().isBold());
        JButton recent = button(seller, "Hóa đơn gần đây");
        assertEquals(new java.awt.Dimension(180, 36), recent.getSize());
        assertEquals(16, recent.getFont().getSize());
        assertEquals(right.getWidth() - 16, boundsIn(recent, right).x + recent.getWidth());
        JLabel totalLabel = label(seller, "Tổng tiền");
        JLabel total = field(seller, "total", JLabel.class);
        assertEquals(24, totalLabel.getFont().getSize());
        assertFalse(totalLabel.getFont().isBold());
        assertEquals(24, total.getFont().getSize());
        assertTrue(total.getFont().isBold());
        Rectangle totalBounds = boundsIn(total, right);
        Rectangle totalLabelBounds = boundsIn(totalLabel, right);
        assertEquals(16, totalLabelBounds.x);
        assertEquals(right.getWidth() - 16, totalBounds.x + totalBounds.width);
        assertTrue(totalLabelBounds.x + totalLabelBounds.width + 8 <= totalBounds.x);
        JButton action = field(seller, "invoiceAction", JButton.class);
        assertEquals(new java.awt.Dimension(144, 36), action.getSize());
        assertEquals(right.getWidth() - 16, boundsIn(action, right).x + action.getWidth());
        assertEquals(523, scroll.getHeight());
        assertEquals(37, table.getTableHeader().getHeight());
        assertEquals(1, table.getTableHeader().getInsets().bottom);
        assertEquals(484, scroll.getViewport().getHeight());
        assertEquals(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, scroll.getVerticalScrollBarPolicy());
        assertTrue(scroll.getVerticalScrollBar().isVisible());
        assertEquals(8, scroll.getVerticalScrollBar().getWidth());
        assertEquals(scroll.getVerticalScrollBar().getMinimum(),
            scroll.getVerticalScrollBar().getMaximum() - scroll.getVerticalScrollBar().getVisibleAmount());
        int viewportWidth = scroll.getViewport().getWidth();
        int[] columnWidths = java.util.stream.IntStream.range(0, table.getColumnCount())
            .map(column -> table.getColumnModel().getColumn(column).getWidth()).toArray();
        assertEquals(0xf2f2f2, painted(scroll.getViewport()).getRGB(4, 20) & 0xffffff);
        assertEquals(0xe5e5e5, painted(table.getTableHeader()).getRGB(4, 4) & 0xffffff);
        assertEquals(0xdddddd, painted(table.getTableHeader()).getRGB(4, 36) & 0xffffff);
        BufferedImage header = painted(table.getTableHeader());
        int boundary = 0;
        for (int column = 0; column < table.getColumnCount() - 1; column++) {
          boundary += table.getColumnModel().getColumn(column).getWidth();
          assertEquals(0xc2c2c2, header.getRGB(boundary - 1, 10) & 0xffffff);
        }
        assertDoesNotThrow(() -> {
          Files.createDirectories(Path.of("target/ui-check"));
          ImageIO.write(painted(frame), "png", Path.of("target/ui-check/checkout-invoice-empty-five.png").toFile());
        });
        invoke(seller, "add", Api.MenuItem.class, dishes[0]);
        frame.validate();
        assertEquals(1, table.getRowCount());
        assertTrue(scroll.getVerticalScrollBar().isVisible());
        assertEquals(viewportWidth, scroll.getViewport().getWidth());
        assertNeutralTableSelection(table, 0);
        tabs.setSelectedIndex(1);
        frame.validate();
        JPanel customerItems = field(seller, "customerItems", JPanel.class);
        JScrollPane customerScroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, customerItems);
        assertEquals(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, customerScroll.getVerticalScrollBarPolicy());
        assertTrue(customerScroll.getVerticalScrollBar().isVisible());
        assertEquals(8, customerScroll.getVerticalScrollBar().getWidth());
        assertEquals(customerScroll.getVerticalScrollBar().getMinimum(),
            customerScroll.getVerticalScrollBar().getMaximum() - customerScroll.getVerticalScrollBar().getVisibleAmount());
        assertEquals(0, customerItems.getComponentCount(),
            "Cart items must not appear to the customer before an invoice is created");
        tabs.setSelectedIndex(0);
        frame.validate();
        assertEquals(96, table.getRowHeight(0));
        assertEquals(0xffffff, painted(scroll.getViewport()).getRGB(4, 48) & 0xffffff);
        assertEquals(0xf2f2f2, painted(scroll.getViewport()).getRGB(4, 120) & 0xffffff);
        assertEquals(96, table.getColumnModel().getColumn(2).getWidth());
        JPanel controls = (JPanel) table.prepareRenderer(table.getCellRenderer(0, 2), 0, 2);
        controls.setSize(96, 96);
        controls.doLayout();
        ((Container) controls.getComponent(0)).doLayout();
        JButton minus = components(controls).filter(c -> c instanceof JButton b
            && "Giảm số lượng".equals(b.getAccessibleContext().getAccessibleName()))
            .map(JButton.class::cast).findFirst().orElseThrow();
        JButton plus = components(controls).filter(c -> c instanceof JButton b
            && "Tăng số lượng".equals(b.getAccessibleContext().getAccessibleName()))
            .map(JButton.class::cast).findFirst().orElseThrow();
        JLabel count = components(controls).filter(c -> c instanceof JLabel l && "1".equals(l.getText()))
            .map(JLabel.class::cast).findFirst().orElseThrow();
        assertEquals(new java.awt.Dimension(32, 32), minus.getSize());
        assertEquals(new java.awt.Dimension(32, 32), plus.getSize());
        assertEquals(new java.awt.Dimension(32, 32), count.getSize());
        assertEquals("", minus.getText());
        assertEquals("", plus.getText());
        assertEquals(16, minus.getIcon().getIconWidth());
        assertEquals(16, minus.getIcon().getIconHeight());
        assertEquals(16, plus.getIcon().getIconWidth());
        assertEquals(16, plus.getIcon().getIconHeight());
        assertFalse(minus.isEnabled());
        assertTrue(plus.isEnabled());
        assertEquals(0xe5e5e5, painted(minus).getRGB(8, 8) & 0xffffff);
        assertEquals(0xffffff, painted(plus).getRGB(8, 8) & 0xffffff);
        plus.getModel().setRollover(true);
        assertEquals(0xffffff, painted(plus).getRGB(8, 8) & 0xffffff);
        plus.getModel().setArmed(true);
        plus.getModel().setPressed(true);
        assertEquals(0xffffff, painted(plus).getRGB(8, 8) & 0xffffff);
        plus.getModel().setPressed(false);
        plus.getModel().setArmed(false);
        plus.getModel().setRollover(false);
        Rectangle minusBounds = boundsIn(minus, controls);
        Rectangle countBounds = boundsIn(count, controls);
        Rectangle plusBounds = boundsIn(plus, controls);
        assertEquals(0, countBounds.x - minusBounds.x - minusBounds.width);
        assertEquals(0, plusBounds.x - countBounds.x - countBounds.width);
        for (int i = 1; i < 5; i++) invoke(seller, "add", Api.MenuItem.class, dishes[i]);
        frame.validate();
        assertEquals(5, table.getRowCount());
        assertTrue(scroll.getVerticalScrollBar().isVisible());
        assertEquals(scroll.getVerticalScrollBar().getMinimum(),
            scroll.getVerticalScrollBar().getMaximum() - scroll.getVerticalScrollBar().getVisibleAmount(),
            "Exactly five rows must fit without scrolling");
        assertEquals(viewportWidth, scroll.getViewport().getWidth());
        assertArrayEquals(columnWidths, java.util.stream.IntStream.range(0, table.getColumnCount())
            .map(column -> table.getColumnModel().getColumn(column).getWidth()).toArray());
        for (int row = 0; row < 4; row++) assertEquals(97, table.getRowHeight(row));
        assertEquals(96, table.getRowHeight(4));
        BufferedImage fiveRows = painted(scroll.getViewport());
        for (int line : new int[] {96, 193, 290, 387})
          assertEquals(0xdddddd, fiveRows.getRGB(4, line) & 0xffffff);
        assertEquals(0xffffff, fiveRows.getRGB(4, 483) & 0xffffff);
        assertDoesNotThrow(() -> ImageIO.write(painted(frame), "png",
            Path.of("target/ui-check/checkout-invoice-five-rows.png").toFile()));
        invoke(seller, "add", Api.MenuItem.class, dishes[5]);
        frame.validate();
        assertEquals(6, table.getRowCount());
        assertTrue(scroll.getVerticalScrollBar().isVisible());
        assertEquals(viewportWidth, scroll.getViewport().getWidth());
        assertArrayEquals(columnWidths, java.util.stream.IntStream.range(0, table.getColumnCount())
            .map(column -> table.getColumnModel().getColumn(column).getWidth()).toArray());
        assertEquals(484, scroll.getViewport().getHeight());
        scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
        assertTrue(scroll.getViewport().getViewPosition().y > 0);
        seller.showNotice("Chưa rõ kết quả tạo hóa đơn. Nhấn Tạo hóa đơn để kiểm tra lại cùng yêu cầu; không lập đơn khác.", true);
        frame.validate();
        assertTrue(Toast.forComponent(frame).isShowing());
        for (int row = 0; row < rows.length - 1; row++)
          assertTrue(rows[row].getY() + rows[row].getHeight() <= rows[row + 1].getY());
        assertEquals(right.getHeight() - 16, rows[4].getY() + rows[4].getHeight());
        assertEquals(523, scroll.getHeight());
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutCartControlsRespectLimitsAndLockDuringInvoiceCreation() throws Exception {
    var json = new ObjectMapper();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var request = new AtomicReference<JsonNode>();
    long now = System.currentTimeMillis();
    Api.MenuItem item = new Api.MenuItem(1, "Cơm gà", "Cơm", 10000, true, 1, 1, false);
    Api.Invoice open = new Api.Invoice(7, "HD00000007", "", "", "", 10000, "OPEN",
        List.of(new Api.InvoiceLine("Cơm gà", 1, 10000, 10000)), now, now + 300000,
        "", "", 1, "fixture-session", "TTAT00000000000007", "", "NONE");
    ApiClient api = api(call -> switch (call.uri().getPath()) {
      case "/api/invoices" -> {
        request.set(requestJson(json, call));
        started.countDown();
        await(release);
        yield fixtureJson(json, open);
      }
      case "/api/invoices/7" -> fixtureJson(json, open);
      default -> throw new AssertionError(call.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    var stalePlus = new AtomicReference<JButton>();
    var staleDelete = new AtomicReference<JButton>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, true, false);
        window.set(frame);
        setField(frame.seller, "items", new Api.MenuItem[] {item});
        frame.setVisible(true);
        invoke(frame.seller, "add", Api.MenuItem.class, item);
        JTable table = field(frame.seller, "cartTable", JTable.class);
        var model = field(frame.seller, "cartModel", javax.swing.table.DefaultTableModel.class);
        @SuppressWarnings("unchecked") Map<Long, Api.OrderItem> orders = field(frame.seller, "cart", Map.class);
        orders.put(1L, new Api.OrderItem(1, 98, 1L));
        invokeNoArgs(frame.seller, "renderCart");
        clickCartCell(table, 0, 2, 92);
        assertEquals(99, model.getValueAt(0, 2));
        assertEquals(CanteenFrame.money(990000), model.getValueAt(0, 4));
        clickCartCell(table, 0, 2, 92);
        assertEquals(99, model.getValueAt(0, 2), "Số lượng tối đa là 99");
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        orders.put(1L, new Api.OrderItem(1, 1, 1L));
        invokeNoArgs(frame.seller, "renderCart");
        assertTrue(table.editCellAt(0, 5));
        staleDelete.set(components(table.getEditorComponent()).filter(c -> c instanceof JButton b
            && "Xóa món khỏi hóa đơn".equals(b.getAccessibleContext().getAccessibleName()))
            .map(JButton.class::cast).findFirst().orElseThrow());
        table.getCellEditor().cancelCellEditing();
        assertTrue(table.editCellAt(0, 2));
        stalePlus.set(components(table.getEditorComponent()).filter(c -> c instanceof JButton b
            && "Tăng số lượng".equals(b.getAccessibleContext().getAccessibleName()))
            .map(JButton.class::cast).findFirst().orElseThrow());
        field(frame.seller, "invoiceAction", JButton.class).doClick(0);
      });
      assertTrue(started.await(3, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        stalePlus.get().doClick(0);
        staleDelete.get().doClick(0);
        var model = field(seller, "cartModel", javax.swing.table.DefaultTableModel.class);
        assertEquals(1, model.getRowCount());
        assertEquals(1, model.getValueAt(0, 2));
        assertFalse(model.isCellEditable(0, 2));
        assertFalse(model.isCellEditable(0, 5));
      });
      assertEquals(1, request.get().path("items").get(0).path("quantity").asInt());
      release.countDown();
      awaitIdle(window.get());
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        assertEquals("Hủy hóa đơn", field(seller, "invoiceAction", JButton.class).getText());
        assertFalse(field(seller, "cartTable", JTable.class).isEnabled());
        stalePlus.get().doClick(0);
        staleDelete.get().doClick(0);
        var model = field(seller, "cartModel", javax.swing.table.DefaultTableModel.class);
        assertEquals(1, model.getRowCount());
        assertEquals(1, model.getValueAt(0, 2));
        assertFalse(model.isCellEditable(0, 2));
        assertFalse(model.isCellEditable(0, 5));
      });
    } finally {
      release.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutDoesNotAttachLateDishImageToAnotherCartRow() throws Exception {
    var json = new ObjectMapper();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB), "png", bytes);
    String encoded = Base64.getEncoder().encodeToString(bytes.toByteArray());
    Api.MenuItem first = new Api.MenuItem(1, "Cơm gà", "Cơm", 10000, true, 1, 1, true);
    Api.MenuItem second = new Api.MenuItem(2, "Cơm trứng", "Cơm", 15000, true, 1, 1, false);
    ApiClient api = api(call -> {
      assertEquals("/api/menu/items/1/image", call.uri().getPath());
      started.countDown();
      await(release);
      return fixtureJson(json, new Api.MenuImage(encoded, "image/png", 1));
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, true, false);
        window.set(frame);
        setField(frame.seller, "items", new Api.MenuItem[] {first, second});
        frame.setVisible(true);
        invoke(frame.seller, "add", Api.MenuItem.class, first);
        invoke(frame.seller, "add", Api.MenuItem.class, second);
      });
      assertTrue(started.await(3, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        clickCartCell(field(seller, "cartTable", JTable.class), 0, 5, 40);
        var model = field(seller, "cartModel", javax.swing.table.DefaultTableModel.class);
        assertEquals(1, model.getRowCount());
        assertEquals("Cơm trứng", model.getValueAt(0, 1));
      });
      release.countDown();
      awaitUi(() -> field(field(window.get().seller, "images", DishImages.class), "pending", Map.class).isEmpty());
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        var model = field(seller, "cartModel", javax.swing.table.DefaultTableModel.class);
        assertEquals("Cơm trứng", model.getValueAt(0, 1));
        assertSame(field(seller, "images", DishImages.class).placeholder(80), model.getValueAt(0, 0));
        assertEquals(CanteenFrame.money(15000), model.getValueAt(0, 4));
      });
    } finally {
      release.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutRetriesUnknownInvoiceCreationWithTheSameRequest() throws Exception {
    var json = new ObjectMapper();
    var requests = new CopyOnWriteArrayList<JsonNode>();
    long now = System.currentTimeMillis();
    Api.MenuItem item = new Api.MenuItem(1, "Cơm gà", "Cơm", 10000, true, 1, 1, false);
    Api.Invoice open = new Api.Invoice(7, "HD00000007", "", "", "", 10000, "OPEN",
        List.of(new Api.InvoiceLine("Cơm gà", 1, 10000, 10000)), now, now + 300000,
        "", "", 1, "fixture-session", "TTAT00000000000007", "", "NONE");
    ApiClient api = api(call -> switch (call.uri().getPath()) {
      case "/api/invoices" -> {
        requests.add(requestJson(json, call));
        if (requests.size() == 1) throw new ApiClient.ApiError(503, "CONNECTION", "Mất kết nối thử nghiệm");
        yield fixtureJson(json, open);
      }
      case "/api/invoices/7" -> fixtureJson(json, open);
      default -> throw new AssertionError(call.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, true, false);
        window.set(frame);
        setField(frame.seller, "items", new Api.MenuItem[] {item});
        frame.setVisible(true);
        invoke(frame.seller, "add", Api.MenuItem.class, item);
        field(frame.seller, "invoiceAction", JButton.class).doClick(0);
      });
      awaitUi(() -> toastText(window.get())
          .contains("Chưa rõ kết quả tạo hóa đơn"));
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        var model = field(seller, "cartModel", javax.swing.table.DefaultTableModel.class);
        assertEquals("Tạo hóa đơn", field(seller, "invoiceAction", JButton.class).getText());
        assertTrue(field(seller, "invoiceAction", JButton.class).isEnabled());
        assertFalse(model.isCellEditable(0, 2));
        assertFalse(model.isCellEditable(0, 5));
        assertEquals(1, model.getValueAt(0, 2));
        field(seller, "invoiceAction", JButton.class).doClick(0);
      });
      awaitUi(() -> requests.size() == 2 && !window.get().isBusy()
          && "Hủy hóa đơn".equals(field(window.get().seller, "invoiceAction", JButton.class).getText()));
      assertEquals(requests.get(0), requests.get(1), "Phải thử lại cùng mã yêu cầu và cùng giỏ hàng");
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        assertFalse(field(seller, "cartTable", JTable.class).isEnabled());
        assertEquals(1, field(seller, "cartModel", javax.swing.table.DefaultTableModel.class).getValueAt(0, 2));
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutSalesRankingHandlesDelayedResponsesPaymentAndErrors() throws Exception {
    var json = new ObjectMapper();
    var firstStarted = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var paymentRefreshStarted = new CountDownLatch(1);
    var releasePaymentRefresh = new CountDownLatch(1);
    var salesRequests = new AtomicInteger();
    var failSales = new AtomicBoolean();
    Api.MenuItem rice = new Api.MenuItem(1, "Cơm", "Món ăn", 35000, true, 1, 1, false);
    Api.MenuItem tea = new Api.MenuItem(2, "Trà", "Món ăn", 15000, true, 1, 1, false);
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/menu" -> fixtureJson(json, new Api.MenuItem[] {rice, tea});
      case "/api/menu/categories" -> fixtureJson(json,
          new Api.MenuCategory[] {new Api.MenuCategory(1, "Món ăn", 2, 1)});
      case "/api/menu/sales" -> {
        int number = salesRequests.incrementAndGet();
        if (number == 1) {
          firstStarted.countDown();
          boolean released = false;
          while (!released) {
            try { releaseFirst.await(); released = true; }
            catch (InterruptedException ignored) { /* Simulate an uncancellable old response. */ }
          }
          yield fixtureJson(json, new Api.MenuSales[] {
              new Api.MenuSales(1, 1), new Api.MenuSales(2, 50)});
        }
        if (number == 3) {
          paymentRefreshStarted.countDown();
          await(releasePaymentRefresh);
        }
        if (failSales.get()) throw new ApiClient.ApiError(503, "CONNECTION", "Fixture offline");
        yield fixtureJson(json, number == 2
            ? new Api.MenuSales[] {new Api.MenuSales(1, 3), new Api.MenuSales(2, 6)}
            : new Api.MenuSales[] {new Api.MenuSales(1, 8), new Api.MenuSales(2, 6)});
      }
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        CanteenFrame frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        frame.seller.loadMenu();
      });
      awaitIdle(window.get());
      assertTrue(firstStarted.await(3, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        sellerDish(seller, "Cơm").doClick(0);
        assertTrue(button(seller, "Tạo hóa đơn").isEnabled(), "Loading sales must not block checkout");
        field(seller, "menuSort", JComboBox.class).setSelectedIndex(1);
        assertTrue(showingLabel(field(seller, "menu", JPanel.class), "Đang tải số lượng bán…"));
        seller.loadMenu();
      });
      awaitUi(() -> field(window.get().seller, "menuSales", Map.class) != null
          && field(window.get().seller, "menu", JPanel.class).getComponent(0) instanceof JButton b
          && b.getText().contains("Trà"));
      releaseFirst.countDown();
      Thread.sleep(150);
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        assertSame(sellerDish(seller, "Trà"), field(seller, "menu", JPanel.class).getComponent(0));
        long now = System.currentTimeMillis();
        Api.Invoice paid = new Api.Invoice(7, "HD00000007", "", "", "", 35000, "PAID",
            List.of(new Api.InvoiceLine("Cơm", 1, 35000, 35000)), now, now, "", "", 2,
            "fixture-session", "TTAT00000000000007", "CARD", "NONE");
        invoke(seller, "accept", Api.Invoice.class, paid);
      });
      assertTrue(paymentRefreshStarted.await(3, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        assertEquals(0, field(seller, "cartModel", javax.swing.table.DefaultTableModel.class).getRowCount());
        assertTrue(showingLabel(field(seller, "menu", JPanel.class), "Đang tải số lượng bán…"));
      });
      releasePaymentRefresh.countDown();
      awaitUi(() -> field(window.get().seller, "menuSales", Map.class) != null
          && field(window.get().seller, "menu", JPanel.class).getComponent(0) instanceof JButton b
          && b.getText().contains("Cơm"));
      failSales.set(true);
      SwingUtilities.invokeAndWait(() -> {
        field(window.get().seller, "menuSort", JComboBox.class).setSelectedIndex(0);
        window.get().seller.loadMenu();
      });
      awaitUi(() -> !window.get().isBusy()
          && field(window.get().seller, "salesWorker", SwingWorker.class) == null
          && field(window.get().seller, "menuSales", Map.class) == null);
      SwingUtilities.invokeAndWait(() -> field(window.get().seller, "menuSort", JComboBox.class).setSelectedIndex(1));
      awaitUi(() -> showingLabel(field(window.get().seller, "menu", JPanel.class),
          "Chưa tải được số lượng bán. Hãy chọn lại Bán chạy."));
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        assertTrue(showingLabel(seller, "Đã hiển thị 0 trên tổng số 2 món ăn."));
        field(seller, "menuSort", JComboBox.class).setSelectedIndex(0);
        sellerDish(seller, "Cơm").doClick(0);
        assertTrue(button(seller, "Tạo hóa đơn").isEnabled(), "A sales error must not block checkout");
        failSales.set(false);
        field(seller, "menuSort", JComboBox.class).setSelectedIndex(1);
      });
      awaitUi(() -> field(window.get().seller, "menuSales", Map.class) != null
          && field(window.get().seller, "menu", JPanel.class).getComponent(0) instanceof JButton b
          && b.getText().contains("Cơm"));
    } finally {
      releaseFirst.countDown();
      releasePaymentRefresh.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutRefreshesVisibleMenuAndCartAfterCatalogDeletion() throws Exception {
    var json = new ObjectMapper();
    Api.MenuItem rice = new Api.MenuItem(1, "Cơm gà", "Cơm", 35000, true, 1, 1, false);
    var available = new AtomicReference<>(new Api.MenuItem[] {rice});
    var deletions = new AtomicInteger();
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/menu" -> fixtureJson(json, available.get());
      case "/api/menu/categories" -> fixtureJson(json,
          new Api.MenuCategory[] {new Api.MenuCategory(1, "Cơm", available.get().length, 1)});
      case "/api/menu/sales" -> "[]";
      case "/api/menu/search" -> fixtureJson(json,
          new Api.MenuSearchResult(Arrays.asList(available.get()), available.get().length));
      case "/api/menu/items/1/delete" -> {
        assertEquals(1, requestJson(json, request).path("revision").asLong());
        deletions.incrementAndGet();
        available.set(new Api.MenuItem[0]);
        yield "{\"message\":\"Đã xóa món.\"}";
      }
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        CanteenFrame frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        frame.seller.loadMenu();
      });
      awaitIdle(window.get());
      SwingUtilities.invokeAndWait(() -> field(window.get().seller, "search", JTextField.class).setText("Cơm"));
      awaitUi(() -> components(field(window.get().seller, "menu", JPanel.class)).anyMatch(c -> c instanceof JButton b
          && b.getText() != null && b.getText().contains("Cơm gà")));
      SwingUtilities.invokeAndWait(() -> {
        sellerDish(window.get().seller, "Cơm gà").doClick(0);
        button(window.get().seller, "Quản lý thực đơn").doClick(0);
      });
      awaitIdle(window.get());
      awaitUi(() -> !field(window.get().seller, "searchPending", Boolean.class));
      var catalog = field(window.get().seller, "catalogDialog", MenuCatalogDialog.class);
      SwingUtilities.invokeLater(() -> {
        JTable table = field(catalog, "table", JTable.class);
        assertTrue(table.editCellAt(0, 4));
        JButton delete = components(table.getEditorComponent())
            .filter(c -> c instanceof JButton b
                && "Xóa món".equals(b.getAccessibleContext().getAccessibleName()))
            .map(JButton.class::cast).findFirst().orElseThrow();
        delete.doClick(0);
      });
      awaitUi(() -> ownedDialog(catalog, "Xóa món") != null);
      SwingUtilities.invokeAndWait(() -> button(ownedDialog(catalog, "Xóa món"), "Xóa món").doClick(0));
      awaitUi(() -> deletions.get() == 1 && ownedDialog(window.get(), "TTM Căn tin") != null);
      SwingUtilities.invokeAndWait(() -> optionPane(ownedDialog(window.get(), "TTM Căn tin"))
          .setValue(JOptionPane.OK_OPTION));
      awaitUi(() -> !window.get().isBusy() && !field(window.get().seller, "searchPending", Boolean.class)
          && field(catalog, "table", JTable.class).getRowCount() == 0);
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        assertEquals(0, field(seller, "cartModel", javax.swing.table.DefaultTableModel.class).getRowCount());
        assertEquals(0, field(seller, "items", Api.MenuItem[].class).length);
        assertTrue(showingLabel(field(seller, "menu", JPanel.class), "Không tìm thấy món ăn phù hợp."));
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutSearchRejectsLateResultsHandlesErrorsAndStopsOnClose() throws Exception {
    var json = new ObjectMapper();
    var firstStarted = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var queries = new CopyOnWriteArrayList<String>();
    Api.MenuItem rice = new Api.MenuItem(1, "Cơm", "Cơm", 10000, true, 1, 1, false);
    Api.MenuItem tea = new Api.MenuItem(2, "Trà", "Đồ uống", 20000, true, 2, 1, false);
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/menu" -> fixtureJson(json, new Api.MenuItem[] {rice, tea});
      case "/api/menu/categories" -> fixtureJson(json, new Api.MenuCategory[] {
          new Api.MenuCategory(1, "Cơm", 1, 1), new Api.MenuCategory(2, "Đồ uống", 1, 1)});
      case "/api/menu/search" -> {
        String query = request.uri().getRawQuery();
        queries.add(query);
        if ("query=C%C6%A1m".equals(query)) {
          firstStarted.countDown();
          boolean released = false;
          while (!released) {
            try { releaseFirst.await(); released = true; }
            catch (InterruptedException ignored) { /* Simulate an uncancellable response. */ }
          }
          yield fixtureJson(json, new Api.MenuSearchResult(List.of(rice), 2));
        }
        if ("query=L%E1%BB%97i".equals(query))
          throw new ApiClient.ApiError(503, "CONNECTION", "Không kết nối được máy chủ.");
        if ("query=Tr%C3%A0".equals(query))
          yield fixtureJson(json, new Api.MenuSearchResult(List.of(tea), 2));
        throw new AssertionError(query);
      }
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        CanteenFrame frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        frame.seller.loadMenu();
      });
      awaitIdle(window.get());
      SwingUtilities.invokeAndWait(() -> field(window.get().seller, "search", JTextField.class).setText("Cơm"));
      assertTrue(firstStarted.await(3, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        field(window.get().seller, "category", JComboBox.class).setSelectedIndex(2);
        field(window.get().seller, "search", JTextField.class).setText("Trà");
      });
      awaitUi(() -> queries.size() == 2 && components(field(window.get().seller, "menu", JPanel.class))
          .anyMatch(c -> c instanceof JButton b && b.getText() != null && b.getText().contains("Trà")));
      releaseFirst.countDown();
      Thread.sleep(150);
      SwingUtilities.invokeAndWait(() -> {
        JPanel menu = field(window.get().seller, "menu", JPanel.class);
        assertEquals(1, menu.getComponentCount());
        assertNotNull(sellerDish(window.get().seller, "Trà"));
        field(window.get().seller, "search", JTextField.class).setText("Lỗi");
      });
      awaitUi(() -> showingLabel(field(window.get().seller, "menu", JPanel.class),
          "Không tìm được món ăn. Hãy thử nhập lại."));
      SwingUtilities.invokeAndWait(() -> field(window.get().seller, "search", JTextField.class).setText("Trà"));
      awaitUi(() -> queries.size() == 4 && components(field(window.get().seller, "menu", JPanel.class))
          .anyMatch(c -> c instanceof JButton b && b.getText() != null && b.getText().contains("Trà")));
      SwingUtilities.invokeAndWait(() -> {
        field(window.get().seller, "search", JTextField.class).setText("Chưa gửi");
        window.get().dispose();
      });
      Thread.sleep(650);
      assertEquals(4, queries.size(), "Closing the counter must cancel pending search");
    } finally {
      releaseFirst.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutReloadDiscardsSearchResultsFromAnOlderCatalog() throws Exception {
    var json = new ObjectMapper();
    var firstStarted = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var searches = new AtomicInteger();
    Api.MenuItem old = new Api.MenuItem(1, "Cơm cũ", "Cơm", 10000, true, 1, 1, false);
    Api.MenuItem updated = new Api.MenuItem(1, "Cơm mới", "Cơm", 15000, true, 1, 2, false);
    var latest = new AtomicReference<>(old);
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/menu" -> fixtureJson(json, new Api.MenuItem[] {latest.get()});
      case "/api/menu/categories" -> fixtureJson(json,
          new Api.MenuCategory[] {new Api.MenuCategory(1, "Cơm", 1, 1)});
      case "/api/menu/search" -> {
        if (searches.incrementAndGet() == 1) {
          firstStarted.countDown();
          boolean released = false;
          while (!released) {
            try { releaseFirst.await(); released = true; }
            catch (InterruptedException ignored) { /* Simulate an uncancellable response. */ }
          }
          yield fixtureJson(json, new Api.MenuSearchResult(List.of(old), 1));
        }
        yield fixtureJson(json, new Api.MenuSearchResult(List.of(updated), 1));
      }
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        CanteenFrame frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        frame.seller.loadMenu();
      });
      awaitIdle(window.get());
      SwingUtilities.invokeAndWait(() -> field(window.get().seller, "search", JTextField.class).setText("Cơm"));
      assertTrue(firstStarted.await(3, TimeUnit.SECONDS));
      latest.set(updated);
      SwingUtilities.invokeAndWait(window.get().seller::loadMenu);
      awaitUi(() -> searches.get() == 2
          && components(field(window.get().seller, "menu", JPanel.class)).anyMatch(c -> c instanceof JButton b
              && b.getText() != null && b.getText().contains("Cơm mới")));
      releaseFirst.countDown();
      Thread.sleep(150);
      SwingUtilities.invokeAndWait(() -> {
        assertNotNull(sellerDish(window.get().seller, "Cơm mới"));
        assertEquals(2, field(window.get().seller, "items", Api.MenuItem[].class)[0].revision());
      });
    } finally {
      releaseFirst.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutShowsCustomerInvoiceOnlyAfterCreationAndResetsAfterCardPayment() throws Exception {
    var json = new ObjectMapper();
    var imageBytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB), "png", imageBytes);
    String dishImage = Base64.getEncoder().encodeToString(imageBytes.toByteArray());
    var verifying = new CountDownLatch(1);
    var releaseVerify = new CountDownLatch(1);
    var paying = new CountDownLatch(1);
    var releasePay = new CountDownLatch(1);
    var pinRequests = new AtomicInteger();
    var card = new Api.ReaderStatus(true, "SIM0001", true, "TTM000001", 5,
        "NONE", 0, 0, "checkout-card", "ACTIVE");
    var reader = new AtomicReference<>(new Api.ReaderStatus(false, "", false, "", 5,
        "NONE", 0, 0, "", ""));
    long now = System.currentTimeMillis();
    var line = new Api.InvoiceLine("Cơm gà", 1, 35000, 35000);
    var open = new Api.Invoice(7, "HD00000007", "", "", "", 35000, "OPEN",
        List.of(line), now, now + 300000, "", "", 1, "fixture-session",
        "TTAT00000000000007", "", "NONE");
    var paid = new Api.Invoice(7, "HD00000007", "AT010203", "Fixture", "TTM000001",
        35000, "PAID", List.of(line), now, now + 300000, "", "", 3,
        "fixture-session", "TTAT00000000000007", "CARD", "FINISHED");
    var current = new AtomicReference<>(open);
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/invoices" -> {
        assertTrue(requestJson(json, request).path("studentCode").isNull());
        yield fixtureJson(json, open);
      }
      case "/api/invoices/7" -> fixtureJson(json, current.get());
      case "/api/menu/items/1/image" -> fixtureJson(json, new Api.MenuImage(dishImage, "image/png", 1));
      case "/api/reader/connect" -> { reader.set(card); yield fixtureJson(json, card); }
      case "/api/invoices/7/prepare" ->
          replyAfter(verifying, releaseVerify, fixtureJson(json, card));
      case "/api/invoices/7/pay" -> {
        pinRequests.incrementAndGet();
        paying.countDown();
        await(releasePay);
        current.set(paid);
        yield fixtureJson(json, paid);
      }
      case "/api/reader/status" -> fixtureJson(json, reader.get());
      case "/api/reader/sync" -> fixtureJson(json,
          new Api.SyncStatus("TTM000001", "SYNCED", 0, "Đã đồng bộ"));
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    var item = new Api.MenuItem(1, "Cơm gà", "Cơm", 35000, true, 1, 1, true);
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, true, false);
        window.set(frame);
        setField(frame.seller, "items", new Api.MenuItem[] {item});
        frame.setVisible(true);
        assertEquals("Tạo hóa đơn", field(frame.seller, "invoiceAction", JButton.class).getText());
        assertFalse(field(frame.seller, "customerPay", JButton.class).isEnabled());
        assertFalse(field(frame.seller, "pinOpen", Boolean.class));
        assertTrue(components(field(frame.seller, "tabs", JTabbedPane.class).getComponentAt(0))
            .noneMatch(c -> c instanceof JButton b &&
                ("Tiến hành thanh toán".equals(b.getText()) || "Đơn hàng mới".equals(b.getText()))));
        invoke(frame.seller, "add", Api.MenuItem.class, item);
        assertEquals(1, field(frame.seller, "cartModel", javax.swing.table.DefaultTableModel.class).getRowCount());
        assertEquals(0, field(frame.seller, "customerModel", javax.swing.table.DefaultTableModel.class).getRowCount());
        button(frame.seller, "Tạo hóa đơn").doClick(0);
      });
      awaitIdle(window.get());
      awaitUi(() -> {
        CheckoutView seller = window.get().seller;
        var cart = field(seller, "cartModel", javax.swing.table.DefaultTableModel.class);
        return cart.getRowCount() == 1 && cart.getValueAt(0, 0)
            != field(seller, "images", DishImages.class).placeholder(80);
      });
      SwingUtilities.invokeAndWait(() -> {
        var frame = window.get();
        assertEquals(1, field(frame.seller, "tabs", JTabbedPane.class).getSelectedIndex());
        assertEquals(1, field(frame.seller, "customerModel", javax.swing.table.DefaultTableModel.class).getRowCount());
        assertTrue(field(frame.seller, "cartOverlay", JPanel.class).isVisible());
        assertEquals("Hóa đơn đang chờ thanh toán!", field(frame.seller, "cartOverlayText", JLabel.class).getText());
        JTable table = field(frame.seller, "cartTable", JTable.class);
        assertFalse(table.isEnabled());
        assertFalse(table.isCellEditable(0, 2));
        assertFalse(table.isCellEditable(0, 5));
        assertEquals("Cơm gà", table.getValueAt(0, 1));
        assertEquals(1, table.getValueAt(0, 2));
        assertEquals(CanteenFrame.money(35000), table.getValueAt(0, 3));
        assertEquals(80, ((Icon) table.getValueAt(0, 0)).getIconWidth());
        assertEquals(4, field(frame.seller, "customerModel", javax.swing.table.DefaultTableModel.class).getColumnCount());
        assertEquals("Hủy hóa đơn", field(frame.seller, "invoiceAction", JButton.class).getText());
        assertTrue(field(frame.seller, "invoiceAction", JButton.class).isEnabled());
        assertFalse(field(frame.seller, "customerPay", JButton.class).isEnabled());
        field(frame.seller, "tabs", JTabbedPane.class).setSelectedIndex(0);
        assertDoesNotThrow(() -> {
          Files.createDirectories(Path.of("target/ui-check"));
          ImageIO.write(painted(frame), "png", Path.of("target/ui-check/checkout-waiting.png").toFile());
        });
        field(frame.seller, "tabs", JTabbedPane.class).setSelectedIndex(1);
        field(frame, "deviceSearch", DeviceSearch.class).setChoices(
            new Api.DeviceChoice[] {new Api.DeviceChoice("SIM0001", "SIM0001")}, "SIM0001");
        field(frame, "connectButton", JButton.class).doClick(0);
      });
      awaitUi(() -> !window.get().isBusy() && window.get().status().present());
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(0, pinRequests.get());
        assertEquals(1, verifying.getCount(), "Connecting a card must not start invoice authentication");
        assertTrue(field(window.get().seller, "customerPay", JButton.class).isEnabled());
        field(window.get().seller, "customerPay", JButton.class).doClick(0);
      });
      await(verifying);
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(0, pinRequests.get());
        assertFalse(field(window.get().seller, "pinOpen", Boolean.class));
      });
      releaseVerify.countDown();
      awaitUi(() -> field(window.get().seller, "pinOpen", Boolean.class));
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(0, pinRequests.get());
        assertFalse(field(window.get().seller, "customerPay", JButton.class).isEnabled());
        SixDigitEntry pin = field(window.get().seller, "customerPin", SixDigitEntry.class);
        enterPin(pin, "123456");
        button(pin, "Xác nhận thanh toán").doClick(0);
      });
      await(paying);
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("Hủy hóa đơn", field(window.get().seller, "invoiceAction", JButton.class).getText());
        assertFalse(field(window.get().seller, "invoiceAction", JButton.class).isEnabled());
        assertEquals("Đang tiến hành thanh toán bằng thẻ!",
            field(window.get().seller, "cartOverlayText", JLabel.class).getText());
        assertEquals(1, field(window.get().seller, "customerItems", JPanel.class).getComponentCount(),
            "The invoice remains visible until payment is confirmed");
      });
      releasePay.countDown();
      awaitUi(() -> "PAID".equals(field(window.get().seller, "invoice", Api.Invoice.class).status()));
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        assertFalse(field(seller, "cartOverlay", JPanel.class).isVisible());
        assertEquals(0, field(seller, "cartModel", javax.swing.table.DefaultTableModel.class).getRowCount());
        assertEquals(0, field(seller, "customerModel", javax.swing.table.DefaultTableModel.class).getRowCount());
        assertEquals(0, field(seller, "customerItems", JPanel.class).getComponentCount());
        assertEquals("0 ₫", field(seller, "customerTotal", JLabel.class).getText());
        assertTrue(field(seller, "customerTitle", JLabel.class).getText().isBlank());
        assertTrue(field(seller, "customerHelp", JLabel.class).getText().isBlank());
        assertFalse(field(seller, "pinOpen", Boolean.class));
        assertEquals("Thanh toán hoá đơn thành công!", toastText(seller));
        assertEquals("Tạo hóa đơn", field(seller, "invoiceAction", JButton.class).getText());
        assertTrue(field(seller, "invoiceAction", JButton.class).isEnabled());
        invoke(seller, "add", Api.MenuItem.class, item);
        assertTrue(field(seller, "invoiceAction", JButton.class).isEnabled());
        assertEquals(0, field(seller, "customerModel", javax.swing.table.DefaultTableModel.class).getRowCount());
      });
    } finally {
      releaseVerify.countDown();
      releasePay.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutCentersTheEmptyInvoicePlaceholderAndHidesItForActiveInvoices() throws Exception {
    ApiClient api = api(request -> "[]");
    SwingUtilities.invokeAndWait(() -> {
      TTM_CuoiKy.configureLookAndFeel();
      CanteenFrame frame = new CanteenFrame(api, true, false);
      try {
        frame.setLocation(-3000, -3000);
        frame.setSize(1280, 880);
        frame.setVisible(true);
        CheckoutView seller = frame.seller;
        field(seller, "tabs", JTabbedPane.class).setSelectedIndex(1);
        JPanel rows = field(seller, "customerItems", JPanel.class);
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, rows);
        for (int height : new int[] {880, 1050}) {
          frame.setSize(1280, height);
          frame.validate();
          JLabel placeholder = label(scroll, "Hiện chưa có hoá đơn.");
          assertNotNull(placeholder);
          JViewport viewport = scroll.getViewport();
          java.awt.Point center = SwingUtilities.convertPoint(placeholder,
              placeholder.getWidth() / 2, placeholder.getHeight() / 2, viewport);
          assertTrue(Math.abs(center.x - viewport.getWidth() / 2) <= 1);
          assertTrue(Math.abs(center.y - viewport.getHeight() / 2) <= 1);
          assertEquals(528, scroll.getHeight());
        }
        frame.setSize(1280, 880);
        frame.validate();
        assertDoesNotThrow(() -> {
          Files.createDirectories(Path.of("target/ui-check"));
          ImageIO.write(painted(frame), "png", Path.of("target/ui-check/customer-invoice-empty.png").toFile());
        });
        long now = System.currentTimeMillis();
        var line = new Api.InvoiceLine("Cơm gà", 1, 35000, 35000);
        var open = new Api.Invoice(7, "HD00000007", "", "", "", 35000, "OPEN",
            List.of(line), now, now + 300000, "", "", 1, "fixture", "", "", "NONE");
        var pending = new Api.Invoice(7, open.number(), "", "", "", 35000, "PAYMENT_PENDING",
            List.of(line), now, now + 300000, "", "", 2, "fixture", "", "", "CARD_AUTH");
        var paid = new Api.Invoice(7, open.number(), "", "", "", 35000, "PAID",
            List.of(line), now, now + 300000, "", "", 3, "fixture", "", "CARD", "FINISHED");
        invoke(seller, "accept", Api.Invoice.class, open);
        assertNull(label(scroll, "Hiện chưa có hoá đơn."));
        assertEquals(1, rows.getComponentCount());
        invoke(seller, "accept", Api.Invoice.class, pending);
        assertNull(label(scroll, "Hiện chưa có hoá đơn."));
        assertEquals(1, rows.getComponentCount());
        invoke(seller, "accept", Api.Invoice.class, paid);
        frame.validate();
        assertNotNull(label(scroll, "Hiện chưa có hoá đơn."));
        assertEquals(0, rows.getComponentCount());
        var emptyOpen = new Api.Invoice(8, "HD00000008", "", "", "", 0, "OPEN",
            List.of(), now, now + 300000, "", "", 1, "fixture", "", "", "NONE");
        var emptyCancelled = new Api.Invoice(8, emptyOpen.number(), "", "", "", 0, "CANCELLED",
            List.of(), now, now + 300000, "", "USER_CANCELLED", 2, "fixture", "", "", "FINISHED");
        invoke(seller, "accept", Api.Invoice.class, emptyOpen);
        assertNull(label(scroll, "Hiện chưa có hoá đơn."));
        invoke(seller, "accept", Api.Invoice.class, emptyCancelled);
        frame.validate();
        assertNotNull(label(scroll, "Hiện chưa có hoá đơn."));
        List<Api.InvoiceLine> many = java.util.stream.IntStream.rangeClosed(1, 6)
            .mapToObj(i -> new Api.InvoiceLine("Món " + i, 1, 10000, 10000)).toList();
        var longOpen = new Api.Invoice(9, "HD00000009", "", "", "", 60000, "OPEN",
            many, now, now + 300000, "", "", 1, "fixture", "", "", "NONE");
        var longCancelled = new Api.Invoice(9, longOpen.number(), "", "", "", 60000, "CANCELLED",
            many, now, now + 300000, "", "USER_CANCELLED", 2, "fixture", "", "", "FINISHED");
        invoke(seller, "accept", Api.Invoice.class, longOpen);
        frame.validate();
        scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
        assertTrue(scroll.getViewport().getViewPosition().y > 0);
        invoke(seller, "accept", Api.Invoice.class, longCancelled);
        frame.validate();
        JLabel placeholder = label(scroll, "Hiện chưa có hoá đơn.");
        assertNotNull(placeholder);
        JViewport viewport = scroll.getViewport();
        java.awt.Point center = SwingUtilities.convertPoint(placeholder,
            placeholder.getWidth() / 2, placeholder.getHeight() / 2, viewport);
        assertTrue(Math.abs(center.x - viewport.getWidth() / 2) <= 1);
        assertTrue(Math.abs(center.y - viewport.getHeight() / 2) <= 1);
      } finally {
        frame.dispose();
      }
    });
  }

  @Test
  void checkoutCustomerListShowsFiveRowsAndSpacesAllSectionsEvenly() throws Exception {
    ApiClient api = new ApiClient();
    SwingUtilities.invokeAndWait(() -> {
      TTM_CuoiKy.configureLookAndFeel();
      CanteenFrame frame = new CanteenFrame(api, true, false);
      try {
        frame.setLocation(-3000, -3000);
        frame.setSize(1280, 880);
        frame.setVisible(true);
        CheckoutView seller = frame.seller;
        field(seller, "tabs", JTabbedPane.class).setSelectedIndex(1);
        JPanel rows = field(seller, "customerItems", JPanel.class);
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, rows);
        JPanel panel = (JPanel) scroll.getParent();
        long now = System.currentTimeMillis();
        List<Api.InvoiceLine> lines = java.util.stream.IntStream.rangeClosed(1, 6)
            .mapToObj(i -> new Api.InvoiceLine("Món " + i, i, 10000, i * 10000L)).toList();
        var invoice = new Api.Invoice(7, "HD00000007", "", "", "", 210000, "OPEN",
            lines, now, now + 300000, "", "", 1, "fixture",
            "TTAT00000000000007", "", "NONE");
        invoke(seller, "accept", Api.Invoice.class, invoice);

        for (int height : new int[] {880, 1050}) {
          frame.setSize(1280, height);
          frame.validate();
          assertEquals(5, panel.getComponentCount());
          JLabel title = (JLabel) panel.getComponent(0);
          Component titleDivider = panel.getComponent(1);
          Component divider = panel.getComponent(3);
          Component total = panel.getComponent(4);
          assertEquals("Chi tiết hoá đơn", title.getText());
          assertEquals(24, title.getFont().getSize());
          assertTrue(title.getFont().isBold());
          assertSame(scroll, panel.getComponent(2));
          assertEquals(16, title.getY());
          assertEquals(panel.getHeight() - 16, total.getY() + total.getHeight());
          assertEquals(528, scroll.getHeight());
          assertEquals(528, scroll.getViewport().getExtentSize().height);
          assertEquals(1, titleDivider.getHeight());
          assertEquals(1, divider.getHeight());
          int[] gaps = java.util.stream.IntStream.range(0, 4).map(index -> {
            Component before = panel.getComponent(index);
            Component after = panel.getComponent(index + 1);
            return after.getY() - before.getY() - before.getHeight();
          }).toArray();
          for (int gap : gaps) assertTrue(Math.abs(gaps[0] - gap) <= 1);
          assertEquals(new java.awt.Insets(8, 8, 8, 8), rows.getInsets());
          assertEquals(6, rows.getComponentCount());
          for (int index = 0; index < 6; index++) {
            Component row = rows.getComponent(index);
            assertEquals(8 + index * 104, row.getY());
            assertEquals(96, row.getHeight());
            JLabel picture = components(row).filter(c -> c instanceof JLabel l && l.getIcon() != null)
                .map(JLabel.class::cast).findFirst().orElseThrow();
            assertEquals(80, picture.getIcon().getIconWidth());
            assertEquals(80, picture.getIcon().getIconHeight());
          }
          Rectangle visible = scroll.getViewport().getViewRect();
          for (int index = 0; index < 5; index++)
            assertTrue(visible.contains(SwingUtilities.convertRectangle(
                rows, rows.getComponent(index).getBounds(), scroll.getViewport().getView())));
          assertFalse(visible.intersects(SwingUtilities.convertRectangle(
              rows, rows.getComponent(5).getBounds(), scroll.getViewport().getView())));
        }
        assertEquals(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, scroll.getVerticalScrollBarPolicy());
        scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
        assertTrue(scroll.getViewport().getViewRect().contains(SwingUtilities.convertRectangle(
            rows, rows.getComponent(5).getBounds(), scroll.getViewport().getView())));
        frame.setSize(1280, 880);
        scroll.getVerticalScrollBar().setValue(0);
        frame.validate();
        assertDoesNotThrow(() -> {
          Files.createDirectories(Path.of("target/ui-check"));
          ImageIO.write(painted(frame), "png", Path.of("target/ui-check/customer-invoice-divider.png").toFile());
        });
      } finally {
        frame.dispose();
      }
    });
  }

  @Test
  void checkoutRetriesInlinePinAndKeepsInvoiceWhenCardIsBlocked() throws Exception {
    var json = new ObjectMapper();
    long now = System.currentTimeMillis();
    var line = new Api.InvoiceLine("Cơm gà", 2, 35000, 70000);
    var open = new Api.Invoice(7, "HD00000007", "", "", "", 70000, "OPEN",
        List.of(line), now, now + 300000, "", "", 1, "fixture-session",
        "TTAT00000000000007", "", "NONE");
    var pending = new Api.Invoice(7, "HD00000007", "AT010203", "Fixture", "fixture",
        70000, "PAYMENT_PENDING", List.of(line), now, now + 300000, "", "", 2,
        "fixture-session", open.paymentReference(), "", "CARD_AUTH");
    var waitingAgain = new Api.Invoice(7, "HD00000007", "", "", "", 70000, "OPEN",
        List.of(line), now, now + 300000, "", "", 3, "fixture-session",
        open.paymentReference(), "", "NONE");
    var card = new Api.ReaderStatus(true, "Fixture", true, "fixture", 4,
        "NONE", 0, 0, "fixture-connection", "ACTIVE");
    var afterWrong = new Api.ReaderStatus(true, "Fixture", true, "fixture", 3,
        "NONE", 0, 0, "fixture-connection", "ACTIVE");
    var blocked = new Api.ReaderStatus(true, "Fixture", true, "fixture", 0,
        "NONE", 0, 0, "fixture-connection", "BLOCKED");
    var reader = new AtomicReference<>(card);
    var current = new AtomicReference<>(open);
    var preparations = new AtomicInteger();
    var attempts = new AtomicInteger();
    var attemptIds = new java.util.concurrent.CopyOnWriteArrayList<String>();
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/invoices/7/prepare" -> {
        preparations.incrementAndGet();
        yield fixtureJson(json, card);
      }
      case "/api/invoices/7/pay" -> {
        JsonNode body = requestJson(json, request);
        assertEquals("000000", body.path("pin").asText());
        attemptIds.add(body.path("attemptId").asText());
        if (attempts.incrementAndGet() == 1) {
          current.set(pending);
          reader.set(afterWrong);
          throw new ApiClient.ApiError(409, "PIN_WRONG", "Mã PIN không đúng. Còn 4 lần thử.");
        }
        current.set(waitingAgain);
        reader.set(blocked);
        throw new ApiClient.ApiError(409, "CARD_BLOCKED", "Thẻ đã bị khóa.");
      }
      case "/api/invoices/7" -> fixtureJson(json, current.get());
      case "/api/reader/status" -> fixtureJson(json, reader.get());
      case "/api/reader/sync" -> fixtureJson(json,
          new Api.SyncStatus("fixture", "SYNCED", 0, "Đã đồng bộ"));
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    var digitBounds = new AtomicReference<Rectangle>();
    var submitBounds = new AtomicReference<Rectangle>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        CanteenFrame frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        field(frame.seller, "tabs", JTabbedPane.class).setSelectedIndex(1);
        assertEquals("Không phát hiện thẻ", field(frame.seller, "cardTitle", JLabel.class).getText());
        assertEquals("------------", field(frame.seller, "connectedCardId", JLabel.class).getText());
        assertFalse(field(frame.seller, "customerPay", JButton.class).isEnabled());
        assertFalse(field(frame.seller, "customerBank", JButton.class).isEnabled());
        invoke(frame.seller, "accept", Api.Invoice.class, open);
        assertTrue(field(frame.seller, "customerBank", JButton.class).isEnabled());
        assertEquals("70.000 ₫", field(frame.seller, "customerTotal", JLabel.class).getText());
        JPanel rows = field(frame.seller, "customerItems", JPanel.class);
        assertEquals(1, rows.getComponentCount());
        assertTrue(components(rows).anyMatch(c -> c instanceof JLabel l && "2".equals(l.getText())));
        invoke(frame, "updateStatus", Api.ReaderStatus.class, card);
        assertEquals("Đã phát hiện thẻ", field(frame.seller, "cardTitle", JLabel.class).getText());
        assertEquals("fixture", field(frame.seller, "connectedCardId", JLabel.class).getText());
        assertEquals(0, preparations.get());
        field(frame.seller, "customerPay", JButton.class).doClick(0);
      });
      awaitUi(() -> field(window.get().seller, "pinOpen", Boolean.class));
      SwingUtilities.invokeAndWait(() -> {
        SixDigitEntry entry = field(window.get().seller, "customerPin", SixDigitEntry.class);
        assertTrue(entry.isShowing());
        assertTrue(field(window.get().seller, "customerItems", JPanel.class).isShowing());
        digitBounds.set(field(entry, "digitRow", JPanel.class).getBounds());
        submitBounds.set(button(entry, "Xác nhận thanh toán").getBounds());
        assertDoesNotThrow(() -> {
          Files.createDirectories(Path.of("target/ui-check"));
          ImageIO.write(painted(window.get()), "png", Path.of("target/ui-check/checkout-inline-pin.png").toFile());
        });
        enterPin(entry, "000000");
        button(entry, "Xác nhận thanh toán").doClick(0);
      });
      awaitUi(() -> attempts.get() == 1 && !window.get().isBusy()
          && field(field(window.get().seller, "customerPin", SixDigitEntry.class), "feedback", JLabel.class)
              .getText().equals("Bạn còn 3 lần nhập sai!"));
      awaitUi(() -> {
        Toast toast = Toast.forComponent(window.get());
        return toast.isShowing() && toast.getY() + toast.getHeight()
            <= window.get().getLayeredPane().getHeight() - 32;
      });
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(field(window.get().seller, "pinOpen", Boolean.class));
        assertEquals(7, field(window.get().seller, "invoice", Api.Invoice.class).id());
        SixDigitEntry entry = field(window.get().seller, "customerPin", SixDigitEntry.class);
        JLabel feedback = field(entry, "feedback", JLabel.class);
        JLabel warning = field(entry, "warning", JLabel.class);
        assertTrue(feedback.isVisible());
        assertEquals("Nếu nhập sai quá số lần cho phép, thẻ sẽ bị khóa!", warning.getText());
        assertTrue(warning.isVisible());
        assertTrue(feedback.getFont().isBold());
        assertFalse(warning.getFont().isBold());
        assertEquals(SwingConstants.CENTER, feedback.getHorizontalAlignment());
        assertEquals(SwingConstants.CENTER, warning.getHorizontalAlignment());
        assertTrue(feedback.getY() < warning.getY());
        assertEquals(digitBounds.get(), field(entry, "digitRow", JPanel.class).getBounds());
        assertEquals(submitBounds.get(), button(entry, "Xác nhận thanh toán").getBounds());
        Toast toast = Toast.forComponent(window.get());
        assertTrue(toast.isShowing());
        assertEquals("Mã PIN không đúng!", field(toast, "message", JTextPane.class).getText());
        assertEquals(TTM_CuoiKy.ERROR_TEXT_COLOR, field(toast, "message", JTextPane.class).getForeground());
        assertEquals(3_000, field(toast, "timeout", Timer.class).getInitialDelay());
        assertDoesNotThrow(() -> ImageIO.write(painted(window.get()), "png",
            Path.of("target/ui-check/checkout-wrong-pin.png").toFile()));
        enterPin(entry, "000000");
        assertFalse(feedback.isVisible());
        assertFalse(warning.isVisible());
        button(entry, "Xác nhận thanh toán").doClick(0);
      });
      awaitUi(() -> attempts.get() == 2 && !field(window.get().seller, "pinOpen", Boolean.class)
          && "OPEN".equals(field(window.get().seller, "invoice", Api.Invoice.class).status()));
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(1, preparations.get());
        assertEquals(2, attemptIds.size());
        assertNotEquals(attemptIds.get(0), attemptIds.get(1));
        assertTrue(field(window.get().seller, "customerBank", JButton.class).isEnabled());
        assertEquals(1, field(window.get().seller, "customerItems", JPanel.class).getComponentCount());
        assertEquals("70.000 ₫", field(window.get().seller, "customerTotal", JLabel.class).getText());
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutKeepsInvoiceLayoutAndAnimatesWaitingUntilCancellation() throws Exception {
    var json = new ObjectMapper();
    long now = System.currentTimeMillis();
    var line = new Api.InvoiceLine("Cơm gà", 1, 35000, 35000);
    var open = new Api.Invoice(7, "HD00000007", "", "", "", 35000, "OPEN",
        List.of(line), now, now + 300000, "", "", 1, "fixture-session",
        "TTAT00000000000007", "", "NONE");
    var cancelled = new Api.Invoice(7, "HD00000007", "", "", "", 35000, "CANCELLED",
        List.of(line), now, now + 300000, "", "USER_CANCELLED", 2, "fixture-session",
        "TTAT00000000000007", "", "FINISHED");
    var current = new AtomicReference<>(open);
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/invoices" -> fixtureJson(json, open);
      case "/api/invoices/7/cancel" -> {
        current.set(cancelled);
        yield "{\"message\":\"Đã nhận yêu cầu\"}";
      }
      case "/api/invoices/7" -> fixtureJson(json, current.get());
      default -> throw new AssertionError(request.uri().getPath());
    });
    var item = new Api.MenuItem(1, "Cơm gà", "Cơm", 35000, true, 1, 1, false);
    var window = new AtomicReference<CanteenFrame>();
    var beforeCreationBounds = new AtomicReference<Rectangle[]>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, true, false);
        window.set(frame);
        setField(frame.seller, "items", new Api.MenuItem[] {item});
        frame.setSize(1280, 880);
        frame.setVisible(true);
        invoke(frame.seller, "add", Api.MenuItem.class, item);
        frame.validate();
        JTabbedPane tabs = field(frame.seller, "tabs", JTabbedPane.class);
        JPanel right = (JPanel) ((JSplitPane) tabs.getComponentAt(0)).getRightComponent();
        frame.seller.showNotice("Đã kết nối thẻ.", false);
        frame.validate();
        beforeCreationBounds.set(Arrays.stream(right.getComponents()).map(Component::getBounds).toArray(Rectangle[]::new));
        field(frame.seller, "invoiceAction", JButton.class).doClick(0);
      });
      awaitUi(() -> field(window.get().seller, "invoice", Api.Invoice.class) != null
          && "OPEN".equals(field(window.get().seller, "invoice", Api.Invoice.class).status())
          && !window.get().isBusy());
      SwingUtilities.invokeAndWait(() -> {
        var frame = window.get();
        var seller = frame.seller;
        JTabbedPane tabs = field(seller, "tabs", JTabbedPane.class);
        assertEquals(1, tabs.getSelectedIndex());
        assertFalse(field(seller, "cartLoadingTimer", Timer.class).isRunning());
        tabs.setSelectedIndex(0);
        frame.validate();
        JPanel right = (JPanel) ((JSplitPane) tabs.getComponentAt(0)).getRightComponent();
        assertArrayEquals(beforeCreationBounds.get(),
            Arrays.stream(right.getComponents()).map(Component::getBounds).toArray(Rectangle[]::new));
        assertEquals("Hủy hóa đơn", field(seller, "invoiceAction", JButton.class).getText());
        JPanel overlay = field(seller, "cartOverlay", JPanel.class);
        JLabel spinner = field(seller, "cartLoadingIcon", JLabel.class);
        JLabel description = field(seller, "cartOverlayText", JLabel.class);
        assertTrue(overlay.isShowing());
        assertTrue(field(seller, "cartLoadingTimer", Timer.class).isRunning());
        assertEquals(new java.awt.Dimension(64, 64), spinner.getSize());
        assertTrue(Math.abs(2 * spinner.getX() + spinner.getWidth() - overlay.getWidth()) <= 1);
        assertTrue(Math.abs(2 * spinner.getY() + spinner.getHeight() - overlay.getHeight()) <= 1);
        assertTrue(Math.abs(2 * description.getX() + description.getWidth() - overlay.getWidth()) <= 1);
        assertEquals(spinner.getY() + 64 + 16, description.getY());
        assertEquals("Hóa đơn đang chờ thanh toán!", description.getText());
        BufferedImage image = painted(spinner);
        assertTrue(Arrays.stream(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()))
            .anyMatch(pixel -> (pixel & 0xffffffffL) == 0xff606060L), "SVG phải có màu xám trung tính");
        assertDoesNotThrow(() -> {
          Files.createDirectories(Path.of("target/ui-check"));
          ImageIO.write(painted(frame), "png", Path.of("target/ui-check/checkout-waiting-spinner.png").toFile());
        });
      });
      double firstAngle = field(window.get().seller, "cartLoadingAngle", Double.class);
      awaitUi(() -> field(window.get().seller, "cartLoadingAngle", Double.class) != firstAngle);
      SwingUtilities.invokeAndWait(() -> {
        var seller = window.get().seller;
        JTabbedPane tabs = field(seller, "tabs", JTabbedPane.class);
        tabs.setSelectedIndex(1);
        assertFalse(field(seller, "cartLoadingTimer", Timer.class).isRunning());
        tabs.setSelectedIndex(0);
        assertTrue(field(seller, "cartLoadingTimer", Timer.class).isRunning());
        seller.showNotice("Thông báo cũ", false);
        SwingUtilities.invokeLater(() -> field(seller, "invoiceAction", JButton.class).doClick(0));
      });
      awaitUi(() -> ownedDialog(window.get(), "Hủy hóa đơn") != null);
      SwingUtilities.invokeAndWait(() ->
          optionPane(ownedDialog(window.get(), "Hủy hóa đơn")).setValue(JOptionPane.OK_OPTION));
      awaitUi(() -> !window.get().isBusy()
          && "CANCELLED".equals(field(window.get().seller, "invoice", Api.Invoice.class).status()));
      awaitUi(() -> "Hoá đơn đã bị huỷ.".equals(toastText(window.get())));
      SwingUtilities.invokeAndWait(() -> {
        var frame = window.get();
        var seller = frame.seller;
        assertNull(ownedDialog(frame, "TTM Căn tin"));
        frame.validate();
        JPanel right = (JPanel) ((JSplitPane) field(seller, "tabs", JTabbedPane.class)
            .getComponentAt(0)).getRightComponent();
        assertArrayEquals(beforeCreationBounds.get(),
            Arrays.stream(right.getComponents()).map(Component::getBounds).toArray(Rectangle[]::new));
        assertFalse(field(seller, "cartOverlay", JPanel.class).isVisible());
        assertFalse(field(seller, "cartLoadingTimer", Timer.class).isRunning());
        assertTrue(field(seller, "cartTable", JTable.class).isEnabled());
        assertEquals(0, field(seller, "cartModel", javax.swing.table.DefaultTableModel.class).getRowCount());
        assertEquals("Tạo hóa đơn", field(seller, "invoiceAction", JButton.class).getText());
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutCancellationWaitsForTheAuthoritativeResultAndClearsBothTables() throws Exception {
    var json = new ObjectMapper();
    long now = System.currentTimeMillis();
    var line = new Api.InvoiceLine("Cơm gà", 1, 35000, 35000);
    var open = new Api.Invoice(7, "HD00000007", "", "", "", 35000, "OPEN",
        List.of(line), now, now + 300000, "", "", 1, "fixture-session",
        "TTAT00000000000007", "", "NONE");
    var cancelled = new Api.Invoice(7, "HD00000007", "", "", "", 35000, "CANCELLED",
        List.of(line), now, now + 300000, "", "USER_CANCELLED", 2, "fixture-session",
        "TTAT00000000000007", "", "FINISHED");
    var current = new AtomicReference<>(open);
    var cancellations = new AtomicInteger();
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/invoices/7/cancel" -> {
        cancellations.incrementAndGet();
        current.set(cancelled);
        yield "{\"message\":\"Đã nhận yêu cầu\"}";
      }
      case "/api/invoices/7" -> fixtureJson(json, current.get());
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    var originalBounds = new AtomicReference<Rectangle[]>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        var frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        frame.validate();
        var tabs = field(frame.seller, "tabs", JTabbedPane.class);
        JPanel right = (JPanel) ((JSplitPane) tabs.getComponentAt(0)).getRightComponent();
        originalBounds.set(Arrays.stream(right.getComponents()).map(Component::getBounds).toArray(Rectangle[]::new));
        invoke(frame.seller, "accept", Api.Invoice.class, open);
        assertEquals("Hủy hóa đơn", field(frame.seller, "invoiceAction", JButton.class).getText());
        assertTrue(field(frame.seller, "invoiceAction", JButton.class).isEnabled());
        SwingUtilities.invokeLater(() -> field(frame.seller, "invoiceAction", JButton.class).doClick(0));
      });
      awaitUi(() -> ownedDialog(window.get(), "Hủy hóa đơn") != null);
      SwingUtilities.invokeAndWait(() -> {
        JOptionPane confirmation = optionPane(ownedDialog(window.get(), "Hủy hóa đơn"));
        assertTrue(confirmation.getMessage().toString().contains("Hóa đơn vẫn đang chờ thanh toán"));
        confirmation.setValue(JOptionPane.CANCEL_OPTION);
      });
      awaitUi(() -> ownedDialog(window.get(), "Hủy hóa đơn") == null);
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(0, cancellations.get());
        assertNull(ownedDialog(window.get(), "TTM Căn tin"));
        SwingUtilities.invokeLater(() -> field(window.get().seller, "invoiceAction", JButton.class).doClick(0));
      });
      awaitUi(() -> ownedDialog(window.get(), "Hủy hóa đơn") != null);
      SwingUtilities.invokeAndWait(() -> {
        JOptionPane confirmation = optionPane(ownedDialog(window.get(), "Hủy hóa đơn"));
        confirmation.setValue(JOptionPane.OK_OPTION);
      });
      awaitUi(() -> cancellations.get() == 1 && !window.get().isBusy()
          && "CANCELLED".equals(field(window.get().seller, "invoice", Api.Invoice.class).status()));
      awaitUi(() -> "Hoá đơn đã bị huỷ.".equals(toastText(window.get())));
      SwingUtilities.invokeAndWait(() -> {
        var seller = window.get().seller;
        assertNull(ownedDialog(window.get(), "TTM Căn tin"));
        window.get().validate();
        var tabs = field(seller, "tabs", JTabbedPane.class);
        JPanel right = (JPanel) ((JSplitPane) tabs.getComponentAt(0)).getRightComponent();
        assertArrayEquals(originalBounds.get(),
            Arrays.stream(right.getComponents()).map(Component::getBounds).toArray(Rectangle[]::new));
        assertFalse(field(seller, "cartOverlay", JPanel.class).isVisible());
        assertEquals(0, field(seller, "cartModel", javax.swing.table.DefaultTableModel.class).getRowCount());
        assertEquals(0, field(seller, "customerModel", javax.swing.table.DefaultTableModel.class).getRowCount());
        assertEquals("Tạo hóa đơn", field(seller, "invoiceAction", JButton.class).getText());
        assertTrue(field(seller, "invoiceAction", JButton.class).isEnabled());
      });
      SwingUtilities.invokeLater(() -> field(window.get().seller, "invoiceAction", JButton.class).doClick(0));
      awaitUi(() -> ownedDialog(window.get(), "TTM Căn tin") != null);
      SwingUtilities.invokeAndWait(() -> {
        JOptionPane notice = optionPane(ownedDialog(window.get(), "TTM Căn tin"));
        assertEquals("Hãy chọn ít nhất một món trước khi tạo hóa đơn.", notice.getMessage());
        notice.setValue(JOptionPane.OK_OPTION);
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutShowsCancellationSuccessOnlyAfterPendingReconciliationCompletes() throws Exception {
    var json = new ObjectMapper();
    long now = System.currentTimeMillis();
    var open = invoice("OPEN", "", 1, now);
    var pending = invoice("CANCEL_PENDING", "USER_CANCELLED", 2, now);
    var cancelled = invoice("CANCELLED", "USER_CANCELLED", 3, now);
    var current = new AtomicReference<>(open);
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/invoices/7/cancel" -> {
        current.set(pending);
        yield "{\"message\":\"Đã nhận yêu cầu\"}";
      }
      case "/api/invoices/7" -> fixtureJson(json, current.get());
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    var notices = new AtomicInteger();
    try {
      SwingUtilities.invokeAndWait(() -> {
        var frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        invoke(frame.seller, "accept", Api.Invoice.class, open);
        SwingUtilities.invokeLater(() -> field(frame.seller, "invoiceAction", JButton.class).doClick(0));
      });
      awaitUi(() -> ownedDialog(window.get(), "Hủy hóa đơn") != null);
      SwingUtilities.invokeAndWait(() ->
          optionPane(ownedDialog(window.get(), "Hủy hóa đơn")).setValue(JOptionPane.OK_OPTION));
      awaitUi(() -> !window.get().isBusy()
          && "CANCEL_PENDING".equals(field(window.get().seller, "invoice", Api.Invoice.class).status())
          && !field(window.get().seller, "pollingInvoice", Boolean.class));
      SwingUtilities.invokeAndWait(() -> {
        var seller = window.get().seller;
        assertNull(ownedDialog(window.get(), "TTM Căn tin"), "Chưa được báo hủy khi đang đối soát");
        assertTrue(field(seller, "cartOverlay", JPanel.class).isVisible());
        assertFalse(field(seller, "invoiceAction", JButton.class).isEnabled());
        JTextPane notice = field(Toast.forComponent(window.get()), "message", JTextPane.class);
        notice.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
          public void insertUpdate(javax.swing.event.DocumentEvent event) { notices.incrementAndGet(); }
          public void removeUpdate(javax.swing.event.DocumentEvent event) {}
          public void changedUpdate(javax.swing.event.DocumentEvent event) {}
        });
        assertFalse("Hoá đơn đã bị huỷ.".equals(notice.getText()));
        current.set(cancelled);
        invokeNoArgs(seller, "pollInvoice");
      });
      awaitUi(() -> "Hoá đơn đã bị huỷ.".equals(toastText(window.get())));
      SwingUtilities.invokeAndWait(() -> {
        var seller = window.get().seller;
        assertEquals("CANCELLED", field(seller, "invoice", Api.Invoice.class).status());
        assertNull(ownedDialog(window.get(), "TTM Căn tin"));
        assertEquals(1, notices.get());
        invoke(seller, "accept", Api.Invoice.class, cancelled);
        assertEquals(1, notices.get(), "Cập nhật trùng trạng thái không được hiện lại toast");
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutDoesNotReportManualCancellationWhenInvoiceExpired() throws Exception {
    var json = new ObjectMapper();
    long now = System.currentTimeMillis();
    var open = invoice("OPEN", "", 1, now);
    var expired = invoice("CANCELLED", "QR_EXPIRED", 2, now);
    var current = new AtomicReference<>(open);
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/invoices/7/cancel" -> {
        current.set(expired);
        yield "{\"message\":\"Đã nhận yêu cầu\"}";
      }
      case "/api/invoices/7" -> fixtureJson(json, current.get());
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        var frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        invoke(frame.seller, "accept", Api.Invoice.class, open);
        SwingUtilities.invokeLater(() -> field(frame.seller, "invoiceAction", JButton.class).doClick(0));
      });
      awaitUi(() -> ownedDialog(window.get(), "Hủy hóa đơn") != null);
      SwingUtilities.invokeAndWait(() ->
          optionPane(ownedDialog(window.get(), "Hủy hóa đơn")).setValue(JOptionPane.OK_OPTION));
      awaitUi(() -> !window.get().isBusy()
          && "QR_EXPIRED".equals(field(window.get().seller, "invoice", Api.Invoice.class).cancelReason()));
      SwingUtilities.invokeAndWait(() -> {
        assertNull(ownedDialog(window.get(), "TTM Căn tin"), "Hóa đơn hết hạn không phải hủy thành công bởi nhân viên");
        assertNull(field(window.get().seller, "cancellationRequestedInvoiceId", Long.class));
        assertEquals("Hoá đơn đã hết hạn.", toastText(window.get()));
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutReturnsFromBankQrBeforeShowingCancellationOrExpiryToast() throws Exception {
    var json = new ObjectMapper();
    var current = new AtomicReference<Api.Invoice>();
    ApiClient api = api(request -> {
      Api.Invoice invoice = current.get();
      String path = "/api/invoices/" + invoice.id();
      assertTrue(request.uri().getPath().equals(path) || request.uri().getPath().equals(path + "/qr"));
      return fixtureJson(json, invoice);
    });
    var window = new AtomicReference<CanteenFrame>();
    var notices = new AtomicInteger();
    var before = new AtomicReference<Rectangle[]>();
    long now = System.currentTimeMillis();
    String qr = "data:image/png;base64,"
        + Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of("src/test/resources/fixtures/qr-sample.png")));
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        CanteenFrame frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        field(frame.seller, "tabs", JTabbedPane.class).setSelectedIndex(1);
        JTextPane message = field(Toast.forComponent(frame), "message", JTextPane.class);
        message.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
          public void insertUpdate(javax.swing.event.DocumentEvent event) {
            assertFalse(field(frame.seller, "showingQrPage", Boolean.class),
                "Return to the previous screen before showing the toast");
            assertTrue(field(frame.seller, "customerPage", JPanel.class).getComponent(0).isShowing());
            notices.incrementAndGet();
          }
          public void removeUpdate(javax.swing.event.DocumentEvent event) {}
          public void changedUpdate(javax.swing.event.DocumentEvent event) {}
        });
      });
      String[] reasons = {"USER_CANCELLED", "QR_EXPIRED"};
      String[] messages = {"Hoá đơn đã bị huỷ.", "Hoá đơn đã hết hạn."};
      for (int index = 0; index < reasons.length; index++) {
        long id = 7 + index;
        String reason = reasons[index], expected = messages[index];
        int expectedNotices = index + 1;
        var line = new Api.InvoiceLine("Cơm gà", 1, 35000, 35000);
        var open = new Api.Invoice(id, "HD" + id, "", "", "", 35000, "OPEN",
            List.of(line), now, now + 300000, qr, "", 1, "fixture-session",
            "TTAT0000000000000" + id, "", "NONE");
        var cancelled = new Api.Invoice(id, "HD" + id, "", "", "", 35000, "CANCELLED",
            List.of(line), now, now + 300000, "", reason, 2, "fixture-session",
            open.paymentReference(), "", "FINISHED");
        awaitUi(() -> !field(window.get().seller, "pollingInvoice", Boolean.class));
        SwingUtilities.invokeAndWait(() -> {
          CanteenFrame frame = window.get();
          CheckoutView seller = frame.seller;
          current.set(open);
          invoke(seller, "accept", Api.Invoice.class, open);
          frame.validate();
          before.set(Stream.of("cardTitle", "cardPicture", "customerPay", "customerBank")
              .map(name -> boundsIn(field(seller, name, JComponent.class), frame.getContentPane()))
              .toArray(Rectangle[]::new));
          field(seller, "customerBank", JButton.class).doClick(0);
          assertTrue(field(seller, "showingQrPage", Boolean.class));
          current.set(cancelled);
          if ("QR_EXPIRED".equals(reason)) field(seller, "qrRetry", JButton.class).doClick(0);
          else invokeNoArgs(seller, "pollInvoice");
        });
        awaitUi(() -> expected.equals(toastText(window.get()))
            && "CANCELLED".equals(field(window.get().seller, "invoice", Api.Invoice.class).status()));
        SwingUtilities.invokeAndWait(() -> {
          CanteenFrame frame = window.get();
          CheckoutView seller = frame.seller;
          frame.validate();
          assertEquals(expectedNotices, notices.get());
          assertTrue(Toast.forComponent(frame).isShowing());
          assertNull(ownedDialog(frame, "TTM Căn tin"));
          assertTrue(field(seller, "customerTitle", JLabel.class).getText().isBlank());
          assertTrue(field(seller, "customerHelp", JLabel.class).getText().isBlank());
          assertArrayEquals(before.get(), Stream.of("cardTitle", "cardPicture", "customerPay", "customerBank")
              .map(name -> boundsIn(field(seller, name, JComponent.class), frame.getContentPane()))
              .toArray(Rectangle[]::new));
          invoke(seller, "accept", Api.Invoice.class, cancelled);
          assertEquals(expectedNotices, notices.get(), "Repeated results must not restart the toast");
        });
      }
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutReturnsFromBankQrAndClearsInvoiceAfterPayment() throws Exception {
    var json = new ObjectMapper();
    long now = System.currentTimeMillis();
    String qr = "data:image/png;base64,"
        + Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of("src/test/resources/fixtures/qr-sample.png")));
    var line = new Api.InvoiceLine("Cơm gà", 1, 35000, 35000);
    var open = new Api.Invoice(7, "HD00000007", "", "", "", 35000, "OPEN",
        List.of(line), now, now + 300000, qr, "", 1, "fixture-session",
        "TTAT00000000000007", "", "NONE");
    var paid = new Api.Invoice(7, open.number(), "", "", "", 35000, "PAID",
        List.of(line), now, now + 300000, "", "", 2, "fixture-session",
        open.paymentReference(), "BANK_TRANSFER", "FINISHED");
    var current = new AtomicReference<>(open);
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/invoices/7" -> fixtureJson(json, current.get());
      case "/api/menu/sales" -> "[]";
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    var notices = new AtomicInteger();
    try {
      SwingUtilities.invokeAndWait(() -> {
        var frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        CheckoutView seller = frame.seller;
        field(seller, "tabs", JTabbedPane.class).setSelectedIndex(1);
        JTextPane message = field(Toast.forComponent(frame), "message", JTextPane.class);
        message.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
          public void insertUpdate(javax.swing.event.DocumentEvent event) {
            assertFalse(field(seller, "showingQrPage", Boolean.class));
            assertEquals(0, field(seller, "customerItems", JPanel.class).getComponentCount());
            notices.incrementAndGet();
          }
          public void removeUpdate(javax.swing.event.DocumentEvent event) {}
          public void changedUpdate(javax.swing.event.DocumentEvent event) {}
        });
        invoke(seller, "accept", Api.Invoice.class, open);
        assertEquals(1, field(seller, "customerItems", JPanel.class).getComponentCount());
        assertEquals(CanteenFrame.money(35000), field(seller, "customerTotal", JLabel.class).getText());
        field(seller, "customerBank", JButton.class).doClick(0);
        assertTrue(field(seller, "showingQrPage", Boolean.class));
        assertNotNull(field(seller, "qr", JLabel.class).getIcon());
        current.set(paid);
        invokeNoArgs(seller, "pollInvoice");
      });
      awaitUi(() -> "Thanh toán hoá đơn thành công!".equals(toastText(window.get())));
      SwingUtilities.invokeAndWait(() -> {
        var seller = window.get().seller;
        assertEquals("PAID", field(seller, "invoice", Api.Invoice.class).status());
        assertFalse(field(seller, "showingQrPage", Boolean.class));
        assertTrue(field(seller, "customerPage", JPanel.class).getComponent(0).isShowing());
        assertNull(field(seller, "qr", JLabel.class).getIcon());
        assertEquals(0, field(seller, "customerModel", javax.swing.table.DefaultTableModel.class).getRowCount());
        assertEquals("0 ₫", field(seller, "customerTotal", JLabel.class).getText());
        assertTrue(field(seller, "customerTitle", JLabel.class).getText().isBlank());
        assertTrue(field(seller, "customerHelp", JLabel.class).getText().isBlank());
        assertFalse(field(seller, "customerBank", JButton.class).isEnabled());
        assertEquals(1, notices.get());
        invoke(seller, "accept", Api.Invoice.class, paid);
        invoke(seller, "accept", Api.Invoice.class, open);
        assertEquals(1, notices.get(), "Repeated or stale results must not restart the toast");
        assertEquals(0, field(seller, "customerItems", JPanel.class).getComponentCount());
        assertEquals("PAID", field(seller, "invoice", Api.Invoice.class).status());
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void bankPaymentWinsWhenCancellationReturnsAnAlreadyPaidInvoice() throws Exception {
    var json = new ObjectMapper();
    long now = System.currentTimeMillis();
    var line = new Api.InvoiceLine("Cơm gà", 1, 35000, 35000);
    var open = new Api.Invoice(7, "HD00000007", "", "", "", 35000, "OPEN",
        List.of(line), now, now + 300000, "", "", 1, "fixture-session",
        "TTAT00000000000007", "", "NONE");
    var paid = new Api.Invoice(7, "HD00000007", "", "", "", 35000, "PAID",
        List.of(line), now, now + 300000, "", "", 2, "fixture-session",
        "TTAT00000000000007", "BANK_TRANSFER", "FINISHED");
    var current = new AtomicReference<>(open);
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/invoices/7/cancel" -> {
        current.set(paid);
        yield "{\"message\":\"Đã nhận yêu cầu\"}";
      }
      case "/api/invoices/7" -> fixtureJson(json, current.get());
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        var frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        invoke(frame.seller, "accept", Api.Invoice.class, open);
        SwingUtilities.invokeLater(() -> field(frame.seller, "invoiceAction", JButton.class).doClick(0));
      });
      awaitUi(() -> ownedDialog(window.get(), "Hủy hóa đơn") != null);
      SwingUtilities.invokeAndWait(() ->
          optionPane(ownedDialog(window.get(), "Hủy hóa đơn")).setValue(JOptionPane.OK_OPTION));
      awaitUi(() -> !window.get().isBusy()
          && "PAID".equals(field(window.get().seller, "invoice", Api.Invoice.class).status()));
      SwingUtilities.invokeAndWait(() -> {
        var seller = window.get().seller;
        assertFalse(field(seller, "cartOverlay", JPanel.class).isVisible());
        assertEquals(0, field(seller, "cartModel", javax.swing.table.DefaultTableModel.class).getRowCount());
        assertEquals(0, field(seller, "customerModel", javax.swing.table.DefaultTableModel.class).getRowCount());
        assertEquals("Thanh toán hoá đơn thành công!", toastText(seller));
        assertNull(ownedDialog(window.get(), "TTM Căn tin"), "Không báo hủy thành công khi hóa đơn đã thanh toán");
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutWaitsForAnInvoiceAndPaymentClickBeforeAskingForPin() throws Exception {
    var json = new ObjectMapper();
    var preparing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var prepareCalls = new AtomicInteger();
    long now = System.currentTimeMillis();
    var card = new Api.ReaderStatus(true, "SIM0001", true, "TTM000001", 5,
        "NONE", 0, 0, "checkout-fixture", "ACTIVE");
    var open = new Api.Invoice(7, "HD00000007", "", "", "", 35000, "OPEN",
        List.of(new Api.InvoiceLine("Cơm gà", 1, 35000, 35000)), now, now + 300000,
        "", "", 1, "fixture-session", "TTAT00000000000007", "", "NONE");
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/reader/connect", "/api/reader/status" -> fixtureJson(json, card);
      case "/api/reader/sync" -> fixtureJson(json,
          new Api.SyncStatus("TTM000001", "SYNCED", 0, "Đã đồng bộ"));
      case "/api/invoices/7" -> fixtureJson(json, open);
      case "/api/invoices/7/prepare" -> {
        prepareCalls.incrementAndGet();
        yield replyAfter(preparing, release, fixtureJson(json, card));
      }
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        CanteenFrame frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setLocation(-3000, -3000);
        frame.setVisible(true);
        field(frame.seller, "tabs", JTabbedPane.class).setSelectedIndex(1);
        field(frame, "deviceSearch", DeviceSearch.class).setChoices(
            new Api.DeviceChoice[] {new Api.DeviceChoice("SIM0001", "Thẻ 1")}, "SIM0001");
        field(frame, "connectButton", JButton.class).doClick(0);
      });
      awaitUi(() -> window.get().status().present() && !window.get().isBusy());
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        assertEquals("Đã phát hiện thẻ", field(seller, "cardTitle", JLabel.class).getText());
        assertFalse(field(seller, "customerPay", JButton.class).isEnabled());
        assertFalse(field(seller, "customerBank", JButton.class).isEnabled());
        assertFalse(field(seller, "pinOpen", Boolean.class));
        assertEquals(0, prepareCalls.get());
        invoke(seller, "accept", Api.Invoice.class, open);
        assertTrue(field(seller, "customerPay", JButton.class).isEnabled());
        assertTrue(field(seller, "customerBank", JButton.class).isEnabled());
        assertFalse(field(seller, "pinOpen", Boolean.class));
        assertEquals(0, prepareCalls.get());
        field(seller, "customerPay", JButton.class).doClick(0);
      });
      await(preparing);
      SwingUtilities.invokeAndWait(() -> assertFalse(field(window.get().seller, "pinOpen", Boolean.class)));
      release.countDown();
      awaitUi(() -> field(window.get().seller, "pinOpen", Boolean.class));
      assertEquals(1, prepareCalls.get());
    } finally {
      release.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void bothCountersShowAuthenticationFailureAsToastWithoutEnteringPin() throws Exception {
    var attempts = new AtomicInteger();
    var prepareCalls = new AtomicInteger();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ApiClient api = api(request -> {
      switch (request.uri().getPath()) {
      case "/api/reader/connect" -> {
        attempts.incrementAndGet();
        started.countDown();
        await(release);
        throw new ApiClient.ApiError(403, "CARD_AUTH", "Fixture ECC failure");
      }
      case "/api/student/prepare", "/api/invoices/7/prepare" -> {
        prepareCalls.incrementAndGet();
        throw new AssertionError("PIN preparation must not run after an ECC failure");
      }
        default -> throw new AssertionError(request.uri().getPath());
      }
    });
    for (boolean checkout : new boolean[] {false, true}) {
      var window = new AtomicReference<CanteenFrame>();
      try {
        SwingUtilities.invokeAndWait(() -> {
          TTM_CuoiKy.configureLookAndFeel();
          CanteenFrame frame = new CanteenFrame(api, checkout, false);
          window.set(frame);
          frame.setLocation(-3000, -3000);
          frame.setVisible(true);
          if (checkout) field(frame.seller, "tabs", JTabbedPane.class).setSelectedIndex(1);
          field(frame, "deviceSearch", DeviceSearch.class).setChoices(
              new Api.DeviceChoice[] {new Api.DeviceChoice("SIM0001", "Thẻ 1")}, "SIM0001");
          field(frame, "connectButton", JButton.class).doClick(0);
        });
        if (!checkout) {
          await(started);
          SwingUtilities.invokeAndWait(() -> {
            assertFalse(window.get().status().present());
            assertTrue(showingLabel(window.get(), "Không phát hiện thẻ"));
            assertFalse(showingLabel(window.get(), "Nhập mã PIN"));
          });
          release.countDown();
        }
        awaitUi(() -> "Xác thực thẻ không thành công!".equals(toastText(window.get())));
        SwingUtilities.invokeAndWait(() -> {
          CanteenFrame frame = window.get();
          assertFalse(frame.status().present());
          assertTrue(showingLabel(frame, "Không phát hiện thẻ"));
          assertFalse(showingLabel(frame, "Nhập mã PIN"));
          assertTrue(field(frame, "connectButton", JButton.class).isEnabled());
          assertNull(ownedDialog(frame, "Chưa thực hiện được thao tác"));
          if (checkout) {
            assertFalse(field(frame.seller, "customerPay", JButton.class).isEnabled());
            assertFalse(field(frame.seller, "customerBank", JButton.class).isEnabled());
          }
        });
      } finally {
        release.countDown();
        if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
      }
    }
    assertEquals(2, attempts.get());
    assertEquals(0, prepareCalls.get());
  }

  @Test
  void failedCardAuthenticationLeavesInvoiceAvailableForBankPayment() throws Exception {
    long now = System.currentTimeMillis();
    var invoice = new Api.Invoice(7, "HD00000007", "", "", "", 35000, "OPEN",
        List.of(new Api.InvoiceLine("Cơm gà", 1, 35000, 35000)), now, now + 300000,
        "", "", 1, "fixture-session", "TTAT00000000000007", "", "NONE");
    ApiClient api = api(request -> {
      if (request.uri().getPath().equals("/api/reader/connect"))
        throw new ApiClient.ApiError(403, "CARD_AUTH", "Fixture ECC failure");
      throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        CanteenFrame frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setLocation(-3000, -3000);
        frame.setVisible(true);
        field(frame.seller, "tabs", JTabbedPane.class).setSelectedIndex(1);
        invoke(frame.seller, "accept", Api.Invoice.class, invoice);
        field(frame, "deviceSearch", DeviceSearch.class).setChoices(
            new Api.DeviceChoice[] {new Api.DeviceChoice("SIM0001", "Thẻ 1")}, "SIM0001");
        field(frame, "connectButton", JButton.class).doClick(0);
      });
      awaitUi(() -> "Xác thực thẻ không thành công!".equals(toastText(window.get())));
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        assertEquals("OPEN", field(seller, "invoice", Api.Invoice.class).status());
        assertEquals(1, field(seller, "customerItems", JPanel.class).getComponentCount());
        assertEquals("35.000 ₫", field(seller, "customerTotal", JLabel.class).getText());
        assertFalse(field(seller, "customerPay", JButton.class).isEnabled());
        assertTrue(field(seller, "customerBank", JButton.class).isEnabled());
        assertFalse(field(seller, "pinOpen", Boolean.class));
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void bothCountersUseTheRequestedMissingCardIcon() throws Exception {
    ApiClient api = api(request -> "[]");
    SwingUtilities.invokeAndWait(() -> {
      TTM_CuoiKy.configureLookAndFeel();
      CanteenFrame management = new CanteenFrame(api, false, false);
      CanteenFrame checkout = new CanteenFrame(api, true, false);
      try {
        Icon expected = DesktopIcons.svg("/icons/card-remove-1-svgrepo-com.svg", 128, "fixture");
        Icon student = field(management.student, "cardReaderIcon", Icon.class);
        JLabel picture = field(checkout.seller, "cardPicture", JLabel.class);
        Icon missing = picture.getIcon();
        assertEquals(128, student.getIconWidth());
        assertEquals(128, student.getIconHeight());
        assertArrayEquals(iconPixels(expected), iconPixels(student));
        assertArrayEquals(iconPixels(expected), iconPixels(missing));
        Api.ReaderStatus card = new Api.ReaderStatus(true, "SIM0001", true, "fixture", 5,
            "NONE", 0, 0, "fixture-connection", "ACTIVE");
        checkout.seller.onCard(card);
        assertSame(field(checkout.seller, "cardPresentIcon", Icon.class), picture.getIcon());
        checkout.seller.onCard(new Api.ReaderStatus(false, "", false, "", 5,
            "NONE", 0, 0, "", ""));
        assertSame(missing, picture.getIcon());
      } finally {
        management.dispose();
        checkout.dispose();
      }
    });
  }

  private static int[] iconPixels(Icon icon) {
    BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
        BufferedImage.TYPE_INT_ARGB);
    java.awt.Graphics2D graphics = image.createGraphics();
    try { icon.paintIcon(null, graphics, 0, 0); } finally { graphics.dispose(); }
    return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
  }

  @Test
  void connectingKeepsStudentPageVisibleUntilCardIsReadyForPin() throws Exception {
    var preparing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/reader/connect" -> CONNECTED;
      case "/api/student/prepare" -> {
        preparing.countDown();
        await(release);
        yield CONNECTED;
      }
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        CanteenFrame frame = new CanteenFrame(api, false, false);
        window.set(frame);
        field(frame, "deviceSearch", DeviceSearch.class).setChoices(
            new Api.DeviceChoice[] {new Api.DeviceChoice("fixture", "Fixture")}, "fixture");
        frame.setVisible(true);
        assertTrue(showingLabel(frame, "Không phát hiện thẻ"));
        button(frame, "Kết nối").doClick(0);
        assertTrue(frame.getGlassPane().isVisible());
      });
      await(preparing);
      SwingUtilities.invokeAndWait(() -> {
        var frame = window.get();
        assertTrue(frame.getGlassPane().isVisible());
        assertFalse(((JComponent) frame.getGlassPane()).isOpaque());
        assertTrue(showingLabel(frame.getGlassPane(), "Đang kiểm tra thẻ…"));
        assertTrue(showingLabel(frame, "Không phát hiện thẻ"),
            "The original page must stay visible while the backend prepares the card");
        assertFalse(showingLabel(frame, "Nhập mã PIN"));
      });
      release.countDown();
      awaitIdle(window.get());
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(showingLabel(window.get(), "Nhập mã PIN"));
        assertFalse(window.get().getGlassPane().isVisible());
        button(window.get(), "Xác thực").doClick(0);
        assertEquals(TTM_CuoiKy.ERROR_TEXT_COLOR,
            label(window.get(), "Vui lòng nhập đủ 6 chữ số PIN.").getForeground());
      });
    } finally {
      release.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void adminKeepsLoginDuringDashboardLoadAndTabSwitches() throws Exception {
    var loading = new CountDownLatch(1);
    var releaseLoad = new CountDownLatch(1);
    var signOutRequests = new AtomicInteger();
    var pollFailure = new AtomicReference<String>();
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/admin/login" -> """
          {"token":"fixture","username":"fixture","email":"fixture@example.invalid"}
          """;
      case "/api/admin/cards/overview" -> {
        assertEquals("Bearer fixture", request.headers().firstValue("Authorization").orElse(null));
        loading.countDown();
        await(releaseLoad);
        yield """
            {"items":[],"total":0,"page":0,"pageSize":10}
            """;
      }
      case "/api/admin/logout" -> {
        signOutRequests.incrementAndGet();
        yield "{\"message\":\"Signed out\"}";
      }
      case "/api/admin/me" -> {
        assertEquals("Bearer fixture", request.headers().firstValue("Authorization").orElse(null));
        if (pollFailure.get() != null) throw new ApiClient.ApiError(503, pollFailure.get(), "Fixture");
        yield "{}";
      }
      case "/api/reader/status" -> """
          {"present":false,"alias":"","initialized":false,"cardId":"","tries":5,
           "role":"NONE","counter":0,"profileLength":0,"connectionId":"","lifecycleStatus":""}
          """;
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, false, false);
        window.set(frame);
        field(frame, "tabs", JTabbedPane.class).setSelectedIndex(1);
        frame.setVisible(true);
        field(frame.admin, "username", JTextField.class).setText("fixture");
        field(frame.admin, "password", JPasswordField.class).setText("fixture-password");
        button(frame.admin, "Đăng nhập").doClick(0);
      });
      await(loading);
      SwingUtilities.invokeAndWait(() -> {
        CanteenFrame frame = window.get();
        assertTrue(showingLabel(frame, "Đăng nhập"));
        assertFalse(showingLabel(frame, "Quản trị hệ thống"));
        assertTrue(frame.getGlassPane().isVisible());
        assertFalse(showingLabel(frame.getGlassPane(), "Đang tìm hồ sơ…"));
      });
      releaseLoad.countDown();
      awaitIdle(window.get());
      SwingUtilities.invokeAndWait(() -> {
        JLabel title = label(window.get(), "Quản trị hệ thống");
        assertNotNull(title);
        assertEquals(field(window.get().student, "welcome", JLabel.class).getFont(), title.getFont());
        assertEquals(12, title.getInsets().top);
        assertEquals(12, title.getInsets().bottom);
        assertEquals(title.getFontMetrics(title.getFont()).getHeight() + 24, title.getHeight());
        assertEquals(title.getHeight() + 1, title.getParent().getHeight());
        assertEquals("Hiển thị 0 trên tổng số 0 kết quả",
            field(window.get().admin, "resultCount", JLabel.class).getText());
        var query = field(window.get().admin, "query", JTextField.class);
        assertEquals("Tìm kiếm theo ID thẻ, Mã SV, Họ tên, Số ĐT",
            query.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT));
        assertEquals(new java.awt.Dimension(320, 40), query.getSize());
        var classFilter = field(window.get().admin, "classFilter", JTextField.class);
        assertEquals("Nhập lớp", classFilter.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT));
        assertEquals(new java.awt.Dimension(120, 40), classFilter.getSize());
        var year = field(window.get().admin, "yearFilter", JTextField.class);
        assertEquals("Nhập năm", year.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT));
        assertEquals(new java.awt.Dimension(120, 40), year.getSize());
        assertTrue(year.getWidth() >= year.getFontMetrics(year.getFont()).stringWidth("Nhập năm") + 16);
        var table = field(window.get().admin, "table", JTable.class);
        var program = field(window.get().admin, "program", JComboBox.class);
        var sort = field(window.get().admin, "sort", JComboBox.class);
        assertEquals(new java.awt.Dimension(120, 40), program.getSize());
        assertEquals(new java.awt.Dimension(240, 40), sort.getSize());
        for (String text : new String[] {"Giao dịch", "------------"}) {
          JButton action = button(window.get().admin, text);
          assertEquals(new java.awt.Dimension(128, 32), action.getSize());
          assertEquals(title.getY() + title.getHeight() / 2,
              SwingUtilities.convertPoint(action, 0, action.getHeight() / 2, title.getParent()).y);
        }
        var sortBounds = SwingUtilities.convertRectangle(sort.getParent(), sort.getBounds(), window.get());
        var scroll = SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
        var tableBounds = SwingUtilities.convertRectangle(scroll.getParent(), scroll.getBounds(), window.get());
        var filterLabel = label(window.get().admin, "Lọc theo :");
        var filterLabelBounds = SwingUtilities.convertRectangle(
            filterLabel.getParent(), filterLabel.getBounds(), window.get());
        assertAdminListLayout(window.get());
        assertEquals(tableBounds.x, filterLabelBounds.x);
        assertEquals(tableBounds.x + tableBounds.width, sortBounds.x + sortBounds.width);
        assertEquals(41, table.getRowHeight());
        assertEquals(10, table.getRowCount());
        assertEquals("ID thẻ", sort.getSelectedItem());
        assertEquals(5, sort.getItemCount());
        JScrollPane tableScroll = (JScrollPane) scroll;
        assertEquals(452, tableScroll.getHeight());
        assertEquals(new java.awt.Insets(1, 1, 1, 1), tableScroll.getInsets());
        assertEquals(409, tableScroll.getViewport().getHeight());
        var borderImage = new BufferedImage(tableScroll.getWidth(), tableScroll.getHeight(),
            BufferedImage.TYPE_INT_RGB);
        var borderGraphics = borderImage.createGraphics();
        try { tableScroll.paint(borderGraphics); } finally { borderGraphics.dispose(); }
        int border = new java.awt.Color(0xC2C2C2).getRGB();
        assertEquals(border, borderImage.getRGB(borderImage.getWidth() / 2, 0));
        assertEquals(border, borderImage.getRGB(borderImage.getWidth() / 2, borderImage.getHeight() - 1));
        assertEquals(border, borderImage.getRGB(0, borderImage.getHeight() / 2));
        assertEquals(border, borderImage.getRGB(borderImage.getWidth() - 1, borderImage.getHeight() / 2));
        for (int row = 0; row < 9; row++) assertEquals(41, table.getRowHeight(row));
        assertEquals(40, table.getRowHeight(9));
        assertEquals(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, tableScroll.getVerticalScrollBarPolicy());
        assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, tableScroll.getHorizontalScrollBarPolicy());
        for (int column = 0; column < table.getColumnCount(); column++) {
          Component renderer = table.prepareRenderer(table.getCellRenderer(9, column), 9, column);
          assertTrue(renderer instanceof JLabel);
          assertEquals("--", ((JLabel) renderer).getText());
        }
        table.changeSelection(9, 0, false, false);
        assertEquals(-1, table.getSelectedRow());
        table.setRowSelectionInterval(9, 9);
        table.getActionMap().get("preview").actionPerformed(null);
        table.getActionMap().get("cardStatusMenu").actionPerformed(null);
        assertFalse(window.get().isBusy());
        assertFalse(field(window.get().admin, "statusMenu", JPopupMenu.class).isVisible());
        table.clearSelection();
        assertEquals(41, table.getTableHeader().getPreferredSize().height);
        assertEquals(1, table.getTableHeader().getInsets().bottom);
        assertEquals("Tình trạng", table.getColumnName(7));
        assertTrue(components(window.get().admin).noneMatch(
            c -> c instanceof JButton b && b.getText().equals("Tìm")));
        assertTrue(components(window.get().admin).noneMatch(
            c -> c instanceof JButton b && b.getText().equals("Khoản nạp")));
        assertTrue(components(window.get().admin).noneMatch(c -> c instanceof JButton b
            && java.util.Set.of("Khóa thẻ", "Mở khoá thẻ", "Xoá thẻ").contains(b.getText())));
        assertTrue(SwingUtilities.convertPoint(field(window.get().admin, "program", JComboBox.class),
            0, 0, window.get()).x < SwingUtilities.convertPoint(
            field(window.get().admin, "sort", JComboBox.class), 0, 0, window.get()).x);
        assertTrue(components(window.get().admin).noneMatch(
            c -> c instanceof JButton b && b.getText().equals("Đăng xuất")));
        field(window.get(), "tabs", JTabbedPane.class).setSelectedIndex(0);
        CanteenFrame frame = window.get();
        assertEquals(0, field(frame, "tabs", JTabbedPane.class).getSelectedIndex());
        assertTrue(frame.admin.logged());
        field(frame, "tabs", JTabbedPane.class).setSelectedIndex(1);
        assertTrue(frame.admin.logged());
        assertTrue(showingLabel(frame, "Quản trị hệ thống"));
      });
      api.get("/admin/me", Map.class);
      assertEquals(0, signOutRequests.get());
      pollFailure.set("CONNECTION");
      SwingUtilities.invokeAndWait(window.get()::poll);
      awaitUi(() -> field(window.get(), "syncStatus", Api.SyncStatus.class).phase().equals("OFFLINE"));
      assertTrue(window.get().admin.logged(), "A connection failure must not sign out Admin");
      pollFailure.set("ADMIN_EXPIRED");
      SwingUtilities.invokeAndWait(window.get()::poll);
      awaitUi(() -> !window.get().admin.logged());
    } finally {
      releaseLoad.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void bothCountersDisableDisconnectWhileCardIsBusyOrTopupIsReserved() throws Exception {
    ApiClient api = api(request -> { throw new AssertionError(request.uri()); });
    AtomicReference<CanteenFrame> management = new AtomicReference<>();
    AtomicReference<CanteenFrame> checkout = new AtomicReference<>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        management.set(new CanteenFrame(api, false, false));
        checkout.set(new CanteenFrame(api, true, false));
        Api.ReaderStatus connected = new Api.ReaderStatus(true, "SIM0001", true, "TTM000001",
            5, "STUDENT", 0, 0, "fixture-connection", "ACTIVE");
        for (CanteenFrame frame : List.of(management.get(), checkout.get())) {
          setField(frame, "current", connected);
          setField(frame, "readerActivity", new Api.ReaderActivity(true, true, true, false));
          invokeNoArgs(frame, "updateManagementConnectionButtons");
          assertFalse(field(frame, "disconnectButton", JButton.class).isEnabled());
          setField(frame, "readerActivity", new Api.ReaderActivity(true, true, false, true));
          invokeNoArgs(frame, "updateManagementConnectionButtons");
          assertFalse(field(frame, "disconnectButton", JButton.class).isEnabled());
          setField(frame, "readerActivity", new Api.ReaderActivity(true, true, false, false));
          invokeNoArgs(frame, "updateManagementConnectionButtons");
          assertTrue(field(frame, "disconnectButton", JButton.class).isEnabled());
          setField(frame, "current", new Api.ReaderStatus(false, "", false, "", 5, "NONE", 0, 0, "", ""));
          field(frame, "deviceSearch", DeviceSearch.class).setChoices(
              new Api.DeviceChoice[] {new Api.DeviceChoice("SIM0001", "Thẻ 1")}, "SIM0001");
          setField(frame, "readerActivity", new Api.ReaderActivity(false, false, false, false, "SIM0001", true));
          invokeNoArgs(frame, "updateManagementConnectionButtons");
          assertFalse(field(frame, "connectButton", JButton.class).isEnabled());
          assertTrue(field(frame, "connectButton", JButton.class).getToolTipText().contains("quầy khác"));
        }
      });
    } finally {
      SwingUtilities.invokeAndWait(() -> {
        if (management.get() != null) management.get().dispose();
        if (checkout.get() != null) checkout.get().dispose();
      });
    }
  }

  @Test
  void bothCountersCheckTheSelectedReaderAndIgnoreLateAvailabilityReplies() throws Exception {
    for (boolean checkout : new boolean[] {false, true}) {
      var json = new ObjectMapper();
      var firstStarted = new CountDownLatch(1);
      var releaseFirst = new CountDownLatch(1);
      var first = new AtomicBoolean(true);
      var connected = new AtomicBoolean();
      var connectCalls = new AtomicInteger();
      var card = new Api.ReaderStatus(true, "SIM0002", true, "TTM000002", 5,
          "NONE", 0, 0, "fixture-connection", "ACTIVE");
      var absent = new Api.ReaderStatus(false, "", false, "", 5, "NONE", 0, 0, "", "");
      ApiClient api = api(request -> switch (request.uri().getPath()) {
        case "/api/station/open", "/api/station/heartbeat" ->
            "{\"sessionId\":\"fixture\",\"epoch\":1,\"leaseUntil\":9999999999999}";
        case "/api/readers" -> """
            [{"id":"SIM0001","label":"Thẻ 1"},{"id":"SIM0002","label":"Thẻ 2"}]
            """;
        case "/api/menu", "/api/menu/categories", "/api/invoices/current" -> "[]";
        case "/api/reader/activity" -> {
          String selected = queryParameter(request.uri().getRawQuery(), "selected");
          if ("SIM0001".equals(selected) && first.compareAndSet(true, false)) {
            firstStarted.countDown();
            await(releaseFirst);
          }
          yield fixtureJson(json, new Api.ReaderActivity(connected.get(), connected.get(), false, false,
              selected, "SIM0001".equals(selected)));
        }
        case "/api/reader/connect" -> {
          assertEquals("SIM0002", requestJson(json, request).path("reader").asText());
          connectCalls.incrementAndGet();
          connected.set(true);
          yield fixtureJson(json, card);
        }
        case "/api/reader/status" -> fixtureJson(json, connected.get() ? card : absent);
        case "/api/reader/sync" -> fixtureJson(json, new Api.SyncStatus("TTM000002", "SYNCED", 0, "Đã đồng bộ"));
        case "/api/student/prepare" -> fixtureJson(json, card);
        default -> throw new AssertionError(request.uri().getPath());
      });
      var window = new AtomicReference<CanteenFrame>();
      try {
        SwingUtilities.invokeAndWait(() -> {
          TTM_CuoiKy.configureLookAndFeel();
          CanteenFrame frame = new CanteenFrame(api, checkout, true);
          window.set(frame);
          frame.setLocation(-3000, -3000);
          frame.setVisible(true);
        });
        awaitUi(() -> field(window.get(), "activityKnown", Boolean.class) && !window.get().isBusy());
        SwingUtilities.invokeAndWait(() -> {
          DeviceSearch search = field(window.get(), "deviceSearch", DeviceSearch.class);
          search.setChoices(new Api.DeviceChoice[] {
              new Api.DeviceChoice("SIM0001", "Thẻ 1"), new Api.DeviceChoice("SIM0002", "Thẻ 2")}, "SIM0001");
        });
        await(firstStarted);
        SwingUtilities.invokeAndWait(() -> {
          DeviceSearch search = field(window.get(), "deviceSearch", DeviceSearch.class);
          search.setChoices(field(search, "choices", Api.DeviceChoice[].class), "SIM0002");
          assertFalse(field(window.get(), "connectButton", JButton.class).isEnabled());
        });
        releaseFirst.countDown();
        awaitUi(() -> "SIM0002".equals(field(window.get(), "checkedReader", String.class))
            && field(window.get(), "connectButton", JButton.class).isEnabled());
        SwingUtilities.invokeAndWait(() -> {
          DeviceSearch search = field(window.get(), "deviceSearch", DeviceSearch.class);
          search.setChoices(field(search, "choices", Api.DeviceChoice[].class), "SIM0001");
        });
        awaitUi(() -> "SIM0001".equals(field(window.get(), "checkedReader", String.class))
            && !field(window.get(), "connectButton", JButton.class).isEnabled()
            && field(window.get(), "connectButton", JButton.class).getToolTipText() != null);
        SwingUtilities.invokeAndWait(() -> {
          assertTrue(field(window.get(), "connectButton", JButton.class).getToolTipText().contains("quầy khác"));
          assertFalse(field(window.get(), "syncState", JLabel.class).getText().contains("quầy khác"));
          DeviceSearch search = field(window.get(), "deviceSearch", DeviceSearch.class);
          search.setChoices(field(search, "choices", Api.DeviceChoice[].class), "SIM0002");
        });
        awaitUi(() -> "SIM0002".equals(field(window.get(), "checkedReader", String.class))
            && field(window.get(), "connectButton", JButton.class).isEnabled());
        SwingUtilities.invokeAndWait(() -> field(window.get(), "connectButton", JButton.class).doClick(0));
        awaitUi(() -> connectCalls.get() == 1 && window.get().status().present());
      } finally {
        releaseFirst.countDown();
        if (window.get() != null) SwingUtilities.invokeAndWait(() -> {
          field(window.get(), "timer", Timer.class).stop();
          field(window.get(), "activityTimer", Timer.class).stop();
          var heartbeat = field(window.get(), "heartbeat", ScheduledExecutorService.class);
          if (heartbeat != null) heartbeat.shutdownNow();
          window.get().dispose();
        });
      }
    }
  }

  @Test
  void checkoutToolbarMatchesManagementAndCanConnectAndDisconnect() throws Exception {
    var json = new ObjectMapper();
    Api.ReaderStatus absent = new Api.ReaderStatus(false, "", false, "", 5, "NONE", 0, 0, "", "");
    Api.ReaderStatus connected = new Api.ReaderStatus(true, "SIM0001", true, "TTM000001", 5,
        "NONE", 0, 0, "checkout-fixture", "ACTIVE");
    var current = new AtomicReference<>(absent);
    var connections = new AtomicInteger();
    var disconnections = new AtomicInteger();
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/reader/connect" -> {
        assertEquals("SIM0001", requestJson(json, request).path("reader").asText());
        connections.incrementAndGet();
        current.set(connected);
        yield fixtureJson(json, connected);
      }
      case "/api/reader/eject" -> {
        disconnections.incrementAndGet();
        current.set(absent);
        yield "{\"message\":\"Disconnected\"}";
      }
      case "/api/checkout/reader/prepare" -> fixtureJson(json, connected);
      case "/api/reader/status" -> fixtureJson(json, current.get());
      case "/api/reader/sync" -> fixtureJson(json,
          new Api.SyncStatus("TTM000001", "SYNCED", 0, "Đã đồng bộ"));
      default -> throw new AssertionError(request.uri().getPath());
    });
    var management = new AtomicReference<CanteenFrame>();
    var checkout = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        management.set(new CanteenFrame(api, false, false));
        checkout.set(new CanteenFrame(api, true, false));
        management.get().setLocation(-3000, -3000);
        checkout.get().setLocation(-3000, -3000);
        management.get().setSize(1280, 880);
        checkout.get().setSize(1280, 720);
        management.get().setVisible(true);
        checkout.get().setVisible(true);
        DeviceSearch left = field(management.get(), "deviceSearch", DeviceSearch.class);
        DeviceSearch right = field(checkout.get(), "deviceSearch", DeviceSearch.class);
        assertEquals(new java.awt.Dimension(384, 32), right.getSize());
        assertEquals(boundsIn(left, management.get().getContentPane()),
            boundsIn(right, checkout.get().getContentPane()));
        JButton connect = field(checkout.get(), "connectButton", JButton.class);
        JButton disconnect = field(checkout.get(), "disconnectButton", JButton.class);
        JButton sync = field(checkout.get(), "reconcile", JButton.class);
        for (JButton button : new JButton[] {connect, disconnect, sync})
          assertEquals(new java.awt.Dimension(128, 32), button.getSize());
        Rectangle searchBounds = boundsIn(right, checkout.get().getContentPane());
        Rectangle connectBounds = boundsIn(connect, checkout.get().getContentPane());
        Rectangle disconnectBounds = boundsIn(disconnect, checkout.get().getContentPane());
        assertEquals(8, connectBounds.x - searchBounds.x - searchBounds.width);
        assertEquals(8, disconnectBounds.x - connectBounds.x - connectBounds.width);
        assertEquals(boundsIn(field(management.get(), "reconcile", JButton.class), management.get().getContentPane()),
            boundsIn(sync, checkout.get().getContentPane()));
        assertTrue(components(checkout.get()).noneMatch(c -> c instanceof JButton b
            && ("Tạo thẻ mới".equals(b.getText()) || "Tải danh sách".equals(b.getText()))));
        assertNull(label(checkout.get(), "QUẦY THANH TOÁN"));
        assertNull(label(checkout.get(), "Thẻ / đầu đọc"));
        right.setChoices(new Api.DeviceChoice[] {new Api.DeviceChoice("SIM0001", "Thẻ mô phỏng SIM0001")},
            "SIM0001");
        setField(checkout.get(), "readerActivity", new Api.ReaderActivity(false, false, false, false, "SIM0001", true));
        invokeNoArgs(checkout.get(), "updateManagementConnectionButtons");
        checkout.get().validate();
        Rectangle ownerNotice = boundsIn(field(checkout.get(), "syncState", JLabel.class),
            checkout.get().getContentPane());
        assertTrue(ownerNotice.x >= disconnectBounds.x + disconnectBounds.width + 12,
            "The other-counter warning must not overlap the connection buttons");
        assertFalse(field(checkout.get(), "syncState", JLabel.class).getText().contains("quầy khác"));
        assertFalse(connect.isEnabled());
        setField(checkout.get(), "readerActivity", new Api.ReaderActivity(false, false, false, false));
        invokeNoArgs(checkout.get(), "updateManagementConnectionButtons");
        BorderLayout layout = (BorderLayout) checkout.get().getContentPane().getLayout();
        assertNull(layout.getLayoutComponent(BorderLayout.SOUTH));
        assertEquals(2, field(checkout.get().seller, "tabs", JTabbedPane.class).getTabCount());
        assertDoesNotThrow(() -> {
          Files.createDirectories(Path.of("target/ui-check"));
          ImageIO.write(painted(checkout.get()), "png", Path.of("target/ui-check/checkout-toolbar.png").toFile());
        });
        right.setChoices(new Api.DeviceChoice[] {new Api.DeviceChoice("SIM0001", "Thẻ mô phỏng SIM0001")},
            "SIM0001");
        connect.doClick(0);
      });
      awaitUi(() -> checkout.get().status().present() && !checkout.get().isBusy());
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(1, connections.get());
        assertEquals("Đã kết nối", field(checkout.get(), "connectButton", JButton.class).getText());
        assertFalse(field(checkout.get(), "connectButton", JButton.class).isEnabled());
        assertTrue(field(checkout.get(), "disconnectButton", JButton.class).isEnabled());
        assertTrue(field(checkout.get().seller, "readerInfo", JLabel.class).getText().contains("TTM000001"));
        assertFalse(field(checkout.get().seller, "pinOpen", Boolean.class));
        assertFalse(field(checkout.get().seller, "customerPay", JButton.class).isEnabled());
        invoke(checkout.get().seller, "accept", Api.Invoice.class,
            invoice("OPEN", "", 1, System.currentTimeMillis()));
        assertTrue(field(checkout.get().seller, "customerPay", JButton.class).isEnabled());
        field(checkout.get(), "disconnectButton", JButton.class).doClick(0);
      });
      awaitUi(() -> !checkout.get().status().present() && !checkout.get().isBusy());
      String warning = "Chưa rõ kết quả tạo hóa đơn. Nhấn Tạo hóa đơn để kiểm tra lại cùng yêu cầu; không lập đơn khác.";
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(1, disconnections.get());
        assertTrue(field(checkout.get(), "connectButton", JButton.class).isEnabled());
        checkout.get().errorMessage(warning);
        checkout.get().validate();
        Toast toast = Toast.forComponent(checkout.get());
        JTextPane messageArea = field(toast, "message", JTextPane.class);
        assertTrue(toast.isShowing());
        assertEquals(warning, messageArea.getText());
        assertEquals(TTM_CuoiKy.ERROR_TEXT_COLOR, messageArea.getForeground());
        assertDoesNotThrow(() -> assertTrue(messageArea.modelToView2D(warning.length() - 1).getMaxY()
            <= messageArea.getHeight(), "The warning must be fully visible"));
        field(checkout.get().seller, "tabs", JTabbedPane.class).setSelectedIndex(1);
        checkout.get().validate();
        assertTrue(toast.isShowing());
        assertEquals(warning, messageArea.getText());
      });
    } finally {
      SwingUtilities.invokeAndWait(() -> {
        if (management.get() != null) management.get().dispose();
        if (checkout.get() != null) checkout.get().dispose();
      });
    }
  }

  @Test
  void checkoutDoesNotRepeatTheSameHeartbeatToastDuringAnOutage() throws Exception {
    var heartbeats = new AtomicInteger();
    var first = new CountDownLatch(1);
    var second = new CountDownLatch(1);
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/station/open" ->
          "{\"sessionId\":\"fixture\",\"epoch\":1,\"leaseUntil\":9999999999999}";
      case "/api/station/heartbeat" -> {
        int count = heartbeats.incrementAndGet();
        if (count == 1) first.countDown();
        if (count == 2) second.countDown();
        throw new ApiClient.ApiError(503, "CONNECTION", "Không kết nối được máy chủ.");
      }
      case "/api/readers", "/api/menu", "/api/menu/categories", "/api/invoices/current" -> "[]";
      case "/api/reader/activity" ->
          "{\"connected\":false,\"ownedByCaller\":false,\"operating\":false,\"topupReserved\":false}";
      case "/api/reader/status" -> """
          {"present":false,"alias":"","initialized":false,"cardId":"","tries":5,
           "role":"NONE","counter":0,"profileLength":0,"connectionId":"","lifecycleStatus":""}
          """;
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        CanteenFrame frame = new CanteenFrame(api, true, true);
        window.set(frame);
        frame.setLocation(-3000, -3000);
        frame.setVisible(true);
      });
      assertTrue(first.await(8, TimeUnit.SECONDS));
      awaitUi(() -> Toast.forComponent(window.get()).isShowing());
      assertTrue(second.await(8, TimeUnit.SECONDS));
      Thread.sleep(1_000);
      SwingUtilities.invokeAndWait(() -> assertFalse(Toast.forComponent(window.get()).isVisible()));
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(() -> {
        CanteenFrame frame = window.get();
        field(frame, "timer", Timer.class).stop();
        field(frame, "activityTimer", Timer.class).stop();
        var heartbeat = field(frame, "heartbeat", ScheduledExecutorService.class);
        if (heartbeat != null) heartbeat.shutdownNow();
        frame.dispose();
      });
    }
  }

  @Test
  void checkoutRestoresWaitingInvoiceAndDishImagesWhenStationReopens() throws Exception {
    var json = new ObjectMapper();
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB), "png", bytes);
    String image = Base64.getEncoder().encodeToString(bytes.toByteArray());
    long now = System.currentTimeMillis();
    var item = new Api.MenuItem(1, "Cơm gà", "Cơm", 35000, true, 1, 1, true);
    var invoice = new Api.Invoice(7, "HD00000007", "", "", "", 70000, "OPEN",
        List.of(new Api.InvoiceLine("Cơm gà", 2, 35000, 70000)), now, now + 300000,
        "", "", 1, "fixture", "TTAT00000000000007", "", "NONE");
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/station/open", "/api/station/heartbeat" ->
          "{\"sessionId\":\"fixture\",\"epoch\":1,\"leaseUntil\":9999999999999}";
      case "/api/invoices/current" -> fixtureJson(json, new Api.Invoice[] {invoice});
      case "/api/invoices/7" -> fixtureJson(json, invoice);
      case "/api/menu" -> fixtureJson(json, new Api.MenuItem[] {item});
      case "/api/menu/items/1/image" -> fixtureJson(json, new Api.MenuImage(image, "image/png", 1));
      case "/api/readers", "/api/menu/categories", "/api/menu/sales" -> "[]";
      case "/api/reader/activity" ->
          "{\"connected\":false,\"ownedByCaller\":false,\"operating\":false,\"topupReserved\":false}";
      case "/api/reader/status" -> """
          {"present":false,"alias":"","initialized":false,"cardId":"","tries":5,
           "role":"NONE","counter":0,"profileLength":0,"connectionId":"","lifecycleStatus":""}
          """;
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        window.set(new CanteenFrame(api, true, true));
        window.get().setLocation(-3000, -3000);
        window.get().setVisible(true);
      });
      awaitUi(() -> {
        CheckoutView seller = window.get().seller;
        JPanel rows = field(seller, "customerItems", JPanel.class);
        return rows.getComponentCount() == 1 && components(rows)
            .filter(c -> c instanceof JLabel label && label.getIcon() != null)
            .map(JLabel.class::cast).anyMatch(label -> label.getIcon()
                != field(seller, "images", DishImages.class).placeholder(80));
      });
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        assertEquals(7, field(seller, "invoice", Api.Invoice.class).id());
        assertEquals(1, field(seller, "tabs", JTabbedPane.class).getSelectedIndex());
        assertEquals("70.000 ₫", field(seller, "customerTotal", JLabel.class).getText());
        assertTrue(field(seller, "customerBank", JButton.class).isEnabled());
        assertFalse(field(seller, "customerPay", JButton.class).isEnabled());
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(() -> {
        field(window.get(), "timer", Timer.class).stop();
        field(window.get(), "activityTimer", Timer.class).stop();
        var heartbeat = field(window.get(), "heartbeat", ScheduledExecutorService.class);
        if (heartbeat != null) heartbeat.shutdownNow();
        window.get().dispose();
      });
    }
  }

  @Test
  void checkoutLoadsMenuWhileCardSearchRefreshes() throws Exception {
    var json = new ObjectMapper();
    var readersStarted = new CountDownLatch(1);
    var releaseReaders = new CountDownLatch(1);
    var menuReads = new AtomicInteger();
    var connections = new AtomicInteger();
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/station/open", "/api/station/heartbeat" ->
          "{\"sessionId\":\"fixture\",\"epoch\":1,\"leaseUntil\":9999999999999}";
      case "/api/readers" -> replyAfter(readersStarted, releaseReaders,
          "[{\"id\":\"SIM0001\",\"label\":\"Thẻ mô phỏng SIM0001\"}]");
      case "/api/menu" -> { menuReads.incrementAndGet(); yield "[]"; }
      case "/api/menu/categories", "/api/invoices/current" -> "[]";
      case "/api/reader/activity" -> fixtureJson(json, new Api.ReaderActivity(false, false, false, false,
          queryParameter(request.uri().getRawQuery(), "selected"), false));
      case "/api/reader/status" -> """
          {"present":false,"alias":"","initialized":false,"cardId":"","tries":5,
           "role":"NONE","counter":0,"profileLength":0,"connectionId":"","lifecycleStatus":""}
          """;
      case "/api/reader/connect" -> {
        assertEquals("SIM0001", requestJson(json, request).path("reader").asText());
        connections.incrementAndGet();
        yield fixtureJson(json, new Api.ReaderStatus(true, "SIM0001", true, "TTM000001", 5,
            "NONE", 0, 0, "checkout-fixture", "ACTIVE"));
      }
      case "/api/checkout/reader/prepare" -> fixtureJson(json,
          new Api.ReaderStatus(true, "SIM0001", true, "TTM000001", 5,
              "NONE", 0, 0, "checkout-fixture", "ACTIVE"));
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, true, true);
        window.set(frame);
        frame.setLocation(-3000, -3000);
        frame.setVisible(true);
      });
      await(readersStarted);
      awaitUi(() -> menuReads.get() == 1 && api.stationReady() && !window.get().isBusy());
      releaseReaders.countDown();
      awaitUi(() -> field(field(window.get(), "deviceSearch", DeviceSearch.class),
          "choices", Api.DeviceChoice[].class).length == 1);
      SwingUtilities.invokeAndWait(() -> {
        DeviceSearch search = field(window.get(), "deviceSearch", DeviceSearch.class);
        assertTrue(search.isEditable());
        assertEquals("SIM0001", field(search, "choices", Api.DeviceChoice[].class)[0].id());
        search.setChoices(field(search, "choices", Api.DeviceChoice[].class), "SIM0001");
      });
      awaitUi(() -> field(window.get(), "connectButton", JButton.class).isEnabled());
      SwingUtilities.invokeAndWait(() -> field(window.get(), "connectButton", JButton.class).doClick(0));
      awaitUi(() -> connections.get() == 1 && window.get().status().present() && !window.get().isBusy());
    } finally {
      releaseReaders.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(() -> {
        field(window.get(), "timer", javax.swing.Timer.class).stop();
        field(window.get(), "activityTimer", javax.swing.Timer.class).stop();
        var heartbeat = field(window.get(), "heartbeat", ScheduledExecutorService.class);
        if (heartbeat != null) heartbeat.shutdownNow();
        window.get().dispose();
      });
    }
  }

  @Test
  void periodicReadKeepsDisconnectEnabledWhileActivityIsIdle() throws Exception {
    var reading = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var activityDuringRead = new CountDownLatch(2);
    var disabledWhileReading = new AtomicInteger();
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/reader/status" -> {
        reading.countDown();
        await(release);
        yield CONNECTED;
      }
      case "/api/reader/activity" -> {
        if (reading.getCount() == 0 && release.getCount() != 0) activityDuringRead.countDown();
        yield "{\"connected\":true,\"ownedByCaller\":true,\"operating\":false,\"topupReserved\":false}";
      }
      case "/api/reader/sync" ->
          "{\"cardId\":\"fixture\",\"phase\":\"SYNCED\",\"operationId\":0,\"message\":\"Synced\"}";
      case "/api/readers" -> "[]";
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, false, true);
        window.set(frame);
        frame.setLocation(-2000, -2000);
        frame.setVisible(true);
        field(frame, "tabs", JTabbedPane.class).setSelectedIndex(1);
        setField(frame, "current", new Api.ReaderStatus(true, "Fixture", true, "fixture", 5,
            "NONE", 0, 0, "fixture-connection", "ACTIVE"));
        setField(frame, "fingerprint", "true|fixture-connection|fixture|true|ACTIVE");
      });
      awaitUi(() -> field(window.get(), "disconnectButton", JButton.class).isEnabled());
      SwingUtilities.invokeAndWait(() -> {
        JButton button = field(window.get(), "disconnectButton", JButton.class);
        button.addPropertyChangeListener("enabled", event -> {
          if (!(Boolean) event.getNewValue() && reading.getCount() == 0 && release.getCount() != 0)
            disabledWhileReading.incrementAndGet();
        });
        window.get().poll();
      });
      assertTrue(reading.await(5, TimeUnit.SECONDS));
      assertTrue(activityDuringRead.await(5, TimeUnit.SECONDS), "Activity must be polled during the slow read");
      SwingUtilities.invokeAndWait(() -> assertTrue(
          field(window.get(), "disconnectButton", JButton.class).isEnabled()));
      assertEquals(0, disabledWhileReading.get());
      release.countDown();
      awaitUi(() -> !field(window.get(), "polling", Boolean.class));
    } finally {
      release.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(() -> {
        field(window.get(), "timer", javax.swing.Timer.class).stop();
        field(window.get(), "activityTimer", javax.swing.Timer.class).stop();
        window.get().dispose();
      });
    }
  }

  @Test
  void adminTransactionsShowsFourTypesAndSearchesWithoutDuplicateRows() throws Exception {
    var json = new ObjectMapper();
    var filterRequest = new AtomicReference<String>();
    var unsupportedFilter = new AtomicBoolean();
    var searchStarted = new CountDownLatch(1);
    var releaseSearch = new CountDownLatch(1);
    var all = new java.util.ArrayList<Api.AdminTransaction>();
    all.add(new Api.AdminTransaction("BANK:1", "TOPUP", "2026-11-10T06:33:15Z", "NTCT07035200000001",
        "TTM000001", "CT070352", 10_000, "WAIT_CARD", "", false));
    all.add(new Api.AdminTransaction("CARD:2", "CARD_INVOICE", "2026-11-10T06:32:15Z", "HD00000002",
        "TTM000001", "CT070352", 20_000, "PAID", "", false));
    all.add(new Api.AdminTransaction("BANK:3", "BANK_INVOICE", "2026-11-10T06:31:15Z", "TTCT07035200000003",
        "TTM000001", "CT070352", 30_000, "PAID", "", false));
    all.add(new Api.AdminTransaction("BANK:4", "UNKNOWN", "2026-11-10T06:30:15Z", "BANK-4",
        "", "", 15_000, "REVIEW", "AMOUNT_MISMATCH", true));
    for (int n = 5; n <= 12; n++) all.add(new Api.AdminTransaction("BANK:" + n, "TOPUP",
        "2026-11-10T06:20:00Z", "NT-" + n, "TTM000002", "CT070353", n * 1000,
        "CREDITED", "", false));
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/admin/login" ->
          "{\"token\":\"fixture\",\"username\":\"fixture\",\"email\":\"fixture@example.invalid\"}";
      case "/api/admin/cards/overview" -> "{\"items\":[],\"total\":0,\"page\":0,\"pageSize\":10}";
      case "/api/admin/me" -> "{}";
      case "/api/reader/status" -> fixtureJson(json,
          new Api.ReaderStatus(false, "", false, "", 5, "NONE", 0, 0, "", ""));
      case "/api/admin/transactions", "/api/admin/transactions/filter" -> {
        assertEquals("Bearer fixture", request.headers().firstValue("Authorization").orElse(null));
        String term = queryParameter(request.uri().getRawQuery(), "q");
        int page = Integer.parseInt(queryParameter(request.uri().getRawQuery(), "page"));
        boolean filtered = request.uri().getPath().endsWith("/filter");
        if (filtered && unsupportedFilter.get())
          throw new ApiClient.ApiError(404, "NOT_FOUND", "Old backend fixture");
        if (filtered) filterRequest.set(request.uri().getRawQuery());
        String from = queryParameter(request.uri().getRawQuery(), "from");
        String to = queryParameter(request.uri().getRawQuery(), "to");
        String amount = queryParameter(request.uri().getRawQuery(), "amount");
        if ("TTM000001".equals(term) && searchStarted.getCount() != 0) {
          searchStarted.countDown();
          await(releaseSearch);
        }
        if ("FAIL".equals(term)) throw new ApiClient.ApiError(503, "PAGE_ERROR", "Không tải được trang.");
        List<Api.AdminTransaction> matching = all.stream().filter(item -> (term.isEmpty()
            || item.reference().equalsIgnoreCase(term) || item.cardId().equalsIgnoreCase(term)
            || item.studentCode().equalsIgnoreCase(term) || (item.simulated() && "DEMO".equalsIgnoreCase(term)))
            && (amount.isEmpty() || item.amount() == Long.parseLong(amount))
            && (from.isEmpty() || !java.time.Instant.parse(item.occurredAt())
                .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate()
                .isBefore(java.time.LocalDate.parse(from)))
            && (to.isEmpty() || !java.time.Instant.parse(item.occurredAt())
                .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate()
                .isAfter(java.time.LocalDate.parse(to))))
            .toList();
        int offset = Math.min(page * 10, matching.size());
        yield fixtureJson(json, new Api.AdminTransactionPage(
            matching.subList(offset, Math.min(offset + 10, matching.size())), matching.size(), page, 10));
      }
      default -> throw new AssertionError(request.uri().getPath());
    });
    CanteenFrame frame = openReconciliationAdmin(api);
    try {
      JDialog dialog = openReconciliation(frame);
      AdminTransactionsView view = components(dialog).filter(AdminTransactionsView.class::isInstance)
          .map(AdminTransactionsView.class::cast).findFirst().orElseThrow();
      SwingUtilities.invokeAndWait(() -> {
        JTable table = namedTable(view, "Giao dịch");
        assertEquals(1280, dialog.getWidth());
        assertTrue(dialog.getHeight() < 720, "The dialog should fit its content");
        assertTrue(components(dialog).noneMatch(c -> c instanceof JButton b && "OK".equals(b.getText())));
        JTextField search = field(view, "query", JTextField.class);
        JTextField start = field(view, "startDate", JTextField.class);
        JTextField end = field(view, "endDate", JTextField.class);
        JTextField amount = field(view, "amount", JTextField.class);
        Rectangle searchBounds = boundsIn(search, view), startBounds = boundsIn(start, view);
        Rectangle endBounds = boundsIn(end, view), amountBounds = boundsIn(amount, view);
        JLabel filterLabel = label(view, "Lọc theo :");
        assertNotNull(filterLabel);
        assertSame(amount, filterLabel.getLabelFor());
        Rectangle labelBounds = boundsIn(filterLabel, view);
        assertEquals(new java.awt.Dimension(400, 40), search.getSize());
        assertEquals(new java.awt.Dimension(180, 40), start.getSize(),
            "view=" + view.getSize() + ", search=" + searchBounds + ", start=" + startBounds
                + ", end=" + endBounds + ", amount=" + amountBounds);
        assertEquals(16, searchBounds.x);
        assertEquals(searchBounds.y, startBounds.y);
        assertEquals(startBounds.y, endBounds.y);
        assertEquals(endBounds.y, amountBounds.y);
        assertEquals(8, amountBounds.x - labelBounds.x - labelBounds.width);
        assertEquals(8, startBounds.x - amountBounds.x - amountBounds.width);
        assertEquals(8, endBounds.x - startBounds.x - startBounds.width);
        assertEquals(16, view.getWidth() - endBounds.x - endBounds.width);
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
        Rectangle scrollBounds = boundsIn(scroll, view);
        assertEquals(16, scrollBounds.x);
        assertEquals(view.getWidth() - 32, scrollBounds.width);
        assertEquals(16, scrollBounds.y - searchBounds.y - searchBounds.height);
        Rectangle paginationBounds = boundsIn(field(view, "pagination", PaginationBar.class), view);
        assertEquals(16, view.getHeight() - paginationBounds.y - paginationBounds.height);
        assertTrue(scroll.getHorizontalScrollBar().isVisible());
        assertTrue(table.getWidth() > scroll.getViewport().getWidth());
        scroll.getHorizontalScrollBar().setValue(scroll.getHorizontalScrollBar().getMaximum());
        assertTrue(scroll.getHorizontalScrollBar().getValue() > 0);
        scroll.getHorizontalScrollBar().setValue(0);
        assertEquals(10, table.getRowCount());
        assertEquals(8, table.getColumnCount());
        assertEquals("Nạp tiền", table.getValueAt(0, 1));
        assertEquals("Chờ ghi thẻ", table.getValueAt(0, 6));
        assertEquals("Thanh toán hóa đơn bằng số dư thẻ", table.getValueAt(1, 1));
        assertEquals("Thanh toán hóa đơn bằng chuyển khoản", table.getValueAt(2, 1));
        assertEquals("Không rõ", table.getValueAt(3, 1));
        assertEquals("Số tiền không khớp", table.getValueAt(3, 7));
        assertTrue(table.getValueAt(3, 2).toString().startsWith("[DEMO]"));
        assertEquals(41, table.getRowHeight(0));
        assertEquals(40, table.getRowHeight(9));
        assertFalse(table.isCellEditable(0, 0));
        assertEquals(SwingConstants.CENTER, ((JLabel) table.prepareRenderer(
            table.getCellRenderer(0, 1), 0, 1)).getHorizontalAlignment());
        assertEquals(SwingConstants.LEFT, ((JLabel) table.prepareRenderer(
            table.getCellRenderer(3, 7), 3, 7)).getHorizontalAlignment());
        assertTrue(components(dialog).noneMatch(JTabbedPane.class::isInstance));
        assertEquals("Tìm kiếm theo mã tham chiếu, ID thẻ hoặc mã SV",
            field(view, "query", JTextField.class).getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT));
        assertEquals("Hiển thị 1–10 trên tổng số 12 kết quả", field(view, "resultCount", JLabel.class).getText());
        captureReconciliation(dialog, "admin-unified-transactions.png");
        button(view, "2").doClick(0);
      });
      awaitUi(() -> field(view, "pageData", Api.AdminTransactionPage.class).page() == 1);
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("Hiển thị 11–12 trên tổng số 12 kết quả", field(view, "resultCount", JLabel.class).getText());
        JTextField query = field(view, "query", JTextField.class);
        query.setText("TTM000001");
        assertTrue(field(view, "status", JLabel.class).getText().isBlank());
        query.postActionEvent();
      });
      await(searchStarted);
      SwingUtilities.invokeAndWait(() -> assertTrue(field(view, "status", JLabel.class).getText().isBlank()));
      releaseSearch.countDown();
      awaitUi(() -> {
        Api.AdminTransactionPage page = field(view, "pageData", Api.AdminTransactionPage.class);
        return page != null && page.total() == 3;
      });
      SwingUtilities.invokeAndWait(() -> {
        JTextField start = field(view, "startDate", JTextField.class);
        JTextField end = field(view, "endDate", JTextField.class);
        JTextField amount = field(view, "amount", JTextField.class);
        start.setText("10112026");
        end.setText("10112026");
        amount.setText("10000");
        amount.postActionEvent();
      });
      awaitUi(() -> {
        Api.AdminTransactionPage page = field(view, "pageData", Api.AdminTransactionPage.class);
        return page != null && page.total() == 1 && filterRequest.get() != null
            && filterRequest.get().contains("from=2026-11-10")
            && filterRequest.get().contains("to=2026-11-10")
            && filterRequest.get().contains("amount=10000");
      });
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("BANK:1", field(view, "pageData", Api.AdminTransactionPage.class).items().getFirst().sourceKey());
        field(view, "endDate", JTextField.class).setText("");
        field(view, "amount", JTextField.class).setText("");
        field(view, "startDate", JTextField.class).postActionEvent();
      });
      awaitUi(() -> {
        Api.AdminTransactionPage page = field(view, "pageData", Api.AdminTransactionPage.class);
        return page != null && page.total() == 3 && filterRequest.get().contains("from=2026-11-10")
            && !filterRequest.get().contains("&to=") && !filterRequest.get().contains("&amount=");
      });
      SwingUtilities.invokeAndWait(() -> {
        field(view, "startDate", JTextField.class).setText("");
        field(view, "endDate", JTextField.class).setText("10112026");
        field(view, "endDate", JTextField.class).postActionEvent();
      });
      awaitUi(() -> {
        Api.AdminTransactionPage page = field(view, "pageData", Api.AdminTransactionPage.class);
        return page != null && page.total() == 3 && filterRequest.get().contains("to=2026-11-10")
            && !filterRequest.get().contains("&from=");
      });
      SwingUtilities.invokeAndWait(() -> {
        field(view, "endDate", JTextField.class).setText("");
        field(view, "amount", JTextField.class).setText("20000");
        field(view, "amount", JTextField.class).postActionEvent();
      });
      awaitUi(() -> {
        Api.AdminTransactionPage page = field(view, "pageData", Api.AdminTransactionPage.class);
        return page != null && page.total() == 1 && filterRequest.get().contains("amount=20000")
            && !filterRequest.get().contains("&from=") && !filterRequest.get().contains("&to=");
      });
      SwingUtilities.invokeAndWait(() -> {
        field(view, "endDate", JTextField.class).setText("10112026");
        field(view, "startDate", JTextField.class).setText("31112026");
        field(view, "startDate", JTextField.class).postActionEvent();
      });
      awaitUi(() -> field(view, "status", JLabel.class).getText().contains("Ngày bắt đầu không hợp lệ"));
      SwingUtilities.invokeAndWait(() -> {
        field(view, "startDate", JTextField.class).setText("11112026");
        field(view, "startDate", JTextField.class).postActionEvent();
      });
      awaitUi(() -> field(view, "status", JLabel.class).getText().contains("Ngày bắt đầu sau ngày kết thúc"));
      SwingUtilities.invokeAndWait(() -> {
        JTextField start = field(view, "startDate", JTextField.class);
        JButton picker = (JButton) start.getClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT);
        assertEquals(new java.awt.Dimension(40, 40), picker.getPreferredSize());
        SwingUtilities.invokeLater(() -> picker.doClick(0));
      });
      awaitUi(() -> Arrays.stream(Window.getWindows()).anyMatch(w -> w instanceof JDialog d
          && d.isShowing() && "Chọn ngày bắt đầu".equals(d.getTitle())));
      SwingUtilities.invokeAndWait(() -> {
        JDialog dateDialog = Arrays.stream(Window.getWindows()).filter(w -> w instanceof JDialog d
            && d.isShowing() && "Chọn ngày bắt đầu".equals(d.getTitle()))
            .map(JDialog.class::cast).findFirst().orElseThrow();
        List<JComboBox> choices = components(dateDialog).filter(JComboBox.class::isInstance)
            .map(JComboBox.class::cast).toList();
        assertEquals(3, choices.size());
        choices.get(1).setSelectedItem(11);
        choices.get(2).setSelectedItem(2026);
        choices.get(0).setSelectedItem(10);
        button(dateDialog, "Chọn").doClick(0);
      });
      awaitUi(() -> field(view, "startDate", JTextField.class).getText().equals("10/11/2026")
          && field(view, "pageData", Api.AdminTransactionPage.class) != null);
      unsupportedFilter.set(true);
      SwingUtilities.invokeAndWait(() -> {
        field(view, "amount", JTextField.class).setText("20000");
        field(view, "amount", JTextField.class).postActionEvent();
      });
      awaitUi(() -> field(view, "status", JLabel.class).getText().contains("cập nhật backend"));
      unsupportedFilter.set(false);
      SwingUtilities.invokeAndWait(() -> {
        field(view, "query", JTextField.class).setText("");
        field(view, "startDate", JTextField.class).setText("10112026");
        field(view, "endDate", JTextField.class).setText("10112026");
        field(view, "amount", JTextField.class).setText("");
        field(view, "startDate", JTextField.class).postActionEvent();
      });
      awaitUi(() -> {
        Api.AdminTransactionPage page = field(view, "pageData", Api.AdminTransactionPage.class);
        return page != null && page.total() == 12 && page.page() == 0;
      });
      SwingUtilities.invokeAndWait(() -> button(view, "2").doClick(0));
      awaitUi(() -> {
        Api.AdminTransactionPage page = field(view, "pageData", Api.AdminTransactionPage.class);
        return page != null && page.page() == 1 && page.items().size() == 2
            && filterRequest.get().contains("from=2026-11-10")
            && filterRequest.get().contains("to=2026-11-10");
      });
      SwingUtilities.invokeAndWait(() -> {
        field(view, "startDate", JTextField.class).setText("");
        field(view, "endDate", JTextField.class).setText("");
        field(view, "amount", JTextField.class).setText("");
        JTextField query = field(view, "query", JTextField.class);
        query.setText("DEMO");
        query.postActionEvent();
      });
      awaitUi(() -> {
        Api.AdminTransactionPage page = field(view, "pageData", Api.AdminTransactionPage.class);
        return page != null && page.total() == 1 && page.items().getFirst().simulated();
      });
      SwingUtilities.invokeAndWait(() -> {
        JTextField query = field(view, "query", JTextField.class);
        query.setText("FAIL");
        query.postActionEvent();
      });
      awaitUi(() -> field(view, "status", JLabel.class).getText().contains("Không tải được"));
      SwingUtilities.invokeAndWait(() -> {
        assertNull(field(view, "pageData", Api.AdminTransactionPage.class));
        assertNull(namedTable(view, "Giao dịch").getValueAt(0, 0));
        dialog.dispatchEvent(new java.awt.event.WindowEvent(dialog, java.awt.event.WindowEvent.WINDOW_CLOSING));
      });
      awaitUi(() -> !dialog.isShowing());
    } finally {
      releaseSearch.countDown();
      SwingUtilities.invokeAndWait(frame::dispose);
    }
  }

  @Test
  void adminTransactionsRejectsExpiredSessionAndStaleOpen() throws Exception {
    var json = new ObjectMapper();
    var failure = new AtomicReference<String>();
    var hold = new AtomicBoolean();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    Api.AdminTransaction row = new Api.AdminTransaction("BANK:1", "TOPUP", "2026-11-10T06:32:15Z",
        "NT-1", "TTM000001", "CT070352", 10_000, "CREDITED", "", false);
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/admin/login" ->
          "{\"token\":\"fixture\",\"username\":\"fixture\",\"email\":\"fixture@example.invalid\"}";
      case "/api/admin/cards/overview" -> "{\"items\":[],\"total\":0,\"page\":0,\"pageSize\":10}";
      case "/api/admin/me" -> "{}";
      case "/api/reader/status" -> fixtureJson(json,
          new Api.ReaderStatus(false, "", false, "", 5, "NONE", 0, 0, "", ""));
      case "/api/admin/transactions" -> {
        if (hold.get()) { started.countDown(); await(release); }
        if ("EXPIRED".equals(failure.get()))
          throw new ApiClient.ApiError(401, "ADMIN_EXPIRED", "Phiên Admin đã hết hạn.");
        yield fixtureJson(json, new Api.AdminTransactionPage(List.of(row), 1, 0, 10));
      }
      default -> throw new AssertionError(request.uri().getPath());
    });
    CanteenFrame frame = openReconciliationAdmin(api);
    try {
      hold.set(true);
      SwingUtilities.invokeLater(() -> button(frame.admin, "Giao dịch").doClick(0));
      await(started);
      SwingUtilities.invokeAndWait(frame::invalidatePrivateState);
      release.countDown();
      awaitIdle(frame);
      SwingUtilities.invokeAndWait(() -> assertTrue(Arrays.stream(frame.getOwnedWindows())
          .noneMatch(window -> window instanceof JDialog dialog && dialog.isShowing()
              && "Giao dịch".equals(dialog.getTitle()))));
      hold.set(false);
      failure.set("EXPIRED");
      SwingUtilities.invokeLater(() -> button(frame.admin, "Giao dịch").doClick(0));
      awaitUi(() -> !frame.admin.logged());
      answerDialog(frame, CanteenFrame.html("Phiên Admin đã hết hạn."), JOptionPane.OK_OPTION);
    } finally {
      release.countDown();
      SwingUtilities.invokeAndWait(frame::dispose);
    }
  }

  @Test
  void adminExplainsWhenBackendStillUsesTwentyItemPages() throws Exception {
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/admin/login" ->
          "{\"token\":\"fixture\",\"username\":\"fixture\",\"email\":\"fixture@example.invalid\"}";
      case "/api/admin/cards/overview" ->
          "{\"items\":[],\"total\":0,\"page\":0,\"pageSize\":20}";
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    String message = "Backend chưa được cập nhật. Vui lòng chạy 09-Cap-nhat-backend.cmd.";
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, false, false);
        window.set(frame);
        field(frame, "tabs", JTabbedPane.class).setSelectedIndex(1);
        frame.setVisible(true);
        field(frame.admin, "username", JTextField.class).setText("fixture");
        field(frame.admin, "password", JPasswordField.class).setText("fixture-password");
        button(frame.admin, "Đăng nhập").doClick(0);
      });
      answerDialog(window.get(), CanteenFrame.html(message), JOptionPane.OK_OPTION);
      SwingUtilities.invokeAndWait(() -> {
        assertFalse(field(window.get().admin, "searchPending", Boolean.class));
        assertEquals(message, toastText(window.get()));
        assertNull(field(window.get().admin, "pageData", Api.CardListPage.class));
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void authenticatedStudentAndAdminSurviveTabChangesIndependently() throws Exception {
    var json = new ObjectMapper();
    var reader = new AtomicReference<>(new Api.ReaderStatus(
        false, "", false, "", 5, "NONE", 0, 0, "", ""));
    var connected = new Api.ReaderStatus(
        true, "SIM0001", true, "TTM000001", 5, "NONE", 0, 0, "fixture-connection", "ACTIVE");
    var authenticated = new Api.ReaderStatus(
        true, "SIM0001", true, "TTM000001", 5, "STUDENT", 0, 0, "fixture-connection", "ACTIVE");
    var managed = new Api.ReaderStatus(
        true, "SIM0001", true, "TTM000001", 5, "ADMIN", 0, 0, "fixture-connection", "ACTIVE");
    Profile profile = fixtureProfile("TTM000001", "Sinh Viên Thử", "");
    var studentVerified = new AtomicBoolean();
    var pinLogins = new AtomicInteger();
    var wrongPinLogins = new AtomicInteger();
    var adminLogouts = new AtomicInteger();
    var authorizedHomeReads = new AtomicInteger();
    var failHome = new AtomicBoolean();
    var homeFailures = new AtomicInteger();
    CountDownLatch refreshingHome = new CountDownLatch(1), allowHome = new CountDownLatch(1);
    ApiClient api = api(request -> {
      String path = request.uri().getPath();
      if (path.startsWith("/api/student/") || path.startsWith("/api/topups")
          || path.startsWith("/api/reader/")) {
        assertTrue(request.headers().firstValue("Authorization").isEmpty(), path);
      }
      return switch (path) {
        case "/api/readers" -> fixtureJson(json,
            List.of(new Api.DeviceChoice("SIM0001", "Thẻ mô phỏng SIM0001")));
        case "/api/admin/login" ->
            "{\"token\":\"fixture\",\"username\":\"fixture\",\"email\":\"fixture@example.invalid\"}";
        case "/api/admin/cards/overview" -> "{\"items\":[],\"total\":0,\"page\":0,\"pageSize\":10}";
        case "/api/admin/me" -> {
          assertEquals("Bearer fixture", request.headers().firstValue("Authorization").orElse(null));
          yield "{}";
        }
        case "/api/admin/logout" -> {
          adminLogouts.incrementAndGet();
          yield "{\"message\":\"Signed out\"}";
        }
        case "/api/reader/connect" -> {
          reader.set(connected);
          yield fixtureJson(json, connected);
        }
        case "/api/reader/status" -> fixtureJson(json, reader.get());
        case "/api/reader/activity" -> fixtureJson(json,
            new Api.ReaderActivity(reader.get().present(), reader.get().present(), false, false));
        case "/api/reader/sync" ->
            fixtureJson(json, new Api.SyncStatus("TTM000001", "SYNCED", 0, "Đã đồng bộ"));
        case "/api/student/home" -> {
          if (!studentVerified.get()) throw new ApiClient.ApiError(401, "PIN_SESSION", "Nhập PIN");
          if (failHome.getAndSet(false)) {
            homeFailures.incrementAndGet();
            throw new ApiClient.ApiError(503, "CONNECTION", "Fixture disconnected");
          }
          if (authorizedHomeReads.incrementAndGet() == 1)
            yield replyAfter(refreshingHome, allowHome, fixtureJson(json, new Api.StudentHome(profile, 0)));
          yield fixtureJson(json, new Api.StudentHome(profile, 0));
        }
        case "/api/student/prepare" -> {
          reader.set(connected);
          yield fixtureJson(json, connected);
        }
        case "/api/student/login" -> {
          String pin = requestJson(json, request).path("pin").asText();
          if (pin.equals("000000")) {
            wrongPinLogins.incrementAndGet();
            throw new ApiClient.ApiError(401, "PIN_WRONG", "Mã PIN không đúng.");
          }
          assertEquals("123456", pin);
          pinLogins.incrementAndGet();
          studentVerified.set(true);
          reader.set(authenticated);
          yield fixtureJson(json, new Api.StudentHome(profile, 0));
        }
        case "/api/student/transactions/pages" -> "{\"items\":[],\"total\":0,\"page\":0,\"pageSize\":4}";
        case "/api/admin/cards/TTM000001/transactions/pages" -> {
          assertEquals("Bearer fixture", request.headers().firstValue("Authorization").orElse(null));
          yield "{\"items\":[],\"total\":0,\"page\":0,\"pageSize\":4}";
        }
        case "/api/topups" -> "[]";
        case "/api/admin/card" -> {
          assertEquals("Bearer fixture", request.headers().firstValue("Authorization").orElse(null));
          studentVerified.set(false);
          reader.set(managed);
          yield fixtureJson(json, new Api.AdminCard(managed, profile, true, ""));
        }
        default -> throw new AssertionError(path);
      };
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, false, true);
        window.set(frame);
        field(frame, "tabs", JTabbedPane.class).setSelectedIndex(1);
        frame.setVisible(true);
        field(frame.admin, "username", JTextField.class).setText("fixture");
        field(frame.admin, "password", JPasswordField.class).setText("fixture-password");
        button(frame.admin, "Đăng nhập").doClick(0);
      });
      CanteenFrame frame = window.get();
      awaitUi(() -> frame.admin.logged() && showingLabel(frame, "Quản trị hệ thống") && !frame.isBusy());
      awaitUi(() -> button(frame, "Kết nối").isEnabled());
      SwingUtilities.invokeAndWait(() -> {
        field(frame, "deviceSearch", DeviceSearch.class).setChoices(
            new Api.DeviceChoice[] {new Api.DeviceChoice("SIM0001", "Thẻ mô phỏng SIM0001")}, "SIM0001");
        button(frame, "Kết nối").doClick(0);
      });
      awaitUi(() -> frame.status().present() && !frame.isBusy()
          && field(frame, "activityKnown", Boolean.class));
      SwingUtilities.invokeAndWait(() -> field(frame, "tabs", JTabbedPane.class).setSelectedIndex(0));
      awaitUi(() -> showingLabel(frame, "Nhập mã PIN") && !frame.isBusy());
      SwingUtilities.invokeAndWait(() -> {
        var entry = field(frame.student, "pinEntry", SixDigitEntry.class);
        JPasswordField[] digits = field(entry, "digits", JPasswordField[].class);
        for (JPasswordField digit : digits) digit.setText("0");
        button(frame.student, "Xác thực").doClick(0);
      });
      awaitUi(() -> wrongPinLogins.get() == 1 && !frame.isBusy()
          && !frame.getGlassPane().isVisible()
          && field(field(frame.student, "pinEntry", SixDigitEntry.class), "feedback", JLabel.class)
              .getText().contains("Mã PIN không đúng."));
      SwingUtilities.invokeAndWait(() -> {
        var entry = field(frame.student, "pinEntry", SixDigitEntry.class);
        JPasswordField[] digits = field(entry, "digits", JPasswordField[].class);
        for (int i = 0; i < digits.length; i++) digits[i].setText("123456".substring(i, i + 1));
        button(frame.student, "Xác thực").doClick(0);
      });
      assertTrue(refreshingHome.await(5, TimeUnit.SECONDS), "The final student-home refresh must start");
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(frame.isBusy());
        assertTrue(frame.getGlassPane().isVisible(), "Keep the PIN screen covered until the card details are ready");
        assertTrue(showingLabel(frame.getGlassPane(), "Đang tải thông tin thẻ…"));
      });
      allowHome.countDown();
      awaitUi(() -> showingLabel(frame, "Thông tin thẻ") && !frame.isBusy());
      SwingUtilities.invokeAndWait(frame::poll);
      awaitUi(() -> frame.status().role().equals("STUDENT") && !frame.isBusy()
          && field(frame, "activityKnown", Boolean.class));
      SwingUtilities.invokeAndWait(() -> field(frame, "tabs", JTabbedPane.class).setSelectedIndex(1));
      awaitUi(() -> frame.adminTab() && !frame.isBusy() && field(frame, "activityKnown", Boolean.class));
      assertTrue(frame.admin.logged());
      SwingUtilities.invokeAndWait(() -> field(frame, "tabs", JTabbedPane.class).setSelectedIndex(0));
      awaitUi(() -> showingLabel(frame, "Thông tin thẻ") && !frame.isBusy());
      assertEquals(1, pinLogins.get());
      assertEquals(0, adminLogouts.get());

      awaitUi(() -> field(frame, "activityKnown", Boolean.class));
      SwingUtilities.invokeAndWait(() -> field(frame, "tabs", JTabbedPane.class).setSelectedIndex(1));
      awaitUi(() -> frame.adminTab() && !frame.isBusy());
      SwingUtilities.invokeAndWait(() -> button(frame.admin, "TTM000001").doClick(0));
      awaitUi(() -> showingLabel(frame.admin, "Thông tin thẻ") && !frame.isBusy());
      awaitUi(() -> field(frame, "activityKnown", Boolean.class));
      SwingUtilities.invokeAndWait(() -> field(frame, "tabs", JTabbedPane.class).setSelectedIndex(0));
      awaitUi(() -> showingLabel(frame, "Nhập mã PIN") && !frame.isBusy());
      assertTrue(frame.admin.logged());
      assertEquals(0, adminLogouts.get());
      SwingUtilities.invokeAndWait(() -> {
        failHome.set(true);
        var entry = field(frame.student, "pinEntry", SixDigitEntry.class);
        JPasswordField[] digits = field(entry, "digits", JPasswordField[].class);
        for (int i = 0; i < digits.length; i++) digits[i].setText("123456".substring(i, i + 1));
        button(frame.student, "Xác thực").doClick(0);
      });
      awaitUi(() -> homeFailures.get() == 1 && showingLabel(frame.student, "Thông tin thẻ")
          && !frame.isBusy() && !frame.getGlassPane().isVisible());
      assertEquals(2, pinLogins.get());
    } finally {
      allowHome.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void studentTopupOpensPageAndHonorsPaymentReceivedDuringCancellation() throws Exception {
    var json = new ObjectMapper();
    Profile profile = fixtureProfile("TTM000001", "Sinh Viên Thử", "");
    Api.StudentHome initial = new Api.StudentHome(profile, 50_000);
    Api.StudentHome credited = new Api.StudentHome(profile, 60_000);
    byte[] image;
    try (var input = getClass().getResourceAsStream("/fixtures/qr-sample.png")) {
      image = input.readAllBytes();
    }
    String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(image);
    Api.Topup waiting = new Api.Topup(77, "NTCT23010100000077", "TTM000001", 10_000,
        "WAITING_BANK", dataUrl, "Fixture");
    var created = new AtomicInteger();
    var cancellations = new AtomicInteger();
    var applied = new AtomicInteger();
    var homeReads = new AtomicInteger();
    var events = new AtomicReference<Consumer<Api.TopupEvent>>();
    var connection = new AtomicReference<Consumer<Boolean>>();
    var warning = new AtomicReference<Consumer<String>>();
    var streamClosed = new AtomicBoolean();
    var originalQr = new AtomicReference<Icon>();
    var qrBounds = new AtomicReference<Rectangle>();
    var detailsBounds = new AtomicReference<Rectangle>();
    CountDownLatch homeStarted = new CountDownLatch(1), allowHome = new CountDownLatch(1);
    CountDownLatch otherStarted = new CountDownLatch(1), allowOther = new CountDownLatch(1);
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/student/transactions/pages" ->
          "{\"items\":[],\"total\":0,\"page\":0,\"pageSize\":4}";
      case "/api/topups" -> {
        assertEquals("POST", request.method());
        assertEquals("includeExpiry=true&fullImage=true", request.uri().getRawQuery());
        assertEquals(10_000, requestJson(json, request).path("amount").asLong());
        created.incrementAndGet();
        yield fixtureJson(json, new Api.TopupQr(waiting, 300_000));
      }
      case "/api/topups/77" -> fixtureJson(json, waiting);
      case "/api/topups/77/cancel" -> {
        assertEquals("includeStatus=true", request.uri().getRawQuery());
        cancellations.incrementAndGet();
        yield fixtureJson(json, new Api.TopupCancellation("BANK_RECEIVED", "Đã nhận tiền."));
      }
      case "/api/topups/77/apply" -> {
        applied.incrementAndGet();
        yield fixtureJson(json, new Api.Topup(77, waiting.reference(), waiting.cardId(),
            waiting.amount(), "CREDITED", "", "Đã nạp tiền vào thẻ."));
      }
      case "/api/student/home" -> {
        if (homeReads.incrementAndGet() == 1)
          throw new ApiClient.ApiError(503, "CONNECTION", "Fixture disconnected");
        yield replyAfter(homeStarted, allowHome, fixtureJson(json, credited));
      }
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    var student = new AtomicReference<StudentView>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, false, false);
        window.set(frame);
        var view = new StudentView(frame,
            id -> api.get("/topups/" + id, Api.Topup.class),
            id -> api.post("/topups/" + id + "/cancel?includeStatus=true", null,
                Api.TopupCancellation.class),
            pin -> api.post("/student/login", pin, Api.StudentHome.class),
            change -> api.post("/student/change-pin", change, Api.Result.class),
            (id, status, warningHandler, connected, fatal) -> {
              assertEquals(77, id);
              events.set(status);
              connection.set(connected);
              warning.set(warningHandler);
              return () -> streamClosed.set(true);
            });
        student.set(view);
        field(frame, "tabs", JTabbedPane.class).setComponentAt(0, view);
        frame.setVisible(true);
        invoke(view, "showHome", Api.StudentHome.class, initial);
      });
      CanteenFrame frame = window.get();
      StudentView view = student.get();
      awaitUi(() -> showingLabel(view, "Thông tin thẻ"));
      clickToOpenDialog(view, "Nạp tiền");
      awaitUi(() -> Arrays.stream(frame.getOwnedWindows())
          .anyMatch(w -> w instanceof JDialog dialog && dialog.isShowing()
              && "Nạp tiền".equals(dialog.getTitle())));
      SwingUtilities.invokeAndWait(() -> {
        JDialog dialog = Arrays.stream(frame.getOwnedWindows())
            .filter(w -> w instanceof JDialog d && d.isShowing() && "Nạp tiền".equals(d.getTitle()))
            .map(JDialog.class::cast).findFirst().orElseThrow();
        JTextField amount = components(dialog).filter(JTextField.class::isInstance)
            .map(JTextField.class::cast).findFirst().orElseThrow();
        amount.setText("10000");
        button(dialog, "OK").doClick(0);
      });
      awaitUi(() -> showingLabel(view, "Mã tham chiếu") && !frame.isBusy());
      events.get().accept(new Api.TopupEvent(77, 0, "WAITING_BANK", ""));
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(showingLabel(view, "10.000 ₫"));
        assertTrue(showingLabel(view, waiting.reference()));
        assertTrue(Arrays.stream(frame.getOwnedWindows()).noneMatch(Window::isShowing));
        invoke(view, "showHome", Api.StudentHome.class, initial);
        assertTrue(showingLabel(view, "Mã tham chiếu"),
            "Refreshing the student session must keep the active QR page visible");
        var tabs = field(frame, "tabs", JTabbedPane.class);
        tabs.setSelectedIndex(1);
        tabs.setSelectedIndex(0);
        assertTrue(showingLabel(view, "Mã tham chiếu"));
        JLabel qr = field(view, "qrImage", JLabel.class);
        originalQr.set(qr.getIcon());
        qrBounds.set(boundsIn(qr, view));
        detailsBounds.set(boundsIn(label(view, waiting.reference()), view));
        assertDoesNotThrow(() -> {
          Files.createDirectories(Path.of("target/ui-check"));
          ImageIO.write(painted(frame), "png", Path.of("target/ui-check/topup-before-processing.png").toFile());
        });
        button(view, "Hủy yêu cầu").doClick(0);
      });
      awaitUi(() -> showingLabel(view, "Đã nhận tiền. Đang ghi vào thẻ…"));
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(frame.getGlassPane().isVisible(), "Keep a waiting veil on the QR page while writing");
        assertNotNull(label(frame.getGlassPane(), "Đã nhận tiền. Đang ghi vào thẻ…"));
        assertSame(originalQr.get(), field(view, "qrImage", JLabel.class).getIcon());
        assertEquals(qrBounds.get(), boundsIn(field(view, "qrImage", JLabel.class), view));
        assertEquals(detailsBounds.get(), boundsIn(label(view, waiting.reference()), view));
        assertDoesNotThrow(() -> ImageIO.write(painted(frame), "png",
            Path.of("target/ui-check/topup-processing.png").toFile()));
      });
      SwingUtilities.invokeAndWait(() -> frame.run("Đang kiểm tra…",
          () -> replyAfter(otherStarted, allowOther, "done"), ignored -> {}));
      assertTrue(otherStarted.await(5, TimeUnit.SECONDS));
      events.get().accept(new Api.TopupEvent(77, 1, "BANK_RECEIVED", ""));
      events.get().accept(new Api.TopupEvent(77, 0, "WAITING_BANK", ""));
      warning.get().accept("Đã nhận tiền. Cần đồng bộ lại thẻ trước khi tiếp tục.");
      awaitUi(() -> field(view, "deferredTopupEvent", Api.TopupEvent.class) != null
          && showingLabel(view, "Đã nhận tiền. Cần đồng bộ lại thẻ trước khi tiếp tục."));
      connection.get().accept(false);
      awaitUi(() -> showingLabel(view, "Đã nhận tiền. Đang kết nối lại để kiểm tra thẻ."));
      allowOther.countDown();
      awaitUi(() -> !frame.isBusy() && !frame.getGlassPane().isVisible()
          && field(view, "lastTopupRevision", Long.class) == 1
          && showingLabel(view, "Đã nhận tiền. Đang kết nối lại để kiểm tra thẻ."));
      connection.get().accept(true);
      awaitUi(() -> !frame.getGlassPane().isVisible()
          && showingLabel(view, "Đã nhận tiền. Cần đồng bộ lại thẻ trước khi tiếp tục."));
      SwingUtilities.invokeAndWait(() -> assertFalse(button(view, "Hủy yêu cầu").isEnabled()));
      warning.get().accept("Đã nhận tiền. Đang thử ghi lại an toàn…");
      awaitUi(() -> label(frame.getGlassPane(), "Đã nhận tiền. Đang thử ghi lại an toàn…") != null);
      connection.get().accept(false);
      awaitUi(() -> showingLabel(view, "Đã nhận tiền. Đang kết nối lại để kiểm tra thẻ."));
      SwingUtilities.invokeAndWait(() -> assertFalse(frame.getGlassPane().isVisible()));
      connection.get().accept(true);
      awaitUi(() -> label(frame.getGlassPane(), "Đã nhận tiền. Đang thử ghi lại an toàn…") != null);
      warning.get().accept("Đã nhận tiền. Cần đồng bộ lại thẻ trước khi tiếp tục.");
      awaitUi(() -> !frame.getGlassPane().isVisible()
          && showingLabel(view, "Đã nhận tiền. Cần đồng bộ lại thẻ trước khi tiếp tục."));
      events.get().accept(new Api.TopupEvent(77, 2, "CREDITED", ""));
      awaitUi(() -> !frame.getGlassPane().isVisible()
          && showingLabel(view, "Đã ghi thẻ. Chưa tải được số dư; vui lòng thử lại."));
      SwingUtilities.invokeAndWait(() -> button(view, "Tải lại số dư").doClick(0));
      assertTrue(homeStarted.await(5, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(frame.getGlassPane().isVisible());
        assertNotNull(label(frame.getGlassPane(), "Đang tải số dư…"));
        assertSame(originalQr.get(), field(view, "qrImage", JLabel.class).getIcon());
        assertEquals(qrBounds.get(), boundsIn(field(view, "qrImage", JLabel.class), view));
        assertEquals(detailsBounds.get(), boundsIn(label(view, waiting.reference()), view));
      });
      allowHome.countDown();
      answerDialog(frame, "Nạp tiền thành công!\nSố dư hiện tại: 60.000 ₫", JOptionPane.OK_OPTION);
      awaitUi(() -> showingLabel(view, "Thông tin thẻ") && !frame.isBusy());
      assertEquals(1, created.get());
      assertEquals(1, cancellations.get());
      assertEquals(2, homeReads.get());
      assertEquals(0, applied.get(), "Only the backend should start a card credit");
      assertTrue(streamClosed.get());
    } finally {
      allowHome.countDown();
      allowOther.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void adminCredentialsStayScopedToAdminRequests() throws Exception {
    var requests = new AtomicInteger();
    ApiClient api = api(request -> {
      String path = request.uri().getPath();
      boolean adminRequest = path.startsWith("/api/admin/") || path.equals("/api/readers/new");
      assertEquals(adminRequest, request.headers().firstValue("Authorization").isPresent());
      requests.incrementAndGet();
      return "{}";
    });
    api.token("fixture");
    api.get("/student/home", Map.class);
    api.get("/student/transactions?beforeId=10", Map.class);
    api.get("/student/transactions/pages?page=1", Map.class);
    api.post("/topups?includeExpiry=true", null, Map.class);
    api.get("/reader/status", Map.class);
    api.get("/admin/me", Map.class);
    api.post("/readers/new", null, Map.class);
    assertEquals(7, requests.get());
  }

  @Test
  void adminSearchDebouncesAndIgnoresResultsForOlderText() throws Exception {
    var json = new ObjectMapper();
    Profile first = fixtureProfile("TTM000001", "Nguyễn An", "");
    Profile latest = fixtureProfile("TTM000002", "Trần Bình", "");
    var requests = new AtomicInteger();
    var oldStarted = new CountDownLatch(1);
    var releaseOld = new CountDownLatch(1);
    var oldFinished = new CountDownLatch(1);
    var filterStarted = new CountDownLatch(1);
    var filteredQuery = new AtomicReference<String>();
    ApiClient api = api(request -> {
      String path = request.uri().getPath();
      if (path.equals("/api/admin/login")) {
        return "{\"token\":\"fixture\",\"username\":\"fixture\",\"email\":\"fixture@example.invalid\"}";
      }
      if (!path.equals("/api/admin/cards/overview")) throw new AssertionError(path);
      requests.incrementAndGet();
      String params = java.net.URLDecoder.decode(request.uri().getRawQuery(),
          java.nio.charset.StandardCharsets.UTF_8);
      if (params.contains("page=1")) {
        return fixtureJson(json, new Api.CardListPage(
            List.of(first), 11, 1, 10, Map.of(first.cardId(), "ACTIVE")));
      }
      if (params.startsWith("q=a&")) {
        oldStarted.countDown();
        while (releaseOld.getCount() != 0) {
          try {
            if (!releaseOld.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out");
          } catch (InterruptedException ignored) {
            // Simulate a server response that cannot be cancelled once started.
          }
        }
        oldFinished.countDown();
        return fixtureJson(json, new Api.CardListPage(
            List.of(first), 1, 0, 10, Map.of(first.cardId(), "ACTIVE")));
      }
      if (params.startsWith("q=ab&")) {
        if (params.contains("group=CT7C")) {
          filteredQuery.set(params);
          filterStarted.countDown();
        }
        return fixtureJson(json, new Api.CardListPage(
            List.of(latest), 1, 0, 10, Map.of(latest.cardId(), "BLOCKED")));
      }
      return fixtureJson(json, new Api.CardListPage(
          List.of(first, latest), 11, 0, 10,
          Map.of(first.cardId(), "ACTIVE", latest.cardId(), "BLOCKED")));
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, false, false);
        window.set(frame);
        field(frame, "tabs", JTabbedPane.class).setSelectedIndex(1);
        frame.setVisible(true);
        field(frame.admin, "username", JTextField.class).setText("fixture");
        field(frame.admin, "password", JPasswordField.class).setText("fixture-password");
        button(frame.admin, "Đăng nhập").doClick(0);
      });
      CanteenFrame frame = window.get();
      awaitUi(() -> showingLabel(frame, "Quản trị hệ thống") && !frame.isBusy());
      SwingUtilities.invokeAndWait(() -> {
        JTable table = field(frame.admin, "table", JTable.class);
        assertEquals("ACTIVE", table.getValueAt(0, 7));
        assertEquals("BLOCKED", table.getValueAt(1, 7));
        table.changeSelection(0, 0, false, false);
        assertEquals(0, table.getSelectedRow());
        table.changeSelection(9, 0, false, false);
        assertEquals(-1, table.getSelectedRow());
        assertDoesNotThrow(() -> {
          Path capture = Path.of("target/ui-check/admin-search-dashboard.png");
          Files.createDirectories(capture.getParent());
          var image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
          var graphics = image.createGraphics();
          try {
            frame.paint(graphics);
          } finally {
            graphics.dispose();
          }
          ImageIO.write(image, "png", capture.toFile());
        });
        assertEquals("Hiển thị 1–2 trên tổng số 11 kết quả",
            field(frame.admin, "resultCount", JLabel.class).getText());
        assertTrue(button(frame.admin, ">").isEnabled());
        button(frame.admin, ">").doClick(0);
      });
      awaitUi(() -> !frame.isBusy()
          && field(frame.admin, "pageData", Api.CardListPage.class).page() == 1);
      SwingUtilities.invokeAndWait(() -> {
        JTable table = field(frame.admin, "table", JTable.class);
        assertEquals("Hiển thị 11 trên tổng số 11 kết quả",
            field(frame.admin, "resultCount", JLabel.class).getText());
        assertTrue(button(frame.admin, "<").isEnabled());
        assertFalse(button(frame.admin, ">").isEnabled());
        table.setRowSelectionInterval(0, 0);
        assertTrue(field(frame.admin, "block", JMenuItem.class).isEnabled());
        field(frame.admin, "query", JTextField.class).setText("a");
        assertFalse(Toast.forComponent(frame).isShowing());
        assertFalse(field(frame.admin, "block", JMenuItem.class).isEnabled());
        assertFalse(button(frame.admin, "<").isEnabled());
      });
      Thread.sleep(250);
      assertEquals(2, requests.get(), "No search should be sent before the debounce interval");
      await(oldStarted);
      SwingUtilities.invokeAndWait(() -> {
        assertFalse(Toast.forComponent(frame).isShowing());
        field(frame.admin, "query", JTextField.class).setText("ab");
      });
      awaitUi(() -> {
        JTable table = field(frame.admin, "table", JTable.class);
        return "TTM000002".equals(table.getValueAt(0, 0));
      });
      releaseOld.countDown();
      await(oldFinished);
      SwingUtilities.invokeAndWait(() -> {
        JTable table = field(frame.admin, "table", JTable.class);
        assertEquals("TTM000002", table.getValueAt(0, 0));
        assertEquals("BLOCKED", table.getValueAt(0, 7));
        assertFalse(button(frame.admin, "<").isEnabled());
        assertEquals(new java.awt.Color(0x606060), button(frame.admin, "1").getBackground());
        field(frame.admin, "yearFilter", JTextField.class).setText("20");
      });
      awaitUi(() -> toastText(frame).equals("Năm sinh phải gồm 4 chữ số."));
      assertEquals(4, requests.get(), "An incomplete year must not trigger a request");
      SwingUtilities.invokeAndWait(() -> {
        field(frame.admin, "yearFilter", JTextField.class).setText("2005");
        field(frame.admin, "classFilter", JTextField.class).setText("CT7C");
        field(frame.admin, "program", JComboBox.class).setSelectedIndex(2);
        field(frame.admin, "sort", JComboBox.class).setSelectedIndex(3);
      });
      await(filterStarted);
      assertTrue(filteredQuery.get().contains("q=ab&group=CT7C&program=CT&sort=NAME_ASC&page=0&year=2005"));
      awaitUi(() -> !field(frame.admin, "searchPending", Boolean.class));
    } finally {
      releaseOld.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void paginationKeepsTheCurrentPageOnFailureAndRecoversIfTheListShrinks() throws Exception {
    var json = new ObjectMapper();
    Profile profile = fixtureProfile("TTM000001", "Sinh Viên Thử", "");
    var pageRequests = new AtomicInteger();
    var failedOnce = new AtomicBoolean();
    var listShrunk = new AtomicBoolean();
    ApiClient api = api(request -> {
      if (request.uri().getPath().equals("/api/admin/login")) {
        return "{\"token\":\"fixture\",\"username\":\"fixture\",\"email\":\"fixture@example.invalid\"}";
      }
      if (!request.uri().getPath().equals("/api/admin/cards/overview"))
        throw new AssertionError(request.uri().getPath());
      boolean second = request.uri().getRawQuery().contains("&page=1");
      if (second) {
        pageRequests.incrementAndGet();
        if (failedOnce.compareAndSet(false, true))
          throw new ApiClient.ApiError(503, "PAGE_ERROR", "Không tải được trang.");
        listShrunk.set(true);
        return fixtureJson(json, new Api.CardListPage(List.of(), 10, 1, 10, Map.of()));
      }
      return fixtureJson(json, new Api.CardListPage(
          List.of(profile), listShrunk.get() ? 10 : 11, 0, 10, Map.of(profile.cardId(), "ACTIVE")));
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, false, false);
        window.set(frame);
        field(frame, "tabs", JTabbedPane.class).setSelectedIndex(1);
        frame.setVisible(true);
        field(frame.admin, "username", JTextField.class).setText("fixture");
        field(frame.admin, "password", JPasswordField.class).setText("fixture-password");
        button(frame.admin, "Đăng nhập").doClick(0);
      });
      CanteenFrame frame = window.get();
      awaitUi(() -> showingLabel(frame, "Quản trị hệ thống") && !frame.isBusy());
      SwingUtilities.invokeLater(() -> button(frame.admin, ">").doClick(0));
      answerDialog(frame, CanteenFrame.html("Không tải được trang."), JOptionPane.OK_OPTION);
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("Hiển thị 1 trên tổng số 11 kết quả",
            field(frame.admin, "resultCount", JLabel.class).getText());
        assertTrue(button(frame.admin, ">").isEnabled());
        assertEquals(new java.awt.Color(0x606060), button(frame.admin, "1").getBackground());
      });
      SwingUtilities.invokeAndWait(() -> button(frame.admin, ">").doClick(0));
      awaitUi(() -> !frame.isBusy() && pageRequests.get() == 2
          && field(frame.admin, "resultCount", JLabel.class).getText()
              .equals("Hiển thị 1 trên tổng số 10 kết quả"));
      SwingUtilities.invokeAndWait(() -> {
        assertFalse(button(frame.admin, "<").isEnabled());
        assertFalse(button(frame.admin, ">").isEnabled());
        assertTrue(components(frame.admin).noneMatch(
            c -> c instanceof JButton b && b.isShowing() && "2".equals(b.getText())));
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void paginationShowsRangesAndNavigatesTheRequestedTenPageSequence() throws Exception {
    var json = new ObjectMapper();
    List<Profile> profiles = java.util.stream.IntStream.rangeClosed(1, 100)
        .mapToObj(i -> fixtureProfile(String.format(java.util.Locale.ROOT, "TTM%06d", i),
            "Sinh Viên " + i, ""))
        .toList();
    ApiClient api = api(request -> {
      if (request.uri().getPath().equals("/api/admin/login")) {
        return "{\"token\":\"fixture\",\"username\":\"fixture\",\"email\":\"fixture@example.invalid\"}";
      }
      if (!request.uri().getPath().equals("/api/admin/cards/overview"))
        throw new AssertionError(request.uri().getPath());
      int index = Arrays.stream(request.uri().getRawQuery().split("&"))
          .filter(value -> value.startsWith("page="))
          .map(value -> Integer.parseInt(value.substring(5))).findFirst().orElseThrow();
      List<Profile> items = profiles.subList(index * 10, Math.min(100, (index + 1) * 10));
      Map<String, String> statuses = items.stream().collect(java.util.stream.Collectors.toMap(
          Profile::cardId, profile -> "ACTIVE"));
      return fixtureJson(json, new Api.CardListPage(items, profiles.size(), index, 10, statuses));
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, false, false);
        window.set(frame);
        field(frame, "tabs", JTabbedPane.class).setSelectedIndex(1);
        frame.setSize(frame.getMinimumSize());
        frame.setVisible(true);
        field(frame.admin, "username", JTextField.class).setText("fixture");
        field(frame.admin, "password", JPasswordField.class).setText("fixture-password");
        button(frame.admin, "Đăng nhập").doClick(0);
      });
      CanteenFrame frame = window.get();
      awaitUi(() -> showingLabel(frame, "Quản trị hệ thống") && !frame.isBusy());
      awaitUi(() -> fixedAdminTable(frame));
      SwingUtilities.invokeAndWait(() -> assertAdminListLayout(frame));
      SwingUtilities.invokeAndWait(() -> {
        frame.setSize(1280, 1000);
        frame.validate();
      });
      awaitUi(() -> fixedAdminTable(frame));
      SwingUtilities.invokeAndWait(() -> assertAdminListLayout(frame));
      SwingUtilities.invokeAndWait(() -> {
        frame.setSize(frame.getMinimumSize());
        frame.validate();
      });
      awaitUi(() -> fixedAdminTable(frame));
      SwingUtilities.invokeAndWait(() -> assertAdminListLayout(frame));
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("Hiển thị 1–10 trên tổng số 100 kết quả",
            field(frame.admin, "resultCount", JLabel.class).getText());
        assertPagination(frame, "< 1 2 3 ... 10 >");
        assertFalse(button(frame.admin, "<").isEnabled());
        var count = field(frame.admin, "resultCount", JLabel.class);
        var pager = field(frame.admin, "pagination", PaginationBar.class);
        assertSame(count.getParent().getParent(), pager.getParent());
        assertFalse(SwingUtilities.isDescendingFrom(pager, field(frame.admin, "table", JTable.class)));
        var scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(
            JScrollPane.class, field(frame.admin, "table", JTable.class));
        var next = button(frame.admin, ">");
        assertEquals(SwingUtilities.convertPoint(scroll, scroll.getWidth(), 0, frame).x,
            SwingUtilities.convertPoint(next, next.getWidth(), 0, frame).x);
        for (String text : new String[] {"<", "1", "...", ">"}) {
          assertEquals(new java.awt.Dimension(40, 40), button(frame.admin, text).getSize());
        }
        assertEquals(12, button(frame.admin, "<").getIcon().getIconWidth());
        assertEquals(12, button(frame.admin, ">").getIcon().getIconHeight());
        assertTrue(SwingUtilities.convertPoint(pager, 0, pager.getHeight(), frame).y
            <= frame.getHeight() - frame.getInsets().bottom);
        captureFrame(frame, "admin-pagination.png");
        button(frame.admin, "3").doClick(0);
      });
      awaitPage(frame, 2);
      SwingUtilities.invokeAndWait(() -> {
        assertPagination(frame, "< 1 2 3 ... 10 >");
        button(frame.admin, ">").doClick(0);
      });
      awaitPage(frame, 3);
      SwingUtilities.invokeAndWait(() -> {
        assertPagination(frame, "< 2 3 4 ... 10 >");
        button(frame.admin, "<").doClick(0);
      });
      awaitPage(frame, 2);
      SwingUtilities.invokeAndWait(() -> {
        assertPagination(frame, "< 2 3 4 ... 10 >");
        button(frame.admin, "10").doClick(0);
      });
      awaitPage(frame, 9);
      SwingUtilities.invokeAndWait(() -> {
        assertPagination(frame, "< 6 7 8 9 10 >");
        assertEquals("Hiển thị 91–100 trên tổng số 100 kết quả",
            field(frame.admin, "resultCount", JLabel.class).getText());
        assertFalse(button(frame.admin, ">").isEnabled());
        button(frame.admin, "6").doClick(0);
      });
      awaitPage(frame, 5);
      SwingUtilities.invokeAndWait(() -> {
        assertPagination(frame, "< 6 7 8 9 10 >");
        button(frame.admin, "<").doClick(0);
      });
      awaitPage(frame, 4);
      SwingUtilities.invokeAndWait(() -> assertPagination(frame, "< 5 6 7 ... 10 >"));
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void recoveryKeepsTheForgotDialogAndWaitsOnEachAdminPage() throws Exception {
    var sending = new CountDownLatch(1);
    var releaseSend = new CountDownLatch(1);
    var verifying = new CountDownLatch(1);
    var releaseVerify = new CountDownLatch(1);
    var saving = new CountDownLatch(1);
    var releaseSave = new CountDownLatch(1);
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/admin/forgot" -> replyAfter(sending, releaseSend, "{\"message\":\"Sent\"}");
      case "/api/admin/recover/verify" ->
          replyAfter(verifying, releaseVerify, "{\"grant\":\"fixture\"}");
      case "/api/admin/recover/complete" ->
          replyAfter(saving, releaseSave, "{\"message\":\"Saved\"}");
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    var dialog = new AtomicReference<JDialog>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, false, false);
        window.set(frame);
        field(frame, "tabs", JTabbedPane.class).setSelectedIndex(1);
        frame.setVisible(true);
        SwingUtilities.invokeLater(() -> button(frame.admin, "Quên mật khẩu?").doClick(0));
      });
      SwingUtilities.invokeAndWait(() -> {
        JDialog forgot = Arrays.stream(window.get().getOwnedWindows())
            .filter(JDialog.class::isInstance).map(JDialog.class::cast)
            .filter(d -> d.isShowing() && d.getTitle().equals("Quên mật khẩu admin"))
            .findFirst().orElseThrow();
        dialog.set(forgot);
        var identity = components(forgot).filter(JTextField.class::isInstance)
            .map(JTextField.class::cast).findFirst().orElseThrow();
        identity.setText("fixture");
        button(forgot, "OK").doClick(0);
      });
      await(sending);
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(dialog.get().isShowing());
        assertFalse(button(dialog.get(), "OK").isEnabled());
        assertFalse(button(dialog.get(), "Cancel").isEnabled());
        assertTrue(showingLabel(window.get(), "Đăng nhập"));
        assertFalse(window.get().getGlassPane().isVisible());
      });
      releaseSend.countDown();
      awaitIdle(window.get());
      SwingUtilities.invokeAndWait(() -> {
        var frame = window.get();
        assertFalse(dialog.get().isDisplayable());
        assertTrue(showingLabel(frame, "Nhập mã OTP"));
        var otp = field(frame.admin, "recoveryOtp", SixDigitEntry.class);
        var digits = components(otp).filter(JPasswordField.class::isInstance)
            .map(JPasswordField.class::cast).toList();
        assertEquals(6, digits.size());
        for (int i = 0; i < digits.size(); i++) digits.get(i).setText(Integer.toString(i));
        button(frame.admin, "Tiếp tục").doClick(0);
      });
      await(verifying);
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(showingLabel(window.get(), "Nhập mã OTP"));
        assertTrue(showingLabel(window.get().getGlassPane(), "Đang kiểm tra mã…"));
        assertFalse(showingLabel(window.get(), "Đặt lại mật khẩu"));
      });
      releaseVerify.countDown();
      awaitIdle(window.get());
      SwingUtilities.invokeAndWait(() -> {
        var frame = window.get();
        assertTrue(showingLabel(frame, "Đặt lại mật khẩu"));
        button(frame.admin, "Hoàn tất").doClick(0);
        assertEquals(TTM_CuoiKy.ERROR_TEXT_COLOR,
            label(frame, "Mật khẩu mới phải chứa từ 12 đến 20 ký tự").getForeground());
        field(frame.admin, "newPassword", JPasswordField.class).setText("fixture-password");
        field(frame.admin, "confirmPassword", JPasswordField.class).setText("fixture-password");
        button(frame.admin, "Hoàn tất").doClick(0);
      });
      await(saving);
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(showingLabel(window.get(), "Đặt lại mật khẩu"));
        assertTrue(showingLabel(window.get().getGlassPane(), "Đang đặt lại mật khẩu…"));
      });
      releaseSave.countDown();
      awaitIdle(window.get());
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(showingLabel(window.get(), "Đăng nhập"));
        assertFalse(window.get().getGlassPane().isVisible());
      });
    } finally {
      releaseSend.countDown();
      releaseVerify.countDown();
      releaseSave.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void checkoutKeepsInvoiceVisibleDuringInlinePinPayment() throws Exception {
    var paying = new CountDownLatch(1);
    var releasePay = new CountDownLatch(1);
    var finalInvoice = new AtomicReference<String>();
    long now = System.currentTimeMillis();
    var reader = new Api.ReaderStatus(
        true, "Fixture", true, "fixture", 5, "NONE", 0, 0, "fixture-connection", "ACTIVE");
    var open = invoice("OPEN", "", 1, now);
    var paid = invoice("PAID", "", 2, now);
    var json = new ObjectMapper();
    String openJson = json.writeValueAsString(open);
    String paidJson = json.writeValueAsString(paid);
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/invoices/7/prepare", "/api/reader/status" -> CONNECTED;
      case "/api/reader/sync" -> "{\"cardId\":\"fixture\",\"phase\":\"SYNCED\",\"operationId\":0,\"message\":\"Synced\"}";
      case "/api/invoices/7" -> finalInvoice.get() == null ? openJson : finalInvoice.get();
      case "/api/invoices/7/pay" -> {
        paying.countDown();
        await(releasePay);
        finalInvoice.set(paidJson);
        yield paidJson;
      }
      default -> throw new AssertionError(request.uri().getPath());
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, true, false);
        window.set(frame);
        invoke(frame, "updateStatus", Api.ReaderStatus.class, reader);
        frame.seller.cardValidated(reader);
        invoke(frame.seller, "accept", Api.Invoice.class, open);
        field(frame.seller, "tabs", JTabbedPane.class).setSelectedIndex(1);
        frame.setVisible(true);
        button(frame, "Tiến hành thanh toán").doClick(0);
      });
      awaitUi(() -> field(window.get().seller, "pinOpen", Boolean.class));
      SwingUtilities.invokeAndWait(() -> {
        CheckoutView seller = window.get().seller;
        SixDigitEntry pin = field(seller, "customerPin", SixDigitEntry.class);
        assertTrue(pin.isShowing());
        assertTrue(field(seller, "customerItems", JPanel.class).isShowing());
        enterPin(pin, "123456");
        button(pin, "Xác nhận thanh toán").doClick(0);
      });
      await(paying);
      SwingUtilities.invokeAndWait(() -> {
        SixDigitEntry pin = field(window.get().seller, "customerPin", SixDigitEntry.class);
        assertTrue(pin.isShowing());
        assertFalse(pin.inputEnabled());
        assertTrue(field(window.get().seller, "customerItems", JPanel.class).isShowing());
        assertFalse(window.get().getGlassPane().isVisible());
      });
      releasePay.countDown();
      awaitUi(() -> "PAID".equals(field(window.get().seller, "invoice", Api.Invoice.class).status()));
      SwingUtilities.invokeAndWait(() -> {
        assertFalse(field(window.get().seller, "pinOpen", Boolean.class));
        assertFalse(window.get().getGlassPane().isVisible());
        JTextPane notice = field(Toast.forComponent(window.get()), "message", JTextPane.class);
        assertTrue(Toast.forComponent(window.get()).isShowing());
        assertEquals("Thanh toán hoá đơn thành công!", notice.getText());
      });
    } finally {
      releasePay.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void managementRefreshLeavesSearchAndCachedResultsUsableUntilTheReply() throws Exception {
    var fetching = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ApiClient api = api(request -> {
      assertEquals("/api/readers", request.uri().getPath());
      return replyAfter(fetching, release, """
          [{"id":"SIM0001","label":"Thẻ cũ"},{"id":"SIM0002","label":"Thẻ mới"}]
          """);
    });
    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, false, false);
        window.set(frame);
        var search = field(frame, "deviceSearch", DeviceSearch.class);
        search.setChoices(new Api.DeviceChoice[] {new Api.DeviceChoice("SIM0001", "Thẻ cũ")}, null);
        frame.setVisible(true);
        frame.reloadDevices();
      });
      await(fetching);
      SwingUtilities.invokeAndWait(() -> {
        CanteenFrame frame = window.get();
        var search = field(frame, "deviceSearch", DeviceSearch.class);
        assertFalse(frame.isBusy());
        assertFalse(frame.getGlassPane().isVisible());
        assertTrue(search.isEditable());
        search.setText("SIM000");
        assertEquals(1, field(search, "choices", Api.DeviceChoice[].class).length);
      });
      release.countDown();
      awaitUi(() -> {
        var search = field(window.get(), "deviceSearch", DeviceSearch.class);
        return field(search, "choices", Api.DeviceChoice[].class).length == 2;
      });
      SwingUtilities.invokeAndWait(() ->
          assertEquals("SIM000", field(window.get(), "deviceSearch", DeviceSearch.class).getText()));
    } finally {
      release.countDown();
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void managementRunsTheCardWizardAndSelectedCardActions() throws Exception {
    var json = new ObjectMapper();
    String alias = "SIM0001", cardId = "TTM000001", connection = "fixture-card-connection";
    var blank = new Api.ReaderStatus(true, alias, false, "", 5, "NONE", 0, 0, connection, "ACTIVE");
    var initialized = new Api.ReaderStatus(
        true, alias, true, cardId, 5, "ADMIN", 1, 100, connection, "ACTIVE");
    var blocked = new Api.ReaderStatus(
        true, alias, true, cardId, 0, "NONE", 1, 100, connection, "BLOCKED");
    var otherConnected = new Api.ReaderStatus(
        true, "SIM0002", true, "TTM000002", 5, "NONE", 0, 0, "other-connection", "ACTIVE");
    var absent = new Api.ReaderStatus(false, "", false, "", 5, "NONE", 0, 0, "", "");
    var reader = new AtomicReference<>(blank);
    var otherStatus = new AtomicReference<>("ACTIVE");
    var syncPhase = new AtomicReference<>("SYNCED");
    var backendOffline = new AtomicBoolean();
    var avatar = new ByteArrayOutputStream();
    var portrait = new BufferedImage(12, 16, BufferedImage.TYPE_INT_RGB);
    var portraitGraphics = portrait.createGraphics();
    try {
      portraitGraphics.setColor(java.awt.Color.WHITE);
      portraitGraphics.fillRect(6, 0, 6, 16);
    } finally {
      portraitGraphics.dispose();
    }
    ImageIO.write(portrait, "png", avatar);
    String photo = Base64.getEncoder().encodeToString(avatar.toByteArray());
    Profile other = fixtureProfile("TTM000002", "Sinh Viên Khác", photo);
    var stored = new AtomicReference<Profile>();
    var creates = new AtomicInteger();
    var reads = new AtomicInteger();
    var enrollments = new AtomicInteger();
    var updates = new AtomicInteger();
    var resets = new AtomicInteger();
    ApiClient api = api(request -> switch (request.uri().getPath()) {
      case "/api/admin/login" ->
          "{\"token\":\"fixture\",\"username\":\"fixture\",\"email\":\"fixture@example.invalid\"}";
      case "/api/admin/me" -> {
        if (backendOffline.get()) throw new ApiClient.ApiError(503, "CONNECTION", "Không kết nối được máy chủ.");
        yield "{\"username\":\"fixture\"}";
      }
      case "/api/admin/cards/overview" -> fixtureJson(json, new Api.CardListPage(
          stored.get() == null ? List.of(other) : List.of(other, stored.get()),
          stored.get() == null ? 1 : 2, 0, 10,
          Map.of(other.cardId(), otherStatus.get(), cardId, reader.get().lifecycleStatus())));
      case "/api/admin/cards/TTM000001" -> fixtureJson(json, stored.get());
      case "/api/admin/cards/TTM000002" -> fixtureJson(json, other);
      case "/api/admin/cards/TTM000001/transactions/pages",
          "/api/admin/cards/TTM000002/transactions/pages" -> {
        assertEquals("Bearer fixture", request.headers().firstValue("Authorization").orElse(null));
        int page = Integer.parseInt(request.uri().getRawQuery().substring("page=".length()));
        boolean secondCard = request.uri().getPath().contains("TTM000002");
        int total = secondCard ? 5 : 1;
        List<Api.CardTransaction> entries = java.util.stream.IntStream.range(page * 4,
                Math.min(total, page * 4 + 4))
            .mapToObj(i -> new Api.CardTransaction(i + 1, "CREDIT", 10_000 + i,
                "2026-11-01T03:00:00Z", (secondCard ? "OTHER-" : "CARD-") + i))
            .toList();
        yield fixtureJson(json, new Api.CardTransactionListPage(entries, total, page, 4));
      }
      case "/api/readers/new" -> {
        creates.incrementAndGet();
        yield fixtureJson(json, Map.of("id", alias));
      }
      case "/api/readers" -> fixtureJson(json, reader.get().present()
          ? List.of(new Api.DeviceChoice(alias, "Thẻ mô phỏng " + alias)) : List.of());
      case "/api/reader/connect" -> {
        assertEquals(alias, requestJson(json, request).path("reader").asText());
        Api.ReaderStatus connected = stored.get() == null ? blank : initialized;
        reader.set(connected);
        yield fixtureJson(json, connected);
      }
      case "/api/reader/eject" -> {
        reader.set(absent);
        yield "{\"message\":\"Disconnected\"}";
      }
      case "/api/reader/status" -> fixtureJson(json, reader.get());
      case "/api/reader/sync" ->
          fixtureJson(json, new Api.SyncStatus(reader.get().cardId(), syncPhase.get(), 0, "Synced"));
      case "/api/admin/card" -> {
        reads.incrementAndGet();
        assertEquals("connectionId=" + connection, request.uri().getQuery());
        yield fixtureJson(json, new Api.AdminCard(
            reader.get(), stored.get(), stored.get() != null, ""));
      }
      case "/api/admin/card/init" -> {
        enrollments.incrementAndGet();
        assertEquals("connectionId=" + connection, request.uri().getQuery());
        var input = requestJson(json, request);
        assertEquals("123456", input.path("pin").asText());
        assertEquals(photo, input.path("profile").path("photoBase64").asText());
        stored.set(fixtureProfile(cardId, "Sinh Viên Thử", photo));
        reader.set(initialized);
        yield fixtureJson(json, stored.get());
      }
      case "/api/admin/card/update" -> {
        updates.incrementAndGet();
        assertEquals("connectionId=" + connection + "&cardId=" + cardId, request.uri().getQuery());
        JsonNode submitted = requestJson(json, request).path("profile");
        assertEquals("Sinh Viên Đã Sửa", submitted.path("fullName").asText());
        stored.set(assertDoesNotThrow(() -> json.treeToValue(submitted, Profile.class)).withCard(cardId, 2));
        yield fixtureJson(json, stored.get());
      }
      case "/api/admin/card/reset-pin" -> {
        resets.incrementAndGet();
        assertEquals("connectionId=" + connection + "&cardId=" + cardId, request.uri().getQuery());
        assertEquals("654321", requestJson(json, request).path("pin").asText());
        yield "{\"message\":\"Reset\"}";
      }
      case "/api/admin/cards/TTM000001/block" -> {
        reader.set(blocked);
        yield "{\"message\":\"Đã tạm khóa thẻ.\"}";
      }
      case "/api/admin/cards/TTM000002/block" -> {
        otherStatus.set("BLOCKED");
        yield "{\"message\":\"Đã tạm khóa thẻ.\"}";
      }
      case "/api/admin/card/unblock" -> {
        assertEquals("connectionId=" + connection + "&cardId=" + cardId, request.uri().getQuery());
        assertEquals(blocked, reader.get());
        reader.set(initialized);
        yield "{\"message\":\"Đã khôi phục thẻ.\"}";
      }
      case "/api/admin/card/delete-preview" -> {
        assertEquals("connectionId=" + connection + "&cardId=" + cardId, request.uri().getQuery());
        assertEquals(blocked, reader.get());
        yield fixtureJson(json, new Api.DeletePreview(cardId, true, 1, 2, 1, "fixture-preview"));
      }
      case "/api/admin/card/delete" -> {
        assertEquals("connectionId=" + connection + "&cardId=" + cardId, request.uri().getQuery());
        var input = requestJson(json, request);
        assertEquals(cardId, input.path("cardId").asText());
        assertTrue(input.path("confirmBalance").asBoolean());
        assertTrue(input.path("confirmUnsynced").asBoolean());
        assertEquals("fixture-preview", input.path("previewToken").asText());
        reader.set(absent);
        stored.set(null);
        yield "{\"message\":\"Đã xóa cứng thẻ.\"}";
      }
      default -> throw new AssertionError(request.uri().getPath());
    });

    var window = new AtomicReference<CanteenFrame>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(api, false, false);
        window.set(frame);
        frame.setSize(frame.getMinimumSize());
        frame.setVisible(true);
      });
      CanteenFrame frame = window.get();
      clickToOpenDialog(frame, "Tạo thẻ mới");
      answerDialog(frame, "Đăng nhập Admin để tạo thẻ mới.", JOptionPane.OK_OPTION);
      assertEquals(0, creates.get());
      SwingUtilities.invokeAndWait(() -> {
        field(frame.admin, "username", JTextField.class).setText("fixture");
        field(frame.admin, "password", JPasswordField.class).setText("fixture-password");
        button(frame.admin, "Đăng nhập").doClick(0);
      });
      awaitIdle(frame);
      SwingUtilities.invokeAndWait(() -> {
        JButton cardStatus = button(frame.admin, "------------");
        assertFalse(cardStatus.isEnabled());
        assertEquals("Chưa có thẻ kết nối", cardStatus.getToolTipText());
        captureFrame(frame, "admin-dashboard-disconnected.png");
        assertFalse(field(frame.admin, "block", JMenuItem.class).isEnabled());
        assertFalse(field(frame.admin, "delete", JMenuItem.class).isEnabled());
        assertFalse(field(frame.admin, "restore", JMenuItem.class).isEnabled());
        button(frame, "Tạo thẻ mới").doClick(0);
      });
      answerDialog(frame, "Tạo thẻ mới thành công!\nID Thẻ : " + alias, JOptionPane.OK_OPTION);
      assertEquals(1, creates.get());
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(alias, field(frame, "deviceSearch", DeviceSearch.class).selection().id());
        button(frame, "Kết nối").doClick(0);
      });
      awaitIdle(frame);
      assertEquals(0, reads.get());
      SwingUtilities.invokeAndWait(() -> {
        JButton cardStatus = button(frame.admin, "------------");
        assertTrue(cardStatus.isEnabled());
        assertEquals("Đã kết nối thẻ, chưa có ID", cardStatus.getToolTipText());
        cardStatus.doClick(0);
      });
      answerDialog(frame, "Thẻ chưa được khởi tạo, cần khởi tạo trước khi thẻ được sử dụng",
          JOptionPane.OK_OPTION);
      var details = field(frame.admin, "cardView", AdminCardView.class);
      var form = field(details, "form", ProfileForm.class);
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(showingLabel(details, "Thông tin thẻ"));
        assertTrue(form.name.isEditable());
        assertFalse(showingLabel(details, "Số dư :"));
        form.code.setText("CT230101");
        form.name.setText("Sinh Viên Thử");
        form.citizen.setText("012345678901");
        form.birth.setText("03/02/2005");
        form.phone.setText("0912345678");
        form.email.setText("fixture@example.invalid");
        field(form, "intakeDisplay", JTextField.class).setText("2023");
      });
      clickToOpenDialog(frame, "Ngắt kết nối");
      answerDialog(frame, "Huỷ bỏ các thay đổi chưa lưu?", "Sửa tiếp");
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(frame.status().present());
        assertEquals("Sinh Viên Thử", form.name.getText());
        button(details, "Tiếp tục").doClick(0);
        assertNotNull(label(details, CanteenFrame.html("Bấm vào ảnh để chọn ảnh đại diện.")));
        setField(form, "photo", photo);
        button(details, "Tiếp tục").doClick(0);
        assertTrue(showingLabel(details, "Thiết lập mã PIN"));
        var initialPinEntry = field(details, "pinEntry", SixDigitEntry.class);
        JButton initialBack = field(initialPinEntry, "backButton", JButton.class);
        assertEquals("Quay lại", initialBack.getText());
        assertEquals(new java.awt.Dimension(192, 32), initialBack.getPreferredSize());
        enterCardPin(details, "123456");
        button(details, "Tiếp tục").doClick(0);
        assertTrue(showingLabel(details, "Xác nhận mã PIN"));
        enterCardPin(details, "123457");
        button(details, "Hoàn tất").doClick(0);
        assertEquals(0, enrollments.get());
        assertEquals(TTM_CuoiKy.ERROR_TEXT_COLOR,
            label(details, "PIN mới và xác nhận không khớp.").getForeground());
        enterCardPin(details, "123456");
        button(details, "Hoàn tất").doClick(0);
      });
      answerDialog(frame, "Khởi tạo thẻ thành công!", JOptionPane.OK_OPTION);
      awaitUi(() -> frame.status().initialized());
      assertEquals(1, enrollments.get());
      SwingUtilities.invokeAndWait(() -> button(details, "Quay lại danh sách").doClick(0));
      awaitIdle(frame);
      SwingUtilities.invokeAndWait(() -> {
        JButton cardStatus = button(frame.admin, cardId);
        assertTrue(cardStatus.isEnabled());
        assertEquals("Thẻ đang kết nối: " + cardId, cardStatus.getToolTipText());
        var insets = cardStatus.getInsets();
        var available = new java.awt.Rectangle(insets.left, insets.top,
            cardStatus.getWidth() - insets.left - insets.right,
            cardStatus.getHeight() - insets.top - insets.bottom);
        var textBounds = new java.awt.Rectangle();
        String visible = SwingUtilities.layoutCompoundLabel(cardStatus,
            cardStatus.getFontMetrics(cardStatus.getFont()), cardStatus.getText(), cardStatus.getIcon(),
            cardStatus.getVerticalAlignment(), cardStatus.getHorizontalAlignment(),
            cardStatus.getVerticalTextPosition(), cardStatus.getHorizontalTextPosition(),
            available, new java.awt.Rectangle(), textBounds, cardStatus.getIconTextGap());
        assertEquals(cardId, visible);
        assertTrue(textBounds.x + textBounds.width <= cardStatus.getWidth() - 24);
        captureFrame(frame, "admin-dashboard-connected.png");
        cardStatus.doClick(0);
      });
      awaitIdle(frame);
      var beforeEdit = new AtomicReference<Map<String, Rectangle>>();
      var avatarEnabledBeforeEdit = new AtomicBoolean();
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(2, reads.get());
        assertEquals("Sinh Viên Thử", form.name.getText());
        assertFalse(form.name.isEditable());
        assertFalse(field(form, "intakePicker", JButton.class).isEnabled());
        assertFalse(field(frame.admin, "table", JTable.class).isShowing());
        assertTrue(button(details, "Chỉnh sửa").getX() < button(details, "Đặt lại PIN").getX());
        frame.validate();
        var avatarComponent = (JButton) field(form, "avatar", JComponent.class);
        avatarEnabledBeforeEdit.set(avatarComponent.isEnabled());
        avatarComponent.getModel().setRollover(false);
        int beforeHover = painted(avatarComponent).getRGB(20, 20);
        avatarComponent.getModel().setRollover(true);
        assertEquals(beforeHover, painted(avatarComponent).getRGB(20, 20),
            "Chưa chỉnh sửa không có hiệu ứng đổi ảnh");
        avatarComponent.getModel().setRollover(false);
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, form);
        assertFalse(scroll.getVerticalScrollBar().isVisible());
        Rectangle avatarBounds = boundsIn(avatarComponent, details);
        Rectangle[] rows = {boundsIn(form.name, details), boundsIn(form.code, details),
            boundsIn(form.birth, details), boundsIn(form.phone, details)};
        int[] gaps = {rows[0].y - avatarBounds.y,
            rows[1].y - rows[0].y - rows[0].height,
            rows[2].y - rows[1].y - rows[1].height,
            rows[3].y - rows[2].y - rows[2].height,
            avatarBounds.y + avatarBounds.height - rows[3].y - rows[3].height};
        for (int gap : gaps) {
          assertTrue(Math.abs(gap - gaps[0]) <= 1, "Khoảng cách các hàng: " + Arrays.toString(gaps));
        }
        beforeEdit.set(profileBounds(details, form));
        button(details, "Chỉnh sửa").doClick(0);
      });
      SwingUtilities.invokeAndWait(() -> {
        frame.validate();
        CardTransactionsView history = field(details, "transactions", CardTransactionsView.class);
        assertEquals(beforeEdit.get(), profileBounds(details, form),
            "Bố cục thay đổi khi chuyển sang chỉnh sửa");
        assertFalse(avatarEnabledBeforeEdit.get(), "Chưa chỉnh sửa không được chọn ảnh");
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, form);
        assertFalse(scroll.getVerticalScrollBar().isVisible());
        JButton photoButton = (JButton) field(form, "avatar", JComponent.class);
        assertTrue(photoButton.isEnabled());
        assertFalse(form.intake.isShowing(), "Không dùng nút tăng/giảm năm");
        assertTrue(field(form, "intakeDisplay", JTextField.class).isEditable());
        assertTrue(field(form, "birthPicker", JButton.class).isEnabled());
        assertTrue(field(form, "intakePicker", JButton.class).isEnabled());
        for (String name : new String[] {"birthPicker", "intakePicker"}) {
          JButton picker = field(form, name, JButton.class);
          assertEquals(new java.awt.Dimension(40, 40), picker.getPreferredSize());
          assertEquals(20, picker.getIcon().getIconWidth());
          assertEquals(20, picker.getIcon().getIconHeight());
        }
        photoButton.getModel().setRollover(false);
        BufferedImage normalPhoto = painted(photoButton);
        photoButton.getModel().setRollover(true);
        BufferedImage hoveredPhoto = painted(photoButton);
        assertNotEquals(normalPhoto.getRGB(20, 20), hoveredPhoto.getRGB(20, 20),
            "Rê chuột trong chế độ sửa phải che ảnh");
        assertNotEquals(normalPhoto.getRGB(20, 20), normalPhoto.getRGB(200, 20));
        assertEquals(hoveredPhoto.getRGB(20, 20), hoveredPhoto.getRGB(200, 20),
            "Nền xám phải che hoàn toàn cả vùng ảnh sáng và vùng ảnh tối");
        int darkIconPixels = 0, minX = 240, minY = 320, maxX = 0, maxY = 0;
        for (int y = 128; y < 192; y++) {
          for (int x = 88; x < 152; x++) {
            var pixel = new java.awt.Color(hoveredPhoto.getRGB(x, y), true);
            if (pixel.getRed() < 80 && pixel.getGreen() < 80 && pixel.getBlue() < 80) {
              darkIconPixels++;
              minX = Math.min(minX, x);
              minY = Math.min(minY, y);
              maxX = Math.max(maxX, x);
              maxY = Math.max(maxY, y);
            }
          }
        }
        assertTrue(darkIconPixels > 50, "Biểu tượng SVG phải hiện ở giữa ảnh");
        assertTrue(maxX - minX + 1 > 48 && maxY - minY + 1 > 48,
            "Biểu tượng SVG phải được phóng lớn trong vùng 64 × 64");
        captureFrame(frame, "admin-edit-avatar-hover.png");
        photoButton.getModel().setRollover(false);

        JTextField year = field(form, "intakeDisplay", JTextField.class);
        year.selectAll();
        year.replaceSelection("202a");
        assertEquals("2023", year.getText());
        year.selectAll();
        year.replaceSelection("20234");
        assertEquals("2023", year.getText());
        year.setText("20");
        button(details, "Lưu").doClick(0);
        assertEquals(CanteenFrame.html("Năm nhập học phải gồm đúng 4 chữ số."),
            field(details, "feedback", JLabel.class).getText());
        assertEquals(0, updates.get());
        year.setText("2023");
        for (String invalidPhone : new String[] {"091234567", "09123456789", "091234567a"}) {
          form.phone.setText(invalidPhone);
          button(details, "Lưu").doClick(0);
          assertEquals(CanteenFrame.html("Số điện thoại phải có 10 chữ số, bắt đầu bằng 0."),
              field(details, "feedback", JLabel.class).getText());
          assertEquals(0, updates.get());
        }
        frame.validate();
        assertEquals(beforeEdit.get().get("history"), boundsIn(field(history, "table", JTable.class), details));
        assertEquals(beforeEdit.get().get("name"), boundsIn(form.name, details));
        assertFalse(scroll.getVerticalScrollBar().isVisible());
        captureFrame(frame, "admin-edit-validation.png");
        form.phone.setText("0912345678");
        form.birth.setText("31/02/2005");
        button(details, "Lưu").doClick(0);
        assertEquals(CanteenFrame.html("Ngày sinh phải hợp lệ và có dạng dd/MM/yyyy."),
            field(details, "feedback", JLabel.class).getText());
        assertEquals(0, updates.get());
        form.birth.setText("03/02/2005");
        form.name.setText("Thay Đổi Nháp");
      });
      SwingUtilities.invokeLater(() -> field(form, "birthPicker", JButton.class).doClick(0));
      var dateDialog = new AtomicReference<JDialog>();
      awaitUi(() -> {
        dateDialog.set(Arrays.stream(frame.getOwnedWindows())
            .filter(JDialog.class::isInstance).map(JDialog.class::cast)
            .filter(dialog -> dialog.isShowing() && "Chọn ngày sinh".equals(dialog.getTitle()))
            .findFirst().orElse(null));
        return dateDialog.get() != null;
      });
      SwingUtilities.invokeAndWait(() -> {
        JComboBox<?> year = combo(dateDialog.get(), "Năm sinh");
        JComboBox<?> month = combo(dateDialog.get(), "Tháng sinh");
        JComboBox<?> day = combo(dateDialog.get(), "Ngày sinh");
        year.setSelectedItem(2023);
        month.setSelectedItem(2);
        assertEquals(28, day.getItemCount());
        year.setSelectedItem(2024);
        assertEquals(29, day.getItemCount());
        day.setSelectedItem(29);
        assertDoesNotThrow(() -> {
          Path capture = Path.of("target/ui-check/admin-date-picker.png");
          Files.createDirectories(capture.getParent());
          ImageIO.write(painted(dateDialog.get()), "png", capture.toFile());
        });
        button(dateDialog.get(), "Chọn").doClick(0);
      });
      awaitUi(() -> !dateDialog.get().isShowing());
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("29/02/2024", form.birth.getText());
        form.birth.setText("03/02/2005");
        field(form, "intakeDisplay", JTextField.class).setText("2021");
      });
      SwingUtilities.invokeLater(() -> field(form, "intakePicker", JButton.class).doClick(0));
      var yearDialog = new AtomicReference<JDialog>();
      awaitUi(() -> {
        yearDialog.set(Arrays.stream(frame.getOwnedWindows())
            .filter(JDialog.class::isInstance).map(JDialog.class::cast)
            .filter(dialog -> dialog.isShowing() && "Chọn năm nhập học".equals(dialog.getTitle()))
            .findFirst().orElse(null));
        return yearDialog.get() != null;
      });
      SwingUtilities.invokeAndWait(() -> {
        JComboBox<?> year = combo(yearDialog.get(), "Năm nhập học");
        assertEquals(2021, year.getSelectedItem());
        year.setSelectedItem(2022);
        assertDoesNotThrow(() -> {
          Path capture = Path.of("target/ui-check/admin-year-picker.png");
          Files.createDirectories(capture.getParent());
          ImageIO.write(painted(yearDialog.get()), "png", capture.toFile());
        });
        button(yearDialog.get(), "Chọn").doClick(0);
      });
      awaitUi(() -> !yearDialog.get().isShowing());
      SwingUtilities.invokeAndWait(() ->
          assertEquals("2022", field(form, "intakeDisplay", JTextField.class).getText()));
      SwingUtilities.invokeLater(() -> field(form, "intakePicker", JButton.class).doClick(0));
      awaitUi(() -> {
        yearDialog.set(Arrays.stream(frame.getOwnedWindows())
            .filter(JDialog.class::isInstance).map(JDialog.class::cast)
            .filter(dialog -> dialog.isShowing() && "Chọn năm nhập học".equals(dialog.getTitle()))
            .findFirst().orElse(null));
        return yearDialog.get() != null;
      });
      SwingUtilities.invokeAndWait(() -> {
        JComboBox<?> year = combo(yearDialog.get(), "Năm nhập học");
        assertEquals(2022, year.getSelectedItem());
        year.setSelectedItem(2024);
        button(yearDialog.get(), "Hủy").doClick(0);
      });
      awaitUi(() -> !yearDialog.get().isShowing());
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("2022", field(form, "intakeDisplay", JTextField.class).getText());
      });
      clickToOpenDialog(details, "Huỷ bỏ");
      answerDialog(frame, "Huỷ bỏ các thay đổi chưa lưu?", "Sửa tiếp");
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(form.name.isEditable());
        assertEquals("Thay Đổi Nháp", form.name.getText());
      });
      clickToOpenDialog(details, "Huỷ bỏ");
      answerDialog(frame, "Huỷ bỏ các thay đổi chưa lưu?", "Huỷ bỏ");
      SwingUtilities.invokeAndWait(() -> {
        assertFalse(form.name.isEditable());
        assertFalse(field(form, "avatar", JComponent.class).isEnabled());
        assertFalse(field(form, "intakeDisplay", JTextField.class).isEditable());
        assertFalse(field(form, "birthPicker", JButton.class).isEnabled());
        assertFalse(field(form, "intakePicker", JButton.class).isEnabled());
        assertEquals("2023", field(form, "intakeDisplay", JTextField.class).getText());
        assertEquals("Sinh Viên Thử", form.name.getText());
        button(details, "Chỉnh sửa").doClick(0);
        form.name.setText("Sinh Viên Đã Sửa");
        field(form, "intakeDisplay", JTextField.class).setText("2022");
        button(details, "Lưu").doClick(0);
      });
      answerDialog(frame, "Thay đổi thông tin thẻ thành công!", JOptionPane.OK_OPTION);
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("Sinh Viên Đã Sửa", form.name.getText());
        assertEquals("2022", field(form, "intakeDisplay", JTextField.class).getText());
        assertFalse(field(form, "avatar", JComponent.class).isEnabled());
        button(details, "Đặt lại PIN").doClick(0);
        assertTrue(showingLabel(details, "Nhập mã PIN mới"));
        frame.validate();
        var pinEntry = field(details, "pinEntry", SixDigitEntry.class);
        JButton back = field(pinEntry, "backButton", JButton.class);
        JButton keyboardButton = field(pinEntry, "keyboardButton", JButton.class);
        assertEquals("", back.getText());
        assertEquals("Quay lại", back.getAccessibleContext().getAccessibleName());
        assertEquals(keyboardButton.getSize(), back.getSize());
        assertEquals(new java.awt.Dimension(64, 64), back.getSize());
        assertEquals(32, back.getIcon().getIconWidth());
        assertEquals(32, back.getIcon().getIconHeight());
        captureFrame(frame, "admin-reset-pin-back.png");
        enterCardPin(details, "654321");
        button(details, "Tiếp tục").doClick(0);
        assertTrue(showingLabel(details, "Xác nhận mã PIN"));
        back.doClick(0);
        assertTrue(showingLabel(details, "Nhập mã PIN mới"));
        for (JPasswordField digit : field(pinEntry, "digits", JPasswordField[].class))
          assertEquals(0, digit.getPassword().length);
        enterCardPin(details, "654321");
        button(details, "Tiếp tục").doClick(0);
        enterCardPin(details, "654321");
        button(details, "Hoàn tất").doClick(0);
      });
      answerDialog(frame, "Đặt lại PIN thành công!", JOptionPane.OK_OPTION);
      assertEquals(1, resets.get());
      SwingUtilities.invokeAndWait(() -> button(details, "Quay lại danh sách").doClick(0));
      awaitIdle(frame);
      SwingUtilities.invokeAndWait(() -> {
        JTable table = field(frame.admin, "table", JTable.class);
        frame.validate();
        assertTrue(table.getWidth() > 1000);
      });
      doubleClickTableRow(frame, 0);
      awaitUi(() -> details.isShowing() && !frame.isBusy());
      awaitUi(() -> {
        CardTransactionsView history = field(details, "transactions", CardTransactionsView.class);
        return history != null && field(history, "pageData", Api.CardTransactionListPage.class) != null;
      });
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("Sinh Viên Khác", form.name.getText());
        assertConnection(details, false);
        JButton back = button(details, "Quay lại danh sách");
        assertEquals("", back.getText());
        assertEquals(24, back.getIcon().getIconWidth());
        assertEquals(24, back.getIcon().getIconHeight());
        assertFalse(back.isBorderPainted());
        assertEquals(new java.awt.Dimension(32, 32), back.getSize());
        JLabel title = label(details, "Thông tin thẻ");
        JButton cardIndicator = field(details, "connectedCard", JButton.class);
        JButton edit = button(details, "Chỉnh sửa");
        JButton reset = button(details, "Đặt lại PIN");
        int titleCenter = SwingUtilities.convertPoint(title, 0, title.getHeight() / 2, details).y;
        for (JButton control : new JButton[] {back, cardIndicator, edit, reset}) {
          assertEquals(titleCenter, SwingUtilities.convertPoint(control, 0, control.getHeight() / 2, details).y);
        }
        assertFalse(showingLabel(details, "Số dư :"));
        assertTrue(components(details).noneMatch(c -> c instanceof JButton b && "Nạp tiền".equals(b.getText())));
        CardTransactionsView history = field(details, "transactions", CardTransactionsView.class);
        assertTrue(showingLabel(history, "Lịch sử giao dịch"));
        JTable historyTable = field(history, "table", JTable.class);
        assertEquals("OTHER-0", historyTable.getValueAt(0, 2));
        assertEquals("Hiển thị 1–4 trên tổng số 5 kết quả", field(history, "resultCount", JLabel.class).getText());
        button(history, ">").doClick(0);
      });
      awaitUi(() -> {
        CardTransactionsView history = field(details, "transactions", CardTransactionsView.class);
        Api.CardTransactionListPage data = field(history, "pageData", Api.CardTransactionListPage.class);
        return data != null && data.page() == 1;
      });
      SwingUtilities.invokeAndWait(() -> {
        CardTransactionsView history = field(details, "transactions", CardTransactionsView.class);
        assertEquals("OTHER-4", field(history, "table", JTable.class).getValueAt(0, 2));
        assertEquals("Hiển thị 5 trên tổng số 5 kết quả", field(history, "resultCount", JLabel.class).getText());
        assertConnection(details, false);
        assertFalse(button(details, "Chỉnh sửa").isEnabled());
        assertFalse(button(details, "Đặt lại PIN").isEnabled());
        captureFrame(frame, "admin-card-disconnected.png");
      });
      reader.set(otherConnected);
      pollUntil(frame, () -> frame.status().cardId().equals(other.cardId()));
      SwingUtilities.invokeAndWait(() -> {
        assertConnection(details, true);
        assertTrue(button(details, "Chỉnh sửa").isEnabled());
        assertTrue(button(details, "Đặt lại PIN").isEnabled());
      });
      reader.set(absent);
      pollUntil(frame, () -> !frame.status().present());
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(details.isShowing());
        assertConnection(details, false);
        assertFalse(button(details, "Chỉnh sửa").isEnabled());
        assertEquals("OTHER-4", field(field(details, "transactions", CardTransactionsView.class),
            "table", JTable.class).getValueAt(0, 2));
      });
      reader.set(initialized);
      pollUntil(frame, () -> frame.status().cardId().equals(cardId));
      SwingUtilities.invokeAndWait(() -> {
        assertConnection(details, false);
        button(details, "Quay lại danh sách").doClick(0);
      });
      awaitIdle(frame);
      assertNull(field(details, "transactions", CardTransactionsView.class));
      SwingUtilities.invokeAndWait(() ->
          field(frame.admin, "table", JTable.class).getModel().setValueAt("BLOCKED", 1, 7));
      doubleClickTableRow(frame, 1);
      awaitUi(() -> details.isShowing() && !frame.isBusy());
      awaitUi(() -> {
        CardTransactionsView history = field(details, "transactions", CardTransactionsView.class);
        return history != null && field(history, "pageData", Api.CardTransactionListPage.class) != null;
      });
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("Sinh Viên Đã Sửa", form.name.getText());
        assertEquals("CARD-0", field(field(details, "transactions", CardTransactionsView.class),
            "table", JTable.class).getValueAt(0, 2));
        assertConnection(details, true);
        assertFalse(button(details, "Chỉnh sửa").isEnabled());
        assertFalse(button(details, "Đặt lại PIN").isEnabled());
      });
      pollUntil(frame, () -> button(details, "Chỉnh sửa").isEnabled());
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(button(details, "Chỉnh sửa").isEnabled());
        assertTrue(button(details, "Đặt lại PIN").isEnabled());
        captureFrame(frame, "admin-card-connected.png");
      });
      backendOffline.set(true);
      pollUntil(frame, () -> !button(details, "Chỉnh sửa").isEnabled());
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(details.isShowing());
        assertConnection(details, false);
        assertTrue(frame.admin.logged());
      });
      backendOffline.set(false);
      pollUntil(frame, () -> button(details, "Chỉnh sửa").isEnabled());
      SwingUtilities.invokeAndWait(() -> {
        assertConnection(details, true);
        button(details, "Chỉnh sửa").doClick(0);
        form.name.setText("Bản nháp chưa lưu");
      });
      SwingUtilities.invokeLater(() -> button(frame, "Ngắt kết nối").doClick(0));
      awaitUi(() -> !frame.status().present() && !frame.isBusy());
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(details.isShowing());
        assertConnection(details, false);
        assertEquals("Bản nháp chưa lưu", form.name.getText());
        assertFalse(button(details, "Lưu").isEnabled());
      });
      SwingUtilities.invokeLater(() -> button(frame, "Kết nối").doClick(0));
      awaitUi(() -> frame.status().cardId().equals(cardId) && !frame.isBusy());
      SwingUtilities.invokeAndWait(() -> {
        assertConnection(details, true);
        assertTrue(button(details, "Lưu").isEnabled());
      });
      clickToOpenDialog(details, "Huỷ bỏ");
      answerDialog(frame, "Huỷ bỏ các thay đổi chưa lưu?", "Huỷ bỏ");
      SwingUtilities.invokeAndWait(() -> {
        button(details, "Chỉnh sửa").doClick(0);
        form.name.setText("Bản nháp đang đồng bộ");
      });
      syncPhase.set("CONFLICT");
      pollUntil(frame, () -> !button(details, "Lưu").isEnabled());
      SwingUtilities.invokeAndWait(() -> {
        assertConnection(details, true);
        assertEquals("Thẻ đang chờ đồng bộ. Vui lòng đồng bộ trước khi lưu.",
            field(details, "feedback", JLabel.class).getText());
        assertEquals("Bản nháp đang đồng bộ", form.name.getText());
      });
      syncPhase.set("SYNCED");
      pollUntil(frame, () -> button(details, "Lưu").isEnabled());
      clickToOpenDialog(details, "Huỷ bỏ");
      answerDialog(frame, "Huỷ bỏ các thay đổi chưa lưu?", "Huỷ bỏ");
      SwingUtilities.invokeAndWait(() -> {
        button(details, "Chỉnh sửa").doClick(0);
        form.name.setText("");
        button(details, "Lưu").doClick(0);
      });
      String validation = field(details, "feedback", JLabel.class).getText();
      assertTrue(validation.contains("Họ tên"));
      pollUntil(frame, () -> frame.status().cardId().equals(cardId));
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(validation, field(details, "feedback", JLabel.class).getText());
        form.name.setText("Sinh Viên Đã Sửa");
        button(details, "Lưu").doClick(0);
      });
      answerDialog(frame, "Thay đổi thông tin thẻ thành công!", JOptionPane.OK_OPTION);
      assertEquals(2, updates.get());
      SwingUtilities.invokeAndWait(() -> {
        button(details, "Đặt lại PIN").doClick(0);
        enterCardPin(details, "123456");
      });
      reader.set(absent);
      pollUntil(frame, () -> !frame.status().present());
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(showingLabel(details, "Thông tin thẻ"));
        assertConnection(details, false);
        var entry = field(details, "pinEntry", SixDigitEntry.class);
        for (JPasswordField digit : field(entry, "digits", JPasswordField[].class))
          assertEquals(0, digit.getPassword().length);
      });
      reader.set(initialized);
      pollUntil(frame, () -> frame.status().cardId().equals(cardId));
      SwingUtilities.invokeAndWait(() -> {
        button(details, "Đặt lại PIN").doClick(0);
        enterCardPin(details, "654321");
        button(details, "Tiếp tục").doClick(0);
        enterCardPin(details, "654321");
        button(details, "Hoàn tất").doClick(0);
      });
      answerDialog(frame, "Đặt lại PIN thành công!", JOptionPane.OK_OPTION);
      assertEquals(2, resets.get());
      SwingUtilities.invokeAndWait(() -> button(details, "Quay lại danh sách").doClick(0));
      awaitIdle(frame);

      rightClickCell(frame, 0, 0);
      SwingUtilities.invokeAndWait(() ->
          assertFalse(field(frame.admin, "statusMenu", JPopupMenu.class).isVisible()));
      showStatusMenu(frame, 0);
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(0, field(frame.admin, "table", JTable.class).getSelectedRow());
        assertTrue(field(frame.admin, "block", JMenuItem.class).isEnabled());
        assertFalse(field(frame.admin, "restore", JMenuItem.class).isEnabled());
        assertFalse(field(frame.admin, "delete", JMenuItem.class).isEnabled());
        JPopupMenu menu = field(frame.admin, "statusMenu", JPopupMenu.class);
        assertEquals("Mở khoá thẻ", field(frame.admin, "restore", JMenuItem.class).getText());
        assertEquals("Xoá thẻ", field(frame.admin, "delete", JMenuItem.class).getText());
        JTable statusTable = field(frame.admin, "table", JTable.class);
        java.awt.Point origin = menu.getLocationOnScreen();
        SwingUtilities.convertPointFromScreen(origin, statusTable);
        java.awt.Rectangle visible = statusTable.getVisibleRect();
        assertTrue(origin.x >= visible.x && origin.x + menu.getWidth() <= visible.x + visible.width);
        assertTrue(origin.y >= visible.y && origin.y + menu.getHeight() <= visible.y + visible.height);
        captureFrame(frame, "admin-card-status-menu.png");
        JTable table = field(frame.admin, "table", JTable.class);
        table.setRowSelectionInterval(1, 1);
        assertFalse(field(frame.admin, "statusMenu", JPopupMenu.class).isVisible());
        table.setRowSelectionInterval(0, 0);
        assertEquals("cardStatusMenu", table.getInputMap().get(
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F10, java.awt.event.InputEvent.SHIFT_DOWN_MASK)));
        table.getActionMap().get("cardStatusMenu").actionPerformed(
            new java.awt.event.ActionEvent(table, java.awt.event.ActionEvent.ACTION_PERFORMED, ""));
        assertTrue(field(frame.admin, "statusMenu", JPopupMenu.class).isVisible());
      });
      chooseStatusAction(frame, "block");
      answerDialog(frame, "Tạm khóa thẻ " + other.cardId() + "?\nCác thao tác mới sẽ bị chặn ngay.",
          JOptionPane.YES_OPTION);
      answerDialog(frame, "Đã tạm khóa thẻ.", JOptionPane.OK_OPTION);
      awaitUi(() -> "BLOCKED".equals(field(frame.admin, "table", JTable.class).getValueAt(0, 7)));
      showStatusMenu(frame, 0);
      SwingUtilities.invokeAndWait(() -> {
        assertFalse(field(frame.admin, "block", JMenuItem.class).isEnabled());
        assertTrue(field(frame.admin, "restore", JMenuItem.class).isEnabled());
        assertTrue(field(frame.admin, "delete", JMenuItem.class).isEnabled());
      });
      chooseStatusAction(frame, "restore");
      answerDialog(frame, "Vui lòng kết nối đúng thẻ TTM000002 đã chọn trong bảng.",
          JOptionPane.OK_OPTION);
      blockFixtureCard(frame, 1, cardId);
      SwingUtilities.invokeAndWait(() -> {
        JTable table = field(frame.admin, "table", JTable.class);
        assertEquals("BLOCKED", table.getValueAt(1, 7));
        Component badge = table.getCellRenderer(1, 7)
            .getTableCellRendererComponent(table, "BLOCKED", false, false, 1, 7);
        assertTrue(components(badge).anyMatch(c -> c instanceof JLabel l && "Tạm khoá".equals(l.getText())));
      });
      doubleClickTableRow(frame, 1);
      awaitUi(() -> details.isShowing() && !frame.isBusy());
      SwingUtilities.invokeAndWait(() -> {
        assertConnection(details, true);
        assertFalse(button(details, "Chỉnh sửa").isEnabled());
        assertFalse(button(details, "Đặt lại PIN").isEnabled());
        button(details, "Quay lại danh sách").doClick(0);
      });
      awaitIdle(frame);
      showStatusMenu(frame, 1);
      chooseStatusAction(frame, "restore");
      answerDialog(frame, "Khôi phục thẻ " + cardId + "?", JOptionPane.OK_OPTION);
      answerDialog(frame, "Đã khôi phục thẻ.", JOptionPane.OK_OPTION);
      awaitUi(() -> frame.status().lifecycleStatus().equals("ACTIVE"));
      SwingUtilities.invokeAndWait(() -> {
        JTable table = field(frame.admin, "table", JTable.class);
        assertEquals("ACTIVE", table.getValueAt(1, 7));
        Component badge = table.getCellRenderer(1, 7)
            .getTableCellRendererComponent(table, "ACTIVE", false, false, 1, 7);
        assertTrue(components(badge).anyMatch(c -> c instanceof JLabel l && "Hoạt động".equals(l.getText())));
      });
      blockFixtureCard(frame, 1, cardId);
      showStatusMenu(frame, 1);
      chooseStatusAction(frame, "delete");
      answerDialog(frame, "Thẻ vẫn còn số dư! Xác nhận xoá?\n" + cardId, JOptionPane.YES_OPTION);
      answerDialog(frame, "Thẻ vẫn còn 1 giao dịch chưa đồng bộ! Tiếp tục xoá?\n"
          + "Chứng từ được giữ riêng để đối chiếu/hoàn tiền sau này.", JOptionPane.YES_OPTION);
      answerDialog(frame, "Xóa cứng đăng ký thẻ " + cardId
          + "?\nKhông thể khôi phục thẻ. Chứng từ nạp chờ được giữ để đối chiếu.", "Xóa cứng");
      answerDialog(frame, "Đã xóa cứng thẻ.", JOptionPane.OK_OPTION);
      awaitUi(() -> !frame.status().present());
      SwingUtilities.invokeAndWait(() -> {
        assertFalse(button(frame.admin, "------------").isEnabled());
        assertEquals(1, field(frame.admin, "pageData", Api.CardListPage.class).items().size());
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  private static void blockFixtureCard(CanteenFrame frame, int row, String cardId) throws Exception {
    showStatusMenu(frame, row);
    SwingUtilities.invokeAndWait(() -> assertTrue(field(frame.admin, "block", JMenuItem.class).isEnabled()));
    chooseStatusAction(frame, "block");
    answerDialog(frame, "Tạm khóa thẻ " + cardId + "?\nCác thao tác mới sẽ bị chặn ngay.",
        JOptionPane.YES_OPTION);
    answerDialog(frame, "Đã tạm khóa thẻ.", JOptionPane.OK_OPTION);
    awaitUi(() -> frame.status().lifecycleStatus().equals("BLOCKED"));
  }

  private static void doubleClickTableRow(CanteenFrame frame, int row) throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      JTable table = field(frame.admin, "table", JTable.class);
      java.awt.Rectangle cell = table.getCellRect(row, 0, true);
      table.dispatchEvent(new java.awt.event.MouseEvent(table, java.awt.event.MouseEvent.MOUSE_CLICKED,
          System.currentTimeMillis(), 0, cell.x + 8, cell.y + 8, 2, false,
          java.awt.event.MouseEvent.BUTTON1));
    });
  }

  private static void rightClickCell(CanteenFrame frame, int row, int column) throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      JTable table = field(frame.admin, "table", JTable.class);
      java.awt.Rectangle cell = table.getCellRect(row, column, true);
      table.dispatchEvent(new java.awt.event.MouseEvent(table, java.awt.event.MouseEvent.MOUSE_RELEASED,
          System.currentTimeMillis(), java.awt.event.InputEvent.BUTTON3_DOWN_MASK,
          cell.x + cell.width / 2, cell.y + cell.height / 2, 1, true,
          java.awt.event.MouseEvent.BUTTON3));
    });
  }

  private static void showStatusMenu(CanteenFrame frame, int row) throws Exception {
    rightClickCell(frame, row, 7);
    SwingUtilities.invokeAndWait(() ->
        assertTrue(field(frame.admin, "statusMenu", JPopupMenu.class).isVisible()));
  }

  private static void chooseStatusAction(CanteenFrame frame, String fieldName) throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      JMenuItem item = field(frame.admin, fieldName, JMenuItem.class);
      assertTrue(item.isEnabled());
      field(frame.admin, "statusMenu", JPopupMenu.class).setVisible(false);
      SwingUtilities.invokeLater(() -> item.doClick(0));
    });
  }

  private static void pollUntil(CanteenFrame frame, BooleanSupplier condition) throws Exception {
    awaitUi(() -> !field(frame, "polling", Boolean.class) && !frame.isBusy());
    SwingUtilities.invokeAndWait(frame::poll);
    awaitUi(() -> !field(frame, "polling", Boolean.class) && condition.getAsBoolean());
  }

  private static void awaitPage(CanteenFrame frame, int index) throws Exception {
    awaitUi(() -> {
      Api.CardListPage data = field(frame.admin, "pageData", Api.CardListPage.class);
      return !frame.isBusy() && data != null && data.page() == index;
    });
  }

  private static void assertAdminListLayout(CanteenFrame frame) {
    var title = label(frame.admin, "Quản trị hệ thống");
    var heading = title.getParent();
    var dashboard = (JComponent) heading.getParent();
    var query = field(frame.admin, "query", JTextField.class);
    var filter = field(frame.admin, "classFilter", JTextField.class);
    var table = field(frame.admin, "table", JTable.class);
    var scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
    var pager = field(frame.admin, "pagination", PaginationBar.class);
    var headingBounds = SwingUtilities.convertRectangle(heading.getParent(), heading.getBounds(), dashboard);
    var searchBounds = SwingUtilities.convertRectangle(query.getParent(), query.getBounds(), dashboard);
    var filterBounds = SwingUtilities.convertRectangle(filter.getParent(), filter.getBounds(), dashboard);
    var tableBounds = SwingUtilities.convertRectangle(scroll.getParent(), scroll.getBounds(), dashboard);
    int firstGap = filterBounds.y - searchBounds.y - searchBounds.height;
    int secondGap = tableBounds.y - filterBounds.y - filterBounds.height;
    int pagerBottom = SwingUtilities.convertPoint(pager, 0, pager.getHeight(), dashboard).y;
    assertEquals(16, searchBounds.y - headingBounds.y - headingBounds.height);
    assertTrue(firstGap >= 16, "The rows need at least 16 px of separation");
    assertTrue(Math.abs(firstGap - secondGap) <= 1, "The row gaps should be equal");
    assertTrue(Math.abs(dashboard.getHeight() - dashboard.getInsets().bottom - pagerBottom) <= 1,
        "Pagination should align with the dashboard's bottom content margin");
  }

  private static boolean fixedAdminTable(CanteenFrame frame) {
    JTable table = field(frame.admin, "table", JTable.class);
    JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
    Rectangle lastRow = table.getCellRect(9, 0, true);
    return scroll.getHeight() == 452 && scroll.getViewport().getExtentSize().height == 409
        && table.getRowHeight() == 41 && table.getRowHeight(9) == 40
        && lastRow.y + lastRow.height == scroll.getViewport().getExtentSize().height;
  }

  private static void assertPagination(CanteenFrame frame, String expected) {
    PaginationBar bar = field(frame.admin, "pagination", PaginationBar.class);
    String labels = String.join(" ", Arrays.stream(bar.getComponents())
        .filter(Component::isVisible).map(JButton.class::cast).map(ScreenTransitionTest::buttonLabel).toList());
    assertEquals(expected, labels);
  }

  private static void assertCatalogColumns(JTable table) {
    var columns = table.getColumnModel();
    assertEquals(80, columns.getColumn(0).getWidth());
    assertEquals(128, columns.getColumn(4).getMinWidth());
    assertEquals(128, columns.getColumn(4).getWidth());
    int first = columns.getColumn(1).getWidth();
    int second = columns.getColumn(2).getWidth();
    int third = columns.getColumn(3).getWidth();
    assertTrue(Math.max(first, Math.max(second, third)) - Math.min(first, Math.min(second, third)) <= 1);
    assertEquals(table.getWidth() - 80 - 128, first + second + third);
  }

  private static Rectangle boundsIn(Component component, Component ancestor) {
    return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), ancestor);
  }

  private static Map<String, Rectangle> profileBounds(AdminCardView details, ProfileForm form) {
    CardTransactionsView history = field(details, "transactions", CardTransactionsView.class);
    return Map.ofEntries(
        Map.entry("avatar", boundsIn(field(form, "avatar", JComponent.class), details)),
        Map.entry("cardId", boundsIn(form.cardId, details)),
        Map.entry("code", boundsIn(form.code, details)),
        Map.entry("name", boundsIn(form.name, details)),
        Map.entry("group", boundsIn(form.group, details)),
        Map.entry("year", boundsIn(field(form, "intakeDisplay", JTextField.class), details)),
        Map.entry("citizen", boundsIn(form.citizen, details)),
        Map.entry("birth", boundsIn(form.birth, details)),
        Map.entry("phone", boundsIn(form.phone, details)),
        Map.entry("email", boundsIn(form.email, details)),
        Map.entry("history", boundsIn(field(history, "table", JTable.class), details)),
        Map.entry("paging", boundsIn(field(history, "pagination", PaginationBar.class), details)));
  }

  private static BufferedImage painted(Component component) {
    BufferedImage result = new BufferedImage(component.getWidth(), component.getHeight(),
        BufferedImage.TYPE_INT_ARGB);
    var graphics = result.createGraphics();
    try {
      component.paint(graphics);
    } finally {
      graphics.dispose();
    }
    return result;
  }

  private static void assertNeutralTableSelection(JTable table, int row) {
    table.clearSelection();
    for (int column = 0; column < table.getColumnCount(); column++) {
      BufferedImage normalCell = paintedTableCell(table, row, column, false, false);
      BufferedImage focusedCell = paintedTableCell(table, row, column, true, true);
      assertArrayEquals(normalCell.getRGB(0, 0, normalCell.getWidth(), normalCell.getHeight(),
              null, 0, normalCell.getWidth()),
          focusedCell.getRGB(0, 0, focusedCell.getWidth(), focusedCell.getHeight(),
              null, 0, focusedCell.getWidth()),
          "Selection and keyboard focus must not change cell " + column);
    }
    BufferedImage before = painted(table);
    table.changeSelection(row, 0, false, false);
    assertTrue(table.isRowSelected(row), "The table must still select rows for actions");
    BufferedImage selected = painted(table);
    assertArrayEquals(before.getRGB(0, 0, before.getWidth(), before.getHeight(), null, 0, before.getWidth()),
        selected.getRGB(0, 0, selected.getWidth(), selected.getHeight(), null, 0, selected.getWidth()),
        "Selecting a row must not change its appearance");
    Rectangle cell = table.getCellRect(row, 0, true);
    table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(),
        0, cell.x + cell.width / 2, cell.y + cell.height / 2, 0, false));
    BufferedImage hovered = painted(table);
    assertArrayEquals(selected.getRGB(0, 0, selected.getWidth(), selected.getHeight(), null, 0, selected.getWidth()),
        hovered.getRGB(0, 0, hovered.getWidth(), hovered.getHeight(), null, 0, hovered.getWidth()),
        "Hovering over a row must not change its appearance");
    table.clearSelection();
  }

  private static BufferedImage paintedTableCell(JTable table, int row, int column,
      boolean selected, boolean focused) {
    Component cell = table.getCellRenderer(row, column).getTableCellRendererComponent(
        table, table.getValueAt(row, column), selected, focused, row, column);
    Rectangle bounds = table.getCellRect(row, column, true);
    cell.setSize(bounds.width, bounds.height);
    if (cell instanceof Container container) container.doLayout();
    return painted(cell);
  }

  private static JComboBox<?> combo(Component root, String accessibleName) {
    return components(root).filter(c -> c instanceof JComboBox<?> box
        && accessibleName.equals(box.getAccessibleContext().getAccessibleName()))
        .map(c -> (JComboBox<?>) c).findFirst().orElseThrow();
  }

  private static void assertConnection(AdminCardView details, boolean connected) {
    JLabel indicator = field(details, "connectionIndicator", JLabel.class);
    assertEquals(connected ? "Đã kết nối đúng thẻ" : "Chưa kết nối đúng thẻ", indicator.getToolTipText());
    assertEquals(connected ? new java.awt.Color(0x208A46) : new java.awt.Color(0xA0A0A0),
        indicator.getForeground());
    JLabel cardId = field(details, "connectedCardId", JLabel.class);
    ProfileForm form = field(details, "form", ProfileForm.class);
    assertEquals(connected ? form.cardId.getText() : "------------", cardId.getText());
    assertEquals(new java.awt.Dimension(128, 32), field(details, "connectedCard", JButton.class).getSize());
  }

  private static void captureFrame(CanteenFrame frame, String name) {
    assertDoesNotThrow(() -> {
      Path capture = Path.of("target/ui-check", name);
      Files.createDirectories(capture.getParent());
      var image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
      var graphics = image.createGraphics();
      try {
        frame.paint(graphics);
        JPopupMenu menu = field(frame.admin, "statusMenu", JPopupMenu.class);
        if (menu.isShowing()) {
          java.awt.Point popup = menu.getLocationOnScreen();
          java.awt.Point origin = frame.getLocationOnScreen();
          java.awt.Graphics popupGraphics = graphics.create(
              popup.x - origin.x, popup.y - origin.y, menu.getWidth(), menu.getHeight());
          try {
            menu.paint(popupGraphics);
          } finally {
            popupGraphics.dispose();
          }
        }
      } finally {
        graphics.dispose();
      }
      ImageIO.write(image, "png", capture.toFile());
    });
  }

  private static Profile fixtureProfile(String id, String name, String photo) {
    return new Profile(id, "CT230101", name, "012345678901", "03/02/2005", "CT23A",
        "0912345678", "fixture@example.invalid", 2023, photo, 1);
  }

  private static void enterCardPin(AdminCardView details, String pin) {
    var entry = field(details, "pinEntry", SixDigitEntry.class);
    enterPin(entry, pin);
  }

  private static void enterPin(SixDigitEntry entry, String pin) {
    JPasswordField[] digits = field(entry, "digits", JPasswordField[].class);
    for (int i = 0; i < digits.length; i++) digits[i].setText(pin.substring(i, i + 1));
  }

  private static void clickToOpenDialog(Component root, String caption) {
    SwingUtilities.invokeLater(() -> button(root, caption).doClick(0));
  }

  private static void answerDialog(CanteenFrame frame, String message, Object value) throws Exception {
    awaitUi(() -> {
      var pane = Arrays.stream(frame.getOwnedWindows()).filter(Component::isShowing)
          .flatMap(ScreenTransitionTest::components)
          .filter(JOptionPane.class::isInstance).map(JOptionPane.class::cast)
          .filter(option -> message.equals(option.getMessage())).findFirst().orElse(null);
      if (pane == null) return false;
      pane.setValue(value);
      return true;
    });
  }

  private static String fixtureJson(ObjectMapper json, Object value) {
    return assertDoesNotThrow(() -> json.writeValueAsString(value));
  }

  private static JsonNode requestJson(ObjectMapper json, HttpRequest request) {
    var body = new CompletableFuture<byte[]>();
    request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
      private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      @Override public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }
      @Override public void onNext(ByteBuffer chunk) {
        byte[] data = new byte[chunk.remaining()];
        chunk.get(data);
        bytes.writeBytes(data);
      }
      @Override public void onError(Throwable error) { body.completeExceptionally(error); }
      @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    });
    return assertDoesNotThrow(() -> json.readTree(body.get(2, TimeUnit.SECONDS)));
  }

  private static Api.Invoice invoice(String status, String reason, long revision, long now) {
    return new Api.Invoice(7, "Fixture invoice", "AT010203", "Fixture", "fixture", 30_000,
        status, List.of(), now, now + 300_000, "", reason, revision, "fixture-station",
        "TTAT01020300000007", "", "");
  }

  private static String replyAfter(CountDownLatch started, CountDownLatch release, String reply) {
    started.countDown();
    await(release);
    return reply;
  }

  private CanteenFrame openReconciliationAdmin(ApiClient api) throws Exception {
    var window = new AtomicReference<CanteenFrame>();
    SwingUtilities.invokeAndWait(() -> {
      TTM_CuoiKy.configureLookAndFeel();
      CanteenFrame frame = new CanteenFrame(api, false, false);
      window.set(frame);
      frame.setSize(frame.getMinimumSize());
      field(frame, "tabs", JTabbedPane.class).setSelectedIndex(1);
      frame.setVisible(true);
      field(frame.admin, "username", JTextField.class).setText("fixture");
      field(frame.admin, "password", JPasswordField.class).setText("fixture-password");
      button(frame.admin, "Đăng nhập").doClick(0);
    });
    try {
      awaitUi(() -> window.get().admin.logged() && showingLabel(window.get(), "Quản trị hệ thống")
          && !window.get().isBusy());
      return window.get();
    } catch (Exception | AssertionError failure) {
      SwingUtilities.invokeAndWait(window.get()::dispose);
      throw failure;
    }
  }

  private static JDialog openReconciliation(CanteenFrame frame) throws Exception {
    SwingUtilities.invokeLater(() -> button(frame.admin, "Giao dịch").doClick(0));
    var result = new AtomicReference<JDialog>();
    awaitUi(() -> {
      result.set(Arrays.stream(frame.getOwnedWindows())
          .filter(JDialog.class::isInstance).map(JDialog.class::cast)
          .filter(dialog -> dialog.isShowing() && "Giao dịch".equals(dialog.getTitle()))
          .findFirst().orElse(null));
      return result.get() != null;
    });
    return result.get();
  }

  private static JTable namedTable(Component root, String name) {
    return components(root).filter(c -> c instanceof JTable table
        && name.equals(table.getAccessibleContext().getAccessibleName()))
        .map(JTable.class::cast).findFirst().orElseThrow();
  }

  private static String queryParameter(String rawQuery, String name) {
    if (rawQuery == null) return "";
    return Arrays.stream(rawQuery.split("&"))
        .map(pair -> pair.split("=", 2))
        .filter(parts -> parts[0].equals(name))
        .map(parts -> parts.length == 1 ? "" : java.net.URLDecoder.decode(
            parts[1], java.nio.charset.StandardCharsets.UTF_8))
        .findFirst().orElse("");
  }

  private static void captureReconciliation(JDialog dialog, String name) {
    assertDoesNotThrow(() -> {
      Path capture = Path.of("target/ui-check", name);
      Files.createDirectories(capture.getParent());
      ImageIO.write(painted(dialog), "png", capture.toFile());
    });
  }

  private ApiClient api(Function<HttpRequest, String> reply) throws Exception {
    Path config = Files.writeString(temp.resolve("client.properties"),
        "api.url=http://127.0.0.1:18080\ndevice.key=fixture\n");
    String previous = System.setProperty("ttm.client", config.toString());
    try {
      ApiClient client = new ApiClient();
      var http = ApiClient.class.getDeclaredField("http");
      http.setAccessible(true);
      http.set(client, new FixtureHttp(reply));
      return client;
    } finally {
      if (previous == null) System.clearProperty("ttm.client");
      else System.setProperty("ttm.client", previous);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for backend fixture");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static void awaitIdle(CanteenFrame frame) throws Exception {
    awaitUi(() -> !frame.isBusy());
  }

  private static void awaitUi(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    var ready = new AtomicBoolean();
    do {
      SwingUtilities.invokeAndWait(() -> ready.set(condition.getAsBoolean()));
      if (ready.get()) return;
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    fail("Timed out waiting for UI");
  }

  private static Stream<Component> components(Component root) {
    return root instanceof Container container
        ? Stream.concat(Stream.of(root), Arrays.stream(container.getComponents())
            .flatMap(ScreenTransitionTest::components))
        : Stream.of(root);
  }

  private static boolean showingLabel(Component root, String text) {
    return label(root, text) != null;
  }

  private static String toastText(Component root) {
    return field(Toast.forComponent(root), "message", JTextPane.class).getText();
  }

  private static JLabel label(Component root, String text) {
    return components(root).filter(c -> c instanceof JLabel label
        && label.isShowing() && text.equals(label.getText()))
        .map(JLabel.class::cast).findFirst().orElse(null);
  }

  private static JButton button(Component root, String text) {
    return components(root).filter(c -> c instanceof JButton b && b.isShowing() && text.equals(buttonLabel(b)))
        .map(JButton.class::cast).findFirst().orElseThrow();
  }

  private static JButton sellerDish(CheckoutView seller, String name) {
    return components(field(seller, "menu", JPanel.class))
        .filter(c -> c instanceof JButton b && b.getText() != null && b.getText().contains(name))
        .map(JButton.class::cast).findFirst().orElseThrow();
  }

  private static void clickCartCell(JTable table, int row, int column, int offsetX) {
    Rectangle cell = table.getCellRect(row, column, true);
    int x = cell.x + offsetX;
    int y = cell.y + cell.height / 2;
    table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_PRESSED,
        System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
    table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_RELEASED,
        System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1));
  }

  private static void assertNoMatchingDishesCentered(CanteenFrame frame) {
    frame.validate();
    JPanel menu = field(frame.seller, "menu", JPanel.class);
    JLabel empty = label(menu, "Không tìm thấy món ăn phù hợp.");
    assertNotNull(empty);
    assertEquals(SwingConstants.CENTER, empty.getHorizontalAlignment());
    assertEquals(SwingConstants.CENTER, empty.getVerticalAlignment());
    JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, menu);
    Rectangle bounds = boundsIn(empty, scroll.getViewport());
    assertTrue(Math.abs(2 * bounds.x + bounds.width - scroll.getViewport().getWidth()) <= 1);
    assertTrue(Math.abs(2 * bounds.y + bounds.height - scroll.getViewport().getHeight()) <= 1);
  }

  private static String buttonLabel(JButton button) {
    String name = button.getAccessibleContext().getAccessibleName();
    if ("Trang trước".equals(name)) return "<";
    if ("Trang sau".equals(name)) return ">";
    if ("Quay lại danh sách".equals(name) && button.getText().isEmpty()) return name;
    return button.getText();
  }

  private static <T> T field(Object target, String name, Class<T> type) {
    try {
      var field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return type.cast(field.get(target));
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static void setField(Object target, String name, Object value) {
    try {
      var field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static <T> void invoke(Object target, String name, Class<T> type, T value) {
    try {
      var method = target.getClass().getDeclaredMethod(name, type);
      method.setAccessible(true);
      method.invoke(target, value);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static void invokeNoArgs(Object target, String name) {
    try {
      var method = target.getClass().getDeclaredMethod(name);
      method.setAccessible(true);
      method.invoke(target);
    } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
  }

  private static final class FixtureHttp extends HttpClient {
    private final Function<HttpRequest, String> reply;

    FixtureHttp(Function<HttpRequest, String> reply) { this.reply = reply; }

    @Override @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      return (HttpResponse<T>) new Reply(request, reply.apply(request));
    }

    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> push) { throw new UnsupportedOperationException(); }
    @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
    @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
    @Override public Redirect followRedirects() { return Redirect.NEVER; }
    @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
    @Override public SSLContext sslContext() { throw new UnsupportedOperationException(); }
    @Override public SSLParameters sslParameters() { return new SSLParameters(); }
    @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
    @Override public Version version() { return Version.HTTP_1_1; }
    @Override public Optional<Executor> executor() { return Optional.empty(); }
  }

  private record Reply(HttpRequest request, String body) implements HttpResponse<String> {
    public int statusCode() { return 200; }
    public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
    public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (key, value) -> true); }
    public Optional<SSLSession> sslSession() { return Optional.empty(); }
    public URI uri() { return request.uri(); }
    public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
  }
}
