package vn.edu.smartcard.card;

import javacard.framework.*;
import javacard.security.*;
import javacardx.crypto.Cipher;

/**
 * Test keys only. Qualify this separate instance before installing the student
 * instance.
 */
public final class ResourceProbeApplet extends Applet {
  private byte[] arena;
  private short[] memory = new short[2];
  private short baseline;
  private AESKey pin, oldKey, nextKey;
  private HMACKey root, pinMac, oldMac, nextMac, secret;
  private ECPrivateKey privateKey;
  private ECPublicKey publicKey;
  private Signature signer, hmac;
  private Cipher enc, dec;
  private MessageDigest digest;
  private RandomData random;
  private static final byte[] AES_ZERO = {
      (byte) 0x66, (byte) 0xe9, (byte) 0x4b, (byte) 0xd4, (byte) 0xef, (byte) 0x8a, (byte) 0x2c, (byte) 0x3b,
      (byte) 0x88, (byte) 0x4c, (byte) 0xfa, (byte) 0x59, (byte) 0xca, (byte) 0x34, (byte) 0x2b, (byte) 0x2e
  };

  public static void install(byte[] b, short o, byte l) {
    new ResourceProbeApplet().register();
  }

  private ResourceProbeApplet() {
    baseline = JCSystem.getAvailableMemory(JCSystem.MEMORY_TYPE_TRANSIENT_DESELECT);
    arena = JCSystem.makeTransientByteArray((short) 1536, JCSystem.CLEAR_ON_DESELECT);
    pin = aes();
    oldKey = aes();
    nextKey = aes();
    root = mac();
    pinMac = mac();
    oldMac = mac();
    nextMac = mac();
    secret = (HMACKey) KeyBuilder.buildKey(KeyBuilder.TYPE_HMAC, (short) 256, false);
    KeyPair pair = new KeyPair(KeyPair.ALG_EC_FP, KeyBuilder.LENGTH_EC_FP_256);
    privateKey = (ECPrivateKey) pair.getPrivate();
    publicKey = (ECPublicKey) pair.getPublic();
    StudentCardApplet.curve(privateKey);
    StudentCardApplet.curve(publicKey);
    pair.genKeyPair();
    signer = Signature.getInstance(Signature.ALG_ECDSA_SHA_256, false);
    hmac = Signature.getInstance(Signature.ALG_HMAC_SHA_256, false);
    enc = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_CBC_NOPAD, false);
    dec = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_CBC_NOPAD, false);
    digest = MessageDigest.getInstance(MessageDigest.ALG_SHA_256, false);
    random = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);
  }

  private static AESKey aes() {
    return (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES_TRANSIENT_DESELECT, KeyBuilder.LENGTH_AES_128, false);
  }

  private static HMACKey mac() {
    return (HMACKey) KeyBuilder.buildKey(KeyBuilder.TYPE_HMAC_TRANSIENT_DESELECT, (short) 256, false);
  }

  public void process(APDU a) {
    if (selectingApplet())
      return;
    byte[] b = a.getBuffer();
    if (b[0] != (byte) 0x80)
      ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
    if (b[1] == 0x10) {
      b[0] = 'T';
      b[1] = 'T';
      b[2] = 'P';
      b[3] = 1;
      Util.setShort(b, (short) 4, baseline);
      Util.setShort(b, (short) 6, JCSystem.getAvailableMemory(JCSystem.MEMORY_TYPE_TRANSIENT_DESELECT));
      Util.setShort(b, (short) 8, JCSystem.getAvailableMemory(JCSystem.MEMORY_TYPE_TRANSIENT_RESET));
      Util.setShort(b, (short) 10, JCSystem.getMaxCommitCapacity());
      JCSystem.getAvailableMemory(memory, (short) 0, JCSystem.MEMORY_TYPE_PERSISTENT);
      Util.setShort(b, (short) 12, memory[0]);
      Util.setShort(b, (short) 14, memory[1]);
      Util.setShort(b, (short) 16, (short) arena.length);
      Util.setShort(b, (short) 18, JCSystem.getVersion());
      a.setOutgoingAndSend((short) 0, (short) 20);
    } else if (b[1] == 0x20) {
      selfTest();
      b[0] = 1;
      a.setOutgoingAndSend((short) 0, (short) 1);
    } else
      ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
  }

  private void selfTest() {
    Util.arrayFillNonAtomic(arena, (short) 0, (short) arena.length, (byte) 0);
    pin.setKey(arena, (short) 0);
    oldKey.setKey(arena, (short) 0);
    nextKey.setKey(arena, (short) 0);
    root.setKey(arena, (short) 0, (short) 32);
    pinMac.setKey(arena, (short) 0, (short) 32);
    oldMac.setKey(arena, (short) 0, (short) 32);
    nextMac.setKey(arena, (short) 0, (short) 32);
    secret.setKey(arena, (short) 0, (short) 32);
    enc.init(oldKey, Cipher.MODE_ENCRYPT, arena, (short) 32, (short) 16);
    enc.doFinal(arena, (short) 0, (short) 16, arena, (short) 64);
    if (Util.arrayCompare(arena, (short) 64, AES_ZERO, (short) 0, (short) 16) != 0)
      ISOException.throwIt((short) 0x6984);
    dec.init(oldKey, Cipher.MODE_DECRYPT, arena, (short) 32, (short) 16);
    dec.doFinal(arena, (short) 64, (short) 16, arena, (short) 96);
    if (Util.arrayCompare(arena, (short) 96, arena, (short) 0, (short) 16) != 0)
      ISOException.throwIt((short) 0x6984);
    hmac.init(root, Signature.MODE_SIGN);
    hmac.update(arena, (short) 0, (short) 16);
    hmac.sign(arena, (short) 16, (short) 16, arena, (short) 128);
    hmac.init(root, Signature.MODE_VERIFY);
    if (!hmac.verify(arena, (short) 0, (short) 32, arena, (short) 128, (short) 32))
      ISOException.throwIt((short) 0x6984);
    digest.doFinal(arena, (short) 0, (short) 32, arena, (short) 160);
    signer.init(privateKey, Signature.MODE_SIGN);
    short n = signer.sign(arena, (short) 160, (short) 32, arena, (short) 192);
    signer.init(publicKey, Signature.MODE_VERIFY);
    if (!signer.verify(arena, (short) 160, (short) 32, arena, (short) 192, n))
      ISOException.throwIt((short) 0x6984);
    random.generateData(arena, (short) 256, (short) 32);
    JCSystem.beginTransaction();
    memory[0] = 1;
    memory[1] = 2;
    JCSystem.abortTransaction();
    pin.clearKey();
    oldKey.clearKey();
    nextKey.clearKey();
    root.clearKey();
    pinMac.clearKey();
    oldMac.clearKey();
    nextMac.clearKey();
    secret.clearKey();
    Util.arrayFillNonAtomic(arena, (short) 0, (short) arena.length, (byte) 0);
  }
}
