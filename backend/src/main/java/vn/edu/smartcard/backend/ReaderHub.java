package vn.edu.smartcard.backend;

import com.licel.jcardsim.base.PersistentSimulatorRuntime;
import com.licel.jcardsim.esotericsoftware.kryo.io.Input;
import com.licel.jcardsim.esotericsoftware.kryo.io.Output;
import com.licel.jcardsim.smartcardio.CardSimulator;
import java.io.*;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javacard.framework.AID;
import javax.smartcardio.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import vn.edu.smartcard.card.StudentCardApplet;
import vn.edu.smartcard.shared.*;

/** Owns independent reader connections and prevents two stations from claiming the same device. */
@Component
@DependsOn("flyway")
public class ReaderHub {
  static {
    if (System.getProperty("com.licel.jcardsim.randomdata.seed") != null)
      throw new IllegalStateException("Không dùng seed cố định.");
    System.setProperty("com.licel.jcardsim.randomdata.secure", "1");
  }

  private final Path directory;
  private final Map<String, SimCard> cards = new java.util.concurrent.ConcurrentSkipListMap<>();
  private final Object registry = new Object();
  private final Map<String, ReaderSession> byTerminal = new java.util.concurrent.ConcurrentHashMap<>();
  private final Map<String, ReaderSession> byDevice = new java.util.concurrent.ConcurrentHashMap<>();
  private final Set<String> closingTerminals = new HashSet<>();
  private final java.util.concurrent.locks.ReentrantReadWriteLock mutationGate =
      new java.util.concurrent.locks.ReentrantReadWriteLock(true);
  private final ThreadLocal<ReaderSession> active = new ThreadLocal<>();
  private volatile Runnable studentReady = () -> {};
  private volatile Function<String, Api.CardLifecycle> lifecycle =
      id -> new Api.CardLifecycle(id, "ACTIVE", 0);

  private static final class ReaderSession {
    final String terminal, alias, connectionId = UUID.randomUUID().toString();
    final AtomicInteger operations = new AtomicInteger();
    final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
    final AtomicBoolean disconnectPending = new AtomicBoolean();
    final ReentrantLock inspectionGate = new ReentrantLock(true);
    final java.util.concurrent.atomic.AtomicLong reservedTopup = new java.util.concurrent.atomic.AtomicLong();
    volatile String reservedCard = "", cardId = "";
    volatile CardClient client;
    Card physical;
    volatile boolean studentSession, managementSession;
    boolean managementPrepared;
    long paymentInvoice;

    ReaderSession(String terminal, String alias) {
      this.terminal = terminal;
      this.alias = alias;
    }
  }

  @Autowired
  public ReaderHub(SecurityMaterial unused, LocalConfig config) {
    this(unused, config, true);
  }

