package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.FlowLayout;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTabbedPane;
import javax.swing.Timer;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService.RepoMapSnapshot;
import org.roxycode.app.events.ContextChangedEvent;
import org.roxycode.app.events.MemoryChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.TokenUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
@Lazy
final class CodebaseCachePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(CodebaseCachePanel.class);

    static final int MAX_DISPLAY_CHARS = 200_000;
    static final int COPY_FEEDBACK_DELAY_MS = 1500;
    static final String PAGE_TITLE = "Repo Map & Prompt";
    static final String PAGE_SUBTITLE = "Inspect the repository symbol map, stable system prompt, and session context sent to the model.";
    static final String REFRESH_BUTTON_TEXT = "Refresh Repo Map";
    static final String COPY_BUTTON_TEXT = "Copy";
    static final String NO_SNAPSHOT_STATUS = "Status: No repo map snapshot generated yet.";
    static final String TAB_REPO_MAP = "Repo Map";
    static final String TAB_STABLE_PROMPT = "Stable Prompt & Memory";
    static final String TAB_SESSION_CONTEXT = "Session Context";
    private static final int TAB_INDEX_STABLE_PROMPT = 1;
    private static final int TAB_INDEX_SESSION_CONTEXT = 2;
    private static final String GROWX = "growx";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final transient RepoMapPackerService packerService;
    private final transient PromptService promptService;
    private final transient SettingsService settingsService;

    final JLabel titleLabel = new JLabel(PAGE_TITLE);
    final JLabel subtitleLabel = new JLabel(PAGE_SUBTITLE);
    final JPanel summaryCardPanel = new JPanel(
            new MigLayout("fillx, insets 12 16 12 16, wrap 1, gapy 6", "[grow, fill]", ""));
    final JLabel statusLabel = new JLabel("Status: Unknown");
    final JLabel repoMapTokensLabel = new JLabel();
    final JLabel stableTokensLabel = new JLabel();
    final JLabel sessionTokensLabel = new JLabel();
    final JLabel totalTokensLabel = new JLabel();
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    final JButton packButton = new JButton(REFRESH_BUTTON_TEXT, FontIcon.of(Codicons.REFRESH, 14));
    final JButton copyButton;

    final JTabbedPane tabbedPane;
    final RSyntaxTextArea repoMapArea = new RSyntaxTextArea();
    final RSyntaxTextArea stableContextArea = new RSyntaxTextArea();
    final RSyntaxTextArea sessionContextArea = new RSyntaxTextArea();

    private Timer copyFeedbackTimer;
    private transient CacheStatusData latestStatusData;
    private boolean pendingEditorUpdate;
    String lastCopiedContent;

    record CacheStatusData(Optional<RepoMapSnapshot> snapshot, long repoMapTokens, long stableTokens,
            long workflowDocsTokens, long contextFilesTokens, long agentMemoryTokens, long sessionTokens,
            long totalTokens, String repoMapText, String stableContextText, String sessionContextText) {
    }

    @Autowired
    public CodebaseCachePanel(RepoMapPackerService packerService, PromptService promptService,
            @Nullable SettingsService settingsService) {
        this.packerService = packerService;
        this.promptService = promptService;
        this.settingsService = settingsService;
        this.copyButton = UiComponents.createCopyButton(COPY_BUTTON_TEXT, true, this::copyCurrentTabContent);
        this.tabbedPane = UiComponents.createUnderlinedTabbedPane(null);
        initComponents();
        runPack();
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        Edt.run(this::runPack);
    }

    @EventListener
    public void onContextChanged(ContextChangedEvent event) {
        Edt.run(this::refreshStatus);
    }

    @EventListener
    public void onMemoryChanged(MemoryChangedEvent event) {
        Edt.run(this::refreshStatus);
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        Edt.run(this::updateTheme);
    }

    @Override
    public void setVisible(boolean aFlag) {
        super.setVisible(aFlag);
        if (aFlag) {
            flushPendingEditorTexts();
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (summaryCardPanel != null && repoMapArea != null) {
            updateTheme();
        }
    }

    static String truncateForDisplay(String text) {
        String safe = StringUtils.defaultString(text);
        if (safe.length() <= MAX_DISPLAY_CHARS) {
            return safe;
        }
        return safe.substring(0, MAX_DISPLAY_CHARS)
                + """


                        ... [Display truncated at 200,000 characters for UI responsiveness; full content (%,d chars) is cached]"""
                        .formatted(safe.length());
    }

    private void initComponents() {
        setLayout(new MigLayout("fill, insets 20, hidemode 3", "[grow, fill]", "[]12[]12[grow, fill]"));

        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actionPanel.setOpaque(false);
        actionPanel.add(copyButton);
        actionPanel.add(packButton);
        add(UiComponents.createPageHeader(titleLabel, subtitleLabel, actionPanel), "growx, wmin 0, wrap");

        UiComponents.styleCardPanel(summaryCardPanel);
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold");
        totalTokensLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold");
        repoMapTokensLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        stableTokensLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        sessionTokensLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");

        JPanel topSummaryRow = new JPanel(new MigLayout("insets 0, fillx", "[grow, fill][]", "center"));
        topSummaryRow.setOpaque(false);
        topSummaryRow.add(statusLabel, GROWX);
        topSummaryRow.add(totalTokensLabel);
        summaryCardPanel.add(topSummaryRow, GROWX);

        JPanel breakdownRow = new JPanel(new MigLayout("insets 0, fillx, gapx 18", "[][][grow]", "center"));
        breakdownRow.setOpaque(false);
        breakdownRow.add(repoMapTokensLabel);
        breakdownRow.add(sessionTokensLabel);
        breakdownRow.add(stableTokensLabel, GROWX);
        summaryCardPanel.add(breakdownRow, GROWX);

        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        summaryCardPanel.add(progressBar, "growx, hidemode 3");
        add(summaryCardPanel, "growx, wrap");

        configureViewer(repoMapArea, SyntaxConstants.SYNTAX_STYLE_NONE);
        configureViewer(stableContextArea, SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
        configureViewer(sessionContextArea, SyntaxConstants.SYNTAX_STYLE_MARKDOWN);

        tabbedPane.addTab(TAB_REPO_MAP, FontIcon.of(Codicons.LIST_TREE, 14), new RTextScrollPane(repoMapArea));
        tabbedPane.addTab(TAB_STABLE_PROMPT, FontIcon.of(Codicons.BOOK, 14), new RTextScrollPane(stableContextArea));
        tabbedPane.addTab(TAB_SESSION_CONTEXT, FontIcon.of(Codicons.PULSE, 14),
                new RTextScrollPane(sessionContextArea));
        add(tabbedPane, "grow, push");

        packButton.addActionListener(e -> runPack());
        updateTheme();
    }

    private void configureViewer(RSyntaxTextArea area, String syntaxStyle) {
        UiComponents.configureSyntaxTextArea(area, syntaxStyle, false, true);
    }

    void updateTheme() {
        UiComponents.styleCardPanel(summaryCardPanel);
        UiComponents.refreshActionButtonIcons(this);
        repoMapTokensLabel.setForeground(SwingHtmlUtils.getMutedForegroundColor());
        stableTokensLabel.setForeground(SwingHtmlUtils.getMutedForegroundColor());
        sessionTokensLabel.setForeground(SwingHtmlUtils.getMutedForegroundColor());
        SwingHtmlUtils.applyRSyntaxTheme(repoMapArea, settingsService);
        SwingHtmlUtils.applyRSyntaxTheme(stableContextArea, settingsService);
        SwingHtmlUtils.applyRSyntaxTheme(sessionContextArea, settingsService);
    }

    void copyCurrentTabContent() {
        flushPendingEditorTexts();
        int index = tabbedPane.getSelectedIndex();
        String content;
        if (index == TAB_INDEX_STABLE_PROMPT) {
            content = stableContextArea.getText();
        } else if (index == TAB_INDEX_SESSION_CONTEXT) {
            content = sessionContextArea.getText();
        } else {
            content = repoMapArea.getText();
        }
        String safe = StringUtils.defaultString(content);
        this.lastCopiedContent = safe;
        copyFeedbackTimer = UiComponents.copyWithFeedback(copyButton, copyFeedbackTimer, safe, COPY_BUTTON_TEXT,
                COPY_FEEDBACK_DELAY_MS);
    }

    CompletableFuture<CacheStatusData> refreshStatus() {
        return Edt.supplyAsync(this::computeStatusData, this::applyStatusDataOnEdt,
                e -> log.error("Failed to refresh repo map and prompt status", e));
    }

    private CacheStatusData computeStatusData() {
        Optional<RepoMapSnapshot> snapshot = Optional.empty();
        if (packerService != null) {
            snapshot = packerService.getCachedSnapshot();
        }
        String stableContext = "";
        String sessionContext = "";
        String globalContext = "";
        String projectContext = "";
        String agentMemory = "";
        if (promptService != null) {
            stableContext = StringUtils.defaultString(promptService.getStableContext());
            sessionContext = StringUtils.trimToEmpty(promptService.getSessionContext());
            globalContext = StringUtils.defaultString(promptService.loadGlobalContext());
            projectContext = StringUtils.defaultString(promptService.loadProjectContext());
            agentMemory = StringUtils.defaultString(promptService.loadAgentMemoryContext());
        }
        long stableTokens = TokenUtils.estimateTokens(stableContext);
        long contextFilesTokens = TokenUtils.estimateTokens(globalContext + projectContext);
        long agentMemoryTokens = TokenUtils.estimateTokens(agentMemory);
        long workflowDocsTokens = Math.max(0L, stableTokens - contextFilesTokens - agentMemoryTokens);
        long sessionTokens = TokenUtils.estimateTokens(sessionContext);

        if (snapshot.isPresent()) {
            RepoMapSnapshot snap = snapshot.get();
            long repoMapTokens = snap.estimatedTokens();
            long totalTokens = repoMapTokens + stableTokens + sessionTokens;
            return new CacheStatusData(snapshot, repoMapTokens, stableTokens, workflowDocsTokens, contextFilesTokens,
                    agentMemoryTokens, sessionTokens, totalTokens, truncateForDisplay(snap.content()),
                    truncateForDisplay(stableContext), truncateForDisplay(sessionContext));
        }
        long totalTokens = stableTokens + sessionTokens;
        return new CacheStatusData(snapshot, 0L, stableTokens, workflowDocsTokens, contextFilesTokens,
                agentMemoryTokens, sessionTokens, totalTokens,
                truncateForDisplay("Repo Map will appear here after generation."), truncateForDisplay(stableContext),
                truncateForDisplay(sessionContext));
    }

    void flushPendingEditorTexts() {
        if (pendingEditorUpdate && latestStatusData != null) {
            applyEditorTexts(latestStatusData);
            pendingEditorUpdate = false;
        }
    }

    private void applyEditorTexts(CacheStatusData data) {
        updateEditorText(repoMapArea, data.repoMapText());
        updateEditorText(stableContextArea, data.stableContextText());
        updateEditorText(sessionContextArea, data.sessionContextText());
    }

    private static void updateEditorText(RSyntaxTextArea area, String text) {
        if (!Objects.equals(area.getText(), text)) {
            area.setText(text);
            area.setCaretPosition(0);
        }
    }

    private void applyStatusDataOnEdt(CacheStatusData data) {
        this.latestStatusData = data;
        if (isVisible()) {
            applyEditorTexts(data);
            this.pendingEditorUpdate = false;
        } else {
            this.pendingEditorUpdate = true;
        }

        repoMapTokensLabel.setText(String.format("Repo Map: %,d (%s)", data.repoMapTokens(),
                TokenUtils.formatTokenCount(data.repoMapTokens())));
        sessionTokensLabel.setText(String.format("Session Context: %,d (%s)", data.sessionTokens(),
                TokenUtils.formatTokenCount(data.sessionTokens())));
        stableTokensLabel.setText(String.format(
                "Stable Prompt & Context: %,d (%s)  [Workflow/JEXL: %,d • Context Files: %,d • Agent Memory: %,d]",
                data.stableTokens(), TokenUtils.formatTokenCount(data.stableTokens()), data.workflowDocsTokens(),
                data.contextFilesTokens(), data.agentMemoryTokens()));
        totalTokensLabel.setText(String.format("Total Prompt Prefix Tokens: %,d (%s)", data.totalTokens(),
                TokenUtils.formatTokenCount(data.totalTokens())));

        if (data.snapshot().isPresent()) {
            RepoMapSnapshot snap = data.snapshot().get();
            statusLabel.setText(String.format(
                    "Status: Last updated on %s | Repo Map: %,d | Stable: %,d | Session: %,d | Total: %,d",
                    snap.lastUpdated().format(DATE_FORMATTER), data.repoMapTokens(), data.stableTokens(),
                    data.sessionTokens(), data.totalTokens()));
        } else {
            statusLabel.setText(NO_SNAPSHOT_STATUS);
        }
    }

    CompletableFuture<EditorResult> runPack() {
        packButton.setEnabled(false);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);
        statusLabel.setText("Generating Repo Map...");

        return Edt.supplyAsync(packerService::forceRegenerateRepoMap, result -> {
            if (result != null && result.success()) {
                statusLabel.setText("Repo Map updated successfully.");
            } else {
                String hint = "Unknown error";
                if (result != null) {
                    hint = result.errorHint();
                }
                statusLabel.setText("Failed: " + hint);
            }
            refreshStatus();
            finishPackUi();
        }, e -> {
            log.error("Repo map generation failed", e);
            statusLabel.setText("Failed: " + e.getMessage());
            finishPackUi();
        });
    }

    private void finishPackUi() {
        packButton.setEnabled(true);
        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);
    }
}
