package org.roxycode.app.ui;

import org.apache.commons.lang3.StringUtils;
import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import javax.swing.undo.UndoManager;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.UserInputService;
import org.roxycode.app.events.NavigateToViewEvent;
import org.roxycode.app.events.UserInputRequestEvent;
import org.roxycode.app.model.ChatTranscriptEntry;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@SuppressWarnings("PMD.NullAssignment")
final class ChatPanel extends JPanel implements AutoCloseable {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(ChatPanel.class);

    static final String NEW_TOPIC_BUTTON_TEXT = "New Topic";
    static final String RESET_SESSION_BUTTON_TEXT = "Reset Session";
    static final String NEW_TOPIC_TOOLTIP = "Archive current conversation and start a new Q&A topic";
    static final String DEFAULT_PLACEHOLDER_TEXT = "Type a message... (Enter to send, Shift+Enter for newline, @ to mention files, drag & drop or Ctrl+V to attach text, PDFs & images)";
    static final String NO_PROJECT_PLACEHOLDER_TEXT = "Select a project folder in the top bar (Change Project...) to start chatting...";
    static final String BUSY_PLACEHOLDER_TEXT = "Roxy is working... Press Esc or click Stop to interrupt.";
    private static final String TOOLBAR_BUTTON_STYLE = "font: -1; foreground: $Label.disabledForeground; focusWidth: 0; innerFocusWidth: 0";
    private static final String GROW_FILL_COL = "[grow, fill]";
    private static final String HIDEMODE_3 = "hidemode 3";
    private static final String LAYOUT_CENTER = "center";
    static final int THUMBNAIL_SIZE = 64;
    static final int COMPOSER_MIN_HEIGHT = 120;
    static final int COMPOSER_PREFERRED_HEIGHT = 140;
    static final double SPLIT_PANE_RESIZE_WEIGHT = 0.85;
    private static final int AUTO_SCROLL_BOTTOM_THRESHOLD_PX = 80;

    final transient ChatModel model;
    final transient ChatController controller;
    MarkdownPane outputArea;
    JTextArea inputArea;
    JButton sendButton;
    JButton stopButton;
    JButton attachImageButton;
    JButton mentionButton;
    JButton viewPlanButton;
    JButton copyCommitMsgButton;
    JButton approveButton;
    JButton rejectButton;
    private Timer copyCommitMsgTimer;
    private final transient PlanService planService;
    private final transient ApplicationEventPublisher eventPublisher;
    JButton resetSessionButton;
    JCheckBox autoApprovePlanCheckBox;
    final JPanel inlineActionPanel;
    final ChatPromptBanner userInputPromptPanel;
    final ChatActivityStrip activityStripPanel;
    JLabel transitionLabel;
    JLabel transitionSubtitleLabel;
    final transient ChatTelemetryBar telemetryBar;
    private JScrollPane outputScrollPane;
    private ChatAttachmentBar attachmentPanel;
    transient FileMentionPopup fileMentionPopup;
    private transient ProjectService projectService;
    private transient Runnable onViewPlanAction;
    private String defaultPlaceholderText = DEFAULT_PLACEHOLDER_TEXT;
    private String lastAppliedPlaceholderText;
    private boolean loading = false;
    private final List<Consumer<Boolean>> loadingListeners = new ArrayList<>();

    @Configuration
    static class ChatPanelBeans {
        @Bean("exploreChatPanel")
        @Lazy
        ChatPanel exploreChatPanel(AiService aiService, PlanService planService,
                ApplicationEventPublisher eventPublisher, ChatMemory chatMemory, SettingsService settingsService,
                ExecutorService chatTurnExecutor, MemoryService memoryService, EnvironmentService environmentService) {
            return createExplorePanel(aiService, planService, eventPublisher, chatMemory, settingsService,
                    chatTurnExecutor, memoryService, environmentService);
        }

        @Bean("featureChatPanel")
        @Lazy
        ChatPanel featureChatPanel(AiService aiService, PlanService planService,
                ApplicationEventPublisher eventPublisher, ChatMemory chatMemory, SettingsService settingsService,
                ExecutorService chatTurnExecutor) {
            return createFeaturePanel(aiService, planService, eventPublisher, chatMemory, settingsService,
                    chatTurnExecutor);
        }

