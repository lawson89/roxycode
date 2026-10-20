package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CancellationException;
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
import org.roxycode.app.model.AppSettings;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
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

    private AiService aiService;
    private AppSettings settings;

    @BeforeEach
    void setUp() {
        settings = new AppSettings();
        lenient().when(settingsService.getSettings()).thenReturn(settings);

        when(chatClientBuilder.defaultAdvisors(
                        any(org.springframework.ai.chat.client.advisor.api.Advisor[].class)))
                .thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(chatClient);

        aiService =
                new AiService(
                        chatClientBuilder,
                        settingsService,
                        promptService,
                        jexlServiceRegistry,
                        workflowService,
                        chatMemory,
                        eventPublisher);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testChatUsesConfiguredModelAndDynamicPrompt() {
        settings.setGeminiModel("test-model-123");
        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
        when(promptService.buildSystemPrompt(WorkflowPhase.CODE, null))
                .thenReturn("FULL CODE SYSTEM PROMPT");

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

        assertEquals("FULL CODE SYSTEM PROMPT", capturedPrompt);
        verify(promptService).buildSystemPrompt(WorkflowPhase.CODE, null);
        verify(requestSpec).options(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testChatWithConversationIdAndPhase() {
        settings.setGeminiModel("test-model-123");
        when(promptService.buildSystemPrompt(WorkflowPhase.REVIEW, "Extra instructions"))
                .thenReturn("REVIEW SYSTEM PROMPT");

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);

        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("AI Response");

        String result =
                aiService.chat(
                        "custom-conv", WorkflowPhase.REVIEW, "Hello", "Extra instructions", null);

        assertEquals("AI Response", result);

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemPromptCaptor.capture());
        assertEquals("REVIEW SYSTEM PROMPT", systemPromptCaptor.getValue());

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

        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.EXPLORE);
        when(promptService.buildSystemPrompt(WorkflowPhase.EXPLORE, null))
                .thenReturn("EXPLORE PROMPT");

        // Mocking the call to throw the limit exception
        when(requestSpec.call()).thenThrow(new RuntimeException("MAX_TOOL_TURNS_EXCEEDED"));

        settings.setMaxAgentToolTurns(3);

        String result = aiService.chat("Hello");

        assertTrue(result.contains("Maximum tool turns (3) exceeded"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testChatFormatsDirectReturnTransitionOutput() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);

        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
        when(promptService.buildSystemPrompt(WorkflowPhase.PLAN, null)).thenReturn("PLAN PROMPT");

        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content())
                .thenReturn(
                        """
                        {"summary":"PLAN SUMMARY","message":"Transition Request","reason":"Ready"}
                        """);

        String result = aiService.chat("Create plan");

        assertEquals("PLAN SUMMARY\n\nTransition Request", result);
    }

    @Test
    void testReturnDirectToolCallingManagerMarksTransitionAsReturnDirect() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolCallingManager manager = aiService.createReturnDirectToolCallingManager(delegate);

        Prompt prompt = mock(Prompt.class);
        ChatResponse chatResponse = mock(ChatResponse.class);

        ToolResponseMessage transitionMsg =
                ToolResponseMessage.builder()
                        .responses(
                                List.of(
                                        new ToolResponseMessage.ToolResponse(
                                                "1",
                                                "executeJexl",
                                                """
                                                {"summary":"PLAN","message":"Transition Request","reason":"Ready"}""")))
                        .build();
        ToolExecutionResult transitionExecResult =
                ToolExecutionResult.builder()
                        .conversationHistory(List.of(transitionMsg))
                        .returnDirect(false)
                        .build();

        when(delegate.executeToolCalls(prompt, chatResponse)).thenReturn(transitionExecResult);

        ToolExecutionResult result = manager.executeToolCalls(prompt, chatResponse);
        assertTrue(
                result.returnDirect(),
                "Transition tool response should set ToolExecutionResult.returnDirect() to true");

        ToolResponseMessage normalMsg =
                ToolResponseMessage.builder()
                        .responses(
                                List.of(
                                        new ToolResponseMessage.ToolResponse(
                                                "2",
                                                "executeJexl",
                                                """
                                                {"result":"2"}""")))
                        .build();
        ToolExecutionResult normalExecResult =
                ToolExecutionResult.builder()
                        .conversationHistory(List.of(normalMsg))
                        .returnDirect(false)
                        .build();

        when(delegate.executeToolCalls(prompt, chatResponse)).thenReturn(normalExecResult);

        ToolExecutionResult normalResult = manager.executeToolCalls(prompt, chatResponse);
        assertFalse(
                normalResult.returnDirect(),
                "Normal tool response should keep ToolExecutionResult.returnDirect() as false");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testChatPropagatesCancellationAndPostCallInterruption() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);

        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.CODE);
        when(promptService.buildSystemPrompt(WorkflowPhase.CODE, null)).thenReturn("CODE PROMPT");

        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content())
                .thenAnswer(
                        inv -> {
                            Thread.currentThread().interrupt();
                            return "Should be discarded due to interrupt";
                        });

        try {
            assertThrows(CancellationException.class, () -> aiService.chat("Run code"));
            assertTrue(
                    Thread.currentThread().isInterrupted(),
                    "Interrupt flag should be preserved when chat is cancelled");
        } finally {
            Thread.interrupted();
        }
    }
}
