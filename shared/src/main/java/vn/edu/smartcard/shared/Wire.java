package vn.edu.smartcard.shared;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;

/** Canonical binary protocol; see docs/THIET-KE.md. No PIN/key logging. */
public final class Wire {
  private Wire() {}

  public static final int STATUS = 0x10,
      PUBLIC_KEY = 0x12,
      PROVE = 0x13,
      VERIFY_PIN = 0x20,
      LOGOUT = 0x21,
      READ_PROFILE = 0x22,
      BALANCE = 0x23;
  public static final int INIT = 1,
      UPDATE = 2,
      RESET_PIN = 3,
      CREDIT = 6,
      DEBIT = 7,
      CHANGE_PIN = 8,
      REVERSAL = 9;
  public static final byte[] AID = new byte[] {(byte) 0xF0, 0x54, 0x54, 0x4D, 0x01, 0x01};

  public static byte[] id(String value) {
    return Arrays.copyOf(value.getBytes(StandardCharsets.US_ASCII), 16);
  }

  public static String id(byte[] bytes, int offset) {
    int n = 0;
    while (n < 16 && bytes[offset + n] != 0) n++;
    return new String(bytes, offset, n, StandardCharsets.US_ASCII);
  }

  public static byte[] hash(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static boolean verify(PublicKey key, byte[] bytes, byte[] signature) {
    try {
      Signature s = Signature.getInstance("SHA256withECDSA");
      s.initVerify(key);
      s.update(bytes);
      return s.verify(signature);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  public static byte[] concat(byte[]... values) {
    int n = 0;
    for (byte[] v : values) n += v.length;
    ByteBuffer b = ByteBuffer.allocate(n);
    for (byte[] v : values) b.put(v);
    return b.array();
  }

  public static byte[] operation(long id, long amount) {
    if (amount < 1 || amount > 100_000_000L)
      throw new IllegalArgumentException("Số tiền phải từ 1 đến 100.000.000 đồng.");
    return ByteBuffer.allocate(12).putLong(id).putInt((int) amount).array();
  }

  public static void pin(String pin) {
    if (pin == null || !pin.matches("[0-9]{6}"))
      throw new IllegalArgumentException("PIN phải có đúng 6 chữ số.");
  }
}
