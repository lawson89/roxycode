package org.roxycode.app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class AppSettingsTest {

    @Test
    void testDefaults() {
        AppSettings settings = new AppSettings();
        assertEquals("FlatLaf Light", settings.getTheme());
        assertEquals("gemini-3.5-flash", settings.getGeminiModel());
    }

    @Test
    void testMaxAgentToolTurns() {
        AppSettings settings = new AppSettings();
        assertEquals(5, settings.getMaxAgentToolTurns());
        settings.setMaxAgentToolTurns(10);
        assertEquals(10, settings.getMaxAgentToolTurns());
    }

    @Test
    void testMaxChatMemoryMessages() {
        AppSettings settings = new AppSettings();
        assertEquals(50, settings.getMaxChatMemoryMessages());
        settings.setMaxChatMemoryMessages(100);
        assertEquals(100, settings.getMaxChatMemoryMessages());
    }
}
