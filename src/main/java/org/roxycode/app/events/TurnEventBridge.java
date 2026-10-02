package org.roxycode.app.events;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class TurnEventBridge {
    private final List<Consumer<AgentTurnEvent>> turnListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<AgentTurnCompleteEvent>> completeListeners = new CopyOnWriteArrayList<>();

    public void addTurnListener(Consumer<AgentTurnEvent> listener) { turnListeners.add(listener); }
    public void addCompleteListener(Consumer<AgentTurnCompleteEvent> listener) { completeListeners.add(listener); }

    @EventListener
    public void handleTurn(AgentTurnEvent event) {
        turnListeners.forEach(l -> l.accept(event));
    }

    @EventListener
    public void handleComplete(AgentTurnCompleteEvent event) {
        completeListeners.forEach(l -> l.accept(event));
    }
}
