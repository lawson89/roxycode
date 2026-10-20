package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class SplashScreenWindowTest {

    @Test
    void testSplashScreenWindowComponentsAndStatusUpdates() throws Exception {
        Assumptions.assumeFalse(
                GraphicsEnvironment.isHeadless(), "Skipping JWindow test in headless environment");

        final SplashScreenWindow[] windowHolder = new SplashScreenWindow[1];
        SwingUtilities.invokeAndWait(
                () -> {
                    SettingsPanel.applyTheme("FlatLaf Dark");
                    windowHolder[0] = new SplashScreenWindow();
                });

        SplashScreenWindow splash = windowHolder[0];
        try {
            assertNotNull(splash);
            assertNotNull(splash.getLogoImage(), "Logo image should be loaded from classpath");

            JProgressBar progressBar = splash.getProgressBar();
            assertNotNull(progressBar);
            assertTrue(progressBar.isIndeterminate(), "Progress bar should be indeterminate");

            JLabel statusLabel = splash.getStatusLabel();
            assertNotNull(statusLabel);
            assertEquals("Starting Spring context...", statusLabel.getText());

            // Test thread-safe status update from off-EDT
            splash.setStatus("Building workspace UI...");
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals("Building workspace UI...", statusLabel.getText());

            // Test status update directly on EDT
            SwingUtilities.invokeAndWait(
                    () -> {
                        splash.setStatus("Ready");
                        assertEquals("Ready", statusLabel.getText());

                        // Verify root panel structure and painting
                        assertInstanceOf(JPanel.class, splash.getContentPane());
                        JPanel root = (JPanel) splash.getContentPane();
                        assertNotNull(root.getBorder());

                        boolean foundTitle = false;
                        boolean foundSubtitle = false;
                        for (Component comp : root.getComponents()) {
                            if (comp instanceof JLabel label) {
                                if ("RoxyCode".equals(label.getText())) {
                                    foundTitle = true;
                                } else if ("AI Coding Harness".equals(label.getText())) {
                                    foundSubtitle = true;
                                }
                            } else if (comp instanceof JPanel logoPanel) {
                                logoPanel.setSize(80, 80);
                                BufferedImage img =
                                        new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB);
                                var g2 = img.createGraphics();
                                try {
                                    assertDoesNotThrow(() -> logoPanel.paint(g2));
                                } finally {
                                    g2.dispose();
                                }
                            }
                        }
                        assertTrue(foundTitle, "Title label 'RoxyCode' should be present");
                        assertTrue(
                                foundSubtitle,
                                "Subtitle label 'AI Coding Harness' should be present");
                    });
        } finally {
            SwingUtilities.invokeAndWait(splash::dispose);
        }
    }
}
