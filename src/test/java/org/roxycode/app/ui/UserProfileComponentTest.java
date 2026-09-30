package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserProfileComponentTest {
    @Test
    void testUserProfileInstantiation() {
        UserProfileComponent profile = new UserProfileComponent("Test User", "test@example.com");
        assertNotNull(profile);
        assertFalse(profile.isOpaque());
    }

    @Test
    void testPaintComponentDoesNotThrow() {
        UserProfileComponent profile = new UserProfileComponent("Test User", "test@example.com");
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g2 = img.createGraphics();
        assertDoesNotThrow(() -> profile.paintAll(g2));
        g2.dispose();
    }
    @Test
    void testAvatarPreferredSize() {
        UserProfileComponent profile = new UserProfileComponent("Test User", "test@example.com");
        boolean foundAvatar = false;
        for (java.awt.Component comp : profile.getComponents()) {
            if (comp instanceof javax.swing.JPanel) {
                java.awt.Dimension pref = comp.getPreferredSize();
                if (pref.width == 128 && pref.height == 128) {
                    foundAvatar = true;
                    break;
                }
            }
        }
        assertTrue(foundAvatar, "Should find an avatar panel with 128x128 preferred size");
    }

}