        @Bean("exploreChatController")
        @Lazy
        ChatController exploreChatController(@Qualifier("exploreChatPanel") ChatPanel exploreChatPanel) {
            return exploreChatPanel.controller;
        }

        @Bean("featureChatController")
        @Lazy
        ChatController featureChatController(@Qualifier("featureChatPanel") ChatPanel featureChatPanel) {
            return featureChatPanel.controller;
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
        this.planService = planService;
        this.eventPublisher = eventPublisher;
        this.model = new ChatModel(sessionPrefix, lockedPhase, isTaskMode);
        this.controller = new ChatController(this.model, this, aiService, planService, eventPublisher, chatMemory,
                settingsService, turnExecutor);
        setLayout(new BorderLayout());

        this.inlineActionPanel = createInlineActionPanel();
        this.userInputPromptPanel = new ChatPromptBanner();
        this.activityStripPanel = new ChatActivityStrip(this.model, this.planService, () -> this.loading);
        this.telemetryBar = new ChatTelemetryBar(getFont());
        Component topComponent = createOutputSection(isTaskMode);
        JPanel inputSection = createInputSection(isTaskMode);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topComponent, inputSection);
        splitPane.setResizeWeight(SPLIT_PANE_RESIZE_WEIGHT);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        add(splitPane, BorderLayout.CENTER);

        SwingHtmlUtils.installTextContextMenu(inputArea);
        setupUndoRedo();
        setupKeyboardShortcuts();
        attachmentPanel.installDragAndDrop(this, outputArea, outputScrollPane, inputArea);
        updateProjectComposerState();
    }

    private JPanel createInlineActionPanel() {
        JPanel panel = new JPanel(new MigLayout("insets 0, fillx, hidemode 3", "[grow, fill][]", LAYOUT_CENTER));
        SwingHtmlUtils.applyTintedBannerStyle(panel, SwingHtmlUtils.getAccentColor(), 10, 16, 20);
        panel.setVisible(false);

        JPanel textCol = new JPanel(new MigLayout("insets 0, gapy 2", "[grow, fill]", "[]"));
        textCol.setOpaque(false);
        transitionLabel = ChatPromptBanner.createPromptHeaderLabel("Workflow Transition", Codicons.ROCKET);
        transitionLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Component.accentColor");
        transitionSubtitleLabel = new JLabel("");
        transitionSubtitleLabel.putClientProperty(FlatClientProperties.STYLE,
                "font: -1; foreground: $Label.disabledForeground");
        textCol.add(transitionLabel, "wrap");
        textCol.add(transitionSubtitleLabel);

        approveButton = UiComponents.createActionButton("Approve", Codicons.CHECK, null, false,
                UiComponents.ACCENT_BUTTON_STYLE, e -> controller.approvePhase());
        approveButton.setEnabled(false);

        viewPlanButton = UiComponents.createActionButton("View Plan", Codicons.LIST_ORDERED, null, false, null, e -> {
            if (onViewPlanAction != null) {
                onViewPlanAction.run();
            }
        });
        viewPlanButton.setVisible(false);

        copyCommitMsgButton = UiComponents.createCopyButton("Copy Commit Msg", true, this::copyPendingCommitMessage);
        copyCommitMsgButton.setVisible(false);

        rejectButton = UiComponents.createActionButton("Reject", Codicons.CLOSE, null, false,
                UiComponents.SUBTLE_RED_BUTTON_STYLE, e -> controller.rejectPhase());
        rejectButton.setEnabled(false);

        JPanel btnCol = new JPanel(new MigLayout("insets 0, gapx 6, hidemode 3", "", LAYOUT_CENTER));
        btnCol.setOpaque(false);
        btnCol.add(viewPlanButton);
        btnCol.add(copyCommitMsgButton);
        btnCol.add(approveButton);
        btnCol.add(rejectButton);

        panel.add(textCol, "growx, wmin 0");
        panel.add(btnCol, "alignx right");
        return panel;
    }