  ReaderHub(SecurityMaterial unused, LocalConfig config, boolean cleanLegacy) {
    if (cleanLegacy) LegacyCleanup.clean(config.root());
    directory = config.root().resolve("cards").toAbsolutePath().normalize();
    try {
      Files.createDirectories(directory);
      try (var stream = Files.list(directory)) {
        for (Path f : stream.toList()) {
          String name = f.getFileName().toString();
          if (!name.matches("SIM[0-9]{4}\\.card(\\..*)?")) continue;
          if (!f.toAbsolutePath().normalize().getParent().equals(directory))
            throw new IOException("Unexpected card path");
          if (!name.endsWith(".card")) continue;
          if (!SnapshotRuntime.isV3(Files.readAllBytes(f)))
            throw new IOException("Unsupported card snapshot; original file preserved: " + name);
          String card = name.substring(0, 7);
          cards.put(card, new SimCard(card));
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("Không đọc được thẻ mô phỏng.", e);
    }
  }

  public void lifecycle(Function<String, Api.CardLifecycle> value) { lifecycle = value; }

  public synchronized List<Api.DeviceChoice> choices() {
    List<Api.DeviceChoice> result = new ArrayList<>();
    for (String n : cards.keySet()) result.add(new Api.DeviceChoice(n, "Thẻ mô phỏng " + n));
    try {
      for (CardTerminal t : TerminalFactory.getDefault().terminals().list())
        result.add(new Api.DeviceChoice("PCSC:" + t.getName(), "Đầu đọc: " + t.getName()));
    } catch (CardException ignored) {}
    return result;
  }

  public synchronized String newCard() {
    int n = 1;
    while (cards.containsKey(String.format("SIM%04d", n))) n++;
    if (n > 9999) throw Failure.bad("Đã hết mã thẻ mô phỏng.");
    String name = String.format("SIM%04d", n);
    SimCard card = new SimCard(name);
    cards.put(name, card);
    card.save();
    return name;
  }

  synchronized String newNamedCard(String name) {
    if (name == null || !name.matches("SIM[0-9]{4}"))
      throw Failure.bad("ID thẻ mô phỏng không hợp lệ.");
    Path file = directory.resolve(name + ".card");
    if (cards.containsKey(name) || Files.exists(file, LinkOption.NOFOLLOW_LINKS))
      throw Failure.bad("Thẻ " + name + " đã tồn tại; không ghi đè.");
    SimCard card = new SimCard(name);
    card.saveNew();
    cards.put(name, card);
    return name;
  }

  public Api.ReaderStatus connect(String terminal, String selected) {
    return connect(terminal, selected, (client, status) -> {});
  }

  Api.ReaderStatus connect(String terminal, String selected,
      BiConsumer<CardClient, Api.ReaderStatus> authenticate) {
    if (terminal == null || terminal.isBlank() || selected == null || selected.isBlank())
      throw Failure.bad("Vui lòng chọn thẻ hoặc đầu đọc.");
    mutationGate.readLock().lock();
    ReaderSession session;
    try {
      synchronized (registry) {
        if (closingTerminals.contains(terminal)) throw readerBusy();
        if (byTerminal.containsKey(terminal))
          throw new Failure(409, "READER_IN_USE", "Quầy này đã kết nối thẻ. Vui lòng ngắt kết nối trước.");
        if (byDevice.containsKey(selected))
          throw new Failure(409, "READER_IN_USE", "Thẻ đang được kết nối ở quầy khác. Vui lòng ngắt kết nối tại quầy đó trước.");
        session = new ReaderSession(terminal, selected);
        beginOperation(session);
        byTerminal.put(terminal, session);
        byDevice.put(selected, session);
      }
      try {
        synchronized (session) {
          connectInternal(session);
          Api.ReaderStatus status = statusInternal(session);
          if (!status.present()) throw Failure.missing("Không phát hiện thẻ.");
          authenticate.accept(session.client, status);
          return status;
        }
      } catch (RuntimeException | Error failure) {
        synchronized (session) { ejectInternal(session); }
        throw failure;
      } finally { endOperation(session); }
    } finally { mutationGate.readLock().unlock(); }
  }

  private void connectInternal(ReaderSession session) {
    String selected = session.alias;
    if (selected.startsWith("PCSC:")) {
      try {
        CardTerminal target = TerminalFactory.getDefault().terminals().getTerminal(selected.substring(5));
        if (target == null || !target.isCardPresent()) throw Failure.missing("Không phát hiện thẻ.");
        Card physical = target.connect("*");
        session.physical = physical;
        ResponseAPDU response = physical.getBasicChannel().transmit(new CommandAPDU(0, 0xA4, 4, 0, Wire.AID));
        if (response.getSW() != 0x9000) throw Failure.bad("Thẻ chưa cài applet V3.");
        session.client = new CardClient(bytes -> {
          try { return physical.getBasicChannel().transmit(new CommandAPDU(bytes)).getBytes(); }
          catch (CardException e) { throw new Failure(409, "CARD_REMOVED", "Mất kết nối với thẻ. Vui lòng kết nối lại."); }
        });
      } catch (CardException e) { throw Failure.bad("Không kết nối được đầu đọc."); }
    } else {
      SimCard card = cards.get(selected);
      if (card == null) throw Failure.missing("Không tìm thấy thẻ mô phỏng.");
      card.sim.reset();
      card.sim.selectApplet(card.aid);
      session.client = new CardClient(bytes -> {
        if (byTerminal.get(session.terminal) != session)
          throw new Failure(409, "CARD_REMOVED", "Thẻ đã bị rút. Kết nối lại đúng thẻ để đối soát.");
        return card.exchange(bytes);
      });
    }
    session.client.protocol();
  }

  public Api.ReaderStatus status(String terminal) {
    ReaderSession session = byTerminal.get(terminal);
    return session == null ? absent() : inspect(session, () -> statusInternal(session));
  }

  private Api.ReaderStatus statusInternal(ReaderSession session) {
    if (session.client == null || byTerminal.get(session.terminal) != session) return absent();
    try {
      Api.ReaderStatus state = session.client.status(session.alias);
      if (state.initialized()) rememberCardId(session, state.cardId());
      Api.CardLifecycle policy = state.initialized() ? lifecycle.apply(state.cardId())
          : new Api.CardLifecycle("", "ACTIVE", 0);
      if (policy.status().equals("BLOCKED") && session.studentSession) endStudent(session.terminal);
      return new Api.ReaderStatus(true, session.alias, state.initialized(), state.cardId(),
          Math.max(0, 5 - policy.pinFailures()),
          session.studentSession ? state.role() : state.role().equals("ADMIN") ? "ADMIN" : "NONE",
          state.counter(), state.profileLength(), session.connectionId, policy.status());
    } catch (Failure failure) {
      if (failure.code.equals("READER_BUSY")) throw failure;
      if (failure.code.equals("READER_IN_USE")) throw failure;
      if (failure.code.equals("NOT_FOUND")) {
        Api.ReaderStatus state = session.client.status(session.alias);
        return new Api.ReaderStatus(true, session.alias, state.initialized(), state.cardId(), 0,
            "NONE", state.counter(), state.profileLength(), session.connectionId, "UNREGISTERED");
      }
      ejectInternal(session);
      return absent();
    }
  }

  void rememberCardId(String terminal, String cardId) { rememberCardId(requireSession(terminal), cardId); }

  private void rememberCardId(ReaderSession session, String cardId) {
    if (cardId == null || cardId.isBlank()) return;
    synchronized (registry) {
      for (ReaderSession other : byTerminal.values())
        if (other != session && cardId.equals(other.cardId))
          throw new Failure(409, "READER_IN_USE", "Thẻ đang được kết nối ở quầy khác. Vui lòng ngắt kết nối tại quầy đó trước.");
      session.cardId = cardId;
    }
  }

  private Api.ReaderStatus absent() {
    return new Api.ReaderStatus(false, "", false, "", 5, "NONE", 0, 0, "", "");
  }

  public Api.ReaderActivity activity(String terminal) { return activity(terminal, null); }

  public Api.ReaderActivity activity(String terminal, String selected) {
    ReaderSession own = byTerminal.get(terminal);
    ReaderSession chosen = selected == null || selected.isBlank() ? null : byDevice.get(selected);
    return new Api.ReaderActivity(own != null, own != null,
        own != null && (own.operations.get() != 0 || own.disconnectPending.get()),
        own != null && own.reservedTopup.get() != 0,
        Objects.toString(selected, ""), chosen != null && chosen != own);
  }

  public void eject(String terminal) {
    ReaderSession session = byTerminal.get(terminal);
    if (session == null) {
      if (!byTerminal.isEmpty()) throw new Failure(409, "READER_IN_USE", "Vui lòng ngắt kết nối tại quầy đang sử dụng thẻ.");
      return;
    }
    if (!session.disconnectPending.compareAndSet(false, true)) throw readerBusy();
    try {
      if (session.operations.get() != 0) throw readerBusy();
      if (!session.inspectionGate.tryLock(5, TimeUnit.SECONDS)) throw readerBusy();
      try {
        synchronized (session) {
          if (session.operations.get() != 0) throw readerBusy();
          if (byTerminal.get(terminal) != session) return;
          if (session.reservedTopup.get() != 0) throw topupReserved();
          session.operations.incrementAndGet();
          try { ejectInternal(session); } finally { session.operations.decrementAndGet(); }
        }
      } finally { session.inspectionGate.unlock(); }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw readerBusy();
    } finally { session.disconnectPending.set(false); }
  }

  private void beginOperation(ReaderSession session) {
    int depth = session.depth.get();
    if (depth == 0 && session.disconnectPending.get()) throw readerBusy();
    session.operations.incrementAndGet();
    if (depth == 0 && session.disconnectPending.get()) {
      session.operations.decrementAndGet();
      throw readerBusy();
    }
    session.depth.set(depth + 1);
  }

  private void endOperation(ReaderSession session) {
    int depth = session.depth.get() - 1;
    if (depth == 0) session.depth.remove(); else session.depth.set(depth);
    session.operations.decrementAndGet();
  }

  private <T> T withActive(ReaderSession session, Supplier<T> action) {
    ReaderSession previous = active.get();
    active.set(session);
    try { return action.get(); }
    finally { if (previous == null) active.remove(); else active.set(previous); }
  }

  private <T> T inspect(ReaderSession session, Supplier<T> action) {
    if (Thread.holdsLock(session)) return withActive(session, action);
    if (session.disconnectPending.get()) throw readerBusy();
    try { session.inspectionGate.lockInterruptibly(); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw readerBusy(); }
    try {
      if (session.disconnectPending.get()) throw readerBusy();
      synchronized (session) { return withActive(session, action); }
    } finally { session.inspectionGate.unlock(); }
  }

  <T> T inspectTopup(String terminal, Function<CardClient, T> action) {
    mutationGate.readLock().lock();
    try {
      ReaderSession session = requireSession(terminal);
      return inspect(session, () -> {
        requireOwner(session);
        return action.apply(session.client);
      });
    } finally { mutationGate.readLock().unlock(); }
  }

  private static Failure readerBusy() {
    return new Failure(409, "READER_BUSY", "Thẻ đang được xử lý. Vui lòng đợi thao tác hoàn tất.");
  }

  private static Failure topupReserved() {
    return new Failure(409, "TOPUP_IN_PROGRESS", "Yêu cầu nạp đang chờ xử lý. Hãy hoàn tất hoặc hủy yêu cầu trước khi ngắt kết nối.");
  }

  void reserveTopup(String terminal, String cardId, long topupId) {
    ReaderSession session = requireSession(terminal);
    synchronized (session) {
      requireOwner(session);
      if (session.reservedTopup.get() != 0 && session.reservedTopup.get() != topupId) throw topupReserved();
      session.reservedCard = cardId;
      session.reservedTopup.set(topupId);
    }
  }

  long reservedTopup(String terminal) {
    ReaderSession session = byTerminal.get(terminal);
    return session == null ? 0 : session.reservedTopup.get();
  }

  List<Long> reservedTopups() {
    return byTerminal.values().stream().map(s -> s.reservedTopup.get()).filter(id -> id != 0).toList();
  }

  void releaseTopup(long topupId) {
    if (topupId == 0) return;
    for (ReaderSession session : byTerminal.values()) session.reservedTopup.compareAndSet(topupId, 0);
  }

  void guardCardMutation(String cardId, Runnable action) {
    mutationGate.writeLock().lock();
    try {
      ReaderSession session = byTerminal.values().stream().filter(s -> cardId.equals(s.cardId)).findFirst().orElse(null);
      if (session == null) { action.run(); return; }
      beginOperation(session);
      try {
        synchronized (session) {
          if (session.reservedTopup.get() != 0 && session.reservedCard.equals(cardId)) throw topupReserved();
          action.run();
        }
      } finally { endOperation(session); }
    } finally { mutationGate.writeLock().unlock(); }
  }

  void closeStation(String terminal, Runnable action) {
    ReaderSession session;
    synchronized (registry) {
      if (!closingTerminals.add(terminal)) throw readerBusy();
      session = byTerminal.get(terminal);
    }
    try {
      if (session == null) { action.run(); return; }
      if (session.operations.get() != 0) throw readerBusy();
      synchronized (session) {
        if (byTerminal.get(terminal) != session) { action.run(); return; }
        if (session.operations.get() != 0) throw readerBusy();
        if (session.reservedTopup.get() != 0) throw topupReserved();
        beginOperation(session);
        try { action.run(); ejectInternal(session); } finally { endOperation(session); }
      }
    } finally {
      synchronized (registry) { closingTerminals.remove(terminal); }
    }
  }

  private void ejectInternal(ReaderSession session) {
    if (session.client != null) try { session.client.logout(); } catch (RuntimeException ignored) {}
    if (session.physical != null) try { session.physical.disconnect(true); } catch (CardException ignored) {}
    session.client = null;
    session.physical = null;
    session.reservedTopup.set(0);
    session.reservedCard = "";
    session.studentSession = false;
    session.managementSession = false;
    session.managementPrepared = false;
    session.paymentInvoice = 0;
    synchronized (registry) {
      byTerminal.remove(session.terminal, session);
      byDevice.remove(session.alias, session);
    }
  }

  public String connection(String terminal) {
    ReaderSession session = requireSession(terminal);
    synchronized (session) { requireOwner(session); return session.connectionId; }
  }

  void onStudentReady(Runnable callback) { studentReady = callback == null ? () -> {} : callback; }
  void signalStudentReady() { studentReady.run(); }

  boolean sameStudentSession(String terminal, String connection) {
    ReaderSession session = byTerminal.get(terminal);
    return session != null && session.studentSession && session.managementSession
        && session.connectionId.equals(connection);
  }

  public boolean managementPrepared(String terminal) {
    ReaderSession session = requireSession(terminal);
    synchronized (session) { requireOwner(session); return session.managementPrepared; }
  }

  public void markManagementPrepared(String terminal) {
    ReaderSession session = requireSession(terminal);
    synchronized (session) {
      requireOwner(session);
      session.managementPrepared = true;
      session.studentSession = false;
      session.managementSession = true;
    }
  }

  public void beginPinAttempt(String terminal) {
    ReaderSession session = requireSession(terminal);
    synchronized (session) {
      requireOwner(session);
      if (session.reservedTopup.get() != 0 && session.studentSession) throw topupReserved();
      session.studentSession = false;
    }
  }

  public void studentSession(String terminal) {
    ReaderSession session = requireSession(terminal);
    synchronized (session) {
      requireOwner(session);
      session.studentSession = true;
      session.managementSession = true;
      session.managementPrepared = true;
    }
  }

  public void paymentSession(String terminal) {
    ReaderSession session = requireSession(terminal);
    synchronized (session) { studentSession(terminal); session.managementSession = false; }
  }

  public Api.ReaderStatus requireStudent(String terminal) {
    ReaderSession session = requireSession(terminal);
    synchronized (session) {
      requireOwner(session);
      Api.ReaderStatus status = session.studentSession ? session.client.status(session.alias) : null;
      if (status == null || !status.role().equals("STUDENT"))
        throw new Failure(401, "PIN_SESSION", "Phiên sinh viên đã kết thúc. Vui lòng nhập PIN lại.");
      return status;
    }
  }

  public void requireStudentCard(CardClient card) {
    ReaderSession session = active.get();
    if (session == null || card != session.client || byTerminal.get(session.terminal) != session)
      throw Failure.denied();
    requireStudent(session.terminal);
  }

  public void endStudent(String terminal) {
    ReaderSession session = byTerminal.get(terminal);
    if (session == null) return;
    beginOperation(session);
    try {
      synchronized (session) {
        if (session.client != null && byTerminal.get(terminal) == session) {
          if (session.reservedTopup.get() != 0) throw topupReserved();
          endStudentForRecovery(terminal);
        }
      }
    } finally { endOperation(session); }
  }

  void endStudentForRecovery(String terminal) {
    ReaderSession session = byTerminal.get(terminal);
    if (session == null) return;
    synchronized (session) {
      if (session.client != null && byTerminal.get(terminal) == session) {
        session.client.logout();
        session.studentSession = false;
        session.managementSession = false;
        session.managementPrepared = false;
        session.paymentInvoice = 0;
      }
    }
  }

  boolean isCurrentStudent(String terminal) {
    ReaderSession session = byTerminal.get(terminal);
    return session != null && session.client != null && session.studentSession && session.managementSession;
  }

  List<String> currentStudents() {
    return byTerminal.values().stream().filter(s -> s.client != null && s.studentSession && s.managementSession)
        .map(s -> s.terminal).sorted().toList();
  }

  public boolean paymentPrepared(String terminal, long invoice) {
    ReaderSession session = requireSession(terminal);
    synchronized (session) { requireOwner(session); return session.paymentInvoice == invoice; }
  }

  public void bindPayment(String terminal, long invoice) {
    ReaderSession session = requireSession(terminal);
    synchronized (session) {
      requireOwner(session);
      session.paymentInvoice = invoice;
      session.studentSession = false;
      session.managementSession = false;
      session.managementPrepared = false;
    }
  }

  public void endPayment(String terminal, long invoice) {
    ReaderSession session = byTerminal.get(terminal);
    if (session == null) return;
    synchronized (session) {
      if (session.client != null && session.paymentInvoice == invoice) endStudent(terminal);
    }
  }

  public void checkoutReady(String terminal) { requireOwner(requireSession(terminal)); }

  public void checkoutFinished(String terminal) {
    ReaderSession session = byTerminal.get(terminal);
    if (session == null) return;
    synchronized (session) { if (session.client != null) endStudent(terminal); }
  }

  public void invalidate(String cardId) {
    ReaderSession session = active.get();
    if (session == null || !cardId.equals(session.cardId))
      session = byTerminal.values().stream().filter(s -> cardId.equals(s.cardId)).findFirst().orElse(null);
    if (session == null) return;
    synchronized (session) {
      if (session.client != null && cardId.equals(session.cardId)) {
        session.client.logout();
        session.studentSession = false;
        session.managementSession = false;
        session.managementPrepared = false;
        session.paymentInvoice = 0;
      }
    }
  }

  private ReaderSession requireSession(String terminal) {
    ReaderSession session = byTerminal.get(terminal);
    if (session == null) throw new Failure(409, "NO_CARD", "Không phát hiện thẻ. Vui lòng kết nối lại.");
    return session;
  }

  private void requireOwner(ReaderSession session) {
    if (session.client == null || byTerminal.get(session.terminal) != session)
      throw new Failure(409, "NO_CARD", "Không phát hiện thẻ. Vui lòng kết nối lại.");
  }

  public <T> T use(String terminal, Function<CardClient, T> action) {
    return use(terminal, null, null, false, action);
  }

  <T> T useTopup(String terminal, Function<CardClient, T> action) {
    return use(terminal, null, null, true, action);
  }

  <T> T use(String terminal, String expectedConnection, String expectedCard, Function<CardClient, T> action) {
    return use(terminal, expectedConnection, expectedCard, false, action);
  }

  private <T> T use(String terminal, String expectedConnection, String expectedCard,
      boolean allowReserved, Function<CardClient, T> action) {
    ReaderSession session = requireSession(terminal);
    beginOperation(session);
    mutationGate.readLock().lock();
    try {
      synchronized (session) {
        requireOwner(session);
        if (!allowReserved && session.reservedTopup.get() != 0) throw topupReserved();
        if ((expectedConnection != null && !session.connectionId.equals(expectedConnection))
            || (expectedCard != null && !expectedCard.equals(session.client.status(session.alias).cardId())))
          throw new Failure(409, "CARD_CHANGED", "Thẻ đang kết nối đã thay đổi. Vui lòng chọn lại thẻ.");
        return withActive(session, () -> action.apply(session.client));
      }
    } finally {
      mutationGate.readLock().unlock();
      endOperation(session);
    }
  }

  public void deleteConnected(String terminal) {
    ReaderSession session = requireSession(terminal);
    synchronized (session) {
      requireOwner(session);
      String name = session.alias;
      try { endStudent(terminal); }
      catch (RuntimeException ignored) { session.studentSession = false; session.managementPrepared = false; }
      synchronized (this) {
        synchronized (registry) {
          ejectInternal(session);
          if (name.matches("SIM[0-9]{4}")) {
            cards.remove(name);
            Path file = directory.resolve(name + ".card").normalize();
            if (!file.getParent().equals(directory)) throw new IllegalStateException("Unexpected card path");
            try { Files.deleteIfExists(file); }
            catch (IOException e) {
              throw new Failure(409, "CARD_FILE_CLEANUP", "Đã xóa đăng ký CSDL; chưa dọn được file mô phỏng. Thẻ cũ không còn quyền sử dụng.");
            }
          }
        }
      }
    }
  }

  public long currentPayment(String terminal) {
    ReaderSession session = byTerminal.get(terminal);
    if (session == null) return 0;
    synchronized (session) { return session.paymentInvoice; }
  }

  public List<String> connectedOwners() { return List.copyOf(byTerminal.keySet()); }

  /** Windows can briefly deny replacement while another process inspects the old file. */
  static void replaceSnapshot(Path source, Path target) throws IOException {
    try {
      moveSnapshot(source, target);
    } catch (AccessDeniedException first) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw first;
      }
      try {
        moveSnapshot(source, target);
      } catch (IOException retry) {
        retry.addSuppressed(first);
        throw retry;
      }
    }
  }

