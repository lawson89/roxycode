package org.roxycode.app.ui;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.Timer;
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
import org.roxycode.app.events.UserMessageEvent;
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

/** Controller coordinating chat turns, service events, and workflow transitions. */
@SuppressWarnings({"PMD.NullAssignment", "PMD.CloseResource"})
public final class ChatController implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    static final String ROLE_SYSTEM = "system";
    static final String ROLE_USER = "user";
    static final String ROLE_AI = "ai";
    static final String IMAGE_REQUIRES_TEXT_MESSAGE =
            "Please enter a prompt message to accompany the attached image.";
    static final int MAX_AUTO_NUDGES = 2;
    static final String AUTO_NUDGE_PROMPT =
            "Roxy, you completed tool turns but didn't provide a final summary or answer to the user. "
                    + "Please review the tool outputs above and provide a concise summary of your actions and findings.";
    static final int DEFAULT_ERROR_NUDGE_DELAY_MS = 1000;
    private static final int MAX_EXCERPT_ENTRIES = 8;
    private static final int MAX_ENTRY_CHARS = 500;

    private final ChatModel model;
    private final ChatPanel view;
    private final AiService aiService;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatMemory chatMemory;
    private final SettingsService settingsService;
    private final PlanService planService;
    private MemoryService memoryService;
    private EnvironmentService environmentService;
    private final boolean turnExecutorOwned;
    private volatile Executor turnExecutor;
    private final AtomicLong turnSequence = new AtomicLong();

    private volatile long activeTurnId = 0;
    private volatile Future<?> activeTurnFuture;
    private volatile WorkflowTransitionEvent deferredTransitionEvent;
    private int consecutiveAutoNudges = 0;
    private int errorNudgeDelayMs = DEFAULT_ERROR_NUDGE_DELAY_MS;
    private volatile Timer autoNudgeTimer;

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
        this.turnExecutorOwned = turnExecutor == null;
        this.turnExecutor =
                turnExecutor != null ? turnExecutor : Executors.newVirtualThreadPerTaskExecutor();
    }

    void setTopicArchiveServices(
            MemoryService memoryService, EnvironmentService environmentService) {
        this.memoryService = memoryService;
        this.environmentService = environmentService;
    }

    Executor getTurnExecutor() {
        return turnExecutor;
    }

    boolean ownsTurnExecutor() {
        return turnExecutorOwned;
    }

    synchronized void ensureOwnedExecutorActive() {
        if (turnExecutorOwned
                && turnExecutor instanceof ExecutorService executorService
                && executorService.isShutdown()) {
            turnExecutor = Executors.newVirtualThreadPerTaskExecutor();
        }
    }

    synchronized void closeOwnedExecutor() {
        if (turnExecutorOwned) {
            close();
        }
    }

    long getActiveTurnId() {
        return activeTurnId;
    }

    int getConsecutiveAutoNudges() {
        return consecutiveAutoNudges;
    }

    void setErrorNudgeDelayMs(int errorNudgeDelayMs) {
        this.errorNudgeDelayMs = Math.max(0, errorNudgeDelayMs);
    }

    int getErrorNudgeDelayMs() {
        return errorNudgeDelayMs;
    }

    Timer getAutoNudgeTimer() {
        return autoNudgeTimer;
    }

    private boolean cancelAutoNudgeTimer() {
        Timer timer = autoNudgeTimer;
        if (timer != null) {
            autoNudgeTimer = null;
            boolean wasRunning = timer.isRunning();
            timer.stop();
            return wasRunning;
        }
        return false;
    }

    @Override
    public void close() {
        consecutiveAutoNudges = 0;
        cancelAutoNudgeTimer();
        if (turnExecutor instanceof ExecutorService executorService) {
            executorService.close();
        }
    }

    public boolean isTurnActive() {
        return (activeTurnId != 0 && activeTurnFuture != null)
                || (autoNudgeTimer != null && autoNudgeTimer.isRunning());
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
        if (text.isEmpty()) {
            if (model.hasPendingImages()) {
                view.appendMessage(ROLE_SYSTEM, IMAGE_REQUIRES_TEXT_MESSAGE);
            }
            return;
        }

        consecutiveAutoNudges = 0;
        cancelAutoNudgeTimer();
        if (eventPublisher != null) {
            eventPublisher.publishEvent(
                    new UserMessageEvent(model.getConversationId(), ROLE_USER, text));
        }
        view.appendMessage(ROLE_USER, text);
        view.clearInputText();
        triggerTurn(text);
    }

    private boolean cancelActiveTurnState(boolean markCancelled) {
        model.setTurnCancelled(markCancelled);
        if (markCancelled) {
            consecutiveAutoNudges = 0;
        }
        boolean hadPendingNudge = cancelAutoNudgeTimer();
        deferredTransitionEvent = null;
        activeTurnId = 0;
        Future<?> futureToCancel = activeTurnFuture;
        if (futureToCancel != null) {
            activeTurnFuture = null;
            futureToCancel.cancel(true);
        }
        return futureToCancel != null || hadPendingNudge;
    }

    public void stopTurn() {
        if (cancelActiveTurnState(true)) {
            Edt.run(
                    () -> {
                        view.appendMessage(ROLE_SYSTEM, "_Request cancelled by user._");
                        view.setLoading(false);
                    });
        }
    }

    public void approvePhase() {
        WorkflowPhase current = planService.getCurrentPhase();
        WorkflowPhase requested = planService.getPendingPhase();
        if (requested != null) {
            cancelActiveTurnState(false);
            consecutiveAutoNudges = 0;
            Edt.run(view::hideTransitionBanner);
            turnExecutor.execute(
                    () -> {
                        planService.approveTransition();
                        Edt.run(
                                () -> {
                                    view.appendMessage(
                                            ROLE_SYSTEM,
                                            "Transition to **"
                                                    + requested.getDisplayName()
                                                    + "** approved.");
                                    if (current == WorkflowPhase.REVIEW
                                            && requested == WorkflowPhase.PLAN) {
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
                                });
                    });
        }
    }

    public void rejectPhase() {
        WorkflowPhase requested = planService.getPendingPhase();
        if (requested != null) {
            cancelActiveTurnState(false);
            consecutiveAutoNudges = 0;
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
        if (!model.isTaskMode()) {
            performNewTopicReset();
            return;
        }
        resetSessionState(true, "Session reset.");
    }

    public void resetForProjectChange() {
        resetSessionState(false, null);
    }

    private void performNewTopicReset() {
        List<ChatTranscriptEntry> capturedTranscript = view.getConversationMessages();
        boolean shouldArchive = !capturedTranscript.isEmpty() && memoryService != null;
        resetSessionState(true, shouldArchive ? null : "Started a new topic.");
        if (!shouldArchive) {
            return;
        }
        turnExecutor.execute(
                () -> {
                    String excerpt = buildTranscriptExcerpt(capturedTranscript);
                    String title = null;
                    if (aiService != null) {
                        try {
                            title = aiService.generateTopicTitle(excerpt);
                        } catch (Exception e) {
                            log.warn("Failed to generate topic title: {}", e.getMessage());
                        }
                    }
                    if (title == null || title.isBlank()) {
                        title = AiService.deriveFallbackTopicTitle(excerpt);
                    }
                    String user =
                            (environmentService != null
                                            && environmentService.getCurrentUser() != null
                                            && !environmentService.getCurrentUser().isBlank())
                                    ? environmentService.getCurrentUser()
                                    : "User";
                    String filename =
                            memoryService.archiveTopicConversation(
                                    title, user, Instant.now().toString(), capturedTranscript);
                    if (filename != null && !filename.isBlank() && eventPublisher != null) {
                        eventPublisher.publishEvent(
                                new ArchiveUpdatedEvent(ArchiveUpdatedEvent.TYPE_TOPIC, filename));
                    }
                    String finalTitle = title;
                    Edt.run(
                            () -> {
                                if (filename != null && !filename.isBlank()) {
                                    view.appendMessage(
                                            ROLE_SYSTEM,
                                            "Archived previous topic as **"
                                                    + finalTitle
                                                    + "** (`"
                                                    + filename
                                                    + "`) and started a new topic.");
                                } else {
                                    view.appendMessage(ROLE_SYSTEM, "Started a new topic.");
                                }
                            });
                });
    }

    static String buildTranscriptExcerpt(List<ChatTranscriptEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int count = Math.min(entries.size(), MAX_EXCERPT_ENTRIES);
        for (int i = 0; i < count; i++) {
            ChatTranscriptEntry entry = entries.get(i);
            String speaker = ROLE_USER.equalsIgnoreCase(entry.role()) ? "User" : "Roxy";
            String content = entry.content() != null ? entry.content().trim() : "";
            if (content.length() > MAX_ENTRY_CHARS) {
                content = content.substring(0, MAX_ENTRY_CHARS) + "...";
            }
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(speaker).append(": ").append(content);
        }
        return sb.toString();
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
        Edt.run(
                () -> {
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
        if (text == null || text.trim().isEmpty()) return;

        cancelActiveTurnState(false);

        long turnId = turnSequence.incrementAndGet();
        activeTurnId = turnId;

        view.setLoading(true);
        model.setCurrentTurn(0);
        view.updateTurnLabel();

        List<java.awt.image.BufferedImage> pendingImages = model.drainPendingImages();
        view.clearAttachments();

        FutureTask<Void> task =
                new FutureTask<>(
                        () -> {
                            try {
                                List<byte[]> imageBytes =
                                        ChatModel.encodeImagesToPngBytes(pendingImages);
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
                                    Edt.run(() -> finishTurnCancelled(turnId));
                                    return;
                                }
                                Edt.run(() -> finishTurnSuccess(turnId, response));
                            } catch (Exception ex) {
                                if (ExceptionUtils.isCancellation(ex) || model.isTurnCancelled()) {
                                    Edt.run(() -> finishTurnCancelled(turnId));
                                    return;
                                }
                                log.error("AI chat interaction failed: {}", ex.getMessage(), ex);
                                String errorMsg =
                                        "Error: "
                                                + (ex.getMessage() != null
                                                        ? ex.getMessage()
                                                        : ex.getClass().getSimpleName());
                                Edt.run(() -> finishTurnError(turnId, errorMsg));
                            }
                        },
                        null);
        activeTurnFuture = task;
        turnExecutor.execute(task);
    }

    private void finishTurnSuccess(long turnId, String response) {
        finishTurn(turnId, ROLE_AI, response, shouldAutoNudgeOnResponse(response), 0);
    }

    private void finishTurnError(long turnId, String errorMsg) {
        finishTurn(turnId, ROLE_AI, errorMsg, shouldAutoNudgeOnError(), errorNudgeDelayMs);
    }

    private void finishTurn(
            long turnId, String role, String message, boolean autoNudge, int nudgeDelayMs) {
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
            return;
        }

        if (autoNudge) {
            scheduleAutoNudge(nudgeDelayMs);
            return;
        }

        view.setLoading(false);
    }

    private boolean shouldAutoNudgeOnError() {
        return !model.isTurnCancelled()
                && !hasPendingPhase()
                && model.getCurrentTurn() > 0
                && consecutiveAutoNudges < MAX_AUTO_NUDGES;
    }

    private boolean shouldAutoNudgeOnResponse(String response) {
        if (model.isTurnCancelled()
                || hasPendingPhase()
                || consecutiveAutoNudges >= MAX_AUTO_NUDGES) {
            return false;
        }
        return AiService.isAutoNudgeableResponse(response, model.getCurrentTurn() > 0);
    }

    private void scheduleAutoNudge(int delayMs) {
        cancelAutoNudgeTimer();
        consecutiveAutoNudges++;
        view.appendMessage(
                ROLE_SYSTEM,
                "_Auto-nudging Roxy to continue ("
                        + consecutiveAutoNudges
                        + "/"
                        + MAX_AUTO_NUDGES
                        + ")..._");
        if (delayMs <= 0) {
            triggerTurn(AUTO_NUDGE_PROMPT);
            return;
        }
        view.setLoading(true);
        Timer timer =
                new Timer(
                        delayMs,
                        e -> {
                            autoNudgeTimer = null;
                            if (!model.isTurnCancelled()) {
                                triggerTurn(AUTO_NUDGE_PROMPT);
                            }
                        });
        timer.setRepeats(false);
        autoNudgeTimer = timer;
        timer.start();
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
            Edt.run(view::hideTransitionBanner);
            return;
        }
        if (model.isTurnCancelled()) return;
        Edt.run(
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
            String label = PlanService.formatApprovalLabel(event.from(), event.to());
            view.showTransitionBanner(label);
        }
    }

    void onAgentTurn(AgentTurnEvent event) {
        if (event == null
                || !model.getConversationId().equals(event.conversationId())
                || model.isTurnCancelled()) {
            return;
        }
        Edt.run(
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
        Edt.run(
                () -> {
                    if (model.isTurnCancelled()) return;
                    String output = JexlToHtmlConverter.convert(event.script());
                    view.appendToolLog(output, event.success(), event.error());
                });
    }

    void onAgentTurnComplete(AgentTurnCompleteEvent event) {
        if (event == null || !model.getConversationId().equals(event.conversationId())) {
            return;
        }
        Edt.run(
                () -> {
                    if (!isTurnActive()) {
                        view.setLoading(false);
                    }
                });
    }

    void onSettingsChanged(SettingsChangedEvent event) {
        Edt.run(view::updateTurnLabel);
    }

    void onPlanUpdated(PlanUpdatedEvent event) {
        if (!model.isTaskMode()) return;
        Edt.run(view::updateTurnLabel);
    }

    void onPhaseChanged(PhaseChangedEvent event) {
        if (event == null) {
            return;
        }
        log.debug("Phase changed to: {}", event.phase());
        Edt.run(
                () -> {
                    if (model.isTaskMode()) {
                        view.hideTransitionBanner();
                        view.updateTurnLabel();
                    }
                });
    }
}
