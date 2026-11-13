package vn.edu.smartcard.backend;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import vn.edu.smartcard.shared.Api;

@RestController
@RequestMapping("/api/menu")
public class MenuController {
  private final MenuService menu;

  public MenuController(WalletService wallet) {
    menu = wallet.catalog;
  }

  @GetMapping("/categories")
  public List<Api.MenuCategory> categories() {
    return menu.categories();
  }

  @GetMapping("/sales")
  public List<Api.MenuSales> sales() {
    return menu.sales();
  }

  @GetMapping("/search")
  public Api.MenuSearchResult search(@RequestParam(required = false) String query) {
    return menu.search(query);
  }

  @GetMapping("/categories/{id}/delete-preview")
  public Api.MenuCategory preview(@PathVariable long id) {
    return menu.category(id);
  }

  @GetMapping("/items/{id}/image")
  public Api.MenuImage image(@PathVariable long id) {
    return menu.image(id);
  }

  @PostMapping("/categories")
  public Api.MenuCategory create(
      @RequestHeader("X-Terminal-ID") String terminal, @RequestBody Api.CategoryInput input) {
    return menu.createCategory(terminal, input);
  }

  @PostMapping("/categories/{id}/delete")
  public Api.Result deleteCategory(
      @RequestHeader("X-Terminal-ID") String terminal,
      @PathVariable long id,
      @RequestBody Api.MenuDelete input) {
    menu.deleteCategory(terminal, id, input);
    return new Api.Result("Đã xóa danh mục và các món.");
  }

  @PostMapping("/items")
  public Api.MenuItem create(
      @RequestHeader("X-Terminal-ID") String terminal, @RequestBody Api.MenuInput input) {
    return menu.createItem(terminal, input);
  }

  @PostMapping("/items/{id}/update")
  public Api.MenuItem update(
      @RequestHeader("X-Terminal-ID") String terminal,
      @PathVariable long id,
      @RequestBody Api.MenuInput input) {
    return menu.updateItem(terminal, id, input);
  }

  @PostMapping("/items/{id}/delete")
  public Api.Result deleteItem(
      @RequestHeader("X-Terminal-ID") String terminal,
      @PathVariable long id,
      @RequestBody Api.MenuDelete input) {
    menu.deleteItem(terminal, id, input);
    return new Api.Result("Đã xóa món.");
  }
}
