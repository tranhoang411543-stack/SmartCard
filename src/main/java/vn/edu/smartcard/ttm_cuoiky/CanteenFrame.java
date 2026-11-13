package vn.edu.smartcard.ttm_cuoiky;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.ui.FlatTabbedPaneUI;
import com.formdev.flatlaf.util.UIScale;
import java.awt.*;
import java.awt.event.*;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;
import vn.edu.smartcard.shared.*;

final class CanteenFrame extends JFrame {

  private static final Api.ReaderStatus NO_CARD =
      new Api.ReaderStatus(false, "", false, "", 5, "NONE", 0, 0, "", "");
  private static final Api.SyncStatus NO_CARD_SYNC = new Api.SyncStatus("", "NO_CARD", 0, "");
  private static final Set<String> SYNC_REQUIRED = Set.of(
      "CREATED", "CARD_PREPARED", "COMMIT_DECIDED", "CARD_COMMITTED", "DB_COMMITTED",
      "CONFLICT", "CANCEL_PENDING");

  final ApiClient api;
  final boolean checkout;
  private final boolean networking;
  final StudentView student;
  final AdminView admin;
  final CheckoutView seller;
  private final DeviceSearch deviceSearch;
  private SwingWorker<Api.DeviceChoice[], Void> deviceLoad;
  private final JLabel syncState = new JLabel("Chưa kiểm tra đồng bộ");
  private final JButton connectButton, disconnectButton, reconcile;
  private Api.SyncStatus syncStatus = new Api.SyncStatus("", "UNKNOWN", 0, "Chưa kiểm tra đồng bộ");
  private final JTabbedPane tabs = new JTabbedPane();
  private final WaitOverlay blocker = new WaitOverlay();
  private String topupWaitMessage;
  private boolean busy, busyOverlay, polling;
  private String fingerprint = "";
  private int selectedTab;
  private long generation, sessionGeneration;
  private Api.ReaderStatus current = NO_CARD;
  private final javax.swing.Timer timer;
  private final javax.swing.Timer activityTimer;
  private Api.ReaderActivity readerActivity = new Api.ReaderActivity(false, false, false, false);
  private boolean activityKnown;
  private String checkedReader;
  private SwingWorker<Api.ReaderActivity, Void> activityWorker;
  private java.util.concurrent.ScheduledExecutorService heartbeat;
  private volatile boolean closing;

  CanteenFrame(ApiClient api, boolean checkout) {
    this(api, checkout, true);
  }

