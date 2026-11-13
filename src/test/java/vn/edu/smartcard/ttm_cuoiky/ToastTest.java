package vn.edu.smartcard.ttm_cuoiky;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.StyleConstants;
import org.junit.jupiter.api.Test;

class ToastTest {
  @Test
  void checkoutErrorLeavesBothTabsInPlaceAndAppearsAboveThem() throws Exception {
    var window = new AtomicReference<CanteenFrame>();
    var beforeCart = new AtomicReference<Rectangle>();
    var beforeQr = new AtomicReference<Rectangle>();
    ApiClient api = new ApiClient();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        CanteenFrame frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        JTabbedPane tabs = field(frame.seller, "tabs", JTabbedPane.class);
        JTable cart = field(frame.seller, "cartTable", JTable.class);
        JLabel qr = field(frame.seller, "qr", JLabel.class);
        frame.validate();
        beforeCart.set(boundsIn(cart, frame));
        tabs.setSelectedIndex(1);
        frame.validate();
        beforeQr.set(boundsIn(qr, frame));
        tabs.setSelectedIndex(0);
        frame.validate();

        String text = "Không kết nối được máy chủ. Vui lòng kiểm tra máy chủ và thử lại.";
        frame.error(new ApiClient.ApiError(503, "CONNECTION", text));
        frame.validate();
        Toast toast = Toast.forComponent(frame);
        assertTrue(toast.isShowing());
        assertEquals(frame.getLayeredPane().getHeight(), toast.getY(),
            "The toast must start below the window before sliding up");
      });
      awaitUi(() -> movingWithinWindow(window.get()));
      awaitUi(() -> settledAtBottom(window.get()));
      SwingUtilities.invokeAndWait(() -> {
        CanteenFrame frame = window.get();
        JTabbedPane tabs = field(frame.seller, "tabs", JTabbedPane.class);
        JTable cart = field(frame.seller, "cartTable", JTable.class);
        JLabel qr = field(frame.seller, "qr", JLabel.class);
        String text = "Không kết nối được máy chủ. Vui lòng kiểm tra máy chủ và thử lại.";
        Toast toast = Toast.forComponent(frame);
        JTextPane message = field(toast, "message", JTextPane.class);
        assertTrue(toast.isShowing());
        assertEquals(text, message.getText());
        assertEquals(TTM_CuoiKy.ERROR_TEXT_COLOR, message.getForeground());
        assertEquals(16f, message.getFont().getSize2D());
        assertEquals(StyleConstants.ALIGN_CENTER,
            StyleConstants.getAlignment(message.getStyledDocument().getParagraphElement(0).getAttributes()));
        JLayeredPane layer = frame.getLayeredPane();
        assertEquals(560, toast.getWidth());
        assertEquals((layer.getWidth() - toast.getWidth()) / 2, toast.getX());
        assertEquals(32, layer.getHeight() - toast.getY() - toast.getHeight());
        assertEquals(new Insets(9, 9, 9, 9), toast.getInsets());
        assertEquals(beforeCart.get(), boundsIn(cart, frame));
        assertFalse(toast.contains(10, 10), "Toast must not block the underlying controls");
        assertTrue(java.util.Arrays.stream(frame.getOwnedWindows()).noneMatch(Window::isShowing));
        int singleLineHeight = toast.getHeight();
        String longText = "Không kết nối được máy chủ. Vui lòng kiểm tra kết nối và thử lại. "
            + "Nếu lỗi vẫn còn, hãy kiểm tra máy chủ trước khi tiếp tục thao tác.";
        Toast.showMessage(frame, longText, true);
        frame.validate();
        assertTrue(toast.getHeight() > singleLineHeight, "A long message must wrap onto multiple lines");
        assertDoesNotThrow(() -> assertTrue(message.modelToView2D(longText.length() - 1).getMaxY()
            <= message.getHeight(), "The full message must remain visible"));
        assertEquals(32, layer.getHeight() - toast.getY() - toast.getHeight());
        assertEquals(beforeCart.get(), boundsIn(cart, frame));
        Toast.showMessage(frame, text, true);
        frame.validate();
        assertDoesNotThrow(() -> {
          Files.createDirectories(Path.of("target/ui-check"));
          BufferedImage capture = new BufferedImage(frame.getWidth(), frame.getHeight(),
              BufferedImage.TYPE_INT_RGB);
          Graphics graphics = capture.getGraphics();
          try { frame.paint(graphics); } finally { graphics.dispose(); }
          ImageIO.write(capture, "png", Path.of("target/ui-check/checkout-error-toast.png").toFile());
        });
        tabs.setSelectedIndex(1);
        frame.validate();
        assertEquals(beforeQr.get(), boundsIn(qr, frame));
        assertTrue(toast.isShowing());
        frame.error(new IllegalStateException());
        assertEquals("Chưa thực hiện được thao tác. Vui lòng thử lại.", message.getText());
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void toastSlidesDownAndRestartsWhenAnotherMessageArrives() throws Exception {
    var window = new AtomicReference<CanteenFrame>();
    var dialog = new AtomicReference<JDialog>();
    ApiClient api = new ApiClient();
    try {
      SwingUtilities.invokeAndWait(() -> {
        CanteenFrame frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setVisible(true);
        JDialog source = new JDialog(frame, "Kiểm tra");
        dialog.set(source);
        source.setSize(400, 240);
        source.setVisible(true);
        frame.error(source, new ApiClient.ApiError(503, "CONNECTION", "Lỗi trong cửa sổ"));
        Toast toast = Toast.forComponent(source);
        assertTrue(toast.isShowing());
        assertEquals("Lỗi trong cửa sổ", field(toast, "message", JTextPane.class).getText());
        assertEquals(3_000, field(toast, "timeout", Timer.class).getInitialDelay());
        assertEquals(source.getLayeredPane().getWidth() - 32, toast.getWidth());
        assertEquals(source.getLayeredPane().getHeight(), toast.getY());
      });
      awaitUi(() -> settledAtBottom(dialog.get()));
      SwingUtilities.invokeAndWait(() -> {
        Toast toast = Toast.forComponent(dialog.get());
        assertEquals(32, dialog.get().getLayeredPane().getHeight() - toast.getY() - toast.getHeight());
      });
      Thread.sleep(1_000);
      SwingUtilities.invokeAndWait(() -> Toast.showMessage(dialog.get(), "Lỗi mới", true));
      Thread.sleep(2_200);
      SwingUtilities.invokeAndWait(() -> {
        Toast toast = Toast.forComponent(dialog.get());
        assertTrue(toast.isShowing(), "A new message must restart the three-second timeout");
        assertEquals("Lỗi mới", field(toast, "message", JTextPane.class).getText());
      });
      awaitUi(() -> movingWithinWindow(dialog.get()));
      SwingUtilities.invokeAndWait(() -> {
        Toast.showMessage(dialog.get(), "Lỗi trong lúc đóng", true);
        assertTrue(Toast.forComponent(dialog.get()).isShowing());
      });
      awaitUi(() -> settledAtBottom(dialog.get()));
      Thread.sleep(2_000);
      SwingUtilities.invokeAndWait(() -> {
        Toast toast = Toast.forComponent(dialog.get());
        assertTrue(toast.isShowing());
        assertEquals("Lỗi trong lúc đóng", field(toast, "message", JTextPane.class).getText());
      });
      awaitUi(() -> !Toast.forComponent(dialog.get()).isVisible());
    } finally {
      SwingUtilities.invokeAndWait(() -> {
        if (dialog.get() != null) dialog.get().dispose();
        if (window.get() != null) window.get().dispose();
      });
    }
  }

  private static Rectangle boundsIn(Component component, Component ancestor) {
    return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), ancestor);
  }

  private static boolean movingWithinWindow(Component component) {
    Toast toast = Toast.forComponent(component);
    JLayeredPane layer = SwingUtilities.getRootPane(component).getLayeredPane();
    int rest = layer.getHeight() - toast.getHeight() - 32;
    return toast.isShowing() && toast.getY() > rest && toast.getY() < layer.getHeight();
  }

  private static boolean settledAtBottom(Component component) {
    Toast toast = Toast.forComponent(component);
    JLayeredPane layer = SwingUtilities.getRootPane(component).getLayeredPane();
    return toast.isShowing() && !field(toast, "slideTimer", Timer.class).isRunning()
        && layer.getHeight() - toast.getY() - toast.getHeight() == 32;
  }

  private static void awaitUi(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    AtomicBoolean ready = new AtomicBoolean();
    while (System.nanoTime() < deadline) {
      SwingUtilities.invokeAndWait(() -> ready.set(condition.getAsBoolean()));
      if (ready.get()) return;
      Thread.sleep(10);
    }
    fail("Timed out waiting for the toast animation");
  }

  private static <T> T field(Object target, String name, Class<T> type) {
    try {
      var field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return type.cast(field.get(target));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }
}
