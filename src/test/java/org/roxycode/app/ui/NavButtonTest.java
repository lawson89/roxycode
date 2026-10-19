package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import java.awt.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

class NavButtonTest {
  @Test
  void testSelectionStyling() {
    NavButton btn = new NavButton("Test", null);

    assertFalse(btn.isSelected());
    assertFalse(btn.isContentAreaFilled());

    btn.setSelected(true);
    assertTrue(btn.isSelected());
    assertTrue(btn.isContentAreaFilled());
    String style = (String) btn.getClientProperty(FlatClientProperties.STYLE);
    assertNotNull(style);

    if (FlatLaf.isLafDark()) {
      assertTrue(style.contains("background: $Component.accentColor"));
    } else {
      assertTrue(style.contains("background: #0d6efd"));
      assertTrue(style.contains("foreground: #ffffff"));
    }

    btn.setSelected(false);
    assertFalse(btn.isSelected());
    assertFalse(btn.isContentAreaFilled());
    assertEquals("", btn.getClientProperty(FlatClientProperties.STYLE));
  }

  @Test
  void testArcProperty() {
    NavButton btn = new NavButton("Test", null);
    assertEquals(6, btn.getClientProperty("JButton.arc"));
  }

  @Test
  void testFontIconColorSync() {
    FontIcon icon = FontIcon.of(Codicons.COMMENT_DISCUSSION, 16);
    NavButton btn = new NavButton("Test", icon);

    btn.setForeground(Color.RED);
    btn.setSelected(false);
    assertEquals(Color.RED, icon.getIconColor());

    btn.setForeground(Color.BLUE);
    btn.setSelected(false);
    assertEquals(Color.BLUE, icon.getIconColor());
  }

  @Test
  void testBusyAndWaitingApprovalStatusIconsAndPainting() {
    NavButton btn = new NavButton("Feature Request", null);
    btn.setSize(160, 32);

    assertFalse(btn.isBusy());
    assertFalse(btn.isWaitingApproval());
    assertNull(btn.getStatusIcon());

    // Busy shows hourglass
    btn.setBusy(true);
    assertTrue(btn.isBusy());
    assertNotNull(btn.getStatusIcon());
    assertEquals(FontAwesomeSolid.HOURGLASS_HALF, btn.getStatusIcon().getIkon());
    assertEquals(13, btn.getStatusIcon().getIconSize());

    // Busy takes precedence over waitingApproval
    btn.setWaitingApproval(true);
    assertTrue(btn.isWaitingApproval());
    assertEquals(FontAwesomeSolid.HOURGLASS_HALF, btn.getStatusIcon().getIkon());

    java.awt.image.BufferedImage img =
        new java.awt.image.BufferedImage(160, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2 = img.createGraphics();
    assertDoesNotThrow(() -> btn.paintComponent(g2));

    // When busy clears while waitingApproval is true, shows question mark
    btn.setBusy(false);
    assertFalse(btn.isBusy());
    assertTrue(btn.isWaitingApproval());
    assertNotNull(btn.getStatusIcon());
    assertEquals(Codicons.QUESTION, btn.getStatusIcon().getIkon());
    assertEquals(14, btn.getStatusIcon().getIconSize());

    btn.setSelected(true);
    assertDoesNotThrow(() -> btn.paintComponent(g2));
    g2.dispose();

    // Clearing waitingApproval removes trailing icon
    btn.setWaitingApproval(false);
    assertFalse(btn.isWaitingApproval());
    assertNull(btn.getStatusIcon());
  }
}
