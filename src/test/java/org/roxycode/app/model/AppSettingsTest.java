package org.roxycode.app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

public class AppSettingsTest {

    @Test
    void testDefaults() {
        AppSettings settings = new AppSettings();
        assertEquals("FlatLaf Light", settings.getTheme());
        assertEquals("gemini-3.5-flash", settings.getGeminiModel());
        assertFalse(settings.isLogToolCalls());
        assertTrue(settings.getGlobalContextFolders().isEmpty());
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

    @Test
    void testLogToolCalls() {
        AppSettings settings = new AppSettings();
        assertFalse(settings.isLogToolCalls());
        settings.setLogToolCalls(true);
        assertTrue(settings.isLogToolCalls());
    }

    @Test
    void testGlobalContextFolders() {
        AppSettings settings = new AppSettings();
        assertTrue(settings.getGlobalContextFolders().isEmpty());
        settings.setGlobalContextFolders(List.of("/global/docs", "/global/rules"));
        assertEquals(List.of("/global/docs", "/global/rules"), settings.getGlobalContextFolders());
        settings.setGlobalContextFolders(null);
        assertTrue(settings.getGlobalContextFolders().isEmpty());
    }

    @Test
    void testCopyCreatesIndependentDeepCopy() {
        AppSettings original = new AppSettings();
        original.setTheme("FlatLaf Dark");
        original.setGeminiApiKey("secret-key");
        original.setCurrentProjectPath("/workspace/proj");
        original.setGeminiModel("gemini-3.5-pro");
        original.setMaxAgentToolTurns(12);
        original.setMaxChatMemoryMessages(80);
        original.setLogToolCalls(true);
        original.setGlobalContextFolders(List.of("/context/one", "/context/two"));

        AppSettings copy = original.copy();
        assertEquals("FlatLaf Dark", copy.getTheme());
        assertEquals("secret-key", copy.getGeminiApiKey());
        assertEquals("/workspace/proj", copy.getCurrentProjectPath());
        assertEquals("gemini-3.5-pro", copy.getGeminiModel());
        assertEquals(12, copy.getMaxAgentToolTurns());
        assertEquals(80, copy.getMaxChatMemoryMessages());
        assertTrue(copy.isLogToolCalls());
        assertEquals(List.of("/context/one", "/context/two"), copy.getGlobalContextFolders());

        original.setTheme("FlatLaf Light");
        original.setLogToolCalls(false);
        original.setGlobalContextFolders(List.of("/context/mutated"));
        assertEquals("FlatLaf Dark", copy.getTheme());
        assertTrue(copy.isLogToolCalls());
        assertEquals(List.of("/context/one", "/context/two"), copy.getGlobalContextFolders());
    }
}
