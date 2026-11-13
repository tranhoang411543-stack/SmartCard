package vn.edu.smartcard.ttm_cuoiky;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import vn.edu.smartcard.shared.Api;

final class DeviceSearch extends JTextField {
  private static final int MAX_ROWS = 10;
  private static final String PLACEHOLDER = "Tìm kiếm thẻ theo ID";
  private static final String INPUT_HINT = "Nhập ID thẻ mô phỏng (ví dụ SIM0001) hoặc tên đầu đọc.";
  private final DefaultListModel<Api.DeviceChoice> matches = new DefaultListModel<>();
  private final JList<Api.DeviceChoice> results = new JList<>(matches);
  private final JPopupMenu popup = new JPopupMenu();
  private Api.DeviceChoice[] choices = new Api.DeviceChoice[0];
  private Api.DeviceChoice selected;
  private boolean selecting;
  private Runnable selectionChanged = () -> {};

  DeviceSearch() {
    setPreferredSize(new Dimension(384, 32));
    putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, PLACEHOLDER);
    getAccessibleContext().setAccessibleName(PLACEHOLDER);
    setToolTipText(INPUT_HINT);

    results.setFocusable(false);
    results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    results.setCellRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean selected, boolean focus) {
            return super.getListCellRendererComponent(
                list, ((Api.DeviceChoice) value).label(), index, selected, focus);
          }
        });
    JScrollPane scroll = new JScrollPane(results);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.setFocusable(false);
    scroll.getVerticalScrollBar().setFocusable(false);
    scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    popup.setFocusable(false);
    popup.add(scroll);

    getDocument()
        .addDocumentListener(
            new DocumentListener() {
              public void insertUpdate(DocumentEvent e) {
                queryChanged();
              }

              public void removeUpdate(DocumentEvent e) {
                queryChanged();
              }

              public void changedUpdate(DocumentEvent e) {
                queryChanged();
              }
            });
    addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusGained(FocusEvent e) {
            showMatches();
          }

          @Override
          public void focusLost(FocusEvent e) {
            popup.setVisible(false);
          }
        });
    results.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            int index = results.locationToIndex(e.getPoint());
            if (SwingUtilities.isLeftMouseButton(e)
                && index >= 0
                && results.getCellBounds(index, index).contains(e.getPoint())) {
              select(matches.get(index));
            }
          }
        });
    addKeyListener(
        new KeyAdapter() {
          @Override
          public void keyPressed(KeyEvent e) {
            if (!popup.isVisible()) {
              return;
            }
            switch (e.getKeyCode()) {
              case KeyEvent.VK_UP -> moveSelection(-1);
              case KeyEvent.VK_DOWN -> moveSelection(1);
              case KeyEvent.VK_ENTER -> select(results.getSelectedValue());
              case KeyEvent.VK_ESCAPE -> popup.setVisible(false);
              default -> {
                return;
              }
            }
            e.consume();
          }
        });
  }

  void setChoices(Api.DeviceChoice[] available, String preferredId) {
    choices = available;
    setToolTipText(INPUT_HINT);
    String id = preferredId;
    if (id == null && selected != null) {
      id = selected.id();
    }
    for (var choice : choices) {
      if (choice.id().equals(id)) {
        select(choice);
        return;
      }
    }
    if (selected != null) {
      select(null);
    } else {
      showMatches();
    }
  }

  void addChoice(Api.DeviceChoice choice) {
    var updated = new ArrayList<>(Arrays.asList(choices));
    updated.removeIf(existing -> existing.id().equals(choice.id()));
    updated.add(choice);
    setChoices(updated.toArray(Api.DeviceChoice[]::new), choice.id());
  }

  Api.DeviceChoice selection() {
    return selected;
  }

  void onSelectionChanged(Runnable action) {
    selectionChanged = action == null ? () -> {} : action;
  }

  private void queryChanged() {
    if (!selecting) {
      boolean changed = selected != null;
      selected = null;
      showMatches();
      if (changed) selectionChanged.run();
    }
  }

  private void showMatches() {
    String query = getText().strip().toLowerCase(Locale.ROOT);
    if (query.isEmpty() || selected != null || !isShowing() || !isFocusOwner()) {
      popup.setVisible(false);
      return;
    }
    matches.clear();
    matches.addAll(
        Arrays.stream(choices)
            .filter(choice -> choice.id().toLowerCase(Locale.ROOT).contains(query))
            .toList());
    if (matches.isEmpty()) {
      popup.setVisible(false);
      return;
    }
    results.setVisibleRowCount(Math.min(MAX_ROWS, matches.getSize()));
    results.setSelectedIndex(0);
    results.ensureIndexIsVisible(0);
    popup.setPreferredSize(null);
    Dimension size = popup.getPreferredSize();
    size.width = Math.max(size.width, getWidth());
    popup.setPreferredSize(size);
    if (popup.isVisible()) {
      popup.pack();
    } else {
      popup.show(this, 0, getHeight());
    }
  }

  private void moveSelection(int step) {
    int index = Math.clamp(results.getSelectedIndex() + step, 0, matches.getSize() - 1);
    results.setSelectedIndex(index);
    results.ensureIndexIsVisible(index);
  }

  private void select(Api.DeviceChoice choice) {
    Api.DeviceChoice previous = selected;
    selecting = true;
    try {
      setText(choice == null ? "" : choice.id());
      setCaretPosition(getDocument().getLength());
      selected = choice;
      popup.setVisible(false);
    } finally {
      selecting = false;
    }
    if (!java.util.Objects.equals(previous, selected)) selectionChanged.run();
  }
}
