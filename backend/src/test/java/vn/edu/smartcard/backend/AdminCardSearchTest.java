package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.edu.smartcard.shared.Api;
import vn.edu.smartcard.shared.Profile;

class AdminCardSearchTest {
  @Test
  void searchReturnsStatusesForTheFilteredPageAndKeepsThePreviousContract() throws Exception {
    SecurityMaterial security = fixtureSecurity();
    Profile active = profile("TTM000001", "Nguyễn An", 2024);
    Profile blocked = profile("TTM000002", "Nguyễn Bình", 2023);
    Profile other = profile("TTM000003", "Trần Nam", 2022);
    var db = new SearchDatabase(List.of(
        new String[] {security.encode(active), "ACTIVE"},
        new String[] {security.encode(blocked), "BLOCKED"},
        new String[] {security.encode(other), "ACTIVE"}));
    var repo = new CardRepository(db, new DataSourceTransactionManager(), security);

    Api.CardListPage result = repo.searchWithStatus("nguyen", "", null, "", "NEWEST", 0);
    assertEquals(10, result.pageSize());
    assertEquals(List.of(active, blocked), result.items());
    assertEquals(Map.of(active.cardId(), "ACTIVE", blocked.cardId(), "BLOCKED"),
        result.lifecycleStatuses());
    assertTrue(db.sql.contains("profile_json, lifecycle_status"));
    assertEquals(Map.of(), repo.searchWithStatus("nguyen", "", null, "", "NEWEST", 1)
        .lifecycleStatuses());

    Api.CardPage legacy = repo.search("nguyen", "", null, "", "NEWEST", 0);
    assertEquals(20, legacy.pageSize());
    assertEquals(result.items(), legacy.items());
    assertFalse(SecurityMaterial.JSON.readTree(SecurityMaterial.JSON.writeValueAsString(legacy))
        .has("lifecycleStatuses"));
  }

  @Test
  void overviewPaginatesTenAndSupportsAllFiveStableOrders() {
    SecurityMaterial security = fixtureSecurity();
    List<Profile> profiles = java.util.stream.IntStream.rangeClosed(1, 23)
        .mapToObj(i -> profile(String.format(Locale.ROOT, "TTM%06d", 24 - i),
            "Sinh Viên " + i, 2022 + i % 3)).toList();
    List<String[]> rows = profiles.stream()
        .map(p -> new String[] {security.encode(p), "ACTIVE"}).toList();
    var repo = new CardRepository(new SearchDatabase(rows), new DataSourceTransactionManager(), security);

    Api.CardListPage first = repo.searchWithStatus("", "", null, "", "CARD_ID", 0);
    Api.CardListPage second = repo.searchWithStatus("", "", null, "", "CARD_ID", 1);
    Api.CardListPage last = repo.searchWithStatus("", "", null, "", "CARD_ID", 2);
    assertEquals(23, first.total());
    assertEquals(10, first.items().size());
    assertEquals(10, second.items().size());
    assertEquals(3, last.items().size());
    assertEquals("TTM000001", first.items().getFirst().cardId());
    assertEquals("TTM000010", first.items().getLast().cardId());
    assertEquals("TTM000011", second.items().getFirst().cardId());
    assertEquals("TTM000023", last.items().getLast().cardId());
    assertEquals(3, last.lifecycleStatuses().size());
    assertEquals(20, repo.search("", "", null, "", "NEWEST", 0).items().size());

    Profile an = profile("TTM000003", "Nguyễn An", 2022);
    Profile binh = profile("TTM000001", "Nguyễn Bình", 2024);
    Profile nam = profile("TTM000002", "Trần Nam", 2023);
    var ordered = new CardRepository(new SearchDatabase(List.of(
        new String[] {security.encode(an), "ACTIVE"},
        new String[] {security.encode(binh), "ACTIVE"},
        new String[] {security.encode(nam), "ACTIVE"})), new DataSourceTransactionManager(), security);
    assertEquals(List.of(binh, nam, an), ordered.searchWithStatus("", "", null, "", "CARD_ID", 0).items());
    assertEquals(List.of(binh, nam, an), ordered.searchWithStatus("", "", null, "", "NEWEST", 0).items());
    assertEquals(List.of(an, nam, binh), ordered.searchWithStatus("", "", null, "", "OLDEST", 0).items());
    assertEquals(List.of(an, binh, nam), ordered.searchWithStatus("", "", null, "", "NAME_ASC", 0).items());
    assertEquals(List.of(nam, binh, an), ordered.searchWithStatus("", "", null, "", "NAME_DESC", 0).items());
  }

