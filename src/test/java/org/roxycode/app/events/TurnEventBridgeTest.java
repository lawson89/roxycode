package org.roxycode.app.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class TurnEventBridgeTest {

  @Test
  void testEventDispatching() {
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    TurnEventBridge bridge = new TurnEventBridge(publisher);
    AtomicBoolean turnCalled = new AtomicBoolean(false);
    AtomicBoolean completeCalled = new AtomicBoolean(false);
    AtomicReference<UserMessageEvent> userMsgRef = new AtomicReference<>();

    String convId = "test-conv";
    bridge.addTurnListener(convId, event -> turnCalled.set(true));
    bridge.addCompleteListener(convId, event -> completeCalled.set(true));
    bridge.addUserMessageListener(convId, userMsgRef::set);

    // Matching conversation
    bridge.handleTurn(new AgentTurnEvent(convId, "Test", 1, "EXPLORE"));
    bridge.handleComplete(new AgentTurnCompleteEvent(convId, "Test", 1, "Done"));
    bridge.handleUserMessage(new UserMessageEvent(convId, "You", "Hello"));
    assertTrue(turnCalled.get(), "Turn listener should be called");
    assertTrue(completeCalled.get(), "Complete listener should be called");
    assertNotNull(userMsgRef.get(), "User message listener should be called");
    assertEquals("Hello", userMsgRef.get().message());

    // Non-matching conversation
    turnCalled.set(false);
    bridge.handleTurn(new AgentTurnEvent("other-conv", "Test", 2, "EXPLORE"));
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
    bridge.addToolExecutionListener(convId, event -> toolCalled.set(true));

    bridge.handleToolExecution(new ToolExecutionEvent(convId, "var x = 1;", 1, true, null));

    assertTrue(toolCalled.get());
  }

  @Test
  void testFileChangedEvent() {
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    TurnEventBridge bridge = new TurnEventBridge(publisher);
    AtomicReference<FileChangedEvent> fileChangedRef = new AtomicReference<>();

    bridge.addFileChangedListener(fileChangedRef::set);
    bridge.handleFileChanged(new FileChangedEvent("src/main/java/App.java"));

    assertNotNull(fileChangedRef.get());
    assertEquals("src/main/java/App.java", fileChangedRef.get().path());
  }
}
