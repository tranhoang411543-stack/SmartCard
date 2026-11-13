package vn.edu.smartcard.ttm_cuoiky;

import java.awt.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.*;
import vn.edu.smartcard.shared.Api;

final class DishImages {
  private final ExecutorService workers =
      Executors.newFixedThreadPool(
          4,
          r -> {
            Thread t = new Thread(r, "menu-image");
            t.setDaemon(true);
            return t;
          });
  private final Map<String, ImageIcon> cache =
      new LinkedHashMap<>(128, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, ImageIcon> entry) {
          return size() > 128;
        }
      };
  private final Map<String, java.util.List<Consumer<ImageIcon>>> pending = new HashMap<>();
  private final Map<Integer, ImageIcon> placeholders = new HashMap<>();

  ImageIcon placeholder(int size) {
    return placeholders.computeIfAbsent(size, dimension -> {
      var resource = Objects.requireNonNull(DishImages.class.getResource("/icons/noimages.png"),
          "Không tìm thấy ảnh mặc định của món ăn.");
      return new ImageIcon(new ImageIcon(resource).getImage()
          .getScaledInstance(dimension, dimension, Image.SCALE_SMOOTH));
    });
  }

  void show(ApiClient api, Api.MenuItem item, JButton button) {
    load(api, item, icon -> {
      if (icon != null) button.setIcon(icon);
      else button.setToolTipText(item.name() + " · Chưa tải được ảnh, vẫn có thể chọn món.");
    });
  }

  void load(ApiClient api, Api.MenuItem item, Consumer<ImageIcon> done) {
    load(api, item, 72, done);
  }

  void load(ApiClient api, Api.MenuItem item, int size, Consumer<ImageIcon> done) {
    if (!item.hasImage()) {
      done.accept(placeholder(size));
      return;
    }
    String key = item.id() + ":" + item.revision() + ":" + size;
    if (cache.containsKey(key)) {
      done.accept(cache.get(key));
      return;
    }
    if (pending.containsKey(key)) {
      pending.get(key).add(done);
      return;
    }
    pending.put(key, new ArrayList<>(java.util.List.of(done)));
    workers.submit(
        new SwingWorker<ImageIcon, Void>() {
          protected ImageIcon doInBackground() {
            var value = api.get("/menu/items/" + item.id() + "/image", Api.MenuImage.class);
            if (value.base64().isEmpty() || value.revision() != item.revision()) return null;
            return new ImageIcon(
                new ImageIcon(Base64.getDecoder().decode(value.base64()))
                    .getImage()
                    .getScaledInstance(size, size, Image.SCALE_SMOOTH));
          }

          protected void done() {
            ImageIcon icon = null;
            try {
              icon = get();
            } catch (Exception unavailable) {
              // The dish remains usable when an image cannot be loaded.
            }
            if (icon != null) cache.put(key, icon);
            for (var callback : pending.remove(key)) callback.accept(icon);
          }
        });
  }
}
