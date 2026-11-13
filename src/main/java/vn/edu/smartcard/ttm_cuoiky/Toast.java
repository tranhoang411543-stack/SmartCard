package vn.edu.smartcard.ttm_cuoiky;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.*;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

/** Temporary feedback above the current window, without changing its content layout. */
final class Toast extends JPanel {
  private enum Tone { INFO, ERROR, SUCCESS }

  private static final String ROOT_PROPERTY = Toast.class.getName();
  private static final int DURATION_MS = 3_000;
  private static final int SLIDE_DURATION_MS = 220;
  private static final int SLIDE_FRAME_MS = 16;
  private static final int MARGIN = 16;
  private static final int BOTTOM_MARGIN = 32;
  private static final int MAX_WIDTH = 560;

  private final JLayeredPane layer;
  private final JTextPane message = new JTextPane();
  private final Timer timeout = new Timer(DURATION_MS, event -> slideTo(1));
  private final Timer slideTimer = new Timer(SLIDE_FRAME_MS, event -> advanceSlide());
  private double slideProgress = 1;
  private double slideFrom, slideTarget;
  private long slideStarted;

  private Toast(JRootPane root) {
    super(new BorderLayout());
    layer = root.getLayeredPane();
    setFocusable(false);
    setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(new Color(0xE57373)),
        BorderFactory.createEmptyBorder(8, 8, 8, 8)));
    message.setFont(UIManager.getFont("Label.font").deriveFont(16f));
    message.setBorder(null);
    message.setMargin(new Insets(0, 0, 0, 0));
    message.setOpaque(false);
    message.setEditable(false);
    message.setFocusable(false);
    add(message);
    timeout.setRepeats(false);
    setVisible(false);
    layer.add(this, JLayeredPane.POPUP_LAYER);
    layer.addComponentListener(new ComponentAdapter() {
      @Override public void componentResized(ComponentEvent event) {
        if (isVisible()) position();
      }
    });
  }

  static Toast forComponent(Component component) {
    JRootPane root = SwingUtilities.getRootPane(component);
    if (root == null) return null;
    Toast toast = (Toast) root.getClientProperty(ROOT_PROPERTY);
    if (toast == null) {
      toast = new Toast(root);
      root.putClientProperty(ROOT_PROPERTY, toast);
    }
    return toast;
  }

  static void showMessage(Component component, String text, boolean error) {
    showMessage(component, text, error ? Tone.ERROR : Tone.INFO);
  }

  static void showSuccess(Component component, String text) {
    showMessage(component, text, Tone.SUCCESS);
  }

  private static void showMessage(Component component, String text, Tone tone) {
    if (text == null || text.isBlank()) return;
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(() -> showMessage(component, text, tone));
      return;
    }
    Toast toast = forComponent(component);
    if (toast == null) return;
    toast.message.setText(text);
    SimpleAttributeSet alignment = new SimpleAttributeSet();
    StyleConstants.setAlignment(alignment, StyleConstants.ALIGN_CENTER);
    toast.message.getStyledDocument().setParagraphAttributes(0,
        toast.message.getDocument().getLength(), alignment, false);
    toast.message.setForeground(switch (tone) {
      case ERROR -> TTM_CuoiKy.ERROR_TEXT_COLOR;
      case SUCCESS -> new Color(0x1B5E20);
      case INFO -> TTM_CuoiKy.DEFAULT_TEXT_COLOR;
    });
    toast.message.getAccessibleContext().setAccessibleName(text);
    toast.setBackground(switch (tone) {
      case ERROR -> new Color(0xFFF1F0);
      case SUCCESS -> new Color(0xE8F5E9);
      case INFO -> new Color(0xEEF5FC);
    });
    toast.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(switch (tone) {
          case ERROR -> new Color(0xE57373);
          case SUCCESS -> new Color(0x81C784);
          case INFO -> new Color(0x7FA9D0);
        }),
        BorderFactory.createEmptyBorder(8, 8, 8, 8)));
    if (!toast.isVisible()) toast.slideProgress = 1;
    toast.position();
    toast.setVisible(true);
    toast.layer.moveToFront(toast);
    toast.slideTo(0);
    toast.timeout.restart();
    toast.repaint();
  }

  private void position() {
    int width = Math.min(MAX_WIDTH, Math.max(1, layer.getWidth() - 2 * MARGIN));
    Insets insets = getInsets();
    message.setSize(Math.max(1, width - insets.left - insets.right), Short.MAX_VALUE);
    int height = message.getPreferredSize().height + insets.top + insets.bottom;
    int y = Math.max(MARGIN, layer.getHeight() - height - BOTTOM_MARGIN);
    y += (int) Math.round(slideProgress * Math.max(0, layer.getHeight() - y));
    setBounds(Math.max(0, (layer.getWidth() - width) / 2), y, width, height);
    revalidate();
  }

  private void slideTo(double target) {
    slideTimer.stop();
    if (slideProgress == target) {
      position();
      if (target == 1) setVisible(false);
      return;
    }
    slideFrom = slideProgress;
    slideTarget = target;
    slideStarted = System.nanoTime();
    slideTimer.start();
  }

  private void advanceSlide() {
    double elapsed = (System.nanoTime() - slideStarted) / 1_000_000.0;
    double fraction = Math.min(1, elapsed / SLIDE_DURATION_MS);
    double eased = slideTarget == 0 ? 1 - Math.pow(1 - fraction, 3) : fraction * fraction;
    slideProgress = slideFrom + (slideTarget - slideFrom) * eased;
    position();
    if (fraction >= 1) {
      slideTimer.stop();
      if (slideTarget == 1) setVisible(false);
    }
  }

  @Override public boolean contains(int x, int y) {
    return false; // Feedback does not intercept clicks on the underlying screen.
  }

  @Override public void removeNotify() {
    timeout.stop();
    slideTimer.stop();
    slideProgress = 1;
    setVisible(false);
    super.removeNotify();
  }
}
