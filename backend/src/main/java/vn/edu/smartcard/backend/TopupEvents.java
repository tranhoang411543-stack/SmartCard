package vn.edu.smartcard.backend;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.edu.smartcard.shared.Api;

/** Post-commit top-up signals; PostgreSQL rows remain authoritative across disconnects. */
@Component
final class TopupEvents {
  private static final Logger LOG = LoggerFactory.getLogger(TopupEvents.class);
  private final LocalConfig config;
  private final CardRepository repo;
  private final WalletService wallet;
  private final ReaderHub readers;
  private final ExecutorService listener = Executors.newSingleThreadExecutor();
  private final ExecutorService credits = Executors.newSingleThreadExecutor();
  private final ExecutorService sends = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
  private final Map<Long, Subscriber> subscribers = new ConcurrentHashMap<>();
  private final Map<Long, ScheduledFuture<?>> expiry = new ConcurrentHashMap<>();
  private final Map<Long, Integer> failures = new ConcurrentHashMap<>();
  private final Map<Long, String> warnings = new ConcurrentHashMap<>();
  private final AtomicInteger listenerWorkFailures = new AtomicInteger();
  private final AtomicBoolean dirty = new AtomicBoolean();
  private final AtomicBoolean processing = new AtomicBoolean();
  private volatile boolean running, listening;
  private volatile Connection listenConnection;

  TopupEvents(LocalConfig config, CardRepository repo, WalletService wallet, ReaderHub readers) {
    this.config = config;
    this.repo = repo;
    this.wallet = wallet;
    this.readers = readers;
  }

  @EventListener(ApplicationReadyEvent.class)
  void start() {
    running = true;
    readers.onStudentReady(() -> {
      failures.clear();
      listenerWorkFailures.set(0);
      signalCredit();
    });
    listener.execute(this::listen);
    timers.scheduleWithFixedDelay(this::heartbeat, 15, 15, TimeUnit.SECONDS);
  }

  SseEmitter subscribe(String terminal, long id) {
    // Authenticate and bind the stream to this exact card insertion and PIN session.
    String connection = readers.useTopup(terminal, card -> {
      wallet.state(terminal, id);
      return readers.connection(terminal);
    });
    if (!listening) throw new Failure(503, "TOPUP_EVENTS_OFFLINE", "Chưa kết nối được trạng thái khoản nạp.");
    SseEmitter emitter = new SseEmitter(0L);
    Subscriber next = new Subscriber(id, terminal, connection, emitter);
    Subscriber previous = subscribers.put(id, next);
    if (previous != null) previous.close();
    emitter.onCompletion(() -> subscribers.remove(id, next));
    emitter.onTimeout(() -> next.close());
    emitter.onError(ignored -> next.close());
    // Register first, then read the current state: a concurrent change cannot be missed.
    try {
      Api.TopupEvent current = snapshot(id);
      if (current != null) send(() -> {
        next.status(current);
        String warning = warnings.get(id);
        if (warning != null) next.warning(warning);
      });
    }
    catch (RuntimeException failure) { next.close(); throw failure; }
    return emitter;
  }

  private void listen() {
    long retryMs = 250;
    while (running) {
      try (Connection connection = openListener(); Statement statement = connection.createStatement()) {
        listenConnection = connection;
        connection.setAutoCommit(true);
        statement.execute("SELECT set_config('application_name','ttm-topup-events:'||current_schema(),false)");
        statement.execute("LISTEN ttm_topup_status");
        PGConnection pg = connection.unwrap(PGConnection.class);
        listening = true;
        retryMs = 250;
        listenerWorkFailures.set(0);
        // Subscribe before scanning so a commit concurrent with this scan is not lost.
        repo.db.update("UPDATE topups SET status='EXPIRED' WHERE status='WAITING_BANK'"
            + " AND expires_at<=clock_timestamp()");
        for (Long id : repo.db.queryForList("SELECT id FROM topups WHERE status IN"
            + " ('WAITING_BANK','BANK_RECEIVED') ORDER BY id", Long.class)) changed(id);
        signalCredit();
        while (running) {
          PGNotification[] notices = pg.getNotifications(10_000);
          if (notices == null) continue;
          for (PGNotification notice : notices) {
            try { changed(Long.parseLong(notice.getParameter())); }
            catch (NumberFormatException ignored) { LOG.warn("Bỏ qua tín hiệu khoản nạp không hợp lệ."); }
          }
        }
      } catch (SQLException | RuntimeException failure) {
        if (running) LOG.warn("Kết nối sự kiện khoản nạp gián đoạn ({}).", failure.getClass().getSimpleName());
      } finally {
        listening = false;
        listenConnection = null;
        subscribers.values().forEach(Subscriber::close);
      }
      if (running) {
        try { Thread.sleep(retryMs); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        retryMs = Math.min(10_000, retryMs * 2);
      }
    }
  }

  private Connection openListener() throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("user", config.required("db.user"));
    properties.setProperty("password", config.required("db.password"));
    properties.setProperty("connectTimeout", "5");
    return DriverManager.getConnection(config.required("db.url"), properties);
  }

