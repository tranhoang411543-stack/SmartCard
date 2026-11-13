package vn.edu.smartcard.ttm_cuoiky;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.FocusEvent;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultCaret;
import javax.swing.text.JTextComponent;

final class DesktopTextFocus {
  static final String POPUP_INPUT_GROUP = "ttm.popupInputGroup";
  private static final PropertyChangeListener EDITABILITY = DesktopTextFocus::editabilityChanged;
  private static boolean installed;

  private DesktopTextFocus() {}

  static synchronized void install() {
    if (installed) return;
    KeyboardFocusManager.getCurrentKeyboardFocusManager()
        .addPropertyChangeListener("focusOwner", DesktopTextFocus::focusChanged);
    Toolkit.getDefaultToolkit().addAWTEventListener(DesktopTextFocus::mouseClicked,
        AWTEvent.MOUSE_EVENT_MASK);
    installed = true;
  }

  private static void focusChanged(PropertyChangeEvent event) {
    if (event.getOldValue() instanceof JTextComponent previous) {
      previous.removePropertyChangeListener("editable", EDITABILITY);
    }
    if (event.getNewValue() instanceof JTextComponent field) {
      field.addPropertyChangeListener("editable", EDITABILITY);
      // Java 25 activates a solid caret in read-only fields on focus gain.
      EventQueue.invokeLater(() -> {
        if (field.isFocusOwner() && !field.isEditable()) field.getCaret().setVisible(false);
      });
    }
  }

  private static void editabilityChanged(PropertyChangeEvent event) {
    JTextComponent field = (JTextComponent) event.getSource();
    if (!field.isFocusOwner()) return;
    boolean editing = field.isEditable() && field.isEnabled();
    if (editing && field.getCaret() instanceof DefaultCaret caret) {
      // Let Swing restore the blink rate saved when the field gained read-only focus.
      caret.focusGained(new FocusEvent(field, FocusEvent.FOCUS_GAINED));
    } else {
      field.getCaret().setVisible(editing);
    }
  }

  private static void mouseClicked(AWTEvent event) {
    if (!(event instanceof MouseEvent click) || click.getID() != MouseEvent.MOUSE_CLICKED) return;
    KeyboardFocusManager focus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
    if (!(focus.getFocusOwner() instanceof JTextComponent field) || !field.isEditable()) return;
    Component target = click.getComponent();
    Component hit = SwingUtilities.getDeepestComponentAt(target, click.getX(), click.getY());
    if (hit != null) target = hit;
    if (belongsToEditor(target, field)) return;
    if (target instanceof JTextComponent next && next.isEnabled() && next.isFocusable()
        && next.isRequestFocusEnabled()) return;

    EventQueue.invokeLater(() -> {
      if (!field.isFocusOwner()) return;
      JRootPane root = SwingUtilities.getRootPane(field);
      // Keep window shortcuts and keyboard traversal available after clicking blank space.
      if (root == null || !root.requestFocusInWindow()) focus.clearFocusOwner();
    });
  }

  private static boolean belongsToEditor(Component target, JTextComponent field) {
    JPopupMenu popup = target instanceof JPopupMenu menu
        ? menu : (JPopupMenu) SwingUtilities.getAncestorOfClass(JPopupMenu.class, target);
    if (popup != null) {
      if (popup.getClientProperty(POPUP_INPUT_GROUP) instanceof Component group
          && SwingUtilities.isDescendingFrom(field, group)) return true;
      target = popup.getInvoker();
    }
    Container spinner = SwingUtilities.getAncestorOfClass(JSpinner.class, field);
    return target != null && SwingUtilities.isDescendingFrom(target, spinner != null ? spinner : field);
  }
}
