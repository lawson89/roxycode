package org.roxycode.app.ui;

import org.apache.commons.lang3.StringUtils;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.SystemFileChooser;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import javax.swing.undo.UndoManager;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.UserInputService;
import org.roxycode.app.events.UserInputRequestEvent;
import org.roxycode.app.model.ChatTranscriptEntry;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.Edt;
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
public final class ChatPanel extends JPanel implements AutoCloseable {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(ChatPanel.class);

    static final String NEW_TOPIC_BUTTON_TEXT = "New Topic";
    static final String RESET_SESSION_BUTTON_TEXT = "Reset Session";
    static final String NEW_TOPIC_TOOLTIP = "Archive current conversation and start a new Q&A topic";
    static final String DEFAULT_PLACEHOLDER_TEXT = "Type a message... (Enter to send, Shift+Enter for newline, @ to mention files, Ctrl+V to paste image)";
    static final String NO_PROJECT_PLACEHOLDER_TEXT = "Select a project folder in the top bar (Change Project...) to start chatting...";
    private static final String TOOLBAR_BUTTON_STYLE = "arc: 8; font: -1; foreground: $Label.disabledForeground";
    private static final String GROW_FILL_COL = "[grow, fill]";
    private static final String HIDEMODE_3 = "hidemode 3";
    static final int THUMBNAIL_SIZE = 64;
    static final int COMPOSER_MIN_HEIGHT = 215;
    static final int COMPOSER_PREFERRED_HEIGHT = 225;
    static final double SPLIT_PANE_RESIZE_WEIGHT = 0.85;
    private static final int AUTO_SCROLL_BOTTOM_THRESHOLD_PX = 80;
    private static final int ACTIVITY_ANIMATION_DELAY_MS = 500;
    private static final int ACTIVITY_ELAPSED_DELAY_MS = 1000;
    private static final FontAwesomeSolid[] HOURGLASS_FRAMES = {FontAwesomeSolid.HOURGLASS_START,
            FontAwesomeSolid.HOURGLASS_HALF, FontAwesomeSolid.HOURGLASS_END};

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
    JButton copyCommitMsgButton;
    JButton approveButton;
    JButton rejectButton;
    private Timer copyCommitMsgTimer;
    String lastCopiedCommitMessage;
    private final transient PlanService planService;
    JButton resetSessionButton;
    JCheckBox autoApprovePlanCheckBox;
    final JPanel inlineActionPanel;
    final ChatPromptBanner userInputPromptPanel;
    final JPanel activityStripPanel;
    JLabel activityStatusLabel;
    JLabel activityElapsedLabel;
    transient FontIcon activityIcon;
    Timer activityAnimationTimer;
    Timer activityElapsedTimer;
    private int activityIconFrameIndex;
    private long turnStartMillis;
    JLabel transitionLabel;
    final transient ChatTelemetryBar telemetryBar;
    private JScrollPane outputScrollPane;
    private JPanel attachmentPanel;
    transient FileMentionPopup fileMentionPopup;
    private transient ProjectService projectService;
    private transient Runnable onViewPlanAction;
    private String defaultPlaceholderText = DEFAULT_PLACEHOLDER_TEXT;
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
        this.model = new ChatModel(sessionPrefix, lockedPhase, isTaskMode);
        this.controller = new ChatController(this.model, this, aiService, planService, eventPublisher, chatMemory,
                settingsService, turnExecutor);
        setLayout(new BorderLayout());

