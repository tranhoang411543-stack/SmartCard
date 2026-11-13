package vn.edu.smartcard.backend;

import java.text.Normalizer;
import java.util.*;
import vn.edu.smartcard.shared.*;

/** Catalog transactions share a short lock with invoice price snapshots. No card I/O here. */
final class MenuService {
  private final CardRepository repo;
  private final StationSessions stations;

  MenuService(CardRepository repo, StationSessions stations) {
    this.repo = repo;
    this.stations = stations;
  }

  void lock() {
    repo.db.queryForList("SELECT pg_advisory_xact_lock(51828,1)");
  }

  List<Api.MenuCategory> categories() {
    return repo.db.query(
        "SELECT c.*,count(m.id) AS item_count FROM menu_categories c LEFT JOIN"
            + " menu_items m ON m.category_id=c.id GROUP BY c.id ORDER BY c.id",
        (r, n) ->
            new Api.MenuCategory(
                r.getLong("id"),
                r.getString("name"),
                r.getInt("item_count"),
                r.getLong("revision")));
  }

  Api.MenuCategory category(long id) {
    return categories().stream()
        .filter(c -> c.id() == id)
        .findFirst()
        .orElseThrow(() -> Failure.missing("Danh mục không còn tồn tại."));
  }

  List<Api.MenuItem> items(Long category, String query) {
    String q = Profile.searchable(Objects.toString(query, ""));
    return repo
        .db
        .query(
            "SELECT m.id,m.name,c.name AS"
                + " category,m.price,m.available,m.category_id,m.revision,m.image_data IS NOT NULL"
                + " AS has_image FROM menu_items m JOIN menu_categories c ON c.id=m.category_id"
                + " WHERE m.available=true ORDER BY c.id,m.id",
            (r, n) ->
                new Api.MenuItem(
                    r.getLong("id"),
                    r.getString("name"),
                    r.getString("category"),
                    r.getLong("price"),
                    r.getBoolean("available"),
                    r.getLong("category_id"),
                    r.getLong("revision"),
                    r.getBoolean("has_image")))
        .stream()
        .filter(
            m ->
                (category == null || m.categoryId() == category)
                    && Profile.searchable(m.name() + " " + m.category()).contains(q))
        .toList();
  }

  List<Api.MenuSales> sales() {
    return repo.db.query(
        "SELECT id,sold_quantity FROM menu_items WHERE available=true ORDER BY id",
        (r, n) -> new Api.MenuSales(r.getLong("id"), r.getLong("sold_quantity")));
  }

  Api.MenuSearchResult search(String query) {
    List<Api.MenuItem> all = items(null, "");
    String normalized = Profile.searchable(Objects.toString(query, ""));
    return new Api.MenuSearchResult(
        all.stream().filter(item -> Profile.searchable(item.name()).contains(normalized)).toList(),
        all.size());
  }

  Api.MenuItem item(long id) {
    return items(null, "").stream()
        .filter(m -> m.id() == id)
        .findFirst()
        .orElseThrow(() -> Failure.missing("Món không còn tồn tại."));
  }

  Api.MenuImage image(long id) {
    var rows = repo.db.queryForList("SELECT image_data,revision FROM menu_items WHERE id=?", id);
    if (rows.isEmpty()) throw Failure.missing("Món không còn tồn tại.");
    var r = rows.getFirst();
    byte[] bytes = (byte[]) r.get("image_data");
    return new Api.MenuImage(
        bytes == null ? "" : Base64.getEncoder().encodeToString(bytes),
        "image/png",
        ((Number) r.get("revision")).longValue());
  }

