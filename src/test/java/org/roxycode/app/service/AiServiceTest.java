package org.roxycode.app.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.AgentRole;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.model.AppSettings;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
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
    private PromptService promptService;

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

    @Mock
    private RepoMapPackerService repoMapPackerService;

    @Mock
    private GitService gitService;

    private AiService aiService;
    private AppSettings settings;

    @BeforeEach
    void setUp() {
        settings = new AppSettings();
        when(settingsService.getSettings()).thenReturn(settings);
        
        when(chatClientBuilder.defaultTools(any())).thenReturn(chatClientBuilder);
        when(chatClientBuilder.defaultAdvisors(any(org.springframework.ai.chat.client.advisor.api.Advisor[].class))).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        
        aiService = new AiService(chatClientBuilder, settingsService, promptService, jexlServiceRegistry, jexlTool, workflowService, chatMemory, eventPublisher, repoMapPackerService, gitService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testChatUsesConfiguredModelAndDynamicPrompt() {
        settings.setGeminiModel("test-model-123");
        when(jexlServiceRegistry.getDocumentation(any())).thenReturn("JEXL DOCS");
        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.DEVELOPMENT);
        when(promptService.loadCoreWorkflowPrompt()).thenReturn("CORE PROMPT");
        when(promptService.loadJexlContext()).thenReturn("JEXL CONTEXT");
        when(promptService.loadAllPrompts()).thenReturn(" ALL PROMPTS");
        when(promptService.loadAllDocs()).thenReturn(" ALL DOCS");
        
        when(repoMapPackerService.generateRepoMap()).thenReturn(new EditorResult(true, "REPO MAP CONTENT", null));
        when(gitService.getStatus()).thenReturn("GIT STATUS OUTPUT");

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("AI Response");
        
        

        String result = aiService.chat("Hello");

        assertEquals("AI Response", result);

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemPromptCaptor.capture());
        String capturedPrompt = systemPromptCaptor.getValue();
        
        assertTrue(capturedPrompt.contains("CORE PROMPT"));
        assertTrue(capturedPrompt.contains("ALL PROMPTS"));
        assertTrue(capturedPrompt.contains("ALL DOCS"));
        assertTrue(capturedPrompt.contains("CURRENT PHASE: DEVELOPMENT"));
        assertTrue(capturedPrompt.contains("Senior Developer"));
        assertTrue(capturedPrompt.contains("JEXL DOCS"));
        assertTrue(capturedPrompt.contains("JEXL CONTEXT"));
        assertTrue(capturedPrompt.contains("REPO MAP CONTENT"));
        assertTrue(capturedPrompt.contains("GIT STATUS OUTPUT"));
        
        verify(requestSpec).options(any());
    }

    @Test
    void testChatHandlesTurnLimitExceeded() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(promptService.loadCoreWorkflowPrompt()).thenReturn("CORE PROMPT");
        when(promptService.loadJexlContext()).thenReturn("JEXL CONTEXT");
        when(promptService.loadAllPrompts()).thenReturn(" ALL PROMPTS");
        when(promptService.loadAllDocs()).thenReturn(" ALL DOCS");
        
        when(repoMapPackerService.generateRepoMap()).thenReturn(new EditorResult(true, "REPO MAP CONTENT", null));
        when(gitService.getStatus()).thenReturn("GIT STATUS OUTPUT");

        // Mocking the call to throw the limit exception
        when(requestSpec.call()).thenThrow(new RuntimeException("MAX_TOOL_TURNS_EXCEEDED"));
        
        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.EXPLORE);
        
        
        settings.setMaxAgentToolTurns(3);
        
        String result = aiService.chat("Hello");
        
        assertTrue(result.contains("Maximum tool turns (3) exceeded"));
    }
}