package vn.edu.smartcard.backend;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.smartcardio.*;
import vn.edu.smartcard.shared.*;

/** No issuer, RSA encryption or per-command signatures: reader/APDU is trusted. */
public final class CardClient {
  public interface Transport {
    byte[] exchange(byte[] command);
  }

  private final Transport transport;
  private PublicKey cachedPublicKey;
  private String authenticatedId;
  private byte[] authenticatedSession, authenticatedKey;

  public CardClient(Transport transport) {
    this.transport = transport;
  }

  public byte[] command(int ins, int p1, int p2, byte[] data) {
    CommandAPDU cmd =
        data.length == 0
            ? new CommandAPDU(0x80, ins, p1, p2, 256)
            : new CommandAPDU(0x80, ins, p1, p2, data, 256);
    ResponseAPDU response = new ResponseAPDU(transport.exchange(cmd.getBytes()));
    int sw = response.getSW();
    if (sw != 0x9000) {
      if (sw == 0x6982)
        throw new Failure(403, "CARD_DENIED", "Thẻ từ chối quyền thao tác. Vui lòng xác thực lại.");
      if (sw == 0x6984)
        throw new Failure(
            409,
            "CARD_CRYPTO",
            "Bản ghi mã hóa trên thẻ không hợp lệ; cần kiểm tra dữ liệu và bí mật tương ứng.");
      if (sw == 0x6A84)
        throw new Failure(409, "FUNDS", "Không đủ số dư hoặc số tiền vượt giới hạn của thẻ.");
      throw new Failure(
          409,
          "CARD_" + Integer.toHexString(sw).toUpperCase(Locale.ROOT),
          "Thẻ từ chối thao tác (" + Integer.toHexString(sw).toUpperCase(Locale.ROOT) + ").");
    }
    return response.getData();
  }

  public byte[] command(int ins) {
    return command(ins, 0, 0, new byte[0]);
  }

  public Api.ReaderStatus status(String alias) {
    byte[] b = command(Wire.STATUS);
    return new Api.ReaderStatus(
        true,
        alias,
        b[0] == 1,
        Wire.id(b, 4),
        5,
        switch (b[2]) {
          case 1 -> "STUDENT";
          case 2 -> "ADMIN";
          default -> "NONE";
        },
        Integer.toUnsignedLong(ByteBuffer.wrap(b, 20, 4).getInt()),
        Short.toUnsignedInt(ByteBuffer.wrap(b, 24, 2).getShort()),
        "",
        "ACTIVE");
  }

