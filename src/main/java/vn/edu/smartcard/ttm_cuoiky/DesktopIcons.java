package vn.edu.smartcard.ttm_cuoiky;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.parser.resources.ResourcePolicy;
import java.awt.*;
import java.net.URL;
import javax.swing.Icon;
import javax.swing.JComponent;

final class DesktopIcons {
  private DesktopIcons() {}

  static Icon svg(String path, int size, String description) {
    URL resource = DesktopIcons.class.getResource(path);
    if (resource == null) {
      throw new IllegalStateException("Không tìm thấy " + description + ".");
    }
    LoaderContext context =
        LoaderContext.builder().externalResourcePolicy(ResourcePolicy.DENY_EXTERNAL).build();
    SVGDocument illustration = new SVGLoader().load(resource, context);
    if (illustration == null) {
      throw new IllegalStateException("Không đọc được " + description + ".");
    }
    return new Icon() {
      @Override
      public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D canvas = (Graphics2D) graphics.create();
        try {
          canvas.setRenderingHint(
              RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
          canvas.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
          canvas.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
          canvas.setRenderingHint(
              RenderingHints.KEY_ALPHA_INTERPOLATION,
              RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
          canvas.translate(x, y);
          canvas.scale(size / illustration.size().getWidth(), size / illustration.size().getHeight());
          illustration.render(component instanceof JComponent swing ? swing : null, canvas);
        } finally {
          canvas.dispose();
        }
      }

      @Override
      public int getIconWidth() {
        return size;
      }

      @Override
      public int getIconHeight() {
        return size;
      }
    };
  }
}
