package vn.edu.smartcard.ttm_cuoiky;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.event.ActionEvent;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultEditorKit;
import org.junit.jupiter.api.Test;

class InputFormatsTest {
  @Test
  void amountsGroupDigitsWithoutChangingTheValueAndRejectAmbiguousPaste() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JTextField amount = InputFormats.moneyField(12);
          type(amount, "1234567");
          assertEquals("1.234.567", amount.getText());
          assertEquals("1234567", InputFormats.amountDigits(amount));

          amount.setCaretPosition(2);
          backspace(amount);
          assertEquals("234.567", amount.getText());
          assertEquals(0, amount.getCaretPosition());

          amount.selectAll();
          amount.replaceSelection("25.000");
          assertEquals("25.000", amount.getText());
          assertEquals("25000", InputFormats.amountDigits(amount));
          amount.selectAll();
          amount.replaceSelection("25.50");
          assertEquals("25.000", amount.getText());
        });
  }

  @Test
  void transactionFilterSupportsTheDatabaseAmountRangeWithoutOverflow() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      JTextField amount = InputFormats.transactionAmountField();
      amount.setText("9223372036854775807");
      assertEquals("9223372036854775807", InputFormats.amountDigits(amount));
      amount.selectAll();
      amount.replaceSelection("9223372036854775808");
      assertEquals("9223372036854775807", InputFormats.amountDigits(amount));
      amount.selectAll();
      amount.replaceSelection("1000000000");
      assertEquals("1.000.000.000", amount.getText());
    });
  }

  @Test
  void dateAddsSeparatorsAsDayAndMonthFinishAndStopsAtEightDigits() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JTextField date = InputFormats.dateField();
          type(date, "12");
          assertEquals("12/", date.getText());
          assertEquals(3, date.getCaretPosition());
          type(date, "08");
          assertEquals("12/08/", date.getText());
          assertEquals(6, date.getCaretPosition());
          type(date, "20001");
          assertEquals("12/08/2000", date.getText());
          assertEquals(10, date.getCaretPosition());
        });
  }

  @Test
  void backspaceRemovesAnEmptySegmentSeparatorAndReturnsToTheLastDigit() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JTextField date = InputFormats.dateField();
          type(date, "12082");
          backspace(date);
          assertEquals("12/08", date.getText());
          assertEquals(5, date.getCaretPosition());
          backspace(date);
          assertEquals("12/0", date.getText());
          backspace(date);
          assertEquals("12", date.getText());
          assertEquals(2, date.getCaretPosition());

          date.setText("12/");
          backspace(date);
          assertEquals("1", date.getText());
          assertEquals(1, date.getCaretPosition());
        });
  }

  @Test
  void dateAcceptsFormattedPasteAndEditingASelectedSegment() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JTextField date = InputFormats.dateField();
          date.replaceSelection("01/02/2003");
          date.select(3, 5);
          date.replaceSelection("11");
          assertEquals("01/11/2003", date.getText());
          assertEquals(6, date.getCaretPosition());
          date.selectAll();
          date.replaceSelection("31012002");
          assertEquals("31/01/2002", date.getText());
        });
  }

  private static void type(JTextField field, String digits) {
    for (int i = 0; i < digits.length(); i++) field.replaceSelection(digits.substring(i, i + 1));
  }

  private static void backspace(JTextField field) {
    field
        .getActionMap()
        .get(DefaultEditorKit.deletePrevCharAction)
        .actionPerformed(new ActionEvent(field, ActionEvent.ACTION_PERFORMED, ""));
  }
}
