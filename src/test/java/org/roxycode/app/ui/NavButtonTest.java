package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.*;
import java.awt.image.BufferedImage;
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
        assertEquals(8, btn.getClientProperty("JButton.arc"));
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
    void testBusyAndWaitingApprovalStatusIconsTooltipsTimerAndPainting() throws Exception {
        FontIcon leadIcon = FontIcon.of(Codicons.GIT_PULL_REQUEST, 16);
        NavButton btn = new NavButton("Feature Request", leadIcon);
        btn.setSize(195, 32);

        assertFalse(btn.isBusy());
        assertFalse(btn.isWaitingApproval());
        assertNull(btn.getStatusIcon());
        assertNull(btn.getToolTipText());
        assertEquals(12, btn.getBorder().getBorderInsets(btn).right);
        int basePrefWidth = btn.getPreferredSize().width;

        // Busy initializes HOURGLASS_HALF, sets busy tooltip, starts timer, and reserves right
        // space
        btn.setBusy(true);
        assertTrue(btn.isBusy());
        assertNotNull(btn.getStatusIcon());
        assertEquals(FontAwesomeSolid.HOURGLASS_HALF, btn.getStatusIcon().getIkon());
        assertEquals(13, btn.getStatusIcon().getIconSize());
        assertEquals(NavButton.BUSY_TOOLTIP, btn.getToolTipText());
        assertNotNull(btn.getBusyTimer());
        assertTrue(btn.getBusyTimer().isRunning());
        assertEquals(500, btn.getBusyTimer().getDelay());
        int expectedBusyRightInset = 12 + btn.getStatusIcon().getIconWidth() + 8;
        assertEquals(expectedBusyRightInset, btn.getBorder().getBorderInsets(btn).right);
        assertTrue(
                btn.getPreferredSize().width > basePrefWidth,
                "Preferred width should increase when status icon is present");
        assertTrue(
                btn.getPreferredSize().width <= 195,
                "Feature Request button with status icon should fit within 195px sidebar button width");

        // Verify animation frame cycling: HOURGLASS_HALF -> HOURGLASS_END -> HOURGLASS_START ->
        // HOURGLASS_HALF
        btn.advanceBusyFrame();
        assertEquals(FontAwesomeSolid.HOURGLASS_END, btn.getStatusIcon().getIkon());
        btn.advanceBusyFrame();
        assertEquals(FontAwesomeSolid.HOURGLASS_START, btn.getStatusIcon().getIkon());
        btn.advanceBusyFrame();
        assertEquals(FontAwesomeSolid.HOURGLASS_HALF, btn.getStatusIcon().getIkon());

        // Busy takes precedence over waitingApproval
        btn.setWaitingApproval(true);
        assertTrue(btn.isWaitingApproval());
        assertEquals(FontAwesomeSolid.HOURGLASS_HALF, btn.getStatusIcon().getIkon());
        assertEquals(NavButton.BUSY_TOOLTIP, btn.getToolTipText());

        BufferedImage img = new BufferedImage(195, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        assertDoesNotThrow(() -> btn.paintComponent(g2));

        // When busy clears while waitingApproval is true, stops timer and shows BELL_DOT with
        // approval tooltip
        btn.setBusy(false);
        assertFalse(btn.isBusy());
        assertFalse(btn.getBusyTimer().isRunning());
        assertTrue(btn.isWaitingApproval());
        assertNotNull(btn.getStatusIcon());
        assertEquals(Codicons.BELL_DOT, btn.getStatusIcon().getIkon());
        assertEquals(14, btn.getStatusIcon().getIconSize());
        assertEquals(NavButton.WAITING_APPROVAL_TOOLTIP, btn.getToolTipText());
        int expectedApprovalRightInset = 12 + btn.getStatusIcon().getIconWidth() + 8;
        assertEquals(expectedApprovalRightInset, btn.getBorder().getBorderInsets(btn).right);
        assertTrue(btn.getPreferredSize().width > basePrefWidth);
        assertTrue(btn.getPreferredSize().width <= 195);

        // Unselected waitingApproval paints amber badge with white icon in both light and dark
        // themes
        boolean wasDark = FlatLaf.isLafDark();
        try {
            FlatLightLaf.setup();
            btn.setSelected(false);
            assertDoesNotThrow(() -> btn.paintComponent(g2));
            assertEquals(Color.WHITE, btn.getStatusIcon().getIconColor());

            FlatDarkLaf.setup();
            btn.setSelected(false);
            assertDoesNotThrow(() -> btn.paintComponent(g2));
            assertEquals(Color.WHITE, btn.getStatusIcon().getIconColor());

            // Selected state renders statusIcon in foreground color
            btn.setSelected(true);
            btn.setForeground(Color.WHITE);
            assertDoesNotThrow(() -> btn.paintComponent(g2));
            assertEquals(Color.WHITE, btn.getStatusIcon().getIconColor());
        } finally {
            if (wasDark) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
        }
        g2.dispose();

        // Clearing waitingApproval removes trailing icon, clears tooltip, and resets right inset
        btn.setWaitingApproval(false);
        assertFalse(btn.isWaitingApproval());
        assertNull(btn.getStatusIcon());
        assertNull(btn.getToolTipText());
        assertEquals(12, btn.getBorder().getBorderInsets(btn).right);
        assertEquals(basePrefWidth, btn.getPreferredSize().width);

        // Verify removeNotify() stops busy animation timer cleanly
        btn.setBusy(true);
        assertTrue(btn.getBusyTimer().isRunning());
        btn.removeNotify();
        assertFalse(btn.getBusyTimer().isRunning());
    }
}
