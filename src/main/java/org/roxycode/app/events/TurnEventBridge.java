package org.roxycode.app.events;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class TurnEventBridge {
    private final ApplicationEventPublisher publisher;
    private final List<Consumer<AgentTurnEvent>> turnListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<AgentTurnCompleteEvent>> completeListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<UserMessageEvent>> userMessageListeners = new CopyOnWriteArrayList<>();

    public TurnEventBridge(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void addTurnListener(Consumer<AgentTurnEvent> listener) { turnListeners.add(listener); }
    public void addCompleteListener(Consumer<AgentTurnCompleteEvent> listener) { completeListeners.add(listener); }
    public void addUserMessageListener(Consumer<UserMessageEvent> listener) { userMessageListeners.add(listener); }

    public void publishUserMessage(String user, String message) {
        publisher.publishEvent(new UserMessageEvent(user, message));
    }

    @EventListener
    public void handleTurn(AgentTurnEvent event) {
        turnListeners.forEach(l -> l.accept(event));
    }

    @EventListener
    public void handleComplete(AgentTurnCompleteEvent event) {
        completeListeners.forEach(l -> l.accept(event));
    }

    @EventListener
    public void handleUserMessage(UserMessageEvent event) {
        userMessageListeners.forEach(l -> l.accept(event));
    }
}
