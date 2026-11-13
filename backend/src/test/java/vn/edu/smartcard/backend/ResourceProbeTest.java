package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;
import com.licel.jcardsim.smartcardio.CardSimulator;
import javax.smartcardio.CommandAPDU;
import javacard.framework.AID;
import org.junit.jupiter.api.Test;
import vn.edu.smartcard.card.ResourceProbeApplet;

class ResourceProbeTest {
  @Test void probeHasSeparateAidAndExecutesCryptoSelfTestWithoutExportingKeys() {
    var simulator=new CardSimulator(); byte[] id={(byte)0xf0,0x54,0x54,0x4d,0x01,0x7e};
    var aid=new AID(id,(short)0,(byte)id.length); simulator.installApplet(aid,ResourceProbeApplet.class);
    simulator.selectApplet(aid);
    var info=simulator.transmitCommand(new CommandAPDU(0x80,0x10,0,0,256));
    assertEquals(0x9000,info.getSW()); assertEquals(20,info.getData().length);
    assertEquals(1536,java.nio.ByteBuffer.wrap(info.getData()).getShort(16));
    for(int i=0;i<3;i++) {
      var check=simulator.transmitCommand(new CommandAPDU(0x80,0x20,0,0,256));
      assertEquals(0x9000,check.getSW()); assertArrayEquals(new byte[]{1},check.getData());
      simulator.reset(); simulator.selectApplet(aid);
    }
    assertEquals(0x6d00,simulator.transmitCommand(new CommandAPDU(0x80,0x12,0,0,256)).getSW());
  }
}
