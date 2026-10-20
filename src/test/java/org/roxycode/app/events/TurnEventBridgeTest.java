package org.roxycode.app.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.model.AppSettings;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;

class TurnEventBridgeTest {

    @Test
    void testEventDispatching() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TurnEventBridge bridge = new TurnEventBridge(publisher);
        AtomicBoolean turnCalled = new AtomicBoolean(false);
        AtomicBoolean completeCalled = new AtomicBoolean(false);
        AtomicReference<UserMessageEvent> userMsgRef = new AtomicReference<>();

        String convId = "test-conv";
        bridge.subscribe(AgentTurnEvent.class, convId, event -> turnCalled.set(true));
        bridge.subscribe(AgentTurnCompleteEvent.class, convId, event -> completeCalled.set(true));
        bridge.subscribe(UserMessageEvent.class, convId, userMsgRef::set);

        // Matching conversation
        bridge.handleApplicationEvent(new AgentTurnEvent(convId, "Test", 1, "EXPLORE"));
        bridge.handleApplicationEvent(new AgentTurnCompleteEvent(convId, "Test", 1, "Done"));
        bridge.handleApplicationEvent(
                new PayloadApplicationEvent<>(this, new UserMessageEvent(convId, "You", "Hello")));
        assertTrue(turnCalled.get(), "Turn listener should be called");
        assertTrue(completeCalled.get(), "Complete listener should be called");
        assertNotNull(userMsgRef.get(), "User message listener should be called");
        assertEquals("Hello", userMsgRef.get().message());

