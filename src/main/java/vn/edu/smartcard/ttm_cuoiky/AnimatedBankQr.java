package vn.edu.smartcard.ttm_cuoiky;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.parser.resources.ResourcePolicy;
import com.github.weisj.jsvg.renderer.animation.AnimationState;
import com.github.weisj.jsvg.renderer.awt.AwtComponentPlatformSupport;
import com.github.weisj.jsvg.renderer.output.Output;
import com.github.weisj.jsvg.view.ViewBox;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.detector.Detector;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.swing.*;

/** Draws a bank QR with an animated scan effect. */
final class AnimatedBankQr extends JLabel {
  private static final String SCAN_RESOURCE = "/icons/qr-code-scan.svg";
  private static final double FRAME_X = 41.07, FRAME_Y = 40.505;
  private static final double FRAME_WIDTH = 117.768, FRAME_HEIGHT = 118.981;
  private static final double FRAME_PADDING_MODULES = 1.5;
  private static final double FRAME_HALF_STROKE = 1.0;
  private static final SVGDocument SCAN = loadScan();

  private final Timer animation = new Timer(33, e -> repaint());
  private SwingWorker<Rectangle2D.Double, Void> detector;
  private Rectangle2D.Double scanBounds;
  private long imageRevision, startedNanos;

  AnimatedBankQr() {
    super("", SwingConstants.CENTER);
    addHierarchyListener(e -> {
      if ((e.getChangeFlags() & (HierarchyEvent.SHOWING_CHANGED | HierarchyEvent.DISPLAYABILITY_CHANGED)) != 0)
        updateAnimation();
    });
  }

  private static SVGDocument loadScan() {
    URL resource = AnimatedBankQr.class.getResource(SCAN_RESOURCE);
    if (resource == null) return null;
    try {
      LoaderContext context = LoaderContext.builder().externalResourcePolicy(ResourcePolicy.DENY_EXTERNAL).build();
      SVGDocument svg = new SVGLoader().load(resource, context);
      return svg != null && svg.isAnimated() ? svg : null;
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  @Override public void setIcon(Icon icon) {
    if (animation == null) {
      super.setIcon(icon); // JLabel's constructor calls this before our fields are initialized.
      return;
    }
    if (icon == getIcon()) return;
    imageRevision++;
    if (detector != null) detector.cancel(true);
    detector = null;
    scanBounds = null;
    animation.stop();
    super.setIcon(icon);
    if (SCAN == null || !(icon instanceof ImageIcon image)
        || image.getIconWidth() < 1 || image.getIconHeight() < 1) return;
    long revision = imageRevision;
    detector = new SwingWorker<>() {
      @Override protected Rectangle2D.Double doInBackground() { return findQrBounds(image); }
      @Override protected void done() {
        if (revision != imageRevision || getIcon() != image) return;
        detector = null;
        try {
          scanBounds = get();
        } catch (CancellationException | ExecutionException ignored) {
          scanBounds = null;
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          scanBounds = null;
        }
        updateAnimation();
        repaint();
      }
    };
    detector.execute();
  }

  private void updateAnimation() {
    boolean active = isShowing() && getIcon() instanceof ImageIcon && scanBounds != null;
    if (active && !animation.isRunning()) {
      startedNanos = System.nanoTime();
      animation.start();
    } else if (!active) {
      animation.stop();
    }
  }

  private static Rectangle2D.Double findQrBounds(ImageIcon icon) {
    int width = icon.getIconWidth(), height = icon.getIconHeight();
    if (width > 4096 || height > 4096 || (long) width * height > 8_000_000) return null;
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, width, height);
      graphics.drawImage(icon.getImage(), 0, 0, null);
    } finally {
      graphics.dispose();
    }
    try {
      var source = new RGBLuminanceSource(width, height, image.getRGB(0, 0, width, height, null, 0, width));
      var result = new Detector(new HybridBinarizer(source).getBlackMatrix()).detect();
      var points = result.getPoints();
      var bottomLeft = points[0];
      var topLeft = points[1];
      var topRight = points[2];
      int distance = result.getBits().getWidth() - 7;
      if (distance <= 0) return null;
      double moduleX = (topRight.getX() - topLeft.getX()) / distance;
      double moduleY = (bottomLeft.getY() - topLeft.getY()) / distance;
      if (moduleX <= 0 || moduleY <= 0
          || Math.abs(topRight.getY() - topLeft.getY()) > 2 * moduleY
          || Math.abs(bottomLeft.getX() - topLeft.getX()) > 2 * moduleX) return null;
      double left = topLeft.getX() - 3.5 * moduleX;
      double top = topLeft.getY() - 3.5 * moduleY;
      double right = topRight.getX() + 3.5 * moduleX;
      double bottom = bottomLeft.getY() + 3.5 * moduleY;
      double padding = FRAME_PADDING_MODULES * Math.max(moduleX, moduleY);
      return new Rectangle2D.Double(left - padding, top - padding,
          right - left + 2 * padding, bottom - top + 2 * padding);
    } catch (ReaderException | RuntimeException invalid) {
      return null;
    }
  }

