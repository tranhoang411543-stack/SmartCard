package vn.edu.smartcard.shared;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import javax.imageio.*;
import javax.imageio.stream.ImageOutputStream;

public final class Portraits {
  private Portraits() {}

  public static String prepare(File file) throws IOException {
    if (file.length() > 10_000_000) throw new IOException("Ảnh tải lên tối đa 10 MB.");
    BufferedImage image;
    try (var input = ImageIO.createImageInputStream(file)) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new IOException("Vui lòng chọn ảnh JPEG hoặc PNG.");
      var reader = readers.next();
      try {
        reader.setInput(input);
        int w = reader.getWidth(0), h = reader.getHeight(0);
        if (w < 48 || h < 64 || w > 8000 || h > 8000 || (long) w * h > 30_000_000)
          throw new IOException("Kích thước ảnh không phù hợp.");
        image = reader.read(0);
      } finally {
        reader.dispose();
      }
    }
    BufferedImage result = new BufferedImage(480, 640, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = result.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, 480, 640);
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    double scale = Math.max(480.0 / image.getWidth(), 640.0 / image.getHeight());
    int w = (int) Math.round(image.getWidth() * scale),
        h = (int) Math.round(image.getHeight() * scale);
    g.drawImage(image, (480 - w) / 2, (640 - h) / 2, w, h, null);
    g.dispose();
    for (float quality : new float[] {.88f, .75f, .62f, .5f, .38f, .28f, .18f}) {
      byte[] bytes = jpeg(result, quality);
      if (bytes.length <= Profile.MAX_PHOTO) return Base64.getEncoder().encodeToString(bytes);
    }
    throw new IOException(
        "Ảnh còn quá nhiều chi tiết để lưu trên thẻ demo. Vui lòng chọn ảnh chân dung nền đơn"
            + " giản.");
  }

  public static byte[] jpeg(BufferedImage image, float quality) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
      writer.setOutput(stream);
      ImageWriteParam p = writer.getDefaultWriteParam();
      p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      p.setCompressionQuality(quality);
      writer.write(null, new IIOImage(image, null, null), p);
    } finally {
      writer.dispose();
    }
    return out.toByteArray();
  }
}
