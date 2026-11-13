package vn.edu.smartcard.ttm_cuoiky;

import static org.junit.jupiter.api.Assertions.*;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import vn.edu.smartcard.shared.Api;

class AnimatedBankQrTest {
  @Test
  void scanFrameKeepsTheFullStrokeAroundAllFourCorners() throws Exception {
    var view = new AtomicReference<AnimatedBankQr>();
    BufferedImage image = qr("INVOICE-CORNER-CHECK");
    SwingUtilities.invokeAndWait(() -> {
      AnimatedBankQr label = new AnimatedBankQr();
      label.setSize(420, 480);
      label.setIcon(new ImageIcon(image));
      view.set(label);
    });
    awaitUi(() -> field(view.get(), "scanBounds", Rectangle2D.Double.class) != null);
    Rectangle2D.Double bounds = field(view.get(), "scanBounds", Rectangle2D.Double.class);
    double scale = Math.min(420.0 / image.getWidth(), 480.0 / image.getHeight());
    int width = (int) (image.getWidth() * scale), height = (int) (image.getHeight() * scale);
    double left = (420 - width) / 2 + bounds.x * width / image.getWidth();
    double top = (480 - height) / 2 + bounds.y * height / image.getHeight();
    double frameWidth = bounds.width * width / image.getWidth();
    double frameHeight = bounds.height * height / image.getHeight();
    double unitX = frameWidth / 117.768, unitY = frameHeight / 118.981;
    BufferedImage painted = render(view.get(), 625);
    for (double x : new double[] {left + 20 * unitX, left + frameWidth - 20 * unitX}) {
      for (double y : new double[] {top, top + frameHeight}) {
        assertBlack(painted, (int) Math.round(x), (int) Math.round(y) - 1);
        assertBlack(painted, (int) Math.round(x), (int) Math.round(y) + 1);
      }
    }
    for (double x : new double[] {left, left + frameWidth}) {
      for (double y : new double[] {top + 18 * unitY, top + frameHeight - 18 * unitY}) {
        assertBlack(painted, (int) Math.round(x) - 1, (int) Math.round(y));
        assertBlack(painted, (int) Math.round(x) + 1, (int) Math.round(y));
      }
    }
  }

  @Test
  void blackAndRedScanMovesWithoutObscuringBrandingOrPreventingQrReads() throws Exception {
    String expected = "INVOICE-QR-TEST-" + "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(4);
    BufferedImage qr = qr(expected);
    BufferedImage branded = new BufferedImage(420, 520, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = branded.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, branded.getWidth(), branded.getHeight());
      graphics.setColor(Color.BLACK);
      graphics.drawString("NGAN HANG MAU", 130, 35);
      graphics.drawImage(qr, 30, 60, 360, 360, null);
      graphics.drawString("THONG TIN HOA DON", 130, 465);
    } finally {
      graphics.dispose();
    }

