package org.roxycode.app.ai.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.PromptService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ExploreManagerTest {

    @Mock
    private SettingsService settingsService;

    @Mock
    private PromptService promptService;

    @Mock
    private AppSettings settings;

    private ExploreManager exploreManager;

    @BeforeEach
    void setUp() {
        when(settingsService.getSettings()).thenReturn(settings);
        when(settings.getMaxAgentToolTurns()).thenReturn(10);
        when(promptService.loadExplorePrompt()).thenReturn("""
                EXPLORE phase Read-Only Tool Efficiency & Batching %d Source Attribution
                """);
        exploreManager = new ExploreManager(settingsService, promptService);
    }

    @Test
    public void testGenerateSystemPrompt() {
        String prompt = exploreManager.generateSystemPrompt();
        
        assertNotNull(prompt);
        assertTrue(prompt.contains("Technical Mentor"));
        assertTrue(prompt.contains("EXPLORE phase"));
        assertTrue(prompt.contains("Read-Only"));
        assertTrue(prompt.contains("Source Attribution"));
        assertTrue(prompt.contains("Tool Efficiency & Batching"));
        assertTrue(prompt.contains("10"));
    }
}