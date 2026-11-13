package vn.edu.smartcard.ttm_cuoiky;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseWheelEvent;
import javax.swing.*;

final class WaitOverlay extends JPanel {
  private static final Color VEIL = new Color(245, 245, 245, 215);

  private final JLabel message = new JLabel();
  private final KeyEventDispatcher blockKeys = event -> isShowing()
      && SwingUtilities.getWindowAncestor(event.getComponent()) == SwingUtilities.getWindowAncestor(this);

  WaitOverlay() {
    super(new GridBagLayout());
    setOpaque(false);
    setVisible(false);
    setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    message.setFont(message.getFont().deriveFont(Font.BOLD, 17f));
    add(message);

    MouseAdapter blockMouse = new MouseAdapter() {};
    addMouseListener(blockMouse);
    addMouseMotionListener(blockMouse);
    addMouseWheelListener(MouseWheelEvent::consume);
  }

  void showMessage(String text) {
    message.setText(text);
    if (!isVisible()) {
      KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(blockKeys);
      setVisible(true);
    }
  }

  void hideMessage() {
    KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(blockKeys);
    setVisible(false);
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    super.paintComponent(graphics);
    graphics.setColor(VEIL);
    graphics.fillRect(0, 0, getWidth(), getHeight());
  }

  @Override
  public void removeNotify() {
    hideMessage();
    super.removeNotify();
  }
}