  CanteenFrame(ApiClient api, boolean checkout, boolean networking) {
    super(checkout ? "TTM Căn tin · Quầy thanh toán" : "TTM Căn tin · Quầy quản lý thẻ");
    this.api = api;
    this.checkout = checkout;
    this.networking = networking;
    deviceSearch = new DeviceSearch();
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    int minimumHeight = 880;
    setMinimumSize(new Dimension(1280, minimumHeight));
    Rectangle work = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    int defaultHeight = minimumHeight;
    setSize(Math.min(1280, work.width - 36),
        Math.max(minimumHeight, Math.min(defaultHeight, work.height - 44)));
    setLocationRelativeTo(null);
    JPanel main = new JPanel(new BorderLayout(0, 12));
    main.setBackground(new Color(245, 245, 245));
    main.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
    setContentPane(main);
    connectButton = button("Kết nối", e -> connect());
    disconnectButton = button("Ngắt kết nối", e -> disconnect());
    reconcile = button("Đồng bộ lại", e -> reconcileCard());
    reconcile.setEnabled(false);
    activityKnown = !networking;
    deviceSearch.onSelectionChanged(() -> {
      checkedReader = null;
      updateManagementConnectionButtons();
      if (networking) pollReaderActivity();
    });
    updateManagementConnectionButtons();
    student = checkout ? null : new StudentView(this);
    admin = checkout ? null : new AdminView(this);
    seller = checkout ? new CheckoutView(this) : null;
    main.add(
        managementToolbar(checkout ? null : button("Tạo thẻ mới", e -> createCard())),
        BorderLayout.NORTH);
    if (networking) {
      deviceSearch.addFocusListener(new FocusAdapter() {
        @Override public void focusGained(FocusEvent e) { refreshDeviceSearch(false); }
      });
      deviceSearch.addMouseListener(new MouseAdapter() {
        @Override public void mousePressed(MouseEvent e) { refreshDeviceSearch(false); }
      });
    }
    if (checkout) {
      main.add(seller, BorderLayout.CENTER);
    } else {
      tabs.setUI(new FlatTabbedPaneUI() {
        @Override
        protected int calculateTabWidth(int placement, int index, FontMetrics metrics) {
          return 128;
        }

        @Override
        protected int calculateTabHeight(int placement, int index, int fontHeight) {
          return 32;
        }
      });
      tabs.setBackground(new Color(0xE0E0E0));
      tabs.putClientProperty(
          FlatClientProperties.STYLE,
          Map.of(
              "tabSelectionHeight", UIScale.unscale(4),
              "selectedBackground", new Color(0xF2F2F2),
              "underlineColor", TTM_CuoiKy.DEFAULT_TEXT_COLOR,
              "inactiveUnderlineColor", TTM_CuoiKy.DEFAULT_TEXT_COLOR));
      tabs.addTab("Sinh viên", student);
      tabs.addTab("Admin", admin);
      tabs.addChangeListener(e -> changeManagementTab());
      main.add(tabs, BorderLayout.CENTER);
    }
    setGlassPane(blocker);
    timer = new javax.swing.Timer(2200, e -> poll());
    activityTimer = new javax.swing.Timer(600, e -> pollReaderActivity());
    if (networking) {
      timer.start();
      activityTimer.start();
      SwingUtilities.invokeLater(this::pollReaderActivity);
    }
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            if (readerLocked()) {
              info("Thẻ đang được xử lý hoặc có yêu cầu nạp chưa kết thúc. Vui lòng đợi hoặc hủy yêu cầu trước.");
              return;
            }
            if (closing || !canLeave()) {
              return;
            }
            boolean disconnectOwn = current.present() || (activityKnown && readerActivity.ownedByCaller());
            closing = true;
            blocker.showMessage("Đang đóng quầy…");
            new SwingWorker<Void, Void>() {
              protected Void doInBackground() {
                if (disconnectOwn) api.post("/reader/eject", null, Api.Result.class);
                if (checkout) api.closeStation();
                else if (admin != null) {
                  try { api.post("/admin/logout", null, Api.Result.class); }
                  catch (RuntimeException ignored) { /* A closed window cannot reuse this session. */ }
                }
                return null;
              }

              protected void done() {
                try {
                  get();
                  timer.stop();
                  activityTimer.stop();
                  if (activityWorker != null) activityWorker.cancel(true);
                  if (deviceLoad != null) deviceLoad.cancel(true);
                  if (heartbeat != null) heartbeat.shutdownNow();
                  invalidatePrivateState();
                  dispose();
                } catch (Exception failure) {
                  if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                  closing = false;
                  blocker.hideMessage();
                  error(failure);
                  pollReaderActivity();
                }
              }
            }.execute();
          }
        });
    if (networking) {
      SwingUtilities.invokeLater(() -> {
        if (!checkout) {
          refreshDeviceSearch(false);
          return;
        }
        run(
            "Đang mở quầy…",
            () -> {
              api.openStation();
              return api.get("/invoices/current", Api.Invoice[].class);
            },
            currentInvoices -> {
              var lastHeartbeatFailure = new java.util.concurrent.atomic.AtomicReference<String>();
              heartbeat =
                  java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread t = new Thread(task, "station-heartbeat");
                    t.setDaemon(true);
                    return t;
                  });
              heartbeat.scheduleWithFixedDelay(
                  () -> {
                    try {
                      api.heartbeat();
                      lastHeartbeatFailure.set(null);
                    } catch (ApiClient.ApiError e) {
                      String failureKey = e.code + "\n" + Objects.toString(e.getMessage(), "");
                      boolean notify = !failureKey.equals(lastHeartbeatFailure.getAndSet(failureKey));
                      SwingUtilities.invokeLater(() -> {
                        if (notify) errorMessage(e.getMessage());
                        if (!api.stationReady()) {
                          invalidatePrivateState();
                          seller.stationEnded();
                        }
                      });
                    }
                  },
                  5,
                  5,
                  java.util.concurrent.TimeUnit.SECONDS);
              seller.restoreCurrentInvoice(currentInvoices);
              reloadDevices();
              seller.loadMenu();
            },
            failure -> {
              seller.stationEnded();
              error(failure);
            });
      });
    }
  }

  private JPanel managementToolbar(JButton create) {
    Dimension buttonSize = new Dimension(128, 32);
    for (JButton button : new JButton[] {create, connectButton, disconnectButton, reconcile}) {
      if (button == null) continue;
      button.setMargin(new Insets(2, 12, 2, 12));
      button.setMinimumSize(buttonSize);
      button.setPreferredSize(buttonSize);
      button.setMaximumSize(buttonSize);
    }

    Box connection = Box.createHorizontalBox();
    connection.add(deviceSearch);
    connection.add(Box.createHorizontalStrut(8));
    if (create != null) {
      connection.add(create);
      connection.add(Box.createHorizontalStrut(8));
    }
    connection.add(connectButton);
    connection.add(Box.createHorizontalStrut(8));
    connection.add(disconnectButton);

    Box sync = Box.createHorizontalBox();
    sync.add(syncState);
    sync.add(Box.createHorizontalStrut(8));
    sync.add(reconcile);

    JPanel toolbar = new JPanel(new BorderLayout(12, 0));
    toolbar.setOpaque(false);
    toolbar.add(connection, BorderLayout.WEST);
    toolbar.add(sync, BorderLayout.EAST);
    return toolbar;
  }

  private void updateManagementConnectionButtons() {
    boolean connected = current.present();
    connectButton.setText(connected ? "Đã kết nối" : "Kết nối");
    boolean available = !networking || activityKnown;
    String selected = selectedReader();
    boolean selectionKnown = !networking || selected.isEmpty() || selected.equals(checkedReader);
    boolean otherOwner = !selected.isEmpty() && selectionKnown && activityKnown
        && readerActivity.selectedOwnedByOther();
    boolean locked = activityKnown && readerActivity.ownedByCaller()
        && (readerActivity.operating() || readerActivity.topupReserved());
    connectButton.setEnabled(available && selectionKnown && !busy && !connected && !otherOwner);
    disconnectButton.setEnabled(available && !busy && connected && !locked);
    connectButton.setToolTipText(otherOwner
        ? "Thẻ đang được kết nối ở quầy khác. Vui lòng ngắt kết nối tại quầy đó trước."
        : !selectionKnown ? "Đang kiểm tra trạng thái thẻ đã chọn…" : null);
    disconnectButton.setToolTipText(locked
        ? "Thẻ đang xử lý hoặc có yêu cầu nạp chưa kết thúc." : null);
  }

  private String selectedReader() {
    Api.DeviceChoice choice = deviceSearch.selection();
    return choice == null ? "" : choice.id();
  }

  private boolean readerLocked() {
    return busy || (networking && !activityKnown && (current.present() || readerActivity.ownedByCaller()))
        || (activityKnown && readerActivity.ownedByCaller()
            && (readerActivity.operating() || readerActivity.topupReserved()));
  }

  private void pollReaderActivity() {
    if (!networking || closing || activityWorker != null || !isDisplayable()) return;
    String requested = selectedReader();
    activityWorker = new SwingWorker<>() {
      @Override protected Api.ReaderActivity doInBackground() {
        String path = "/reader/activity";
        if (!requested.isEmpty()) path += "?selected="
            + java.net.URLEncoder.encode(requested, java.nio.charset.StandardCharsets.UTF_8);
        return api.get(path, Api.ReaderActivity.class);
      }
      @Override protected void done() {
        if (activityWorker != this) return;
        activityWorker = null;
        if (closing || !isDisplayable()) return;
        try {
          Api.ReaderActivity value = get();
          if (value == null) throw new IllegalStateException("Thiếu trạng thái thẻ.");
          readerActivity = value;
          activityKnown = true;
          checkedReader = requested.equals(value.selectedReader()) ? requested : null;
        } catch (Exception failure) {
          if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
          activityKnown = false;
          checkedReader = null;
        }
        updateManagementConnectionButtons();
        if (activityKnown && !requested.equals(selectedReader())) pollReaderActivity();
      }
    };
    activityWorker.execute();
  }

  static JButton button(String label, java.awt.event.ActionListener action) {
    JButton b = new JButton(label);
    b.addActionListener(action);
    return b;
  }

  static String money(long value) {
    return NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(value) + " ₫";
  }

  static JPanel actions(JComponent... buttons) {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
    p.setOpaque(false);
    for (JComponent b : buttons) {
      p.add(b);
    }
    return p;
  }

  static String html(String s) {
    return "<html>"
        + Objects.toString(s, "")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
        + "</html>";
  }

  boolean canLeave() {
    return admin == null || admin.confirmDiscard();
  }

  private boolean canChangeReader() {
    return admin == null || admin.confirmReaderChange();
  }

  private void changeManagementTab() {
    int next = tabs.getSelectedIndex();
    if (next == selectedTab) return;
    if (readerLocked()) {
      tabs.setSelectedIndex(selectedTab);
      return;
    }
    if (next == 1) {
      selectedTab = next;
      student.discardPinChange();
      if (admin.logged()) admin.onCard(current);
      return;
    }

    tabs.setSelectedIndex(selectedTab);
    if (!canLeave()) return;
    Runnable showStudent = () -> {
      selectedTab = 0;
      tabs.setSelectedIndex(0);
      admin.discardRecovery();
    };
    boolean needsSync = SYNC_REQUIRED.contains(syncStatus.phase());
    if (networking && !needsSync) {
      student.resume(showStudent);
    } else {
      showStudent.run();
    }
  }

  boolean isBusy() {
    return busy;
  }

  boolean adminTab() {
    return !checkout && tabs.getSelectedIndex() == 1;
  }

  long sessionEpoch() {
    return sessionGeneration;
  }

  void invalidatePrivateState() {
    sessionGeneration++;
    generation++;
    if (student != null) {
      student.discardPinChange();
      student.clearTransactions();
    }
    for (Window window : getOwnedWindows()) {
      if (window instanceof Dialog) {
        window.dispose();
      }
    }
  }

  private void reconcileCard() {
    if (!canLeave()) {
      return;
    }
    run(
        "Đang đồng bộ thẻ…",
        () -> {
          Api.Result result = api.post("/reader/reconcile", null, Api.Result.class);
          return new ReconcileReply(result.message(), readCardState());
        },
        reply -> {
          fingerprint = "";
          updateStatus(reply.state().card(), reply.state().sync(), () -> {
            info(reply.message());
            if (seller != null) seller.refreshInvoice();
          });
        });
  }

  boolean syncReady() {
    return syncStatus.phase().equals("SYNCED");
  }

  void message(String text) {
    if (seller != null) seller.showNotice(text, false);
  }

  void errorMessage(String text) {
    if (seller != null) seller.showNotice(text, true);
  }

  void info(String text) {
    JOptionPane.showMessageDialog(this, text, "TTM Căn tin", JOptionPane.INFORMATION_MESSAGE);
  }

  void error(Throwable failure) {
    error(failure, "Chưa thực hiện được thao tác");
  }

  void error(Throwable failure, String title) {
    error(null, failure, title);
  }

  void error(JDialog source, Throwable failure) {
    error(source, failure, "Chưa thực hiện được thao tác");
  }

  private void error(JDialog source, Throwable failure, String title) {
    Throwable e = failure;
    while (e instanceof java.util.concurrent.ExecutionException && e.getCause() != null) {
      e = e.getCause();
    }
    if (e instanceof ApiClient.ApiError a) {
      if (a.code.startsWith("ADMIN_") && admin != null) {
        admin.expired();
      } else if (a.code.equals("CONNECTION") && admin != null) {
        admin.suspendCard();
      }
      if ((a.code.startsWith("PIN_")
              || a.code.equals("CARD_BLOCKED")
              || a.code.equals("CONNECTION"))
          && student != null) {
        student.unavailable(e.getMessage());
      }
      if (a.code.startsWith("SYNC_")) {
        if (student != null) {
          student.unavailable(e.getMessage());
        }
        if (admin != null) {
          admin.suspendCard();
        }
      }
    }
    Component parent = source != null && source.isShowing() ? source : this;
    if (seller != null) {
      String message = e.getMessage();
      Toast.showMessage(parent, message == null || message.isBlank()
          ? "Chưa thực hiện được thao tác. Vui lòng thử lại." : message, true);
      return;
    }
    JOptionPane.showMessageDialog(parent, html(e.getMessage()), title, JOptionPane.WARNING_MESSAGE);
  }

  <T> void run(String text, Callable<T> work, Consumer<T> done) {
    run(text, work, done, this::error);
  }

  void showTopupWait(String text) {
    topupWaitMessage = text;
    if (!closing && (!busy || !busyOverlay)) blocker.showMessage(text);
  }

  void hideTopupWait() {
    topupWaitMessage = null;
    if (!closing && (!busy || !busyOverlay)) blocker.hideMessage();
  }

  <T> void runWithoutOverlay(Callable<T> work, Consumer<T> done, Consumer<Throwable> failure) {
    run(null, work, done, failure);
  }

  <T> void run(String text, Callable<T> work, Consumer<T> done, Consumer<Throwable> failure) {
    run(null, text, work, done, failure);
  }

  <T> void run(
      JDialog source, String text, Callable<T> work, Consumer<T> done, Consumer<Throwable> failure) {
    if (busy || closing) {
      return;
    }
    generation++;
    long epoch = sessionGeneration;
    busy = true;
    busyOverlay = text != null;
    updateManagementConnectionButtons();
    boolean showOverlay = busyOverlay;
    WaitOverlay dialogOverlay = source == null ? null : overlayFor(source);
    if (showOverlay) {
      blocker.showMessage(dialogOverlay == null ? text : null);
      if (dialogOverlay != null) dialogOverlay.showMessage(text);
    }
    new SwingWorker<T, Void>() {
      protected T doInBackground() throws Exception {
        return work.call();
      }

      protected void done() {
        busy = false;
        busyOverlay = false;
        if (networking) {
          activityKnown = false;
          if (activityWorker != null) {
            var oldActivity = activityWorker;
            activityWorker = null;
            oldActivity.cancel(true);
          }
          pollReaderActivity();
        }
        updateManagementConnectionButtons();
        if (showOverlay) {
          if (!closing) {
            if (topupWaitMessage == null) blocker.hideMessage();
            else blocker.showMessage(topupWaitMessage);
          }
          if (dialogOverlay != null) dialogOverlay.hideMessage();
        }
        try {
          T result = get();
          if (epoch == sessionGeneration) {
            done.accept(result);
          }
        } catch (Exception e) {
          if (epoch != sessionGeneration) {
            return;
          }
          Throwable cause =
              e instanceof java.util.concurrent.ExecutionException && e.getCause() != null
                  ? e.getCause()
                  : e;
          failure.accept(cause);
        }
      }
    }.execute();
  }

  private static WaitOverlay overlayFor(JDialog dialog) {
    if (dialog.getGlassPane() instanceof WaitOverlay overlay) return overlay;
    WaitOverlay overlay = new WaitOverlay();
    dialog.setGlassPane(overlay);
    return overlay;
  }

  private void createCard() {
    if (busy) return;
    if (!admin.logged()) {
      tabs.setSelectedIndex(1);
      info("Đăng nhập Admin để tạo thẻ mới.");
      return;
    }
    run("Đang tạo thẻ mới…", () -> {
      Object id = api.post("/readers/new", null, Map.class).get("id");
      if (!(id instanceof String value) || value.isBlank()) {
        throw new IllegalStateException("Chưa nhận được ID thẻ mới.");
      }
      return value;
    }, id -> {
      deviceSearch.addChoice(new Api.DeviceChoice(id, "Thẻ mô phỏng " + id));
      refreshDeviceSearch(true);
      info("Tạo thẻ mới thành công!\nID Thẻ : " + id);
    });
  }

  void reloadDevices() {
    refreshDeviceSearch(true);
  }

  private void refreshDeviceSearch(boolean restart) {
    if (closing || (!restart && deviceLoad != null)) return;
    if (deviceLoad != null) {
      var previous = deviceLoad;
      deviceLoad = null;
      previous.cancel(true);
    }
    deviceLoad = new SwingWorker<>() {
      @Override protected Api.DeviceChoice[] doInBackground() {
        return api.get("/readers", Api.DeviceChoice[].class);
      }

      @Override protected void done() {
        if (deviceLoad != this) return;
        deviceLoad = null;
        if (closing || !isDisplayable()) return;
        try {
          deviceSearch.setChoices(get(), null);
        } catch (Exception failure) {
          deviceSearch.setToolTipText("Chưa tải được danh sách. Bấm lại vào ô tìm kiếm để thử lại.");
        }
      }
    };
    deviceLoad.execute();
  }

  private void connect() {
    if (checkout && networking && !api.stationReady()) {
      info("Phiên quầy chưa sẵn sàng hoặc đã kết thúc. Mở lại ứng dụng trước.");
      return;
    }
    if (!canChangeReader()) {
      return;
    }
    Api.DeviceChoice selected = deviceSearch.selection();
    if (selected == null) {
      info("Nhập ID thẻ hoặc tên đầu đọc và chọn một kết quả để kết nối.");
      return;
    }
    run(
        "Đang kết nối thẻ…",
        () -> {
          Api.ReaderStatus status =
              api.post("/reader/connect", new Api.Connect(selected.id(), ""), Api.ReaderStatus.class);
          return status;
        },
        s -> {
          fingerprint = "";
          updateStatus(s);
          message("Đã kết nối thẻ.");
        },
        failure -> {
          if (failure instanceof ApiClient.ApiError error && error.code.equals("CARD_AUTH")) {
            fingerprint = "";
            updateStatus(NO_CARD, NO_CARD_SYNC, () -> {});
            Toast.showMessage(this, "Xác thực thẻ không thành công!", true);
            return;
          }
          if (checkout) poll();
          error(failure);
        });
  }

  private void disconnect() {
    if (!canChangeReader()) {
      return;
    }
    run(
        "Đang ngắt kết nối…",
        () -> api.post("/reader/eject", null, Api.Result.class),
        result -> {
          fingerprint = "";
          updateStatus(NO_CARD, NO_CARD_SYNC, () -> {});
          poll();
        });
  }

  private record PollReply(Api.ReaderStatus card, Api.SyncStatus sync) {}
  private record ReconcileReply(String message, PollReply state) {}

  private PollReply readCardState() {
    Api.ReaderStatus status = api.get("/reader/status", Api.ReaderStatus.class);
    return new PollReply(status,
        status.present() ? api.get("/reader/sync", Api.SyncStatus.class) : NO_CARD_SYNC);
  }

  void poll() {
    if (closing || busy || polling || !isDisplayable()) {
      return;
    }
    polling = true;
    long started = generation;
    boolean checkAdmin = admin != null && adminTab() && admin.logged();
    new SwingWorker<PollReply, Void>() {
      protected PollReply doInBackground() {
        if (checkAdmin) {
          api.get("/admin/me", java.util.Map.class);
        }
        return readCardState();
      }

      protected void done() {
        polling = false;
        if (busy || started != generation) {
          return;
        }
        try {
          PollReply reply = get();
          updateStatus(reply.card(), reply.sync(), () -> {});
        } catch (Exception e) {
          Throwable cause = e instanceof java.util.concurrent.ExecutionException
                  && e.getCause() != null ? e.getCause() : e;
          if (admin != null && admin.logged()) {
            if (cause instanceof ApiClient.ApiError error && error.code.startsWith("ADMIN_")) {
              admin.expired();
            } else {
              admin.suspendCard();
            }
          }
          if (student != null) {
            student.unavailable("Chưa kết nối máy chủ. Vui lòng thử lại.");
          }
          syncStatus =
              new Api.SyncStatus(
                  "",
                  "OFFLINE",
                  0,
                  "Chưa kết nối máy chủ");
          TTM_CuoiKy.statusText(syncState, syncStatus.message(), true);
          reconcile.setEnabled(false);
          if (seller != null) {
            seller.onCard(current);
          }
          fingerprint = "";
        }
      }
    }.execute();
  }

  private void updateSync(Api.SyncStatus value) {
    String previous = syncStatus.phase();
    syncStatus = value;
    TTM_CuoiKy.statusText(syncState, value.message(),
        value.phase().equals("CONFLICT") || value.phase().equals("OFFLINE"));
    reconcile.setEnabled(current.present());
    if (!previous.equals(value.phase()) && SYNC_REQUIRED.contains(value.phase())) {
      if (student != null) {
        student.unavailable(value.message());
      }
      if (admin != null) {
        admin.syncChanged();
      }
    }
  }

  void refreshCard() {
    fingerprint = "";
    poll();
  }

  private void updateStatus(Api.ReaderStatus s) {
    updateStatus(s, null, () -> {});
  }

  private void updateStatus(Api.ReaderStatus s, Api.SyncStatus sync, Runnable ready) {
    current = s;
    updateManagementConnectionButtons();
    String key =
        s.present()
            + "|"
            + s.connectionId()
            + "|"
            + s.cardId()
            + "|"
            + s.initialized()
            + "|"
            + s.lifecycleStatus();
    boolean changed = !key.equals(fingerprint);
    boolean wasWaitingForSync = SYNC_REQUIRED.contains(syncStatus.phase());
    if (changed) {
      invalidatePrivateState();
      fingerprint = key;
      syncStatus = new Api.SyncStatus(s.cardId(), "UNKNOWN", 0, "Chưa kiểm tra đồng bộ");
    }
    if (sync != null) updateSync(sync);
    updateManagementConnectionButtons();
    if (checkout) {
      seller.onCard(s);
    } else if (adminTab()) {
      admin.onCard(s, ready);
      return;
    } else if (!SYNC_REQUIRED.contains(syncStatus.phase())) {
      if (changed || wasWaitingForSync) {
        student.onCard(s, ready);
        return;
      }
      student.updateTries(s.tries());
    }
    ready.run();
  }

  Api.ReaderStatus status() {
    return current;
  }

  boolean cardReady() {
    return !"OFFLINE".equals(syncStatus.phase()) && !SYNC_REQUIRED.contains(syncStatus.phase());
  }

}
