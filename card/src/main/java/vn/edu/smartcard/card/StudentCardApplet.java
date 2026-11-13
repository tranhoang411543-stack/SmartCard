package vn.edu.smartcard.card;

import javacard.framework.*;
import javacard.security.*;
import javacardx.crypto.Cipher;

/**
 * V3. Trusted APDU transport. Only identity, card secret, ciphertext and
 * operation metadata persist. Derived keys are CLEAR_ON_DESELECT objects.
 */
public final class StudentCardApplet extends Applet {

    private static final short MAX = 28016, INVALID = (short) 0x6A80, DENIED = (short) 0x6982;
    private final ECPrivateKey privateKey;
    private final ECPublicKey publicKey;
    private final HMACKey secret;
    private final byte[] id = new byte[16],
            identity = new byte[32],
            counter = new byte[4],
            balance = new byte[4],
            walletVersion = new byte[4];
    private final byte[] ciphertext0 = new byte[MAX],
            ciphertext1 = new byte[MAX],
            iv0 = new byte[16],
            iv1 = new byte[16],
            tag0 = new byte[32],
            tag1 = new byte[32];
    private final byte[] pin0 = new byte[16],
            pin1 = new byte[16],
            pinIv0 = new byte[16],
            pinIv1 = new byte[16],
            pinTag0 = new byte[32],
            pinTag1 = new byte[32];
    private final short[] lengths = new short[2], cipherLengths = new short[2];
    private final byte[] p0 = new byte[4], p1 = new byte[4], c0 = new byte[4], c1 = new byte[4];
    private byte active, initialized, phase, preparedSlot, pendingKind, lastKind;
    private final byte[] pendingOp = new byte[8],
            pendingHash = new byte[32],
            pendingId = new byte[16],
            pendingBalance = new byte[4],
            pendingWalletVersion = new byte[4];
    private final byte[] lastOp = new byte[8], lastHash = new byte[32], lastAck = new byte[8];

    private byte financialHold;
    private byte[] debitId = new byte[8],
            debitBefore = new byte[4],
            debitAmount = new byte[4],
            debitWallet = new byte[4];

    /**
     * Simulator-only upgrade of the explicit persistent allowlist; no APDU
     * exports it.
     */
    public void upgradeFinancialState() {
        if (debitId == null) {
            debitId = new byte[8];
            debitBefore = new byte[4];
            debitAmount = new byte[4];
            debitWallet = new byte[4];
            financialHold = 0;
        }
        if (hash0 == null) {
            hash0 = new byte[32];
            hash1 = new byte[32];
            hashValid = new byte[2];
        }
    }

    // Runtime buffers/crypto handles; hash0/hash1/hashValid are persistent record
    // caches.
    private byte[] input, stage, work, scratch, session, context, ramId, header, output;
    // All counters/flags below live in transient arrays, including on a physical
    // card.
    private byte[] runtime, tail, streamIv, readState;
    private static final short EXPECTED = 0, RECEIVED = 2, OUTPUT_LENGTH = 4,
            TRANSFER = 6, READY = 7, CARRY = 8, WRITTEN = 10, DISCARD = 12;
    private byte[] hash0 = new byte[32], hash1 = new byte[32], hashValid = new byte[2];
    private AESKey pinKey;
    private HMACKey root, pinMac;
    private AESKey[] aes;
    private HMACKey[] mac;
    private Cipher cipher, decipher;
    private Signature signer, hmac;
    private MessageDigest digest;
    private RandomData random;

    public static void install(byte[] b, short o, byte l) {
        new StudentCardApplet().register();
    }

    private StudentCardApplet() {
        initRam();
        secret = (HMACKey) KeyBuilder.buildKey(KeyBuilder.TYPE_HMAC, (short) 256, false);
        random.generateData(scratch, (short) 0, (short) 32);
        secret.setKey(scratch, (short) 0, (short) 32);
        KeyPair pair = new KeyPair(KeyPair.ALG_EC_FP, KeyBuilder.LENGTH_EC_FP_256);
        privateKey = (ECPrivateKey) pair.getPrivate();
        publicKey = (ECPublicKey) pair.getPublic();
        curve(privateKey);
        curve(publicKey);
        pair.genKeyPair();
        short n = publicKey.getW(scratch, (short) 0);
        digest.doFinal(scratch, (short) 0, n, identity, (short) 0);
        wipe(scratch);
    }

    static void curve(ECKey key) {
        key.setFieldFP(CardConstants.P, (short) 0, (short) 32);
        key.setA(CardConstants.A, (short) 0, (short) 32);
        key.setB(CardConstants.B, (short) 0, (short) 32);
        key.setG(CardConstants.G, (short) 0, (short) 65);
        key.setR(CardConstants.R, (short) 0, (short) 32);
        key.setK((short) 1);
    }

    private static byte[] ram(short n) {
        return JCSystem.makeTransientByteArray(n, JCSystem.CLEAR_ON_DESELECT);
    }

    private static HMACKey ramMac() {
        return (HMACKey) KeyBuilder.buildKey(KeyBuilder.TYPE_HMAC_TRANSIENT_DESELECT, (short) 256, false);
    }