    private Component createOutputSection(boolean isTaskMode) {
        outputArea = new MarkdownPane(isTaskMode);
        outputArea.setOnStarterPromptListener(prompt -> {
            setInputText(prompt);
            focusInput();
        });
        outputArea.setOnMoveToFeatureListener(content -> {
            if (this.eventPublisher != null) {
                this.eventPublisher.publishEvent(new NavigateToViewEvent(MainFrame.TASK_VIEW, content));
            }
        });
        outputScrollPane = new JScrollPane(outputArea);
        outputScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        updateOutputScrollPaneBorder();

        outputArea.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                JScrollBar vertical = outputScrollPane.getVerticalScrollBar();
                if (isScrollBarNearBottom(vertical)) {
                    SwingUtilities.invokeLater(() -> vertical.setValue(vertical.getMaximum()));
                }
            }
        });

        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.setOpaque(false);
        southPanel.add(userInputPromptPanel, BorderLayout.NORTH);
        southPanel.add(activityStripPanel, BorderLayout.CENTER);
        if (isTaskMode) {
            southPanel.add(inlineActionPanel, BorderLayout.SOUTH);
        }
        JPanel outputPanel = new JPanel(new BorderLayout());
        outputPanel.add(outputScrollPane, BorderLayout.CENTER);
        outputPanel.add(southPanel, BorderLayout.SOUTH);
        return outputPanel;
    }

    private JPanel createInputSection(boolean isTaskMode) {
        attachmentPanel = new ChatAttachmentBar(model, this::isProjectReady);

        JPanel inputSection = new JPanel(new MigLayout("fill, insets 6 12 4 12", GROW_FILL_COL, GROW_FILL_COL));
        inputSection.setMinimumSize(new Dimension(0, COMPOSER_MIN_HEIGHT));
        inputSection.setPreferredSize(new Dimension(0, COMPOSER_PREFERRED_HEIGHT));

        JPanel composerCard = new JPanel(new BorderLayout());
        updateComposerCardBorder(composerCard, false);

        inputArea = new JTextArea(4, 0);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setOpaque(false);
        inputArea.setMargin(new Insets(10, 14, 6, 14));
        inputArea.setBorder(BorderFactory.createEmptyBorder(10, 14, 6, 14));
        inputArea.putClientProperty(FlatClientProperties.STYLE, "border: 10,14,6,14; background: null");
        inputArea.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, DEFAULT_PLACEHOLDER_TEXT);
        inputArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                updateComposerCardBorder(composerCard, true);
            }

            @Override
            public void focusLost(FocusEvent e) {
                updateComposerCardBorder(composerCard, false);
            }
        });
        JScrollPane inputScrollPane = new JScrollPane(inputArea);
        inputScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        inputScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inputScrollPane.setBorder(BorderFactory.createEmptyBorder());
        inputScrollPane.putClientProperty(FlatClientProperties.STYLE, "border: null; background: null");
        inputScrollPane.setOpaque(false);
        inputScrollPane.getViewport().setOpaque(false);
        fileMentionPopup = new FileMentionPopup(inputArea, inputScrollPane);

        sendButton = UiComponents.createActionButton("Send", Codicons.CHEVRON_RIGHT, new Insets(5, 14, 5, 14), false,
                "arc: 8; font: bold; " + UiComponents.ACCENT_BUTTON_STYLE, this::sendMessage);
        sendButton.setToolTipText("Send message (Enter)");

        stopButton = UiComponents.createActionButton("Stop", Codicons.DEBUG_STOP, new Insets(5, 14, 5, 14), false,
                "arc: 8; font: bold; " + UiComponents.RED_BUTTON_STYLE, e -> controller.stopTurn());
        stopButton.setToolTipText("Stop active turn (Esc)");
        stopButton.setEnabled(false);
        stopButton.setVisible(false);

        attachImageButton = UiComponents.createActionButton("Attach", Codicons.FILE_MEDIA, new Insets(4, 8, 4, 8), true,
                TOOLBAR_BUTTON_STYLE, e -> chooseAttachment());
        attachImageButton.setToolTipText("Attach Image, PDF, or Text/Log File (or drag & drop / paste with Ctrl+V)");

        String resetText;
        Codicons resetIcon;
        if (isTaskMode) {
            resetText = RESET_SESSION_BUTTON_TEXT;
            resetIcon = Codicons.REFRESH;
        } else {
            resetText = NEW_TOPIC_BUTTON_TEXT;
            resetIcon = Codicons.COMMENT_DISCUSSION;
        }
        resetSessionButton = UiComponents.createActionButton(resetText, resetIcon, new Insets(4, 8, 4, 8), true,
                TOOLBAR_BUTTON_STYLE, e -> controller.resetSession());
        if (!isTaskMode) {
            resetSessionButton.setToolTipText(NEW_TOPIC_TOOLTIP);
        }

        mentionButton = UiComponents.createActionButton("Mention", Codicons.MENTION, new Insets(4, 8, 4, 8), true,
                TOOLBAR_BUTTON_STYLE, e -> triggerMentionPopup());
        mentionButton.setToolTipText("Insert @ to search and mention project files");

        updateTurnLabel();

        JSeparator toolbarSep = new JSeparator(SwingConstants.VERTICAL);
        String toolbarCols;
        if (isTaskMode) {
            toolbarCols = "[]4[]6[]6[]8[]push[]push[right]";
        } else {
            toolbarCols = "[]4[]6[]6[]push[]push[right]";
        }
        JPanel actionToolbar = new JPanel(new MigLayout("insets 4 8 6 8, fillx", toolbarCols, LAYOUT_CENTER));
        actionToolbar.setOpaque(false);
        actionToolbar.add(attachImageButton);
        actionToolbar.add(mentionButton);
        actionToolbar.add(toolbarSep, "h 16!");
        actionToolbar.add(resetSessionButton);
        if (isTaskMode) {
            autoApprovePlanCheckBox = new JCheckBox("Auto-approve Plan");
            autoApprovePlanCheckBox.setOpaque(false);
            autoApprovePlanCheckBox.setToolTipText(
                    "Automatically approve PLAN → CODE transitions so Roxy starts coding immediately after submitting the plan");
            autoApprovePlanCheckBox.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            actionToolbar.add(autoApprovePlanCheckBox);
        }
        actionToolbar.add(telemetryBar.telemetryPanel, "wmin 0");
        actionToolbar.add(sendButton, "split 2, alignx right, " + HIDEMODE_3);
        actionToolbar.add(stopButton, "alignx right, " + HIDEMODE_3);

        composerCard.add(attachmentPanel, BorderLayout.NORTH);
        composerCard.add(inputScrollPane, BorderLayout.CENTER);
        composerCard.add(actionToolbar, BorderLayout.SOUTH);

        inputSection.add(composerCard, "grow");
        return inputSection;
    }

    static void updateComposerCardBorder(JPanel composerCard, boolean focused) {
        String borderColorToken;
        if (focused) {
            borderColorToken = "$Component.focusColor";
        } else {
            borderColorToken = "$Component.borderColor";
        }
        composerCard.putClientProperty(FlatClientProperties.STYLE,
                "arc: 12; background: $TextArea.background; border: 1,1,1,1," + borderColorToken);
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

    void updateActivityStatus(String statusText) {
        activityStripPanel.updateStatus(statusText);
    }

    @Override
    public void removeNotify() {
        activityStripPanel.stopTimers();
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
        if (inlineActionPanel != null && userInputPromptPanel != null) {
            applyInlineBannerStyling();
        }
        if (fileMentionPopup != null) {
            fileMentionPopup.updateTheme();
        }
    }

    void applyInlineBannerStyling() {
        Color accent = SwingHtmlUtils.getAccentColor();
        SwingHtmlUtils.applyTintedBannerStyle(inlineActionPanel, accent, 10, 16, 20);
        if (transitionLabel != null && transitionLabel.getIcon() instanceof FontIcon fontIcon) {
            transitionLabel.setIcon(FontIcon.of(fontIcon.getIkon(), fontIcon.getIconSize(), accent));
        }
        userInputPromptPanel.applyThemeStyling();
        if (activityStripPanel != null) {
            activityStripPanel.applyStyling();
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
    public void setGitService(GitService gitService) {
        controller.setGitService(gitService);
    }

    @Autowired(required = false)
    public void setProjectService(ProjectService projectService) {
        if (Objects.equals(this.projectService, projectService)) {
            updateProjectComposerState();
            return;
        }
        this.projectService = projectService;
        controller.setProjectService(projectService);
        updateProjectComposerState();
    }

    private boolean isProjectReady() {
        return projectService == null || projectService.hasActiveProject();
    }

    void updateProjectComposerState() {
        boolean projectReady = isProjectReady();
        if (!projectReady) {
            defaultPlaceholderText = NO_PROJECT_PLACEHOLDER_TEXT;
        } else if (NO_PROJECT_PLACEHOLDER_TEXT.equals(defaultPlaceholderText)) {
            defaultPlaceholderText = DEFAULT_PLACEHOLDER_TEXT;
        }
        updateInputPlaceholderText();
        boolean enableComposer = !loading && projectReady;
        inputArea.setEnabled(enableComposer);
        sendButton.setEnabled(enableComposer);
        attachImageButton.setEnabled(enableComposer);
        resetSessionButton.setEnabled(enableComposer);
        mentionButton.setEnabled(enableComposer);
        if (autoApprovePlanCheckBox != null) {
            autoApprovePlanCheckBox.setEnabled(projectReady);
        }
    }

    private void updateInputPlaceholderText() {
        if (inputArea == null) {
            return;
        }
        String placeholder;
        if (loading) {
            placeholder = BUSY_PLACEHOLDER_TEXT;
        } else {
            placeholder = defaultPlaceholderText;
        }
        if (!Objects.equals(lastAppliedPlaceholderText, placeholder)) {
            lastAppliedPlaceholderText = placeholder;
            inputArea.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        }
    }

    public void setOnViewPlanAction(Runnable action) {
        this.onViewPlanAction = action;
        if (viewPlanButton != null) {
            viewPlanButton.setVisible(action != null);
        }
    }

    public void setInputText(String text) {
        if (inputArea != null) {
            inputArea.setText(text);
            inputArea.requestFocusInWindow();
        }
    }

    static String stripTrailingMoveToFeatureCta(String content) {
        if (StringUtils.isBlank(content)) {
            return "";
        }
        List<String> lines = new ArrayList<>(content.lines().toList());
        while (!lines.isEmpty() && isTrailingMoveToFeatureCtaLine(lines.get(lines.size() - 1))) {
            lines.remove(lines.size() - 1);
        }
        String cleaned = String.join(System.lineSeparator(), lines).trim();
        if (cleaned.isEmpty()) {
            return content.trim();
        }
        return cleaned;
    }

    private static boolean isTrailingMoveToFeatureCtaLine(String line) {
        String trimmed = StringUtils.defaultString(line).trim();
        if (trimmed.isEmpty() || "---".equals(trimmed) || "***".equals(trimmed)) {
            return true;
        }
        return trimmed.contains("[Move to Feature Request]");
    }

    public boolean submitPrompt(String prompt) {
        if (StringUtils.isBlank(prompt)) {
            return false;
        }
        String trimmedPrompt = prompt.trim();
        boolean canAutoSubmit = isProjectReady() && !isLoading() && !isWaitingUserInput()
                && !controller.hasPendingPhase() && !inlineActionPanel.isVisible();
        if (canAutoSubmit) {
            fileMentionPopup.hidePopup();
            clearInputText();
            controller.sendMessage(trimmedPrompt);
            return true;
        }
        setInputText(trimmedPrompt);
        return false;
    }

    public boolean submitMovedFeatureRequest(String content) {
        if (StringUtils.isBlank(content)) {
            return false;
        }
        return submitPrompt(stripTrailingMoveToFeatureCta(content));
    }

    public void notifyFeatureRequestSent(boolean autoSubmitted) {
        String notice;
        if (autoSubmitted) {
            notice = "Feature request sent to **Feature Development**.";
        } else {
            notice = "Feature request copied to the **Feature Development** composer.";
        }
        appendMessage(ChatHtmlTemplates.ROLE_SYSTEM, notice);
        controller.persistActiveChatSession();
    }

    static boolean isScrollBarNearBottom(JScrollBar scrollBar) {
        if (scrollBar == null) {
            return true;
        }
        int remaining = scrollBar.getMaximum() - (scrollBar.getValue() + scrollBar.getVisibleAmount());
        return remaining <= AUTO_SCROLL_BOTTOM_THRESHOLD_PX;
    }

    public boolean isAutoApprovePlanEnabled() {
        return autoApprovePlanCheckBox != null && autoApprovePlanCheckBox.isSelected();
    }

    public void setAutoApprovePlanEnabled(boolean enabled) {
        if (autoApprovePlanCheckBox != null) {
            autoApprovePlanCheckBox.setSelected(enabled);
        }
    }

    void setInputPlaceholderText(String placeholder) {
        if (StringUtils.isNotBlank(placeholder)) {
            this.defaultPlaceholderText = placeholder;
        } else if (isProjectReady()) {
            this.defaultPlaceholderText = DEFAULT_PLACEHOLDER_TEXT;
        } else {
            this.defaultPlaceholderText = NO_PROJECT_PLACEHOLDER_TEXT;
        }
        updateInputPlaceholderText();
    }

    void triggerMentionPopup() {
        if (!inputArea.isEnabled()) {
            return;
        }
        inputArea.requestFocusInWindow();
        String text = inputArea.getText();
        int caret = inputArea.getCaretPosition();
        FileMentionPopup.MentionToken activeToken = FileMentionPopup.extractMentionToken(text, caret);
        if (activeToken == null) {
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

    void invalidateFileMentionCache() {
        fileMentionPopup.invalidateCache();
    }

    void updateTurnLabel() {
        telemetryBar.update(model, controller.formatCurrentTurnStatus());
        updateInputPlaceholderText();
    }

    void appendMessage(String role, String content) {
        outputArea.appendMessage(role, content);
    }

    void appendPreRenderedMessage(String role, String content, String renderedHtml) {
        outputArea.appendPreRenderedMessage(role, content, renderedHtml, List.of(), List.of());
    }

    void appendPreRenderedMessage(String role, String content, String renderedHtml, List<String> thumbnailBase64List,
            List<String> attachedFileNames) {
        outputArea.appendPreRenderedMessage(role, content, renderedHtml, thumbnailBase64List, attachedFileNames);
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
        setInputPlaceholderText(null);
    }

    void clearAttachments() {
        attachmentPanel.clearAttachments();
    }

    private void copyPendingCommitMessage() {
        if (planService == null || planService.getCurrentPlan() == null) {
            return;
        }
        String commitMsg = PlanService.formatCommitMessage(planService.getCurrentPlan());
        if (StringUtils.isBlank(commitMsg)) {
            return;
        }
        copyCommitMsgTimer = UiComponents.copyWithFeedback(copyCommitMsgButton, copyCommitMsgTimer, commitMsg,
                "Copy Commit Msg", 1500);
    }

    void updateCopyCommitMsgButtonState(String bannerText) {
        if (copyCommitMsgButton == null) {
            return;
        }
        boolean isReviewToPlan = (planService != null && planService.getCurrentPhase() == WorkflowPhase.REVIEW
                && planService.getPendingPhase() == WorkflowPhase.PLAN)
                || PlanService.formatApprovalLabel(WorkflowPhase.REVIEW, WorkflowPhase.PLAN).equals(bannerText);
        String commitMsg = null;
        if (planService != null && planService.getCurrentPlan() != null) {
            commitMsg = PlanService.formatCommitMessage(planService.getCurrentPlan());
        }
        boolean showCopyCommit = isReviewToPlan && StringUtils.isNotBlank(commitMsg);
        copyCommitMsgButton.setEnabled(showCopyCommit);
        copyCommitMsgButton.setVisible(showCopyCommit);
    }

    static String resolveTransitionSubtitle(String bannerText, WorkflowPhase currentPhase, WorkflowPhase pendingPhase) {
        if ((currentPhase == WorkflowPhase.REVIEW && pendingPhase == WorkflowPhase.PLAN)
                || PlanService.formatApprovalLabel(WorkflowPhase.REVIEW, WorkflowPhase.PLAN).equals(bannerText)) {
            return "Approving archives this completed plan to Past Plans and resets the workspace for your next feature.";
        }
        if ((currentPhase == WorkflowPhase.PLAN && pendingPhase == WorkflowPhase.CODE)
                || PlanService.formatApprovalLabel(WorkflowPhase.PLAN, WorkflowPhase.CODE).equals(bannerText)) {
            return "Review the implementation blueprint before unlocking file editing in the Code phase.";
        }
        return "Review the proposed workflow transition and approve or reject to continue.";
    }

    void showTransitionBanner(String path) {
        transitionLabel.setText(path);
        WorkflowPhase currentPhase = null;
        WorkflowPhase pendingPhase = null;
        if (planService != null) {
            currentPhase = planService.getCurrentPhase();
            pendingPhase = planService.getPendingPhase();
        }
        transitionSubtitleLabel.setText(resolveTransitionSubtitle(path, currentPhase, pendingPhase));
        approveButton.setText("Approve");
        approveButton.setEnabled(true);
        rejectButton.setEnabled(true);
        updateCopyCommitMsgButtonState(path);
        inlineActionPanel.setVisible(true);
    }

    void hideTransitionBanner() {
        approveButton.setEnabled(false);
        rejectButton.setEnabled(false);
        if (copyCommitMsgButton != null) {
            copyCommitMsgButton.setVisible(false);
        }
        inlineActionPanel.setVisible(false);
    }

    void showUserInputPrompt(UserInputRequestEvent event, Consumer<Object> onResponse) {
        userInputPromptPanel.showUserInputPrompt(event, onResponse);
    }

    boolean cancelActivePrompt() {
        return userInputPromptPanel.cancelActivePrompt();
    }

    void hideUserInputPrompt() {
        userInputPromptPanel.hidePrompt();
    }

    void showResetSessionPrompt(Runnable onConfirm) {
        userInputPromptPanel.showResetSessionPrompt(onConfirm);
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

    private void chooseAttachment() {
        attachmentPanel.chooseAttachment(this);
    }

    private void addPendingImage(Image img) {
        attachmentPanel.addPendingImage(img);
    }

    void setLoading(boolean loading) {
        this.loading = loading;
        stopButton.setEnabled(loading);
        stopButton.setVisible(loading);
        sendButton.setVisible(!loading);
        updateProjectComposerState();
        if (model.isTaskMode()) {
            boolean enableDecision = !loading && controller.hasPendingPhase();
            approveButton.setEnabled(enableDecision);
            rejectButton.setEnabled(enableDecision);
        }
        if (loading) {
            sendButton.setText("Sending...");
            activityStripPanel.startTimers();
        } else {
            activityStripPanel.reset();
            SwingUtilities.invokeLater(inputArea::requestFocusInWindow);
            sendButton.setText("Send");
        }
        for (Consumer<Boolean> listener : loadingListeners) {
            listener.accept(loading);
        }
    }

    public boolean isLoading() {
        return loading;
    }

    public boolean isWaitingUserInput() {
        if (controller != null && controller.isWaitingUserInput()) {
            return true;
        }
        return userInputPromptPanel.isVisible();
    }

    public void addUserInputListener(Consumer<Boolean> listener) {
        userInputPromptPanel.addVisibilityListener(listener);
    }

    public void addLoadingListener(Consumer<Boolean> listener) {
        if (listener != null) {
            loadingListeners.add(listener);
        }
    }

    public List<ChatTranscriptEntry> getConversationMessages() {
        return outputArea.getConversationMessages();
    }

    public boolean hasExportableContent() {
        return outputArea.hasExportableContent();
    }

    public void addExportStateListener(Runnable listener) {
        outputArea.addContentChangeListener(listener);
    }

    public List<MarkdownPane.PersistedChatEntry> exportMessages() {
        return outputArea.exportMessages();
    }

    public void restoreMessages(List<MarkdownPane.PersistedChatEntry> entries) {
        outputArea.restoreMessages(entries);
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
        int menuShortcutMask = UiComponents.resolveMenuShortcutMask();

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
        bindPopupKey(im, am, KeyEvent.VK_ESCAPE, "dismiss-autocomplete", fileMentionPopup::hidePopup,
                this::handleEscapeKey);

        InputMap panelIm = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap panelAm = getActionMap();
        panelIm.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "stop-active-turn");
        panelAm.put("stop-active-turn", createAction(() -> {
            if (fileMentionPopup != null && fileMentionPopup.isPopupVisible()) {
                fileMentionPopup.hidePopup();
                return;
            }
            handleEscapeKey();
        }));

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-newline");
        am.put("insert-newline", createAction(() -> {
            if (fileMentionPopup != null) {
                fileMentionPopup.hidePopup();
            }
            inputArea.insert("\n", inputArea.getCaretPosition());
        }));

        installClipboardPasteAction();
    }

    private void handleEscapeKey() {
        if (!loading && cancelActivePrompt()) {
            return;
        }
        if (loading) {
            controller.stopTurn();
        }
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
