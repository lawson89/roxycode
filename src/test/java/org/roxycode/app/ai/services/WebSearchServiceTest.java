package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SettingsService;

class WebSearchServiceTest {

    private AiService aiService;
    private SettingsService settingsService;
    private AppSettings settings;
    private WebSearchService webSearchService;

    @BeforeEach
    void setUp() {
        aiService = mock(AiService.class);
        settingsService = mock(SettingsService.class);
        settings = new AppSettings();
        settings.setGoogleGroundingEnabled(true);
        when(settingsService.getSettings()).thenReturn(settings);
        webSearchService = new WebSearchService(aiService, settingsService);
    }

    @Test
    void testSearchValidatesSettingsAndInputAndDelegatesToAiService() {
        settings.setGoogleGroundingEnabled(false);
        EditorResult disabledResult = webSearchService.search("Java 21 features");
        assertFalse(disabledResult.success());
        assertTrue(disabledResult.errorHint().contains("Google Search Grounding is disabled"));
        verifyNoInteractions(aiService);

        settings.setGoogleGroundingEnabled(true);
        EditorResult blankResult = webSearchService.search("   ");
        assertFalse(blankResult.success());
        assertTrue(blankResult.errorHint().contains("must not be blank"));
        verifyNoInteractions(aiService);

        when(aiService.groundedSearch("Spring AI 2.0")).thenReturn("Spring AI 2.0 supports Gemini grounding.");
        EditorResult okResult = webSearchService.search("  Spring AI 2.0  ");
        assertTrue(okResult.success());
        assertEquals("Spring AI 2.0 supports Gemini grounding.", okResult.content());
        assertNull(okResult.errorHint());

        when(aiService.groundedSearch("empty query")).thenReturn("   ");
        EditorResult emptyResult = webSearchService.search("empty query");
        assertFalse(emptyResult.success());
        assertTrue(emptyResult.errorHint().contains("empty response"));

        when(aiService.groundedSearch("error query")).thenThrow(new IllegalStateException("Quota exceeded"));
        EditorResult errorResult = webSearchService.search("error query");
        assertFalse(errorResult.success());
        assertTrue(errorResult.errorHint().contains("Quota exceeded"));

        when(aiService.groundedSearch("cancel query")).thenThrow(new CancellationException("Stopped"));
        assertThrows(CancellationException.class, () -> webSearchService.search("cancel query"));
    }
}
