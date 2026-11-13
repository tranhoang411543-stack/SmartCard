package vn.edu.smartcard.ttm_cuoiky;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import javax.swing.*;

/** Optional, editable transaction date using the same picker style as the card profile. */
final class TransactionDatePicker {
  static final DateTimeFormatter FORMAT =
      DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

  private TransactionDatePicker() {}

  static JTextField field(String label) {
    JTextField field = InputFormats.dateField();
    field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, label);
    field.putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT,
        ProfileForm.pickerButton("Chọn " + label.toLowerCase(java.util.Locale.ROOT),
            () -> choose(field, label)));
    field.getAccessibleContext().setAccessibleName(label);
    field.setToolTipText(label + " (dd/MM/yyyy)");
    return field;
  }

  private static void choose(JTextField field, String label) {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    LocalDate initial;
    try {
      initial = LocalDate.parse(field.getText(), FORMAT);
      if (initial.getYear() < 1900 || initial.getYear() > today.getYear() + 1) initial = today;
    } catch (RuntimeException invalid) {
      initial = today;
    }
    int initialDay = initial.getDayOfMonth();
    JComboBox<Integer> day = new JComboBox<>(), month = new JComboBox<>(), year = new JComboBox<>();
    for (int value = 1; value <= 12; value++) month.addItem(value);
    for (int value = today.getYear() + 1; value >= 1900; value--) year.addItem(value);
    month.setSelectedItem(initial.getMonthValue());
    year.setSelectedItem(initial.getYear());
    year.setMaximumRowCount(12);
    Runnable updateDays = () -> {
      int previous = day.getSelectedItem() instanceof Integer selected ? selected : initialDay;
      int count = YearMonth.of((Integer) year.getSelectedItem(), (Integer) month.getSelectedItem())
          .lengthOfMonth();
      DefaultComboBoxModel<Integer> days = new DefaultComboBoxModel<>();
      for (int value = 1; value <= count; value++) days.addElement(value);
      day.setModel(days);
      day.setSelectedItem(Math.min(previous, count));
    };
    updateDays.run();
    month.addActionListener(e -> updateDays.run());
    year.addActionListener(e -> updateDays.run());
    JPanel choices = new JPanel(new GridLayout(2, 3, 8, 4));
    for (String text : new String[] {"Ngày", "Tháng", "Năm"}) choices.add(new JLabel(text));
    choices.add(day);
    choices.add(month);
    choices.add(year);
    day.getAccessibleContext().setAccessibleName("Ngày");
    month.getAccessibleContext().setAccessibleName("Tháng");
    year.getAccessibleContext().setAccessibleName("Năm");
    Object[] options = {"Chọn", "Hủy"};
    int choice = JOptionPane.showOptionDialog(field, choices, "Chọn " + label.toLowerCase(java.util.Locale.ROOT),
        JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
    if (choice == 0 && field.isShowing()) {
      LocalDate selected = LocalDate.of((Integer) year.getSelectedItem(),
          (Integer) month.getSelectedItem(), (Integer) day.getSelectedItem());
      field.setText(FORMAT.format(selected));
    }
  }
}
