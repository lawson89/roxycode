package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.model.AppSettings;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class AiServiceTest {

  @Mock private ChatClient.Builder chatClientBuilder;

  @Mock private ChatClient chatClient;

  @Mock private ChatClient.ChatClientRequestSpec requestSpec;

  @Mock private ChatClient.CallResponseSpec responseSpec;

  @Mock private SettingsService settingsService;

  @Mock private PromptService promptService;

  @Mock private JexlServiceRegistry jexlServiceRegistry;

  @Mock private WorkflowService workflowService;

  @Mock private ChatMemory chatMemory;

  @Mock private ApplicationEventPublisher eventPublisher;

  @Mock private RepoMapPackerService repoMapPackerService;

  @Mock private GitService gitService;

  private AiService aiService;
  private AppSettings settings;

  @BeforeEach
  void setUp() {
    settings = new AppSettings();
    when(settingsService.getSettings()).thenReturn(settings);

    when(chatClientBuilder.defaultAdvisors(
            any(org.springframework.ai.chat.client.advisor.api.Advisor[].class)))
        .thenReturn(chatClientBuilder);
    when(chatClientBuilder.build()).thenReturn(chatClient);

    ProjectAnalysisService projectAnalysisService = mock(ProjectAnalysisService.class);
    when(projectAnalysisService.getDominantLanguage()).thenReturn("Java");
    aiService =
        new AiService(
            chatClientBuilder,
            settingsService,
            promptService,
            jexlServiceRegistry,
            workflowService,
            chatMemory,
            eventPublisher,
            repoMapPackerService,
            gitService,
            projectAnalysisService);
  }

  @Test
  @SuppressWarnings("unchecked")
  void testChatUsesConfiguredModelAndDynamicPrompt() {
    settings.setGeminiModel("test-model-123");
    when(jexlServiceRegistry.getDocumentation(any())).thenReturn("JEXL DOCS");
    when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
    when(promptService.loadCoreWorkflowPrompt()).thenReturn("CORE PROMPT");
    when(promptService.loadJexlContext()).thenReturn("JEXL CONTEXT");
    when(promptService.loadAllPrompts()).thenReturn(" ALL PROMPTS");
    when(promptService.loadAllDocs()).thenReturn(" ALL DOCS");
    when(promptService.loadCodePrompt()).thenReturn(" CODE PHASE PROMPT");
    when(promptService.loadProjectContext())
        .thenReturn("\n\n## Project Context: rules.md\nCUSTOM RULES");

    when(repoMapPackerService.generateRepoMap())
        .thenReturn(new EditorResult(true, "REPO MAP CONTENT", null));
    when(gitService.getStatus()).thenReturn("GIT STATUS OUTPUT");

    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(anyString())).thenReturn(requestSpec);
    when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.tools(any())).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);

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
    assertTrue(capturedPrompt.contains("CUSTOM RULES"));
    assertTrue(capturedPrompt.contains("CURRENT PHASE: CODE"));
    assertTrue(capturedPrompt.contains("Senior Developer"));
    assertTrue(capturedPrompt.contains("JEXL DOCS"));
    assertTrue(capturedPrompt.contains("JEXL CONTEXT"));
    assertTrue(capturedPrompt.contains("REPO MAP CONTENT"));
    assertTrue(capturedPrompt.contains("GIT STATUS OUTPUT"));
    assertTrue(capturedPrompt.contains("CODE PHASE PROMPT"));

    verify(requestSpec).options(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testChatWithConversationIdAndPhase() {
    settings.setGeminiModel("test-model-123");
    when(jexlServiceRegistry.getDocumentation(any())).thenReturn("JEXL DOCS");
    when(promptService.loadCoreWorkflowPrompt()).thenReturn("CORE");
    when(promptService.loadJexlContext()).thenReturn("JEXL");
    when(promptService.loadAllPrompts()).thenReturn(" ALL");
    when(promptService.loadAllDocs()).thenReturn(" DOCS");
    when(promptService.loadProjectContext()).thenReturn("");
    when(promptService.loadReviewPrompt()).thenReturn("REVIEW PHASE PROMPT");

    when(repoMapPackerService.generateRepoMap()).thenReturn(new EditorResult(true, "", null));
    when(gitService.getStatus()).thenReturn("");

    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(anyString())).thenReturn(requestSpec);
    when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.tools(any())).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);

    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(responseSpec);
    when(responseSpec.content()).thenReturn("AI Response");

    String result = aiService.chat("custom-conv", WorkflowPhase.REVIEW, "Hello", null, null);

    assertEquals("AI Response", result);

    ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
    verify(requestSpec).system(systemPromptCaptor.capture());
    String capturedPrompt = systemPromptCaptor.getValue();

    assertTrue(capturedPrompt.contains("CURRENT PHASE: REVIEW"));
    assertTrue(capturedPrompt.contains("REVIEW PHASE PROMPT"));

    // Verify conversation ID set in advisor
    verify(requestSpec).advisors(any(Consumer.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void testChatHandlesTurnLimitExceeded() {
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(anyString())).thenReturn(requestSpec);
    when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.tools(any())).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);

    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(promptService.loadCoreWorkflowPrompt()).thenReturn("CORE PROMPT");
    when(promptService.loadJexlContext()).thenReturn("JEXL CONTEXT");
    when(promptService.loadAllPrompts()).thenReturn(" ALL PROMPTS");
    when(promptService.loadAllDocs()).thenReturn(" ALL DOCS");
    when(promptService.loadProjectContext()).thenReturn("");

    when(repoMapPackerService.generateRepoMap())
        .thenReturn(new EditorResult(true, "REPO MAP CONTENT", null));
    when(gitService.getStatus()).thenReturn("GIT STATUS OUTPUT");

    // Mocking the call to throw the limit exception
    when(requestSpec.call()).thenThrow(new RuntimeException("MAX_TOOL_TURNS_EXCEEDED"));

    when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.EXPLORE);
    when(promptService.generateExploreSystemPrompt()).thenReturn("EXPLORE PROMPT");

    settings.setMaxAgentToolTurns(3);

        String result = aiService.chat("Hello");

    assertTrue(result.contains("Maximum tool turns (3) exceeded"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void testChatHandlesYieldTurnExceptionImmediately() {
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(anyString())).thenReturn(requestSpec);
    when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.tools(any())).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);

    when(promptService.loadCoreWorkflowPrompt()).thenReturn("CORE PROMPT");
    when(promptService.loadJexlContext()).thenReturn("JEXL CONTEXT");
    when(promptService.loadAllPrompts()).thenReturn(" ALL PROMPTS");
    when(promptService.loadAllDocs()).thenReturn(" ALL DOCS");
    when(promptService.loadProjectContext()).thenReturn("");
    when(promptService.loadPlanPrompt()).thenReturn("PLAN PROMPT");
    when(repoMapPackerService.generateRepoMap()).thenReturn(new EditorResult(true, "", null));
    when(gitService.getStatus()).thenReturn("");
    when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);

    org.roxycode.app.ai.YieldTurnException yieldEx =
        new org.roxycode.app.ai.YieldTurnException(
            "{\"summary\":\"PLAN SUMMARY\",\"message\":\"Transition Request\"}");
    when(requestSpec.call()).thenThrow(new RuntimeException(yieldEx));

    String result = aiService.chat("Create plan");

    assertTrue(result.contains("PLAN SUMMARY"));
    assertTrue(result.contains("Transition Request"));
    verify(chatMemory).add(eq("default"), any(org.springframework.ai.chat.messages.AssistantMessage.class));
  }
}