  private void changed(long id) {
    Api.TopupEvent event = snapshot(id);
    if (event == null) {
      Subscriber old = subscribers.remove(id);
      if (old != null) old.close();
      cancelExpiry(id);
      warnings.remove(id);
      return;
    }
    if (event.status().equals("WAITING_BANK")) scheduleExpiry(id);
    else {
      cancelExpiry(id);
      if (!event.status().equals("BANK_RECEIVED")) {
        readers.releaseTopup(id);
        failures.remove(id);
        warnings.remove(id);
      }
    }
    publish(event);
    if (event.status().equals("BANK_RECEIVED") || event.status().equals("CREDITED")) signalCredit();
  }

  private Api.TopupEvent snapshot(long id) {
    var values = repo.db.query(
        "SELECT id,status_revision,status FROM topups WHERE id=?",
        (r, n) -> {
          String status = r.getString("status");
          String message = switch (status) {
            case "BANK_RECEIVED" -> "Đã nhận tiền. Đang ghi vào thẻ…";
            case "CREDITED" -> "Đã nạp tiền vào thẻ.";
            case "EXPIRED" -> "Yêu cầu đã hết hạn hoặc đã hủy.";
            case "REVIEW" -> "Khoản chuyển cần đối chiếu. Vui lòng liên hệ quản trị viên.";
            default -> "Đang chờ chuyển khoản.";
          };
          return new Api.TopupEvent(r.getLong("id"), r.getLong("status_revision"), status, message);
        }, id);
    return values.isEmpty() ? null : values.getFirst();
  }

  private void publish(Api.TopupEvent event) {
    Subscriber subscriber = subscribers.get(event.id());
    if (subscriber != null) send(() -> subscriber.status(event));
  }

  private void warning(long id, String message) {
    warnings.put(id, message);
    Subscriber subscriber = subscribers.get(id);
    if (subscriber != null) send(() -> subscriber.warning(message));
  }

  private void heartbeat() {
    if (!running) return;
    subscribers.values().forEach(s -> {
      if (s.heartbeatQueued.compareAndSet(false, true)) send(() -> {
        try { s.heartbeat(); } finally { s.heartbeatQueued.set(false); }
      });
    });
  }

  private void send(Runnable task) {
    if (!running) return;
    try { sends.execute(task); }
    catch (RejectedExecutionException ignored) { /* shutting down */ }
  }

  private void scheduleExpiry(long id) {
    var rows = repo.db.queryForList("SELECT GREATEST(0,CEIL(EXTRACT(EPOCH FROM"
        + " (expires_at-clock_timestamp()))*1000))::bigint FROM topups"
        + " WHERE id=? AND status='WAITING_BANK'", Long.class, id);
    if (rows.isEmpty()) { cancelExpiry(id); return; }
    ScheduledFuture<?> next = timers.schedule(() -> expire(id), rows.getFirst(), TimeUnit.MILLISECONDS);
    ScheduledFuture<?> old = expiry.put(id, next);
    if (old != null) old.cancel(false);
  }

  private void expire(long id) {
    if (!running) return;
    try {
      int changed = repo.db.update("UPDATE topups SET status='EXPIRED' WHERE id=?"
          + " AND status='WAITING_BANK' AND expires_at<=clock_timestamp()", id);
      if (changed != 0) readers.releaseTopup(id);
      else scheduleExpiry(id); // The database clock may not yet have reached the deadline.
    } catch (RuntimeException failure) {
      LOG.warn("Chưa xác nhận được hạn khoản nạp ({}).", failure.getClass().getSimpleName());
      if (running) timers.schedule(() -> expire(id), 5, TimeUnit.SECONDS);
    }
  }

