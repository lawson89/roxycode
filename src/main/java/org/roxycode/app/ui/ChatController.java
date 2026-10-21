package org.roxycode.app.ui;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.events.UserMessageEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;

/** Controller coordinating chat turns, service events, and workflow transitions. */
@SuppressWarnings("PMD.NullAssignment")
public final class ChatController implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    static final String ROLE_SYSTEM = "system";
    static final String ROLE_USER = "user";
    static final String ROLE_AI = "ai";

    private final ChatModel model;
    private final ChatPanel view;
    private final AiService aiService;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatMemory chatMemory;
    private final SettingsService settingsService;
    private final PlanService planService;
    private final Executor turnExecutor;
    private final AtomicLong turnSequence = new AtomicLong();

    private volatile long activeTurnId = 0;
    private volatile Future<?> activeTurnFuture;
    private volatile WorkflowTransitionEvent deferredTransitionEvent;

    public ChatController(
            ChatModel model,
            ChatPanel view,
            AiService aiService,
            PlanService planService,
            ApplicationEventPublisher eventPublisher,
            ChatMemory chatMemory,
            SettingsService settingsService) {
        this(
                model,
                view,
                aiService,
                planService,
                eventPublisher,
                chatMemory,
                settingsService,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    public ChatController(
            ChatModel model,
            ChatPanel view,
            AiService aiService,
            PlanService planService,
            ApplicationEventPublisher eventPublisher,
            ChatMemory chatMemory,
            SettingsService settingsService,
            Executor turnExecutor) {
        this.model = model;
        this.view = view;
        this.aiService = aiService;
        this.planService = planService;
        this.eventPublisher = eventPublisher;
        this.chatMemory = chatMemory;
        this.settingsService = settingsService;
        this.turnExecutor =
                turnExecutor != null ? turnExecutor : Executors.newVirtualThreadPerTaskExecutor();
    }

    Executor getTurnExecutor() {
        return turnExecutor;
    }

    long getActiveTurnId() {
        return activeTurnId;
    }

    @Override
    public void close() {
        if (turnExecutor instanceof ExecutorService executorService) {
            executorService.close();
        }
    }

    public boolean isTurnActive() {
        return activeTurnId != 0 && activeTurnFuture != null;
    }

    public String formatCurrentTurnStatus() {
        AppSettings settings = settingsService != null ? settingsService.getSettings() : null;
        ImplementationPlan plan =
                (model.isTaskMode() && planService != null) ? planService.getCurrentPlan() : null;
        return model.formatTurnStatus(settings, plan);
    }

    public boolean hasPendingPhase() {
        return (planService != null && planService.getPendingPhase() != null)
                || (deferredTransitionEvent != null
                        && !deferredTransitionEvent.autonomous()
                        && deferredTransitionEvent.to() != null);
    }

    public void sendMessage(String rawText) {
        if (rawText == null) return;
        String text = rawText.trim();
        if (text.isEmpty()) return;

        if (eventPublisher != null) {
            eventPublisher.publishEvent(
                    new UserMessageEvent(model.getConversationId(), ROLE_USER, text));
        }
        view.appendMessage(ROLE_USER, text);
        view.clearInputText();
        triggerTurn(text);
    }

    public void stopTurn() {
        model.setTurnCancelled(true);
        deferredTransitionEvent = null;
        activeTurnId = 0;
        Future<?> futureToCancel = activeTurnFuture;
        if (futureToCancel != null) {
            activeTurnFuture = null;
            futureToCancel.cancel(true);
            Runnable uiUpdate =
                    () -> {
                        view.appendMessage(ROLE_SYSTEM, "_Request cancelled by user._");
                        view.setLoading(false);
                    };
            if (SwingUtilities.isEventDispatchThread()) {
                uiUpdate.run();
            } else {
                SwingUtilities.invokeLater(uiUpdate);
            }
        }
    }

    public void approvePhase() {
        WorkflowPhase current = planService.getCurrentPhase();
        WorkflowPhase requested = planService.getPendingPhase();
        if (requested != null) {
            model.setTurnCancelled(false);
            deferredTransitionEvent = null;
            planService.approveTransition();
            view.appendMessage(
                    ROLE_SYSTEM, "Transition to **" + requested.getDisplayName() + "** approved.");
            if (current == WorkflowPhase.REVIEW && requested == WorkflowPhase.PLAN) {
                chatMemory.clear(model.getConversationId());
                view.appendMessage(
                        ROLE_SYSTEM,
                        "Task complete. What is the next feature you would like to work on?");
            } else {
                triggerTurn(
                        "I approve the transition to "
                                + requested.getDisplayName()
                                + ". Please proceed with the next phase.");
            }
        }
    }

    public void rejectPhase() {
        WorkflowPhase requested = planService.getPendingPhase();
        if (requested != null) {
            model.setTurnCancelled(false);
            deferredTransitionEvent = null;
            planService.rejectTransition();
            view.appendMessage(
                    ROLE_SYSTEM, "Transition to **" + requested.getDisplayName() + "** rejected.");
            triggerTurn(
                    "I have rejected the transition to "
                            + requested.getDisplayName()
                            + ". Let's discuss what needs to be fixed or adjusted.");
        }
    }

    public void resetSession() {
        if (view.confirmResetSession()) {
            performResetSession();
        }
    }

    void performResetSession() {
        model.setTurnCancelled(true);
        deferredTransitionEvent = null;
        activeTurnId = 0;
        Future<?> futureToCancel = activeTurnFuture;
        if (futureToCancel != null) {
            activeTurnFuture = null;
            futureToCancel.cancel(true);
            view.setLoading(false);
        }
        if (model.isTaskMode()) {
            planService.resetWorkflow();
        }
        model.setCurrentTurn(0);
        view.updateTurnLabel();
        chatMemory.clear(model.getConversationId());
        view.clearOutput();
        view.appendMessage(ROLE_SYSTEM, "Session reset.");
    }

    public void triggerTurn(String text) {
        if (text == null || text.trim().isEmpty()) return;

        model.setTurnCancelled(false);
        deferredTransitionEvent = null;

        Future<?> previousFuture = activeTurnFuture;
        if (previousFuture != null) {
            activeTurnFuture = null;
            previousFuture.cancel(true);
        }

        long turnId = turnSequence.incrementAndGet();
        activeTurnId = turnId;

                view.setLoading(true);
        model.setCurrentTurn(0);
        view.updateTurnLabel();

        List<byte[]> imageBytes = model.drainPendingImagesAsPngBytes();
        view.clearAttachments();

        FutureTask<Void> task =
                new FutureTask<>(
                        () -> {
                            try {
                                WorkflowPhase activePhase =
                                        model.resolveActivePhase(planService.getCurrentPhase());
                                String response =
                                        aiService.chat(
                                                model.getConversationId(),
                                                activePhase,
                                                text,
                                                imageBytes);
                                if (Thread.currentThread().isInterrupted()
                                        || model.isTurnCancelled()) {
                                    SwingUtilities.invokeLater(() -> finishTurnCancelled(turnId));
                                    return;
                                }
                                SwingUtilities.invokeLater(
                                        () -> finishTurnSuccess(turnId, response));
                            } catch (Exception ex) {
                                if (ExceptionUtils.isCancellation(ex) || model.isTurnCancelled()) {
                                    SwingUtilities.invokeLater(() -> finishTurnCancelled(turnId));
                                    return;
                                }
                                log.error("AI chat interaction failed: {}", ex.getMessage(), ex);
                                String errorMsg =
                                        "Error: "
                                                + (ex.getMessage() != null
                                                        ? ex.getMessage()
                                                        : ex.getClass().getSimpleName());
                                SwingUtilities.invokeLater(
                                        () -> finishTurnSuccess(turnId, errorMsg));
                            }
                        },
                        null);
        activeTurnFuture = task;
        turnExecutor.execute(task);
    }

    private void finishTurnSuccess(long turnId, String response) {
        if (activeTurnId != turnId || model.isTurnCancelled()) {
            return;
        }
        activeTurnId = 0;
        activeTurnFuture = null;
        WorkflowTransitionEvent transition = deferredTransitionEvent;
        deferredTransitionEvent = null;

        view.appendMessage(ROLE_AI, response);
        view.setLoading(false);

        if (transition != null && !model.isTurnCancelled()) {
            applyWorkflowTransition(transition);
        }
    }

    private void finishTurnCancelled(long turnId) {
        if (activeTurnId != turnId) {
            return;
        }
        activeTurnId = 0;
        activeTurnFuture = null;
        model.setTurnCancelled(true);
        deferredTransitionEvent = null;
        view.appendMessage(ROLE_SYSTEM, "_Request cancelled by user._");
        view.setLoading(false);
    }

    void onWorkflowTransition(WorkflowTransitionEvent event) {
        if (event == null || !model.isTaskMode()) return;
        if (!event.autonomous() && event.to() == null) {
            deferredTransitionEvent = null;
            SwingUtilities.invokeLater(view::hideTransitionBanner);
            return;
        }
        if (model.isTurnCancelled()) return;
        SwingUtilities.invokeLater(
                () -> {
                    if (model.isTurnCancelled()) return;
                    if (isTurnActive()) {
                        deferredTransitionEvent = event;
                    } else {
                        applyWorkflowTransition(event);
                    }
                });
    }

    private void applyWorkflowTransition(WorkflowTransitionEvent event) {
        if (model.isTurnCancelled() || event.to() == null) return;
        if (event.autonomous()) {
            if (event.to() == WorkflowPhase.PLAN) {
                view.appendMessage(
                        ROLE_SYSTEM,
                        "Task complete. What is the next feature you would like to work on?");
            } else {
                triggerTurn(
                        "Phase changed to "
                                + event.to()
                                + ". Please proceed with the tasks in the new phase.");
            }
        } else {
            String path = event.from().getDisplayName() + " → " + event.to().getDisplayName();
            view.showTransitionBanner(path);
        }
    }

    void onAgentTurn(AgentTurnEvent event) {
        if (event == null
                || !model.getConversationId().equals(event.conversationId())
                || model.isTurnCancelled()) {
            return;
        }
        SwingUtilities.invokeLater(
                () -> {
                    if (model.isTurnCancelled()) return;
                    model.setCurrentTurn(event.turnNumber());
                    view.updateTurnLabel();
                });
    }

    void onToolExecution(ToolExecutionEvent event) {
        if (event == null
                || !model.getConversationId().equals(event.conversationId())
                || model.isTurnCancelled()) {
            return;
        }
        SwingUtilities.invokeLater(
                () -> {
                    if (model.isTurnCancelled()) return;
                    String toolName = "";
                    String output = JexlToHtmlConverter.convert(event.script());
                    view.appendToolLog(toolName, output, event.success(), event.error());
                });
    }

    void onAgentTurnComplete(AgentTurnCompleteEvent event) {
        if (event == null || !model.getConversationId().equals(event.conversationId())) {
            return;
        }
        SwingUtilities.invokeLater(
                () -> {
                    if (!isTurnActive()) {
                        view.setLoading(false);
                    }
                });
    }

    void onSettingsChanged(SettingsChangedEvent event) {
        SwingUtilities.invokeLater(view::updateTurnLabel);
    }

    void onPlanUpdated(PlanUpdatedEvent event) {
        if (!model.isTaskMode()) return;
        SwingUtilities.invokeLater(view::updateTurnLabel);
    }

    void onPhaseChanged(PhaseChangedEvent event) {
        if (event != null) {
            onPhaseChanged(event.phase());
        }
    }

    void onPhaseChanged(WorkflowPhase phase) {
        log.debug("Phase changed to: {}", phase);
        SwingUtilities.invokeLater(
                () -> {
                    if (model.isTaskMode()) {
                        view.hideTransitionBanner();
                        view.updateTurnLabel();
                    }
                });
    }
}
