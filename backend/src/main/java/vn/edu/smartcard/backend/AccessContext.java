package vn.edu.smartcard.backend;

/** HTTP-scoped permission check, re-evaluated at privileged service boundaries. Never persisted. */
final class AccessContext {
  private static final ThreadLocal<Runnable> ADMIN = new ThreadLocal<>();

  static void admin(Runnable check) {
    admin(check, 0);
  }

  static void admin(Runnable check, long id) {
    ADMIN.set(check);
  }

  static void clear() {
    ADMIN.remove();
  }

  static Runnable requireAdmin() {
    Runnable check = ADMIN.get();
    if (check == null) throw new Failure(401, "ADMIN_LOGIN", "Vui lòng đăng nhập admin.");
    check.run();
    return check;
  }
}