    private void initRam() {
        if (input != null) {
            return;
        }
        input = ram((short) 256);
        stage = ram((short) 32);
        work = ram((short) 256);
        scratch = ram((short) 256);
        session = ram((short) 16);
        context = ram((short) 2);
        ramId = ram((short) 16);
        header = ram((short) 63);
        output = ram((short) 288);
        runtime = ram((short) 24);
        tail = ram((short) 16);
        streamIv = ram((short) 16);
        readState = ram((short) 8);
        pinKey = (AESKey) KeyBuilder.buildKey(
                KeyBuilder.TYPE_AES_TRANSIENT_DESELECT, KeyBuilder.LENGTH_AES_128, false);
        root = ramMac();
        pinMac = ramMac();
        aes = new AESKey[2];
        mac = new HMACKey[2];
        for (byte i = 0; i < 2; i++) {
            aes[i] = (AESKey) KeyBuilder.buildKey(
                    KeyBuilder.TYPE_AES_TRANSIENT_DESELECT, KeyBuilder.LENGTH_AES_128, false);
            mac[i] = ramMac();
        }
        cipher = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_CBC_NOPAD, false);
        decipher = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_CBC_NOPAD, false);
        signer = Signature.getInstance(Signature.ALG_ECDSA_SHA_256, false);
        hmac = Signature.getInstance(Signature.ALG_HMAC_SHA_256, false);
        digest = MessageDigest.getInstance(MessageDigest.ALG_SHA_256, false);
        random = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);
    }

    private static void wipe(byte[] b) {
        if (b != null) {
            Util.arrayFillNonAtomic(b, (short) 0, (short) b.length, (byte) 0);
        }
    }

    private byte[] ct(byte s) {
        return s == 0 ? ciphertext0 : ciphertext1;
    }

    private byte[] iv(byte s) {
        return s == 0 ? iv0 : iv1;
    }

    private byte[] tag(byte s) {
        return s == 0 ? tag0 : tag1;
    }

    private byte[] pc(byte s) {
        return s == 0 ? pin0 : pin1;
    }

    private byte[] pi(byte s) {
        return s == 0 ? pinIv0 : pinIv1;
    }

    private byte[] pt(byte s) {
        return s == 0 ? pinTag0 : pinTag1;
    }

    private byte[] pv(byte s) {
        return s == 0 ? p0 : p1;
    }

    private byte[] cv(byte s) {
        return s == 0 ? c0 : c1;
    }

    private void clearKeys() {
        context[0] = context[1] = 0;
        pinKey.clearKey();
        pinMac.clearKey();
        root.clearKey();
        for (byte i = 0; i < 2; i++) {
            aes[i].clearKey();
            mac[i].clearKey();
        }
    }

    private void clearContext() {
        clearKeys();
        wipe(ramId);
        wipe(input);
        wipe(stage);
        wipe(work);
        wipe(scratch);
        wipe(header);
        wipe(output);
        endTransfer();
        wipe(readState);
        Util.setShort(runtime, OUTPUT_LENGTH, (short) 0);
    }

    public boolean select() {
        initRam();
        clearContext();
        random.generateData(session, (short) 0, (short) 16);
        return true;
    }

    public void deselect() {
        clearContext();
        wipe(session);
    }

    private short receive(APDU a) {
        short n = a.setIncomingAndReceive(),
                total = a.getIncomingLength(),
                got = 0,
                off = a.getOffsetCdata();
        if (total > 256) {
            ISOException.throwIt(INVALID);
        }
        while (n > 0) {
            Util.arrayCopyNonAtomic(a.getBuffer(), off, input, got, n);
            got += n;
            if (got == total) {
                break;
            }
            n = a.receiveBytes(off);
        }
        if (got != total) {
            ISOException.throwIt(INVALID);
        }
        return total;
    }

    public void process(APDU a) {
        if (selectingApplet()) {
            return;
        }
        byte[] b = a.getBuffer();
        if (b[0] != (byte) 0x80) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }
        try {
            if (runtime[TRANSFER] != 0 && b[1] != 0x53 && b[1] != 0x54
                    && b[1] != 0x10 && b[1] != 0x50 && b[1] != 0x21) {
                ISOException.throwIt((short) 0x6985);
            }
            switch (b[1]) {
                case 0x10:
                    b[0] = initialized;
                    b[1] = 0;
                    b[2] = context[0];
                    b[3] = 0;
                    Util.arrayCopyNonAtomic(id, (short) 0, b, (short) 4, (short) 16);
                    Util.arrayCopyNonAtomic(counter, (short) 0, b, (short) 20, (short) 4);
                    Util.setShort(b, (short) 24, lengths[active]);
                    a.setOutgoingAndSend((short) 0, (short) 26);
                    break;
                case 0x12:
                    short n = publicKey.getW(b, (short) 0);
                    a.setOutgoingAndSend((short) 0, n);
                    break;
                case 0x13:
                    prove(a, b[2]);
                    break;
                case 0x14:
                    send(
                            a,
                            output,
                            (short) (((short) (b[2] & 255) << 8) | (short) (b[3] & 255)),
                            (short) 180,
                            Util.getShort(runtime, OUTPUT_LENGTH));
                    break;
                case 0x20:
                    verifyPin(a);
                    break;
                case 0x21:
                    clearContext();
                    break;
                case 0x22:
                    readProfile(a, (short) (((short) (b[2] & 255) << 8) | (short) (b[3] & 255)));
                    break;
                case 0x23:
                    requireRole(true);
                    Util.arrayCopyNonAtomic(balance, (short) 0, b, (short) 0, (short) 4);
                    a.setOutgoingAndSend((short) 0, (short) 4);
                    break;
                case 0x24:
                    unlock(a, b[2]);
                    break;
                case 0x50:
                    b[0] = 4;
                    Util.arrayCopyNonAtomic(session, (short) 0, b, (short) 1, (short) 16);
                    b[17] = phase;
                    Util.arrayCopyNonAtomic(identity, (short) 0, b, (short) 18, (short) 32);
                    a.setOutgoingAndSend((short) 0, (short) 50);
                    break;
                case 0x52:
                    beginTransfer(a);
                    break;
                case 0x53:
                    receiveChunk(a);
                    break;
                case 0x54:
                    invoke(a);
                    break;
                default:
                    ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
            }
        } catch (RuntimeException failure) {
            endTransfer();
            wipe(readState);
            throw failure;
        } finally {
            wipe(input);
            wipe(scratch);
            wipe(work);
        }
    }

    private void send(APDU a, byte[] source, short off, short count, short total) {
        if (off < 0 || off >= total || count < 0) {
            ISOException.throwIt(INVALID);
        }
        short n = (short) (total - off);
        if (n > count) {
            n = count;
        }
        Util.arrayCopyNonAtomic(source, off, a.getBuffer(), (short) 0, n);
        a.setOutgoingAndSend((short) 0, n);
    }

    private void prove(APDU a, byte purpose) {
        if (receive(a) != 40) {
            ISOException.throwIt(INVALID);
        }
        Util.arrayCopyNonAtomic(CardConstants.AUTH, (short) 0, output, (short) 0, (short) 9);
        output[9] = purpose;
        Util.arrayCopyNonAtomic(id, (short) 0, output, (short) 10, (short) 16);
        Util.arrayCopyNonAtomic(session, (short) 0, output, (short) 26, (short) 16);
        Util.arrayCopyNonAtomic(input, (short) 0, output, (short) 42, (short) 40);
        signer.init(privateKey, Signature.MODE_SIGN);
        Util.setShort(runtime, OUTPUT_LENGTH,
                (short) (82 + signer.sign(output, (short) 0, (short) 82, output, (short) 82)));
        Util.setShort(a.getBuffer(), (short) 0, Util.getShort(runtime, OUTPUT_LENGTH));
        a.setOutgoingAndSend((short) 0, (short) 2);
    }

    private void kdf(
            HMACKey key,
            byte[] label,
            byte[] ctx,
            short off,
            short len,
            short bits,
            byte[] dest,
            short destOff) {
        wipe(scratch);
        scratch[3] = 1;
        short n = (short) label.length;
        Util.arrayCopyNonAtomic(label, (short) 0, scratch, (short) 4, n);
        Util.arrayCopyNonAtomic(ctx, off, scratch, (short) (5 + n), len);
        short end = (short) (5 + n + len);
        Util.setShort(scratch, (short) (end + 2), bits);
        hmac.init(key, Signature.MODE_SIGN);
        hmac.sign(scratch, (short) 0, (short) (end + 4), dest, destOff);
    }

    private void unlock(APDU a, byte role) {
        if (receive(a) != 48 || (role != 0 && role != 2)) {
            ISOException.throwIt(INVALID);
        }
        if (initialized != 0 && Util.arrayCompare(input, (short) 0, id, (short) 0, (short) 16) != 0) {
            ISOException.throwIt(DENIED);
        }
        clearKeys();
        Util.arrayCopyNonAtomic(input, (short) 0, ramId, (short) 0, (short) 16);
        wipe(work);
        Util.arrayCopyNonAtomic(input, (short) 0, work, (short) 0, (short) 16);
        work[19] = 1;
        Util.arrayCopyNonAtomic(input, (short) 16, work, (short) 20, (short) 32);
        kdf(secret, CardConstants.ROOT, work, (short) 0, (short) 52, (short) 256, work, (short) 100);
        root.setKey(work, (short) 100, (short) 32);
        kdf(root, CardConstants.PIN_ENC, ramId, (short) 0, (short) 16, (short) 128, work, (short) 100);
        pinKey.setKey(work, (short) 100);
        kdf(root, CardConstants.PIN_MAC, ramId, (short) 0, (short) 16, (short) 256, work, (short) 100);
        pinMac.setKey(work, (short) 100, (short) 32);
        context[1] = 1;
        if (initialized != 0) {
            openPin(active);
            if (role == 2) {
                deriveData(active, work, (short) 0);
            }
        }
        context[0] = role;
    }

    private void checkPinFormat(byte[] data, short off) {
        for (short i = 0; i < 6; i++) {
            if (data[(short) (off + i)] < '0' || data[(short) (off + i)] > '9') {
                ISOException.throwIt(INVALID);
            }
        }
    }

    private void verifyPin(APDU a) {
        if (initialized == 0 || context[1] == 0 || phase != 0 || financialHold != 0) {
            ISOException.throwIt(DENIED);
        }
        if (receive(a) != 6) {
            ISOException.throwIt(INVALID);
        }
        checkPinFormat(input, (short) 0);
        context[0] = 0;
        openPin(active);
        byte diff = 0;
        for (short i = 0; i < 6; i++) {
            diff |= (byte) (input[i] ^ work[i]);
        }
        if (diff == 0) {
            deriveData(active, input, (short) 0);
            context[0] = 1;
        } else {
            aes[active].clearKey();
            mac[active].clearKey();
        }
        a.getBuffer()[0] = (byte) (diff == 0 ? 1 : 0);
        a.setOutgoingAndSend((short) 0, (short) 1);
    }

    private void requireRole(boolean student) {
        if (initialized == 0
                || context[1] == 0
                || (student ? context[0] != 1 : context[0] != 1 && context[0] != 2)) {
            ISOException.throwIt(DENIED);
        }
    }

    private void deriveData(byte slot, byte[] pin, short off) {
        Util.arrayCopyNonAtomic(ramId, (short) 0, work, (short) 100, (short) 16);
        Util.arrayCopyNonAtomic(cv(slot), (short) 0, work, (short) 116, (short) 4);
        Util.arrayCopyNonAtomic(pin, off, work, (short) 120, (short) 6);
        kdf(root, CardConstants.DATA_ENC, work, (short) 100, (short) 26, (short) 128, work, (short) 160);
        aes[slot].setKey(work, (short) 160);
        kdf(root, CardConstants.DATA_MAC, work, (short) 100, (short) 26, (short) 256, work, (short) 160);
        mac[slot].setKey(work, (short) 160, (short) 32);
    }

    private void pinTag(byte slot, byte[] dest) {
        wipe(scratch);
        scratch[0] = 1;
        scratch[1] = 3;
        Util.arrayCopyNonAtomic(ramId, (short) 0, scratch, (short) 2, (short) 16);
        Util.arrayCopyNonAtomic(cv(slot), (short) 0, scratch, (short) 18, (short) 4);
        Util.arrayCopyNonAtomic(pi(slot), (short) 0, scratch, (short) 22, (short) 16);
        Util.setShort(scratch, (short) 38, (short) 6);
        hmac.init(pinMac, Signature.MODE_SIGN);
        hmac.update(scratch, (short) 0, (short) 40);
        hmac.sign(pc(slot), (short) 0, (short) 16, dest, (short) 0);
    }

    private void sealPin(byte slot, byte[] pin, short off) {
        checkPinFormat(pin, off);
        Util.arrayCopyNonAtomic(pin, off, work, (short) 0, (short) 6);
        work[6] = (byte) 0x80;
        Util.arrayFillNonAtomic(work, (short) 7, (short) 9, (byte) 0);
        random.generateData(pi(slot), (short) 0, (short) 16);
        cipher.init(pinKey, Cipher.MODE_ENCRYPT, pi(slot), (short) 0, (short) 16);
        cipher.doFinal(work, (short) 0, (short) 16, pc(slot), (short) 0);
        pinTag(slot, pt(slot));
        wipe(work);
    }

    private void openPin(byte slot) {
        pinTag(slot, output);
        verifyTag(output, pt(slot));
        wipe(output);
        cipher.init(pinKey, Cipher.MODE_DECRYPT, pi(slot), (short) 0, (short) 16);
        cipher.doFinal(pc(slot), (short) 0, (short) 16, work, (short) 0);
        padding(work, (short) 6, (short) 16);
        checkPinFormat(work, (short) 0);
    }

    private static void verifyTag(byte[] actual, byte[] expected) {
        byte diff = 0;
        for (short i = 0; i < 32; i++) {
            diff |= (byte) (actual[i] ^ expected[i]);
        }
        if (diff != 0) {
            ISOException.throwIt((short) 0x6984);
        }
    }

    private static void padding(byte[] data, short n, short end) {
        byte diff = (byte) (data[n] ^ (byte) 0x80);
        for (short i = (short) (n + 1); i < end; i++) {
            diff |= data[i];
        }
        if (diff != 0) {
            ISOException.throwIt((short) 0x6984);
        }
    }

    private byte[] recordId(byte slot) {
        return initialized == 0 || phase != 0 && pendingKind == 1 && slot == preparedSlot
                ? pendingId
                : id;
    }

    private void writeHeader(byte slot, byte[] out) {
        out[0] = 3;
        Util.arrayCopyNonAtomic(recordId(slot), (short) 0, out, (short) 1, (short) 16);
        Util.arrayCopyNonAtomic(pv(slot), (short) 0, out, (short) 17, (short) 4);
        Util.arrayCopyNonAtomic(cv(slot), (short) 0, out, (short) 21, (short) 4);
        Util.arrayCopyNonAtomic(iv(slot), (short) 0, out, (short) 25, (short) 16);
        Util.setShort(out, (short) 41, lengths[slot]);
        Util.setShort(out, (short) 43, cipherLengths[slot]);
        Util.arrayCopyNonAtomic(tag(slot), (short) 0, out, (short) 45, (short) 32);
    }

    private void profileTag(byte slot, byte[] dest) {
        writeHeader(slot, scratch);
        hmac.init(mac[slot], Signature.MODE_SIGN);
        scratch[100] = 2;
        hmac.update(scratch, (short) 100, (short) 1);
        hmac.update(scratch, (short) 0, (short) 45);
        short at = 0, remaining = cipherLengths[slot];
        while (remaining > 256) {
            hmac.update(ct(slot), at, (short) 256);
            at += 256;
            remaining -= 256;
        }
        hmac.sign(ct(slot), at, remaining, dest, (short) 0);
    }

    private void verifyProfile(byte slot) {
        if (lengths[slot] <= 0 || lengths[slot] > 28000
                || cipherLengths[slot] != (short) ((lengths[slot] / 16 + 1) * 16)) {
            ISOException.throwIt((short) 0x6984);
        }
        profileTag(slot, output);
        verifyTag(output, tag(slot));
        wipe(output);
        short last = (short) (cipherLengths[slot] - 16);
        decryptWindow(slot, last, (short) 16);
        padding(work, (short) (lengths[slot] % 16), (short) 16);
        wipe(work);
    }

    private void decryptWindow(byte slot, short block, short count) {
        if (block == 0) {
            cipher.init(aes[slot], Cipher.MODE_DECRYPT, iv(slot), (short) 0, (short) 16); 
        }else {
            cipher.init(aes[slot], Cipher.MODE_DECRYPT, ct(slot), (short) (block - 16), (short) 16);
        }
        cipher.doFinal(ct(slot), block, count, work, (short) 0);
    }

    private void readProfile(APDU a, short at) {
        requireRole(false);
        if (at < 0 || at >= lengths[active]) {
            ISOException.throwIt(INVALID);
        }
        if (readState[0] == 0 || readState[1] != active || at != Util.getShort(readState, (short) 2)) {
            verifyProfile(active);
            readState[0] = 1;
            readState[1] = active;
        }
        short count = (short) (lengths[active] - at);
        if (count > 180) {
            count = 180;
        }
        short block = (short) (at - at % 16), skip = (short) (at % 16);
        short size = (short) (((short) (skip + count + 15) / 16) * 16);
        decryptWindow(active, block, size);
        Util.arrayCopyNonAtomic(work, skip, a.getBuffer(), (short) 0, count);
        Util.setShort(readState, (short) 2, (short) (at + count));
        if ((short) (at + count) == lengths[active]) {
            readState[0] = 0;
        }
        a.setOutgoingAndSend((short) 0, count);
    }

    private void invoke(APDU a) {
        if (runtime[TRANSFER] == 0 || received() != expected()) {
            ISOException.throwIt(INVALID);
        }
        byte scope = header[0];
        if (initialized != 0 && Util.arrayCompare(header, (short) 1, id, (short) 0, (short) 16) != 0) {
            ISOException.throwIt(DENIED);
        }
        short off = Util.getShort(header, (short) 25), len = Util.getShort(header, (short) 27);
        if (off < 0 || len < 0 || len > 180) {
            ISOException.throwIt(INVALID);
        }
        try {
            if (scope == 1) {
                prepare(); 
            }else if (scope == 2) {
                commit(); 
            }else if (scope == 3) {
                ack(); 
            }else if (scope == 4) {
                abort(); 
            }else if (scope == 5) {
                buildState();
                send(a, output, off, len, (short) 288);
            } else if (scope == 10) {
                release();
            } else if (scope == 9) {
                if (expected() != 1) {
                    ISOException.throwIt(INVALID);
                }
                byte slot = stage[0] == 1 ? preparedSlot : active;
                if (stage[0] == 1
                        && (phase == 0
                        || Util.arrayCompare(header, (short) 17, pendingOp, (short) 0, (short) 8) != 0)) {
                    ISOException.throwIt(DENIED);
                }
                exportPart(a, slot, off, len);
            } else {
                ISOException.throwIt(INVALID);
            }
        } finally {
            wipe(stage);
            wipe(header);
            endTransfer();
        }
    }

    private void nextVersion(short at, byte[] current, boolean advance) {
        short carry = (short) (advance ? 1 : 0);
        for (short i = 3; i >= 0; i--) {
            short v = (short) ((current[i] & 255) + carry);
            if (stage[(short) (at + i)] != (byte) v) {
                ISOException.throwIt((short) 0x6985);
            }
            carry = (short) (v > 255 ? 1 : 0);
        }
        if (carry != 0) {
            ISOException.throwIt(INVALID);
        }
    }

    private void prepare() {
        if (runtime[DISCARD] != 0) {
            return;
        }
        if (runtime[READY] == 0) {
            ISOException.throwIt(INVALID);
        }
        byte kind = stage[0];
        if (kind == 1 || kind == 2) {
            finishProfile(); 
        }else if (kind == 3 || kind == 8) {
            rekeyProfile(); 
        }else {
            if (expected() != 29) {
                ISOException.throwIt(INVALID);
            }
            if (kind == 9
                    && (Util.arrayCompare(stage, (short) 17, debitId, (short) 0, (short) 8) != 0
                    || Util.arrayCompare(stage, (short) 25, debitAmount, (short) 0, (short) 4) != 0
                    || Util.arrayCompare(walletVersion, (short) 0, debitWallet, (short) 0, (short) 4) != 0)) {
                ISOException.throwIt(DENIED);
            }
            if (kind == 7 && compare(balance, (short) 0, stage, (short) 25, (short) 4) < 0) {
                ISOException.throwIt((short) 0x6A84);
            }
            byte nz = 0;
            for (short i = 25; i < 29; i++) {
                nz |= stage[i];
            }
            if (nz == 0 || stage[25] < 0) {
                ISOException.throwIt(INVALID);
            }
            short carry = 0;
            for (short i = 3; i >= 0; i--) {
                short amount = (short) (stage[(short) (25 + i)] & 255);
                if (kind == 7) {
                    amount = (short) -amount;
                }
                short v = (short) ((balance[i] & 255) + amount + carry);
                pendingBalance[i] = (byte) v;
                carry = 0;
                if (kind == 7 && v < 0) {
                    carry = -1;
                }
                if (kind != 7 && v > 255) {
                    carry = 1;
                }
            }
            if ((kind == 6 || kind == 9) && (carry != 0 || pendingBalance[0] < 0)) {
                ISOException.throwIt((short) 0x6A84);
            }
            if (kind == 9
                    && Util.arrayCompare(pendingBalance, (short) 0, debitBefore, (short) 0, (short) 4) != 0) {
                ISOException.throwIt(DENIED);
            }
            Util.arrayCopyNonAtomic(stage, (short) 13, pendingWalletVersion, (short) 0, (short) 4);
        }
        JCSystem.beginTransaction();
        Util.arrayCopy(header, (short) 17, pendingOp, (short) 0, (short) 8);
        Util.arrayCopy(header, (short) 31, pendingHash, (short) 0, (short) 32);
        phase = 1;
        JCSystem.commitTransaction();
    }

    private static short compare(byte[] a, short ao, byte[] b, short bo, short n) {
        for (short i = 0; i < n; i++) {
            short x = (short) (a[(short) (ao + i)] & 255), y = (short) (b[(short) (bo + i)] & 255);
            if (x != y) {
                return (short) (x < y ? -1 : 1);
            }
        }
        return 0;
    }

    private void requirePending(byte p) {
        if (phase != p || Util.arrayCompare(header, (short) 17, pendingOp, (short) 0, (short) 8) != 0) {
            ISOException.throwIt((short) 0x6985);
        }
    }

    private void commit() {
        if (phase == 0 && Util.arrayCompare(header, (short) 17, lastOp, (short) 0, (short) 8) == 0) {
            return;
        }
        if (phase == 2 && Util.arrayCompare(header, (short) 17, pendingOp, (short) 0, (short) 8) == 0) {
            return;
        }
        requirePending((byte) 1);
        JCSystem.beginTransaction();
        if (pendingKind == 1) {
            Util.arrayCopy(pendingId, (short) 0, id, (short) 0, (short) 16);
            initialized = 1;
        }
        active = preparedSlot;
        wipe(readState);
        if (pendingKind == 7) {
            Util.arrayCopy(balance, (short) 0, debitBefore, (short) 0, (short) 4);
            Util.arrayCopy(pendingOp, (short) 0, debitId, (short) 0, (short) 8);
            Util.arrayCopy(pendingWalletVersion, (short) 0, debitWallet, (short) 0, (short) 4);
            short borrow = 0;
            for (short i = 3; i >= 0; i--) {
                short v = (short) ((balance[i] & 255) - (pendingBalance[i] & 255) + borrow);
                scratch[i] = (byte) v;
                borrow = (short) (v < 0 ? -1 : 0);
            }
            Util.arrayCopy(scratch, (short) 0, debitAmount, (short) 0, (short) 4);
            financialHold = 1;
        } else if (pendingKind == 9) {
            financialHold = 2;
        }
        Util.arrayCopy(pendingBalance, (short) 0, balance, (short) 0, (short) 4);
        Util.arrayCopy(pendingWalletVersion, (short) 0, walletVersion, (short) 0, (short) 4);
        Util.arrayCopy(pendingOp, (short) 0, lastOp, (short) 0, (short) 8);
        Util.arrayCopy(pendingHash, (short) 0, lastHash, (short) 0, (short) 32);
        lastKind = pendingKind;
        for (short i = 3; i >= 0; i--) {
            counter[i]++;
            if (counter[i] != 0) {
                break;
            }
        }
        phase = 2;
        JCSystem.commitTransaction();
    }

    private void clearInactive() {
        byte old = (byte) (1 - active);
        hashValid[old] = 0;
        wipe(iv(old));
        wipe(tag(old));
        wipe(pc(old));
        wipe(pi(old));
        wipe(pt(old));
        wipe(pv(old));
        wipe(cv(old));
        lengths[old] = cipherLengths[old] = 0;
        aes[old].clearKey();
        mac[old].clearKey();
    }

    private void ack() {
        if (phase == 0 && Util.arrayCompare(header, (short) 17, lastAck, (short) 0, (short) 8) == 0) {
            return;
        }
        requirePending((byte) 2);
        clearInactive();
        JCSystem.beginTransaction();
        Util.arrayCopy(pendingOp, (short) 0, lastAck, (short) 0, (short) 8);
        phase = 0;
        pendingKind = 0;
        JCSystem.commitTransaction();
    }

    private void abort() {
        requirePending((byte) 1);
        if (preparedSlot != active) {
            clearInactive();
        }
        JCSystem.beginTransaction();
        phase = 0;
        pendingKind = 0;
        JCSystem.commitTransaction();
    }

    private void release() {
        if (expected() != 0
                || phase != 0
                || Util.arrayCompare(header, (short) 17, debitId, (short) 0, (short) 8) != 0) {
            ISOException.throwIt((short) 0x6985);
        }
        if (financialHold == 0) {
            return;
        }
        JCSystem.beginTransaction();
        financialHold = 0;
        JCSystem.commitTransaction();
    }

    private void blobHash(byte slot, byte[] dest, short at) {
        if (lengths[slot] == 0) {
            Util.arrayFillNonAtomic(dest, at, (short) 32, (byte) 0);
            return;
        }
        if (hashValid[slot] == 0) {
            cacheHash(slot);
        }
        Util.arrayCopyNonAtomic(slot == 0 ? hash0 : hash1, (short) 0, dest, at, (short) 32);
    }

    private void exportPart(APDU a, byte slot, short off, short len) {
        short total = (short) (77 + cipherLengths[slot]);
        if (lengths[slot] == 0 || off >= total || (short) (off + len) > total) {
            ISOException.throwIt(INVALID);
        }
        writeHeader(slot, scratch);
        for (short i = 0; i < len; i++) {
            short at = (short) (off + i);
            a.getBuffer()[i] = at < 77 ? scratch[at] : ct(slot)[(short) (at - 77)];
        }
        a.setOutgoingAndSend((short) 0, len);
    }

    private void buildState() {
        wipe(output);
        output[0] = 'T';
        output[1] = 'T';
        output[2] = 'S';
        output[3] = '4';
        Util.arrayCopyNonAtomic(identity, (short) 0, output, (short) 4, (short) 32);
        Util.arrayCopyNonAtomic(id, (short) 0, output, (short) 36, (short) 16);
        Util.arrayCopyNonAtomic(counter, (short) 0, output, (short) 52, (short) 4);
        Util.arrayCopyNonAtomic(pv(active), (short) 0, output, (short) 56, (short) 4);
        Util.arrayCopyNonAtomic(cv(active), (short) 0, output, (short) 60, (short) 4);
        Util.arrayCopyNonAtomic(walletVersion, (short) 0, output, (short) 64, (short) 4);
        Util.arrayCopyNonAtomic(balance, (short) 0, output, (short) 68, (short) 4);
        output[72] = phase;
        output[73] = pendingKind;
        Util.arrayCopyNonAtomic(pendingOp, (short) 0, output, (short) 74, (short) 8);
        Util.arrayCopyNonAtomic(pendingId, (short) 0, output, (short) 82, (short) 16);
        Util.arrayCopyNonAtomic(pv(preparedSlot), (short) 0, output, (short) 98, (short) 4);
        Util.arrayCopyNonAtomic(cv(preparedSlot), (short) 0, output, (short) 102, (short) 4);
        Util.arrayCopyNonAtomic(pendingWalletVersion, (short) 0, output, (short) 106, (short) 4);
        Util.arrayCopyNonAtomic(pendingBalance, (short) 0, output, (short) 110, (short) 4);
        blobHash(active, output, (short) 114);
        if (phase != 0) {
            blobHash(preparedSlot, output, (short) 146);
        }
        Util.arrayCopyNonAtomic(pendingHash, (short) 0, output, (short) 178, (short) 32);
        Util.arrayCopyNonAtomic(lastOp, (short) 0, output, (short) 210, (short) 8);
        output[218] = lastKind;
        Util.arrayCopyNonAtomic(lastHash, (short) 0, output, (short) 219, (short) 32);
        Util.arrayCopyNonAtomic(header, (short) 17, output, (short) 251, (short) 8);
        Util.arrayCopyNonAtomic(lastAck, (short) 0, output, (short) 259, (short) 8);
        output[267] = financialHold;
        Util.arrayCopyNonAtomic(debitId, (short) 0, output, (short) 268, (short) 8);
        Util.arrayCopyNonAtomic(debitBefore, (short) 0, output, (short) 276, (short) 4);
        Util.arrayCopyNonAtomic(debitAmount, (short) 0, output, (short) 280, (short) 4);
        Util.arrayCopyNonAtomic(debitWallet, (short) 0, output, (short) 284, (short) 4);
    }

    private short expected() {
        return Util.getShort(runtime, EXPECTED);
    }

    private short received() {
        return Util.getShort(runtime, RECEIVED);
    }

    private void endTransfer() {
        wipe(stage);
        wipe(header);
        wipe(tail);
        wipe(streamIv);
        Util.setShort(runtime, EXPECTED, (short) 0);
        Util.setShort(runtime, RECEIVED, (short) 0);
        Util.arrayFillNonAtomic(runtime, TRANSFER, (short) 8, (byte) 0);
    }

    private void beginTransfer(APDU a) {
        if (receive(a) != 63) {
            ISOException.throwIt(INVALID);
        }
        endTransfer();
        wipe(readState);
        Util.arrayCopyNonAtomic(input, (short) 0, header, (short) 0, (short) 63);
        short size = Util.getShort(header, (short) 29);
        if (size < 0 || size > 28280) {
            ISOException.throwIt(INVALID);
        }
        if (initialized != 0 && Util.arrayCompare(header, (short) 1, id, (short) 0, (short) 16) != 0) {
            ISOException.throwIt(DENIED);
        }
        if (header[0] == 1) {
            if (size < 17) {
                ISOException.throwIt(INVALID);
            }
            if (phase != 0) {
                if (Util.arrayCompare(header, (short) 17, pendingOp, (short) 0, (short) 8) != 0
                        || Util.arrayCompare(header, (short) 31, pendingHash, (short) 0, (short) 32) != 0) {
                    ISOException.throwIt((short) 0x6985);
                }
                runtime[DISCARD] = 1;
            } else if (compare(header, (short) 17, lastOp, (short) 0, (short) 8) <= 0) {
                ISOException.throwIt((short) 0x6985);
            }
        } else if (size > 1) {
            ISOException.throwIt(INVALID);
        }
        Util.setShort(runtime, EXPECTED, size);
        runtime[TRANSFER] = 1;
    }

    private short prefixSize() {
        if (header[0] != 1) {
            return expected();
        }
        byte kind = stage[0];
        if (kind == 1 || kind == 3 || kind == 8) {
            return 23;
        }
        if (kind == 2) {
            return 17;
        }
        return 29;
    }

    private void receiveChunk(APDU a) {
        if (runtime[TRANSFER] == 0) {
            ISOException.throwIt((short) 0x6985);
        }
        short count = receive(a), at = Util.getShort(input, (short) 0);
        short size = (short) (count - 2), before = received();
        if (count <= 2 || size > 180 || at != before || (short) (at + size) > expected()) {
            ISOException.throwIt(INVALID);
        }
        if (runtime[DISCARD] == 0) {
            for (short i = 0; i < size; i++) {
                short pos = (short) (at + i);
                if (pos < prefixSize()) {
                    stage[pos] = input[(short) (i + 2)];
                }
            }
            short prefix = prefixSize();
            if (header[0] == 1 && runtime[READY] == 0 && (short) (at + size) >= prefix) {
                startPrepare();
            }
            if (header[0] == 1 && runtime[READY] != 0 && (stage[0] == 1 || stage[0] == 2)) {
                short dataAt = at > prefix ? at : prefix;
                short amount = (short) (at + size - dataAt);
                if (amount > 0) {
                    appendProfile((short) (2 + dataAt - at), amount);
                }
            }
        }
        Util.setShort(runtime, RECEIVED, (short) (before + size));
    }

    private void startPrepare() {
        if (expected() < prefixSize()) {
            ISOException.throwIt(INVALID);
        }
        byte kind = stage[0];
        if (kind != 1 && kind != 2 && kind != 3 && kind != 6 && kind != 7 && kind != 8 && kind != 9) {
            ISOException.throwIt(INVALID);
        }
        if (financialHold != 0 && kind != 9) {
            ISOException.throwIt((short) 0x6985);
        }
        if (kind == 9) {
            if (initialized == 0 || financialHold != 1) {
                ISOException.throwIt(DENIED);
            }
        } else if (context[1] == 0) {
            ISOException.throwIt(DENIED);
        }
        if (kind == 1) {
            if (initialized != 0 || context[0] != 2) {
                ISOException.throwIt(DENIED);
            }
        } else if (kind != 9) {
            requireRole(kind == 6 || kind == 7 || kind == 8);
        }
        if ((kind == 2 || kind == 3) && context[0] != 2) {
            ISOException.throwIt(DENIED);
        }
        if (Util.arrayCompare(stage, (short) 1, balance, (short) 0, (short) 4) != 0) {
            ISOException.throwIt((short) 0x6985);
        }
        nextVersion((short) 5, pv(active), kind == 1 || kind == 2);
        nextVersion((short) 9, cv(active), kind == 1 || kind == 3 || kind == 8);
        nextVersion((short) 13, walletVersion, kind == 6 || kind == 7 || kind == 9);

        short length = 0;
        if (kind == 1 || kind == 2) {
            length = (short) (expected() - prefixSize());
            if (length <= 0 || length > 28000) {
                ISOException.throwIt(INVALID);
            }
        } else if (expected() != prefixSize()) {
            ISOException.throwIt(INVALID);
        }
        if (kind == 3 || kind == 8) {
            verifyProfile(active);
        }
        preparedSlot = active;
        pendingKind = kind;
        Util.arrayCopyNonAtomic(header, (short) 1, pendingId, (short) 0, (short) 16);
        Util.arrayCopyNonAtomic(balance, (short) 0, pendingBalance, (short) 0, (short) 4);
        Util.arrayCopyNonAtomic(walletVersion, (short) 0, pendingWalletVersion, (short) 0, (short) 4);
        if (kind == 1 || kind == 2 || kind == 3 || kind == 8) {
            byte next = (byte) (1 - active);
            preparedSlot = next;
            hashValid[next] = 0;
            Util.arrayCopyNonAtomic(stage, (short) 5, pv(next), (short) 0, (short) 4);
            Util.arrayCopyNonAtomic(stage, (short) 9, cv(next), (short) 0, (short) 4);
            if (kind == 2) {
                openPin(active);
                deriveData(next, work, (short) 0);
                sealPin(next, work, (short) 0);
            } else {
                checkPinFormat(stage, (short) 17);
                deriveData(next, stage, (short) 17);
                sealPin(next, stage, (short) 17);
            }
            if (kind == 3 || kind == 8) {
                length = lengths[active];
            }
            lengths[next] = length;
            cipherLengths[next] = (short) ((length / 16 + 1) * 16);
            random.generateData(iv(next), (short) 0, (short) 16);
            Util.arrayCopyNonAtomic(iv(next), (short) 0, streamIv, (short) 0, (short) 16);
        }
        runtime[READY] = 1;
    }

    private void appendProfile(short at, short count) {
        short saved = (short) (runtime[CARRY] & 255);
        Util.arrayCopyNonAtomic(tail, (short) 0, work, (short) 0, saved);
        Util.arrayCopyNonAtomic(input, at, work, saved, count);
        short total = (short) (saved + count), full = (short) (total - total % 16);
        if (full > 0) {
            encryptBlocks(full);
        }
        short left = (short) (total - full);
        wipe(tail);
        Util.arrayCopyNonAtomic(work, full, tail, (short) 0, left);
        runtime[CARRY] = (byte) left;
    }

    private void encryptBlocks(short count) {
        short at = Util.getShort(runtime, WRITTEN);
        if ((short) (at + count) > cipherLengths[preparedSlot]) {
            ISOException.throwIt(INVALID);
        }
        cipher.init(aes[preparedSlot], Cipher.MODE_ENCRYPT, streamIv, (short) 0, (short) 16);
        cipher.doFinal(work, (short) 0, count, ct(preparedSlot), at);
        Util.arrayCopyNonAtomic(ct(preparedSlot), (short) (at + count - 16), streamIv, (short) 0, (short) 16);
        Util.setShort(runtime, WRITTEN, (short) (at + count));
    }

    private void finishProfile() {
        short count = (short) (runtime[CARRY] & 255);
        Util.arrayCopyNonAtomic(tail, (short) 0, work, (short) 0, count);
        work[count] = (byte) 0x80;
        Util.arrayFillNonAtomic(work, (short) (count + 1), (short) (15 - count), (byte) 0);
        encryptBlocks((short) 16);
        if (Util.getShort(runtime, WRITTEN) != cipherLengths[preparedSlot]) {
            ISOException.throwIt(INVALID);
        }
        profileTag(preparedSlot, tag(preparedSlot));
        cacheHash(preparedSlot);
    }

    private void rekeyProfile() {
        byte next = preparedSlot;
        decipher.init(aes[active], Cipher.MODE_DECRYPT, iv(active), (short) 0, (short) 16);
        cipher.init(aes[next], Cipher.MODE_ENCRYPT, iv(next), (short) 0, (short) 16);
        short at = 0, total = cipherLengths[active];
        while (at < total) {
            short count = (short) (total - at);
            if (count > 256) {
                count = 256;
            }
            short made;
            if ((short) (at + count) == total) {
                made = decipher.doFinal(ct(active), at, count, work, (short) 0);
                made = cipher.doFinal(work, (short) 0, made, ct(next), at);
            } else {
                made = decipher.update(ct(active), at, count, work, (short) 0);
                if (made != count) {
                    ISOException.throwIt((short) 0x6984);
                }
                made = cipher.update(work, (short) 0, made, ct(next), at);
            }
            if (made != count) {
                ISOException.throwIt((short) 0x6984);
            }
            wipe(work);
            at += count;
        }
        profileTag(next, tag(next));
        cacheHash(next);
    }

    private void cacheHash(byte slot) {
        byte[] dest = slot == 0 ? hash0 : hash1;
        writeHeader(slot, scratch);
        digest.reset();
        digest.update(scratch, (short) 0, (short) 77);
        short at = 0, remaining = cipherLengths[slot];
        while (remaining > 256) {
            digest.update(ct(slot), at, (short) 256);
            at += 256;
            remaining -= 256;
        }
        digest.doFinal(ct(slot), at, remaining, dest, (short) 0);
        hashValid[slot] = 1;
    }

}
