package org.roxycode.app.ui;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.MemoryService.MemoryEntry;
import org.roxycode.app.ai.services.MemoryService.MemoryMetadata;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.UserInputService;
import org.springframework.lang.Nullable;
import org.roxycode.app.events.AgentActivityEvent;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.ArchiveUpdatedEvent;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.events.TokenUsageEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.events.UserInputRequestEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.SettingsService;
import java.awt.image.BufferedImage;
import org.roxycode.app.model.ChatTranscriptEntry;
import org.roxycode.app.ui.ChatModel.PendingAttachment;
import org.roxycode.app.ui.MarkdownPane.PersistedChatEntry;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.ExceptionUtils;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

/**
 * Coordinates asynchronous AI chat turns, tool execution telemetry, inline user input prompts, workflow phase
 * transitions, and session persistence between {@link ChatModel}, {@link ChatPanel}, and backend AI services.
 */
@SuppressWarnings({"PMD.NullAssignment", "PMD.CloseResource"})
final class ChatController implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    static final String ROLE_SYSTEM = ChatHtmlTemplates.ROLE_SYSTEM;
    static final String ROLE_USER = ChatHtmlTemplates.ROLE_USER;
    static final String ROLE_AI = ChatHtmlTemplates.ROLE_AI;
    private static final String CMD_RESET = "/reset";
    private static final String CMD_CLEAR = "/clear";
    private static final String CMD_HELP = "/help";
    private static final String CMD_MEM_P_VIEW = "/mem-p-view";
    private static final String CMD_MEM_P_PUT = "/mem-p-put";
    private static final String CMD_MEM_P_FIND = "/mem-p-find";
    private static final String CMD_MEM_P_DEL = "/mem-p-del";
    private static final String CMD_MEM_G_VIEW = "/mem-g-view";
    private static final String CMD_MEM_G_PUT = "/mem-g-put";
    private static final String CMD_MEM_G_FIND = "/mem-g-find";
    private static final String CMD_MEM_G_DEL = "/mem-g-del";
    static final String IMAGE_REQUIRES_TEXT_MESSAGE = "Please enter a prompt message to accompany the attached image.";
    static final String INITIAL_ACTIVITY_STATUS = "Preparing prompt Context & Repo Map...";
    private static final String BT = "`";
    public record PersistedMemoryMessage(String type, String text) {
    }

    public record ActiveChatSessionState(List<PersistedChatEntry> uiEntries,
            List<PersistedMemoryMessage> memoryMessages, long lastPromptTokens, long lastCachedInputTokens,
            int lastContextWindowTokens) {
    }

    private final ChatModel model;
    private final ChatPanel view;
    private final AiService aiService;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatMemory chatMemory;
    private final SettingsService settingsService;
    private ProjectService projectService;
    private final PlanService planService;
    private static final int MAX_PRELOADED_REVIEW_DIFF_CHARS = 30_000;
    private static final String CODE_FENCE = "```";
    private static final String REVIEW_DIFF_TRUNCATION_NOTICE = "\n[... diff truncated; use gitService.getDiff(relativePath) to inspect remaining files ...]";
    private MemoryService memoryService;
    private EnvironmentService environmentService;
    private UserInputService userInputService;
    private GitService gitService;
    final boolean turnExecutorOwned;
    volatile Executor turnExecutor;
    private final AtomicLong turnSequence = new AtomicLong();

    volatile long activeTurnId = 0;
    private volatile Future<?> activeTurnFuture;
    private volatile CompletableFuture<Object> pendingUserInputFuture;
    private volatile WorkflowTransitionEvent deferredTransitionEvent;
    private final Object persistLock = new Object();
    private CompletableFuture<Void> lastPersistFuture = CompletableFuture.completedFuture(null);

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

    void setProjectService(ProjectService projectService) {
        this.projectService = projectService;
        restoreSessionForCurrentProject();
    }

    void setTopicArchiveServices(MemoryService memoryService, EnvironmentService environmentService) {
        this.memoryService = memoryService;
        this.environmentService = environmentService;
    }

    void setUserInputService(UserInputService userInputService) {
        this.userInputService = userInputService;
    }

    void setGitService(@Nullable GitService gitService) {
        this.gitService = gitService;
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

    public boolean isWaitingUserInput() {
        CompletableFuture<Object> future = pendingUserInputFuture;
        if (future != null && !future.isDone()) {
            return true;
        }
        return false;
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
            if (model.hasPendingAttachments()) {
                view.appendMessage(ROLE_SYSTEM, IMAGE_REQUIRES_TEXT_MESSAGE);
            }
            return;
        }
        if (text.startsWith("/")) {
            view.clearInputText();
            handleSlashCommand(text);
            return;
        }
        if (model.isTaskMode() && planService != null && planService.getPendingPhase() != null) {
            WorkflowPhase currentPhase = planService.getCurrentPhase();
            WorkflowPhase pendingPhase = planService.getPendingPhase();
            if (currentPhase == WorkflowPhase.PLAN && pendingPhase == WorkflowPhase.CODE) {
                planService.rejectTransition();
                view.hideTransitionBanner();
            } else if (currentPhase == WorkflowPhase.REVIEW && pendingPhase == WorkflowPhase.PLAN) {
                planService.rejectCompletionAndReturnToCode(text);
                view.hideTransitionBanner();
            }
        }
        List<PendingAttachment> pendingAttachments = model.drainPendingAttachments();
        view.clearAttachments();
        List<BufferedImage> pendingImages = ChatModel.extractAttachedImages(pendingAttachments);
        List<String> thumbnailBase64List = UiComponents.encodeThumbnailsToBase64Png(pendingImages,
                UiComponents.CHAT_THUMBNAIL_MAX_DIM);
        List<String> attachedFileNames = ChatModel.extractAttachedFileNames(pendingAttachments);
        view.hideUserInputPrompt();
        view.appendMessage(ROLE_USER, text, thumbnailBase64List, attachedFileNames);
        persistActiveChatSession();
        view.clearInputText();

        triggerTurn(text, pendingAttachments);
    }

    private void handleSlashCommand(String text) {
        String command = StringUtils.substringBefore(text, " ");
        String argument = StringUtils.substringAfter(text, " ").trim();
        if (argument.isEmpty() && CMD_RESET.equals(command)) {
            resetSession();
            return;
        }
        if (argument.isEmpty() && CMD_CLEAR.equals(command)) {
            view.clearOutput();
            persistActiveChatSession();
            return;
        }
        if (argument.isEmpty() && CMD_HELP.equals(command)) {
            String helpItem = "*   ";
            String helpText = "### Available Commands\n" + helpItem + BT + CMD_RESET + BT
                    + ": Reset the current session or topic.\n" + helpItem + BT + CMD_CLEAR + BT
                    + ": Clear the chat output area.\n" + helpItem + BT + CMD_HELP + BT + ": Show this help message.\n"
                    + helpItem + BT + CMD_MEM_P_VIEW + " [id]" + BT
                    + ": List project memories or view a specific entry.\n" + helpItem + BT + CMD_MEM_P_PUT
                    + " [<id>:] <summary>" + BT + ": Save a project memory.\n" + helpItem + BT + CMD_MEM_P_FIND
                    + " <query>" + BT + ": Search project memories.\n" + helpItem + BT + CMD_MEM_P_DEL + " <id>" + BT
                    + ": Delete a project memory.\n" + helpItem + BT + CMD_MEM_G_VIEW + " [id]" + BT
                    + ": List global memories or view a specific entry.\n" + helpItem + BT + CMD_MEM_G_PUT
                    + " [<id>:] <summary>" + BT + ": Save a global memory.\n" + helpItem + BT + CMD_MEM_G_FIND
                    + " <query>" + BT + ": Search global memories.\n" + helpItem + BT + CMD_MEM_G_DEL + " <id>" + BT
                    + ": Delete a global memory.";
            view.appendMessage(ROLE_SYSTEM, helpText);
            return;
        }
        if (CMD_MEM_P_VIEW.equals(command)) {
            handleMemoryViewCommand(MemoryService.SCOPE_PROJECT, argument);
            return;
        }
        if (CMD_MEM_G_VIEW.equals(command)) {
            handleMemoryViewCommand(MemoryService.SCOPE_GLOBAL, argument);
            return;
        }
        if (CMD_MEM_P_PUT.equals(command)) {
            handleMemoryPutCommand(MemoryService.SCOPE_PROJECT, argument, CMD_MEM_P_PUT);
            return;
        }
        if (CMD_MEM_G_PUT.equals(command)) {
            handleMemoryPutCommand(MemoryService.SCOPE_GLOBAL, argument, CMD_MEM_G_PUT);
            return;
        }
        if (CMD_MEM_P_FIND.equals(command)) {
            handleMemoryFindCommand(MemoryService.SCOPE_PROJECT, argument, CMD_MEM_P_FIND);
            return;
        }
        if (CMD_MEM_G_FIND.equals(command)) {
            handleMemoryFindCommand(MemoryService.SCOPE_GLOBAL, argument, CMD_MEM_G_FIND);
            return;
        }
        if (CMD_MEM_P_DEL.equals(command)) {
            handleMemoryDeleteCommand(MemoryService.SCOPE_PROJECT, argument, CMD_MEM_P_DEL);
            return;
        }
        if (CMD_MEM_G_DEL.equals(command)) {
            handleMemoryDeleteCommand(MemoryService.SCOPE_GLOBAL, argument, CMD_MEM_G_DEL);
            return;
        }
        view.appendMessage(ROLE_SYSTEM,
                "Unknown command: " + text + ". Type " + BT + CMD_HELP + BT + " for available commands.");
    }

    private boolean ensureMemoryServiceAvailable() {
        if (memoryService == null) {
            view.appendMessage(ROLE_SYSTEM, "Memory service is not available.");
            return false;
        }
        return true;
    }

    private void handleMemoryViewCommand(String scope, String idArg) {
        if (!ensureMemoryServiceAvailable()) {
            return;
        }
        if (idArg.isEmpty()) {
            List<MemoryMetadata> entries = memoryService.list(scope);
            if (entries.isEmpty()) {
                view.appendMessage(ROLE_SYSTEM, "No " + scope + " memories saved.");
                return;
            }
            StringBuilder sb = new StringBuilder("### ").append(StringUtils.capitalize(scope)).append(" Memories (")
                    .append(entries.size()).append(")\n");
            for (MemoryMetadata entry : entries) {
                sb.append(formatMemoryListLine(entry)).append("\n");
            }
            view.appendMessage(ROLE_SYSTEM, sb.toString().stripTrailing());
            return;
        }
        try {
            MemoryEntry entry = memoryService.read(scope, idArg);
            if (entry == null) {
                view.appendMessage(ROLE_SYSTEM, "Memory not found in " + scope + " scope: " + BT + idArg + BT);
                return;
            }
            StringBuilder sb = new StringBuilder("### ").append(StringUtils.capitalize(scope)).append(" Memory: ")
                    .append(BT).append(entry.id()).append(BT).append("\n");
            if (!entry.tags().isEmpty()) {
                sb.append("**Tags:** ").append(MemoryService.formatTagsDisplay(entry.tags())).append("\n");
            }
            sb.append("**Summary:** ").append(entry.summary());
            if (!entry.details().isBlank()) {
                sb.append("\n\n").append(entry.details());
            }
            view.appendMessage(ROLE_SYSTEM, sb.toString());
        } catch (IllegalArgumentException e) {
            view.appendMessage(ROLE_SYSTEM, "Invalid memory id: " + e.getMessage());
        }
    }

    private void handleMemoryPutCommand(String scope, String argument, String commandName) {
        if (!ensureMemoryServiceAvailable()) {
            return;
        }
        if (argument.isEmpty()) {
            view.appendMessage(ROLE_SYSTEM,
                    "Please provide a summary. Usage: " + BT + commandName + " [<id>:] <summary>" + BT);
            return;
        }
        String id;
        String summary;
        int colonIdx = argument.indexOf(':');
        if (colonIdx > 0) {
            String candidateId = argument.substring(0, colonIdx).trim();
            String remainder = argument.substring(colonIdx + 1).trim();
            if (!candidateId.isEmpty() && !remainder.isEmpty() && candidateId.matches("[a-zA-Z0-9_-]+")) {
                id = MemoryService.sanitizeSlug(candidateId);
                summary = remainder;
            } else {
                summary = argument;
                id = MemoryService.deriveSlugFromSummary(summary);
            }
        } else {
            summary = argument;
            id = MemoryService.deriveSlugFromSummary(summary);
        }

        EditorResult result = memoryService.put(scope, id, summary);
        if (result.success()) {
            view.appendMessage(ROLE_SYSTEM, "Saved " + scope + " memory " + BT + id + BT + ": " + summary);
            persistActiveChatSession();
        } else {
            view.appendMessage(ROLE_SYSTEM, "Failed to save " + scope + " memory: " + result.errorHint());
        }
    }

    private void handleMemoryFindCommand(String scope, String query, String commandName) {
        if (!ensureMemoryServiceAvailable()) {
            return;
        }
        if (query.isEmpty()) {
            view.appendMessage(ROLE_SYSTEM,
                    "Please provide a search query. Usage: " + BT + commandName + " <query>" + BT);
            return;
        }
        List<MemoryMetadata> matches = memoryService.find(scope, query);
        if (matches.isEmpty()) {
            view.appendMessage(ROLE_SYSTEM, "No " + scope + " memories matching " + BT + query + BT + ".");
            return;
        }
        StringBuilder sb = new StringBuilder("### Matching ").append(StringUtils.capitalize(scope))
                .append(" Memories (").append(matches.size()).append(")\n");
        for (MemoryMetadata entry : matches) {
            sb.append(formatMemoryListLine(entry)).append("\n");
        }
        view.appendMessage(ROLE_SYSTEM, sb.toString().stripTrailing());
    }

    private void handleMemoryDeleteCommand(String scope, String idArg, String commandName) {
        if (!ensureMemoryServiceAvailable()) {
            return;
        }
        if (idArg.isEmpty()) {
            view.appendMessage(ROLE_SYSTEM, "Please provide a memory id. Usage: " + BT + commandName + " <id>" + BT);
            return;
        }
        try {
            String sanitizedId = MemoryService.sanitizeSlug(idArg);
            EditorResult result = memoryService.delete(scope, sanitizedId);
            if (result.success()) {
                view.appendMessage(ROLE_SYSTEM, "Deleted " + scope + " memory " + BT + sanitizedId + BT + ".");
                persistActiveChatSession();
            } else {
                view.appendMessage(ROLE_SYSTEM, "Failed to delete " + scope + " memory: " + result.errorHint());
            }
        } catch (IllegalArgumentException e) {
            view.appendMessage(ROLE_SYSTEM, "Invalid memory id: " + e.getMessage());
        }
    }

    private static String formatMemoryListLine(MemoryMetadata entry) {
        StringBuilder line = new StringBuilder("- ").append(BT).append(entry.id()).append(BT);
        if (!entry.tags().isEmpty()) {
            line.append(" [").append(MemoryService.formatTagsDisplay(entry.tags())).append("]");
        }
        if (entry.hasDetails()) {
            line.append(" (+details)");
        }
        line.append(": ").append(entry.summary());
        return line.toString();
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
        boolean promptCancelled = cancelPendingUserInput();
        return futureToCancel != null || promptCancelled;
    }

    private boolean cancelPendingUserInput() {
        boolean cancelled = false;
        CompletableFuture<Object> future = pendingUserInputFuture;
        if (future != null) {
            pendingUserInputFuture = null;
            if (!future.isDone()) {
                future.cancel(true);
                cancelled = true;
            }
        }
        if (userInputService != null && userInputService.cancelPendingRequest(model.getConversationId())) {
            cancelled = true;
        }
        Edt.run(view::hideUserInputPrompt);
        return cancelled;
    }

    public void stopTurn() {
        boolean hadActiveTurn = cancelActiveTurnState(true);
        if (hadActiveTurn || view.isLoading()) {
            Edt.run(() -> {
                if (hadActiveTurn) {
                    view.appendMessage(ROLE_SYSTEM, "_Request cancelled by user._");
                    persistActiveChatSession();
                }
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
                    persistActiveChatSession();
                } else {
                    persistActiveChatSession();
                    triggerTurn("I approve the transition to " + requested.getDisplayName()
                            + ". Please proceed with the next phase.");
                }
            });
        });
    }

    public void rejectPhase() {
        WorkflowPhase current = planService.getCurrentPhase();
        WorkflowPhase requested = planService.getPendingPhase();
        if (requested == null) {
            return;
        }
        cancelActiveTurnState(false);
        view.hideTransitionBanner();
        if (current == WorkflowPhase.PLAN && requested == WorkflowPhase.CODE) {
            planService.rejectTransition();
            view.appendMessage(ROLE_SYSTEM,
                    "Transition to **Code** rejected. Type your feedback below to revise the plan.");
            view.setInputPlaceholderText("Describe what to adjust in the plan...");
        } else if (current == WorkflowPhase.REVIEW && requested == WorkflowPhase.PLAN) {
            planService.rejectCompletionAndReturnToCode("User rejected completion.");
            view.appendMessage(ROLE_SYSTEM,
                    "Completion rejected — returned to **Code** phase. Describe the changes needed below.");
        } else {
            planService.rejectTransition();
            view.appendMessage(ROLE_SYSTEM, "Transition to **" + requested.getDisplayName() + "** rejected.");
        }
        persistActiveChatSession();
        view.focusInput();
    }

    public void resetSession() {
        if (!model.isTaskMode()) {
            performNewTopicReset(true, null);
            return;
        }
        view.showResetSessionPrompt(this::performResetSession);
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
        String title = MemoryService.cleanTopicTitle(customTopicTitle);
        if (title.isEmpty()) {
            if (aiService != null) {
                title = aiService.generateTopicTitle(capturedTranscript);
            } else {
                title = MemoryService.deriveDefaultTopicTitle(capturedTranscript);
            }
        }
        String user = EnvironmentService.resolveCurrentUser(environmentService);
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
        model.clearPendingAttachments();
        model.setCurrentTurn(0);
        resetTelemetry();
        if (clearMemory) {
            if (chatMemory != null) {
                chatMemory.clear(model.getConversationId());
            }
            Path sessionPath = resolveActiveChatSessionPath();
            if (sessionPath != null) {
                CompletableFuture<Void> deleteFuture = enqueueSessionDiskOperation(sessionPath, null, true);
                if (!SwingUtilities.isEventDispatchThread()) {
                    deleteFuture.join();
                }
            }
        }
        Edt.run(() -> {
            view.clearAttachments();
            view.hideUserInputPrompt();
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
        List<PendingAttachment> pendingAttachments = model.drainPendingAttachments();
        view.clearAttachments();
        triggerTurn(text, pendingAttachments);
    }

    private void triggerTurn(String text, List<PendingAttachment> pendingAttachments) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        triggerTurn(() -> text, pendingAttachments);
    }

    private void triggerTurn(Supplier<String> promptSupplier, List<PendingAttachment> pendingAttachments) {
        if (promptSupplier == null) {
            return;
        }

        cancelActiveTurnState(false);
        long turnId = turnSequence.incrementAndGet();
        activeTurnId = turnId;

        view.setLoading(true);
        view.updateActivityStatus(INITIAL_ACTIVITY_STATUS);
        model.setCurrentTurn(0);
        view.updateTurnLabel();

        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                String rawPromptText = promptSupplier.get();
                if (rawPromptText == null || rawPromptText.trim().isEmpty()) {
                    Edt.run(() -> finishTurnCancelled(turnId));
                    return;
                }
                String promptText = ChatModel.formatPromptWithTextAttachments(rawPromptText, pendingAttachments);
                List<byte[]> imageBytes = ChatModel.encodeImageAttachments(pendingAttachments);
                List<byte[]> pdfBytes = ChatModel.encodePdfAttachments(pendingAttachments);
                WorkflowPhase activePhase = model.resolveActivePhase(planService.getCurrentPhase());
                String response = aiService.chat(model.getConversationId(), activePhase, promptText, imageBytes,
                        pdfBytes);
                if (Thread.currentThread().isInterrupted() || model.isTurnCancelled()) {
                    Edt.run(() -> finishTurnCancelled(turnId));
                    return;
                }
                String renderedHtml = MarkdownPane.renderMarkdownBodySafely(response);
                String extractedToml = view.outputArea.resolveExtractedBuildToml(ROLE_AI, response);
                Edt.run(() -> finishTurn(turnId, ROLE_AI, response, renderedHtml, extractedToml));
            } catch (Exception ex) {
                if (ExceptionUtils.isCancellation(ex) || model.isTurnCancelled()) {
                    Edt.run(() -> finishTurnCancelled(turnId));
                    return;
                }
                log.error("AI chat interaction failed: {}", ex.getMessage(), ex);
                String errorMsg = "Error: " + ExceptionUtils.getRootCauseMessage(ex);
                String renderedHtml = MarkdownPane.renderMarkdownBodySafely(errorMsg);
                Edt.run(() -> finishTurn(turnId, ROLE_AI, errorMsg, renderedHtml, null));
            }
        }, null);
        activeTurnFuture = task;
        turnExecutor.execute(task);
    }

    private void finishTurn(long turnId, String role, String message, String renderedHtml, String extractedToml) {
        if (activeTurnId != turnId || model.isTurnCancelled()) {
            return;
        }
        activeTurnId = 0;
        activeTurnFuture = null;
        cancelPendingUserInput();
        WorkflowTransitionEvent transition = deferredTransitionEvent;
        deferredTransitionEvent = null;

        view.appendPreRenderedMessage(role, message, renderedHtml, extractedToml);
        persistActiveChatSession();
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
        cancelPendingUserInput();
        deferredTransitionEvent = null;
        view.appendMessage(ROLE_SYSTEM, "_Request cancelled by user._");
        persistActiveChatSession();
        view.setLoading(false);
    }

    @EventListener
    public void onUserInputRequest(UserInputRequestEvent event) {
        if (event == null || !model.getConversationId().equals(event.conversationId())) {
            return;
        }
        CompletableFuture<Object> responseFuture = event.responseFuture();
        if (responseFuture == null || responseFuture.isDone()) {
            return;
        }
        CompletableFuture<Object> previous = pendingUserInputFuture;
        if (previous != null && !Objects.equals(previous, responseFuture) && !previous.isDone()) {
            previous.cancel(true);
        }
        pendingUserInputFuture = responseFuture;
        Edt.run(() -> {
            if (!Objects.equals(pendingUserInputFuture, responseFuture) || responseFuture.isDone()) {
                return;
            }
            view.showUserInputPrompt(event, response -> completeUserInput(responseFuture, response));
        });
    }

    private void completeUserInput(CompletableFuture<Object> responseFuture, Object response) {
        if (Objects.equals(pendingUserInputFuture, responseFuture)) {
            pendingUserInputFuture = null;
        }
        view.hideUserInputPrompt();
        if (responseFuture != null && !responseFuture.isDone()) {
            responseFuture.complete(response);
        }
    }

    @EventListener
    public void onWorkflowTransition(WorkflowTransitionEvent event) {
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
                persistActiveChatSession();
            } else {
                triggerTurn(() -> buildAutonomousTransitionPrompt(event, gitService), List.of());
            }
        } else if (event.from() == WorkflowPhase.PLAN && event.to() == WorkflowPhase.CODE
                && view.isAutoApprovePlanEnabled()) {
            approvePhase();
        } else {
            view.showTransitionBanner(PlanService.formatApprovalLabel(event.from(), event.to()));
        }
    }

    static String buildAutonomousTransitionPrompt(WorkflowTransitionEvent event, @Nullable GitService gitService) {
        if (event == null || event.to() == null) {
            return "Please proceed with the tasks in the current phase.";
        }
        if (event.to() == WorkflowPhase.REVIEW) {
            String basePrompt = "Phase changed to REVIEW. Perform an independent pull-request audit of the changes: "
                    + "inspect gitService.getDiff() and the changed files in ## CURRENT PLAN PROGRESS first, "
                    + "verify all requirements, edge cases, project coding guidelines, and unit test quality, "
                    + "and either record remediation steps (planService.addTechnicalStep / planService.markStepIncomplete) "
                    + "and call planService.requestFixes(reason), or mark 'Automated code review passes' completed "
                    + "and call planService.requestCompletion(reason). "
                    + "When the pre-loaded diff is complete and all requirements and guidelines are satisfied, "
                    + "complete the review in a single turn.";
            return appendPreloadedReviewGitContext(basePrompt, gitService);
        }
        if (event.from() == WorkflowPhase.REVIEW && event.to() == WorkflowPhase.CODE) {
            String reason = StringUtils.defaultIfBlank(event.reason(), "Review findings require remediation.");
            return "Phase changed from REVIEW to CODE. Review findings to address: " + reason
                    + ". Complete all open remediation step(s) in the plan, re-verify with buildToolService.buildAndTest(), "
                    + "and call planService.requestReview(reason) when ready.";
        }
        return "Phase changed to " + event.to() + ". Please proceed with the tasks in the new phase.";
    }

    private static String appendPreloadedReviewGitContext(String basePrompt, @Nullable GitService gitService) {
        if (gitService == null) {
            return basePrompt;
        }
        String status;
        String diff;
        try {
            status = gitService.getStatus();
            diff = gitService.getDiff();
        } catch (Exception e) {
            log.debug("Failed to pre-load git status/diff for REVIEW transition: {}", e.getMessage());
            return basePrompt;
        }
        boolean validStatus = GitService.isValidOutput(status);
        boolean validDiff = GitService.isValidOutput(diff);
        if (!validStatus && !validDiff) {
            return basePrompt;
        }

        StringBuilder sb = new StringBuilder(basePrompt);
        sb.append("\n\n### Pre-Loaded Working Tree Status & Diff\n");
        if (validStatus) {
            sb.append("#### Git Status\n").append(CODE_FENCE).append('\n').append(status.trim()).append('\n')
                    .append(CODE_FENCE).append('\n');
        }
        if (validDiff) {
            String trimmedDiff = diff.trim();
            if (trimmedDiff.length() > MAX_PRELOADED_REVIEW_DIFF_CHARS) {
                trimmedDiff = trimmedDiff.substring(0, MAX_PRELOADED_REVIEW_DIFF_CHARS) + REVIEW_DIFF_TRUNCATION_NOTICE;
            }
            sb.append("#### Git Diff\n").append(CODE_FENCE).append("diff\n").append(trimmedDiff).append('\n')
                    .append(CODE_FENCE);
        }
        return sb.toString();
    }

    private int resolveConfiguredContextWindowTokens() {
        WorkflowPhase activePhase = model.resolveActivePhase(planService.getCurrentPhase());
        String modelName = ProjectService.resolveEffectiveGeminiModel(projectService, settingsService, activePhase);
        return SettingsService.resolveModelConfig(settingsService, modelName).contextWindowTokens();
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        view.invalidateFileMentionCache();
        restoreSessionForCurrentProject();
        Edt.run(view::updateProjectComposerState);
    }

    @EventListener
    public void onFileChanged(FileChangedEvent event) {
        view.invalidateFileMentionCache();
    }

    void restoreSessionForCurrentProject() {
        cancelActiveTurnState(false);
        model.clearPendingAttachments();
        model.setCurrentTurn(0);
        Path sessionPath = resolveActiveChatSessionPath();
        ActiveChatSessionState saved = PathUtils.readJsonOrDefault(sessionPath, ActiveChatSessionState.class, null);
        applyRestoredSession(saved);
    }

    private void applyRestoredSession(ActiveChatSessionState saved) {
        if (saved != null) {
            restoreChatMemoryMessages(saved.memoryMessages());
            model.setLastPromptTokens(Math.max(0L, saved.lastPromptTokens()));
            model.setLastCachedInputTokens(Math.max(0L, saved.lastCachedInputTokens()));
            if (saved.lastContextWindowTokens() > 0) {
                model.setLastContextWindowTokens(saved.lastContextWindowTokens());
            } else {
                model.setLastContextWindowTokens(resolveConfiguredContextWindowTokens());
            }
        } else {
            if (chatMemory != null) {
                chatMemory.clear(model.getConversationId());
            }
            resetTelemetry();
        }
        List<PersistedChatEntry> entriesToRestore;
        if (saved != null && saved.uiEntries() != null) {
            entriesToRestore = saved.uiEntries();
        } else {
            entriesToRestore = List.of();
        }
        Edt.run(() -> {
            view.clearAttachments();
            view.hideUserInputPrompt();
            view.setLoading(false);
            view.restoreMessages(entriesToRestore);
            syncTransitionBannerWithPlanService();
            view.updateTurnLabel();
        });
    }

    private void syncTransitionBannerWithPlanService() {
        if (!model.isTaskMode()) {
            return;
        }
        if (planService != null && planService.getPendingPhase() != null) {
            view.showTransitionBanner(
                    PlanService.formatApprovalLabel(planService.getCurrentPhase(), planService.getPendingPhase()));
        } else {
            view.hideTransitionBanner();
        }
    }

    static String resolveActiveChatFilename(String conversationId) {
        return "active-chat-" + PathUtils.sanitizeSlug(conversationId, 40, "default") + ".json";
    }

    private Path resolveActiveChatSessionPath() {
        return ProjectService.resolveProjectDataPathSafely(projectService,
                resolveActiveChatFilename(model.getConversationId()));
    }

    void persistActiveChatSession() {
        Path path = resolveActiveChatSessionPath();
        if (path == null) {
            return;
        }
        List<PersistedChatEntry> uiEntries = view.exportMessages();
        List<PersistedMemoryMessage> memoryMessages = exportChatMemoryMessages();
        long promptTokens = model.getLastPromptTokens();
        long cachedTokens = model.getLastCachedInputTokens();
        int contextWindowTokens = model.getLastContextWindowTokens();
        boolean deleteOnly = uiEntries.isEmpty() && memoryMessages.isEmpty() && promptTokens <= 0;
        ActiveChatSessionState state;
        if (deleteOnly) {
            state = null;
        } else {
            state = new ActiveChatSessionState(uiEntries, memoryMessages, promptTokens, cachedTokens,
                    contextWindowTokens);
        }
        CompletableFuture<Void> saveFuture = enqueueSessionDiskOperation(path, state, deleteOnly);
        if (!SwingUtilities.isEventDispatchThread()) {
            saveFuture.join();
        }
    }

    private CompletableFuture<Void> enqueueSessionDiskOperation(Path path, ActiveChatSessionState state,
            boolean deleteOnly) {
        synchronized (persistLock) {
            lastPersistFuture = lastPersistFuture.thenRunAsync(() -> writeSessionStateToDisk(path, state, deleteOnly),
                    Edt.VIRTUAL_EXECUTOR);
            return lastPersistFuture;
        }
    }

    void awaitPendingPersistence() {
        CompletableFuture<Void> future;
        synchronized (persistLock) {
            future = lastPersistFuture;
        }
        future.join();
    }

    private static void writeSessionStateToDisk(Path path, ActiveChatSessionState state, boolean deleteOnly) {
        if (deleteOnly) {
            PathUtils.deleteIfExistsQuietly(path);
            return;
        }
        try {
            PathUtils.writeJson(path, state);
        } catch (Exception e) {
            log.warn("Failed to persist active chat session state", e);
        }
    }

    private List<PersistedMemoryMessage> exportChatMemoryMessages() {
        if (chatMemory == null) {
            return List.of();
        }
        List<Message> stored = chatMemory.get(model.getConversationId());
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        List<PersistedMemoryMessage> result = new ArrayList<>(stored.size());
        for (Message msg : stored) {
            if (msg == null || msg.getMessageType() == null) {
                continue;
            }
            MessageType type = msg.getMessageType();
            if (type == MessageType.USER || type == MessageType.ASSISTANT || type == MessageType.SYSTEM) {
                result.add(new PersistedMemoryMessage(type.name(), StringUtils.defaultString(msg.getText())));
            }
        }
        return List.copyOf(result);
    }

    private void restoreChatMemoryMessages(List<PersistedMemoryMessage> persisted) {
        if (chatMemory == null) {
            return;
        }
        chatMemory.clear(model.getConversationId());
        if (persisted == null || persisted.isEmpty()) {
            return;
        }
        List<Message> restored = new ArrayList<>(persisted.size());
        for (PersistedMemoryMessage item : persisted) {
            if (item == null || StringUtils.isBlank(item.type())) {
                continue;
            }
            String text = StringUtils.defaultString(item.text());
            if (MessageType.USER.name().equalsIgnoreCase(item.type())) {
                restored.add(new UserMessage(text));
            } else if (MessageType.ASSISTANT.name().equalsIgnoreCase(item.type())) {
                restored.add(new AssistantMessage(text));
            } else if (MessageType.SYSTEM.name().equalsIgnoreCase(item.type())) {
                restored.add(new SystemMessage(text));
            }
        }
        if (!restored.isEmpty()) {
            chatMemory.add(model.getConversationId(), restored);
        }
    }

    @EventListener
    public void onTokenUsage(TokenUsageEvent event) {
        if (event == null || !model.getConversationId().equals(event.conversationId())) {
            return;
        }
        model.setLastPromptTokens(Math.max(0, event.promptTokens()));
        model.setLastCachedInputTokens(Math.max(0, event.cachedInputTokens()));
        if (event.contextWindowTokens() > 0) {
            model.setLastContextWindowTokens(event.contextWindowTokens());
        } else {
            model.setLastContextWindowTokens(resolveConfiguredContextWindowTokens());
        }
        Edt.run(() -> {
            view.updateTurnLabel();
            persistActiveChatSession();
        });
    }

    private void resetTelemetry() {
        model.setLastPromptTokens(0);
        model.setLastCachedInputTokens(0);
        model.setLastContextWindowTokens(resolveConfiguredContextWindowTokens());
    }

    private boolean isMatchingActiveTurnEvent(String conversationId) {
        return model.getConversationId().equals(conversationId) && !model.isTurnCancelled();
    }

    @EventListener
    public void onAgentTurn(AgentTurnEvent event) {
        if (event == null || !isMatchingActiveTurnEvent(event.conversationId())) {
            return;
        }
        Edt.run(() -> {
            if (!model.isTurnCancelled()) {
                model.setCurrentTurn(event.turnNumber());
                view.updateTurnLabel();
                String summary = StringUtils.trimToEmpty(event.scriptSummary());
                if (summary.isEmpty()) {
                    view.updateActivityStatus("Running tool (Turn " + event.turnNumber() + ")...");
                } else {
                    view.updateActivityStatus("Running tool (Turn " + event.turnNumber() + "): " + summary);
                }
            }
        });
    }

    @EventListener
    public void onAgentActivity(AgentActivityEvent event) {
        if (event == null || StringUtils.isBlank(event.statusText())) {
            return;
        }
        if ((!isTurnActive() && !view.isLoading()) || model.isTurnCancelled()) {
            return;
        }
        String eventConvId = StringUtils.trimToEmpty(event.conversationId());
        if (!eventConvId.isEmpty() && !isMatchingActiveTurnEvent(eventConvId)) {
            return;
        }
        Edt.run(() -> {
            if ((isTurnActive() || view.isLoading()) && !model.isTurnCancelled()) {
                view.updateActivityStatus(event.statusText());
            }
        });
    }

    @EventListener
    public void onToolExecution(ToolExecutionEvent event) {
        if (event == null || !isMatchingActiveTurnEvent(event.conversationId())) {
            return;
        }
        String highlightedHtml = JexlToHtmlConverter.convert(event.script());
        Edt.run(() -> {
            if (!model.isTurnCancelled()) {
                view.appendToolLog(highlightedHtml, event.success(), event.error());
                persistActiveChatSession();
            }
        });
    }

    @EventListener
    public void onAgentTurnComplete(AgentTurnCompleteEvent event) {
        if (event == null || !model.getConversationId().equals(event.conversationId())) {
            return;
        }
        Edt.run(() -> {
            if (!isTurnActive()) {
                view.setLoading(false);
            }
        });
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        model.setLastContextWindowTokens(resolveConfiguredContextWindowTokens());
        Edt.run(view::updateTurnLabel);
    }

    @EventListener
    public void onPlanUpdated(PlanUpdatedEvent event) {
        if (model.isTaskMode()) {
            Edt.run(() -> {
                view.updateTurnLabel();
                if (view.inlineActionPanel.isVisible()) {
                    view.updateCopyCommitMsgButtonState(view.transitionLabel.getText());
                }
            });
        }
    }

    @EventListener
    public void onPhaseChanged(PhaseChangedEvent event) {
        if (event == null || !model.isTaskMode()) {
            return;
        }
        log.debug("Phase changed to: {}", event.phase());
        Edt.run(() -> {
            syncTransitionBannerWithPlanService();
            view.updateTurnLabel();
        });
    }
}
