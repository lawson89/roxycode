package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import org.roxycode.app.service.SettingsService;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SidebarPanelTest {
    @TempDir
    Path tempDir;

    @Test
    void testSidebarPanelInstantiation() {
        SettingsService settingsService = new SettingsService();
        settingsService.setSettingsPathOverride(tempDir.resolve("settings.toml"));
        
        SidebarPanel sidebar = new SidebarPanel(settingsService, card -> {});
        assertNotNull(sidebar);
    }

    @Test
    void testSidebarStructure() {
        SettingsService settingsService = new SettingsService();
        settingsService.setSettingsPathOverride(tempDir.resolve("settings_structure.toml"));
        
        SidebarPanel sidebar = new SidebarPanel(settingsService, card -> {});
        
        Component[] components = sidebar.getComponents();
        
        // Expected: 1 UserProfile, 3 Headers, 9 NavButtons = 13
        assertEquals(13, components.length, "Sidebar should have exactly 13 components");

        int headerCount = 0;
        int buttonCount = 0;
        int profileCount = 0;

        for (Component c : components) {
            if (c instanceof JLabel) headerCount++;
            if (c instanceof JButton) buttonCount++;
            if (c instanceof UserProfileComponent) profileCount++;
        }

        assertEquals(1, profileCount, "Should have 1 user profile");
        assertEquals(3, headerCount, "Should have 3 headers (MAIN, CONTEXT, CONFIG)");
        assertEquals(9, buttonCount, "Should have 9 navigation buttons");
    }
}