package vn.edu.smartcard.shared;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class ProfileCodec {
  private ProfileCodec() {}

  public static byte[] encode(Profile p) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeByte(1);
      for (String s :
          new String[] {
            p.cardId(),
            p.studentCode(),
            p.fullName(),
            p.citizenId(),
            p.birthDate(),
            p.className(),
            p.phone(),
            p.email()
          }) {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(data.length);
        out.write(data);
      }
      out.writeShort(p.intakeYear());
      out.writeInt((int) p.version());
      byte[] photo = Base64.getDecoder().decode(p.photoBase64());
      out.writeShort(photo.length);
      out.write(photo);
      out.flush();
      if (bytes.size() > 28000)
        throw new IllegalArgumentException("Hồ sơ vượt dung lượng thẻ demo.");
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static Profile decode(byte[] bytes) {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
      if (in.readUnsignedByte() != 1) throw new IOException("Phiên bản hồ sơ không được hỗ trợ.");
      String[] s = new String[8];
      for (int i = 0; i < s.length; i++) {
        int n = in.readUnsignedShort();
        if (n > 1000 || n > in.available()) throw new IOException("Hồ sơ lỗi.");
        s[i] = new String(in.readNBytes(n), StandardCharsets.UTF_8);
      }
      int year = in.readUnsignedShort();
      long revision = Integer.toUnsignedLong(in.readInt());
      int n = in.readUnsignedShort();
      if (n > Profile.MAX_PHOTO || n != in.available())
        throw new IOException("Ảnh trong hồ sơ lỗi.");
      return new Profile(
          s[0],
          s[1],
          s[2],
          s[3],
          s[4],
          s[5],
          s[6],
          s[7],
          year,
          Base64.getEncoder().encodeToString(in.readNBytes(n)),
          revision);
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("Không đọc được hồ sơ trên thẻ.", e);
    }
  }
}