        this.inlineActionPanel = createInlineActionPanel();
        this.userInputPromptPanel = new ChatPromptBanner();
        this.activityStripPanel = createActivityStripPanel();
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
        updateProjectComposerState();
    }

    private JPanel createInlineActionPanel() {
        JPanel panel = new JPanel(new MigLayout("insets 0, gap 15", "", "center"));
        SwingHtmlUtils.applyTintedBannerStyle(panel, SwingHtmlUtils.getAccentColor(), 10, 16, 20);
        panel.setVisible(false);

        transitionLabel = ChatPromptBanner.createPromptHeaderLabel("Workflow Transition", Codicons.ROCKET);
        transitionLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Component.accentColor");

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

        panel.add(transitionLabel);
        panel.add(Box.createHorizontalStrut(10));
        panel.add(viewPlanButton, HIDEMODE_3);
        panel.add(copyCommitMsgButton, HIDEMODE_3);
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
        attachmentPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        attachmentPanel.setOpaque(false);
        attachmentPanel.setVisible(false);

        JPanel inputSection = new JPanel(new MigLayout("fill, insets 6 12 4 12", GROW_FILL_COL, GROW_FILL_COL));
        inputSection.setMinimumSize(new Dimension(0, COMPOSER_MIN_HEIGHT));
        inputSection.setPreferredSize(new Dimension(0, COMPOSER_PREFERRED_HEIGHT));

        JPanel composerCard = new JPanel(new BorderLayout());
        updateComposerCardBorder(composerCard, false);

        inputArea = new JTextArea(8, 0);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setOpaque(false);
        inputArea.setMargin(new Insets(8, 12, 4, 12));
        inputArea.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
        inputArea.putClientProperty(FlatClientProperties.STYLE, "border: null; background: null");
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

        attachImageButton = UiComponents.createActionButton("Image", Codicons.FILE_MEDIA, new Insets(4, 8, 4, 8), true,
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
        resetSessionButton = UiComponents.createActionButton(resetText, resetIcon, new Insets(4, 8, 4, 8), true,
                TOOLBAR_BUTTON_STYLE, e -> controller.resetSession());
        if (!isTaskMode) {
            resetSessionButton.setToolTipText(NEW_TOPIC_TOOLTIP);
        }

        mentionButton = UiComponents.createActionButton("Mention", Codicons.MENTION, new Insets(4, 8, 4, 8), true,
                TOOLBAR_BUTTON_STYLE, e -> triggerMentionPopup());
        mentionButton.setToolTipText("Insert @ to search and mention project files");

        updateTurnLabel();

        String toolbarCols;
        if (isTaskMode) {
            toolbarCols = "[]4[]4[]8[]push[]push[right]";
        } else {
            toolbarCols = "[]4[]4[]push[]push[right]";
        }
        JPanel actionToolbar = new JPanel(new MigLayout("insets 4 8 6 8, fillx", toolbarCols, "center"));
        actionToolbar.setOpaque(false);
        actionToolbar.add(attachImageButton);
        actionToolbar.add(resetSessionButton);
        actionToolbar.add(mentionButton);
        if (isTaskMode) {
            autoApprovePlanCheckBox = new JCheckBox("Auto-approve Plan");
            autoApprovePlanCheckBox.setOpaque(false);
            autoApprovePlanCheckBox.setToolTipText(
                    "Automatically approve PLAN → CODE transitions so Roxy starts coding immediately after submitting the plan");
            autoApprovePlanCheckBox.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            actionToolbar.add(autoApprovePlanCheckBox);
        }
        telemetryBar.addToToolbar(actionToolbar);
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

    private JPanel createActivityStripPanel() {
        JPanel panel = new JPanel(new MigLayout("insets 6 14 6 14, fillx", "[]8[grow, fill]12[]", "center"));
        panel.setVisible(false);

        activityIcon = FontIcon.of(HOURGLASS_FRAMES[0], 13, SwingHtmlUtils.getAccentColor());
        activityStatusLabel = new JLabel("");
        activityStatusLabel.setIcon(activityIcon);
        activityStatusLabel.setIconTextGap(8);
        activityStatusLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");

        activityElapsedLabel = new JLabel("0:00");
        activityElapsedLabel.putClientProperty(FlatClientProperties.STYLE,
                "font: -1; foreground: $Label.disabledForeground");

        panel.add(activityStatusLabel, "growx, wmin 0");
        panel.add(activityElapsedLabel, " right");

        activityAnimationTimer = new Timer(ACTIVITY_ANIMATION_DELAY_MS, e -> advanceActivityIconFrame());
        activityElapsedTimer = new Timer(ACTIVITY_ELAPSED_DELAY_MS, e -> updateElapsedTimeLabel());
        applyActivityStripStyling(panel);
        return panel;
    }

    private void applyActivityStripStyling(JPanel panel) {
        Color accent = SwingHtmlUtils.getAccentColor();
        panel.setOpaque(true);
        panel.setBackground(SwingHtmlUtils.getTintedBackground(accent, 0.08));
        panel.setBorder(BorderFactory.createCompoundBorder(SwingHtmlUtils.createThemeMatteBorder(1, 0, 0, 0),
                BorderFactory.createEmptyBorder(0, 0, 0, 0)));
        if (activityIcon != null) {
            activityIcon.setIconColor(accent);
        }
    }

    void advanceActivityIconFrame() {
        if (!loading || activityIcon == null) {
            stopActivityTimers();
            return;
        }
        activityIconFrameIndex = (activityIconFrameIndex + 1) % HOURGLASS_FRAMES.length;
        activityIcon.setIkon(HOURGLASS_FRAMES[activityIconFrameIndex]);
        activityStripPanel.repaint();
    }

    void updateElapsedTimeLabel() {
        if (!loading) {
            return;
        }
        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - turnStartMillis) / 1000L);
        activityElapsedLabel.setText(formatElapsedSeconds(elapsedSeconds));
    }

    static String formatElapsedSeconds(long elapsedSeconds) {
        long safeSeconds = Math.max(0L, elapsedSeconds);
        long minutes = safeSeconds / 60L;
        long seconds = safeSeconds % 60L;
        return String.format("%d:%02d", minutes, seconds);
    }

    private void startActivityTimers() {
        turnStartMillis = System.currentTimeMillis();
        activityIconFrameIndex = 0;
        if (activityIcon != null) {
            activityIcon.setIkon(HOURGLASS_FRAMES[0]);
        }
        activityElapsedLabel.setText("0:00");
        activityStripPanel.setVisible(true);
        if (activityAnimationTimer != null && !activityAnimationTimer.isRunning()) {
            activityAnimationTimer.start();
        }
        if (activityElapsedTimer != null && !activityElapsedTimer.isRunning()) {
            activityElapsedTimer.start();
        }
    }

    private void stopActivityTimers() {
        if (activityAnimationTimer != null && activityAnimationTimer.isRunning()) {
            activityAnimationTimer.stop();
        }
        if (activityElapsedTimer != null && activityElapsedTimer.isRunning()) {
            activityElapsedTimer.stop();
        }
    }

    void updateActivityStatus(String statusText) {
        String text = Objects.requireNonNullElse(statusText, "").trim();
        activityStatusLabel.setText(text);
        if (text.isEmpty()) {
            activityStatusLabel.setToolTipText(null);
        } else {
            activityStatusLabel.setToolTipText(text);
        }
        if (loading && !text.isEmpty() && !activityStripPanel.isVisible()) {
            activityStripPanel.setVisible(true);
        }
        activityStripPanel.revalidate();
        activityStripPanel.repaint();
    }

    String getActivityStatusText() {
        return activityStatusLabel.getText();
    }

    @Override
    public void removeNotify() {
        stopActivityTimers();
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
        UiComponents.refreshActionButtonIcons(this);
        userInputPromptPanel.applyThemeStyling();
        if (activityStripPanel != null) {
            applyActivityStripStyling(activityStripPanel);
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
            placeholder = "Roxy is working (" + controller.formatCurrentTurnStatus()
                    + ")... Press Esc or click Stop to cancel";
        } else {
            placeholder = defaultPlaceholderText;
        }
        inputArea.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
    }

    public void setOnViewPlanAction(Runnable action) {
        this.onViewPlanAction = action;
        if (viewPlanButton != null) {
            viewPlanButton.setVisible(action != null);
        }
    }

    public void setOnMoveToFeatureListener(Consumer<String> listener) {
        if (outputArea != null) {
            outputArea.setOnMoveToFeatureListener(listener);
        }
    }

    public void setInputText(String text) {
        if (inputArea != null) {
            inputArea.setText(text);
            inputArea.requestFocusInWindow();
        }
    }

    public boolean submitMovedFeatureRequest(String content) {
        if (StringUtils.isBlank(content)) {
            return false;
        }
        boolean canAutoSubmit = isProjectReady() && !isLoading() && !isWaitingUserInput()
                && !controller.hasPendingPhase() && !inlineActionPanel.isVisible();
        if (canAutoSubmit) {
            fileMentionPopup.hidePopup();
            clearInputText();
            controller.sendMessage(content);
            return true;
        }
        setInputText(content);
        return false;
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

    void appendMessage(String role, String content, List<String> thumbnailBase64List) {
        outputArea.appendMessage(role, content, thumbnailBase64List);
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
        attachmentPanel.removeAll();
        attachmentPanel.setVisible(false);
    }

    private void copyPendingCommitMessage() {
        if (planService == null || planService.getCurrentPlan() == null) {
            return;
        }
        String commitMsg = PlanService.formatCommitMessage(planService.getCurrentPlan());
        if (StringUtils.isBlank(commitMsg)) {
            return;
        }
        this.lastCopiedCommitMessage = commitMsg;
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

    void showTransitionBanner(String path) {
        transitionLabel.setText(path);
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

    void showNewTopicPrompt(String defaultTopicName, Consumer<NewTopicChoice> onChoice) {
        userInputPromptPanel.showNewTopicPrompt(defaultTopicName, onChoice);
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
        SystemFileChooser.FileNameExtensionFilter imageFilter = new SystemFileChooser.FileNameExtensionFilter(
                "Image Files (png, jpg, jpeg, gif, bmp)", "png", "jpg", "jpeg", "gif", "bmp");
        File selectedFile = UiComponents.chooseOpenFile(this, "Attach Image", imageFilter);
        if (selectedFile != null) {
            loadImageAttachmentAsync(selectedFile);
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

    void addPendingImage(Image img) {
        BufferedImage bimage = model.addPendingImage(img);
        attachmentPanel.add(createAttachmentChip(bimage));
        attachmentPanel.setVisible(true);
        attachmentPanel.revalidate();
        attachmentPanel.repaint();
    }

    private JPanel createAttachmentChip(BufferedImage bimage) {
        JLabel thumbLabel = new JLabel(new ImageIcon(UiComponents.createThumbnail(bimage, THUMBNAIL_SIZE)));
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
            startActivityTimers();
        } else {
            stopActivityTimers();
            activityStripPanel.setVisible(false);
            activityStatusLabel.setText("");
            activityStatusLabel.setToolTipText(null);
            activityElapsedLabel.setText("0:00");
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
