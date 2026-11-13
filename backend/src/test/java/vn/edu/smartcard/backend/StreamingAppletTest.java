package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.smartcard.shared.*;

class StreamingAppletTest extends CardTestFixture {
  private byte[] raw(int size) {
    byte[] value = new byte[size];
    new Random(7816 + size).nextBytes(value);
    return value;
  }
  private byte[] readRaw() {
    int length = use(c -> c.status("").profileLength());
    byte[] all = new byte[length];
    for (int at=0; at<length;) {
      final int offset=at;
      byte[] part=use(c -> c.command(Wire.READ_PROFILE, offset>>8, offset&255, new byte[0]));
      assertTrue(part.length>0 && part.length<=180);
      System.arraycopy(part,0,all,at,part.length); at+=part.length;
    }
    return all;
  }
  private void initRaw(byte[] data) {
    admin(); mutate(Wire.INIT, Wire.concat(pin("123456"), data));
  }
  private byte[] updateRequest(byte[] data) {
    var s=state();
    return Protocol.request(Wire.UPDATE,s.balance(),s.profileVersion()+1,s.cryptoVersion(),s.walletVersion(),data);
  }
  private void begin(long id, byte[] data, byte[] token) {
    use(c -> c.command(Protocol.BEGIN,0,0,Protocol.header(Protocol.PREPARE,"TTM000001",id,0,0,data.length,token)));
  }
  private void piece(byte[] request,int at,int size) {
    use(c -> c.command(Protocol.CHUNK,0,0,ByteBuffer.allocate(size+2).putShort((short)at).put(request,at,size).array()));
  }
  @ParameterizedTest @ValueSource(ints={1,15,16,17,175,176,179,180,181,255,256,257,27999,28000})
  void boundaryProfilesRoundTripAndRekeyWithoutWholeRecordRam(int size) throws Exception {
    byte[] bytes=raw(size); initRaw(bytes);
    assertArrayEquals(bytes,readRaw());
    login("123456"); mutate(Wire.CHANGE_PIN,pin("654321"));
    assertArrayEquals(bytes,readRaw());
    hub.eject(TERMINAL); hub=new ReaderHub(security,config); hub.connect(TERMINAL,alias);
    login("654321"); assertArrayEquals(bytes,readRaw());
    admin(); mutate(Wire.RESET_PIN,pin("112233"));
    login("112233"); assertArrayEquals(bytes,readRaw());
  }
  @Test void maximumPhotoStillUsesExistingProfileCodecAndApplicationInterface() throws Exception {
    var p=profile(); byte[] image=Arrays.copyOf(Base64.getDecoder().decode(p.photoBase64()),Profile.MAX_PHOTO);
    var large=new Profile(p.cardId(),p.studentCode(),p.fullName(),p.citizenId(),p.birthDate(),p.className(),p.phone(),p.email(),p.intakeYear(),Base64.getEncoder().encodeToString(image),p.version()).normalized();
    admin(); mutate(Wire.INIT,Wire.concat(pin("123456"),ProfileCodec.encode(large)));
    assertEquals(large,use(CardClient::profile));
    login("123456"); mutate(Wire.CHANGE_PIN,pin("654321"));
    assertEquals(large,use(CardClient::profile));
  }
  @Test void prefixAndAesBlocksCanCrossOneByteChunks() {
    byte[] bytes=raw(181); initRaw(raw(16)); admin();
    byte[] request=updateRequest(bytes); long op=++operation;
    begin(op,request,security.randomBytes(32));
    for(int at=0;at<request.length;at++) piece(request,at,1);
    use(c -> c.command(Protocol.EXECUTE));
    complete(Protocol.COMMIT,op); complete(Protocol.ACK,op);
    assertArrayEquals(bytes,readRaw());
  }
  @Test void partialWriteAfterResetKeepsOldRecordAndAllowsNewUpdate() {
    byte[] old=raw(28000); initRaw(old); admin();
    byte[] request=updateRequest(raw(28000)); begin(++operation,request,security.randomBytes(32));
    piece(request,0,180); piece(request,180,180);
    hub.eject(TERMINAL); hub=new ReaderHub(security,config); hub.connect(TERMINAL,alias);
    assertEquals(0,state().phase()); admin(); assertArrayEquals(old,readRaw());
    byte[] replacement=raw(257); mutate(Wire.UPDATE,replacement); assertArrayEquals(replacement,readRaw());
  }
  @Test void missingAndDuplicateChunksCannotPublishPartialData() {
    byte[] old=raw(256); initRaw(old); admin();
    byte[] request=updateRequest(raw(1000)); begin(++operation,request,security.randomBytes(32)); piece(request,0,180);
    assertThrows(Failure.class,() -> use(c -> c.command(Protocol.EXECUTE)));
    assertEquals(0,state().phase()); assertArrayEquals(old,readRaw());
    begin(++operation,request,security.randomBytes(32)); piece(request,0,180);
    assertThrows(Failure.class,() -> piece(request,0,180));
    assertArrayEquals(old,readRaw());
  }
  @Test void preparedReplayCannotOverwriteCandidateAndWrongTokenIsRejected() {
    initRaw(raw(256)); admin(); byte[] target=raw(28000), request=updateRequest(target), token=security.randomBytes(32);
    long op=++operation; use(c -> {c.prepare("TTM000001",op,request,token,()->{}); return null;});
    byte[] hash=state().nextCipherHash(); byte[] different=request.clone(); different[100]^=1;
    use(c -> {c.prepare("TTM000001",op,different,token,()->{}); return null;});
    assertArrayEquals(hash,state().nextCipherHash());
    assertThrows(Failure.class,()->begin(op,request,security.randomBytes(32)));
    assertArrayEquals(hash,state().nextCipherHash());
    complete(Protocol.COMMIT,op); complete(Protocol.ACK,op); assertArrayEquals(target,readRaw());
  }
  @Test void oversizeRecordIsRejectedBeforeActiveChanges() {
    byte[] old=raw(256); initRaw(old); admin();
    assertThrows(Failure.class,()->prepare(Wire.UPDATE,raw(28001)));
    assertArrayEquals(old,readRaw()); assertEquals(0,state().phase());
  }
  @Test void nonSequentialUnalignedReadsReturnTheRequestedBytes() {
    byte[] data=raw(1000); initRaw(data);
    for(int at:new int[]{17,3,181,999,0,255}) {
      byte[] part=use(c -> c.command(Wire.READ_PROFILE,at>>8,at&255,new byte[0]));
      assertArrayEquals(Arrays.copyOfRange(data,at,Math.min(data.length,at+180)),part);
    }
  }
  @Test void walletOperationsNeverRewriteTheInactiveCiphertextSlot() throws Exception {
    initRaw(raw(28000)); login("123456");
    Object applet=applet(); var field=applet.getClass().getDeclaredField("ciphertext0"); field.setAccessible(true);
    byte[] inactive=(byte[])field.get(applet); inactive[100]=99;
    mutate(Wire.CREDIT,Wire.operation(1,1000)); mutate(Wire.DEBIT,Wire.operation(2,100));
    assertEquals(99,inactive[100]); assertEquals(900,use(CardClient::balance));
  }
  @Test void transientPayloadStaysWithinBudgetAndCountersAreTransient() throws Exception {
    Object a=applet(); int total=0;
    for(var f:a.getClass().getDeclaredFields()) {
      if(java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
      f.setAccessible(true); Object value=f.get(a);
      if(value instanceof byte[] bytes && javacard.framework.JCSystem.isTransient(bytes)!=0) total+=bytes.length;
      assertFalse(Set.of("expected","received","outputLength").contains(f.getName()));
    }
    assertEquals(1249,total); assertTrue(total<=1536);
  }
  private Object applet() throws Exception {
    var f=ReaderHub.class.getDeclaredField("cards"); f.setAccessible(true);
    Object sim=((Map<?,?>)f.get(hub)).get(alias); var rf=sim.getClass().getDeclaredField("runtime"); rf.setAccessible(true);
    var runtime=rf.get(sim);
    for(Class<?> c=runtime.getClass();c!=null;c=c.getSuperclass()) {
      try {var m=c.getDeclaredMethod("getApplet",javacard.framework.AID.class);m.setAccessible(true);return m.invoke(runtime,new javacard.framework.AID(Wire.AID,(short)0,(byte)Wire.AID.length));}
      catch(NoSuchMethodException ignored) {}
    }
    throw new AssertionError("Applet missing");
  }
}
