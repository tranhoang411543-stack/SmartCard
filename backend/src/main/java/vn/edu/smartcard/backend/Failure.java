package vn.edu.smartcard.backend;

public class Failure extends RuntimeException {
  public final int status;
  public final String code;

  public Failure(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static Failure bad(String message) {
    return new Failure(400, "INVALID", message);
  }

  public static Failure denied() {
    return new Failure(403, "DENIED", "Bạn không có quyền thực hiện thao tác này.");
  }

  public static Failure missing(String message) {
    return new Failure(404, "NOT_FOUND", message);
  }
}
