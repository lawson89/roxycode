package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.DefaultEditorKit;
import javax.swing.undo.UndoManager;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.UserInputService;
import org.roxycode.app.events.TokenUsageEvent;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.events.UserInputRequestEvent;
import org.roxycode.app.events.UserInputRequestEvent.PromptType;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.Edt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;

@SuppressWarnings("PMD.NullAssignment")
public final class ChatPanel extends JPanel implements AutoCloseable {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(ChatPanel.class);

    static final String NEW_TOPIC_BUTTON_TEXT = "New Topic";
    static final String RESET_SESSION_BUTTON_TEXT = "Reset Session";
    static final String NEW_TOPIC_TOOLTIP = "Archive current conversation and start a new Q&A topic";
    static final String NEW_TOPIC_PROMPT_MESSAGE = "Save the current topic to Past Topics before starting a new one?";
    static final String TOPIC_NAME_LABEL_TEXT = "Topic Name:";
    static final String SAVE_TOPIC_BUTTON_TEXT = "Yes, Save Topic";
    static final String DISCARD_TOPIC_BUTTON_TEXT = "No, Discard";
    static final String DEFAULT_PLACEHOLDER_TEXT = "Type a message... (Enter to send, Shift+Enter for newline, @ to mention files, Ctrl+V to paste image)";
    static final String NO_PROJECT_PLACEHOLDER_TEXT = "Select a project folder in the top bar (Change Project...) to start chatting...";
    private static final String TOOLBAR_BUTTON_STYLE = "arc: 8; font: -1; foreground: $Label.disabledForeground";
    private static final String GROW_FILL_COL = "[grow, fill]";
    static final int THUMBNAIL_SIZE = 64;

    public record NewTopicChoice(boolean proceed, boolean saveTopic, String topicName) {
    }

    final transient ChatModel model;
    final transient ChatController controller;
    MarkdownPane outputArea;
    JTextArea inputArea;
    JButton sendButton;
    JButton stopButton;
    JButton attachImageButton;
    JButton mentionButton;
    JButton viewPlanButton;
    JButton approveButton;
    JButton rejectButton;
    JButton resetSessionButton;
    final JPanel inlineActionPanel;
    final JPanel userInputPromptPanel;
    JLabel transitionLabel;
    JLabel turnLabel;
    JLabel ctxLabel;
    JProgressBar ctxProgressBar;
    JLabel cacheLabel;
    JProgressBar cacheProgressBar;
    private JScrollPane outputScrollPane;
    private JPanel attachmentPanel;
    transient FileMentionPopup fileMentionPopup;
    private transient ProjectService projectService;
    private transient Runnable onViewPlanAction;
    private boolean loading = false;
    private transient Function<String, NewTopicChoice> newTopicDialogPresenter;
    private final List<Consumer<Boolean>> loadingListeners = new ArrayList<>();

    @Configuration
    static class ChatPanelBeans {
        @Bean("exploreChatPanel")
        @Lazy
        ChatPanel exploreChatPanel(AiService aiService, PlanService planService,
                ApplicationEventPublisher eventPublisher, ChatMemory chatMemory, SettingsService settingsService,
                ExecutorService chatTurnExecutor, MemoryService memoryService, EnvironmentService environmentService,
                ProjectService projectService) {
            ChatPanel panel = createExplorePanel(aiService, planService, eventPublisher, chatMemory, settingsService,
                    chatTurnExecutor, memoryService, environmentService);
            panel.setProjectService(projectService);
            return panel;
        }

        @Bean("featureChatPanel")
        @Lazy
        ChatPanel featureChatPanel(AiService aiService, PlanService planService,
                ApplicationEventPublisher eventPublisher, ChatMemory chatMemory, SettingsService settingsService,
                ExecutorService chatTurnExecutor, ProjectService projectService) {
            ChatPanel panel = createFeaturePanel(aiService, planService, eventPublisher, chatMemory, settingsService,
                    chatTurnExecutor);
            panel.setProjectService(projectService);
            return panel;
        }
    }

    public static ChatPanel createExplorePanel(AiService aiService, PlanService planService,
            ApplicationEventPublisher eventPublisher, ChatMemory chatMemory, SettingsService settingsService,
            Executor turnExecutor, MemoryService memoryService, EnvironmentService environmentService) {
        ChatPanel panel = new ChatPanel(aiService, planService, eventPublisher, chatMemory, ChatModel.QA_SESSION_PREFIX,
                WorkflowPhase.EXPLORE, false, settingsService, turnExecutor);
        panel.controller.setTopicArchiveServices(memoryService, environmentService);
        return panel;
    }

