package org.roxycode.app.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.workflow.WorkflowPhase;
import org.roxycode.app.ai.workflow.WorkflowService;
import org.roxycode.app.model.AppSettings;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Mock
    private WorkflowService workflowService;

    @Mock
    private ChatMemory chatMemory;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AiService aiService;
    private AppSettings settings;

    @BeforeEach
    void setUp() {
        settings = new AppSettings();
        when(settingsService.getSettings()).thenReturn(settings);
        
        when(chatClientBuilder.defaultTools(any())).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        
        aiService = new AiService(chatClientBuilder, settingsService, jexlServiceRegistry, jexlTool, workflowService, chatMemory, eventPublisher);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testChatUsesConfiguredModelAndDynamicPrompt() {
        settings.setGeminiModel("test-model-123");
        when(jexlServiceRegistry.getDocumentation()).thenReturn("JEXL DOCS");
        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.DEVELOPMENT);
        
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("AI Response");
        
        when(chatMemory.get(anyString())).thenReturn(new java.util.ArrayList<>());

        String result = aiService.chat("Hello");

        assertEquals("AI Response", result);

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemPromptCaptor.capture());
        String capturedPrompt = systemPromptCaptor.getValue();
        
        assertTrue(capturedPrompt.contains("CURRENT PHASE: DEVELOPMENT"));
        assertTrue(capturedPrompt.contains("Senior Developer"));
        assertTrue(capturedPrompt.contains("JEXL DOCS"));
        
        verify(requestSpec).options(any());
    }

    @Test
    void testChatHandlesTurnLimitExceeded() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        
        // Mocking the call to throw the limit exception
        when(requestSpec.call()).thenThrow(new RuntimeException("MAX_TOOL_TURNS_EXCEEDED"));
        
        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.EXPLORE);
        when(chatMemory.get(anyString())).thenReturn(new java.util.ArrayList<>());
        
        settings.setMaxAgentToolTurns(3);
        
        String result = aiService.chat("Hello");
        
        assertTrue(result.contains("Maximum tool turns (3) exceeded"));
    }
}
