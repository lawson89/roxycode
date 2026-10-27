package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class SplashScreenWindowTest {

    @Test
    void testSplashScreenWindowComponentsAndStatusUpdates() {
        Assumptions.assumeFalse(
                GraphicsEnvironment.isHeadless(), "Skipping JWindow test in headless environment");

        SplashScreenWindow splash =
                SwingTestUtils.callOnEdt(
                        () -> {
                            SettingsPanel.applyTheme("FlatLaf Dark");
                            return new SplashScreenWindow();
                        });
        try {
            assertNotNull(splash.getLogoImage());
            JProgressBar progressBar = splash.getProgressBar();
            assertTrue(progressBar.isIndeterminate());

            JLabel statusLabel = splash.getStatusLabel();
            assertEquals("Starting Spring context...", statusLabel.getText());

            splash.setStatus("Building workspace UI...");
            SwingTestUtils.flushEdt();
            assertEquals("Building workspace UI...", statusLabel.getText());

            SwingTestUtils.runOnEdt(
                    () -> {
                        splash.setStatus("Ready");
                        assertEquals("Ready", statusLabel.getText());
                        JPanel root = assertInstanceOf(JPanel.class, splash.getContentPane());
                        assertNotNull(root.getBorder());

                        boolean foundTitle = false;
                        boolean foundSubtitle = false;
                        for (Component comp : root.getComponents()) {
                            if (comp instanceof JLabel label) {
                                foundTitle |= "RoxyCode".equals(label.getText());
                                foundSubtitle |= "AI Coding Harness".equals(label.getText());
                            } else if (comp instanceof JPanel logoPanel) {
                                logoPanel.setSize(80, 80);
                                var g2 =
                                        new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB)
                                                .createGraphics();
                                try {
                                    assertDoesNotThrow(() -> logoPanel.paint(g2));
                                } finally {
                                    g2.dispose();
                                }
                            }
                        }
                        assertTrue(foundTitle);
                        assertTrue(foundSubtitle);
                    });
        } finally {
            SwingTestUtils.runOnEdt(
                    () -> {
                        splash.dispose();
                        assertFalse(splash.getProgressBar().isIndeterminate());
                        SettingsPanel.applyTheme("FlatLaf Light");
                    });
        }
    }
}