  public PublicKey publicKey() {
    if (cachedPublicKey != null) return cachedPublicKey;
    try {
      byte[] w = command(Wire.PUBLIC_KEY);
      if (w.length != 65 || w[0] != 4) throw Failure.denied();
      AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
      parameters.init(new ECGenParameterSpec("secp256r1"));
      cachedPublicKey =
          KeyFactory.getInstance("EC")
              .generatePublic(
                  new ECPublicKeySpec(
                      new ECPoint(
                          new BigInteger(1, Arrays.copyOfRange(w, 1, 33)),
                          new BigInteger(1, Arrays.copyOfRange(w, 33, 65))),
                      parameters.getParameterSpec(ECParameterSpec.class)));
      return cachedPublicKey;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public void authenticate(
      PublicKey registered, String id, int purpose, long operation, boolean force) {
    byte[] session = Arrays.copyOfRange(protocol(), 1, 17);
    if (!force
        && Objects.equals(authenticatedId, id)
        && Arrays.equals(authenticatedSession, session)
        && Arrays.equals(authenticatedKey, Protocol.fingerprint(registered))) return;
    byte[] challenge = new byte[32];
    new SecureRandom().nextBytes(challenge);
    byte[] context = ByteBuffer.allocate(8).putLong(operation).array();
    byte[] n = command(Wire.PROVE, purpose, 0, Wire.concat(challenge, context));
    byte[] proof = readOutput(Short.toUnsignedInt(ByteBuffer.wrap(n).getShort()));
    byte[] body =
        Wire.concat(
            "TTM3-AUTH".getBytes(StandardCharsets.US_ASCII),
            new byte[] {(byte) purpose},
            Wire.id(id),
            session,
            challenge,
            context);
    if (proof.length <= body.length
        || !Arrays.equals(body, Arrays.copyOf(proof, body.length))
        || !Wire.verify(registered, body, Arrays.copyOfRange(proof, body.length, proof.length)))
      throw new Failure(403, "CARD_AUTH", "Không xác thực được thẻ đã đăng ký.");
    authenticatedId = id;
    authenticatedSession = session;
    authenticatedKey = Protocol.fingerprint(registered);
  }

  public void unlock(String id, byte[] share, boolean admin) {
    if (share.length != 32) throw new IllegalArgumentException("Invalid backend share");
    byte[] payload = Wire.concat(Wire.id(id), share);
    try {
      command(0x24, admin ? 2 : 0, 0, payload);
    } finally {
      Arrays.fill(payload, (byte) 0);
    }
  }

  public boolean verifyPin(String value) {
    Wire.pin(value);
    byte[] pin = value.getBytes(StandardCharsets.US_ASCII);
    try {
      byte[] reply = command(Wire.VERIFY_PIN, 0, 0, pin);
      if (reply.length != 1 || (reply[0] != 0 && reply[0] != 1)) throw Failure.denied();
      return reply[0] == 1;
    } finally {
      Arrays.fill(pin, (byte) 0);
    }
  }

  public long balance() {
    return Integer.toUnsignedLong(ByteBuffer.wrap(command(Wire.BALANCE)).getInt());
  }

  public void logout() {
    try {
      command(Wire.LOGOUT);
    } finally {
      authenticatedId = null;
      authenticatedSession = null;
      authenticatedKey = null;
    }
  }

  public Profile profile() {
    int len = status("").profileLength();
    byte[] bytes = new byte[len];
    try {
      for (int at = 0; at < len; ) {
        byte[] part = command(Wire.READ_PROFILE, at >> 8, at & 255, new byte[0]);
        if (part.length == 0 || part.length > len - at) throw Failure.denied();
        System.arraycopy(part, 0, bytes, at, part.length);
        at += part.length;
        Arrays.fill(part, (byte) 0);
      }
      return ProfileCodec.decode(bytes);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  public byte[] protocol() {
    byte[] info = command(Protocol.INFO);
    if (info.length != 50 || info[0] != 4)
      throw new Failure(
          409,
          "CARD_VERSION",
          "Thẻ dùng cơ chế cũ. Thẻ cần capability hoàn bù V4. Vui lòng nâng cấp applet trước khi"
              + " thanh toán.");
    return info;
  }

  public byte[] invoke(
      int scope, String id, long operation, int offset, int length, byte[] payload, byte[] token) {
    command(
        Protocol.BEGIN,
        0,
        0,
        Protocol.header(scope, id, operation, offset, length, payload.length, token));
    for (int at = 0; at < payload.length; ) {
      int n = Math.min(180, payload.length - at);
      byte[] part = ByteBuffer.allocate(n + 2).putShort((short) at).put(payload, at, n).array();
      try {
        command(Protocol.CHUNK, 0, 0, part);
      } finally {
        Arrays.fill(part, (byte) 0);
      }
      at += n;
    }
    return command(Protocol.EXECUTE);
  }

  public Protocol.State state() {
    String id = status("").cardId();
    long read = newId();
    byte[] bytes = new byte[Protocol.STATE_SIZE];
    for (int at = 0; at < bytes.length; ) {
      int n = Math.min(180, bytes.length - at);
      byte[] part = invoke(Protocol.STATE, id, read, at, n, new byte[0], new byte[32]);
      if (part.length != n) throw Failure.denied();
      System.arraycopy(part, 0, bytes, at, n);
      at += n;
    }
    try {
      return Protocol.state(bytes, publicKey(), read);
    } catch (IllegalArgumentException e) {
      throw new Failure(409, "CARD_STATE", "Không đọc được trạng thái thẻ.");
    }
  }

  public byte[] cipher(Protocol.State state, boolean pending) {
    String id = state.cardId().isEmpty() ? state.pendingCardId() : state.cardId();
    long op = pending ? state.pendingId() : newId();
    byte[] payload = {(byte) (pending ? 1 : 0)};
    byte[] head = invoke(Protocol.EXPORT, id, op, 0, Protocol.HEADER_SIZE, payload, new byte[32]);
    int size = Protocol.cipherSize(head);
    byte[] all = Arrays.copyOf(head, size);
    for (int at = head.length; at < size; ) {
      int n = Math.min(180, size - at);
      byte[] part = invoke(Protocol.EXPORT, id, op, at, n, payload, new byte[32]);
      if (part.length != n) throw Failure.denied();
      System.arraycopy(part, 0, all, at, n);
      at += n;
    }
    if (!Arrays.equals(Wire.hash(all), pending ? state.nextCipherHash() : state.cipherHash()))
      throw new Failure(409, "CIPHER_MISMATCH", "Bản mã thẻ chưa khớp trạng thái.");
    return all;
  }

  public void prepare(String id, long operation, byte[] request, byte[] token, Runnable check) {
    check.run();
    invoke(Protocol.PREPARE, id, operation, 0, 0, request, token);
  }

  public void complete(int scope, String id, long operation) {
    invoke(scope, id, operation, 0, 0, new byte[0], new byte[32]);
  }

  private static long newId() {
    long n = new SecureRandom().nextLong() & Long.MAX_VALUE;
    return n == 0 ? 1 : n;
  }

  private byte[] readOutput(int len) {
    if (len < 83 || len > 180) throw Failure.denied();
    byte[] all = new byte[len];
    for (int at = 0; at < len; ) {
      byte[] part = command(0x14, at >> 8, at & 255, new byte[0]);
      if (part.length == 0 || part.length > len - at) throw Failure.denied();
      System.arraycopy(part, 0, all, at, part.length);
      at += part.length;
    }
    return all;
  }
}
