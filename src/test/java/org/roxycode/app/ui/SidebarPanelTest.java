package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import org.roxycode.app.service.SettingsService;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

class SidebarPanelTest {
    @TempDir
    Path tempDir;

    @Test
    void testSidebarPanelInstantiation() {
        SettingsService settingsService = new SettingsService();
        settingsService.setSettingsPathOverride(tempDir.resolve("settings.toml"));
        
        SidebarPanel sidebar = new SidebarPanel(settingsService, card -> {});
        assertNotNull(sidebar);
        // Sidebar background is now theme-aware, no hardcoded check
    }
}