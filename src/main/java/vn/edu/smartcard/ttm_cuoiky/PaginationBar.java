package vn.edu.smartcard.ttm_cuoiky;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.IntConsumer;
import javax.swing.JButton;
import javax.swing.Icon;
import javax.swing.JPanel;

/** Five numbered slots with a stable window while navigating back and forth. */
final class PaginationBar extends JPanel {
  private static final int SLOTS = 5;
  private static final Color SELECTED = new Color(0x606060);
  private final JButton previous = arrow("/icons/previous-svgrepo-com.svg", "Trang trước");
  private final JButton next = arrow("/icons/next-svgrepo-com.svg", "Trang sau");
  private final JButton[] numbers = new JButton[SLOTS];
  private final int[] targets = new int[SLOTS];
  private final IntConsumer changePage;
  private int current, totalPages, windowStart = 1;

  PaginationBar(IntConsumer changePage) {
    super(new GridBagLayout());
    this.changePage = changePage;
    setOpaque(false);
    getAccessibleContext().setAccessibleName("Phân trang danh sách thẻ");
    previous.addActionListener(e -> {
      if (current > 1) changePage.accept(current - 2);
    });
    next.addActionListener(e -> {
      if (current < totalPages) changePage.accept(current);
    });
    GridBagConstraints layout = new GridBagConstraints();
    layout.gridx = 0;
    layout.gridy = 0;
    layout.weightx = 1;
    layout.anchor = GridBagConstraints.EAST;
    layout.insets = new Insets(0, 0, 0, 4);
    add(previous, layout);
    layout.weightx = 0;
    for (int i = 0; i < numbers.length; i++) {
      int slot = i;
      numbers[i] = button("", "Trang");
      numbers[i].addActionListener(e -> {
        int target = targets[slot];
        if (target > 0 && target != current) changePage.accept(target - 1);
      });
      layout.gridx = i + 1;
      add(numbers[i], layout);
    }
    layout.gridx = SLOTS + 1;
    layout.insets = new Insets(0, 0, 0, 0);
    add(next, layout);
    setPage(0, 0, 20, false);
  }

  void setPage(int pageIndex, int total, int pageSize, boolean pending) {
    totalPages = total <= 0 || pageSize <= 0 ? 0 : (int) (((long) total + pageSize - 1) / pageSize);
    current = totalPages == 0 ? 0 : Math.max(1, Math.min(pageIndex + 1, totalPages));
    adjustWindow();
    int[] visible = visiblePages();
    previous.setEnabled(!pending && current > 1);
    next.setEnabled(!pending && current > 0 && current < totalPages);
    for (int i = 0; i < numbers.length; i++) {
      JButton button = numbers[i];
      boolean shown = i < visible.length;
      button.setVisible(shown);
      targets[i] = shown ? visible[i] : 0;
      if (!shown) continue;
      int target = targets[i];
      boolean selected = target == current && target > 0;
      button.setText(target == 0 ? "..." : Integer.toString(target));
      button.setEnabled(!pending && target > 0);
      button.setBackground(selected ? SELECTED : Color.WHITE);
      button.setForeground(selected ? Color.WHITE : TTM_CuoiKy.DEFAULT_TEXT_COLOR);
      button.setFont(button.getFont().deriveFont(selected ? Font.BOLD : Font.PLAIN, 16f));
      String name = target == 0 ? "Các trang ở giữa" : "Trang " + target + (selected ? ", hiện tại" : "");
      button.getAccessibleContext().setAccessibleName(name);
      button.setToolTipText(target == 0 ? null : name);
    }
    revalidate();
    repaint();
  }

  private void adjustWindow() {
    if (totalPages <= SLOTS) {
      windowStart = 1;
      return;
    }
    int lastWindow = totalPages - 4;
    windowStart = Math.max(1, Math.min(windowStart, lastWindow));
    int windowEnd = windowStart == lastWindow ? totalPages : windowStart + 2;
    if (current < windowStart) windowStart = current;
    else if (current > windowEnd) windowStart = Math.min(current - 2, lastWindow);
    windowStart = Math.max(1, Math.min(windowStart, lastWindow));
  }

  private int[] visiblePages() {
    if (totalPages <= SLOTS) {
      int[] visible = new int[totalPages];
      for (int i = 0; i < visible.length; i++) visible[i] = i + 1;
      return visible;
    }
    if (windowStart == totalPages - 4) {
      return new int[] {windowStart, windowStart + 1, windowStart + 2, windowStart + 3, totalPages};
    }
    return new int[] {windowStart, windowStart + 1, windowStart + 2, 0, totalPages};
  }

  private static JButton button(String text, String accessibleName) {
    JButton button = new JButton(text);
    Dimension size = new Dimension(40, 40);
    button.setPreferredSize(size);
    button.setMinimumSize(size);
    button.setMaximumSize(size);
    button.setMargin(new Insets(0, 0, 0, 0));
    // FlatLaf's arc is the corner diameter: 8 gives a 4 px corner radius.
    button.putClientProperty(FlatClientProperties.STYLE, "arc: 8");
    button.setAlignmentY(Component.CENTER_ALIGNMENT);
    button.getAccessibleContext().setAccessibleName(accessibleName);
    button.setToolTipText(accessibleName);
    return button;
  }

  private static JButton arrow(String path, String accessibleName) {
    JButton button = button("", accessibleName);
    Icon icon = DesktopIcons.svg(path, 12, accessibleName);
    button.setIcon(icon);
    button.setDisabledIcon(new Icon() {
      @Override public int getIconWidth() { return icon.getIconWidth(); }
      @Override public int getIconHeight() { return icon.getIconHeight(); }
      @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D canvas = (Graphics2D) graphics.create();
        try {
          canvas.setComposite(AlphaComposite.SrcOver.derive(0.4f));
          icon.paintIcon(component, canvas, x, y);
        } finally {
          canvas.dispose();
        }
      }
    });
    return button;
  }
}
