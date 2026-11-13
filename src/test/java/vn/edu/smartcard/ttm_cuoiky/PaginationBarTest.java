package vn.edu.smartcard.ttm_cuoiky;

import static org.junit.jupiter.api.Assertions.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class PaginationBarTest {
  @Test
  void keepsTheVisibleWindowUntilTheCurrentPageLeavesIt() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      TTM_CuoiKy.configureLookAndFeel();
      PaginationBar bar = new PaginationBar(ignored -> {});
      check(bar, 1, "< 1 2 3 ... 10 >");
      check(bar, 2, "< 1 2 3 ... 10 >");
      check(bar, 3, "< 1 2 3 ... 10 >");
      check(bar, 4, "< 2 3 4 ... 10 >");
      check(bar, 3, "< 2 3 4 ... 10 >");
      check(bar, 2, "< 2 3 4 ... 10 >");
      check(bar, 1, "< 1 2 3 ... 10 >");
      check(bar, 4, "< 2 3 4 ... 10 >");
      check(bar, 5, "< 3 4 5 ... 10 >");
      check(bar, 6, "< 4 5 6 ... 10 >");
      check(bar, 7, "< 5 6 7 ... 10 >");
      check(bar, 8, "< 6 7 8 9 10 >");
      check(bar, 9, "< 6 7 8 9 10 >");
      check(bar, 10, "< 6 7 8 9 10 >");
      check(bar, 9, "< 6 7 8 9 10 >");
      check(bar, 6, "< 6 7 8 9 10 >");
      check(bar, 5, "< 5 6 7 ... 10 >");
    });
  }

  @Test
  void clickingNumbersAndArrowsUsesTheCorrectPageAndDisablesGaps() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      TTM_CuoiKy.configureLookAndFeel();
      List<Integer> selected = new ArrayList<>();
      var reference = new AtomicReference<PaginationBar>();
      PaginationBar bar = new PaginationBar(index -> {
        selected.add(index);
        reference.get().setPage(index, 200, 20, false);
      });
      reference.set(bar);
      bar.setPage(0, 200, 20, false);
      assertEquals("", find(bar, "<").getText());
      assertEquals("", find(bar, ">").getText());
      assertEquals(12, find(bar, "<").getIcon().getIconWidth());
      assertEquals(12, find(bar, "<").getIcon().getIconHeight());
      assertEquals(12, find(bar, ">").getIcon().getIconWidth());
      assertEquals(12, find(bar, ">").getIcon().getIconHeight());
      assertNotNull(find(bar, "<").getDisabledIcon());
      assertFalse(find(bar, "<").isEnabled());
      assertFalse(find(bar, "...").isEnabled());
      find(bar, "3").doClick(0);
      assertEquals(List.of(2), selected);
      assertEquals("Trang 3, hiện tại", find(bar, "3").getAccessibleContext().getAccessibleName());
      find(bar, ">").doClick(0);
      assertEquals(List.of(2, 3), selected);
      assertEquals("< 2 3 4 ... 10 >", labels(bar));
      find(bar, "10").doClick(0);
      assertEquals(List.of(2, 3, 9), selected);
      assertFalse(find(bar, ">").isEnabled());
      assertEquals("< 6 7 8 9 10 >", labels(bar));
      bar.setPage(9, 200, 20, true);
      assertTrue(buttons(bar).stream().noneMatch(JButton::isEnabled));
    });
  }

  @Test
  void handlesEmptyAndSmallListsWithoutInventingPages() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      PaginationBar bar = new PaginationBar(ignored -> {});
      bar.setPage(0, 0, 20, false);
      assertEquals("< >", labels(bar));
      assertTrue(buttons(bar).stream().noneMatch(JButton::isEnabled));
      assertLayout(bar);
      bar.setPage(0, 1, 20, false);
      assertEquals("< 1 >", labels(bar));
      assertLayout(bar);
      bar.setPage(4, 100, 20, false);
      assertEquals("< 1 2 3 4 5 >", labels(bar));
      assertLayout(bar);
      bar.setPage(0, 101, 20, false);
      assertEquals("< 1 2 3 ... 6 >", labels(bar));
      bar.setPage(3, 101, 20, false);
      assertEquals("< 2 3 4 5 6 >", labels(bar));
      bar.setPage(0, 21, 20, false);
      assertEquals("< 1 2 >", labels(bar));
    });
  }

  private static void check(PaginationBar bar, int page, String expected) {
    bar.setPage(page - 1, 200, 20, false);
    assertEquals(expected, labels(bar));
    JButton current = find(bar, Integer.toString(page));
    assertEquals(new Color(0x606060), current.getBackground());
    assertEquals(Color.WHITE, current.getForeground());
    assertEquals(Font.BOLD, current.getFont().getStyle());
    assertEquals(new Dimension(40, 40), current.getPreferredSize());
    assertEquals("arc: 8", current.getClientProperty(FlatClientProperties.STYLE));
  }

  private static void assertLayout(PaginationBar bar) {
    bar.setSize(bar.getPreferredSize());
    bar.doLayout();
    List<JButton> visible = buttons(bar);
    JButton last = visible.get(visible.size() - 1);
    assertEquals(bar.getWidth(), last.getX() + last.getWidth());
    for (int i = 1; i < visible.size(); i++) {
      JButton before = visible.get(i - 1);
      assertEquals(4, visible.get(i).getX() - before.getX() - before.getWidth());
    }
  }

  private static List<JButton> buttons(PaginationBar bar) {
    return Arrays.stream(bar.getComponents()).filter(Component::isVisible)
        .map(JButton.class::cast).toList();
  }

  private static JButton find(PaginationBar bar, String text) {
    return buttons(bar).stream().filter(button -> label(button).equals(text)).findFirst().orElseThrow();
  }

  private static String labels(PaginationBar bar) {
    return String.join(" ", buttons(bar).stream().map(PaginationBarTest::label).toList());
  }

  private static String label(JButton button) {
    String name = button.getAccessibleContext().getAccessibleName();
    if ("Trang trước".equals(name)) return "<";
    if ("Trang sau".equals(name)) return ">";
    return button.getText();
  }
}
