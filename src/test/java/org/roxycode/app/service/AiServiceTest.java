package org.roxycode.app.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.model.AppSettings;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    @Mock
    private SettingsService settingsService;

    @Mock
    private JexlServiceRegistry jexlServiceRegistry;

    @Mock
    private JexlTool jexlTool;

    private AiService aiService;
    private AppSettings settings;

    @BeforeEach
    void setUp() {
        settings = new AppSettings();
        when(settingsService.getSettings()).thenReturn(settings);
        
        when(chatClientBuilder.defaultSystem(anyString())).thenReturn(chatClientBuilder);
        when(chatClientBuilder.defaultTools(any())).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        
        aiService = new AiService(chatClientBuilder, settingsService, jexlServiceRegistry, jexlTool);
    }

    @Test
    void testChatUsesConfiguredModel() {
        settings.setGeminiModel("test-model-123");
        when(jexlServiceRegistry.getDocumentation()).thenReturn("JEXL DOCS");
        
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("AI Response");

        String result = aiService.chat("Hello");

        assertEquals("AI Response", result);

        // Note: The service now passes the builder to .options()
        ArgumentCaptor<GoogleGenAiChatOptions.Builder> builderCaptor = ArgumentCaptor.forClass(GoogleGenAiChatOptions.Builder.class);
        verify(requestSpec).options(builderCaptor.capture());
        
        GoogleGenAiChatOptions.Builder capturedBuilder = builderCaptor.getValue();
        assertEquals("test-model-123", capturedBuilder.build().getModel());
    }
}