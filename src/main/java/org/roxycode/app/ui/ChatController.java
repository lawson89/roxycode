package org.roxycode.app.ui;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.ArchiveUpdatedEvent;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.ui.MarkdownPane.ChatTranscriptEntry;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;

@SuppressWarnings({"PMD.NullAssignment", "PMD.CloseResource"})
public final class ChatController implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    static final String ROLE_SYSTEM = "system";
    static final String ROLE_USER = "user";
    static final String ROLE_AI = "ai";
    static final String IMAGE_REQUIRES_TEXT_MESSAGE = "Please enter a prompt message to accompany the attached image.";
    private static final String BT = String.valueOf((char) 96);

    private final ChatModel model;
    private final ChatPanel view;
    private final AiService aiService;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatMemory chatMemory;
    private final SettingsService settingsService;
    private final PlanService planService;
    private MemoryService memoryService;
    private EnvironmentService environmentService;
    final boolean turnExecutorOwned;
    volatile Executor turnExecutor;
    private final AtomicLong turnSequence = new AtomicLong();

    volatile long activeTurnId = 0;
    private volatile Future<?> activeTurnFuture;
    private volatile WorkflowTransitionEvent deferredTransitionEvent;

    public ChatController(ChatModel model, ChatPanel view, AiService aiService, PlanService planService,
            ApplicationEventPublisher eventPublisher, ChatMemory chatMemory, SettingsService settingsService,
            Executor turnExecutor) {
        this.model = model;
        this.view = view;
        this.aiService = aiService;
        this.planService = planService;
        this.eventPublisher = eventPublisher;
        this.chatMemory = chatMemory;
        this.settingsService = settingsService;
        this.turnExecutorOwned = turnExecutor == null;
        this.turnExecutor = Objects.requireNonNullElseGet(turnExecutor, Executors::newVirtualThreadPerTaskExecutor);
    }

    void setTopicArchiveServices(MemoryService memoryService, EnvironmentService environmentService) {
        this.memoryService = memoryService;
        this.environmentService = environmentService;
    }

    synchronized void ensureOwnedExecutorActive() {
        if (turnExecutorOwned && turnExecutor instanceof ExecutorService executorService
                && executorService.isShutdown()) {
            turnExecutor = Executors.newVirtualThreadPerTaskExecutor();
        }
    }

    synchronized void closeOwnedExecutor() {
        if (turnExecutorOwned) {
            close();
        }
    }

    @Override
    public void close() {
        cancelActiveTurnState(true);
        synchronized (this) {
            if (turnExecutorOwned && turnExecutor instanceof ExecutorService executorService) {
                executorService.close();
            }
        }
    }

    public boolean isTurnActive() {
        return (activeTurnId != 0 && activeTurnFuture != null);
    }

    public String formatCurrentTurnStatus() {
        AppSettings settings = null;
        if (settingsService != null) {
            settings = settingsService.getSettings();
        }
        ImplementationPlan plan = null;
        if (model.isTaskMode() && planService != null) {
            plan = planService.getCurrentPlan();
        }
        return model.formatTurnStatus(settings, plan);
    }

    public boolean hasPendingPhase() {
        return (planService != null && planService.getPendingPhase() != null) || (deferredTransitionEvent != null
                && !deferredTransitionEvent.autonomous() && deferredTransitionEvent.to() != null);
    }

    public void sendMessage(String rawText) {
        if (rawText == null) {
            return;
        }
        String text = rawText.trim();
        if (text.isEmpty()) {
            if (model.hasPendingImages()) {
                view.appendMessage(ROLE_SYSTEM, IMAGE_REQUIRES_TEXT_MESSAGE);
            }
            return;
        }
        view.appendMessage(ROLE_USER, text);
        view.clearInputText();
        triggerTurn(text);
    }

    private boolean cancelActiveTurnState(boolean markCancelled) {
        model.setTurnCancelled(markCancelled);
        deferredTransitionEvent = null;
        activeTurnId = 0;
        Future<?> futureToCancel = activeTurnFuture;
        if (futureToCancel != null) {
            activeTurnFuture = null;
            futureToCancel.cancel(true);
        }
        return futureToCancel != null;
    }

    public void stopTurn() {
        if (cancelActiveTurnState(true)) {
            Edt.run(() -> {
                view.appendMessage(ROLE_SYSTEM, "_Request cancelled by user._");
                view.setLoading(false);
            });
        }
    }

    public void approvePhase() {
        WorkflowPhase current = planService.getCurrentPhase();
        WorkflowPhase requested = planService.getPendingPhase();
        if (requested == null) {
            return;
        }
        cancelActiveTurnState(false);
        Edt.run(view::hideTransitionBanner);
        turnExecutor.execute(() -> {
            planService.approveTransition();
            Edt.run(() -> {
                view.appendMessage(ROLE_SYSTEM, "Transition to **" + requested.getDisplayName() + "** approved.");
                if (current == WorkflowPhase.REVIEW && requested == WorkflowPhase.PLAN) {
                    chatMemory.clear(model.getConversationId());
                    view.appendMessage(ROLE_SYSTEM,
                            "Task complete. What is the next feature you would like to work on?");
                } else {
                    triggerTurn("I approve the transition to " + requested.getDisplayName()
                            + ". Please proceed with the next phase.");
                }
            });
        });
    }

    public void rejectPhase() {
        WorkflowPhase requested = planService.getPendingPhase();
        if (requested == null) {
            return;
        }
        cancelActiveTurnState(false);
        planService.rejectTransition();
        view.appendMessage(ROLE_SYSTEM, "Transition to **" + requested.getDisplayName() + "** rejected.");
        triggerTurn("I have rejected the transition to " + requested.getDisplayName()
                + ". Let's discuss what needs to be fixed or adjusted.");
    }

    public void resetSession() {
        if (!model.isTaskMode()) {
            List<ChatTranscriptEntry> capturedTranscript = view.getConversationMessages();
            if (capturedTranscript.isEmpty()) {
                performNewTopicReset(false, "");
                return;
            }
            String defaultTitle = MemoryService.deriveDefaultTopicTitle(capturedTranscript);
            ChatPanel.NewTopicChoice choice = view.promptNewTopicChoice(defaultTitle);
            if (choice != null && choice.proceed()) {
                performNewTopicReset(choice.saveTopic(), choice.topicName());
            }
            return;
        }
        if (view.confirmResetSession()) {
            performResetSession();
        }
    }

    void performResetSession() {
        if (!model.isTaskMode()) {
            performNewTopicReset(true, null);
        } else {
            resetSessionState(true, "Session reset.");
        }
    }

    public void resetForProjectChange() {
        resetSessionState(false, null);
    }

    void performNewTopicReset(boolean saveTopic, String customTopicTitle) {
        List<ChatTranscriptEntry> capturedTranscript = view.getConversationMessages();
        boolean shouldArchive = saveTopic && !capturedTranscript.isEmpty() && memoryService != null;
        String notice = "Started a new topic.";
        if (shouldArchive) {
            notice = null;
        }
        resetSessionState(true, notice);
        if (shouldArchive) {
            turnExecutor.execute(() -> archiveCapturedTopic(capturedTranscript, customTopicTitle));
        }
    }

    private void archiveCapturedTopic(List<ChatTranscriptEntry> capturedTranscript, String customTopicTitle) {
        String title = MemoryService.resolveTopicTitle(customTopicTitle, capturedTranscript);
        String rawUser = null;
        if (environmentService != null) {
            rawUser = environmentService.getCurrentUser();
        }
        String user = StringUtils.defaultIfBlank(rawUser, "User");
        String filename = memoryService.archiveTopicConversation(title, user, Instant.now().toString(),
                capturedTranscript);
        if (filename != null && !filename.isBlank() && eventPublisher != null) {
            eventPublisher.publishEvent(new ArchiveUpdatedEvent(ArchiveUpdatedEvent.TYPE_TOPIC, filename));
        }
        String finalTitle = title;
        Edt.run(() -> {
            String message;
            if (filename != null && !filename.isBlank()) {
                message = "Archived previous topic as **" + finalTitle + "** (" + BT + filename + BT
                        + ") and started a new topic.";
            } else {
                message = "Started a new topic.";
            }
            view.appendMessage(ROLE_SYSTEM, message);
        });
    }

    private void resetSessionState(boolean clearMemory, String systemNotice) {
        cancelActiveTurnState(false);
        if (model.isTaskMode() && planService != null) {
            planService.resetWorkflow();
        }
        model.clearPendingImages();
        model.setCurrentTurn(0);
        if (clearMemory) {
            chatMemory.clear(model.getConversationId());
        }
        Edt.run(() -> {
            view.clearAttachments();
            view.hideTransitionBanner();
            view.setLoading(false);
            view.updateTurnLabel();
            view.clearOutput();
            if (systemNotice != null && !systemNotice.isEmpty()) {
                view.appendMessage(ROLE_SYSTEM, systemNotice);
            }
        });
    }

    public void triggerTurn(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        cancelActiveTurnState(false);
        long turnId = turnSequence.incrementAndGet();
        activeTurnId = turnId;

        view.setLoading(true);
        model.setCurrentTurn(0);
        view.updateTurnLabel();

        List<java.awt.image.BufferedImage> pendingImages = model.drainPendingImages();
        view.clearAttachments();

        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                List<byte[]> imageBytes = ChatModel.encodeImagesToPngBytes(pendingImages);
                WorkflowPhase activePhase = model.resolveActivePhase(planService.getCurrentPhase());
                String response = aiService.chat(model.getConversationId(), activePhase, text, imageBytes);
                if (Thread.currentThread().isInterrupted() || model.isTurnCancelled()) {
                    Edt.run(() -> finishTurnCancelled(turnId));
                    return;
                }
                Edt.run(() -> finishTurn(turnId, ROLE_AI, response));
            } catch (Exception ex) {
                if (ExceptionUtils.isCancellation(ex) || model.isTurnCancelled()) {
                    Edt.run(() -> finishTurnCancelled(turnId));
                    return;
                }
                log.error("AI chat interaction failed: {}", ex.getMessage(), ex);
                String errorMsg = "Error: " + ExceptionUtils.getMessageOrSimpleName(ex);
                Edt.run(() -> finishTurn(turnId, ROLE_AI, errorMsg));
            }
        }, null);
        activeTurnFuture = task;
        turnExecutor.execute(task);
    }

    private void finishTurn(long turnId, String role, String message) {
        if (activeTurnId != turnId || model.isTurnCancelled()) {
            return;
        }
        activeTurnId = 0;
        activeTurnFuture = null;
        WorkflowTransitionEvent transition = deferredTransitionEvent;
        deferredTransitionEvent = null;

        view.appendMessage(role, message);
        if (transition != null && !model.isTurnCancelled()) {
            view.setLoading(false);
            applyWorkflowTransition(transition);
        } else {
            view.setLoading(false);
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
        if (event == null || !model.isTaskMode()) {
            return;
        }
        if (!event.autonomous() && event.to() == null) {
            deferredTransitionEvent = null;
            Edt.run(view::hideTransitionBanner);
            return;
        }
        if (model.isTurnCancelled()) {
            return;
        }
        Edt.run(() -> {
            if (model.isTurnCancelled()) {
                return;
            }
            if (isTurnActive()) {
                deferredTransitionEvent = event;
            } else {
                applyWorkflowTransition(event);
            }
        });
    }

    private void applyWorkflowTransition(WorkflowTransitionEvent event) {
        if (model.isTurnCancelled() || event.to() == null) {
            return;
        }
        if (event.autonomous()) {
            if (event.to() == WorkflowPhase.PLAN) {
                view.appendMessage(ROLE_SYSTEM, "Task complete. What is the next feature you would like to work on?");
            } else {
                triggerTurn("Phase changed to " + event.to() + ". Please proceed with the tasks in the new phase.");
            }
        } else {
            view.showTransitionBanner(PlanService.formatApprovalLabel(event.from(), event.to()));
        }
    }

    private boolean isMatchingActiveTurnEvent(String conversationId) {
        return model.getConversationId().equals(conversationId) && !model.isTurnCancelled();
    }

    void onAgentTurn(AgentTurnEvent event) {
        if (event == null || !isMatchingActiveTurnEvent(event.conversationId())) {
            return;
        }
        Edt.run(() -> {
            if (!model.isTurnCancelled()) {
                model.setCurrentTurn(event.turnNumber());
                view.updateTurnLabel();
            }
        });
    }

    void onToolExecution(ToolExecutionEvent event) {
        if (event == null || !isMatchingActiveTurnEvent(event.conversationId())) {
            return;
        }
        Edt.run(() -> {
            if (!model.isTurnCancelled()) {
                view.appendToolLog(JexlToHtmlConverter.convert(event.script()), event.success(), event.error());
            }
        });
    }

    void onAgentTurnComplete(AgentTurnCompleteEvent event) {
        if (event == null || !model.getConversationId().equals(event.conversationId())) {
            return;
        }
        Edt.run(() -> {
            if (!isTurnActive()) {
                view.setLoading(false);
            }
        });
    }

    void onSettingsChanged(SettingsChangedEvent event) {
        Edt.run(view::updateTurnLabel);
    }

    void onPlanUpdated(PlanUpdatedEvent event) {
        if (model.isTaskMode()) {
            Edt.run(view::updateTurnLabel);
        }
    }

    void onPhaseChanged(PhaseChangedEvent event) {
        if (event == null) {
            return;
        }
        log.debug("Phase changed to: {}", event.phase());
        Edt.run(() -> {
            if (model.isTaskMode()) {
                view.hideTransitionBanner();
                view.updateTurnLabel();
            }
        });
    }
}
