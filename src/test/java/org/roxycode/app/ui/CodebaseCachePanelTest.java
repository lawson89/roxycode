package org.roxycode.app.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.services.cache.GeminiCacheService;
import org.roxycode.app.ai.services.cache.ProjectCacheMetaService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.service.ProjectAnalysisService;
import org.roxycode.app.ai.WorkflowPhase;


import org.roxycode.app.service.SettingsService;

import javax.swing.*;
import java.awt.*;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodebaseCachePanelTest {

    @Mock private WorkflowService workflowService;
    @Mock private JexlServiceRegistry jexlServiceRegistry;
    @Mock private GitService gitService;
    @Mock private ProjectAnalysisService projectAnalysisService;

    @Mock private ProjectService projectService;
    @Mock private SettingsService settingsService;
    private org.roxycode.app.service.PromptService promptService;
    @Mock private RepoMapPackerService packerService;
    @Mock private ProjectCacheMetaService metaService;
    @Mock private GeminiCacheService geminiCacheService;

    private CodebaseCachePanel panel;

        @BeforeEach
    void setUp() {
        when(metaService.loadMeta()).thenReturn(Optional.empty());
        when(metaService.loadRepoCache()).thenReturn(Optional.empty());
        
        
        panel = new CodebaseCachePanel(projectService, settingsService, packerService, metaService, geminiCacheService, promptService, workflowService, jexlServiceRegistry, gitService, projectAnalysisService);
    }

    @Test
    void testButtonsExist() {
        JButton packButton = findButton(panel, "Update Implicit Cache");
        assertNotNull(packButton, "Update button should exist");
    }
    
    @Test
    void testRepoMapAreaExists() {
        JTextArea textArea = findComponent(panel, JTextArea.class);
        assertNotNull(textArea, "JTextArea should exist for repo map content");
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
    
    private <T> T findComponent(Container container, Class<T> clazz) {
        for (Component comp : container.getComponents()) {
            if (clazz.isInstance(comp)) {
                return clazz.cast(comp);
            }
            if (comp instanceof Container) {
                T res = findComponent((Container) comp, clazz);
                if (res != null) {
                    return res;
                }
            }
        }
        return null;
    }
}
