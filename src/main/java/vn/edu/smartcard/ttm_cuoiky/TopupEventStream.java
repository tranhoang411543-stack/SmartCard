package vn.edu.smartcard.ttm_cuoiky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import vn.edu.smartcard.shared.Api;

/** A single reconnecting SSE stream. No periodic status requests are made. */
final class TopupEventStream implements AutoCloseable {
  private final HttpClient http;
  private final URI uri;
  private final String device, terminal;
  private final Consumer<Api.TopupEvent> onStatus;
  private final Consumer<String> onWarning;
  private final Consumer<Boolean> onConnection;
  private final Consumer<ApiClient.ApiError> onFatal;
  private final ObjectMapper json = new ObjectMapper();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread thread = new Thread(r, "topup-stream-watchdog");
    thread.setDaemon(true);
    return thread;
  });
  private volatile InputStream input;
  private volatile CompletableFuture<HttpResponse<InputStream>> pendingRequest;
  private volatile long lastActivity;
  private volatile Thread worker;

  TopupEventStream(HttpClient http, URI uri, String device, String terminal,
      Consumer<Api.TopupEvent> onStatus, Consumer<String> onWarning,
      Consumer<Boolean> onConnection, Consumer<ApiClient.ApiError> onFatal) {
    this.http = http;
    this.uri = uri;
    this.device = device;
    this.terminal = terminal;
    this.onStatus = onStatus;
    this.onWarning = onWarning;
    this.onConnection = onConnection;
    this.onFatal = onFatal;
  }

  void start() {
    watchdog.scheduleWithFixedDelay(() -> {
      InputStream active = input;
      if (active != null && System.nanoTime() - lastActivity > TimeUnit.SECONDS.toNanos(45)) {
        try { active.close(); } catch (IOException ignored) {}
      }
    }, 15, 15, TimeUnit.SECONDS);
    worker = Thread.ofVirtual().name("topup-event-stream").start(this::run);
  }

  private void run() {
    long delayMs = 500;
    try {
      while (!closed.get()) {
        try {
          HttpRequest request = HttpRequest.newBuilder(uri)
              .header("X-Device-Key", device).header("X-Terminal-ID", terminal)
              .header("Accept", "text/event-stream").GET().build();
          CompletableFuture<HttpResponse<InputStream>> pending =
              http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
          pendingRequest = pending;
          HttpResponse<InputStream> response;
          try {
            response = pending.get(10, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            pending.cancel(true);
            throw interrupted;
          } catch (TimeoutException | ExecutionException failure) {
            pending.cancel(true);
            throw new IOException("Event stream connection failed", failure);
          } finally {
            pendingRequest = null;
          }
          try (InputStream body = response.body()) {
            if (closed.get()) return;
            if (response.statusCode() != 200) {
              byte[] bytes = body.readNBytes(8192);
              if (response.statusCode() >= 400 && response.statusCode() < 500
                  && response.statusCode() != 408 && response.statusCode() != 429) {
                String code = "REQUEST_FAILED", message = "Không theo dõi được khoản nạp.";
                try {
                  JsonNode error = json.readTree(bytes);
                  if (error != null) {
                    code = error.path("code").asText(code);
                    message = error.path("message").asText(message);
                  }
                } catch (IOException ignored) {}
                onFatal.accept(new ApiClient.ApiError(response.statusCode(), code, message));
                return;
              }
              throw new IOException("Event stream unavailable");
            }
            String type = response.headers().firstValue("Content-Type").orElse("")
                .toLowerCase(Locale.ROOT);
            if (!type.startsWith("text/event-stream")) throw new IOException("Unexpected event stream");
            input = body;
            lastActivity = System.nanoTime();
            onConnection.accept(true);
            delayMs = 500;
            if (readEvents(body)) return;
          } finally { input = null; }
        } catch (InterruptedException interrupted) {
          if (closed.get()) return;
          Thread.interrupted();
        } catch (IOException | RuntimeException failure) {
          // A disconnected stream says nothing about whether a transfer succeeded.
        }
        if (closed.get()) return;
        onConnection.accept(false);
        try { Thread.sleep(delayMs); }
        catch (InterruptedException interrupted) { if (closed.get()) return; }
        delayMs = Math.min(10_000, delayMs * 2);
      }
    } finally { watchdog.shutdownNow(); }
  }

  private boolean readEvents(InputStream body) throws IOException {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
      String event = "message", line;
      StringBuilder data = new StringBuilder();
      while (!closed.get() && (line = reader.readLine()) != null) {
        lastActivity = System.nanoTime();
        if (line.isEmpty()) {
          if (!data.isEmpty() && dispatch(event, data.toString())) return true;
          event = "message";
          data.setLength(0);
        } else if (line.startsWith("event:")) event = line.substring(6).strip();
        else if (line.startsWith("data:")) {
          if (!data.isEmpty()) data.append('\n');
          String value = line.substring(5);
          data.append(value.startsWith(" ") ? value.substring(1) : value);
          if (data.length() > 32_768) throw new IOException("Event too large");
        }
      }
      return false;
    }
  }

  private boolean dispatch(String name, String data) throws IOException {
    if (closed.get()) return true;
    if (name.equals("status")) {
      Api.TopupEvent event = json.readValue(data, Api.TopupEvent.class);
      onStatus.accept(event);
      return java.util.Set.of("CREDITED", "EXPIRED", "REVIEW").contains(event.status());
    } else if (name.equals("warning")) onWarning.accept(data);
    return false;
  }

  @Override public void close() {
    if (!closed.compareAndSet(false, true)) return;
    InputStream active = input;
    CompletableFuture<HttpResponse<InputStream>> pending = pendingRequest;
    Thread thread = worker;
    if (thread != null) thread.interrupt();
    if (pending != null) pending.cancel(true);
    if (active != null) Thread.startVirtualThread(() -> {
      try { active.close(); } catch (IOException ignored) {}
    });
    watchdog.shutdownNow();
  }
}
