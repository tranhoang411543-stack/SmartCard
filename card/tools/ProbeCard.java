import java.util.*;
import java.nio.ByteBuffer;
import javax.smartcardio.*;

/** PC/SC diagnostic client; never installs/deletes applets or exports card keys. */
public class ProbeCard {
  private static final byte[] PROBE_AID={(byte)0xf0,0x54,0x54,0x4d,0x01,0x7e};
  private static byte[] exchange(Card card, CommandAPDU command) throws Exception {
    ResponseAPDU reply=card.getBasicChannel().transmit(command);
    if(reply.getSW()!=0x9000) throw new IllegalStateException(String.format("APDU INS=%02X SW=%04X. Install/select the separate resource probe instance first.",command.getINS(),reply.getSW()));
    return reply.getData();
  }
  private static String quote(String value) {
    return "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
  }
  private static int u16(byte[] bytes,int at) {return Short.toUnsignedInt(ByteBuffer.wrap(bytes).getShort(at));}
  public static void main(String[] args) throws Exception {
    List<CardTerminal> terminals=TerminalFactory.getDefault().terminals().list();
    if(args.length==0 || args[0].equals("--list")) {
      for(CardTerminal terminal:terminals) System.out.println(terminal.getName());
      return;
    }
    CardTerminal terminal=terminals.stream().filter(t -> t.getName().equals(args[0])).findFirst().orElseThrow(() -> new IllegalArgumentException("Reader name not found; use --list."));
    if(!terminal.isCardPresent()) throw new IllegalStateException("No card is present in the selected reader.");
    Card card=terminal.connect("*");
    try {
      exchange(card,new CommandAPDU(0,0xa4,4,0,PROBE_AID));
      byte[] before=exchange(card,new CommandAPDU(0x80,0x10,0,0,256));
      if(before.length!=20 || before[0]!='T' || before[1]!='T' || before[2]!='P' || before[3]!=1) throw new IllegalStateException("Unexpected probe format.");
      byte[] check=exchange(card,new CommandAPDU(0x80,0x20,0,0,256));
      if(!Arrays.equals(check,new byte[]{1})) throw new IllegalStateException("Crypto self-test failed.");
      byte[] after=exchange(card,new CommandAPDU(0x80,0x10,0,0,256));
      boolean virtual=terminal.getName().toLowerCase(Locale.ROOT).contains("virtual");
      System.out.println("{");
      System.out.println("  \"reader\": "+quote(terminal.getName())+",");
      System.out.println("  \"environment\": "+quote(virtual?"virtual PC/SC":"PC/SC; physical model requires independent confirmation")+",");
      System.out.println("  \"protocol\": "+quote(card.getProtocol())+",");
      System.out.println("  \"apiVersionHex\": "+quote(String.format("%04X",u16(before,18)))+",");
      System.out.println("  \"baselineTransientBytes\": "+u16(before,4)+",");
      System.out.println("  \"availableTransientBeforeSelfTest\": "+u16(before,6)+",");
      System.out.println("  \"availableTransientAfterSelfTest\": "+u16(after,6)+",");
      System.out.println("  \"availableTransientResetBytes\": "+u16(after,8)+",");
      System.out.println("  \"maxCommitCapacityBytes\": "+u16(after,10)+",");
      System.out.println("  \"availablePersistentBytes\": "+Integer.toUnsignedLong(ByteBuffer.wrap(after).getInt(12))+",");
      System.out.println("  \"probeArenaBytes\": "+u16(after,16)+",");
      System.out.println("  \"cryptoSelfTest\": true,");
      System.out.println("  \"physicalAcceptanceComplete\": false");
      System.out.println("}");
    } finally {card.disconnect(false);}
  }
}
