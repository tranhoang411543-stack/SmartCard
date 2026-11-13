package vn.edu.smartcard.shared;

import java.nio.ByteBuffer;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

/** Trusted-link V3 messages. State is an unsigned snapshot, not an attestation. */
public final class Protocol {
  private Protocol() {}

  public static final int INFO = 0x50, BEGIN = 0x52, CHUNK = 0x53, EXECUTE = 0x54;
  public static final int PREPARE = 1,
      COMMIT = 2,
      ACK = 3,
      ABORT = 4,
      STATE = 5,
      EXPORT = 9,
      RELEASE = 10;
  public static final int STATE_SIZE = 288, HEADER_SIZE = 77;

  public static byte[] point(PublicKey key) {
    ECPublicKey ec = (ECPublicKey) key;
    return Wire.concat(
        new byte[] {4},
        unsigned(ec.getW().getAffineX().toByteArray(), 32),
        unsigned(ec.getW().getAffineY().toByteArray(), 32));
  }

  public static byte[] fingerprint(PublicKey key) {
    return Wire.hash(point(key));
  }

  private static byte[] unsigned(byte[] source, int len) {
    byte[] result = new byte[len];
    int n = Math.min(len, source.length);
    System.arraycopy(source, source.length - n, result, len - n, n);
    return result;
  }

  public static byte[] header(
      int scope,
      String id,
      long operation,
      int offset,
      int length,
      int payloadLength,
      byte[] token) {
    if (token.length != 32
        || offset < 0
        || offset > 32767
        || length < 0
        || length > 180
        || payloadLength < 0
        || payloadLength > 28280) throw new IllegalArgumentException("Invalid command bounds");
    return ByteBuffer.allocate(63)
        .put((byte) scope)
        .put(Wire.id(id))
        .putLong(operation)
        .putShort((short) offset)
        .putShort((short) length)
        .putShort((short) payloadLength)
        .put(token)
        .array();
  }

  public static byte[] request(
      int kind, long balance, long profile, long crypto, long wallet, byte[] data) {
    return ByteBuffer.allocate(17 + data.length)
        .put((byte) kind)
        .putInt((int) balance)
        .putInt((int) profile)
        .putInt((int) crypto)
        .putInt((int) wallet)
        .put(data)
        .array();
  }

  public record State(
      String cardId,
      long counter,
      long profileVersion,
      long cryptoVersion,
      long walletVersion,
      long balance,
      int phase,
      int pendingKind,
      long pendingId,
      String pendingCardId,
      long nextProfile,
      long nextCrypto,
      long nextWallet,
      long nextBalance,
      byte[] cipherHash,
      byte[] nextCipherHash,
      byte[] pendingHash,
      long lastId,
      int lastKind,
      byte[] lastHash,
      long readId,
      long lastAck,
      int financialHold,
      long debitId,
      long debitBeforeBalance,
      long debitAmount,
      long debitWalletVersion,
      byte[] encoded) {}

  public static State state(byte[] bytes, PublicKey key, long readId) {
    if (bytes.length != STATE_SIZE
        || bytes[0] != 'T'
        || bytes[1] != 'T'
        || bytes[2] != 'S'
        || bytes[3] != '4'
        || !Arrays.equals(Arrays.copyOfRange(bytes, 4, 36), fingerprint(key)))
      throw new IllegalArgumentException("Invalid card state");
    ByteBuffer b = ByteBuffer.wrap(bytes);
    if (b.getLong(251) != readId) throw new IllegalArgumentException("Stale card state");
    return new State(
        Wire.id(bytes, 36),
        u(b, 52),
        u(b, 56),
        u(b, 60),
        u(b, 64),
        u(b, 68),
        bytes[72] & 255,
        bytes[73] & 255,
        b.getLong(74),
        Wire.id(bytes, 82),
        u(b, 98),
        u(b, 102),
        u(b, 106),
        u(b, 110),
        Arrays.copyOfRange(bytes, 114, 146),
        Arrays.copyOfRange(bytes, 146, 178),
        Arrays.copyOfRange(bytes, 178, 210),
        b.getLong(210),
        bytes[218] & 255,
        Arrays.copyOfRange(bytes, 219, 251),
        readId,
        b.getLong(259),
        bytes[267] & 255,
        b.getLong(268),
        u(b, 276),
        u(b, 280),
        u(b, 284),
        bytes.clone());
  }

  public static long u(ByteBuffer b, int at) {
    return Integer.toUnsignedLong(b.getInt(at));
  }

  public static int cipherSize(byte[] header) {
    if (header.length < HEADER_SIZE || header[0] != 3)
      throw new IllegalArgumentException("Invalid cipher snapshot");
    int n = Short.toUnsignedInt(ByteBuffer.wrap(header).getShort(43));
    if (n < 16 || n > 28016 || n % 16 != 0)
      throw new IllegalArgumentException("Invalid cipher length");
    return HEADER_SIZE + n;
  }
}
