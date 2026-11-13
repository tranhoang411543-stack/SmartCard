package vn.edu.smartcard.ttm_cuoiky;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import vn.edu.smartcard.shared.*;

/** Nonblocking catalog forms preserve entered data through validation/network failures. */
final class MenuDialogs {
  private enum FormStyle { CATEGORY, ADD_DISH, EDIT_DISH }

  private final CanteenFrame frame;
  private final Runnable reload;

  MenuDialogs(CanteenFrame frame, Runnable reload) {
    this.frame = frame;
    this.reload = reload;
  }

  void category(Window owner) {
    JTextField name = new JTextField(24);
    name.setPreferredSize(new Dimension(0, 32));
    name.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Nhập tên danh mục");
    JPanel fields = new JPanel(new BorderLayout(0, 4));
    JLabel label = new JLabel("Tên danh mục");
    label.setLabelFor(name);
    fields.add(label, BorderLayout.NORTH);
    fields.add(name, BorderLayout.CENTER);
    String request = UUID.randomUUID().toString();
    form(
        owner,
        "Thêm danh mục",
        fields,
        "Tạo danh mục",
        false,
        FormStyle.CATEGORY,
        () -> {
          var input = new Api.CategoryInput(name.getText(), request);
          return () -> frame.api.post("/menu/categories", input, Api.MenuCategory.class);
        });
  }

  void dish(Api.MenuCategory[] categories, long selected, Api.MenuItem item) {
    dish(frame, categories, selected, item);
  }