    public static ChatPanel createFeaturePanel(AiService aiService, PlanService planService,
            ApplicationEventPublisher eventPublisher, ChatMemory chatMemory, SettingsService settingsService,
            Executor turnExecutor) {
        return new ChatPanel(aiService, planService, eventPublisher, chatMemory, ChatModel.TASK_SESSION_PREFIX, null,
                true, settingsService, turnExecutor);
    }

    public ChatPanel(AiService aiService, PlanService planService, ApplicationEventPublisher eventPublisher,
            ChatMemory chatMemory, String sessionPrefix, WorkflowPhase lockedPhase, boolean isTaskMode,
            SettingsService settingsService, Executor turnExecutor) {
        this.model = new ChatModel(sessionPrefix, lockedPhase, isTaskMode);
        this.controller = new ChatController(this.model, this, aiService, planService, eventPublisher, chatMemory,
                settingsService, turnExecutor);
        setLayout(new BorderLayout());

        this.inlineActionPanel = createInlineActionPanel();
        this.userInputPromptPanel = createUserInputPromptPanel();
        Component topComponent = createOutputSection(isTaskMode);
        JPanel inputSection = createInputSection(isTaskMode);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topComponent, inputSection);
        splitPane.setResizeWeight(1.0);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        add(splitPane, BorderLayout.CENTER);