    var window = new AtomicReference<JFrame>();
    var view = new AtomicReference<AnimatedBankQr>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        JFrame frame = new JFrame();
        window.set(frame);
        AnimatedBankQr label = new AnimatedBankQr();
        label.setPreferredSize(new Dimension(420, 480));
        frame.setContentPane(label);
        frame.pack();
        frame.setLocation(-3000, -3000);
        frame.setVisible(true);
        view.set(label);
      });
      for (BufferedImage image : new BufferedImage[] {qr, branded}) {
        SwingUtilities.invokeAndWait(() -> view.get().setIcon(new ImageIcon(image)));
        awaitUi(() -> field(view.get(), "scanBounds", Rectangle2D.Double.class) != null);
        assertTrue(field(view.get(), "animation", Timer.class).isRunning());
        BufferedImage baseline = plainImage(image);
        int start = -1, halfway = -1;
        for (int time = 0; time < 2500; time += 50) {
          BufferedImage rendered = render(view.get(), time);
          assertEquals(expected, decode(rendered), "QR must remain readable at " + time + " ms");
          int redY = meanRedY(rendered);
          assertTrue(redY >= 0, "The scan line must be red");
          assertTrue(hasBlackFrame(rendered, baseline), "The frame must be black");
          if (image == branded) {
            for (int y = 0; y < 40; y++) for (int x = 0; x < 420; x++)
              assertEquals(baseline.getRGB(x, y), rendered.getRGB(x, y), "Header must remain visible");
            for (int y = 420; y < 480; y++) for (int x = 0; x < 420; x++)
              assertEquals(baseline.getRGB(x, y), rendered.getRGB(x, y), "Details must remain visible");
          }
          if (time == 0) start = redY;
          if (time == 1250) halfway = redY;
        }
        assertTrue(Math.abs(start - halfway) > 80, "The red line must move across the QR");
      }
      SwingUtilities.invokeAndWait(() -> {
        window.get().setVisible(false);
        assertFalse(field(view.get(), "animation", Timer.class).isRunning());
        window.get().setVisible(true);
        assertTrue(field(view.get(), "animation", Timer.class).isRunning());
        view.get().setIcon(null);
        assertFalse(field(view.get(), "animation", Timer.class).isRunning());
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void replacingQrWithAnotherImageDoesNotReuseAnOldScanEffect() throws Exception {
    var view = new AtomicReference<AnimatedBankQr>();
    BufferedImage oldQr = qr("PREVIOUS-INVOICE");
    BufferedImage blank = new BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = blank.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, blank.getWidth(), blank.getHeight());
    } finally {
      graphics.dispose();
    }
    SwingUtilities.invokeAndWait(() -> {
      AnimatedBankQr label = new AnimatedBankQr();
      label.setSize(420, 480);
      label.setIcon(new ImageIcon(oldQr));
      label.setIcon(new ImageIcon(blank));
      view.set(label);
    });
    awaitUi(() -> field(view.get(), "detector", SwingWorker.class) == null);
    SwingUtilities.invokeAndWait(() -> {
      assertNull(field(view.get(), "scanBounds", Rectangle2D.Double.class));
      assertFalse(field(view.get(), "animation", Timer.class).isRunning());
      view.get().setIcon(new ImageIcon(new byte[0]));
      BufferedImage canvas = new BufferedImage(420, 480, BufferedImage.TYPE_INT_RGB);
      Graphics2D target = canvas.createGraphics();
      try { assertDoesNotThrow(() -> view.get().paint(target)); } finally { target.dispose(); }
    });
  }

  @Test
  void checkoutReturnsFromBankQrAndShowsToastWhenInvoiceExpires() throws Exception {
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(qr("INVOICE-QR-UI-TEST"), "png", bytes);
    String image = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
    long now = System.currentTimeMillis();
    var open = new Api.Invoice(7, "HD00000007", "", "", "", 35000, "OPEN",
        List.of(new Api.InvoiceLine("Cơm gà", 1, 35000, 35000)), now, now + 300000,
        image, "", 1, "fixture-session", "TTAT00000000000007", "", "NONE");
    var expired = new Api.Invoice(7, open.number(), "", "", "", 35000, "CANCELLED",
        open.items(), now, now + 300000, "", "QR_EXPIRED", 2, "fixture-session",
        open.paymentReference(), "", "FINISHED");
    ApiClient api = new ApiClient();
    var window = new AtomicReference<CanteenFrame>();
    var label = new AtomicReference<AnimatedBankQr>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        CanteenFrame frame = new CanteenFrame(api, true, false);
        window.set(frame);
        frame.setLocation(-3000, -3000);
        frame.setVisible(true);
        field(frame.seller, "tabs", JTabbedPane.class).setSelectedIndex(1);
        invokeInvoice(frame.seller, open);
        label.set(field(frame.seller, "qr", AnimatedBankQr.class));
        assertNotNull(label.get().getIcon());
        field(frame.seller, "customerBank", JButton.class).doClick(0);
        assertTrue(field(frame.seller, "showingQrPage", Boolean.class));
      });
      awaitUi(() -> field(label.get(), "animation", Timer.class).isRunning());
      SwingUtilities.invokeAndWait(() -> {
        CanteenFrame frame = window.get();
        invokeInvoice(frame.seller, expired);
        assertNull(label.get().getIcon());
        assertFalse(field(label.get(), "animation", Timer.class).isRunning());
        assertFalse(field(frame.seller, "showingQrPage", Boolean.class));
        assertEquals("Hoá đơn đã hết hạn.",
            field(Toast.forComponent(frame), "message", JTextPane.class).getText());
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  @Test
  void studentTopupUsesTheScanAndStopsItWhenLeavingThePage() throws Exception {
    String expected = "NTCT07035200000077";
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(qr(expected), "png", bytes);
    String image = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
    var topup = new Api.Topup(77, expected, "TTM000001", 10000, "WAITING_BANK", image, "Fixture");
    var offer = new Api.TopupQr(topup, 300_000);
    ApiClient api = new ApiClient();
    var window = new AtomicReference<CanteenFrame>();
    var student = new AtomicReference<StudentView>();
    var label = new AtomicReference<AnimatedBankQr>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        TTM_CuoiKy.configureLookAndFeel();
        CanteenFrame frame = new CanteenFrame(api, false, false);
        window.set(frame);
        StudentView view = new StudentView(frame, id -> topup,
            id -> new Api.TopupCancellation("EXPIRED", "Fixture"));
        student.set(view);
        field(frame, "tabs", JTabbedPane.class).setComponentAt(0, view);
        frame.setLocation(-3000, -3000);
        frame.setVisible(true);
        invoke(view, "showQr", Api.TopupQr.class, offer);
        label.set(assertInstanceOf(AnimatedBankQr.class, field(view, "qrImage", JLabel.class)));
        assertNotNull(label.get().getIcon());
      });
      awaitUi(() -> field(label.get(), "animation", Timer.class).isRunning());
      for (int time = 0; time < 2500; time += 250) {
        BufferedImage frame = new BufferedImage(label.get().getWidth(), label.get().getHeight(),
            BufferedImage.TYPE_INT_RGB);
        int elapsed = time;
        SwingUtilities.invokeAndWait(() -> {
          Graphics2D graphics = frame.createGraphics();
          try { label.get().paintFrame(graphics, elapsed); } finally { graphics.dispose(); }
        });
        assertEquals(expected, decode(frame));
      }
      SwingUtilities.invokeAndWait(() -> {
        JTabbedPane tabs = field(window.get(), "tabs", JTabbedPane.class);
        tabs.setSelectedIndex(1);
        assertFalse(field(label.get(), "animation", Timer.class).isRunning());
        tabs.setSelectedIndex(0);
        assertTrue(field(label.get(), "animation", Timer.class).isRunning());
        invoke(student.get(), "closeQr", null, null);
        assertFalse(field(label.get(), "animation", Timer.class).isRunning());
        assertNull(label.get().getIcon());
      });
    } finally {
      if (window.get() != null) SwingUtilities.invokeAndWait(window.get()::dispose);
    }
  }

  private static BufferedImage qr(String value) throws Exception {
    var matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 360, 360,
        Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 4));
    BufferedImage result = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < result.getHeight(); y++)
      for (int x = 0; x < result.getWidth(); x++) result.setRGB(x, y, matrix.get(x, y) ? 0xff000000 : 0xffffffff);
    return result;
  }

  private static BufferedImage render(AnimatedBankQr label, long time) throws Exception {
    BufferedImage result = new BufferedImage(420, 480, BufferedImage.TYPE_INT_RGB);
    SwingUtilities.invokeAndWait(() -> {
      Graphics2D graphics = result.createGraphics();
      try { label.paintFrame(graphics, time); } finally { graphics.dispose(); }
    });
    return result;
  }

  private static BufferedImage plainImage(BufferedImage image) {
    BufferedImage result = new BufferedImage(420, 480, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = result.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, 420, 480);
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
      double scale = Math.min(420.0 / image.getWidth(), 480.0 / image.getHeight());
      int width = (int) (image.getWidth() * scale), height = (int) (image.getHeight() * scale);
      graphics.drawImage(image, (420 - width) / 2, (480 - height) / 2, width, height, null);
    } finally {
      graphics.dispose();
    }
    return result;
  }

  private static int meanRedY(BufferedImage image) {
    long total = 0, count = 0;
    for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
      int rgb = image.getRGB(x, y);
      int red = rgb >> 16 & 255, green = rgb >> 8 & 255, blue = rgb & 255;
      if (red > 90 && red - green > 50 && red - blue > 50) { total += y; count++; }
    }
    return count == 0 ? -1 : (int) (total / count);
  }

  private static boolean hasBlackFrame(BufferedImage image, BufferedImage baseline) {
    for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
      if ((image.getRGB(x, y) & 0xffffff) == 0 && (baseline.getRGB(x, y) & 0xffffff) == 0xffffff)
        return true;
    return false;
  }

  private static void assertBlack(BufferedImage image, int x, int y) {
    Color color = new Color(image.getRGB(x, y));
    assertTrue(color.getRed() < 50 && color.getGreen() < 50 && color.getBlue() < 50,
        "The complete black frame must remain visible at " + x + ", " + y);
  }

  private static void awaitUi(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      var result = new java.util.concurrent.atomic.AtomicBoolean();
      SwingUtilities.invokeAndWait(() -> result.set(condition.getAsBoolean()));
      if (result.get()) return;
      Thread.sleep(20);
    }
    fail("Timed out waiting for the scan animation");
  }

  private static String decode(BufferedImage image) throws Exception {
    var source = new RGBLuminanceSource(image.getWidth(), image.getHeight(),
        image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
    return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
  }

  private static void invokeInvoice(CheckoutView seller, Api.Invoice invoice) {
    try {
      var accept = CheckoutView.class.getDeclaredMethod("accept", Api.Invoice.class);
      accept.setAccessible(true);
      accept.invoke(seller, invoice);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void invoke(Object target, String name, Class<?> parameter, Object value) {
    try {
      var method = parameter == null ? target.getClass().getDeclaredMethod(name)
          : target.getClass().getDeclaredMethod(name, parameter);
      method.setAccessible(true);
      if (parameter == null) method.invoke(target); else method.invoke(target, value);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static <T> T field(Object target, String name, Class<T> type) {
    try {
      var value = target.getClass().getDeclaredField(name);
      value.setAccessible(true);
      return type.cast(value.get(target));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }
}
