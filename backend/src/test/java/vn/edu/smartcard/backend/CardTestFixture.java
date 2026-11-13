package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import vn.edu.smartcard.shared.*;

abstract class CardTestFixture {
  protected LocalConfig config;
  protected SecurityMaterial security;
  protected ReaderHub hub;
  protected String alias;
  protected long operation;
  protected final byte[] share = new byte[32];
  protected static final String TERMINAL = "26eb34d2-c6e7-4417-880e-34eb304491a3";

  @BeforeEach
  void setup() throws Exception {
    Path base = Path.of("target/test-work");
    Files.createDirectories(base);
    Path root = Files.createTempDirectory(base, "protocol-");
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    new SecureRandom().nextBytes(share);
    Path f = root.resolve("server.properties");
    Files.writeString(f, "master.key=" + Base64.getEncoder().encodeToString(key) + "\n");
    System.setProperty("ttm.config", f.toAbsolutePath().toString());
    config = new LocalConfig();
    security = new SecurityMaterial(config);
    hub = new ReaderHub(security, config);
    alias = hub.newCard();
    hub.connect(TERMINAL, alias);
  }

  protected <T> T use(Function<CardClient, T> action) {
    return hub.use(TERMINAL, action);
  }

  protected Profile profile() throws Exception {
    return new Profile(
            "TTM000001",
            "CT070352",
            "Trần Văn An",
            "012345678901",
            "03/02/2005",
            "CT7C",
            "0912345678",
            "student@example.invalid",
            2023,
            Base64.getEncoder()
                .encodeToString(
                    Portraits.jpeg(new BufferedImage(480, 640, BufferedImage.TYPE_INT_RGB), .7f)),
            1)
        .normalized();
  }

  protected Protocol.State state() {
    return use(CardClient::state);
  }

  protected void admin() {
    use(c -> {
      c.unlock("TTM000001", share, true);
      return null;
    });
  }

  protected Profile init() throws Exception {
    Profile p = profile();
    admin();
    mutate(
        Wire.INIT,
        Wire.concat("123456".getBytes(StandardCharsets.US_ASCII), ProfileCodec.encode(p)));
    return p;
  }

  protected void login(String pin) {
    use(c -> {
      c.unlock("TTM000001", share, false);
      if (!c.verifyPin(pin)) throw new Failure(401, "PIN_WRONG", "Wrong fixture PIN");
      return null;
    });
    hub.studentSession(TERMINAL);
  }

  protected long prepare(int kind, byte[] data) {
    var before = state();
    long id = ++operation;
    long p = kind == Wire.INIT ? 1 : before.profileVersion() + (kind == Wire.UPDATE ? 1 : 0),
        crypto =
            kind == Wire.INIT
                ? 1
                : before.cryptoVersion()
                    + ((kind == Wire.RESET_PIN || kind == Wire.CHANGE_PIN) ? 1 : 0),
        wallet =
            before.walletVersion()
                + ((kind == Wire.CREDIT || kind == Wire.DEBIT || kind == Wire.REVERSAL) ? 1 : 0);
    byte[] request = Protocol.request(kind, before.balance(), p, crypto, wallet, data);
    use(c -> {
      c.prepare("TTM000001", id, request, security.randomBytes(32), () -> {});
      return null;
    });
    return id;
  }

  protected void complete(int scope, long id) {
    use(c -> {
      c.complete(scope, "TTM000001", id);
      return null;
    });
  }

  protected void mutate(int kind, byte[] data) {
    long id = prepare(kind, data);
    complete(Protocol.COMMIT, id);
    complete(Protocol.ACK, id);
    if (kind == Wire.DEBIT) complete(Protocol.RELEASE, id);
  }

  protected static byte[] pin(String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }
}
