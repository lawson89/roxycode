package org.roxycode.app.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.services.cache.GeminiCacheService;
import org.roxycode.app.ai.services.cache.ProjectCacheMetaService;
import org.roxycode.app.ai.services.cache.ProjectPackerService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;

import javax.swing.*;
import java.awt.*;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodebaseCachePanelTest {

    @Mock private ProjectService projectService;
    @Mock private SettingsService settingsService;
    @Mock private ProjectPackerService packerService;
    @Mock private ProjectCacheMetaService metaService;
    @Mock private GeminiCacheService geminiCacheService;

    private CodebaseCachePanel panel;

    @BeforeEach
    void setUp() {
        when(metaService.loadMeta()).thenReturn(Optional.empty());
        when(metaService.loadRepoCache()).thenReturn(Optional.empty());
        
        panel = new CodebaseCachePanel(projectService, settingsService, packerService, metaService, geminiCacheService);
    }

    @Test
    void testButtonsExist() {
        JButton packButton = findButton(panel, "Pack Local Codebase");
        JButton uploadButton = findButton(panel, "Upload to Gemini");

        assertNotNull(packButton, "Pack button should exist");
        assertNotNull(uploadButton, "Upload button should exist");
    }

    private JButton findButton(Container container, String text) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JButton && text.equals(((JButton) comp).getText())) {
                return (JButton) comp;
            }
            if (comp instanceof Container) {
                JButton res = findButton((Container) comp, text);
                if (res != null) {
                    return res;
                }
            }
        }
        return null;
    }
}