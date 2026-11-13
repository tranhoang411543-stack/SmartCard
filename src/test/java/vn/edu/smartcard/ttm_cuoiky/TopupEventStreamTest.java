package vn.edu.smartcard.ttm_cuoiky;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import vn.edu.smartcard.shared.Api;

class TopupEventStreamTest {
  @Test
  void reconnectsAndReadsTheLatestStatusWithoutPostingACredit() throws Exception {
    var calls = new AtomicInteger();
    var headers = new AtomicReference<List<String>>();
    var events = new CopyOnWriteArrayList<Api.TopupEvent>();
    var connections = new CopyOnWriteArrayList<Boolean>();
    var warning = new AtomicReference<String>();
    var fatal = new AtomicReference<ApiClient.ApiError>();
    CountDownLatch done = new CountDownLatch(1);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/events", exchange -> {
      headers.set(List.of(exchange.getRequestMethod(),
          exchange.getRequestHeaders().getFirst("X-Device-Key"),
          exchange.getRequestHeaders().getFirst("X-Terminal-ID")));
      int number = calls.incrementAndGet();
      String payload = number == 1
          ? ": keepalive\n\nevent: status\ndata: {\"id\":7,\"revision\":0,\"status\":\"WAITING_BANK\",\"message\":\"\"}\n\n"
              + "event: warning\ndata: Đang kiểm tra\n\n"
          : "event: status\ndata: {\"id\":7,\"revision\":2,\"status\":\"CREDITED\",\"message\":\"\"}\n\n";
      byte[] body = payload.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream;charset=UTF-8");
      exchange.sendResponseHeaders(200, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    server.start();
    URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/events");
    try (TopupEventStream stream = new TopupEventStream(HttpClient.newHttpClient(), uri,
        "fixture-device", "fixture-terminal", event -> {
          events.add(event);
          if (event.status().equals("CREDITED")) done.countDown();
        }, warning::set, connections::add, fatal::set)) {
      stream.start();
      assertTrue(done.await(8, TimeUnit.SECONDS));
      assertEquals(List.of("GET", "fixture-device", "fixture-terminal"), headers.get());
      assertEquals(List.of("WAITING_BANK", "CREDITED"), events.stream().map(Api.TopupEvent::status).toList());
      assertEquals(List.of(true, false, true), connections);
      assertEquals("Đang kiểm tra", warning.get());
      assertEquals(2, calls.get());
      assertNull(fatal.get());
    } finally { server.stop(0); }
  }

  @Test
  void forbiddenStreamIsReportedInsteadOfRetried() throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var error = new AtomicReference<ApiClient.ApiError>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/events", exchange -> {
      calls.incrementAndGet();
      byte[] body = "{\"code\":\"NO_CARD\",\"message\":\"Không có thẻ\"}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(409, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    server.start();
    try (TopupEventStream stream = new TopupEventStream(HttpClient.newHttpClient(),
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/events"),
        "device", "terminal", event -> fail("Unexpected status"), message -> {}, connected -> {}, failure -> {
          error.set(failure);
          done.countDown();
        })) {
      stream.start();
      assertTrue(done.await(5, TimeUnit.SECONDS));
      assertEquals(409, error.get().status);
      assertEquals("NO_CARD", error.get().code);
      assertEquals(1, calls.get());
    } finally { server.stop(0); }
  }
}
