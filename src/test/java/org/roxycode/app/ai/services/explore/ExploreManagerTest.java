package org.roxycode.app.ai.services.explore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.SettingsService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ExploreManagerTest {

    @Mock
    private SettingsService settingsService;

    @Mock
    private AppSettings settings;

    private ExploreManager exploreManager;

    @BeforeEach
    void setUp() {
        when(settingsService.getSettings()).thenReturn(settings);
        when(settings.getMaxAgentToolTurns()).thenReturn(10);
        exploreManager = new ExploreManager(settingsService);
    }

    @Test
    public void testGenerateSystemPrompt() {
        String prompt = exploreManager.generateSystemPrompt();
        
        assertNotNull(prompt);
        assertTrue(prompt.contains("Technical Mentor"));
        assertTrue(prompt.contains("EXPLORE phase"));
        assertTrue(prompt.contains("Read-Only"));
        assertTrue(prompt.contains("Source Attribution"));
        assertTrue(prompt.contains("10"));
    }
}