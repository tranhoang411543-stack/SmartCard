package vn.edu.smartcard.backend;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.parser.resources.ResourcePolicy;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.detector.Detector;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Keeps the desktop's PNG contract when VietQR returns its new SVG format. */
final class BankImage {
  private static final String PNG_DATA_URL = "data:image/png;base64,";

  private BankImage() {}

  static byte[] png(byte[] raw, String contentType) throws IOException {
    return encodePng(decode(raw, contentType));
  }

  static String qrOnly(String dataUrl) {
    try {
      if (!dataUrl.startsWith(PNG_DATA_URL)) throw new IOException("Invalid QR data URL");
      byte[] raw = Base64.getDecoder().decode(dataUrl.substring(PNG_DATA_URL.length()));
      byte[] cropped = encodePng(cropQr(decode(raw, "image/png")));
      return PNG_DATA_URL + Base64.getEncoder().encodeToString(cropped);
    } catch (IOException | ReaderException | IllegalArgumentException e) {
      throw new Failure(503, "BANK_UNAVAILABLE", "Chưa đọc được mã QR ngân hàng. Vui lòng thử lại.");
    }
  }

  private static BufferedImage cropQr(BufferedImage image) throws IOException, ReaderException {
    int width = image.getWidth(), height = image.getHeight();
    if (image.getColorModel().hasAlpha()) {
      BufferedImage opaque = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      Graphics2D graphics = opaque.createGraphics();
      try {
        graphics.setBackground(Color.WHITE);
        graphics.clearRect(0, 0, width, height);
        graphics.drawImage(image, 0, 0, null);
      } finally {
        graphics.dispose();
      }
      image = opaque;
    }
    var luminance = new RGBLuminanceSource(width, height,
        image.getRGB(0, 0, width, height, null, 0, width));
    var detected = new Detector(new HybridBinarizer(luminance).getBlackMatrix()).detect();
    var points = detected.getPoints();
    var bottomLeft = points[0];
    var topLeft = points[1];
    var topRight = points[2];
    int finderDistance = detected.getBits().getWidth() - 7;
    double moduleX = (topRight.getX() - topLeft.getX()) / finderDistance;
    double moduleY = (bottomLeft.getY() - topLeft.getY()) / finderDistance;
    if (moduleX <= 0 || moduleY <= 0) throw new IOException("Unexpected QR orientation");

    // The three finder centers are 3.5 modules in from the edges of an upright QR.
    int left = Math.max(0, (int) Math.floor(topLeft.getX() - 3.5 * moduleX));
    int top = Math.max(0, (int) Math.floor(topLeft.getY() - 3.5 * moduleY));
    int right = Math.min(width, (int) Math.ceil(topRight.getX() + 3.5 * moduleX));
    int bottom = Math.min(height, (int) Math.ceil(bottomLeft.getY() + 3.5 * moduleY));
    int padding = (int) Math.ceil(4 * Math.max(moduleX, moduleY));
    int qrWidth = right - left, qrHeight = bottom - top;
    BufferedImage cropped = new BufferedImage(qrWidth + 2 * padding, qrHeight + 2 * padding,
        BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = cropped.createGraphics();
    try {
      graphics.setBackground(Color.WHITE);
      graphics.clearRect(0, 0, cropped.getWidth(), cropped.getHeight());
      graphics.drawImage(image, padding, padding, padding + qrWidth, padding + qrHeight,
          left, top, right, bottom, null);
    } finally {
      graphics.dispose();
    }
    return cropped;
  }

  private static BufferedImage decode(byte[] raw, String contentType) throws IOException {
    if (raw.length < 8 || raw.length > 2_000_000) throw new IOException("QR image size");
    BufferedImage image;
    if (raw[0] == (byte) 0x89 && raw[1] == 'P' && raw[2] == 'N' && raw[3] == 'G') {
      try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) throw new IOException("Invalid QR PNG");
        var reader = readers.next();
        try {
          reader.setInput(input);
          dimensions(reader.getWidth(0), reader.getHeight(0));
          image = reader.read(0);
        } finally {
          reader.dispose();
        }
      }
    } else {
      String xml = new String(raw, StandardCharsets.UTF_8);
      String upper = xml.toUpperCase(Locale.ROOT);
      if (!contentType.toLowerCase(Locale.ROOT).contains("svg")
          || upper.contains("<!DOCTYPE")
          || upper.contains("<!ENTITY")) {
        throw new IOException("Unsupported QR image");
      }
      var context =
          LoaderContext.builder().externalResourcePolicy(ResourcePolicy.DENY_EXTERNAL).build();
      SVGDocument document = new SVGLoader().load(new ByteArrayInputStream(raw), null, context);
      if (document == null) throw new IOException("Invalid QR SVG");
      var size = document.size();
      int width = (int) Math.ceil(size.width), height = (int) Math.ceil(size.height);
      dimensions(width, height);
      image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      Graphics2D graphics = image.createGraphics();
      try {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        document.render(null, graphics);
      } finally {
        graphics.dispose();
      }
    }
    return image;
  }

  private static byte[] encodePng(BufferedImage image) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    if (!ImageIO.write(image, "png", output)) throw new IOException("No PNG encoder");
    return output.toByteArray();
  }

  private static void dimensions(int width, int height) throws IOException {
    if (width < 1 || height < 1 || width > 2048 || height > 2048) {
      throw new IOException("QR image dimensions");
    }
  }
}