  private static void moveSnapshot(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private final class SimCard {
    final AID aid = new AID(Wire.AID, (short) 0, (byte) Wire.AID.length);
    final SnapshotRuntime runtime = new SnapshotRuntime();
    final CardSimulator sim = new CardSimulator(runtime);
    final Path file;

    SimCard(String name) {
      file = directory.resolve(name + ".card");
      try {
        if (Files.exists(file)) {
          sim.loadApplet(aid, StudentCardApplet.class);
          runtime.restore(aid, Files.readAllBytes(file));
        } else sim.installApplet(aid, StudentCardApplet.class);
        sim.reset();
        sim.selectApplet(aid);
      } catch (Exception e) {
        throw new IllegalStateException("Không nạp được thẻ V3 " + name, e);
      }
    }

    byte[] exchange(byte[] bytes) {
      try {
        return sim.transmitCommand(new CommandAPDU(bytes)).getBytes();
      } finally {
        save();
      }
    }

    void save() {
      try {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, runtime.snapshot(aid));
        replaceSnapshot(tmp, file);
      } catch (IOException e) {
        throw new IllegalStateException("Không lưu được bộ nhớ thẻ.", e);
      }
    }

    void saveNew() {
      Path temporary = null;
      try {
        temporary = Files.createTempFile(directory, ".new-card-", ".tmp");
        Files.write(temporary, runtime.snapshot(aid));
        Files.move(temporary, file);
      } catch (IOException e) {
        throw new IllegalStateException("Không tạo được thẻ mới; thẻ hiện có không bị ghi đè.", e);
      } finally {
        if (temporary != null) {
          try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
      }
    }
  }

  static final class SnapshotRuntime extends PersistentSimulatorRuntime {
    static final Set<String> PERSISTENT =
        Set.of(
            "privateKey",
            "publicKey",
            "secret",
            "id",
            "identity",
            "counter",
            "balance",
            "walletVersion",
            "ciphertext0",
            "ciphertext1",
            "iv0",
            "iv1",
            "tag0",
            "tag1",
            "pin0",
            "pin1",
            "pinIv0",
            "pinIv1",
            "pinTag0",
            "pinTag1",
            "lengths",
            "cipherLengths",
            "p0",
            "p1",
            "c0",
            "c1",
            "active",
            "initialized",
            "phase",
            "preparedSlot",
            "pendingKind",
            "lastKind",
            "pendingOp",
            "pendingHash",
            "pendingId",
            "pendingBalance",
            "pendingWalletVersion",
            "lastOp",
            "lastHash",
            "lastAck",
            "financialHold",
            "debitId",
            "debitBefore",
            "debitAmount",
            "debitWallet",
            "hash0",
            "hash1",
            "hashValid");
    static final Set<String> FINANCIAL =
        Set.of("financialHold", "debitId", "debitBefore", "debitAmount", "debitWallet");

    SnapshotRuntime() {
      appletsDir = null;
      var fields =
          new com.licel.jcardsim.esotericsoftware.kryo.serializers.FieldSerializer<>(
              kryo, StudentCardApplet.class);
      for (var f : StudentCardApplet.class.getDeclaredFields())
        if (!Modifier.isStatic(f.getModifiers()) && !PERSISTENT.contains(f.getName()))
          fields.removeField(f.getName());
      kryo.register(StudentCardApplet.class, fields, 3001);
    }

    static boolean isV3(byte[] b) {
      return b.length > 4 && b[0] == 'T' && b[1] == 'T' && b[2] == 'M'
          && (b[3] == 3 || b[3] == 4 || b[3] == 5);
    }

    byte[] snapshot(AID aid) {
      activateSimulatorRuntimeInstance();
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      bytes.writeBytes(new byte[] {'T', 'T', 'M', 5});
      try (Output out = new Output(bytes)) {
        kryo.writeClassAndObject(out, getApplet(aid));
      }
      return bytes.toByteArray();
    }

    private void serializer(boolean legacy) {
      serializer(legacy ? 3 : 5);
    }

    private void serializer(int version) {
      var fields =
          new com.licel.jcardsim.esotericsoftware.kryo.serializers.FieldSerializer<>(
              kryo, StudentCardApplet.class);
      for (var f : StudentCardApplet.class.getDeclaredFields())
        if (!Modifier.isStatic(f.getModifiers())
            && (!PERSISTENT.contains(f.getName())
                || version == 3 && FINANCIAL.contains(f.getName())
                || version < 5 && Set.of("hash0", "hash1", "hashValid").contains(f.getName())))
          fields.removeField(f.getName());
      kryo.register(StudentCardApplet.class, fields, 3001);
    }

    void restore(AID aid, byte[] bytes) {
      activateSimulatorRuntimeInstance();
      if (!isV3(bytes)) throw new IllegalArgumentException("V3 cards only");
      boolean legacy = bytes[3] < 5;
      if (legacy) serializer(bytes[3]);
      try (Input in = new Input(Arrays.copyOfRange(bytes, 4, bytes.length))) {
        var applet = (StudentCardApplet) kryo.readClassAndObject(in);
        applet.upgradeFinancialState();
        applets.put(aid, new ApplicationInstance(aid, applet));
      } finally {
        if (legacy) serializer(false);
      }
    }
  }
}
