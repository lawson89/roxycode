package org.roxycode.app.ui;

import java.util.List;
import java.util.concurrent.CancellationException;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.YieldTurnException;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.AutonomousTransitionEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.events.TurnEventBridge;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;

/** Controller coordinating chat turns, service events, and workflow transitions. */
public final class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    static final String ROLE_SYSTEM = "system";
    static final String ROLE_USER = "user";
    static final String ROLE_AI = "ai";

    private final ChatModel model;
    private final ChatPanel view;
    private final AiService aiService;
    private final WorkflowService workflowService;
    private final TurnEventBridge turnEventBridge;
    private final ChatMemory chatMemory;
    private final SettingsService settingsService;
    private final PlanService planService;

    private SwingWorker<String, Void> currentWorker;

    public ChatController(
            ChatModel model,
            ChatPanel view,
            AiService aiService,
            WorkflowService workflowService,
            TurnEventBridge turnEventBridge,
            ChatMemory chatMemory,
            SettingsService settingsService,
            PlanService planService) {
        this.model = model;
        this.view = view;
        this.aiService = aiService;
        this.workflowService = workflowService;
        this.turnEventBridge = turnEventBridge;
        this.chatMemory = chatMemory;
        this.settingsService = settingsService;
        this.planService = planService;
    }

    public void registerListeners() {
        String conversationId = model.getConversationId();
        turnEventBridge.addTurnListener(conversationId, this::onAgentTurn);
        if (model.isTaskMode()) {
            turnEventBridge.addAutonomousTransitionListener(this::onAutonomousTransition);
        }
        turnEventBridge.addToolExecutionListener(conversationId, this::onToolExecution);
        turnEventBridge.addCompleteListener(
                conversationId,
                event ->
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (currentWorker == null) {
                                        view.setLoading(false);
                                    }
                                }));

        if (settingsService != null) {
            settingsService.addSettingsListener(
                    s -> SwingUtilities.invokeLater(view::updateTurnLabel));
        }
        if (model.isTaskMode()) {
            workflowService.addPhaseListener(this::onPhaseChanged);
            workflowService.addTransitionRequestListener(this::onTransitionRequested);
            if (planService != null) {
                planService.addListener(plan -> SwingUtilities.invokeLater(view::updateTurnLabel));
            }
        }
    }

    public String formatCurrentTurnStatus() {
        AppSettings settings = settingsService != null ? settingsService.getSettings() : null;
        ImplementationPlan plan =
                (model.isTaskMode() && planService != null) ? planService.getCurrentPlan() : null;
        return model.formatTurnStatus(settings, plan);
    }

    public boolean hasPendingPhase() {
        return workflowService != null && workflowService.getPendingPhase() != null;
    }

    public void sendMessage(String rawText) {
        if (rawText == null) return;
        String text = rawText.trim();
        if (text.isEmpty()) return;

        turnEventBridge.publishUserMessage(model.getConversationId(), ROLE_USER, text);
        view.appendMessage(ROLE_USER, text);
        view.clearInputText();
        triggerTurn(text);
    }

    public void stopTurn() {
        model.setTurnCancelled(true);
        model.clearPostTurnActions();
        if (currentWorker != null) {
            currentWorker.cancel(true);
        }
    }

    public void approvePhase() {
        WorkflowPhase current = workflowService.getCurrentPhase();
        WorkflowPhase requested = workflowService.getPendingPhase();
        if (requested != null) {
            model.setTurnCancelled(false);
            workflowService.approveTransition();
            view.appendMessage(
                    ROLE_SYSTEM, "Transition to **" + requested.getDisplayName() + "** approved.");
            if (requested == WorkflowPhase.EXPLORE) {
                view.appendMessage(
                        ROLE_SYSTEM,
                        "Task complete. We are back in Explore mode. Please enter a new question or task.");
            } else if (current == WorkflowPhase.REVIEW && requested == WorkflowPhase.PLAN) {
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
        WorkflowPhase requested = workflowService.getPendingPhase();
        if (requested != null) {
            model.setTurnCancelled(false);
            workflowService.rejectTransition();
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
        model.clearPostTurnActions();
        if (currentWorker != null) {
            SwingWorker<String, Void> workerToCancel = currentWorker;
            currentWorker = null; // NOPMD
            workerToCancel.cancel(true);
            view.setLoading(false);
        }
        if (model.isTaskMode()) {
            workflowService.resetWorkflow();
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
        model.clearPostTurnActions();

        if (currentWorker != null && !currentWorker.isDone()) {
            SwingWorker<String, Void> previousWorker = currentWorker;
            currentWorker = null; // NOPMD
            previousWorker.cancel(true);
        }

        view.setLoading(true);
        view.clearThoughts();
        model.setCurrentTurn(0);
        view.updateTurnLabel();

        List<byte[]> imageBytes = model.drainPendingImagesAsPngBytes();
        view.clearAttachments();

        currentWorker =
                new SwingWorker<>() {
                    @Override
                    protected String doInBackground() {
                        try {
                            WorkflowPhase phase =
                                    model.resolveActivePhase(workflowService.getCurrentPhase());
                            return aiService.chat(
                                    model.getConversationId(), phase, text, null, imageBytes);
                        } catch (CancellationException ex) {
                            throw ex;
                        } catch (Exception ex) {
                            if (isCancelled()
                                    || model.isTurnCancelled()
                                    || Thread.currentThread().isInterrupted()
                                    || YieldTurnException.findCause(ex, CancellationException.class)
                                            != null) {
                                CancellationException ce =
                                        new CancellationException("Turn cancelled");
                                ce.initCause(ex);
                                throw ce;
                            }
                            log.error("AI chat interaction failed: {}", ex.getMessage(), ex);
                            return "Error: "
                                    + (ex.getMessage() != null
                                            ? ex.getMessage()
                                            : ex.getClass().getSimpleName());
                        }
                    }

                    @Override
                    protected void done() {
                        if (currentWorker != this) {
                            return;
                        }
                        boolean cancelled = isCancelled() || model.isTurnCancelled();
                        try {
                            String response = get();
                            if (!cancelled) {
                                view.appendMessage(ROLE_AI, response);
                            } else {
                                view.appendMessage(ROLE_SYSTEM, "_Request cancelled by user._");
                            }
                        } catch (CancellationException ce) {
                            cancelled = true;
                            model.setTurnCancelled(true);
                            view.appendMessage(ROLE_SYSTEM, "_Request cancelled by user._");
                        } catch (Exception ex) {
                            if (cancelled
                                    || YieldTurnException.findCause(ex, CancellationException.class)
                                            != null) {
                                cancelled = true;
                                model.setTurnCancelled(true);
                                view.appendMessage(ROLE_SYSTEM, "_Request cancelled by user._");
                            } else {
                                log.error(
                                        "Failed to retrieve AI response: {}", ex.getMessage(), ex);
                                view.appendMessage(ROLE_AI, "_Error communicating with AI._");
                            }
                        } finally {
                            if (currentWorker == this) {
                                currentWorker = null; // NOPMD
                                view.setLoading(false);
                                List<Runnable> actions = model.drainPostTurnActions();
                                if (!cancelled && !model.isTurnCancelled()) {
                                    for (Runnable action : actions) {
                                        action.run();
                                    }
                                }
                            }
                        }
                    }
                };
        currentWorker.execute();
    }

    void runAfterCurrentTurn(Runnable action) {
        if (model.isTurnCancelled()) return;
        SwingUtilities.invokeLater(
                () -> {
                    if (model.isTurnCancelled()) return;
                    if (currentWorker != null) {
                        model.enqueuePostTurnAction(action);
                    } else {
                        action.run();
                    }
                });
    }

    void onAutonomousTransition(AutonomousTransitionEvent event) {
        if (!model.isTaskMode() || model.isTurnCancelled()) return;
        runAfterCurrentTurn(
                () -> {
                    if (model.isTurnCancelled()) return;
                    view.appendMessage(
                            ROLE_SYSTEM,
                            "Autonomous transition detected: **"
                                    + event.from()
                                    + " → "
                                    + event.to()
                                    + "**. Reason: "
                                    + event.reason());

                    if (event.to() == WorkflowPhase.EXPLORE) {
                        view.appendMessage(
                                ROLE_SYSTEM,
                                "Task complete. We are back in Explore mode. Please enter a new question or task.");
                    } else if (event.to() == WorkflowPhase.PLAN) {
                        view.appendMessage(
                                ROLE_SYSTEM,
                                "Task complete. What is the next feature you would like to work on?");
                    } else {
                        triggerTurn(
                                "Phase changed to "
                                        + event.to()
                                        + ". Please proceed with the tasks in the new phase.");
                    }
                });
    }

    void onAgentTurn(AgentTurnEvent event) {
        if (model.isTurnCancelled()) return;
        SwingUtilities.invokeLater(
                () -> {
                    if (model.isTurnCancelled()) return;
                    model.setCurrentTurn(event.turnNumber());
                    view.updateTurnLabel();
                });
    }

    void onToolExecution(ToolExecutionEvent event) {
        if (model.isTurnCancelled()) return;
        SwingUtilities.invokeLater(
                () -> {
                    if (model.isTurnCancelled()) return;
                    String toolName = "";
                    String output = JexlToHtmlConverter.convert(event.script());
                    view.appendToolLog(toolName, output);
                });
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

    void onTransitionRequested(
            WorkflowPhase current, WorkflowPhase requested, String reason) {
        if (!model.isTaskMode()) return;
        if (requested == null) {
            SwingUtilities.invokeLater(view::hideTransitionBanner);
            return;
        }
        if (model.isTurnCancelled()) return;
        runAfterCurrentTurn(
                () -> {
                    if (model.isTurnCancelled()) return;
                    String path = current.getDisplayName() + " → " + requested.getDisplayName();
                    view.showTransitionBanner(path);
                    view.appendMessage(
                            ROLE_SYSTEM,
                            "Roxy requested a transition: **"
                                    + path
                                    + "**.\n\n**Reason:** "
                                    + reason);
                });
    }
}
