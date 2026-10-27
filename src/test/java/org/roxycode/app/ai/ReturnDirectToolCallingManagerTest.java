package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ReturnDirectToolCallingManagerTest {

    @Mock private ToolCallingManager delegate;
    @Mock private SettingsService settingsService;
    @Mock private PlanService planService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AppSettings settings;
    private ReturnDirectToolCallingManager manager;

    @BeforeEach
    void setUp() {
        settings = new AppSettings();
        lenient().when(settingsService.getSettings()).thenReturn(settings);
        manager =
                new ReturnDirectToolCallingManager(
                        delegate, settingsService, planService, eventPublisher);
    }

    private static Prompt buildPrompt(String userText, TurnToolContext ctx) {
        return new Prompt(
                List.of(new UserMessage(userText)),
                ToolCallingChatOptions.builder().toolContext(ctx.toMap()).build());
    }

    private static ToolExecutionResult buildDelegatedResult(
            String userText,
            AssistantMessage assistantMsg,
            boolean returnDirect,
            ToolResponseMessage.ToolResponse... responses) {
        return ToolExecutionResult.builder()
                .conversationHistory(
                        List.of(
                                new UserMessage(userText),
                                assistantMsg,
                                ToolResponseMessage.builder()
                                        .responses(List.of(responses))
                                        .build()))
                .returnDirect(returnDirect)
                .build();
    }

    @Test
    void testResolveToolDefinitionsDelegates() {
        ToolCallingChatOptions options = mock(ToolCallingChatOptions.class);
        List<ToolDefinition> expected = List.of(mock(ToolDefinition.class));
        when(delegate.resolveToolDefinitions(options)).thenReturn(expected);
        assertSame(expected, manager.resolveToolDefinitions(options));
        verify(delegate).resolveToolDefinitions(options);
    }

    @Test
    void testExecuteToolCallsEnforcesMaxTurnsAndPublishesEvents() {
        settings.setMaxAgentToolTurns(1);
        AtomicInteger turnCounter = new AtomicInteger(0);
        Prompt prompt =
                buildPrompt(
                        "Hi", new TurnToolContext("conv-1", WorkflowPhase.CODE, turnCounter, null));
        AssistantMessage assistantMsg =
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "call-1",
                                                "function",
                                                "executeJexl",
                                                """
                        {"script":"1"}""")))
                        .build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistantMsg)));

        doReturn(
                        buildDelegatedResult(
                                "Hi",
                                assistantMsg,
                                false,
                                new ToolResponseMessage.ToolResponse("call-1", "executeJexl", "1")))
                .when(delegate)
                .executeToolCalls(prompt, chatResponse);

        ToolExecutionResult r1 = manager.executeToolCalls(prompt, chatResponse);
        assertFalse(r1.returnDirect());
        assertEquals(1, turnCounter.get());
        verify(eventPublisher).publishEvent(new AgentTurnEvent("conv-1", "Roxy", 1, "CODE"));

        ToolExecutionResult r2 = manager.executeToolCalls(prompt, chatResponse);
        assertTrue(r2.returnDirect());
        ToolResponseMessage stopMsg =
                assertInstanceOf(
                        ToolResponseMessage.class,
                        r2.conversationHistory().get(r2.conversationHistory().size() - 1));
        assertEquals(
                "Autonomous execution stopped: Maximum tool turns (1) exceeded.",
                stopMsg.getResponses().get(0).responseData());
    }

    @Test
    void testExecuteToolCallsSuppressesReturnDirectAndLogsPlanToolFailure() {
        Prompt prompt =
                buildPrompt(
                        "Transition",
                        new TurnToolContext(
                                "task:default", WorkflowPhase.PLAN, new AtomicInteger(0), null));
        AssistantMessage assistantMsg =
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "call-1",
                                                "function",
                                                "requestDevelopment",
                                                """
                        {"reason":"Ready"}""")))
                        .build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistantMsg)));

        doReturn(
                        buildDelegatedResult(
                                "Transition",
                                assistantMsg,
                                true,
                                new ToolResponseMessage.ToolResponse(
                                        "call-1",
                                        "requestDevelopment",
                                        """
                        {"error":"Plan missing"}""")))
                .when(delegate)
                .executeToolCalls(prompt, chatResponse);

        ToolExecutionResult result = manager.executeToolCalls(prompt, chatResponse);
        assertFalse(result.returnDirect());
        verify(eventPublisher)
                .publishEvent(
                        new ToolExecutionEvent(
                                "task:default",
                                """
                planService.requestDevelopment({"reason":"Ready"})""",
                                null,
                                false,
                                "Plan missing"));
    }

    @Test
    void testParallelToolCallsWithEmptyIdsLogMatchingArgumentsPerTool() {
        Prompt prompt =
                buildPrompt(
                        "Complete and review",
                        new TurnToolContext(
                                "task:default", WorkflowPhase.CODE, new AtomicInteger(0), null));
        AssistantMessage assistantMsg =
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "",
                                                "function",
                                                "markStepsCompleted",
                                                """
                                {"indices":[3,4]}"""),
                                        new AssistantMessage.ToolCall(
                                                "",
                                                "function",
                                                "requestReview",
                                                """
                                {"reason":"All steps verified"}""")))
                        .build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistantMsg)));

        doReturn(
                        buildDelegatedResult(
                                "Complete and review",
                                assistantMsg,
                                true,
                                new ToolResponseMessage.ToolResponse(
                                        "", "markStepsCompleted", "null"),
                                new ToolResponseMessage.ToolResponse(
                                        "", "requestReview", '"' + "Transitioned to REVIEW" + '"')))
                .when(delegate)
                .executeToolCalls(prompt, chatResponse);

        ToolExecutionResult result = manager.executeToolCalls(prompt, chatResponse);
        assertTrue(result.returnDirect());
        verify(eventPublisher)
                .publishEvent(
                        new ToolExecutionEvent(
                                "task:default",
                                """
                planService.markStepsCompleted({"indices":[3,4]})""",
                                "null",
                                true,
                                null));
        verify(eventPublisher)
                .publishEvent(
                        new ToolExecutionEvent(
                                "task:default",
                                """
                planService.requestReview({"reason":"All steps verified"})""",
                                "Transitioned to REVIEW",
                                true,
                                null));
    }

    @Test
    void testExecuteToolCallsAccumulatesAssistantText() {
        settings.setMaxAgentToolTurns(5);
        StringBuilder accumulator = new StringBuilder();
        Prompt prompt =
                buildPrompt(
                        "Hi",
                        new TurnToolContext(
                                "conv-accum",
                                WorkflowPhase.EXPLORE,
                                new AtomicInteger(0),
                                accumulator));
        AssistantMessage assistantMsg =
                AssistantMessage.builder()
                        .content("Initial thoughts.")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "c1", "function", "executeJexl", "{}")))
                        .build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistantMsg)));

        doReturn(
                        ToolExecutionResult.builder()
                                .conversationHistory(List.of(new UserMessage("Hi"), assistantMsg))
                                .returnDirect(false)
                                .build())
                .when(delegate)
                .executeToolCalls(prompt, chatResponse);

        manager.executeToolCalls(prompt, chatResponse);
        assertEquals("Initial thoughts.\n\n", accumulator.toString());
    }
}
