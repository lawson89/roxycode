package org.roxycode.app.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.roxycode.app.model.AppSettings;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private SettingsService settingsService;

    private AiService aiService;
    private AppSettings settings;

    @BeforeEach
    void setUp() {
        settings = new AppSettings();
        when(settingsService.getSettings()).thenReturn(settings);
        aiService = new AiService(chatModel, settingsService);
    }

    @Test
    void testChatUsesConfiguredModel() {
        settings.setGeminiModel("test-model-123");
        
        ChatResponse mockResponse = mock(ChatResponse.class);
        Generation mockGeneration = mock(Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage mockMessage = mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        
        when(chatModel.call(any(Prompt.class))).thenReturn(mockResponse);
        when(mockResponse.getResult()).thenReturn(mockGeneration);
        when(mockGeneration.getOutput()).thenReturn(mockMessage);
        when(mockMessage.getText()).thenReturn("AI Response");

        String result = aiService.chat("Hello");

        assertEquals("AI Response", result);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        
        Prompt capturedPrompt = promptCaptor.getValue();
        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) capturedPrompt.getOptions();
        assertEquals("test-model-123", options.getModel());
    }
}