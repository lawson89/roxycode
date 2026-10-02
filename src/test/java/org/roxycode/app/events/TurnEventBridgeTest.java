package org.roxycode.app.events;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TurnEventBridgeTest {

    @Test
    void testEventDispatching() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TurnEventBridge bridge = new TurnEventBridge(publisher);
        AtomicBoolean turnCalled = new AtomicBoolean(false);
        AtomicBoolean completeCalled = new AtomicBoolean(false);
        AtomicReference<UserMessageEvent> userMsgRef = new AtomicReference<>();

        bridge.addTurnListener(event -> turnCalled.set(true));
        bridge.addCompleteListener(event -> completeCalled.set(true));
        bridge.addUserMessageListener(userMsgRef::set);

        bridge.handleTurn(new AgentTurnEvent("Test", 1, "EXPLORE"));
        bridge.handleComplete(new AgentTurnCompleteEvent("Test", 1, "Done"));
        bridge.handleUserMessage(new UserMessageEvent("You", "Hello"));

        assertTrue(turnCalled.get(), "Turn listener should be called");
        assertTrue(completeCalled.get(), "Complete listener should be called");
        assertNotNull(userMsgRef.get(), "User message listener should be called");
        assertEquals("Hello", userMsgRef.get().message());
    }

    @Test
    void testPublishUserMessage() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TurnEventBridge bridge = new TurnEventBridge(publisher);
        
        bridge.publishUserMessage("You", "Test Message");
        
        verify(publisher).publishEvent(any(UserMessageEvent.class));
    }
}