  @Override protected void paintComponent(Graphics graphics) {
    if (!(getIcon() instanceof ImageIcon image)
        || image.getIconWidth() < 1 || image.getIconHeight() < 1) {
      super.paintComponent(graphics);
      return;
    }
    long elapsed = animation.isRunning()
        ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos) : 0;
    paintFrame((Graphics2D) graphics, elapsed);
  }

  void paintFrame(Graphics2D graphics, long elapsedMillis) {
    if (!(getIcon() instanceof ImageIcon image)
        || image.getIconWidth() < 1 || image.getIconHeight() < 1) return;
    double scale = Math.min((double) getWidth() / image.getIconWidth(),
        (double) getHeight() / image.getIconHeight());
    int width = (int) (image.getIconWidth() * scale), height = (int) (image.getIconHeight() * scale);
    int left = (getWidth() - width) / 2, top = (getHeight() - height) / 2;
    Graphics2D copy = (Graphics2D) graphics.create();
    try {
      copy.setColor(Color.WHITE);
      copy.fillRect(0, 0, getWidth(), getHeight());
      copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
      copy.drawImage(image.getImage(), left, top, width, height, this);
      if (SCAN == null || scanBounds == null) return;
      double sx = (double) width / image.getIconWidth(), sy = (double) height / image.getIconHeight();
      double frameScaleX = scanBounds.width * sx / FRAME_WIDTH;
      double frameScaleY = scanBounds.height * sy / FRAME_HEIGHT;
      // The SVG stroke is centered on its frame boundary; include its outer half and antialiasing.
      double clipPaddingX = FRAME_HALF_STROKE * frameScaleX + 1;
      double clipPaddingY = FRAME_HALF_STROKE * frameScaleY + 1;
      Graphics2D overlay = (Graphics2D) copy.create();
      try {
        overlay.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        overlay.clip(new Rectangle2D.Double(left + scanBounds.x * sx - clipPaddingX,
            top + scanBounds.y * sy - clipPaddingY,
            scanBounds.width * sx + 2 * clipPaddingX, scanBounds.height * sy + 2 * clipPaddingY));
        overlay.translate(left + scanBounds.x * sx, top + scanBounds.y * sy);
        overlay.scale(frameScaleX, frameScaleY);
        overlay.translate(-FRAME_X, -FRAME_Y);
        Output output = Output.createForGraphics(overlay);
        try {
          SCAN.renderWithPlatform(new AwtComponentPlatformSupport(this), output,
              new ViewBox(0, 0, 200, 200), new AnimationState(0, Math.max(0, elapsedMillis)));
        } catch (RuntimeException invalid) {
          scanBounds = null;
          animation.stop();
        } finally {
          output.dispose();
        }
      } finally {
        overlay.dispose();
      }
    } finally {
      copy.dispose();
    }
  }
}
