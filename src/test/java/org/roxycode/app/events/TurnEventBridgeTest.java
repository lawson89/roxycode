package org.roxycode.app.events;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnEventBridgeTest {

    @Test
    void testEventDispatching() {
        TurnEventBridge bridge = new TurnEventBridge();
        AtomicBoolean turnCalled = new AtomicBoolean(false);
        AtomicBoolean completeCalled = new AtomicBoolean(false);

        bridge.addTurnListener(event -> turnCalled.set(true));
        bridge.addCompleteListener(event -> completeCalled.set(true));

        bridge.handleTurn(new AgentTurnEvent("Test", 1, "EXPLORE"));
        bridge.handleComplete(new AgentTurnCompleteEvent("Test", 1, "Done"));

        assertTrue(turnCalled.get(), "Turn listener should be called");
        assertTrue(completeCalled.get(), "Complete listener should be called");
    }
}
