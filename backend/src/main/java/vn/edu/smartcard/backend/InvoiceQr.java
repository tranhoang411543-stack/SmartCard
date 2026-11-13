package vn.edu.smartcard.backend;

import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;

/** Local invoice QR; independent of the external bank top-up QR. */
final class InvoiceQr {
  static String payload(long id, long amount, long expires) {
    return "ttm://invoice/" + id + "?amount=" + amount + "&expires=" + expires;
  }

  static String image(long id, long amount, long expires) {
    try {
      var bits =
          new QRCodeWriter()
              .encode(
                  payload(id, amount, expires),
                  BarcodeFormat.QR_CODE,
                  360,
                  360,
                  Map.of(EncodeHintType.MARGIN, 4));
      BufferedImage image =
          new BufferedImage(bits.getWidth(), bits.getHeight(), BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < bits.getHeight(); y++)
        for (int x = 0; x < bits.getWidth(); x++) image.setRGB(x, y, bits.get(x, y) ? 0 : 0xffffff);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    } catch (Exception e) {
      throw new IllegalStateException("Không tạo được QR hóa đơn.", e);
    }
  }
}
