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
    private final List<Consumer<ToolExecutionEvent>> toolExecutionListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<AutonomousTransitionEvent>> autonomousTransitionListeners = new CopyOnWriteArrayList<>();

    public TurnEventBridge(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void addTurnListener(String conversationId, Consumer<AgentTurnEvent> listener) {
        turnListeners.add(event -> {
            if (event.conversationId().equals(conversationId)) {
                listener.accept(event);
            }
        });
    }

    public void addCompleteListener(String conversationId, Consumer<AgentTurnCompleteEvent> listener) {
        completeListeners.add(event -> {
            if (event.conversationId().equals(conversationId)) {
                listener.accept(event);
            }
        });
    }

    public void addUserMessageListener(String conversationId, Consumer<UserMessageEvent> listener) {
        userMessageListeners.add(event -> {
            if (event.conversationId().equals(conversationId)) {
                listener.accept(event);
            }
        });
    }

    public void addToolExecutionListener(String conversationId, Consumer<ToolExecutionEvent> listener) {
        toolExecutionListeners.add(event -> {
            if (event.conversationId().equals(conversationId)) {
                listener.accept(event);
            }
        });
    }

    public void addAutonomousTransitionListener(Consumer<AutonomousTransitionEvent> listener) {
        autonomousTransitionListeners.add(listener);
    }

    public void publishUserMessage(String conversationId, String user, String message) {
        publisher.publishEvent(new UserMessageEvent(conversationId, user, message));
    }

    public void publishToolExecution(String conversationId, String script, Object result, boolean success, String error) {
        publisher.publishEvent(new ToolExecutionEvent(conversationId, script, result, success, error));
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

    @EventListener
    public void handleToolExecution(ToolExecutionEvent event) {
        toolExecutionListeners.forEach(l -> l.accept(event));
    }

    @EventListener
    public void handleAutonomousTransition(AutonomousTransitionEvent event) {
        autonomousTransitionListeners.forEach(l -> l.accept(event));
    }
}
