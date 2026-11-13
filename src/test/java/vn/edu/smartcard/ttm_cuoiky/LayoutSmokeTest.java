package vn.edu.smartcard.ttm_cuoiky;

import static org.junit.jupiter.api.Assertions.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.DefaultCaret;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import vn.edu.smartcard.shared.Api;

class LayoutSmokeTest {
  @Test
  void catalogFilterAndTwoPaymentColumnsWorkAtSupportedSizes() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          CanteenFrame f = null;
          try {
            f = new CanteenFrame(new ApiClient(), true, false);
            CanteenFrame management = new CanteenFrame(new ApiClient(), false, false);
            try {
              assertEquals(new Dimension(1280, 880), f.getMinimumSize());
              assertEquals(management.getMinimumSize(), f.getMinimumSize());
              assertEquals(management.getSize(), f.getSize());
            } finally {
              management.dispose();
            }
            f.setSize(960, 660);
            assertEquals(f.getMinimumSize(), f.getSize());
            f.setVisible(true);
            var itemsField = CheckoutView.class.getDeclaredField("items");
            itemsField.setAccessible(true);
            itemsField.set(
                f.seller,
                new vn.edu.smartcard.shared.Api.MenuItem[] {
                  new vn.edu.smartcard.shared.Api.MenuItem(
                      1, "Cơm gà", "Cơm", 35000, true, 1, 1, false),
                  new vn.edu.smartcard.shared.Api.MenuItem(
                      2, "Nước suối", "Đồ uống", 10000, true, 2, 1, false)
                });
            var categoryField = CheckoutView.class.getDeclaredField("category");
            categoryField.setAccessible(true);
            @SuppressWarnings("unchecked")
            JComboBox<vn.edu.smartcard.shared.Api.MenuCategory> categories =
                (JComboBox<vn.edu.smartcard.shared.Api.MenuCategory>) categoryField.get(f.seller);
            categories.addItem(new vn.edu.smartcard.shared.Api.MenuCategory(0, "Tất cả", 2, 1));
            categories.addItem(new vn.edu.smartcard.shared.Api.MenuCategory(1, "Cơm", 1, 1));
            categories.addItem(new vn.edu.smartcard.shared.Api.MenuCategory(2, "Đồ uống", 1, 1));
            var menuField = CheckoutView.class.getDeclaredField("menu");
            menuField.setAccessible(true);
            assertEquals(2, ((JPanel) menuField.get(f.seller)).getComponentCount());
            categories.setSelectedIndex(1);
            assertEquals(1, ((JPanel) menuField.get(f.seller)).getComponentCount());
            categories.setSelectedIndex(0);
            capture(f, Path.of("target/ui-check/catalog-1280x720.png"));
            var current = CanteenFrame.class.getDeclaredField("current");
            current.setAccessible(true);
            var reader =
                new vn.edu.smartcard.shared.Api.ReaderStatus(
                    true,
                    "fixture",
                    true,
                    "TTM000001",
                    5,
                    "NONE",
                    0,
                    0,
                    "same-connection",
                    "ACTIVE");
            current.set(f, reader);
            f.seller.onCard(reader);
            var accept =
                CheckoutView.class.getDeclaredMethod(
                    "accept", vn.edu.smartcard.shared.Api.Invoice.class);
            accept.setAccessible(true);
            long now = System.currentTimeMillis();
            String qr =
                "data:image/png;base64,"
                    + java.util.Base64.getEncoder()
                        .encodeToString(
                            Files.readAllBytes(Path.of("src/test/resources/fixtures/qr-sample.png")));
            var invoice =
                new vn.edu.smartcard.shared.Api.Invoice(
                    123,
                    "HD00000123",
                    "",
                    "",
                    "",
                    80000,
                    "OPEN",
                    java.util.List.of(
                        new vn.edu.smartcard.shared.Api.InvoiceLine("Cơm gà", 2, 35000, 70000),
                        new vn.edu.smartcard.shared.Api.InvoiceLine("Nước suối", 1, 10000, 10000)),
                    now,
                    now + 300000,
                    qr,
                    "",
                    1,
                    "fixture",
                    "TTAT01020300000123",
                    "",
                    "NONE");
            accept.invoke(f.seller, invoice);
            f.seller.onCard(reader);
            f.seller.cardValidated(reader);
            assertTrue(find(f, "Tiến hành thanh toán").isEnabled());
            var pin = CheckoutView.class.getDeclaredField("pinOpen");
            pin.setAccessible(true);
            assertFalse(pin.getBoolean(f.seller));
            var tabsField = CheckoutView.class.getDeclaredField("tabs");
            tabsField.setAccessible(true);
            var tabs = (JTabbedPane) tabsField.get(f.seller);
            tabs.setSelectedIndex(1);
            capture(f, Path.of("target/ui-check/payment-1280x720.png"));
            var listField = CheckoutView.class.getDeclaredField("customerItems");
            listField.setAccessible(true);
            JPanel list = (JPanel) listField.get(f.seller);
            assertTrue(list.isShowing());
            assertEquals(2, list.getComponentCount());
            assertTrue(list.getVisibleRect().height >= 192,
                "Customer must see both invoice lines at the minimum window size");
            f.setSize(1280, 850);
            capture(f, Path.of("target/ui-check/payment-1280.png"));
            var showPin = CheckoutView.class.getDeclaredMethod("showPin", long.class, String.class);
            showPin.setAccessible(true);
            showPin.invoke(f.seller, invoice.id(), reader.connectionId());
            assertTrue(pin.getBoolean(f.seller));
            accept.invoke(
                f.seller,
                new vn.edu.smartcard.shared.Api.Invoice(
                    invoice.id(),
                    invoice.number(),
                    "",
                    "",
                    "",
                    invoice.total(),
                    "PAID",
                    invoice.items(),
                    now,
                    now + 300000,
                    "",
                    "",
                    2,
                    "fixture",
                    invoice.paymentReference(),
                    "BANK_TRANSFER",
                    "FINISHED"));
            assertFalse(pin.getBoolean(f.seller));
            var qrField = CheckoutView.class.getDeclaredField("qr");
            qrField.setAccessible(true);
            assertNull(((JLabel) qrField.get(f.seller)).getIcon());
            capture(f, Path.of("target/ui-check/payment-bank-paid.png"));
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            if (f != null) f.dispose();
          }
        });
  }

  @Test
  void checkoutHasSeparateQrTabAndExpiryReturnsCustomerToIdle() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            CanteenFrame f = new CanteenFrame(new ApiClient(), true, false);
            f.setSize(f.getMinimumSize());
            f.setVisible(true);
            var field = CheckoutView.class.getDeclaredField("tabs");
            field.setAccessible(true);
            JTabbedPane tabs = (JTabbedPane) field.get(f.seller);
            assertEquals(2, tabs.getTabCount());
            assertEquals("Nhân viên bán hàng", tabs.getTitleAt(0));
            assertEquals("Màn hình QR cho khách", tabs.getTitleAt(1));
            byte[] png = Files.readAllBytes(Path.of("src/test/resources/fixtures/qr-sample.png"));
            String qr =
                "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png);
            long now = System.currentTimeMillis();
            var i =
                new vn.edu.smartcard.shared.Api.Invoice(
                    51,
                    "HD00000051",
                    "AT400101",
                    "Nguyễn Văn An",
                    "TTM000001",
                    35000,
                    "OPEN",
                    java.util.List.of(
                        new vn.edu.smartcard.shared.Api.InvoiceLine("Cơm gà", 1, 35000, 35000)),
                    now,
                    now + 300000,
                    qr,
                    "");
            var accept =
                CheckoutView.class.getDeclaredMethod(
                    "accept", vn.edu.smartcard.shared.Api.Invoice.class);
            accept.setAccessible(true);
            accept.invoke(f.seller, i);
            tabs.setSelectedIndex(1);
            find(f, "Thanh toán bằng chuyển khoản").doClick(0);
            f.validate();
            var pagesField = CheckoutView.class.getDeclaredField("customerPage");
            pagesField.setAccessible(true);
            JPanel pages = (JPanel) pagesField.get(f.seller);
            JPanel bankPage = (JPanel) pages.getComponent(1);
            JButton back = find(bankPage, "Quay lại");
            assertNotNull(back);
            assertEquals(new Insets(16, 16, 16, 16), bankPage.getInsets());
            for (int width : new int[] {1280, 1500}) {
              f.setSize(width, 880);
              f.validate();
              assertEquals(new Dimension(128, 32), back.getSize());
              Rectangle position = SwingUtilities.convertRectangle(back.getParent(), back.getBounds(), bankPage);
              assertEquals(16, position.y);
              assertEquals(bankPage.getWidth() - 16, position.x + position.width);
            }
            f.setSize(f.getMinimumSize());
            f.validate();
            capture(f, Path.of("target/ui-check/qr-1280x720.png"));
            assertFalse(find(f, "Tiến hành thanh toán").isEnabled());
            var qf = CheckoutView.class.getDeclaredField("qr");
            qf.setAccessible(true);
            assertNotNull(((JLabel) qf.get(f.seller)).getIcon());
            accept.invoke(
                f.seller,
                new vn.edu.smartcard.shared.Api.Invoice(
                    i.id(),
                    i.number(),
                    i.studentCode(),
                    i.fullName(),
                    i.cardId(),
                    i.total(),
                    "CANCELLED",
                    i.items(),
                    now + 300001,
                    now + 300000,
                    "",
                    "QR_EXPIRED"));
            assertNull(((JLabel) qf.get(f.seller)).getIcon());
            assertTrue(pages.getComponent(0).isVisible());
            assertFalse(bankPage.isVisible());
            var notice = Toast.class.getDeclaredField("message");
            notice.setAccessible(true);
            assertEquals("Hoá đơn đã hết hạn.",
                ((JTextPane) notice.get(Toast.forComponent(f))).getText());
            var title = CheckoutView.class.getDeclaredField("customerTitle");
            var help = CheckoutView.class.getDeclaredField("customerHelp");
            title.setAccessible(true);
            help.setAccessible(true);
            assertTrue(((JLabel) title.get(f.seller)).getText().isBlank());
            assertTrue(((JLabel) help.get(f.seller)).getText().isBlank());
            assertNull(find(f, "Đơn hàng mới"));
            assertTrue(find(f, "Tạo hóa đơn").isEnabled());
            capture(f, Path.of("target/ui-check/qr-expired-1280x720.png"));
            f.dispose();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test
  void pendingCancellationCannotBeShownAsCancelledOrRevivedByOldReply() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            CanteenFrame f = new CanteenFrame(new ApiClient(), true, false);
            f.setSize(f.getMinimumSize());
            f.setVisible(true);
            var accept =
                CheckoutView.class.getDeclaredMethod(
                    "accept", vn.edu.smartcard.shared.Api.Invoice.class);
            accept.setAccessible(true);
            long now = System.currentTimeMillis();
            var pending =
                new vn.edu.smartcard.shared.Api.Invoice(
                    99,
                    "HD00000099",
                    "AT400101",
                    "Nguyễn Văn An",
                    "TTM000001",
                    35000,
                    "CANCEL_PENDING",
                    java.util.List.of(),
                    now,
                    now + 300000,
                    "",
                    "STATION_CLOSED",
                    3,
                    "fixture-session");
            accept.invoke(f.seller, pending);
            assertNull(find(f, "Đơn hàng mới"));
            assertFalse(find(f, "Hủy hóa đơn").isEnabled());
            assertFalse(find(f, "Tiến hành thanh toán").isEnabled());
            var old =
                new vn.edu.smartcard.shared.Api.Invoice(
                    99,
                    "HD00000099",
                    "AT400101",
                    "Nguyễn Văn An",
                    "TTM000001",
                    35000,
                    "OPEN",
                    java.util.List.of(),
                    now,
                    now + 300000,
                    "",
                    "",
                    1,
                    "fixture-session");
            accept.invoke(f.seller, old);
            var field = CheckoutView.class.getDeclaredField("invoice");
            field.setAccessible(true);
            assertEquals(
                "CANCEL_PENDING",
                ((vn.edu.smartcard.shared.Api.Invoice) field.get(f.seller)).status());
            capture(f, Path.of("target/ui-check/cancel-pending-1280x720.png"));
            var tabs = CheckoutView.class.getDeclaredField("tabs");
            tabs.setAccessible(true);
            ((JTabbedPane) tabs.get(f.seller)).setSelectedIndex(1);
            capture(f, Path.of("target/ui-check/cancel-pending-customer-1280x720.png"));
            f.seller.stationEnded();
            assertFalse(find(f, "Hủy hóa đơn").isEnabled());
            f.dispose();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  @BeforeEach
  void configure() throws Exception {
    Assumptions.assumeFalse(
        GraphicsEnvironment.isHeadless(), "Requires a Windows desktop rendering session");
    Path dir = Path.of("target/ui-check");
    Files.createDirectories(dir);
    Path client = dir.resolve("client.properties");
    Files.writeString(client, "api.url=http://127.0.0.1:18080\ndevice.key=fixture-not-live\n");
    System.setProperty("ttm.client", client.toAbsolutePath().toString());
    com.formdev.flatlaf.FlatLightLaf.setup();
    UIManager.put("defaultFont", new Font("Segoe UI", Font.PLAIN, 14));
  }

  @Test
  void initialWindowsRenderAndRequireCardBeforePin() throws Exception {
    Assumptions.assumeFalse(
        GraphicsEnvironment.isHeadless(), "Requires a Windows desktop rendering session");
    Path dir = Path.of("target/ui-check");
    Files.createDirectories(dir);
    Path client = dir.resolve("client.properties");
    Files.writeString(client, "api.url=http://127.0.0.1:18080\ndevice.key=fixture-not-live\n");
    System.setProperty("ttm.client", client.toAbsolutePath().toString());
    com.formdev.flatlaf.FlatLightLaf.setup();
    UIManager.put("defaultFont", new Font("Segoe UI", Font.PLAIN, 14));
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            Dimension minimum = new Dimension(1280, 880);
            CanteenFrame management = new CanteenFrame(new ApiClient(), false, false);
            assertEquals(minimum, management.getMinimumSize());
            management.setVisible(true);
            var content = management.getContentPane();
            assertEquals(new Insets(20, 20, 20, 20), content.getInsets());
            assertEquals(new Color(245, 245, 245), content.getBackground());
            var layout = (BorderLayout) content.getLayout();
            assertNull(layout.getLayoutComponent(BorderLayout.SOUTH));
            var toolbar = (JPanel) layout.getLayoutComponent(BorderLayout.NORTH);
            var tabs = (JTabbedPane) layout.getLayoutComponent(BorderLayout.CENTER);
            assertEquals(12, tabs.getY() - toolbar.getY() - toolbar.getHeight());
            assertEquals("Sinh viên", tabs.getTitleAt(0));
            assertEquals("Admin", tabs.getTitleAt(1));
            var toolbarLayout = (BorderLayout) toolbar.getLayout();
            var connection = (Box) toolbarLayout.getLayoutComponent(BorderLayout.WEST);
            var search = connection.getComponent(0);
            assertInstanceOf(DeviceSearch.class, search);
            assertEquals(new Dimension(384, 32), search.getSize());
            assertEquals(new Point(20, 20), SwingUtilities.convertPoint(search, 0, 0, content));
            assertNotNull(find(toolbar, "Tạo thẻ mới"));
            assertNotNull(find(toolbar, "Kết nối"));
            assertNotNull(find(toolbar, "Ngắt kết nối"));
            JButton sync = find(toolbar, "Đồng bộ lại");
            assertNotNull(sync);
            assertEquals(
                content.getWidth() - 20,
                SwingUtilities.convertPoint(sync, sync.getWidth(), 0, content).x);
            JButton pin = find(management, "Xác thực");
            assertNotNull(pin);
            assertFalse(pin.isEnabled());
            assertFalse(sync.isEnabled());
            capture(management, dir.resolve("quan-ly-sinh-vien.png"));
            management.setSize(960, 660);
            assertEquals(minimum, management.getSize());
            capture(management, dir.resolve("quan-ly-1280x880.png"));
            management.dispose();
            ProfileForm info = new ProfileForm();
            info.show(
                new vn.edu.smartcard.shared.Profile(
                    "TTM000001",
                    "CT070352",
                    "Trần Văn An",
                    "012345678901",
                    "03/02/2005",
                    "CT7C",
                    "0912345678",
                    "student@example.invalid",
                    2023,
                    "",
                    1));
            info.setEditable(false);
            assertFalse(info.code.isEditable());
            assertFalse(info.citizen.isEditable());
            JButton avatar = assertInstanceOf(JButton.class, profilePortrait(info).getComponent(0));
            assertFalse(avatar.isEnabled());
            info.setEditable(true);
            assertTrue(avatar.isEnabled());
            assertFalse(info.cardId.isEditable());
            assertFalse(info.group.isEditable());
            CanteenFrame checkout = new CanteenFrame(new ApiClient(), true, false);
            Dimension checkoutMinimum = new Dimension(1280, 880);
            assertEquals(checkoutMinimum, checkout.getMinimumSize());
            checkout.setSize(960, 660);
            assertEquals(checkoutMinimum, checkout.getSize());
            checkout.setVisible(true);
            JButton pay = find(checkout, "Tiến hành thanh toán");
            assertNotNull(pay);
            assertFalse(pay.isEnabled());
            capture(checkout, dir.resolve("quay-thanh-toan.png"));
            checkout.dispose();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test
  void deviceSearchFiltersAndScrollsBeforeSelectingADevice() throws Exception {
    Api.DeviceChoice[] choices = new Api.DeviceChoice[13];
    for (int i = 0; i < 12; i++) {
      String id = String.format(Locale.ROOT, "SIM%04d", i + 1);
      choices[i] = new Api.DeviceChoice(id, "Thẻ mô phỏng " + id);
    }
    Api.DeviceChoice reader =
        new Api.DeviceChoice("PCSC:JAVACOS Virtual Contact Reader 0", "Đầu đọc JAVACOS");
    choices[12] = reader;
    var popupField = DeviceSearch.class.getDeclaredField("popup");
    popupField.setAccessible(true);
    AtomicReference<JFrame> owner = new AtomicReference<>();
    AtomicReference<DeviceSearch> input = new AtomicReference<>();
    CountDownLatch focused = new CountDownLatch(1);
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            TTM_CuoiKy.configureLookAndFeel();
            JFrame frame = new JFrame();
            owner.set(frame);
            DeviceSearch search = new DeviceSearch();
            input.set(search);
            search.setChoices(choices, null);
            search.addFocusListener(
                new FocusAdapter() {
                  @Override
                  public void focusGained(FocusEvent e) {
                    focused.countDown();
                  }
                });
            frame.add(search, BorderLayout.NORTH);
            frame.setSize(430, 400);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            search.requestFocusInWindow();
          });
      assertTrue(focused.await(4, TimeUnit.SECONDS), "Search field did not receive focus");
      DeviceSearch search = input.get();
      JPopupMenu popup = (JPopupMenu) popupField.get(search);
      SwingUtilities.invokeAndWait(
          () -> {
            JScrollPane scroll = (JScrollPane) popup.getComponent(0);
            JList<?> list = (JList<?>) scroll.getViewport().getView();
            assertEquals(
                "Tìm kiếm thẻ theo ID", search.getClientProperty("JTextField.placeholderText"));
            assertTrue(search.getText().isEmpty());
            assertTrue(((javax.swing.text.DefaultCaret) search.getCaret()).isActive());
            assertTrue(search.getCaret().getBlinkRate() > 0);
            assertFalse(popup.isVisible());
            assertNull(search.selection());

            search.setText("sIm");
            assertTrue(popup.isVisible());
            assertEquals(12, list.getModel().getSize());
            assertEquals(10, list.getVisibleRowCount());
            assertEquals(
                list.getCellBounds(0, 9).height, scroll.getViewport().getExtentSize().height);
            assertTrue(scroll.getVerticalScrollBar().isVisible());
            assertNull(search.selection());
            search.setCaretPosition(3);
            search.replaceSelection("0001");
            assertTrue(popup.isVisible());
            assertEquals(1, list.getModel().getSize());
            assertEquals(
                list.getCellBounds(0, 0).height, scroll.getViewport().getExtentSize().height);
            assertFalse(scroll.getVerticalScrollBar().isVisible());
            search.select(3, search.getText().length());
            search.replaceSelection("");
            for (int i = 0; i < 11; i++) {
              searchKey(search, KeyEvent.VK_DOWN);
            }
            assertTrue(scroll.getVerticalScrollBar().getValue() > 0);
            searchKey(search, KeyEvent.VK_ENTER);
            assertEquals(choices[11], search.selection());
            assertEquals("SIM0012", search.getText());
            assertFalse(popup.isVisible());
            search.setChoices(choices, null);
            assertEquals(choices[11], search.selection());

            search.setText("  jAvAcOs  ");
            assertTrue(popup.isVisible());
            assertEquals(1, list.getModel().getSize());
            assertEquals(1, list.getVisibleRowCount());
            assertFalse(scroll.getVerticalScrollBar().isVisible());
            assertNull(search.selection());
            list.dispatchEvent(
                new MouseEvent(
                    list, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1));
            assertEquals(reader, search.selection());
            assertFalse(popup.isVisible());

            search.setText("không có mã này");
            assertFalse(popup.isVisible());
            assertNull(search.selection());
            search.setChoices(choices, "SIM0003");
            assertEquals(choices[2], search.selection());
            search.setChoices(new Api.DeviceChoice[] {reader}, null);
            assertNull(search.selection());
            assertTrue(search.getText().isEmpty());
          });
      SwingUtilities.invokeAndWait(() -> assertTrue(search.isFocusOwner()));
    } finally {
      SwingUtilities.invokeAndWait(
          () -> {
            if (owner.get() != null) {
              owner.get().dispose();
            }
          });
    }
  }

  private void searchKey(DeviceSearch search, int keyCode) {
    KeyboardFocusManager.getCurrentKeyboardFocusManager()
        .redispatchEvent(
            search, new KeyEvent(search, KeyEvent.KEY_PRESSED, 0, 0, keyCode, KeyEvent.CHAR_UNDEFINED));
  }

  @Test
  void textCaretsFollowEditabilityAndClickOutside() throws Exception {
    record Fields(JFrame frame, JTextField editable, JTextArea readOnly, JLabel background) {}
    var create = new FutureTask<>(() -> {
      TTM_CuoiKy.configureLookAndFeel();
      JFrame frame = new JFrame();
      JTextField editable = new JTextField("Có thể nhập", 18);
      JTextArea readOnly = new JTextArea("Dữ liệu chỉ đọc", 2, 18);
      readOnly.setEditable(false);
      JLabel background = new JLabel("Vùng ngoài ô nhập");
      JPanel surface = new JPanel(new GridLayout(3, 1, 0, 8));
      surface.add(editable);
      surface.add(readOnly);
      surface.add(background);
      frame.setContentPane(surface);
      frame.pack();
      frame.setLocationRelativeTo(null);
      return new Fields(frame, editable, readOnly, background);
    });
    SwingUtilities.invokeAndWait(create);
    Fields fields = create.get();
    try {
      awaitFocus(fields.editable(), () -> {
        fields.frame().setVisible(true);
        fields.editable().requestFocusInWindow();
      });
      SwingUtilities.invokeAndWait(() -> {
        DefaultCaret caret = assertInstanceOf(DefaultCaret.class, fields.editable().getCaret());
        assertTrue(caret.isActive());
        fields.editable().setEditable(false);
        assertFalse(caret.isActive());
        fields.editable().setEditable(true);
        assertTrue(caret.isActive());
        assertTrue(caret.getBlinkRate() > 0);
      });

      awaitFocus(fields.readOnly(), () -> click(fields.readOnly()));
      SwingUtilities.invokeAndWait(() -> {
        DefaultCaret caret = assertInstanceOf(DefaultCaret.class, fields.readOnly().getCaret());
        assertFalse(caret.isActive());
        fields.readOnly().selectAll();
        assertEquals("Dữ liệu chỉ đọc", fields.readOnly().getSelectedText());
        fields.readOnly().setEditable(true);
        assertTrue(caret.isActive());
        assertTrue(caret.getBlinkRate() > 0);
        fields.readOnly().setEditable(false);
        assertFalse(caret.isActive());
      });

      awaitFocus(fields.editable(), () -> click(fields.editable()));
      SwingUtilities.invokeAndWait(() ->
          assertTrue(assertInstanceOf(DefaultCaret.class, fields.editable().getCaret()).isActive()));
      awaitFocus(fields.frame().getRootPane(), () -> click(fields.background()));
      SwingUtilities.invokeAndWait(() -> {
        assertFalse(fields.editable().isFocusOwner());
        assertFalse(assertInstanceOf(DefaultCaret.class, fields.editable().getCaret()).isActive());
      });
    } finally {
      SwingUtilities.invokeAndWait(fields.frame()::dispose);
    }
  }

  private static void awaitFocus(Component component, Runnable action) throws Exception {
    var focused = new CountDownLatch(1);
    FocusListener listener = new FocusAdapter() {
      @Override
      public void focusGained(FocusEvent event) {
        focused.countDown();
      }
    };
    try {
      SwingUtilities.invokeAndWait(() -> {
        component.addFocusListener(listener);
        action.run();
        if (component.isFocusOwner()) focused.countDown();
      });
      assertTrue(focused.await(4, TimeUnit.SECONDS), "Focus did not reach " + component.getClass().getSimpleName());
    } finally {
      SwingUtilities.invokeAndWait(() -> component.removeFocusListener(listener));
    }
  }

  private static void click(Component component) {
    long when = System.currentTimeMillis();
    component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_PRESSED, when,
        InputEvent.BUTTON1_DOWN_MASK, 5, 5, 1, false, MouseEvent.BUTTON1));
    component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_RELEASED, when,
        0, 5, 5, 1, false, MouseEvent.BUTTON1));
    component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_CLICKED, when,
        0, 5, 5, 1, false, MouseEvent.BUTTON1));
  }

  @Test
  void endingSessionClosesProfileDialogsAndClearsSensitiveForms() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            CanteenFrame frame = new CanteenFrame(new ApiClient(), false, false);
            frame.setVisible(true);
            assertNotNull(find(frame.admin, "Đăng nhập"));
            assertNull(find(frame.admin, "Thiết lập admin lần đầu"));
            AdminCardView card = studentField(frame.admin, "cardView", AdminCardView.class);
            ProfileForm form = studentField(card, "form", ProfileForm.class);
            form.code.setText("CT070352");
            form.citizen.setText("012345678901");
            JDialog profile = new JDialog(frame, "Hồ sơ fixture", false);
            profile.add(new JLabel("Hồ sơ kiểm thử"));
            profile.pack();
            profile.setVisible(true);
            long before = frame.sessionEpoch();
            frame.admin.expired();
            assertTrue(frame.sessionEpoch() > before);
            assertFalse(profile.isDisplayable());
            assertTrue(form.code.getText().isBlank());
            assertTrue(form.citizen.getText().isBlank());
            assertFalse(frame.admin.logged());
            frame.dispose();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test
  void asynchronousResultFromEndedSessionCannotRepopulateUi() throws Exception {
    AtomicReference<CanteenFrame> ref = new AtomicReference<>();
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicBoolean delivered = new AtomicBoolean();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            CanteenFrame f = new CanteenFrame(new ApiClient(), false, false);
            f.setVisible(true);
            ref.set(f);
            f.run(
                "Fixture chờ phản hồi",
                () -> {
                  started.countDown();
                  assertTrue(release.await(3, TimeUnit.SECONDS));
                  return "old-private-result";
                },
                r -> delivered.set(true));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    try {
      assertTrue(started.await(3, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> ref.get().student.clear());
      release.countDown();
      AtomicBoolean busy = new AtomicBoolean(true);
      for (int i = 0; i < 50 && busy.get(); i++) {
        Thread.sleep(40);
        SwingUtilities.invokeAndWait(() -> busy.set(ref.get().isBusy()));
      }
      assertFalse(busy.get());
      assertFalse(delivered.get());
    } finally {
      release.countDown();
      SwingUtilities.invokeAndWait(() -> ref.get().dispose());
    }
  }

  @Test
  void studentChangesPinInTheExistingEntryScreen() throws Exception {
    record PinUi(CanteenFrame frame, StudentView view, JPanel body, JLabel title, JLabel feedback,
        JPasswordField[] digits, SixDigitEntry entry, JButton back, JButton keyboard) {}
    var verifications = new AtomicInteger();
    var changes = new AtomicInteger();
    var wrongPin = new CountDownLatch(1);
    var currentVerified = new CountDownLatch(1);
    var currentVerifiedAgain = new CountDownLatch(1);
    var currentVerifiedThird = new CountDownLatch(1);
    var verifiedSteps = new AtomicInteger();
    var success = new CompletableFuture<Object>();
    var observer = new AtomicReference<javax.swing.Timer>();
    var home = new Api.StudentHome(null, 0);
    var create = new FutureTask<>(() -> {
      TTM_CuoiKy.configureLookAndFeel();
      var frame = new CanteenFrame(new ApiClient(), false, false);
      var view = new StudentView(frame, id -> fail("Unexpected top-up lookup"),
          id -> fail("Unexpected top-up cancellation"), input -> {
            verifications.incrementAndGet();
            if (input.pin().equals("000000")) {
              throw new ApiClient.ApiError(401, "PIN_WRONG", "Mã PIN hiện tại không đúng.");
            }
            assertEquals("123456", input.pin());
            return home;
          }, change -> {
            assertEquals("654321", change.newPin());
            changes.incrementAndGet();
            return new Api.Result("Fixture");
          });
      frame.setContentPane(view);
      var homeField = StudentView.class.getDeclaredField("home");
      homeField.setAccessible(true);
      homeField.set(view, home);
      JPanel body = studentField(view, "body", JPanel.class);
      ((CardLayout) body.getLayout()).show(body, "home");
      SixDigitEntry pinEntry = studentField(view, "pinEntry", SixDigitEntry.class);
      JLabel title = studentField(pinEntry, "title", JLabel.class);
      title.addPropertyChangeListener("text", event -> {
        if ("Nhập mã PIN mới".equals(event.getNewValue())) {
          switch (verifiedSteps.incrementAndGet()) {
            case 1 -> currentVerified.countDown();
            case 2 -> currentVerifiedAgain.countDown();
            default -> currentVerifiedThird.countDown();
          }
        }
      });
      JLabel feedback = studentField(pinEntry, "feedback", JLabel.class);
      feedback.addPropertyChangeListener("text", event -> {
        if (feedback.getText().contains("Mã PIN hiện tại không đúng.")) wrongPin.countDown();
      });
      return new PinUi(frame, view, body, title, feedback,
          studentField(pinEntry, "digits", JPasswordField[].class), pinEntry,
          studentField(pinEntry, "backButton", JButton.class),
          studentField(pinEntry, "keyboardButton", JButton.class));
    });
    SwingUtilities.invokeAndWait(create);
    PinUi ui = create.get();
    try {
      SwingUtilities.invokeAndWait(() -> {
        assertFalse(ui.back().isVisible(), "Trang nhập PIN đăng nhập không hiện nút quay lại");
        find(ui.view(), "Đổi mã PIN").doClick(0);
        assertTrue(ui.body().getComponent(1).isVisible());
        assertEquals("Nhập mã PIN hiện tại", ui.title().getText());
        assertEquals(6, ui.digits().length);
        Container entry = (Container) ui.body().getComponent(1);
        entry.setSize(1224, 686);
        entry.doLayout();
        ui.title().getParent().doLayout();
        ui.digits()[0].getParent().doLayout();
        assertEquals(new Dimension(64, 80), ui.digits()[0].getSize());
        assertStudentPinBack(ui.back(), ui.keyboard());
        ui.digits()[0].setText("1");
        ui.back().doClick(0);
        assertTrue(ui.body().getComponent(2).isVisible());
        assertFalse(ui.back().isVisible());
        for (JPasswordField digit : ui.digits()) assertEquals(0, digit.getPassword().length);
        assertEquals(0, verifications.get());
        find(ui.view(), "Đổi mã PIN").doClick(0);
        assertStudentPinBack(ui.back(), ui.keyboard());
        ui.digits()[0].setText("2");
        KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        Object escapeAction = ui.entry().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape);
        assertNotNull(escapeAction);
        ui.entry().getActionMap().get(escapeAction).actionPerformed(
            new ActionEvent(ui.entry(), ActionEvent.ACTION_PERFORMED, "escape"));
        assertTrue(ui.body().getComponent(2).isVisible());
        for (JPasswordField digit : ui.digits()) assertEquals(0, digit.getPassword().length);
        find(ui.view(), "Đổi mã PIN").doClick(0);
        JButton next = find(ui.view(), "Tiếp tục");
        assertNotNull(next);
        assertEquals(new Dimension(240, 48), next.getPreferredSize());
        enterFixturePin(ui.digits(), "000000");
        next.doClick(0);
      });
      assertTrue(wrongPin.await(5, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("Nhập mã PIN hiện tại", ui.title().getText());
        assertEquals(1, verifications.get());
        assertEquals(0, changes.get());
        enterFixturePin(ui.digits(), "123456");
        find(ui.view(), "Tiếp tục").doClick(0);
      });
      assertTrue(currentVerified.await(5, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(2, verifications.get());
        assertTrue(ui.body().getComponent(1).isVisible());
        assertStudentPinBack(ui.back(), ui.keyboard());
        assertEquals(0, ui.digits()[0].getPassword().length);
        ui.digits()[0].setText("6");
        KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        Object escapeAction = ui.entry().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape);
        ui.entry().getActionMap().get(escapeAction).actionPerformed(
            new ActionEvent(ui.entry(), ActionEvent.ACTION_PERFORMED, "escape"));
        assertTrue(ui.body().getComponent(2).isVisible(), "Escape hủy toàn bộ việc đổi PIN");
        assertFalse(ui.back().isVisible());
        for (JPasswordField digit : ui.digits()) assertEquals(0, digit.getPassword().length);
        find(ui.view(), "Đổi mã PIN").doClick(0);
        enterFixturePin(ui.digits(), "123456");
        find(ui.view(), "Tiếp tục").doClick(0);
      });
      assertTrue(currentVerifiedAgain.await(5, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(3, verifications.get());
        assertStudentPinBack(ui.back(), ui.keyboard());
        enterFixturePin(ui.digits(), "654321");
        ui.back().doClick(0);
        assertEquals("Nhập mã PIN hiện tại", ui.title().getText());
        for (JPasswordField digit : ui.digits()) assertEquals(0, digit.getPassword().length);
        assertEquals(0, changes.get());
        enterFixturePin(ui.digits(), "123456");
        find(ui.view(), "Tiếp tục").doClick(0);
      });
      assertTrue(currentVerifiedThird.await(5, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        assertEquals(4, verifications.get());
        assertStudentPinBack(ui.back(), ui.keyboard());
        enterFixturePin(ui.digits(), "654321");
        find(ui.view(), "Tiếp tục").doClick(0);
        assertEquals("Xác nhận mã PIN", ui.title().getText());
        assertStudentPinBack(ui.back(), ui.keyboard());
        char[] previousPin = assertDoesNotThrow(() -> studentField(ui.view(), "pendingNewPin", char[].class));
        assertNotNull(previousPin);
        ui.digits()[0].setText("6");
        ui.back().doClick(0);
        assertEquals("Nhập mã PIN mới", ui.title().getText());
        assertNull(assertDoesNotThrow(() -> studentField(ui.view(), "pendingNewPin", char[].class)));
        for (char digit : previousPin) assertEquals('\0', digit);
        for (JPasswordField digit : ui.digits()) assertEquals(0, digit.getPassword().length);
        assertEquals(0, changes.get());
        enterFixturePin(ui.digits(), "654321");
        find(ui.view(), "Tiếp tục").doClick(0);
        assertEquals("Xác nhận mã PIN", ui.title().getText());
        JButton finish = find(ui.view(), "Hoàn tất");
        assertNotNull(finish);
        enterFixturePin(ui.digits(), "654320");
        finish.doClick(0);
        assertEquals("Xác nhận mã PIN", ui.title().getText());
        assertEquals("PIN mới và xác nhận không khớp.", ui.feedback().getText());
        assertEquals(0, changes.get());

        var timer = new javax.swing.Timer(25, event -> {
          for (Window owned : ui.frame().getOwnedWindows()) {
            if (owned instanceof JDialog dialog && dialog.isShowing()
                && dialog.getContentPane().getComponentCount() > 0
                && dialog.getContentPane().getComponent(0) instanceof JOptionPane pane) {
              success.complete(pane.getMessage());
              dialog.dispose();
              ((javax.swing.Timer) event.getSource()).stop();
            }
          }
        });
        observer.set(timer);
        timer.start();
        enterFixturePin(ui.digits(), "654321");
        finish.doClick(0);
      });
      assertEquals("Đổi mã PIN thành công!", success.get(6, TimeUnit.SECONDS));
      assertEquals(1, changes.get());
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(ui.body().getComponent(2).isVisible());
        assertEquals("Nhập mã PIN", ui.title().getText());
        assertFalse(ui.back().isVisible());
        for (JPasswordField digit : ui.digits()) assertEquals(0, digit.getPassword().length);
      });
    } finally {
      SwingUtilities.invokeAndWait(() -> {
        if (observer.get() != null) observer.get().stop();
        ui.view().clear();
        ui.frame().dispose();
      });
    }
  }

  private static void assertStudentPinBack(JButton back, JButton keyboard) {
    assertTrue(back.isVisible());
    assertEquals(new Dimension(64, 64), back.getPreferredSize());
    assertEquals(keyboard.getPreferredSize(), back.getPreferredSize());
    assertEquals(keyboard.getSize(), back.getSize());
    assertEquals("Quay lại", back.getAccessibleContext().getAccessibleName());
    assertEquals(32, back.getIcon().getIconWidth());
    assertEquals(32, back.getIcon().getIconHeight());
  }

  private static <T> T studentField(Object view, String name, Class<T> type)
      throws ReflectiveOperationException {
    var field = view.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return type.cast(field.get(view));
  }

  private static void enterFixturePin(JPasswordField[] digits, String pin) {
    for (int i = 0; i < digits.length; i++) digits[i].setText(pin.substring(i, i + 1));
  }

  private static Api.TopupQr fixtureTopupQr(long remainingMillis) throws IOException {
    var code = ImageIO.read(LayoutSmokeTest.class.getResource("/fixtures/qr-sample.png"));
    var qr = new BufferedImage(460, 520, BufferedImage.TYPE_INT_RGB);
    var graphics = qr.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, qr.getWidth(), qr.getHeight());
      graphics.setColor(Color.BLUE);
      graphics.fillRect(20, 10, 420, 30);
      graphics.drawImage(code, 50, 70, null);
      graphics.setColor(Color.RED);
      graphics.fillRect(30, 460, 400, 28);
    } finally {
      graphics.dispose();
    }
    var image = new java.io.ByteArrayOutputStream();
    ImageIO.write(qr, "png", image);
    String dataUrl = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(image.toByteArray());
    Api.Topup topup = new Api.Topup(77, "NTCT07035200000077", "TTM000001", 10000,
        "WAITING_BANK", dataUrl, "Fixture");
    return new Api.TopupQr(topup, remainingMillis);
  }

  @Test
  void studentQrPageWaitsForCancellationAndLetsUserRetry() throws Exception {
    ApiClient api = new ApiClient();
    Api.TopupQr offer = fixtureTopupQr(300_000);
    var showQr = StudentView.class.getDeclaredMethod("showQr", Api.TopupQr.class);
    var closeQr = StudentView.class.getDeclaredMethod("closeQr");
    var pageField = StudentView.class.getDeclaredField("qrPage");
    var imageField = StudentView.class.getDeclaredField("qrImage");
    var statusField = StudentView.class.getDeclaredField("qrStatus");
    var homeField = StudentView.class.getDeclaredField("home");
    for (var field : new java.lang.reflect.Field[] {pageField, imageField, statusField, homeField}) {
      field.setAccessible(true);
    }
    showQr.setAccessible(true);
    closeQr.setAccessible(true);

    var window = new AtomicReference<CanteenFrame>();
    var student = new AtomicReference<StudentView>();
    var qrPage = new AtomicReference<JPanel>();
    var cancel = new AtomicReference<JButton>();
    var attempts = new AtomicInteger();
    CountDownLatch failed = new CountDownLatch(1), closed = new CountDownLatch(1);
    try {
      SwingUtilities.invokeAndWait(() -> {
        try {
          TTM_CuoiKy.configureLookAndFeel();
          CanteenFrame frame = new CanteenFrame(api, false, false);
          window.set(frame);
          StudentView view = new StudentView(frame, id -> offer.topup(), id -> {
            assertEquals(offer.topup().id(), id);
            if (attempts.incrementAndGet() == 1) {
              throw new ApiClient.ApiError(503, "CONNECTION", "Fixture disconnected");
            }
            return new Api.TopupCancellation("EXPIRED", "Đã hủy yêu cầu nạp.");
          });
          student.set(view);
          homeField.set(view, new Api.StudentHome(null, 0));
          var tabsField = CanteenFrame.class.getDeclaredField("tabs");
          tabsField.setAccessible(true);
          ((JTabbedPane) tabsField.get(frame)).setComponentAt(0, view);
          frame.setSize(frame.getMinimumSize());
          frame.setVisible(true);

          showQr.invoke(view, offer);
          JPanel previous = (JPanel) pageField.get(view);
          closeQr.invoke(view);
          assertNull(previous.getParent());
          assertEquals(0, attempts.get());

          showQr.invoke(view, offer);
          JPanel page = (JPanel) pageField.get(view);
          qrPage.set(page);
          frame.validate();
          assertTrue(page.isShowing());
          assertEquals(0, frame.getOwnedWindows().length, "QR must be a page, not a dialog");
          JLabel imageLabel = (JLabel) imageField.get(view);
          var qrIcon = assertInstanceOf(ImageIcon.class, imageLabel.getIcon());
          assertEquals(420, qrIcon.getIconWidth());
          assertEquals(475, qrIcon.getIconHeight());
          var qr = assertInstanceOf(BufferedImage.class, qrIcon.getImage());
          assertEquals(Color.BLUE.getRGB(), qr.getRGB(40, 20));
          assertEquals(Color.BLACK.getRGB(), qr.getRGB(95, 115));
          assertEquals(Color.RED.getRGB(), qr.getRGB(60, 430));
          Files.createDirectories(Path.of("target/ui-check"));
          ImageIO.write(qr, "png", Path.of("target/ui-check/student-topup-qr-sample.png").toFile());
          assertNull(findLabel(page, "Nạp tiền"));
          JLabel detailsTitle = findLabel(page, "Thông tin nạp tiền");
          assertNotNull(detailsTitle);
          assertEquals(32, detailsTitle.getFont().getSize());
          assertTrue(detailsTitle.getFont().isBold());
          assertEquals(SwingConstants.CENTER, detailsTitle.getHorizontalAlignment());
          JLabel amountLabel = findLabel(page, "Số tiền nạp");
          JLabel amount = findLabel(page, "10.000 ₫");
          JLabel referenceLabel = findLabel(page, "Mã tham chiếu");
          assertNotNull(amountLabel);
          assertNotNull(amount);
          assertNotNull(referenceLabel);
          JLabel reference = findLabel(page, offer.topup().reference());
          assertNotNull(reference);
          assertEquals(16, reference.getFont().getSize());
          assertTrue(reference.getFont().isBold());
          Component[] rows = reference.getParent().getComponents();
          int referenceIndex = java.util.Arrays.asList(rows).indexOf(reference);
          Component divider = rows[referenceIndex + 1];
          assertEquals(1, divider.getHeight());
          assertEquals(reference.getWidth(), divider.getWidth());
          assertEquals(new Color(0xD0D0D0), divider.getBackground());
          JLabel instruction = findLabel(page, "Mở ứng dụng ngân hàng/ví điện tử để tiến hành thanh toán");
          JLabel warningTitle = findLabel(page, "Lưu ý :");
          JLabel referenceWarning = findLabel(page, "Không chỉnh sửa nội dung chuyển khoản!");
          JLabel amountWarning = findLabel(page, "Chuyển đúng số tiền cần nạp!");
          assertNotNull(instruction);
          assertNotNull(warningTitle);
          assertNotNull(referenceWarning);
          assertNotNull(amountWarning);
          assertEquals(new Color(0xC62828), warningTitle.getForeground());
          assertEquals(warningTitle.getForeground(), referenceWarning.getForeground());
          assertEquals(warningTitle.getForeground(), amountWarning.getForeground());
          JLabel expiry = findLabel(page, "Yêu cầu sẽ hết hạn trong");
          assertNotNull(expiry);
          assertEquals(SwingConstants.CENTER, expiry.getHorizontalAlignment());
          JLabel countdown = findLabel(page, "05:00");
          if (countdown == null) countdown = findLabel(page, "04:59");
          assertNotNull(countdown);
          assertEquals(SwingConstants.CENTER, countdown.getHorizontalAlignment());
          assertEquals(36, amountLabel.getY() - detailsTitle.getY() - detailsTitle.getHeight());
          assertEquals(4, amount.getY() - amountLabel.getY() - amountLabel.getHeight());
          assertEquals(16, referenceLabel.getY() - amount.getY() - amount.getHeight());
          assertEquals(4, reference.getY() - referenceLabel.getY() - referenceLabel.getHeight());
          assertEquals(16, divider.getY() - reference.getY() - reference.getHeight());
          assertEquals(16, instruction.getY() - divider.getY() - divider.getHeight());
          assertEquals(4, warningTitle.getY() - instruction.getY() - instruction.getHeight());
          assertEquals(4, referenceWarning.getY() - warningTitle.getY() - warningTitle.getHeight());
          assertEquals(4, amountWarning.getY() - referenceWarning.getY() - referenceWarning.getHeight());
          assertEquals(32, expiry.getY() - amountWarning.getY() - amountWarning.getHeight());
          assertEquals(4, countdown.getY() - expiry.getY() - expiry.getHeight());
          JButton button = find(page, "Hủy yêu cầu");
          assertNotNull(button);
          assertEquals(new Dimension(320, 40), button.getSize());
          JLabel qrStatus = (JLabel) statusField.get(view);
          assertEquals(32, button.getY() - countdown.getY() - countdown.getHeight());
          assertEquals(16, qrStatus.getY() - button.getY() - button.getHeight());
          assertEquals(32, qrStatus.getHeight());
          assertTrue(Math.abs(detailsTitle.getX() + detailsTitle.getWidth() / 2
              - button.getX() - button.getWidth() / 2) <= 1, "The cancel button should be centered");
          Rectangle qrBounds = SwingUtilities.convertRectangle(imageLabel.getParent(), imageLabel.getBounds(), page);
          Rectangle referenceBounds = SwingUtilities.convertRectangle(reference.getParent(), reference.getBounds(), page);
          assertTrue(qrBounds.x + qrBounds.width < referenceBounds.x, "QR should be left of the details");
          Container columns = imageLabel.getParent().getParent();
          GridBagLayout layout = (GridBagLayout) columns.getLayout();
          Point origin = layout.getLayoutOrigin();
          int[][] dimensions = layout.getLayoutDimensions();
          Point qrCenter = SwingUtilities.convertPoint(imageLabel, imageLabel.getWidth() / 2,
              imageLabel.getHeight() / 2, columns);
          assertTrue(Math.abs(qrCenter.x - origin.x - dimensions[0][0] / 2) <= 1);
          assertTrue(Math.abs(qrCenter.y - origin.y - dimensions[1][0] / 2) <= 1);
          assertTrue(SwingUtilities.convertPoint(button, 0, button.getHeight(), page).y
              <= page.getHeight() - page.getInsets().bottom);
          Files.createDirectories(Path.of("target/ui-check"));
          capture(frame, Path.of("target/ui-check/student-topup-page.png"));
          page.addHierarchyListener(e -> {
            if (!page.isShowing()) closed.countDown();
          });
          qrStatus.addPropertyChangeListener("text", e -> {
            if ("Chưa xác nhận hủy. Hãy thử lại.".equals(e.getNewValue())) failed.countDown();
          });
          cancel.set(button);
          button.doClick(0);
          assertFalse(button.isEnabled());
          button.doClick(0);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      assertTrue(failed.await(5, TimeUnit.SECONDS));
      assertEquals(1, attempts.get());
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(qrPage.get().isShowing());
        assertTrue(cancel.get().isEnabled());
        cancel.get().doClick(0);
      });
      assertTrue(closed.await(5, TimeUnit.SECONDS));
      assertEquals(2, attempts.get());
    } finally {
      SwingUtilities.invokeAndWait(() -> {
        if (student.get() != null) student.get().clear();
        if (window.get() != null) window.get().dispose();
      });
    }
  }

  @Test
  void studentQrPageAutomaticallyClosesAndShowsExpiryMessage() throws Exception {
    ApiClient api = new ApiClient();
    Api.TopupQr offer = fixtureTopupQr(1_000);
    Api.Topup topup = offer.topup();
    var showQr = StudentView.class.getDeclaredMethod("showQr", Api.TopupQr.class);
    var pageField = StudentView.class.getDeclaredField("qrPage");
    var homeField = StudentView.class.getDeclaredField("home");
    showQr.setAccessible(true);
    pageField.setAccessible(true);
    homeField.setAccessible(true);
    var window = new AtomicReference<CanteenFrame>();
    var student = new AtomicReference<StudentView>();
    var qrPage = new AtomicReference<JPanel>();
    var observer = new AtomicReference<javax.swing.Timer>();
    var notice = new CompletableFuture<Object>();
    var stateReads = new AtomicInteger();
    try {
      SwingUtilities.invokeAndWait(() -> {
        try {
          TTM_CuoiKy.configureLookAndFeel();
          var frame = new CanteenFrame(api, false, false);
          window.set(frame);
          var view = new StudentView(frame, id -> {
            assertEquals(topup.id(), id);
            stateReads.incrementAndGet();
            return new Api.Topup(id, topup.reference(), topup.cardId(), topup.amount(),
                "EXPIRED", "", "Fixture server message");
          }, id -> fail("The expiry timer should read state, not cancel the request"));
          student.set(view);
          homeField.set(view, new Api.StudentHome(null, 0));
          var tabsField = CanteenFrame.class.getDeclaredField("tabs");
          tabsField.setAccessible(true);
          ((JTabbedPane) tabsField.get(frame)).setComponentAt(0, view);
          frame.setVisible(true);
          showQr.invoke(view, offer);
          qrPage.set((JPanel) pageField.get(view));
          assertTrue(qrPage.get().isShowing());
          var timer = new javax.swing.Timer(25, e -> {
            for (Window owned : frame.getOwnedWindows()) {
              if (owned instanceof JDialog dialog && dialog.isShowing()
                  && dialog.getContentPane().getComponentCount() > 0
                  && dialog.getContentPane().getComponent(0) instanceof JOptionPane pane) {
                notice.complete(pane.getMessage());
                dialog.dispose();
                ((javax.swing.Timer) e.getSource()).stop();
              }
            }
          });
          observer.set(timer);
          timer.start();
        } catch (ReflectiveOperationException e) {
          throw new AssertionError(e);
        }
      });
      assertEquals("Yêu cầu đã hết hạn!", notice.get(6, TimeUnit.SECONDS));
      assertEquals(1, stateReads.get());
      SwingUtilities.invokeAndWait(() -> assertNull(qrPage.get().getParent()));
    } finally {
      SwingUtilities.invokeAndWait(() -> {
        if (observer.get() != null) observer.get().stop();
        if (student.get() != null) student.get().clear();
        if (window.get() != null) window.get().dispose();
      });
    }
  }

  @ParameterizedTest
  @CsvSource({"900000,900.000", "1000000,1.000.000"})
  void studentTopupAmountFormatsMoneyAndKeepsErrorSpace(long validAmount, String display)
      throws Exception {
    var window = new AtomicReference<CanteenFrame>();
    var confirmedAmount = new AtomicLong();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        try {
          var frame = new CanteenFrame(new ApiClient(), false, false);
          window.set(frame);
          var home = StudentView.class.getDeclaredField("home");
          home.setAccessible(true);
          home.set(frame.student, new Api.StudentHome(null, 0));
          SwingUtilities.invokeLater(() -> frame.student.topup((dialog, amount) -> {
            dialog.dispose();
            confirmedAmount.set(amount);
          }));
        } catch (IOException | ReflectiveOperationException e) {
          throw new AssertionError(e);
        }
      });
      SwingUtilities.invokeAndWait(() -> {
        JDialog dialog = assertInstanceOf(JDialog.class, window.get().getOwnedWindows()[0]);
        assertTrue(dialog.isShowing());
        assertEquals("Nạp tiền", dialog.getTitle());
        Container content = dialog.getContentPane();
        JPanel input = (JPanel) ((BorderLayout) content.getLayout())
            .getLayoutComponent(BorderLayout.CENTER);
        BorderLayout layout = (BorderLayout) input.getLayout();
        JLabel prompt = (JLabel) layout.getLayoutComponent(BorderLayout.NORTH);
        JTextField amount = assertInstanceOf(JTextField.class, prompt.getLabelFor());
        JLabel currency = assertInstanceOf(JLabel.class,
            amount.getClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT));
        JLabel error = (JLabel) layout.getLayoutComponent(BorderLayout.SOUTH);
        JButton ok = find(dialog, "OK");
        JButton cancel = find(dialog, "Cancel");
        Rectangle initialDialog = dialog.getBounds();
        Rectangle initialAmount = amount.getBounds();
        Rectangle initialCurrency = currency.getBounds();
        Point initialButtons = SwingUtilities.convertPoint(cancel, 0, 0, content);

        assertEquals("Nhập số tiền cần nạp", prompt.getText());
        assertEquals(new Dimension(320, 32), initialAmount.getSize());
        assertEquals("", amount.getText());
        assertEquals("VND", currency.getText());
        assertTrue(currency.isShowing());
        assertEquals(8, amount.getWidth() - currency.getX() - currency.getWidth()
            + currency.getInsets().right);
        assertEquals(prompt.getX(), amount.getX());
        assertEquals(8, amount.getY() - prompt.getY() - prompt.getHeight());
        assertEquals(8, error.getY() - amount.getY() - amount.getHeight());
        assertEquals("", error.getText());
        assertTrue(error.getHeight() > 0);
        assertEquals(16, error.getFont().getSize());
        assertEquals(new Color(0xC62828), error.getForeground());
        Point errorBottom = SwingUtilities.convertPoint(error, 0, error.getHeight(), content);
        assertEquals(8, initialButtons.y - errorBottom.y);
        assertEquals(new Dimension(90, 30), ok.getSize());
        assertEquals(ok.getSize(), cancel.getSize());
        assertEquals(8, cancel.getX() - ok.getX() - ok.getWidth());
        Point amountRight = SwingUtilities.convertPoint(amount, amount.getWidth(), 0, content);
        assertEquals(amountRight.x, initialButtons.x + cancel.getWidth());

        amount.replaceSelection("a");
        assertEquals("", amount.getText());
        assertEquals("", error.getText());
        ok.doClick(0);
        assertTrue(dialog.isShowing());
        assertEquals(0, confirmedAmount.get());
        assertEquals("", error.getText());

        for (char digit : "1000".toCharArray()) {
          amount.replaceSelection(String.valueOf(digit));
        }
        assertEquals("1.000", amount.getText());
        amount.replaceSelection("000");
        assertEquals("1.000.000", amount.getText());
        assertEquals("", error.getText());

        for (String invalid : new String[] {"a", "@", ".", "10a00"}) {
          amount.selectAll();
          amount.replaceSelection(invalid);
          assertEquals("1.000.000", amount.getText());
          assertEquals("", error.getText());
        }
        String limitError = CanteenFrame.html("Số tiền cần nạp không được vượt quá\n1.000.000 VND!");
        for (String exceeded : new String[] {"1000001", "99999999999999999999"}) {
          amount.selectAll();
          amount.replaceSelection(exceeded);
          assertEquals("1.000.000", amount.getText());
          assertEquals(limitError, error.getText());
        }
        dialog.validate();
        assertEquals(initialDialog, dialog.getBounds());
        assertEquals(initialAmount, amount.getBounds());
        assertEquals(initialCurrency, currency.getBounds());
        assertEquals("VND", currency.getText());
        assertTrue(currency.isShowing());
        assertEquals(initialButtons, SwingUtilities.convertPoint(cancel, 0, 0, content));

        amount.setText(Long.toString(validAmount));
        assertEquals(display, amount.getText());
        assertEquals("", error.getText());
        amount.setCaretPosition(amount.getText().length());
        amount.replaceSelection("1");
        assertEquals(display, amount.getText());
        assertEquals(limitError, error.getText());

        ok.doClick(0);
        assertFalse(dialog.isShowing());
        assertEquals(validAmount, confirmedAmount.get());
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void desktopControlsPaintTheSameOnHoverPressAndFocus() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      TTM_CuoiKy.configureLookAndFeel();
      var focused = new AtomicBoolean();
      JButton button = new JButton("Kết nối", new ImageIcon(new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB))) {
        @Override
        public boolean hasFocus() {
          return focused.get();
        }
      };
      button.setSize(128, 32);
      assertEquals(4, Math.round(com.formdev.flatlaf.ui.FlatUIUtils.getBorderArc(button) / 2));
      for (String type : new String[] {null, com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON}) {
        button.putClientProperty(com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE, type);
        int[] normal = paintedPixels(button);
        button.getModel().setRollover(true);
        assertArrayEquals(normal, paintedPixels(button));
        button.getModel().setPressed(true);
        assertArrayEquals(normal, paintedPixels(button));
        button.getModel().setPressed(false);
        button.getModel().setRollover(false);
        focused.set(true);
        assertArrayEquals(normal, paintedPixels(button));
        focused.set(false);
      }

      JTextField text = new JTextField("Nội dung") {
        @Override
        public boolean hasFocus() {
          return focused.get();
        }
      };
      text.setSize(160, 32);
      assertEquals(4, Math.round(com.formdev.flatlaf.ui.FlatUIUtils.getBorderArc(text) / 2));
      int[] normal = paintedPixels(text);
      focused.set(true);
      assertArrayEquals(normal, paintedPixels(text));
    });
  }

  private int[] paintedPixels(JComponent component) {
    int width = component.getWidth(), height = component.getHeight();
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      component.printAll(graphics);
    } finally {
      graphics.dispose();
    }
    return image.getRGB(0, 0, width, height, null, 0, width);
  }

  @Test
  void studentHistoryIsReachableAtMinimumSizeAndClearsOnSessionEnd() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          CanteenFrame frame = null;
          try {
            TTM_CuoiKy.configureLookAndFeel();
            frame = new CanteenFrame(new ApiClient(), false, false);
            frame.setSize(frame.getMinimumSize());
            var bodyField = StudentView.class.getDeclaredField("body");
            bodyField.setAccessible(true);
            JPanel body = (JPanel) bodyField.get(frame.student);
            ((CardLayout) body.getLayout()).show(body, "home");
            frame.setVisible(true);

            BorderLayout main = (BorderLayout) frame.getContentPane().getLayout();
            JPanel toolbar = (JPanel) main.getLayoutComponent(BorderLayout.NORTH);
            Box connection = (Box) ((BorderLayout) toolbar.getLayout()).getLayoutComponent(BorderLayout.WEST);
            assertEquals(new Dimension(384, 32), connection.getComponent(0).getSize());
            for (String caption : new String[] {"Tạo thẻ mới", "Kết nối", "Ngắt kết nối", "Đồng bộ lại"}) {
              JButton button = find(toolbar, caption);
              assertNotNull(button);
              assertEquals(new Dimension(128, 32), button.getSize());
              Insets padding = button.getInsets();
              assertTrue(button.getFontMetrics(button.getFont()).stringWidth(caption)
                  <= button.getWidth() - padding.left - padding.right);
            }
            JTabbedPane tabs = (JTabbedPane) main.getLayoutComponent(BorderLayout.CENTER);
            Rectangle studentTab = tabs.getBoundsAt(0);
            Rectangle adminTab = tabs.getBoundsAt(1);
            assertEquals(new Dimension(128, 32), studentTab.getSize());
            assertEquals(studentTab.getSize(), adminTab.getSize());
            assertEquals(studentTab.x + studentTab.width, adminTab.x);

            JTable table = showingTable(frame.student);
            assertNotNull(table);
            CardTransactionsView history = (CardTransactionsView)
                SwingUtilities.getAncestorOfClass(CardTransactionsView.class, table);
            Container details = history.getParent();
            var profile = (ProfileForm) ((BorderLayout) details.getLayout())
                .getLayoutComponent(BorderLayout.NORTH);
            JPanel portrait = profilePortrait(profile);
            assertEquals(1, portrait.getComponentCount());
            JLabel avatar = assertInstanceOf(JLabel.class, portrait.getComponent(0));
            frame.validate();
            var welcomeField = StudentView.class.getDeclaredField("welcome");
            welcomeField.setAccessible(true);
            JLabel welcome = (JLabel) welcomeField.get(frame.student);
            assertEquals(new Insets(12, 16, 12, 16), welcome.getInsets());
            assertEquals(welcome.getFontMetrics(welcome.getFont()).getHeight() + 24, welcome.getHeight());
            Container profileHeader = welcome.getParent();
            JButton changePin = find(profileHeader, "Đổi mã PIN");
            assertNotNull(changePin);
            assertEquals(new Dimension(128, 32), changePin.getSize());
            assertEquals(
                new Point(profileHeader.getWidth() - 16, welcome.getHeight() / 2),
                SwingUtilities.convertPoint(
                    changePin, changePin.getWidth(), changePin.getHeight() / 2, profileHeader));

            JPanel historyHeader = (JPanel) ((BorderLayout) history.getLayout())
                .getLayoutComponent(BorderLayout.NORTH);
            JLabel historyTitle = (JLabel) ((BorderLayout) historyHeader.getLayout())
                .getLayoutComponent(BorderLayout.WEST);
            JLabel historyStatus = (JLabel) ((BorderLayout) historyHeader.getLayout())
                .getLayoutComponent(BorderLayout.CENTER);
            assertEquals(8, historyStatus.getX() - historyTitle.getX() - historyTitle.getWidth());
            assertEquals(new Insets(12, 0, 12, 0), historyTitle.getInsets());
            assertEquals(16, history.getInsets().left);
            assertEquals(16, history.getInsets().right);
            assertEquals(0, history.getInsets().bottom);
            assertEquals(historyTitle.getFontMetrics(historyTitle.getFont()).getHeight() + 24,
                historyTitle.getHeight());
            assertEquals(profileHeader.getHeight(), historyHeader.getHeight());

            Point avatarBottom = SwingUtilities.convertPoint(avatar, 0, avatar.getHeight(), details);
            assertEquals(16, history.getY() - avatarBottom.y);
            Point avatarTop = SwingUtilities.convertPoint(avatar, 0, 0, details);
            Container fields = (Container) ((BorderLayout) profile.getLayout())
                .getLayoutComponent(BorderLayout.CENTER);
            var informationRows = java.util.Arrays.stream(fields.getComponents())
                .filter(JPanel.class::isInstance).toList();
            assertEquals(5, informationRows.size());
            for (int i = 0; i < informationRows.size(); i++) {
              Component row = informationRows.get(i);
              assertEquals(40, row.getHeight());
              assertEquals(avatarTop.y + i * 70,
                  SwingUtilities.convertPoint(row, 0, 0, details).y);
            }
            JPanel balanceRow = (JPanel) informationRows.get(4);
            JLabel balanceLabel = (JLabel) balanceRow.getComponent(0);
            JTextField balance = (JTextField) balanceLabel.getLabelFor();
            JPanel balanceActions = assertInstanceOf(JPanel.class, balance.getComponent(0));
            JButton eye = assertInstanceOf(JButton.class, balanceActions.getComponent(0));
            profile.setBalance(999_999_999);
            frame.validate();
            JButton topup = find(balanceRow, "Nạp tiền");
            assertNotNull(topup);
            assertEquals(new Dimension(120, 40), topup.getSize());
            assertEquals(balance.getX() + balance.getWidth() + 16, topup.getX());
            assertEquals(balance.getY() + balance.getHeight() / 2,
                topup.getY() + topup.getHeight() / 2);
            int compactWidth = balance.getPreferredSize().width;
            assertEquals(compactWidth, balance.getWidth());
            assertTrue(compactWidth < balanceRow.getWidth() / 2);
            assertTrue(balanceActions.getX() >= 160);
            assertEquals(16, balance.getFont().getSize());
            assertEquals(8, profile.cardId.getInsets().left);
            assertEquals(1, balanceActions.getInsets().left);
            assertEquals(new Dimension(40, 40), eye.getPreferredSize());
            assertEquals(24, eye.getIcon().getIconWidth());
            assertEquals("*".repeat(15) + " VND", balance.getText());
            eye.doClick(0);
            assertEquals("999.999.999 VND", balance.getText());
            assertEquals(compactWidth, balance.getPreferredSize().width);
            profile.setBalance(Long.MAX_VALUE);
            assertEquals("9.223.372.036.854.775.807 VND", balance.getText());
            assertTrue(balance.getPreferredSize().width > compactWidth);
            eye.doClick(0);
            assertEquals("*".repeat(15) + " VND", balance.getText());
            assertEquals(compactWidth, balance.getPreferredSize().width);
            assertNull(find(frame.student, "Xem số dư"));

            assertEquals("Lịch sử giao dịch", table.getAccessibleContext().getAccessibleName());
            assertEquals("Tiền vào", table.getColumnName(3));
            assertEquals("Tiền ra", table.getColumnName(4));
            for (int row = 0; row < 4; row++) {
              assertEquals(row < 3 ? 41 : 40, table.getRowHeight(row));
            }
            assertEquals(0, table.getRowMargin());
            assertFalse(table.getShowHorizontalLines());
            assertEquals(41, table.getTableHeader().getHeight());
            assertEquals(1, table.getTableHeader().getInsets().bottom);
            assertTrue(table.getTableHeader().getFont().isBold());
            assertEquals(16, table.getTableHeader().getFont().getSize());
            assertEquals(new Color(0xAAAAAA), table.getTableHeader().getBackground());
            assertEquals(table.getBackground(), table.getSelectionBackground());

            var model = (javax.swing.table.DefaultTableModel) table.getModel();
            Object[] transaction = {"08/11/2026 08:05:00", "Nạp tiền", "NT-DEMO", "+100.000 ₫", ""};
            for (int i = 0; i < 4; i++) {
              for (int column = 0; column < transaction.length; column++) {
                model.setValueAt(transaction[column], i, column);
              }
            }
            assertFalse(table.isCellEditable(0, 0));
            for (int column = 0; column < table.getColumnCount(); column++) {
              JLabel cell = (JLabel) table.prepareRenderer(table.getCellRenderer(0, column), 0, column);
              assertEquals(SwingConstants.CENTER, cell.getHorizontalAlignment());
            }

            JScrollPane tableScroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
            assertEquals(206, tableScroll.getHeight());
            assertEquals(new Insets(1, 1, 1, 1), tableScroll.getInsets());
            assertEquals(historyHeader.getY() + historyHeader.getHeight(),
                SwingUtilities.convertPoint(tableScroll, 0, 0, history).y);
            JViewport tableViewport = tableScroll.getViewport();
            JScrollBar tableScrollBar = tableScroll.getVerticalScrollBar();
            tableScroll.doLayout();
            assertEquals(163, tableViewport.getHeight());
            assertFalse(tableScrollBar.isVisible());
            assertEquals(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, tableScroll.getVerticalScrollBarPolicy());
            Rectangle lastRow = table.getCellRect(3, 0, true);
            assertEquals(tableScroll.getHeight() - 1,
                SwingUtilities.convertPoint(table, 0, lastRow.y + lastRow.height, tableScroll).y);
            BufferedImage painted = new BufferedImage(tableScroll.getWidth(), tableScroll.getHeight(),
                BufferedImage.TYPE_INT_RGB);
            Graphics2D tableGraphics = painted.createGraphics();
            tableScroll.paint(tableGraphics);
            tableGraphics.dispose();
            assertEquals(0xC2C2C2, painted.getRGB(4, 0) & 0xFFFFFF);
            assertEquals(0xC2C2C2, painted.getRGB(4, 205) & 0xFFFFFF);
            assertEquals(0xFFFFFF, painted.getRGB(4, 204) & 0xFFFFFF);
            for (int line : new int[] {41, 82, 123, 164}) {
              assertEquals(0xD0D0D0, painted.getRGB(4, line) & 0xFFFFFF);
            }
            PaginationBar pager = studentField(history, "pagination", PaginationBar.class);
            JButton next = java.util.Arrays.stream(pager.getComponents())
                .filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(b -> "Trang sau".equals(b.getAccessibleContext().getAccessibleName()))
                .findFirst().orElseThrow();
            assertEquals(new Dimension(40, 40), next.getSize());
            assertEquals(SwingUtilities.convertPoint(tableScroll, tableScroll.getWidth(), 0, history).x,
                SwingUtilities.convertPoint(next, next.getWidth(), 0, history).x);
            assertEquals(8, SwingUtilities.convertPoint(pager, 0, 0, history).y
                - SwingUtilities.convertPoint(tableScroll, 0, tableScroll.getHeight(), history).y);

            JScrollPane dashboardScroll =
                (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, tableScroll);
            assertNotNull(dashboardScroll);
            frame.validate();
            assertFalse(dashboardScroll.getVerticalScrollBar().isVisible());
            dashboardScroll.getVerticalScrollBar().setValue(dashboardScroll.getVerticalScrollBar().getMaximum());
            assertEquals(163, table.getVisibleRect().height);
            assertEquals(40, pager.getVisibleRect().height);
            assertTrue(topup.isShowing());
            capture(frame, Path.of("target/ui-check/student-dashboard-pagination.png"));

            frame.setSize(frame.getWidth(), 1000);
            frame.validate();
            assertEquals(163, tableViewport.getHeight());
            assertFalse(tableScrollBar.isVisible());

            frame.student.clear();
            assertEquals(4, table.getRowCount());
            assertNull(table.getValueAt(0, 0));
            assertEquals("", balance.getText());
            assertFalse(eye.isEnabled());
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            if (frame != null) frame.dispose();
          }
        });
  }

  @Test
  void studentHistoryPaginatesFourTransactionsAndRetainsPageOnFailure() throws Exception {
    ApiClient client = new ApiClient();
    var window = new AtomicReference<CanteenFrame>();
    var tableScroll = new AtomicReference<JScrollPane>();
    var bar = new AtomicReference<PaginationBar>();
    var count = new AtomicReference<JLabel>();
    var statusLabel = new AtomicReference<JLabel>();
    var statuses = new LinkedBlockingQueue<String>();
    var requests = new CopyOnWriteArrayList<Integer>();
    var total = new AtomicInteger(13);
    var failOlderOnce = new AtomicBoolean(true);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var blockStale = new AtomicBoolean(false);
    var staleStarted = new CountDownLatch(1);
    var staleRelease = new CountDownLatch(1);
    var staleFinished = new CountDownLatch(1);
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        var frame = new CanteenFrame(client, false, false);
        var history = new CardTransactionsView(frame, page -> {
          requests.add(page);
          if (page == 0 && blockStale.getAndSet(false)) {
            staleStarted.countDown();
            boolean interrupted = false;
            try {
              for (;;) {
                try {
                  if (!staleRelease.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
                  break;
                } catch (InterruptedException e) {
                  interrupted = true;
                }
              }
              return historyPage(13, 0);
            } finally {
              if (interrupted) Thread.currentThread().interrupt();
              staleFinished.countDown();
            }
          }
          if (page == 1 && failOlderOnce.getAndSet(false)) {
            throw new ApiClient.ApiError(503, "TEST_UNAVAILABLE", "Fixture unavailable");
          }
          if (page == 1 && started.getCount() != 0) {
            started.countDown();
            try {
              if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(e);
            }
          }
          return historyPage(total.get(), page);
        });
        frame.setContentPane(history);
        window.set(frame);
        frame.setSize(frame.getMinimumSize());
        frame.setVisible(true);
        JTable table = showingTable(history);
        assertNotNull(table);
        tableScroll.set((JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table));
        JPanel header = (JPanel) ((BorderLayout) history.getLayout())
            .getLayoutComponent(BorderLayout.NORTH);
        JLabel status = (JLabel) ((BorderLayout) header.getLayout())
            .getLayoutComponent(BorderLayout.CENTER);
        statusLabel.set(status);
        status.addPropertyChangeListener("text", event -> statuses.add((String) event.getNewValue()));
        try {
          bar.set(studentField(history, "pagination", PaginationBar.class));
          count.set(studentField(history, "resultCount", JLabel.class));
        } catch (ReflectiveOperationException e) {
          throw new AssertionError(e);
        }
        history.reload();
      });
      assertEquals("Đang tải lịch sử giao dịch…", statuses.poll(5, TimeUnit.SECONDS));
      assertEquals(" ", statuses.poll(5, TimeUnit.SECONDS));

      SwingUtilities.invokeAndWait(() -> {
        JScrollPane pane = tableScroll.get();
        pane.doLayout();
        pane.getViewport().doLayout();
        JTable table = (JTable) pane.getViewport().getView();
        assertEquals(4, table.getRowCount());
        assertEquals(163, pane.getViewport().getHeight());
        assertEquals("--", ((JLabel) table.prepareRenderer(table.getCellRenderer(0, 2), 0, 2)).getText());
        assertEquals("--", ((JLabel) table.prepareRenderer(table.getCellRenderer(0, 4), 0, 4)).getText());
        assertEquals("--", ((JLabel) table.prepareRenderer(table.getCellRenderer(1, 3), 1, 3)).getText());
        assertEquals("GD-12", table.getValueAt(1, 2));
        assertEquals("Hiển thị 1–4 trên tổng số 13 kết quả", count.get().getText());
        try {
          capture(window.get(), Path.of("target/ui-check/student-pagination.png"));
        } catch (Exception e) {
          throw new AssertionError(e);
        }
        find(bar.get(), "2").doClick(0);
      });
      assertEquals("Đang tải lịch sử giao dịch…", statuses.poll(5, TimeUnit.SECONDS));
      assertEquals("Chưa tải được trang giao dịch. Vui lòng thử lại.", statuses.poll(5, TimeUnit.SECONDS));

      SwingUtilities.invokeAndWait(() -> {
        JTable table = (JTable) tableScroll.get().getViewport().getView();
        assertEquals("GD-12", table.getValueAt(1, 2));
        assertEquals("Hiển thị 1–4 trên tổng số 13 kết quả", count.get().getText());
        assertEquals(TTM_CuoiKy.ERROR_TEXT_COLOR, statusLabel.get().getForeground());
        find(bar.get(), "2").doClick(0);
      });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertEquals("Đang tải lịch sử giao dịch…", statuses.poll(5, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(java.util.Arrays.stream(bar.get().getComponents())
            .filter(Component::isVisible).filter(JButton.class::isInstance).map(JButton.class::cast)
            .noneMatch(JButton::isEnabled));
        assertEquals("Hiển thị 1–4 trên tổng số 13 kết quả", count.get().getText());
      });
      release.countDown();
      assertEquals(" ", statuses.poll(5, TimeUnit.SECONDS));

      SwingUtilities.invokeAndWait(() -> {
        JTable table = (JTable) tableScroll.get().getViewport().getView();
        assertEquals("GD-9", table.getValueAt(0, 2));
        assertEquals("Hiển thị 5–8 trên tổng số 13 kết quả", count.get().getText());
        find(bar.get(), "4").doClick(0);
      });
      assertEquals("Đang tải lịch sử giao dịch…", statuses.poll(5, TimeUnit.SECONDS));
      assertEquals(" ", statuses.poll(5, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        JTable table = (JTable) tableScroll.get().getViewport().getView();
        assertEquals("GD-1", table.getValueAt(0, 2));
        assertEquals("Hiển thị 13 trên tổng số 13 kết quả", count.get().getText());
        for (int row = 1; row < 4; row++) {
          for (int column = 0; column < table.getColumnCount(); column++) {
            assertEquals("--", ((JLabel) table.prepareRenderer(table.getCellRenderer(row, column), row, column)).getText());
          }
        }
        JButton previous = java.util.Arrays.stream(bar.get().getComponents())
            .filter(JButton.class::isInstance).map(JButton.class::cast)
            .filter(b -> "Trang trước".equals(b.getAccessibleContext().getAccessibleName()))
            .findFirst().orElseThrow();
        previous.doClick(0);
      });
      assertEquals("Đang tải lịch sử giao dịch…", statuses.poll(5, TimeUnit.SECONDS));
      assertEquals(" ", statuses.poll(5, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("Hiển thị 9–12 trên tổng số 13 kết quả", count.get().getText());
        total.set(5);
        find(bar.get(), "4").doClick(0);
      });
      assertEquals("Đang tải lịch sử giao dịch…", statuses.poll(5, TimeUnit.SECONDS));
      assertEquals(" ", statuses.poll(5, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("Hiển thị 5 trên tổng số 5 kết quả", count.get().getText());
        total.set(0);
        ((CardTransactionsView) window.get().getContentPane()).reload();
      });
      assertEquals("Đang tải lịch sử giao dịch…", statuses.poll(5, TimeUnit.SECONDS));
      assertEquals(" ", statuses.poll(5, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("Hiển thị 0 trên tổng số 0 kết quả", count.get().getText());
        JTable table = (JTable) tableScroll.get().getViewport().getView();
        for (int row = 0; row < 4; row++) assertNull(table.getValueAt(row, 0));
        blockStale.set(true);
        ((CardTransactionsView) window.get().getContentPane()).reload();
      });
      assertEquals("Đang tải lịch sử giao dịch…", statuses.poll(5, TimeUnit.SECONDS));
      assertTrue(staleStarted.await(5, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> ((CardTransactionsView) window.get().getContentPane()).clear());
      staleRelease.countDown();
      assertTrue(staleFinished.await(5, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {
        assertEquals("Hiển thị 0 trên tổng số 0 kết quả", count.get().getText());
        JTable table = (JTable) tableScroll.get().getViewport().getView();
        for (int row = 0; row < 4; row++) assertNull(table.getValueAt(row, 0));
      });
      assertEquals(List.of(0, 1, 1, 3, 2, 3, 1, 0, 0), requests);
    } finally {
      release.countDown();
      staleRelease.countDown();
      SwingUtilities.invokeAndWait(() -> {
        CanteenFrame frame = window.get();
        if (frame != null) {
          ((CardTransactionsView) frame.getContentPane()).clear();
          frame.dispose();
        }
      });
    }
  }

  private Api.CardTransactionListPage historyPage(int total, int page) {
    List<Api.CardTransaction> items = new ArrayList<>();
    int firstId = total - page * 4;
    int count = Math.max(0, Math.min(4, firstId));
    for (int i = 0; i < count; i++) {
      int id = firstId - i;
      items.add(new Api.CardTransaction(id, i % 2 == 0 ? "CREDIT" : "DEBIT", 1000,
          "2026-11-08T01:05:00Z", id == 13 ? null : "GD-" + id));
    }
    return new Api.CardTransactionListPage(items, total, page, 4);
  }

  @Test
  void adminAvatarOpensFileChooserOnlyWhileEditing() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      ProfileForm admin = ProfileForm.forAdminDetails();
      JPanel portrait = profilePortrait(admin);
      assertEquals(1, portrait.getComponentCount());
      JButton avatar = assertInstanceOf(JButton.class, portrait.getComponent(0));
      assertFalse(avatar.isEnabled());
      admin.setEditable(true);
      assertTrue(avatar.isEnabled());

      AtomicBoolean opened = new AtomicBoolean();
      javax.swing.Timer closeChooser = new javax.swing.Timer(50, e -> {
        for (Window window : Window.getWindows()) {
          if (window instanceof JDialog dialog && dialog.isShowing()) {
            opened.set("Chọn ảnh chân dung".equals(dialog.getTitle()));
            dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
          }
        }
      });
      closeChooser.start();
      try {
        avatar.doClick(0);
      } finally {
        closeChooser.stop();
      }
      assertTrue(opened.get());
      assertTrue(admin.code.isEditable());
      admin.setEditable(false);
      assertFalse(avatar.isEnabled());
    });
  }

  private JPanel profilePortrait(ProfileForm profile) {
    return (JPanel) ((BorderLayout) profile.getLayout()).getLayoutComponent(BorderLayout.WEST);
  }

  private JLabel findLabel(Container root, String text) {
    for (Component c : root.getComponents()) {
      if (c instanceof JLabel label && text.equals(label.getText())) return label;
      if (c instanceof Container container) {
        JLabel found = findLabel(container, text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private JButton find(Container root, String text) {
    for (Component c : root.getComponents()) {
      if (c instanceof JButton b && text.equals(b.getText())) return b;
      if (c instanceof Container container) {
        JButton b = find(container, text);
        if (b != null) return b;
      }
    }
    return null;
  }

  private JTable showingTable(Container root) {
    for (Component c : root.getComponents()) {
      if (c instanceof JTable table && table.isShowing()) return table;
      if (c instanceof Container child) {
        var table = showingTable(child);
        if (table != null) return table;
      }
    }
    return null;
  }

  private void capture(JFrame frame, Path file) throws Exception {
    frame.validate();
    BufferedImage image =
        new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    frame.paint(g);
    g.dispose();
    ImageIO.write(image, "png", file.toFile());
  }
}