        SwingHtmlUtils.installTextContextMenu(inputArea);
        setupUndoRedo();
        setupKeyboardShortcuts();
        updateProjectComposerState();
    }

    private JPanel createInlineActionPanel() {
        JPanel panel = new JPanel(new MigLayout("insets 10 20 10 20, gap 15", "", "center"));
        panel.putClientProperty(FlatClientProperties.STYLE,
                "background: darken($TextPane.background, 3%); border: 1,0,0,0,$Component.borderColor");
        panel.setVisible(false);

        Color accentColor = SwingHtmlUtils.getUiColor(Color.GRAY, "Component.accentColor", "Label.foreground");
        transitionLabel = new JLabel("Workflow Transition", FontIcon.of(Codicons.ROCKET, 16, accentColor),
                SwingConstants.LEFT);
        transitionLabel.setFont(transitionLabel.getFont().deriveFont(Font.BOLD));
        transitionLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Component.accentColor");

        approveButton = createActionButton("Approve", Codicons.CHECK, null, false,
                "background: $Actions.Green; focusedBackground: $Actions.Green; hoverBackground: darken($Actions.Green, 8%); pressedBackground: darken($Actions.Green, 14%); foreground: #ffffff",
                e -> controller.approvePhase());
        approveButton.setEnabled(false);

        viewPlanButton = createActionButton("View Plan", Codicons.LIST_ORDERED, null, false,
                "background: $Component.accentColor; focusedBackground: $Component.accentColor; hoverBackground: darken($Component.accentColor, 8%); pressedBackground: darken($Component.accentColor, 14%); foreground: #ffffff",
                e -> {
                    if (onViewPlanAction != null) {
                        onViewPlanAction.run();
                    }
                });
        viewPlanButton.setVisible(false);

        rejectButton = createActionButton("Reject", Codicons.CLOSE, null, false,
                "background: $Actions.Red; focusedBackground: $Actions.Red; hoverBackground: darken($Actions.Red, 8%); pressedBackground: darken($Actions.Red, 14%); foreground: #ffffff",
                e -> controller.rejectPhase());
        rejectButton.setEnabled(false);

        panel.add(transitionLabel);
        panel.add(Box.createHorizontalStrut(10));
        panel.add(viewPlanButton, "hidemode 3");
        panel.add(approveButton);
        panel.add(rejectButton);
        return panel;
    }

    private Component createOutputSection(boolean isTaskMode) {
        outputArea = new MarkdownPane(isTaskMode);
        outputScrollPane = new JScrollPane(outputArea);
        outputScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        updateOutputScrollPaneBorder();

        outputArea.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                SwingUtilities.invokeLater(() -> {
                    JScrollBar vertical = outputScrollPane.getVerticalScrollBar();
                    vertical.setValue(vertical.getMaximum());
                });
            }
        });

        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.setOpaque(false);
        southPanel.add(userInputPromptPanel, BorderLayout.NORTH);
        if (isTaskMode) {
            southPanel.add(inlineActionPanel, BorderLayout.SOUTH);
        }
        JPanel outputPanel = new JPanel(new BorderLayout());
        outputPanel.add(outputScrollPane, BorderLayout.CENTER);
        outputPanel.add(southPanel, BorderLayout.SOUTH);
        return outputPanel;
    }

    private JPanel createUserInputPromptPanel() {
        JPanel panel = new JPanel(new MigLayout("insets 10 20 10 20, fillx, wrap 1, gapy 8", GROW_FILL_COL));
        panel.putClientProperty(FlatClientProperties.STYLE,
                "background: darken($TextPane.background, 3%); border: 1,0,0,0,$Component.borderColor");
        panel.setVisible(false);
        return panel;
    }

    private JPanel createInputSection(boolean isTaskMode) {
        attachmentPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        attachmentPanel.setOpaque(false);
        attachmentPanel.setVisible(false);

        JPanel inputSection = new JPanel(new MigLayout("fill, insets 8 12 10 12", GROW_FILL_COL, GROW_FILL_COL));
        inputSection.setMinimumSize(new Dimension(0, 95));

        JPanel telemetryBar = new JPanel(new MigLayout("insets 0 4 4 4", "[] 6 [] 12 [] 6 []", "center"));
        telemetryBar.setOpaque(false);

        Font statusFont = getFont().deriveFont(11f);
        ctxLabel = new JLabel();
        ctxLabel.setFont(statusFont);
        ctxProgressBar = new JProgressBar(0, 100);
        ctxProgressBar.setStringPainted(false);
        ctxProgressBar.putClientProperty(FlatClientProperties.STYLE, "arc: 6");

        cacheLabel = new JLabel();
        cacheLabel.setFont(statusFont);
        cacheProgressBar = new JProgressBar(0, 100);
        cacheProgressBar.setStringPainted(false);
        cacheProgressBar.putClientProperty(FlatClientProperties.STYLE, "arc: 6");

        telemetryBar.add(ctxLabel);
        telemetryBar.add(ctxProgressBar, "w 60!, h 8!");
        telemetryBar.add(cacheLabel);
        telemetryBar.add(cacheProgressBar, "w 60!, h 8!");

        JPanel composerCard = new JPanel(new BorderLayout());
        composerCard.putClientProperty(FlatClientProperties.STYLE,
                "arc: 12; background: $TextArea.background; border: 1,1,1,1,$Component.borderColor");

        inputArea = new JTextArea(3, 0);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setMargin(new Insets(8, 12, 4, 12));
        inputArea.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
        inputArea.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, DEFAULT_PLACEHOLDER_TEXT);
        JScrollPane inputScrollPane = new JScrollPane(inputArea);
        inputScrollPane.setBorder(BorderFactory.createEmptyBorder());
        inputScrollPane.setOpaque(false);
        inputScrollPane.getViewport().setOpaque(false);
        fileMentionPopup = new FileMentionPopup(inputArea, inputScrollPane);

        sendButton = createActionButton("Send", Codicons.CHEVRON_RIGHT, new Insets(5, 14, 5, 14), false,
                "arc: 8; font: bold; background: $Component.accentColor; focusedBackground: $Component.accentColor; hoverBackground: darken($Component.accentColor, 8%); pressedBackground: darken($Component.accentColor, 14%); foreground: #ffffff",
                this::sendMessage);

        stopButton = createActionButton("Stop", Codicons.DEBUG_STOP, new Insets(5, 14, 5, 14), false,
                "arc: 8; font: bold; background: $Actions.Red; focusedBackground: $Actions.Red; hoverBackground: darken($Actions.Red, 8%); pressedBackground: darken($Actions.Red, 14%); foreground: #ffffff",
                e -> controller.stopTurn());
        stopButton.setEnabled(false);
        stopButton.setVisible(false);

        attachImageButton = createActionButton("Attach Image", Codicons.FILE_MEDIA, new Insets(4, 8, 4, 8), true,
                TOOLBAR_BUTTON_STYLE, e -> chooseImageAttachment());
        attachImageButton.setToolTipText("Attach Image (or paste with Ctrl+V)");

        String resetText;
        Codicons resetIcon;
        if (isTaskMode) {
            resetText = RESET_SESSION_BUTTON_TEXT;
            resetIcon = Codicons.REFRESH;
        } else {
            resetText = NEW_TOPIC_BUTTON_TEXT;
            resetIcon = Codicons.COMMENT_DISCUSSION;
        }
        resetSessionButton = createActionButton(resetText, resetIcon, new Insets(4, 8, 4, 8), true,
                TOOLBAR_BUTTON_STYLE, e -> controller.resetSession());
        if (!isTaskMode) {
            resetSessionButton.setToolTipText(NEW_TOPIC_TOOLTIP);
        }

        mentionButton = createActionButton("Mention", Codicons.MENTION, new Insets(4, 8, 4, 8), true,
                TOOLBAR_BUTTON_STYLE, e -> triggerMentionPopup());
        mentionButton.setToolTipText("Insert @ to search and mention project files");

        turnLabel = new JLabel();
        turnLabel.putClientProperty(FlatClientProperties.STYLE,
                "font: $small.font; foreground: $Label.disabledForeground; background: darken($TextArea.background, 4%); arc: 8; border: 2,8,2,8");
        updateTurnLabel();

        JPanel actionToolbar = new JPanel(new MigLayout("insets 4 8 6 8, fillx", "[]4[]4[]push[]8[]", "center"));
        actionToolbar.setOpaque(false);
        actionToolbar.add(attachImageButton);
        actionToolbar.add(resetSessionButton);
        actionToolbar.add(mentionButton);
        actionToolbar.add(turnLabel);
        actionToolbar.add(sendButton, "hidemode 3");
        actionToolbar.add(stopButton, "hidemode 3");

        composerCard.add(attachmentPanel, BorderLayout.NORTH);
        composerCard.add(inputScrollPane, BorderLayout.CENTER);
        composerCard.add(actionToolbar, BorderLayout.SOUTH);

        inputSection.add(telemetryBar, "wrap");
        inputSection.add(composerCard, "grow");
        return inputSection;
    }

    private static JButton createActionButton(String text, Codicons icon, Insets margin, boolean borderless,
            String style, ActionListener listener) {
        FontIcon fontIcon;
        if (borderless) {
            fontIcon = FontIcon.of(icon, 16);
        } else {
            fontIcon = FontIcon.of(icon, 16, Color.WHITE);
        }
        JButton btn = new JButton(text, fontIcon);
        if (margin != null) {
            btn.setMargin(margin);
        }
        if (borderless) {
            btn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        }
        if (style != null) {
            btn.putClientProperty(FlatClientProperties.STYLE, style);
        }
        if (listener != null) {
            btn.addActionListener(listener);
        }
        return btn;
    }

    private static Action createAction(Runnable action) {
        return new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        };
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (controller != null) {
            controller.ensureOwnedExecutorActive();
        }
    }

    @Override
    public void removeNotify() {
        if (controller != null) {
            controller.closeOwnedExecutor();
        }
        super.removeNotify();
    }

    @Override
    public void close() {
        if (controller != null) {
            controller.close();
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (outputScrollPane != null) {
            updateOutputScrollPaneBorder();
        }
        if (fileMentionPopup != null) {
            fileMentionPopup.updateTheme();
        }
    }

    private void updateOutputScrollPaneBorder() {
        outputScrollPane.setBorder(SwingHtmlUtils.createThemeMatteBorder(0, 0, 1, 0));
    }

    @Autowired(required = false)
    public void setGrepService(GrepService grepService) {
        fileMentionPopup.setGrepService(grepService);
    }

    @Autowired(required = false)
    public void setUserInputService(UserInputService userInputService) {
        controller.setUserInputService(userInputService);
    }

    @Autowired(required = false)
    public void setProjectService(ProjectService projectService) {
        this.projectService = projectService;
        controller.setProjectService(projectService);
        updateProjectComposerState();
    }

    private boolean isProjectReady() {
        return projectService == null || projectService.hasActiveProject();
    }

    void updateProjectComposerState() {
        boolean projectReady = isProjectReady();
        String placeholder;
        if (projectReady) {
            placeholder = DEFAULT_PLACEHOLDER_TEXT;
        } else {
            placeholder = NO_PROJECT_PLACEHOLDER_TEXT;
        }
        inputArea.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        boolean enableComposer = !loading && projectReady;
        inputArea.setEnabled(enableComposer);
        sendButton.setEnabled(enableComposer);
        attachImageButton.setEnabled(enableComposer);
        mentionButton.setEnabled(enableComposer);
    }

    public void setOnViewPlanAction(Runnable action) {
        this.onViewPlanAction = action;
        if (viewPlanButton != null) {
            viewPlanButton.setVisible(action != null);
        }
    }

    void triggerMentionPopup() {
        if (!inputArea.isEnabled()) {
            return;
        }
        inputArea.requestFocusInWindow();
        String text = inputArea.getText();
        int caret = inputArea.getCaretPosition();
        if (FileMentionPopup.extractMentionToken(text, caret) == null) {
            String insertion;
            if (caret > 0 && !Character.isWhitespace(text.charAt(caret - 1))) {
                insertion = " @";
            } else {
                insertion = "@";
            }
            inputArea.insert(insertion, caret);
            inputArea.setCaretPosition(caret + insertion.length());
        }
        if (fileMentionPopup != null) {
            fileMentionPopup.evaluateCurrentInput();
        }
    }

    @EventListener
    public void onAgentTurn(AgentTurnEvent event) {
        controller.onAgentTurn(event);
    }

    @EventListener
    public void onToolExecution(ToolExecutionEvent event) {
        controller.onToolExecution(event);
    }

    @EventListener
    public void onUserInputRequest(UserInputRequestEvent event) {
        controller.onUserInputRequest(event);
    }

    @EventListener
    public void onTokenUsage(TokenUsageEvent event) {
        controller.onTokenUsage(event);
    }

    @EventListener
    public void onAgentTurnComplete(AgentTurnCompleteEvent event) {
        controller.onAgentTurnComplete(event);
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        controller.onSettingsChanged(event);
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        fileMentionPopup.invalidateCache();
        controller.onProjectChanged(event);
        Edt.run(this::updateProjectComposerState);
    }

    @EventListener
    public void onFileChanged(FileChangedEvent event) {
        fileMentionPopup.invalidateCache();
    }

    @EventListener
    public void onWorkflowTransition(WorkflowTransitionEvent event) {
        if (model.isTaskMode()) {
            controller.onWorkflowTransition(event);
        }
    }

    @EventListener
    public void onPhaseChanged(PhaseChangedEvent event) {
        if (model.isTaskMode()) {
            controller.onPhaseChanged(event);
        }
    }

    @EventListener
    public void onPlanUpdated(PlanUpdatedEvent event) {
        if (model.isTaskMode()) {
            controller.onPlanUpdated(event);
        }
    }

    void updateTurnLabel() {
        turnLabel.setText(controller.formatCurrentTurnStatus());
        updateTelemetry();
    }

    private void updateTelemetry() {
        long lastPromptTokens = model.getLastPromptTokens();
        long lastCachedInputTokens = model.getLastCachedInputTokens();
        int contextWindow = Math.max(1, model.getLastContextWindowTokens());
        int ctxPct = (int) Math.min(100L, Math.max(0L, Math.round((lastPromptTokens * 100.0) / contextWindow)));
        int lastCacheHitPct = org.roxycode.app.util.TokenUtils.computeCacheHitPercent(lastCachedInputTokens,
                lastPromptTokens);

        if (lastPromptTokens > 0) {
            ctxLabel.setText(String.format(java.util.Locale.US, "Context Usage: %s / %s (%d%%)",
                    org.roxycode.app.util.TokenUtils.formatTokenCount(lastPromptTokens),
                    org.roxycode.app.util.TokenUtils.formatTokenCount(contextWindow), ctxPct));
            cacheLabel.setText(String.format(java.util.Locale.US, "Cache Hit: %s / %s (%d%%)",
                    org.roxycode.app.util.TokenUtils.formatTokenCount(lastCachedInputTokens),
                    org.roxycode.app.util.TokenUtils.formatTokenCount(lastPromptTokens), lastCacheHitPct));
        } else {
            ctxLabel.setText(String.format(java.util.Locale.US, "Context Usage: 0 / %s (0%%)",
                    org.roxycode.app.util.TokenUtils.formatTokenCount(contextWindow)));
            cacheLabel.setText("Cache Hit: 0 / 0 (0%)");
        }
        ctxProgressBar.setValue(ctxPct);
        String ctxTooltip = String.format(java.util.Locale.US, "Context window usage: %,d / %,d tokens (%d%%)",
                lastPromptTokens, contextWindow, ctxPct);
        ctxLabel.setToolTipText(ctxTooltip);
        ctxProgressBar.setToolTipText(ctxTooltip);

        cacheProgressBar.setValue(lastCacheHitPct);
        String cacheTooltip = String.format(java.util.Locale.US, "Implicit cache hit: %,d / %,d input tokens (%d%%)",
                lastCachedInputTokens, lastPromptTokens, lastCacheHitPct);
        cacheLabel.setToolTipText(cacheTooltip);
        cacheProgressBar.setToolTipText(cacheTooltip);
    }

    void appendMessage(String role, String content) {
        outputArea.appendMessage(role, content);
    }

    void appendToolLog(String output, boolean success, String error) {
        outputArea.appendToolLog(output, success, error);
    }

    void clearOutput() {
        outputArea.clear();
    }

    void clearInputText() {
        fileMentionPopup.hidePopup();
        inputArea.setText("");
    }

    void clearAttachments() {
        attachmentPanel.removeAll();
        attachmentPanel.setVisible(false);
    }

    void showTransitionBanner(String path) {
        transitionLabel.setText(path);
        approveButton.setText("Approve");
        approveButton.setEnabled(true);
        rejectButton.setEnabled(true);
        inlineActionPanel.setVisible(true);
    }

    void hideTransitionBanner() {
        approveButton.setEnabled(false);
        rejectButton.setEnabled(false);
        inlineActionPanel.setVisible(false);
    }

    void showUserInputPrompt(UserInputRequestEvent event, Consumer<Object> onResponse) {
        userInputPromptPanel.removeAll();

        Color accentColor = SwingHtmlUtils.getUiColor(Color.GRAY, "Component.accentColor", "Label.foreground");
        JLabel questionLabel = new JLabel(event.question(), FontIcon.of(Codicons.QUESTION, 16, accentColor),
                SwingConstants.LEFT);
        questionLabel.setFont(questionLabel.getFont().deriveFont(Font.BOLD));
        userInputPromptPanel.add(questionLabel);

        if (StringUtils.isNotBlank(event.details())) {
            JLabel detailsLabel = new JLabel(event.details());
            detailsLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
            userInputPromptPanel.add(detailsLabel);
        }

        PromptType promptType = event.promptType();
        if (promptType == PromptType.CONFIRM) {
            JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            actionsPanel.setOpaque(false);
            JButton yesButton = createActionButton("Yes", Codicons.CHECK, null, false,
                    "background: $Actions.Green; focusedBackground: $Actions.Green; hoverBackground: darken($Actions.Green, 8%); pressedBackground: darken($Actions.Green, 14%); foreground: #ffffff",
                    e -> onResponse.accept(Boolean.TRUE));
            JButton noButton = createActionButton("No", Codicons.CLOSE, null, false,
                    "background: $Actions.Red; focusedBackground: $Actions.Red; hoverBackground: darken($Actions.Red, 8%); pressedBackground: darken($Actions.Red, 14%); foreground: #ffffff",
                    e -> onResponse.accept(Boolean.FALSE));
            actionsPanel.add(yesButton);
            actionsPanel.add(noButton);
            userInputPromptPanel.add(actionsPanel);
        } else if (promptType == PromptType.SINGLE_SELECT) {
            JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            optionsPanel.setOpaque(false);
            for (String option : event.options()) {
                JButton optionButton = new JButton(option);
                optionButton.putClientProperty(FlatClientProperties.STYLE, "arc: 8");
                optionButton.addActionListener(e -> onResponse.accept(option));
                optionsPanel.add(optionButton);
            }
            userInputPromptPanel.add(optionsPanel);
        } else if (promptType == PromptType.MULTI_SELECT) {
            JPanel checksPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
            checksPanel.setOpaque(false);
            List<JCheckBox> checkBoxes = new ArrayList<>(event.options().size());
            for (String option : event.options()) {
                JCheckBox checkBox = new JCheckBox(option);
                checkBox.setOpaque(false);
                checkBoxes.add(checkBox);
                checksPanel.add(checkBox);
            }
            JButton submitButton = createActionButton("Submit", Codicons.CHECK, null, false,
                    "background: $Component.accentColor; focusedBackground: $Component.accentColor; hoverBackground: darken($Component.accentColor, 8%); pressedBackground: darken($Component.accentColor, 14%); foreground: #ffffff",
                    e -> {
                        List<String> selected = new ArrayList<>();
                        for (JCheckBox cb : checkBoxes) {
                            if (cb.isSelected()) {
                                selected.add(cb.getText());
                            }
                        }
                        onResponse.accept(List.copyOf(selected));
                    });
            checksPanel.add(submitButton);
            userInputPromptPanel.add(checksPanel);
        } else if (promptType == PromptType.TEXT) {
            JPanel textPanel = new JPanel(new MigLayout("insets 0, fillx, gap 8", "[grow, fill][]"));
            textPanel.setOpaque(false);
            JTextField textField = new JTextField(StringUtils.defaultString(event.defaultValue()));
            ActionListener submitAction = e -> onResponse.accept(textField.getText());
            textField.addActionListener(submitAction);
            JButton submitButton = createActionButton("Submit", Codicons.CHECK, null, false,
                    "background: $Component.accentColor; focusedBackground: $Component.accentColor; hoverBackground: darken($Component.accentColor, 8%); pressedBackground: darken($Component.accentColor, 14%); foreground: #ffffff",
                    submitAction);
            textPanel.add(textField, "growx");
            textPanel.add(submitButton);
            userInputPromptPanel.add(textPanel);
            SwingUtilities.invokeLater(textField::requestFocusInWindow);
        }

        userInputPromptPanel.setVisible(true);
        userInputPromptPanel.revalidate();
        userInputPromptPanel.repaint();
    }

    void hideUserInputPrompt() {
        userInputPromptPanel.removeAll();
        userInputPromptPanel.setVisible(false);
        userInputPromptPanel.revalidate();
        userInputPromptPanel.repaint();
    }

    boolean confirmResetSession() {
        String message = "Reset session? This cancels active tasks and clears chat history.";
        return JOptionPane.showConfirmDialog(this, message, "Reset",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    NewTopicChoice promptNewTopicChoice(String defaultTopicName) {
        if (newTopicDialogPresenter != null) {
            return newTopicDialogPresenter.apply(defaultTopicName);
        }
        JTextField topicNameField = new JTextField(defaultTopicName, 30);
        topicNameField.selectAll();

        JPanel dialogPanel = new JPanel(new MigLayout("insets 0, fillx, wrap 1", "[grow, fill]"));
        dialogPanel.add(new JLabel(NEW_TOPIC_PROMPT_MESSAGE));
        dialogPanel.add(new JLabel(TOPIC_NAME_LABEL_TEXT), "gaptop 8");
        dialogPanel.add(topicNameField);

        Object[] options = {SAVE_TOPIC_BUTTON_TEXT, DISCARD_TOPIC_BUTTON_TEXT};
        int choice = JOptionPane.showOptionDialog(this, dialogPanel, NEW_TOPIC_BUTTON_TEXT, JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice == JOptionPane.YES_OPTION) {
            return new NewTopicChoice(true, true, topicNameField.getText());
        }
        if (choice == JOptionPane.NO_OPTION) {
            return new NewTopicChoice(true, false, "");
        }
        return new NewTopicChoice(false, false, "");
    }

    void setNewTopicDialogPresenter(Function<String, NewTopicChoice> newTopicDialogPresenter) {
        this.newTopicDialogPresenter = newTopicDialogPresenter;
    }

    private void sendMessage(ActionEvent e) {
        if (e != null) {
            log.debug("Send message triggered by: {}", e.getActionCommand());
        }
        if (!isProjectReady()) {
            return;
        }
        fileMentionPopup.hidePopup();
        controller.sendMessage(inputArea.getText());
    }

    private void chooseImageAttachment() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Image Files (png, jpg, jpeg, gif, bmp)", "png", "jpg",
                "jpeg", "gif", "bmp"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            loadImageAttachmentAsync(chooser.getSelectedFile());
        }
    }

    CompletableFuture<BufferedImage> loadImageAttachmentAsync(File file) {
        if (file == null) {
            return CompletableFuture.completedFuture(null);
        }
        return Edt.supplyAsync(() -> {
            try {
                return ImageIO.read(file);
            } catch (Exception ex) {
                log.error("Failed to load attached image", ex);
                return null;
            }
        }, img -> {
            if (img != null) {
                addPendingImage(img);
            }
        });
    }

    static BufferedImage createThumbnail(BufferedImage source, int size) {
        BufferedImage thumb = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = thumb.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(source, 0, 0, size, size, null);
        } finally {
            g2.dispose();
        }
        return thumb;
    }

    void addPendingImage(Image img) {
        BufferedImage bimage = model.addPendingImage(img);
        attachmentPanel.add(createAttachmentChip(bimage));
        attachmentPanel.setVisible(true);
        attachmentPanel.revalidate();
        attachmentPanel.repaint();
    }

    private JPanel createAttachmentChip(BufferedImage bimage) {
        JLabel thumbLabel = new JLabel(new ImageIcon(createThumbnail(bimage, THUMBNAIL_SIZE)));
        thumbLabel.setBorder(
                BorderFactory.createLineBorder(SwingHtmlUtils.getUiColor(Color.GRAY, "Component.borderColor")));

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(thumbLabel, BorderLayout.CENTER);

        JButton removeBtn = new JButton(FontIcon.of(Codicons.CLOSE, 12));
        removeBtn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        removeBtn.setMargin(new Insets(0, 0, 0, 0));
        removeBtn.addActionListener(ae -> {
            model.removePendingImage(bimage);
            attachmentPanel.remove(wrapper);
            attachmentPanel.setVisible(model.hasPendingImages());
            attachmentPanel.revalidate();
            attachmentPanel.repaint();
        });
        wrapper.add(removeBtn, BorderLayout.NORTH);
        return wrapper;
    }

    void setLoading(boolean loading) {
        this.loading = loading;
        boolean enableComposer = !loading && isProjectReady();
        stopButton.setEnabled(loading);
        stopButton.setVisible(loading);
        sendButton.setEnabled(enableComposer);
        sendButton.setVisible(!loading);
        attachImageButton.setEnabled(enableComposer);
        mentionButton.setEnabled(enableComposer);
        inputArea.setEnabled(enableComposer);
        if (model.isTaskMode()) {
            boolean enableDecision = !loading && controller.hasPendingPhase();
            approveButton.setEnabled(enableDecision);
            rejectButton.setEnabled(enableDecision);
        }
        if (loading) {
            sendButton.setText("Sending...");
        } else {
            SwingUtilities.invokeLater(inputArea::requestFocusInWindow);
            sendButton.setText("Send");
        }
        for (Consumer<Boolean> listener : loadingListeners) {
            listener.accept(loading);
        }
    }

    public void addLoadingListener(Consumer<Boolean> listener) {
        if (listener != null) {
            loadingListeners.add(listener);
        }
    }

    public List<MarkdownPane.ChatTranscriptEntry> getConversationMessages() {
        return outputArea.getConversationMessages();
    }

    public void focusInput() {
        inputArea.requestFocusInWindow();
        inputArea.setCaretPosition(inputArea.getDocument().getLength());
    }

    private void setupUndoRedo() {
        UndoManager undoManager = new UndoManager();
        inputArea.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));

        InputMap inputMap = inputArea.getInputMap();
        ActionMap actionMap = inputArea.getActionMap();
        int menuShortcutMask;
        if (GraphicsEnvironment.isHeadless()) {
            menuShortcutMask = java.awt.event.InputEvent.CTRL_DOWN_MASK;
        } else {
            menuShortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        }

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuShortcutMask), "Undo");
        actionMap.put("Undo", createAction(() -> {
            if (undoManager.canUndo()) {
                undoManager.undo();
            }
        }));

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, menuShortcutMask), "Redo");
        inputMap.put(
                KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuShortcutMask | java.awt.event.InputEvent.SHIFT_DOWN_MASK),
                "Redo");
        actionMap.put("Redo", createAction(() -> {
            if (undoManager.canRedo()) {
                undoManager.redo();
            }
        }));
    }

    private void setupKeyboardShortcuts() {
        InputMap im = inputArea.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = inputArea.getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send-message");
        am.put("send-message", createAction(() -> {
            if (fileMentionPopup != null && fileMentionPopup.isPopupVisible()) {
                fileMentionPopup.acceptSelected();
                return;
            }
            sendMessage(null);
        }));

        bindPopupKey(im, am, KeyEvent.VK_TAB, "autocomplete-or-tab", fileMentionPopup::acceptSelected,
                () -> inputArea.insert("\t", inputArea.getCaretPosition()));
        bindPopupKey(im, am, KeyEvent.VK_UP, "autocomplete-or-up", fileMentionPopup::selectPrevious, null);
        bindPopupKey(im, am, KeyEvent.VK_DOWN, "autocomplete-or-down", fileMentionPopup::selectNext, null);
        bindPopupKey(im, am, KeyEvent.VK_ESCAPE, "dismiss-autocomplete", fileMentionPopup::hidePopup, null);

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-newline");
        am.put("insert-newline", createAction(() -> {
            if (fileMentionPopup != null) {
                fileMentionPopup.hidePopup();
            }
            inputArea.insert("\n", inputArea.getCaretPosition());
        }));

        installClipboardPasteAction();
    }

    private void installClipboardPasteAction() {
        Action defaultPaste = inputArea.getActionMap().get(DefaultEditorKit.pasteAction);
        inputArea.getActionMap().put(DefaultEditorKit.pasteAction, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                    try {
                        addPendingImage((Image) clipboard.getData(DataFlavor.imageFlavor));
                    } catch (Exception ex) {
                        log.error("Failed to paste image", ex);
                    }
                } else {
                    defaultPaste.actionPerformed(e);
                }
            }
        });
    }

    private void bindPopupKey(InputMap im, ActionMap am, int keyCode, String actionName, Runnable onPopupVisible,
            Runnable fallbackWhenNoDefault) {
        KeyStroke keyStroke = KeyStroke.getKeyStroke(keyCode, 0);
        Object existingKey = im.get(keyStroke);
        Action defaultAction;
        if (existingKey != null) {
            defaultAction = am.get(existingKey);
        } else {
            defaultAction = null;
        }
        im.put(keyStroke, actionName);
        am.put(actionName, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                if (fileMentionPopup != null && fileMentionPopup.isPopupVisible()) {
                    onPopupVisible.run();
                    return;
                }
                if (defaultAction != null) {
                    defaultAction.actionPerformed(e);
                } else if (fallbackWhenNoDefault != null) {
                    fallbackWhenNoDefault.run();
                }
            }
        });
    }
}
