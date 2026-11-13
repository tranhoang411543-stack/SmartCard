package vn.edu.smartcard.ttm_cuoiky;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import vn.edu.smartcard.shared.Api;

final class ApiClient {

  private final ObjectMapper json = new ObjectMapper();
  private HttpClient http;

  private synchronized HttpClient http() {
    if (http == null) {
      http =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(4))
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();
    }
    return http;
  }

  private final String base, device, terminal = UUID.randomUUID().toString();
  private volatile String token;
  private final Path clientFile;
  private volatile Api.StationSession station;
  private volatile boolean stationEnded;

  ApiClient() throws IOException {
    Properties p = new Properties();
    Path path =
        Path.of(System.getProperty("ttm.client", ".local/client.properties")).toAbsolutePath();
    clientFile = path;
    try (InputStream in = Files.newInputStream(path)) {
      p.load(in);
    }
    base = p.getProperty("api.url", "http://127.0.0.1:18080").replaceAll("/+$", "");
    URI url = URI.create(base);
    if (!url.getScheme().equals("https")
        && !(url.getScheme().equals("http")
            && Set.of("127.0.0.1", "localhost", "::1").contains(url.getHost()))) {
      throw new IOException("Kết nối máy chủ ngoài máy tính phải sử dụng HTTPS.");
    }
    device = p.getProperty("device.key", "");
    if (device.isBlank()) {
      throw new IOException("Chưa có cấu hình quầy. Vui lòng chạy bước chuẩn bị project.");
    }
  }

  void openStation() {
    try {
      Path file = clientFile.resolveSibling("station-checkout.id");
      String id;
      if (Files.exists(file)) {
        id = Files.readString(file).strip();
      } else {
        id = UUID.randomUUID().toString();
        try {
          Files.writeString(file, id, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException race) {
          id = Files.readString(file).strip();
        }
      }
      UUID.fromString(id);
      station = post("/station/open", new Api.OpenStation(id, terminal), Api.StationSession.class);
      stationEnded = false;
    } catch (IOException e) {
      throw new ApiError(
          503, "STATION_CONFIG", "Không lưu được định danh quầy. Kiểm tra thư mục cấu hình.");
    }
  }

  void heartbeat() {
    if (station != null && !stationEnded)
      try {
        station = post("/station/heartbeat", null, Api.StationSession.class);
      } catch (ApiError e) {
        if (e.code.equals("STATION_EXPIRED")) {
          stationEnded = true;
        }
        throw e;
      }
  }

  boolean stationReady() {
    return station != null && !stationEnded;
  }

  void closeStation() {
    if (station != null)
      try {
        post("/station/close", null, Api.Result.class);
      } finally {
        stationEnded = true;
      }
  }

  void token(String value) {
    token = value;
  }

  <T> T get(String path, Class<T> type) {
    return request("GET", path, null, json.getTypeFactory().constructType(type));
  }

  <T> T get(String path, TypeReference<T> type) {
    return request("GET", path, null, json.getTypeFactory().constructType(type));
  }

  <T> T post(String path, Object body, Class<T> type) {
    return request("POST", path, body, json.getTypeFactory().constructType(type));
  }

  TopupEventStream watchTopup(long id, Consumer<Api.TopupEvent> onStatus,
      Consumer<String> onWarning, Consumer<Boolean> onConnection, Consumer<ApiError> onFatal) {
    TopupEventStream stream = new TopupEventStream(http(), URI.create(base + "/api/topups/" + id + "/events"),
        device, terminal, onStatus, onWarning, onConnection, onFatal);
    stream.start();
    return stream;
  }

  private <T> T request(String method, String path, Object body, JavaType type) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(base + "/api" + path))
              .timeout(Duration.ofSeconds(45))
              .header("X-Device-Key", device)
              .header("X-Terminal-ID", terminal)
              .header("Accept", "application/json");
      String current = token;
      String route = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
      if (current != null && (route.startsWith("/admin/") || route.equals("/readers/new"))) {
        builder.header("Authorization", "Bearer " + current);
      }
      if (method.equals("GET")) {
        builder.GET();
      } else {
        builder
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    body == null ? "{}" : json.writeValueAsString(body)));
      }
      HttpResponse<String> response =
          http().send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        JsonNode error = json.readTree(response.body());
        throw new ApiError(
            response.statusCode(),
            error.path("code").asText("REQUEST_FAILED"),
            error.path("message").asText("Chưa thực hiện được thao tác."));
      }
      return json.readValue(response.body(), type);
    } catch (ApiError e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiError(
          503, "CONNECTION", "Thao tác đã bị gián đoạn. Vui lòng kiểm tra lại kết quả.");
    } catch (Exception e) {
      throw new ApiError(
          503, "CONNECTION", "Không kết nối được máy chủ. Vui lòng kiểm tra máy chủ và thử lại.");
    }
  }

  static String query(String value) {
    return URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  static final class ApiError extends RuntimeException {

    final int status;
    final String code;

    ApiError(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }
  }
}
