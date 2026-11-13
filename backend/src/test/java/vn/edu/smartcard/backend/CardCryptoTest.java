package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javacard.framework.AID;
import javacard.security.HMACKey;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.smartcard.shared.*;

/**
 * Independent Java SE vectors using fixture-only reflective access. Production APIs never export a
 * card secret, PIN record or AES key.
 */
class CardCryptoTest extends CardTestFixture {
  private Object applet() throws Exception {
    var field = ReaderHub.class.getDeclaredField("cards");
    field.setAccessible(true);
    Object sim = ((Map<?, ?>) field.get(hub)).get(alias);
    var rf = sim.getClass().getDeclaredField("runtime");
    rf.setAccessible(true);
    Object runtime = rf.get(sim);
    for (Class<?> type = runtime.getClass(); type != null; type = type.getSuperclass())
      try {
        var method = type.getDeclaredMethod("getApplet", AID.class);
        method.setAccessible(true);
        return method.invoke(runtime, new AID(Wire.AID, (short) 0, (byte) Wire.AID.length));
      } catch (NoSuchMethodException ignored) {
      }
    throw new AssertionError("Applet unavailable");
  }

  private Object field(Object applet, String name) throws Exception {
    var f = applet.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(applet);
  }

  private int slot(Object applet) throws Exception {
    return (Byte) field(applet, "active");
  }

  private byte[] bytes(Object applet, String first, String second) throws Exception {
    return (byte[]) field(applet, slot(applet) == 0 ? first : second);
  }

  private static byte[] f(byte[] key, String label, byte[] context, int bits) throws Exception {
    Mac h = Mac.getInstance("HmacSHA256");
    h.init(new SecretKeySpec(key, "HmacSHA256"));
    byte[] name = label.getBytes(StandardCharsets.US_ASCII);
    return Arrays.copyOf(
        h.doFinal(
            ByteBuffer.allocate(4 + name.length + 1 + context.length + 4)
                .putInt(1)
                .put(name)
                .put((byte) 0)
                .put(context)
                .putInt(bits)
                .array()),
        bits / 8);
  }

  private byte[] root(Object applet) throws Exception {
    byte[] card = new byte[32];
    ((HMACKey) field(applet, "secret")).getKey(card, (short) 0);
    return f(
        card,
        "TTM3/ROOT",
        Wire.concat(Wire.id("TTM000001"), ByteBuffer.allocate(4).putInt(1).array(), share),
        256);
  }

  private static byte[] decrypt(byte[] key, byte[] iv, byte[] ciphertext) throws Exception {
    Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
    return c.doFinal(ciphertext);
  }

  @Test
  void profileCipherAndMacMatchIndependentCounterModeKdfVector() throws Exception {
    Profile p = init();
    Object applet = applet();
    byte[] root = root(applet),
        context =
            Wire.concat(
                Wire.id(p.cardId()), ByteBuffer.allocate(4).putInt(1).array(), pin("123456"));
    var state = state();
    byte[] blob = use(c -> c.cipher(state, false)),
        cipher = Arrays.copyOfRange(blob, 77, blob.length),
        enc = f(root, "TTM3/DATA/ENC", context, 128),
        mac = f(root, "TTM3/DATA/MAC", context, 256);
    Mac h = Mac.getInstance("HmacSHA256");
    h.init(new SecretKeySpec(mac, "HmacSHA256"));
    assertArrayEquals(
        Arrays.copyOfRange(blob, 45, 77),
        h.doFinal(Wire.concat(new byte[] {2}, Arrays.copyOf(blob, 45), cipher)));
    byte[] clear = decrypt(enc, Arrays.copyOfRange(blob, 25, 41), cipher);
    int size = Short.toUnsignedInt(ByteBuffer.wrap(blob).getShort(41));
    assertEquals((byte) 0x80, clear[size]);
    assertEquals(p, ProfileCodec.decode(Arrays.copyOf(clear, size)));
    assertArrayEquals(f(root, "TTM3/PIN/ENC", Wire.id(p.cardId()), 128), pinKey(applet));
  }

  private byte[] pinKey(Object applet) throws Exception {
    byte[] key = new byte[16];
    ((javacard.security.AESKey) field(applet, "pinKey")).getKey(key, (short) 0);
    return key;
  }

  @Test
  void encryptedPinDependsOnBothSecretsAndRekeyUsesNewPin() throws Exception {
    init();
    Object applet = applet();
    byte[] r = root(applet), key = f(r, "TTM3/PIN/ENC", Wire.id("TTM000001"), 128);
    assertEquals(
        "123456",
        new String(
            decrypt(key, bytes(applet, "pinIv0", "pinIv1"), bytes(applet, "pin0", "pin1")),
            0,
            6,
            StandardCharsets.US_ASCII));
    byte[] oldKey =
        f(
            r,
            "TTM3/DATA/ENC",
            Wire.concat(
                Wire.id("TTM000001"), ByteBuffer.allocate(4).putInt(1).array(), pin("123456")),
            128);
    login("123456");
    mutate(Wire.CHANGE_PIN, pin("654321"));
    assertArrayEquals(r, root(applet));
    assertArrayEquals(key, pinKey(applet));
    assertEquals(
        "654321",
        new String(
            decrypt(key, bytes(applet, "pinIv0", "pinIv1"), bytes(applet, "pin0", "pin1")),
            0,
            6,
            StandardCharsets.US_ASCII));
    byte[] newKey =
        f(
            r,
            "TTM3/DATA/ENC",
            Wire.concat(
                Wire.id("TTM000001"), ByteBuffer.allocate(4).putInt(2).array(), pin("654321")),
            128);
    assertFalse(Arrays.equals(oldKey, newKey));
  }

  @ParameterizedTest
  @ValueSource(strings = {"ciphertext", "iv", "tag", "p", "c"})
  void profileTamperingFailsBeforePlaintextIsReturned(String name) throws Exception {
    init();
    login("123456");
    Object a = applet();
    byte[] value = bytes(a, name + "0", name + "1");
    value[0] ^= 1;
    assertEquals("CARD_CRYPTO", assertThrows(Failure.class, () -> use(CardClient::profile)).code);
  }

  @ParameterizedTest
  @ValueSource(strings = {"pin", "pinIv", "pinTag"})
  void pinRecordTamperingIsCryptoFailureRatherThanWrongPin(String name) throws Exception {
    init();
    Object a = applet();
    byte[] value = bytes(a, name + "0", name + "1");
    value[0] ^= 1;
    assertEquals(
        "CARD_CRYPTO", assertThrows(Failure.class, () -> use(c -> c.verifyPin("123456"))).code);
  }

  @Test
  void ivChangesOnEveryWriteEvenForIdenticalProfile() throws Exception {
    Profile p = init();
    Object a = applet();
    byte[] iv = bytes(a, "iv0", "iv1").clone(), pinIv = bytes(a, "pinIv0", "pinIv1").clone();
    admin();
    Profile next = p.withCard(p.cardId(), 2);
    mutate(Wire.UPDATE, ProfileCodec.encode(next));
    assertFalse(Arrays.equals(iv, bytes(a, "iv0", "iv1")));
    assertFalse(Arrays.equals(pinIv, bytes(a, "pinIv0", "pinIv1")));
  }
}