        // Non-matching conversation
        turnCalled.set(false);
        bridge.handleApplicationEvent(new AgentTurnEvent("other-conv", "Test", 2, "EXPLORE"));
        assertFalse(turnCalled.get(), "Listener should not be called for different conversation");
    }

    @Test
    void testPublishUserMessage() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TurnEventBridge bridge = new TurnEventBridge(publisher);

        bridge.publishUserMessage("conv1", "You", "Test Message");

        verify(publisher).publishEvent(any(UserMessageEvent.class));
    }

    @Test
    void testToolExecutionEvent() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TurnEventBridge bridge = new TurnEventBridge(publisher);
        AtomicBoolean toolCalled = new AtomicBoolean(false);

        String convId = "test-conv";
        bridge.subscribe(ToolExecutionEvent.class, convId, event -> toolCalled.set(true));

        bridge.handleApplicationEvent(new ToolExecutionEvent(convId, "var x = 1;", 1, true, null));

        assertTrue(toolCalled.get());
    }

    @Test
    void testFileChangedEvent() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TurnEventBridge bridge = new TurnEventBridge(publisher);
        AtomicReference<FileChangedEvent> fileChangedRef = new AtomicReference<>();

        bridge.subscribe(FileChangedEvent.class, fileChangedRef::set);
        bridge.handleApplicationEvent(new FileChangedEvent("src/main/java/App.java"));

        assertNotNull(fileChangedRef.get());
        assertEquals("src/main/java/App.java", fileChangedRef.get().path());
    }

    @Test
    void testGenericSubscribeAndDispatchConversationAndGlobalEvents() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TurnEventBridge bridge = new TurnEventBridge(publisher);

        AtomicReference<AgentTurnEvent> turnRef = new AtomicReference<>();
        AtomicReference<AgentTurnCompleteEvent> completeRef = new AtomicReference<>();
        AtomicReference<UserMessageEvent> userRef = new AtomicReference<>();
        AtomicReference<ToolExecutionEvent> toolRef = new AtomicReference<>();
        AtomicReference<AutonomousTransitionEvent> transitionRef = new AtomicReference<>();
        AtomicReference<FileChangedEvent> fileRef = new AtomicReference<>();
        AtomicReference<PhaseChangedEvent> phaseRef = new AtomicReference<>();
        AtomicReference<TransitionRequestedEvent> reqRef = new AtomicReference<>();
        AtomicReference<PlanUpdatedEvent> planRef = new AtomicReference<>();
        AtomicReference<SettingsChangedEvent> settingsRef = new AtomicReference<>();
        AtomicReference<ProjectChangedEvent> projectRef = new AtomicReference<>();

        String convId = "conv-123";
        bridge.subscribe(AgentTurnEvent.class, convId, turnRef::set);
        bridge.subscribe(AgentTurnCompleteEvent.class, convId, completeRef::set);
        bridge.subscribe(UserMessageEvent.class, convId, userRef::set);
        bridge.subscribe(ToolExecutionEvent.class, convId, toolRef::set);
        bridge.subscribe(AutonomousTransitionEvent.class, transitionRef::set);
        bridge.subscribe(FileChangedEvent.class, fileRef::set);
        bridge.subscribe(PhaseChangedEvent.class, phaseRef::set);
        bridge.subscribe(TransitionRequestedEvent.class, reqRef::set);
        bridge.subscribe(PlanUpdatedEvent.class, planRef::set);
        bridge.subscribe(SettingsChangedEvent.class, settingsRef::set);
        bridge.subscribe(ProjectChangedEvent.class, projectRef::set);

        // Dispatch events for a different conversation - should be filtered out
        bridge.dispatch(new AgentTurnEvent("other", "Agent", 1, "CODE"));
        bridge.dispatch(new AgentTurnCompleteEvent("other", "Agent", 1, "Result"));
        bridge.dispatch(new UserMessageEvent("other", "User", "Msg"));
        bridge.dispatch(new ToolExecutionEvent("other", "1+1", 2, true, null));

        assertNull(turnRef.get());
        assertNull(completeRef.get());
        assertNull(userRef.get());
        assertNull(toolRef.get());

        // Dispatch events for matching conversation and global events
        AgentTurnEvent turnEvent = new AgentTurnEvent(convId, "Agent", 1, "CODE");
        AgentTurnCompleteEvent completeEvent =
                new AgentTurnCompleteEvent(convId, "Agent", 1, "Result");
        UserMessageEvent userEvent = new UserMessageEvent(convId, "User", "Msg");
        ToolExecutionEvent toolEvent = new ToolExecutionEvent(convId, "1+1", 2, true, null);
        AutonomousTransitionEvent transitionEvent =
                new AutonomousTransitionEvent(
                        WorkflowPhase.CODE, WorkflowPhase.REVIEW, "Ready for review");
        FileChangedEvent fileEvent = new FileChangedEvent("pom.xml");
        PhaseChangedEvent phaseEvent = new PhaseChangedEvent(WorkflowPhase.CODE);
        TransitionRequestedEvent reqEvent =
                new TransitionRequestedEvent(WorkflowPhase.PLAN, WorkflowPhase.CODE, "Ready");
        PlanUpdatedEvent planEvent = new PlanUpdatedEvent(null);
        SettingsChangedEvent settingsEvent = new SettingsChangedEvent(new AppSettings());
        ProjectChangedEvent projectEvent = new ProjectChangedEvent(Paths.get("."));

        bridge.dispatch(turnEvent);
        bridge.dispatch(completeEvent);
        bridge.dispatch(userEvent);
        bridge.dispatch(toolEvent);
        bridge.handleApplicationEvent(transitionEvent);
        bridge.dispatch(fileEvent);
        bridge.handleApplicationEvent(phaseEvent);
        bridge.handleApplicationEvent(reqEvent);
        bridge.handleApplicationEvent(planEvent);
        bridge.handleApplicationEvent(settingsEvent);
        bridge.handleApplicationEvent(projectEvent);

        assertSame(turnEvent, turnRef.get());
        assertSame(completeEvent, completeRef.get());
        assertSame(userEvent, userRef.get());
        assertSame(toolEvent, toolRef.get());
        assertSame(transitionEvent, transitionRef.get());
        assertSame(fileEvent, fileRef.get());
        assertSame(phaseEvent, phaseRef.get());
        assertSame(reqEvent, reqRef.get());
        assertSame(planEvent, planRef.get());
        assertSame(settingsEvent, settingsRef.get());
        assertSame(projectEvent, projectRef.get());

        assertInstanceOf(ConversationEvent.class, turnEvent);
        assertInstanceOf(ConversationEvent.class, completeEvent);
        assertInstanceOf(ConversationEvent.class, userEvent);
        assertInstanceOf(ConversationEvent.class, toolEvent);
    }
}
