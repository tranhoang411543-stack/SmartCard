package vn.edu.smartcard.shared;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Strict dish images; deliberately independent of portrait cropping/resizing. */
public final class MenuImages {
  public static final int MAX_BYTES = 1_048_576;

  private MenuImages() {}

  public static String read(File file) throws IOException {
    if (file.length() > MAX_BYTES) throw new IOException("Ảnh món tối đa 1 MiB.");
    return Base64.getEncoder()
        .encodeToString(validate(java.nio.file.Files.readAllBytes(file.toPath())));
  }

  public static byte[] decode(String encoded) {
    try {
      if (encoded == null || encoded.length() > (MAX_BYTES + 2) / 3 * 4)
        throw new IOException("Ảnh món tối đa 1 MiB.");
      return validate(Base64.getDecoder().decode(encoded));
    } catch (IOException | IllegalArgumentException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  public static byte[] validate(byte[] bytes) throws IOException {
    if (bytes.length == 0 || bytes.length > MAX_BYTES)
      throw new IOException("Ảnh món tối đa 1 MiB.");
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new IOException("Vui lòng chọn ảnh JPEG hoặc PNG.");
      var reader = readers.next();
      try {
        reader.setInput(input);
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (!Set.of("jpeg", "jpg", "png").contains(format)
            || reader.getWidth(0) != 256
            || reader.getHeight(0) != 256)
          throw new IOException("Chỉ chấp nhận ảnh JPEG/PNG kích thước đúng 256 × 256 pixel.");
        BufferedImage image = reader.read(0);
        if (image == null) throw new IOException("Không đọc được ảnh món.");
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
      } finally {
        reader.dispose();
      }
    }
  }
}