  @Test
  void overviewRequiresAdminAndThePreviousEndpointKeepsItsShape() throws Exception {
    SecurityMaterial security = fixtureSecurity();
    Profile profile = profile("TTM000001", "Nguyễn An", 2024);
    Profile newer = profile("TTM000002", "Nguyễn Bình", 2025);
    var db = new SearchDatabase(List.of(new String[] {security.encode(newer), "ACTIVE"},
        new String[] {security.encode(profile), "BLOCKED"}));
    var repo = new CardRepository(db, new DataSourceTransactionManager(), security);
    AuthService auth = new AuthService(null, null, null, null) {
      @Override public Admin require(String token) {
        if (!"fixture".equals(token)) throw new Failure(401, "ADMIN_LOGIN", "Đăng nhập Admin.");
        return new Admin(1, "fixture", "fixture@example.invalid");
      }
    };
    LocalConfig config = new LocalConfig() {
      @Override public String required(String key) { return "fixture-device"; }
    };
    var registry = new InterceptorRegistry() {
      HandlerInterceptor[] registered() {
        return getInterceptors().toArray(HandlerInterceptor[]::new);
      }
    };
    new ApiAccess(config, auth).addInterceptors(registry);
    var mvc = MockMvcBuilders.standaloneSetup(new ApiController(auth, null, null, repo, null, null))
        .addInterceptors(registry.registered()).setControllerAdvice(new ApiErrors()).build();

    mvc.perform(get("/api/admin/cards/overview").header("X-Device-Key", "fixture-device"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/admin/cards/overview").header("X-Device-Key", "fixture-device")
            .header("Authorization", "Bearer fixture"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].cardId").value(profile.cardId()))
        .andExpect(jsonPath("$.pageSize").value(10))
        .andExpect(jsonPath("$.lifecycleStatuses.TTM000001").value("BLOCKED"));
    mvc.perform(get("/api/admin/cards").header("X-Device-Key", "fixture-device")
            .header("Authorization", "Bearer fixture"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].cardId").value(newer.cardId()))
        .andExpect(jsonPath("$.pageSize").value(20))
        .andExpect(jsonPath("$.lifecycleStatuses").doesNotExist());
  }

  private static SecurityMaterial fixtureSecurity() {
    return new SecurityMaterial(new LocalConfig() {
      @Override public String required(String key) {
        return Base64.getEncoder().encodeToString(new byte[32]);
      }
    });
  }

  private static Profile profile(String id, String name, int year) {
    return new Profile(id, "CT070352", name, "012345678901", "03/02/2005", "CT7C",
        "0912345678", "fixture@example.invalid", year, "", 1);
  }

  private static final class SearchDatabase extends JdbcTemplate {
    private final List<String[]> rows;
    private String sql;

    SearchDatabase(List<String[]> rows) { this.rows = rows; }

    @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
      this.sql = sql;
      List<T> result = new ArrayList<>();
      for (String[] row : rows) {
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("getString")) return row[(int) arguments[0] - 1];
              throw new UnsupportedOperationException(method.getName());
            });
        try {
          result.add(mapper.mapRow(resultSet, result.size()));
        } catch (SQLException e) {
          throw new IllegalStateException(e);
        }
      }
      return result;
    }
  }
}
