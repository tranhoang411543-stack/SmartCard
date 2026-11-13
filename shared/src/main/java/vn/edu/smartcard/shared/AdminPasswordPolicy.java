package vn.edu.smartcard.shared;

public final class AdminPasswordPolicy {
  private static final int MIN_LENGTH = 12;
  private static final int MAX_LENGTH = 20;
  public static final String LENGTH_ERROR =
      "Mật khẩu mới phải chứa từ " + MIN_LENGTH + " đến " + MAX_LENGTH + " ký tự";

  private AdminPasswordPolicy() {}

  public static boolean acceptsLength(int length) {
    return length >= MIN_LENGTH && length <= MAX_LENGTH;
  }
}
