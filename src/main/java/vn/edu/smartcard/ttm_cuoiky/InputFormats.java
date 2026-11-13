package vn.edu.smartcard.ttm_cuoiky;

import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.JTextField;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

final class InputFormats {
  private InputFormats() {}

  static JTextField moneyField(int columns) {
    return field(columns, Format.MONEY);
  }

  static JTextField moneyField(int columns, long maximum, Consumer<Boolean> showLimitError) {
    return field(columns, Format.MONEY, digits -> {
      try {
        return digits.isEmpty() || Long.parseLong(digits) <= maximum;
      } catch (NumberFormatException tooLarge) {
        return false;
      }
    }, showLimitError);
  }

  static JTextField transactionAmountField() {
    return field(0, Format.MONEY, digits -> {
      try {
        return digits.isEmpty() || Long.parseLong(digits) >= 0;
      } catch (NumberFormatException tooLarge) {
        return false;
      }
    }, ignored -> {}, 19);
  }

  static JTextField dateField() {
    return field(10, Format.DATE);
  }

  static JTextField yearField() {
    JTextField field = new JTextField(4);
    ((AbstractDocument) field.getDocument()).setDocumentFilter(new DocumentFilter() {
      @Override
      public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs)
          throws BadLocationException {
        replace(fb, offset, 0, text, attrs);
      }

      @Override
      public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
          throws BadLocationException {
        String value = text == null ? "" : text;
        if (!value.matches("[0-9]*") || fb.getDocument().getLength() - length + value.length() > 4)
          return;
        fb.replace(offset, length, value, attrs);
      }
    });
    return field;
  }

  static String amountDigits(JTextField field) {
    return field.getText().replace(".", "");
  }

  private static JTextField field(int columns, Format format) {
    return field(columns, format, digits -> true, invalid -> {});
  }

  private static JTextField field(
      int columns, Format format, Predicate<String> withinLimit, Consumer<Boolean> showLimitError) {
    return field(columns, format, withinLimit, showLimitError, format.maxDigits);
  }

  private static JTextField field(int columns, Format format, Predicate<String> withinLimit,
      Consumer<Boolean> showLimitError, int maxDigits) {
    JTextField field = new JTextField(columns);
    ((AbstractDocument) field.getDocument())
        .setDocumentFilter(new DigitFilter(field, format, withinLimit, showLimitError, maxDigits));
    return field;
  }

  private enum Format {
    MONEY(9, '.', "[0-9]{1,3}(\\.[0-9]{3})+"),
    DATE(8, '/', "[0-9]{2}/(?:[0-9]{0,2}|[0-9]{2}/[0-9]{0,4})");

    final int maxDigits;
    final char separator;
    final String formattedInput;

    Format(int maxDigits, char separator, String formattedInput) {
      this.maxDigits = maxDigits;
      this.separator = separator;
      this.formattedInput = formattedInput;
    }

    String stripSeparators(String text) {
      return text.replace(String.valueOf(separator), "");
    }

    String display(String digits, boolean inserting) {
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < digits.length(); i++) {
        if (this == MONEY && i > 0 && (digits.length() - i) % 3 == 0) result.append(separator);
        result.append(digits.charAt(i));
        if (this == DATE && (i == 1 || i == 3) && (i + 1 < digits.length() || inserting))
          result.append(separator);
      }
      return result.toString();
    }
  }

  private static final class DigitFilter extends DocumentFilter {
    private final JTextField field;
    private final Format format;
    private final Predicate<String> withinLimit;
    private final Consumer<Boolean> showLimitError;
    private final int maxDigits;

    DigitFilter(JTextField field, Format format, Predicate<String> withinLimit,
        Consumer<Boolean> showLimitError, int maxDigits) {
      this.field = field;
      this.format = format;
      this.withinLimit = withinLimit;
      this.showLimitError = showLimitError;
      this.maxDigits = maxDigits;
    }

    @Override
    public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs)
        throws BadLocationException {
      replace(fb, offset, 0, text, attrs);
    }

    @Override
    public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
      replace(fb, offset, length, "", null);
    }

    @Override
    public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
        throws BadLocationException {
      boolean inserting = text != null && !text.isEmpty();
      String input = inserting ? text.strip() : "";
      if (inserting && !input.matches("[0-9]+") && !input.matches(format.formattedInput)) return;
      String added = format.stripSeparators(input);

      String current = fb.getDocument().getText(0, fb.getDocument().getLength());
      String digits = format.stripSeparators(current);
      int start = format.stripSeparators(current.substring(0, offset)).length();
      int end = format.stripSeparators(current.substring(0, offset + length)).length();

      // Backspace or Delete at a separator should edit the neighboring digit.
      if (!inserting
          && length == 1
          && start == end
          && field.getSelectionStart() == field.getSelectionEnd()) {
        if (field.getCaretPosition() > offset) start = Math.max(0, start - 1);
        else end = Math.min(digits.length(), end + 1);
      }

      String updated = digits.substring(0, start) + added + digits.substring(end);
      if (!withinLimit.test(updated)) {
        showLimitError.accept(true);
        return;
      }
      if (updated.length() > maxDigits) return;
      String display = format.display(updated, inserting);
      int remaining = start + added.length();
      int caret = 0;
      while (remaining > 0) {
        if (display.charAt(caret++) != format.separator) remaining--;
      }
      if (inserting && caret < display.length() && display.charAt(caret) == format.separator)
        caret++;
      if (!current.equals(display)) fb.replace(0, current.length(), display, attrs);
      field.setCaretPosition(caret);
      showLimitError.accept(false);
    }
  }
}