  private void cancelExpiry(long id) {
    ScheduledFuture<?> previous = expiry.remove(id);
    if (previous != null) previous.cancel(false);
  }

  private void signalCredit() {
    if (!running) return;
    dirty.set(true);
    if (!processing.compareAndSet(false, true)) return;
    try { credits.execute(this::drainCredits); }
    catch (RejectedExecutionException ignored) { processing.set(false); }
  }

  private void drainCredits() {
    try {
      do {
        dirty.set(false);
        WalletService.CreditAttempt attempt = wallet.processOneReceivedCredit();
        if (attempt.credited()) {
          listenerWorkFailures.set(0);
          failures.remove(attempt.topupId());
          dirty.set(true); // Process any earlier received request for the same card.
        } else if (attempt.topupId() != 0 && !attempt.error().isBlank()) {
          if (attempt.error().startsWith("SYNC_") || attempt.error().equals("CARD_REMOVED")) {
            warning(attempt.topupId(), "Đã nhận tiền. Cần đồng bộ lại thẻ trước khi tiếp tục.");
          } else {
            int count = failures.merge(attempt.topupId(), 1, Integer::sum);
            if (count <= 4 && running) {
              warning(attempt.topupId(), "Đã nhận tiền. Đang thử ghi lại an toàn…");
              timers.schedule(this::signalCredit, 1L << (count - 1), TimeUnit.SECONDS);
            } else warning(attempt.topupId(), "Đã nhận tiền. Vui lòng kiểm tra kết nối và đồng bộ lại.");
          }
        } else if (!attempt.error().isBlank()) {
          int count = listenerWorkFailures.incrementAndGet();
          if (count <= 4 && running) timers.schedule(this::signalCredit, 1L << (count - 1), TimeUnit.SECONDS);
          else subscribers.keySet().forEach(id -> warning(id,
              "Chưa kiểm tra được khoản nạp. Vui lòng kiểm tra kết nối và đồng bộ lại."));
        } else {
          listenerWorkFailures.set(0);
        }
      } while (running && dirty.get());
    } finally {
      processing.set(false);
      if (running && dirty.get()) signalCredit();
    }
  }

  @PreDestroy
  void stop() {
    running = false;
    listening = false;
    readers.onStudentReady(null);
    subscribers.values().forEach(Subscriber::close);
    expiry.values().forEach(future -> future.cancel(false));
    Connection connection = listenConnection;
    if (connection != null) try { connection.close(); } catch (SQLException ignored) {}
    listener.shutdownNow();
    credits.shutdownNow();
    timers.shutdownNow();
    sends.shutdownNow();
  }

  private final class Subscriber {
    final long id;
    final String terminal, connection;
    final SseEmitter emitter;
    final AtomicBoolean heartbeatQueued = new AtomicBoolean();
    long lastRevision = -1;
    boolean closed;

    Subscriber(long id, String terminal, String connection, SseEmitter emitter) {
      this.id = id;
      this.terminal = terminal;
      this.connection = connection;
      this.emitter = emitter;
    }

    synchronized void status(Api.TopupEvent event) {
      if (closed || event.revision() <= lastRevision) return;
      if (!readers.sameStudentSession(terminal, connection)) { close(); return; }
      try {
        emitter.send(SseEmitter.event().name("status").id(Long.toString(event.revision()))
            .data(event, MediaType.APPLICATION_JSON));
        lastRevision = event.revision();
        if (Set.of("CREDITED", "EXPIRED", "REVIEW").contains(event.status())) close();
      } catch (IOException | IllegalStateException failure) { close(); }
    }

    synchronized void warning(String message) {
      if (closed || !readers.sameStudentSession(terminal, connection)) { close(); return; }
      try { emitter.send(SseEmitter.event().name("warning").data(message, MediaType.TEXT_PLAIN)); }
      catch (IOException | IllegalStateException failure) { close(); }
    }

    synchronized void heartbeat() {
      if (closed || !readers.sameStudentSession(terminal, connection)) { close(); return; }
      try { emitter.send(SseEmitter.event().comment("keepalive")); }
      catch (IOException | IllegalStateException failure) { close(); }
    }

    synchronized void close() {
      if (closed) return;
      closed = true;
      subscribers.remove(id, this);
      try { emitter.complete(); } catch (IllegalStateException ignored) {}
    }
  }
}
