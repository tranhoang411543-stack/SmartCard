package vn.edu.smartcard.ttm_cuoiky;

import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.util.UIScale;
import java.awt.Color;
import java.awt.Font;
import java.util.Arrays;
import javax.swing.*;

public final class TTM_CuoiKy {
  static final Color DEFAULT_TEXT_COLOR = new Color(0x606060);
  static final Color ERROR_TEXT_COLOR = new Color(0xC62828);

  static void statusText(JLabel label, String text, boolean failed) {
    label.setForeground(failed ? ERROR_TEXT_COLOR : DEFAULT_TEXT_COLOR);
    label.setText(text);
  }

  public static void main(String[] args) {
    configureLookAndFeel();
    boolean checkout = Arrays.asList(args).contains("--quay=thanhtoan");
    SwingUtilities.invokeLater(() -> {
      try {
        new CanteenFrame(new ApiClient(), checkout).setVisible(true);
      } catch (Exception e) {
        JOptionPane.showMessageDialog(
            null,
            "Chưa mở được ứng dụng.\n"
                + e.getMessage()
                + "\nVui lòng chạy 01-Chuan-bi.ps1 và 02-Backend.ps1 trước.",
            "TTM Căn tin",
            JOptionPane.ERROR_MESSAGE);
      }
    });
  }

  static void configureLookAndFeel() {
    FlatLightLaf.setup();
    UIManager.put("defaultFont", new Font("Segoe UI", Font.PLAIN, 16));
    UIManager.put("defaultForeground", DEFAULT_TEXT_COLOR);
    UIManager.put("Component.foreground", DEFAULT_TEXT_COLOR);
    UIManager.put("Label.foreground", DEFAULT_TEXT_COLOR);
    UIManager.put("Button.foreground", DEFAULT_TEXT_COLOR);
    UIManager.put("TextField.foreground", DEFAULT_TEXT_COLOR);
    UIManager.put("PasswordField.foreground", DEFAULT_TEXT_COLOR);
    UIManager.put("TextArea.foreground", DEFAULT_TEXT_COLOR);
    UIManager.put("TextPane.foreground", DEFAULT_TEXT_COLOR);
    UIManager.put("EditorPane.foreground", DEFAULT_TEXT_COLOR);
    UIManager.put("ComboBox.foreground", DEFAULT_TEXT_COLOR);
    UIManager.put("List.foreground", DEFAULT_TEXT_COLOR);
    UIManager.put("Table.foreground", DEFAULT_TEXT_COLOR);
    UIManager.put("Table.selectionBackground", UIManager.getColor("Table.background"));
    UIManager.put("Table.selectionForeground", DEFAULT_TEXT_COLOR);
    UIManager.put("Table.selectionInactiveBackground", UIManager.getColor("Table.background"));
    UIManager.put("Table.selectionInactiveForeground", DEFAULT_TEXT_COLOR);
    UIManager.put("Table.focusCellBackground", UIManager.getColor("Table.background"));
    UIManager.put("Table.focusCellForeground", DEFAULT_TEXT_COLOR);
    UIManager.put("Table.showCellFocusIndicator", false);
    UIManager.put("TableHeader.foreground", DEFAULT_TEXT_COLOR);
    UIManager.put("TabbedPane.foreground", DEFAULT_TEXT_COLOR);
    UIManager.put("CheckBox.foreground", DEFAULT_TEXT_COLOR);
    UIManager.put("RadioButton.foreground", DEFAULT_TEXT_COLOR);
    int arc = UIScale.unscale(8);
    UIManager.put("Button.arc", arc);
    UIManager.put("Component.arc", arc);
    UIManager.put("TextComponent.arc", arc);
    useNeutralInteractionColors();
    DesktopTextFocus.install();
  }

  private static void useNeutralInteractionColors() {
    // FlatLaf falls back to the base color when these optional state colors are absent.
    UIDefaults defaults = UIManager.getLookAndFeelDefaults();
    for (String control : new String[] {
        "Button", "Button.default", "Button.toolbar", "HelpButton",
        "ToggleButton", "ToggleButton.toolbar", "CheckBox.icon", "RadioButton.icon",
        "TextField", "FormattedTextField", "PasswordField", "TextArea", "TextPane", "EditorPane",
        "ComboBox", "Spinner", "TableHeader"
    }) {
      for (String state : new String[] {"hover", "pressed", "focused"}) {
        defaults.remove(control + "." + state + "Background");
        defaults.remove(control + "." + state + "Foreground");
        defaults.remove(control + "." + state + "BorderColor");
      }
    }
    for (String key : new String[] {
        "TabbedPane.hoverColor", "TabbedPane.hoverForeground",
        "TabbedPane.focusColor", "TabbedPane.focusForeground",
        "TabbedPane.buttonHoverBackground", "TabbedPane.buttonPressedBackground",
        "ToggleButton.tab.hoverBackground", "ToggleButton.tab.hoverForeground",
        "ToggleButton.tab.focusBackground", "ToggleButton.tab.focusForeground",
        "ComboBox.buttonFocusedBackground", "ComboBox.buttonHoverArrowColor",
        "ComboBox.buttonPressedArrowColor", "Spinner.buttonHoverArrowColor",
        "Spinner.buttonPressedArrowColor", "SearchField.searchIconHoverColor",
        "SearchField.searchIconPressedColor", "SearchField.clearIconHoverColor",
        "SearchField.clearIconPressedColor", "CheckBox.icon[filled].focusedSelectedBorderColor",
        "CheckBox.icon[filled].focusedSelectedBackground", "CheckBox.icon[filled].focusedCheckmarkColor",
        "CheckBox.icon[filled].hoverSelectedBackground", "CheckBox.icon[filled].pressedSelectedBackground",
        "ScrollBar.hoverTrackColor", "ScrollBar.pressedTrackColor", "ScrollBar.hoverThumbColor",
        "ScrollBar.pressedThumbColor", "ScrollBar.hoverButtonBackground",
        "ScrollBar.pressedButtonBackground", "ToolBar.hoverButtonGroupBackground"
    }) {
      defaults.remove(key);
    }
    UIManager.put("Component.focusedBorderColor", UIManager.getColor("Component.borderColor"));
    UIManager.put("Component.innerFocusWidth", 0f);
    UIManager.put("Button.toolbar.focusWidth", 0f);
    UIManager.put("CheckBox.icon.focusWidth", 0);
    UIManager.put("RadioButton.icon.focusWidth", 0);
    UIManager.put("TabbedPane.inactiveUnderlineColor", UIManager.getColor("TabbedPane.underlineColor"));
    for (String control : new String[] {"Table", "List"}) {
      var border = UIManager.getBorder(control + ".cellNoFocusBorder");
      UIManager.put(control + ".focusCellHighlightBorder", border);
      UIManager.put(control + ".focusSelectedCellHighlightBorder", border);
    }
  }
}
