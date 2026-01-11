package org.roxycode.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roxycode.core.config.GeminiModel;
import org.roxycode.core.config.GeminiModelRegistry;
import org.roxycode.core.tools.service.SkillService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlashCommandServiceTest {

    private GenAIService genAIService;
    private SettingsService settingsService;
    private GeminiModelRegistry modelRegistry;
    private SlashCommandService slashCommandService;
    private RoxyProjectService roxyProjectService;
    private SkillService skillService;

    @BeforeEach

    void setUp() {
        genAIService = mock(GenAIService.class);
        settingsService = mock(SettingsService.class);
        modelRegistry = mock(GeminiModelRegistry.class);
        roxyProjectService = mock(RoxyProjectService.class);
        skillService = mock(SkillService.class);

        slashCommandService = new SlashCommandService(genAIService, settingsService, modelRegistry, roxyProjectService, skillService);
    }
    void testIsCommand() {
        assertTrue(slashCommandService.isCommand("/help"));
        assertTrue(slashCommandService.isCommand(" /clear"));
        assertFalse(slashCommandService.isCommand("hello /help"));
        assertFalse(slashCommandService.isCommand("help"));
    }

    @Test
    void testExecuteHelp() {
        SlashCommandService.CommandResult result = slashCommandService.execute("/help");
        assertTrue(result.success());
        assertTrue(result.message().contains("Available commands"));
    }

    @Test
    void testExecuteClear() {
        SlashCommandService.CommandResult result = slashCommandService.execute("/clear");
        assertTrue(result.success());
        assertEquals(SlashCommandService.CommandAction.CLEAR, result.action());
    }

    @Test
    void testExecuteReset() {
        SlashCommandService.CommandResult result = slashCommandService.execute("/reset");
        assertTrue(result.success());
        verify(genAIService).clearHistory();
    }

    @Test
    void testExecuteModelList() {
        GeminiModel m1 = new GeminiModel();
        m1.setApiName("gemini-1.5-flash");
        when(modelRegistry.getAllModels()).thenReturn(List.of(m1));

        SlashCommandService.CommandResult result = slashCommandService.execute("/model");
        assertTrue(result.success());
        assertTrue(result.message().contains("gemini-1.5-flash"));
    }

    @Test
    void testExecuteModelSwitchSuccess() {
        GeminiModel m1 = new GeminiModel();
        m1.setApiName("gemini-1.5-pro");
        when(modelRegistry.getModelByName("gemini-1.5-pro")).thenReturn(Optional.of(m1));

        SlashCommandService.CommandResult result = slashCommandService.execute("/model gemini-1.5-pro");
        assertTrue(result.success());
        assertEquals(SlashCommandService.CommandAction.UPDATE_STATS, result.action());
        verify(settingsService).setGeminiModel("gemini-1.5-pro");
    }

    @Test
    void testExecuteModelSwitchFailure() {
        when(modelRegistry.getModelByName("unknown")).thenReturn(Optional.empty());

        SlashCommandService.CommandResult result = slashCommandService.execute("/model unknown");
        assertFalse(result.success());
        assertTrue(result.message().contains("Model not found"));
    }

    @Test
    void testUnknownCommand() {
        SlashCommandService.CommandResult result = slashCommandService.execute("/unknown");
        assertFalse(result.success());
        assertTrue(result.message().contains("Unknown command"));
    }


    @Test
    void testExecuteCode() {
        SlashCommandService.CommandResult result = slashCommandService.execute("/code");
        assertTrue(result.success());
        verify(roxyProjectService).setCurrentMode(RoxyMode.CODE);
        assertEquals(SlashCommandService.CommandAction.UPDATE_STATS, result.action());
    }
}
