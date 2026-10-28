package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
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
        assertTrue(FlatLaf.isLafDark()
                ? style.contains("background: $Component.accentColor")
                : style.contains("background: #0d6efd") && style.contains("foreground: #ffffff"));

        btn.setSelected(false);
        assertFalse(btn.isSelected());
        assertFalse(btn.isContentAreaFilled());
        assertEquals("", btn.getClientProperty(FlatClientProperties.STYLE));
    }

    @Test
    void testArcProperty() {
        assertEquals(8, new NavButton("Test", null).getClientProperty("JButton.arc"));
    }

    @Test
    void testFontIconColorSync() {
        FontIcon icon = FontIcon.of(Codicons.COMMENT_DISCUSSION, 16);
        NavButton btn = new NavButton("Test", icon);
        for (Color color : new Color[]{Color.RED, Color.BLUE}) {
            btn.setForeground(color);
            btn.setSelected(false);
            assertEquals(color, icon.getIconColor());
        }
    }

    @Test
    void testBusyAndWaitingApprovalStatusIconsTooltipsTimerAndPainting() {
        NavButton btn = new NavButton("Feature Request", FontIcon.of(Codicons.GIT_PULL_REQUEST, 16));
        btn.setSize(195, 32);

        assertFalse(btn.isBusy());
        assertFalse(btn.isWaitingApproval());
        assertNull(btn.getStatusIcon());
        assertNull(btn.getToolTipText());
        assertEquals(12, btn.getBorder().getBorderInsets(btn).right);
        int basePrefWidth = btn.getPreferredSize().width;

        btn.setBusy(true);
        assertTrue(btn.isBusy());
        assertEquals(FontAwesomeSolid.HOURGLASS_HALF, btn.getStatusIcon().getIkon());
        assertEquals(13, btn.getStatusIcon().getIconSize());
        assertEquals(NavButton.BUSY_TOOLTIP, btn.getToolTipText());
        assertTrue(btn.getBusyTimer().isRunning());
        assertEquals(500, btn.getBusyTimer().getDelay());
        assertEquals(12 + btn.getStatusIcon().getIconWidth() + 8, btn.getBorder().getBorderInsets(btn).right);
        assertTrue(btn.getPreferredSize().width > basePrefWidth && btn.getPreferredSize().width <= 195);

        for (FontAwesomeSolid expected : new FontAwesomeSolid[]{FontAwesomeSolid.HOURGLASS_END,
                FontAwesomeSolid.HOURGLASS_START, FontAwesomeSolid.HOURGLASS_HALF}) {
            btn.advanceBusyFrame();
            assertEquals(expected, btn.getStatusIcon().getIkon());
        }

        btn.setWaitingApproval(true);
        assertTrue(btn.isWaitingApproval());
        assertEquals(FontAwesomeSolid.HOURGLASS_HALF, btn.getStatusIcon().getIkon());
        assertEquals(NavButton.BUSY_TOOLTIP, btn.getToolTipText());

        Graphics2D g2 = new BufferedImage(195, 32, BufferedImage.TYPE_INT_ARGB).createGraphics();
        assertDoesNotThrow(() -> btn.paintComponent(g2));

        btn.setBusy(false);
        assertFalse(btn.isBusy());
        assertFalse(btn.getBusyTimer().isRunning());
        assertTrue(btn.isWaitingApproval());
        assertEquals(Codicons.BELL_DOT, btn.getStatusIcon().getIkon());
        assertEquals(14, btn.getStatusIcon().getIconSize());
        assertEquals(NavButton.WAITING_APPROVAL_TOOLTIP, btn.getToolTipText());

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

            btn.setSelected(true);
            btn.setForeground(Color.WHITE);
            assertDoesNotThrow(() -> btn.paintComponent(g2));
            assertEquals(Color.WHITE, btn.getStatusIcon().getIconColor());
        } finally {
            if (wasDark)
                FlatDarkLaf.setup();
            else
                FlatLightLaf.setup();
            g2.dispose();
        }

        btn.setWaitingApproval(false);
        assertFalse(btn.isWaitingApproval());
        assertNull(btn.getStatusIcon());
        assertNull(btn.getToolTipText());
        assertEquals(12, btn.getBorder().getBorderInsets(btn).right);
        assertEquals(basePrefWidth, btn.getPreferredSize().width);

        btn.setBusy(true);
        assertTrue(btn.getBusyTimer().isRunning());
        btn.removeNotify();
        assertFalse(btn.getBusyTimer().isRunning());
    }

    @Test
    void testHourglassAnimatorStandaloneLifecycleAndFrameCycling() {
        AtomicInteger frameCallbacks = new AtomicInteger(0);
        HourglassAnimator animator = new HourglassAnimator(frameCallbacks::incrementAndGet);

        assertFalse(animator.isRunning());
        assertNull(animator.getIcon());
        animator.advanceFrame();
        assertEquals(0, frameCallbacks.get());

        FontIcon icon = animator.start();
        assertSame(icon, animator.getIcon());
        assertEquals(HourglassAnimator.DEFAULT_ICON_SIZE, icon.getIconSize());
        assertEquals(FontAwesomeSolid.HOURGLASS_HALF, icon.getIkon());
        assertTrue(animator.isRunning());
        assertEquals(HourglassAnimator.ANIMATION_DELAY_MS, animator.getTimer().getDelay());

        FontAwesomeSolid[] expectedFrames = {FontAwesomeSolid.HOURGLASS_END, FontAwesomeSolid.HOURGLASS_START,
                FontAwesomeSolid.HOURGLASS_HALF};
        for (int i = 0; i < expectedFrames.length; i++) {
            animator.advanceFrame();
            assertEquals(expectedFrames[i], icon.getIkon());
            assertEquals(i + 1, frameCallbacks.get());
        }

        animator.stop();
        assertFalse(animator.isRunning());
        animator.advanceFrame();
        assertEquals(FontAwesomeSolid.HOURGLASS_END, icon.getIkon());
        assertSame(icon, animator.start());
        assertEquals(FontAwesomeSolid.HOURGLASS_HALF, icon.getIkon());
        assertTrue(animator.isRunning());
        animator.stop();
    }
}