  private String name(String value, int max) {
    String s =
        Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFC)
            .strip()
            .replaceAll("\\s+", " ");
    if (s.isEmpty() || s.length() > max || s.chars().anyMatch(Character::isISOControl))
      throw Failure.bad("Tên phải có từ 1 đến " + max + " ký tự, không chứa ký tự điều khiển.");
    return s;
  }

  private String payload(Object value) {
    try {
      return repo.security.keyed(SecurityMaterial.JSON.writeValueAsString(value));
    } catch (Exception e) {
      throw Failure.bad("Dữ liệu thực đơn không hợp lệ.");
    }
  }

  private Long replay(String terminal, String request, String kind, String payload) {
    UUID id = uuid(request);
    var rows = repo.db.queryForList("SELECT * FROM menu_requests WHERE request_id=?", id);
    if (rows.isEmpty()) return null;
    var r = rows.getFirst();
    if (!uuid(terminal).equals(r.get("terminal_id"))
        || !kind.equals(r.get("kind"))
        || !payload.equals(r.get("payload")))
      throw conflict("Mã yêu cầu đã được dùng với nội dung khác.");
    return ((Number) r.get("entity_id")).longValue();
  }

  private void remember(String terminal, String request, String kind, String payload, long id) {
    repo.db.update(
        "INSERT INTO menu_requests VALUES(?,?,?,?,?)",
        uuid(request),
        uuid(terminal),
        kind,
        payload,
        id);
  }

  private void audit(String terminal, String action, long id, String detail) {
    repo.db.update(
        "INSERT INTO menu_audit(terminal_id,action,entity_id,detail) VALUES(?,?,?,?)",
        uuid(terminal),
        action,
        id,
        detail);
  }

  private void bump(long category) {
    repo.db.update("UPDATE menu_categories SET revision=revision+1 WHERE id=?", category);
  }

  private void revision(long actual, long expected) {
    if (actual != expected)
      throw conflict("Thực đơn đã thay đổi. Tải lại và kiểm tra trước khi xác nhận.");
  }

  Api.MenuCategory createCategory(String terminal, Api.CategoryInput input) {
    if (input == null) throw Failure.bad("Thiếu tên danh mục.");
    String value = name(input.name(), 50),
        key = value.toLowerCase(Locale.ROOT),
        hash = payload(value);
    return repo.tx.execute(
        tx -> {
          stations.requireActive(terminal, true);
          lock();
          Long replay = replay(terminal, input.requestId(), "CATEGORY", hash);
          if (replay != null) return category(replay);
          // Legacy name_key values came from PostgreSQL's locale-dependent lower().
          // Compare normalized names in Java as well, including Vietnamese capitals.
          boolean duplicate = repo.db.queryForList("SELECT name FROM menu_categories", String.class)
              .stream().map(existing -> Normalizer.normalize(existing, Normalizer.Form.NFC)
                  .strip().replaceAll("\\s+", " "))
              .anyMatch(existing -> existing.equalsIgnoreCase(value)
                  || existing.toLowerCase(Locale.ROOT).equals(key));
          if (duplicate) throw conflict("Tên danh mục đã tồn tại.");
          long id =
              repo.db.queryForObject(
                  "INSERT INTO menu_categories(name,name_key) VALUES(?,?) RETURNING id",
                  Long.class,
                  value,
                  key);
          remember(terminal, input.requestId(), "CATEGORY", hash, id);
          audit(terminal, "CREATE_CATEGORY", id, value);
          return category(id);
        });
  }

  void deleteCategory(String terminal, long id, Api.MenuDelete input) {
    repo.tx.executeWithoutResult(
        tx -> {
          stations.requireActive(terminal, true);
          lock();
          var c = category(id);
          revision(c.revision(), input.revision());
          repo.db.update("DELETE FROM menu_categories WHERE id=?", id);
          audit(terminal, "DELETE_CATEGORY", id, c.name() + " · " + c.itemCount() + " món");
        });
  }

  Api.MenuItem createItem(String terminal, Api.MenuInput input) {
    validate(input);
    byte[] image;
    try {
      image = MenuImages.decode(input.imageBase64());
    } catch (IllegalArgumentException e) {
      throw Failure.bad(e.getMessage());
    }
    String value = name(input.name(), 120), hash = payload(input);
    return repo.tx.execute(
        tx -> {
          stations.requireActive(terminal, true);
          lock();
          Long replay = replay(terminal, input.requestId(), "ITEM", hash);
          if (replay != null) return item(replay);
          var c = category(input.categoryId());
          long id =
              repo.db.queryForObject(
                  "INSERT INTO menu_items(name,category,category_id,price,image_data)"
                      + " VALUES(?,?,?,?,?) RETURNING id",
                  Long.class,
                  value,
                  c.name(),
                  c.id(),
                  input.price(),
                  image);
          bump(c.id());
          remember(terminal, input.requestId(), "ITEM", hash, id);
          audit(terminal, "CREATE_ITEM", id, value);
          return item(id);
        });
  }

  private void validate(Api.MenuInput input) {
    if (input == null || input.price() <= 0 || input.price() > 100_000_000)
      throw Failure.bad("Giá món phải là số nguyên từ 1 đến 100.000.000 VNĐ.");
  }

  Api.MenuItem updateItem(String terminal, long id, Api.MenuInput input) {
    validate(input);
    byte[] image = null;
    if (input.imageBase64() != null && !input.imageBase64().isEmpty()) {
      try {
        image = MenuImages.decode(input.imageBase64());
      } catch (IllegalArgumentException e) {
        throw Failure.bad(e.getMessage());
      }
    }
    final byte[] replacementImage = image;
    String value = name(input.name(), 120);
    return repo.tx.execute(
        tx -> {
          stations.requireActive(terminal, true);
          lock();
          var old = item(id);
          revision(old.revision(), input.revision());
          var c = category(input.categoryId());
          if (replacementImage == null) {
            repo.db.update(
                "UPDATE menu_items SET name=?,category=?,category_id=?,price=?,revision=revision+1"
                    + " WHERE id=?",
                value, c.name(), c.id(), input.price(), id);
          } else {
            repo.db.update(
                "UPDATE menu_items SET name=?,category=?,category_id=?,price=?,image_data=?,"
                    + "revision=revision+1 WHERE id=?",
                value, c.name(), c.id(), input.price(), replacementImage, id);
          }
          bump(old.categoryId());
          if (old.categoryId() != c.id()) bump(c.id());
          audit(terminal, "UPDATE_ITEM", id, value);
          return item(id);
        });
  }

  void deleteItem(String terminal, long id, Api.MenuDelete input) {
    repo.tx.executeWithoutResult(
        tx -> {
          stations.requireActive(terminal, true);
          lock();
          var old = item(id);
          revision(old.revision(), input.revision());
          repo.db.update("DELETE FROM menu_items WHERE id=?", id);
          bump(old.categoryId());
          audit(terminal, "DELETE_ITEM", id, old.name());
        });
  }

  static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (Exception e) {
      throw Failure.bad("Mã yêu cầu/phiên quầy không hợp lệ.");
    }
  }

  static Failure conflict(String message) {
    return new Failure(409, "MENU_CHANGED", message);
  }
}
