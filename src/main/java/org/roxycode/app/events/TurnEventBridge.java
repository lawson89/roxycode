package org.roxycode.app.events;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class TurnEventBridge {
    private final ApplicationEventPublisher publisher;
    private final Map<Class<?>, List<Consumer<?>>> listeners = new ConcurrentHashMap<>();

    public TurnEventBridge(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public <T> void subscribe(Class<T> eventType, Consumer<T> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public <T extends ConversationEvent> void subscribe(
            Class<T> eventType, String conversationId, Consumer<T> listener) {
        subscribe(
                eventType,
                event -> {
                    if (event.conversationId().equals(conversationId)) {
                        listener.accept(event);
                    }
                });
    }

    @SuppressWarnings("unchecked")
    public <T> void dispatch(T event) {
        if (event == null) {
            return;
        }
        for (Consumer<?> raw : listeners.getOrDefault(event.getClass(), List.of())) {
            ((Consumer<T>) raw).accept(event);
        }
    }

    public void addTurnListener(String conversationId, Consumer<AgentTurnEvent> listener) {
        subscribe(AgentTurnEvent.class, conversationId, listener);
    }

    public void addCompleteListener(
            String conversationId, Consumer<AgentTurnCompleteEvent> listener) {
        subscribe(AgentTurnCompleteEvent.class, conversationId, listener);
    }

    public void addUserMessageListener(String conversationId, Consumer<UserMessageEvent> listener) {
        subscribe(UserMessageEvent.class, conversationId, listener);
    }

    public void addToolExecutionListener(
            String conversationId, Consumer<ToolExecutionEvent> listener) {
        subscribe(ToolExecutionEvent.class, conversationId, listener);
    }

    public void addAutonomousTransitionListener(Consumer<AutonomousTransitionEvent> listener) {
        subscribe(AutonomousTransitionEvent.class, listener);
    }

    public void addFileChangedListener(Consumer<FileChangedEvent> listener) {
        subscribe(FileChangedEvent.class, listener);
    }

    public void publishUserMessage(String conversationId, String user, String message) {
        publisher.publishEvent(new UserMessageEvent(conversationId, user, message));
    }

    @EventListener
    public void handleTurn(AgentTurnEvent event) {
        dispatch(event);
    }

    @EventListener
    public void handleComplete(AgentTurnCompleteEvent event) {
        dispatch(event);
    }

    @EventListener
    public void handleUserMessage(UserMessageEvent event) {
        dispatch(event);
    }

    @EventListener
    public void handleToolExecution(ToolExecutionEvent event) {
        dispatch(event);
    }

    @EventListener
    public void handleAutonomousTransition(AutonomousTransitionEvent event) {
        dispatch(event);
    }

    @EventListener
    public void handleFileChanged(FileChangedEvent event) {
        dispatch(event);
    }
}