  void dish(Window owner, Api.MenuCategory[] categories, long selected, Api.MenuItem item) {
    if (categories.length == 0) {
      JOptionPane.showMessageDialog(owner, "Hãy tạo danh mục trước khi thêm món.");
      return;
    }
    JComboBox<Api.MenuCategory> category = new JComboBox<>(categories);
    category.setMaximumRowCount(4);
    if (item == null) {
      category.setSelectedIndex(-1);
      category.setRenderer(new DefaultListCellRenderer() {
        @Override
        public Component getListCellRendererComponent(
            JList<?> list, Object value, int index, boolean selected, boolean focused) {
          super.getListCellRendererComponent(list, value, index, selected, focused);
          if (index == -1 && value == null) {
            setText("Chọn danh mục");
            Color placeholder = UIManager.getColor("TextField.placeholderForeground");
            if (placeholder != null) setForeground(placeholder);
          }
          return this;
        }
      });
    } else {
      for (var c : categories)
        if (c.id() == item.categoryId()) category.setSelectedItem(c);
    }
    JTextField name = new JTextField(item == null ? "" : item.name(), 24);
    JTextField price = InputFormats.moneyField(12);
    if (item != null) price.setText(Long.toString(item.price()));
    final String[] image = {""};
    final boolean[] loading = {false};
    JPanel formFields;
    {
      name.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Nhập tên món");
      price.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Nhập giá bán");
      JLabel currency = new JLabel("VND");
      currency.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
      price.putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, currency);
      JPanel entryFields = new JPanel(null) {
        @Override
        public Dimension getPreferredSize() {
          int height = 0;
          for (Component child : getComponents()) height += child.getPreferredSize().height;
          Insets insets = getInsets();
          return new Dimension(insets.left + insets.right, height + insets.top + insets.bottom);
        }

        @Override
        public void doLayout() {
          Component[] rows = getComponents();
          if (rows.length == 0) return;
          Insets insets = getInsets();
          int height = 0;
          for (Component row : rows) height += row.getPreferredSize().height;
          int spare = Math.max(0, getHeight() - insets.top - insets.bottom - height);
          int width = Math.max(0, getWidth() - insets.left - insets.right);
          int precedingHeight = 0;
          for (int i = 0; i < rows.length; i++) {
            int rowHeight = rows[i].getPreferredSize().height;
            int offset = (int) Math.round((2.0 * i + 1) * spare / (2 * rows.length));
            rows[i].setBounds(insets.left, insets.top + precedingHeight + offset, width, rowHeight);
            precedingHeight += rowHeight;
          }
        }
      };
      entryFields.add(dishField("Danh mục", category));
      entryFields.add(dishField("Tên món", name));
      entryFields.add(dishField("Giá bán", price));
      Icon imagePlaceholder = DesktopIcons.svg("/icons/img-box-svgrepo-com.svg", 128, "biểu tượng ảnh món");
      ImageIcon[] originalImage = {null};
      JButton preview = new JButton() {
        @Override
        protected void paintComponent(Graphics graphics) {
          boolean active = getModel().isRollover() || getModel().isPressed();
          graphics.setColor(active ? new Color(0xE0E0E0) : getParent().getBackground());
          graphics.fillRect(0, 0, getWidth(), getHeight());
          super.paintComponent(graphics);
          if (active && getIcon() instanceof ImageIcon) {
            Graphics2D overlay = (Graphics2D) graphics.create();
            overlay.setColor(new Color(224, 224, 224, 180));
            overlay.fillRect(0, 0, getWidth(), getHeight());
            imagePlaceholder.paintIcon(this, overlay,
                (getWidth() - imagePlaceholder.getIconWidth()) / 2,
                (getHeight() - imagePlaceholder.getIconHeight()) / 2);
            overlay.dispose();
          }
        }
      };
      preview.setPreferredSize(new Dimension(256, 256));
      preview.setContentAreaFilled(false);
      preview.setBorder(BorderFactory.createLineBorder(new Color(0xCCCCCC), 1));
      preview.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      preview.setIcon(imagePlaceholder);
      preview.getAccessibleContext().setAccessibleName("Ảnh món ăn");
      preview.getAccessibleContext().setAccessibleDescription("Chưa chọn ảnh món ăn");
      preview.setToolTipText("Chọn ảnh JPEG/PNG 256 × 256");
      preview.addActionListener(
          e -> {
            JFileChooser picker = new JFileChooser();
            picker.setDialogTitle("Chọn ảnh món 256 × 256");
            picker.setFileFilter(
                new FileNameExtensionFilter("Ảnh JPEG, PNG", "jpg", "jpeg", "png"));
            if (picker.showOpenDialog(preview) != JFileChooser.APPROVE_OPTION) return;
            var file = picker.getSelectedFile();
            loading[0] = true;
            preview.setEnabled(false);
            new SwingWorker<String, Void>() {
              protected String doInBackground() throws Exception {
                return MenuImages.read(file);
              }

              protected void done() {
                loading[0] = false;
                preview.setEnabled(true);
                try {
                  image[0] = get();
                  preview.setIcon(
                      new ImageIcon(
                          new ImageIcon(Base64.getDecoder().decode(image[0]))
                              .getImage()
                              .getScaledInstance(256, 256, Image.SCALE_SMOOTH)));
                  preview.getAccessibleContext().setAccessibleDescription("Ảnh món ăn đã chọn");
                } catch (Exception failure) {
                  if (image[0].isEmpty() && originalImage[0] != null) preview.setIcon(originalImage[0]);
                  frame.error(failure);
                }
              }
            }.execute();
          });
      if (item != null && item.hasImage()) {
        new SwingWorker<ImageIcon, Void>() {
          protected ImageIcon doInBackground() {
            Api.MenuImage existing = frame.api.get("/menu/items/" + item.id() + "/image", Api.MenuImage.class);
            if (existing.revision() != item.revision() || existing.base64().isEmpty()) return null;
            return new ImageIcon(new ImageIcon(Base64.getDecoder().decode(existing.base64()))
                .getImage().getScaledInstance(256, 256, Image.SCALE_SMOOTH));
          }

          protected void done() {
            if (!preview.isDisplayable()) return;
            try {
              originalImage[0] = get();
              if (originalImage[0] != null && image[0].isEmpty() && !loading[0]) {
                preview.setIcon(originalImage[0]);
                preview.getAccessibleContext().setAccessibleDescription("Ảnh món ăn hiện tại");
              } else if (originalImage[0] == null) {
                preview.setToolTipText("Không tải được ảnh hiện tại. Nhấn để chọn ảnh mới.");
              }
            } catch (Exception unavailable) {
              preview.setToolTipText("Không tải được ảnh hiện tại. Nhấn để chọn ảnh mới.");
            }
          }
        }.execute();
      }
      formFields = new JPanel(new BorderLayout(16, 0));
      formFields.add(preview, BorderLayout.WEST);
      formFields.add(entryFields, BorderLayout.CENTER);
    }
    String request = UUID.randomUUID().toString();
    form(
        owner,
        item == null ? "Thêm món" : "Sửa món",
        formFields,
        item == null ? "Tạo món" : "Lưu",
        true,
        FormStyle.ADD_DISH,
        () -> {
          if (loading[0]) throw new IllegalArgumentException("Đang đọc ảnh. Vui lòng chờ.");
          var c = (Api.MenuCategory) category.getSelectedItem();
          if (c == null) throw new IllegalArgumentException("Vui lòng chọn danh mục.");
          String raw = InputFormats.amountDigits(price);
          if (!raw.matches("[0-9]{1,9}"))
            throw new IllegalArgumentException("Giá phải là số nguyên VNĐ từ 1 đến 100.000.000.");
          long amount = Long.parseLong(raw);
          if (amount < 1 || amount > 100_000_000)
            throw new IllegalArgumentException("Giá phải từ 1 đến 100.000.000 VNĐ.");
          var input =
              new Api.MenuInput(
                  c.id(),
                  name.getText(),
                  amount,
                  image[0],
                  item == null ? 0 : item.revision(),
                  request);
          return () ->
              frame.api.post(
                  item == null ? "/menu/items" : "/menu/items/" + item.id() + "/update",
                  input,
                  Api.MenuItem.class);
        });
  }

  private JPanel dishField(String text, JComponent input) {
    JLabel label = new JLabel(text);
    label.setFont(label.getFont().deriveFont(Font.PLAIN, 16f));
    label.setLabelFor(input);
    input.setPreferredSize(new Dimension(0, 32));
    JPanel row = new JPanel(new BorderLayout(0, 4));
    row.setAlignmentX(Component.LEFT_ALIGNMENT);
    row.add(label, BorderLayout.NORTH);
    row.add(input, BorderLayout.CENTER);
    int height = row.getPreferredSize().height;
    row.setPreferredSize(new Dimension(0, height));
    return row;
  }

  private void field(JPanel parent, String text, JComponent input) {
    JLabel label = new JLabel(text);
    label.setLabelFor(input);
    parent.add(label);
    parent.add(input);
    parent.add(Box.createVerticalStrut(9));
    input.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
  }

  private void form(
      Window owner, String title, JPanel fields, String action, boolean confirmDiscard,
      FormStyle style,
      Supplier<Callable<?>> input) {
    boolean addCategory = style == FormStyle.CATEGORY;
    boolean addDish = style == FormStyle.ADD_DISH;
    JDialog dialog = new JDialog(owner, title,
        owner == frame ? Dialog.ModalityType.MODELESS : Dialog.ModalityType.DOCUMENT_MODAL);
    dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    JPanel body = new JPanel(new BorderLayout(10, addDish || addCategory ? 0 : 12));
    int padding = addDish || addCategory ? 16 : 18;
    body.setBorder(BorderFactory.createEmptyBorder(padding, padding, padding, padding));
    JLabel error = new JLabel(" ");
    if (addCategory) {
      error.setForeground(new Color(0xC62828));
      error.setVerticalAlignment(SwingConstants.TOP);
      error.setAlignmentX(Component.LEFT_ALIGNMENT);
      error.setMinimumSize(new Dimension(0, 22));
      error.setPreferredSize(new Dimension(0, 22));
      error.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
      fields.setAlignmentX(Component.LEFT_ALIGNMENT);
      fields.setMaximumSize(new Dimension(Integer.MAX_VALUE, fields.getPreferredSize().height));
      JPanel content = new JPanel();
      content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
      content.add(fields);
      content.add(Box.createVerticalStrut(12));
      content.add(error);
      content.add(Box.createVerticalStrut(12));
      body.add(content, BorderLayout.CENTER);
    } else if (addDish) {
      body.add(fields, BorderLayout.NORTH);
      body.add(error, BorderLayout.CENTER);
    } else {
      JPanel content = new JPanel(new BorderLayout(0, 10));
      content.add(fields);
      content.add(error, BorderLayout.SOUTH);
      body.add(content);
    }
    boolean[] busy = {false};
    Runnable dismiss =
        () -> {
          if (!busy[0]
              && (!confirmDiscard || JOptionPane.showConfirmDialog(
                      dialog, "Bỏ nội dung đang nhập?", "Đóng form", JOptionPane.YES_NO_OPTION)
                  == JOptionPane.YES_OPTION)) dialog.dispose();
        };
    JButton save = new JButton(action), cancel = new JButton("Hủy");
    cancel.addActionListener(e -> dismiss.run());
    save.addActionListener(
        e -> {
          if (busy[0] || frame.isBusy()) return;
          Callable<?> work;
          try {
            work = input.get();
          } catch (RuntimeException invalid) {
            error.setText(CanteenFrame.html(invalid.getMessage()));
            return;
          }
          busy[0] = true;
          save.setEnabled(false);
          cancel.setEnabled(false);
          frame.run(dialog, "Đang lưu thực đơn…", work,
              result -> {
                dialog.dispose();
                reload.run();
                frame.message("Đã lưu thực đơn.");
              },
              failure -> {
                busy[0] = false;
                save.setEnabled(true);
                cancel.setEnabled(true);
                error.setText(CanteenFrame.html(failure.getMessage()));
              });
        });
    if (addDish || addCategory) {
      save.setPreferredSize(new Dimension(addCategory ? 150 : 120, 30));
      cancel.setPreferredSize(new Dimension(120, 30));
      JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
      actions.add(cancel);
      actions.add(Box.createHorizontalStrut(8));
      actions.add(save);
      body.add(actions, BorderLayout.SOUTH);
    } else {
      body.add(CanteenFrame.actions(save, cancel), BorderLayout.SOUTH);
    }
    dialog.addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            dismiss.run();
          }
        });
    dialog
        .getRootPane()
        .registerKeyboardAction(
            e -> dismiss.run(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
    dialog.getRootPane().setDefaultButton(save);
    dialog.setContentPane(body);
    dialog.pack();
    if (addCategory) {
      dialog.setSize(360, dialog.getHeight());
      dialog.setResizable(false);
    } else if (addDish) {
      Dimension dialogSize = new Dimension(660, 400);
      dialog.setMinimumSize(dialogSize);
      dialog.setSize(dialogSize);
    } else {
      dialog.setMinimumSize(new Dimension(400, dialog.getHeight()));
    }
    dialog.setLocationRelativeTo(owner);
    dialog.setVisible(true);
  }
}
