package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.MemoryService.ArchiveEntrySummary;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.FrontmatterUtils;
import org.roxycode.app.util.PathUtils;
import org.roxycode.app.util.TokenUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ArchiveBrowserPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(ArchiveBrowserPanel.class);

    static final int SEARCH_DEBOUNCE_MS = 200;
    static final int COPY_FEEDBACK_DELAY_MS = 1500;
    static final int MAX_SNIPPET_LENGTH = 80;
    static final int TIMESTAMP_PREFIX_LEN = 16;
    private static final int SINGLE_ITEM_COUNT = 1;
    static final String COPY_BUTTON_TEXT = "Copy as Markdown";
    private static final String MIG_GROW_X = "growx";

    public enum ArchiveMode {
        TOPICS("Search past topics...", "topic", "topics", Codicons.COMMENT_DISCUSSION, "Archived On",
                "No archived Q&A topics yet.",
                "When you start a New Topic in Ask Roxy, the conversation is archived here automatically."),
        PLANS("Search past plans...", "plan", "plans", Codicons.PROJECT, "Completed On",
                "No archived implementation plans yet.",
                "When a Feature Request is approved as complete in Review, its plan is archived here automatically.");

        private final String placeholder;
        private final String singularNoun;
        private final String pluralNoun;
        private final Codicons icon;
        private final String dateLabel;
        private final String emptyTitle;
        private final String emptyHint;

        ArchiveMode(String placeholder, String singularNoun, String pluralNoun, Codicons icon, String dateLabel,
                String emptyTitle, String emptyHint) {
            this.placeholder = placeholder;
            this.singularNoun = singularNoun;
            this.pluralNoun = pluralNoun;
            this.icon = icon;
            this.dateLabel = dateLabel;
            this.emptyTitle = emptyTitle;
            this.emptyHint = emptyHint;
        }

        public String getPlaceholder() {
            return placeholder;
        }

        public Codicons getIcon() {
            return icon;
        }
    }

    private final transient MemoryService memoryService;
    final ArchiveMode mode;

    final JTextField searchField;
    final JLabel countLabel;
    final JButton refreshButton;
    final DefaultListModel<ArchiveEntrySummary> listModel = new DefaultListModel<>();
    final JList<ArchiveEntrySummary> entryList = new JList<>(listModel);
    final JLabel detailSummaryLabel = new JLabel("");
    final JButton renameButton;
    final JButton deleteButton;
    final JButton copyMarkdownButton;
    final JPanel renamePanel;
    final JTextField renameField;
    final JButton saveRenameButton;
    final JButton cancelRenameButton;
    final UiComponents.InlineConfirmBar deleteConfirmBar;
    final JEditorPane displayArea;
    private final Timer searchDebounceTimer;

    private Timer copyFeedbackTimer;
    private String currentRawMarkdown = "";
    String lastCopiedMarkdown;
    private transient ArchiveEntrySummary currentSelectedEntry;
    private transient IntConsumer onTotalCountChanged;
    transient CompletableFuture<String> lastSelectionFuture = CompletableFuture.completedFuture("");

    public ArchiveBrowserPanel(MemoryService memoryService, ArchiveMode mode) {
        super(new BorderLayout());
        this.memoryService = memoryService;
        this.mode = Objects.requireNonNull(mode, "mode must not be null");

        JPanel topBar = new JPanel(new MigLayout("insets 8 12 8 12, fillx", "[grow, fill]12[]8[]", "center"));
        topBar.setBorder(SwingHtmlUtils.createThemeMatteBorder(0, 0, 1, 0));

        searchField = new JTextField();
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, mode.getPlaceholder());
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, FontIcon.of(Codicons.SEARCH, 14));
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);

        countLabel = new JLabel(formatCountText(0));
        countLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");

        refreshButton = new JButton("Refresh", FontIcon.of(Codicons.REFRESH, 14));
        refreshButton.setToolTipText("Refresh archive list");
        refreshButton.addActionListener(e -> refresh());

        topBar.add(searchField, MIG_GROW_X);
        topBar.add(countLabel);
        topBar.add(refreshButton);
        add(topBar, BorderLayout.NORTH);

        entryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        entryList.setCellRenderer(new ArchiveEntryCellRenderer());
        entryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onEntrySelected(entryList.getSelectedValue());
            }
        });

        JScrollPane listScrollPane = new JScrollPane(entryList);
        listScrollPane.setBorder(null);

        JPanel detailPanel = new JPanel(new BorderLayout());
        JPanel detailToolbar = new JPanel(new BorderLayout(8, 0));
        detailToolbar.setBorder(BorderFactory.createCompoundBorder(SwingHtmlUtils.createThemeMatteBorder(0, 0, 1, 0),
                BorderFactory.createEmptyBorder(4, 12, 4, 8)));

        detailSummaryLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold -1");
        detailToolbar.add(detailSummaryLabel, BorderLayout.WEST);

        JPanel actionRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actionRight.setOpaque(false);
        renameButton = UiComponents.createActionButton("Rename", Codicons.EDIT, null, false, null, e -> startRename());
        deleteButton = UiComponents.createActionButton("Delete", Codicons.TRASH, null, false,
                UiComponents.SUBTLE_RED_BUTTON_STYLE, e -> promptDeleteEntry());
        renameButton.setEnabled(false);
        deleteButton.setEnabled(false);
        actionRight.add(renameButton);
        actionRight.add(deleteButton);
        copyMarkdownButton = UiComponents.createCopyButton(COPY_BUTTON_TEXT, false, this::copyCurrentMarkdown);
        actionRight.add(copyMarkdownButton);
        detailToolbar.add(actionRight, BorderLayout.EAST);

        renamePanel = new JPanel(new MigLayout("insets 6 12 6 12, fillx, gap 8", "[][grow, fill][][]", "center"));
        renamePanel.setBorder(SwingHtmlUtils.createThemeMatteBorder(0, 0, 1, 0));
        renamePanel.setVisible(false);
        renameField = new JTextField(24);
        renameField.addActionListener(e -> saveRename());
        renameField.registerKeyboardAction(e -> cancelRename(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                WHEN_FOCUSED);
        saveRenameButton = UiComponents.createActionButton("Save", Codicons.CHECK, null, false,
                UiComponents.ACCENT_BUTTON_STYLE, e -> saveRename());
        cancelRenameButton = UiComponents.createActionButton("Cancel", Codicons.CLOSE, null, false, null,
                e -> cancelRename());
        renamePanel.add(new JLabel("Title:"));
        renamePanel.add(renameField, MIG_GROW_X);
        renamePanel.add(saveRenameButton);
        renamePanel.add(cancelRenameButton);

        deleteConfirmBar = new UiComponents.InlineConfirmBar();

        JPanel detailHeaderPanel = new JPanel(new MigLayout("insets 0, fillx, wrap 1, gapy 0", "[grow, fill]"));
        detailHeaderPanel.setOpaque(false);
        detailHeaderPanel.add(detailToolbar);
        detailHeaderPanel.add(renamePanel);
        detailHeaderPanel.add(deleteConfirmBar);

        displayArea = SwingHtmlUtils.createHtmlEditorPane();
        JScrollPane detailScrollPane = UiComponents.createHtmlScrollPane(displayArea);

        detailPanel.add(detailHeaderPanel, BorderLayout.NORTH);
        detailPanel.add(detailScrollPane, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScrollPane, detailPanel);
        splitPane.setDividerLocation(320);
        splitPane.setResizeWeight(0.28);
        splitPane.setBorder(null);
        add(splitPane, BorderLayout.CENTER);

        searchDebounceTimer = new Timer(SEARCH_DEBOUNCE_MS, e -> executeSearchAsync());
        searchDebounceTimer.setRepeats(false);
        SwingHtmlUtils.addChangeListener(searchField, this::scheduleDebouncedSearch);

        updateTheme();
        renderEmptyState(false);
    }

    private void scheduleDebouncedSearch() {
        searchDebounceTimer.restart();
    }

    void setOnTotalCountChanged(IntConsumer listener) {
        this.onTotalCountChanged = listener;
    }

    public CompletableFuture<?> refresh() {
        if (searchDebounceTimer != null && searchDebounceTimer.isRunning()) {
            searchDebounceTimer.stop();
        }
        return executeSearchAsync();
    }

    CompletableFuture<?> executeSearchAsync() {
        String searchQuery = "";
        if (searchField != null) {
            searchQuery = searchField.getText();
        }
        final String q = searchQuery;
        return Edt.supplyAsync(() -> loadEntriesForQuery(q), results -> applySearchResults(results, q), e -> {
            log.warn("Failed to load archive entries for {}: {}", mode, e.getMessage());
            applySearchResults(List.of(), q);
        }).thenCompose(results -> lastSelectionFuture);
    }

    private List<ArchiveEntrySummary> loadEntriesForQuery(String query) {
        if (memoryService == null) {
            return List.of();
        }
        String trimmed = StringUtils.trimToEmpty(query);
        if (mode == ArchiveMode.TOPICS) {
            if (trimmed.isEmpty()) {
                return memoryService.listArchivedTopicSummaries();
            }
            return memoryService.searchArchivedTopics(trimmed);
        }
        if (trimmed.isEmpty()) {
            return memoryService.listArchivedPlanSummaries();
        }
        return memoryService.searchArchivedPlans(trimmed);
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void applySearchResults(List<ArchiveEntrySummary> results, String query) {
        List<ArchiveEntrySummary> safeResults = Objects.requireNonNullElse(results, List.of());
        String previousFilename = null;
        if (currentSelectedEntry != null) {
            previousFilename = currentSelectedEntry.filename();
        }

        countLabel.setText(formatCountText(safeResults.size()));
        if ((query == null || query.isBlank()) && onTotalCountChanged != null) {
            onTotalCountChanged.accept(safeResults.size());
        }
        lastSelectionFuture = CompletableFuture.completedFuture("");
        int selectedIndex = UiComponents.replaceListItemsPreservingSelection(listModel, entryList, safeResults,
                ArchiveEntrySummary::filename, previousFilename, true);
        if (selectedIndex < 0) {
            currentSelectedEntry = null;
            currentRawMarkdown = "";
            detailSummaryLabel.setText("");
            renameButton.setEnabled(false);
            deleteButton.setEnabled(false);
            copyMarkdownButton.setEnabled(false);
            cancelRename();
            deleteConfirmBar.cancel();
            renderEmptyState(query != null && !query.isBlank());
            lastSelectionFuture = CompletableFuture.completedFuture("");
        }
    }

    CompletableFuture<String> onEntrySelected(ArchiveEntrySummary entry) {
        currentSelectedEntry = entry;
        cancelRename();
        deleteConfirmBar.cancel();
        if (entry == null) {
            currentRawMarkdown = "";
            detailSummaryLabel.setText("");
            renameButton.setEnabled(false);
            deleteButton.setEnabled(false);
            copyMarkdownButton.setEnabled(false);
            renderEmptyState(searchField != null && !searchField.getText().isBlank());
            CompletableFuture<String> done = CompletableFuture.completedFuture("");
            lastSelectionFuture = done;
            return done;
        }

        renameButton.setEnabled(true);
        deleteButton.setEnabled(true);
        detailSummaryLabel.setText(entry.title());
        CompletableFuture<String> future = Edt.supplyAsync(() -> readEntryMarkdown(entry.filename()),
                normalized -> applyDetailContent(entry, normalized), e -> {
                    log.warn("Failed to read archived markdown {}: {}", entry.filename(), e.getMessage());
                    applyDetailContent(entry, "");
                });
        lastSelectionFuture = future;
        return future;
    }

    private String readEntryMarkdown(String filename) {
        if (memoryService == null || filename == null || filename.isBlank()) {
            return "";
        }
        if (mode == ArchiveMode.TOPICS) {
            return memoryService.readArchivedTopic(filename);
        }
        return memoryService.readArchivedPlan(filename);
    }

    private void applyDetailContent(ArchiveEntrySummary entry, String rawMarkdown) {
        this.currentRawMarkdown = StringUtils.defaultString(rawMarkdown);
        copyMarkdownButton.setEnabled(!this.currentRawMarkdown.isBlank());
        displayArea.setText(buildDetailHtml(entry, this.currentRawMarkdown, mode));
        displayArea.setCaretPosition(0);
    }

    static String buildDetailHtml(ArchiveEntrySummary entry, String rawMarkdown, ArchiveMode archiveMode) {
        if (entry == null) {
            return "<html><body></body></html>";
        }
        String renderedBody = SwingHtmlUtils.renderMarkdownToHtml(FrontmatterUtils.stripFrontmatter(rawMarkdown));
        String formattedDate = PathUtils.formatTimestampDisplay(entry.timestamp(), TIMESTAMP_PREFIX_LEN, "");
        String author = StringUtils.defaultIfBlank(entry.createdBy(), "User");

        String dateDisplay = StringUtils.defaultIfEmpty(formattedDate, entry.filename());
        String messageCountSuffix = "";
        if (archiveMode == ArchiveMode.TOPICS && entry.messageCount() > 0) {
            messageCountSuffix = " &nbsp;&bull;&nbsp; <b>Messages:</b> " + entry.messageCount();
        }

        StringBuilder metaRows = new StringBuilder();
        metaRows.append("""
                <tr>
                    <td valign='top' nowrap='nowrap' class='plan-muted'><b>%s:</b></td>
                    <td width='100%%'>%s &nbsp;&bull;&nbsp; <b>By:</b> %s%s</td>
                </tr>
                """.formatted(SwingHtmlUtils.escapeHtml(archiveMode.dateLabel), SwingHtmlUtils.escapeHtml(dateDisplay),
                SwingHtmlUtils.escapeHtml(author), messageCountSuffix));

        if (archiveMode == ArchiveMode.PLANS && (entry.totalTokens() > 0L || entry.estimatedCostUsd() > 0.0)) {
            String tokenMeta = SwingHtmlUtils.escapeHtml(TokenUtils.formatTokenCount(entry.totalTokens())) + " tok ("
                    + entry.cacheHitPercent() + "% cached) &nbsp;&bull;&nbsp; <b>Cost:</b> "
                    + SwingHtmlUtils.escapeHtml(TokenUtils.formatCostUsd(entry.estimatedCostUsd()));
            metaRows.append("""
                    <tr>
                        <td valign='top' nowrap='nowrap' class='plan-muted'><b>Tokens:</b></td>
                        <td width='100%%'>%s</td>
                    </tr>
                    """.formatted(tokenMeta));
        }

        if (entry.matchSnippet() != null && !entry.matchSnippet().isBlank()) {
            metaRows.append("""
                    <tr>
                        <td valign='top' nowrap='nowrap' class='plan-muted'><b>Match:</b></td>
                        <td width='100%%'><i>%s</i></td>
                    </tr>
                    """.formatted(SwingHtmlUtils.escapeHtml(entry.matchSnippet())));
        }

        return """
                <html><body>
                <table width='100%%' border='0' cellpadding='6' cellspacing='0' class='welcome-card'>
                    <tr>
                        <td colspan='2'><b>%s</b> <span class='plan-muted'>(%s)</span></td>
                    </tr>
                    %s
                </table>
                <div class='content'>%s</div>
                </body></html>
                """.formatted(SwingHtmlUtils.escapeHtml(entry.title()), SwingHtmlUtils.escapeHtml(entry.filename()),
                metaRows, renderedBody);
    }

    private void renderEmptyState(boolean isSearchEmpty) {
        String title;
        String hint;
        if (isSearchEmpty) {
            title = "No matching " + mode.pluralNoun + " found.";
            hint = "Try a different search term or clear the search box.";
        } else {
            title = mode.emptyTitle;
            hint = mode.emptyHint;
        }
        displayArea.setText("<html><body><h3>%s</h3><p class='plan-muted'>%s</p></body></html>"
                .formatted(SwingHtmlUtils.escapeHtml(title), SwingHtmlUtils.escapeHtml(hint)));
        displayArea.setCaretPosition(0);
    }

    private void copyCurrentMarkdown() {
        if (currentRawMarkdown == null || currentRawMarkdown.isBlank()) {
            return;
        }
        this.lastCopiedMarkdown = currentRawMarkdown;
        copyFeedbackTimer = UiComponents.copyWithFeedback(copyMarkdownButton, copyFeedbackTimer, currentRawMarkdown,
                COPY_BUTTON_TEXT, COPY_FEEDBACK_DELAY_MS);
    }

    void startRename() {
        if (currentSelectedEntry == null) {
            return;
        }
        deleteConfirmBar.cancel();
        renameField.setText(StringUtils.defaultString(currentSelectedEntry.title()));
        renamePanel.setVisible(true);
        renameField.selectAll();
        renameField.requestFocusInWindow();
        revalidate();
        repaint();
    }

    void cancelRename() {
        if (renamePanel != null && renamePanel.isVisible()) {
            renamePanel.setVisible(false);
            revalidate();
            repaint();
        }
    }

    CompletableFuture<EditorResult> saveRename() {
        if (currentSelectedEntry == null || memoryService == null) {
            return CompletableFuture
                    .completedFuture(new EditorResult(false, "", "No " + mode.singularNoun + " selected"));
        }
        String filename = currentSelectedEntry.filename();
        String newTitle = renameField.getText();
        saveRenameButton.setEnabled(false);
        return Edt.supplyAsync(() -> {
            if (mode == ArchiveMode.PLANS) {
                return memoryService.renameArchivedPlan(filename, newTitle);
            }
            return memoryService.renameArchivedTopic(filename, newTitle);
        }, result -> {
            saveRenameButton.setEnabled(true);
            if (result != null && result.success()) {
                cancelRename();
            }
        }, ex -> {
            saveRenameButton.setEnabled(true);
            log.warn("Failed to rename archived {} {}: {}", mode.singularNoun, filename, ex.getMessage());
        }).thenCompose(result -> {
            if (result != null && result.success()) {
                return refresh().thenApply(v -> result);
            }
            return CompletableFuture.completedFuture(result);
        });
    }

    void promptDeleteEntry() {
        if (currentSelectedEntry == null) {
            return;
        }
        cancelRename();
        String filename = currentSelectedEntry.filename();
        String title = StringUtils.defaultIfBlank(currentSelectedEntry.title(), filename);
        deleteConfirmBar.prompt("Delete " + mode.singularNoun + " '" + title + "'?", "Confirm Delete", true,
                () -> performDeleteEntry(filename));
    }

    CompletableFuture<EditorResult> performDeleteEntry(String filename) {
        if (memoryService == null || StringUtils.isBlank(filename)) {
            return CompletableFuture.completedFuture(new EditorResult(false, "", "Invalid " + mode.singularNoun));
        }
        return Edt.supplyAsync(() -> {
            if (mode == ArchiveMode.PLANS) {
                return memoryService.deleteArchivedPlan(filename);
            }
            return memoryService.deleteArchivedTopic(filename);
        }, result -> {
            // Post-delete refresh is chained via thenCompose below.
        }, ex -> log.warn("Failed to delete archived {} {}: {}", mode.singularNoun, filename, ex.getMessage()))
                .thenCompose(result -> {
                    if (result != null && result.success()) {
                        return refresh().thenApply(v -> result);
                    }
                    return CompletableFuture.completedFuture(result);
                });
    }

    private String formatCountText(int count) {
        if (count == SINGLE_ITEM_COUNT) {
            return count + " " + mode.singularNoun;
        }
        return count + " " + mode.pluralNoun;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (displayArea != null) {
            updateTheme();
            if (currentSelectedEntry != null) {
                displayArea.setText(buildDetailHtml(currentSelectedEntry, currentRawMarkdown, mode));
                displayArea.setCaretPosition(0);
            } else {
                renderEmptyState(searchField != null && !searchField.getText().isBlank());
            }
        }
    }

    private void updateTheme() {
        SwingHtmlUtils.applyTheme(displayArea, 16);
        UiComponents.refreshActionButtonIcons(this);
        if (countLabel != null) {
            countLabel.setForeground(SwingHtmlUtils.getMutedForegroundColor());
        }
    }

    private final class ArchiveEntryCellRenderer extends JPanel implements ListCellRenderer<ArchiveEntrySummary> {
        private static final long serialVersionUID = 1L;

        private final JLabel titleLabel = new JLabel();
        private final JLabel subtitleLabel = new JLabel();
        private final JLabel snippetLabel = new JLabel();

        ArchiveEntryCellRenderer() {
            super(new MigLayout("insets 6 10 6 10, fillx, wrap 1, gapy 2", "[grow, fill]"));
            setOpaque(true);
            titleLabel.setIconTextGap(6);
            titleLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold 0");
            subtitleLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            snippetLabel.putClientProperty(FlatClientProperties.STYLE, "font: italic -1");

            add(titleLabel, "growx");
            add(subtitleLabel, "growx");
            add(snippetLabel, "growx, hidemode 3");
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ArchiveEntrySummary> list,
                ArchiveEntrySummary value, int index, boolean isSelected, boolean cellHasFocus) {
            UiComponents.ListCellColors colors = UiComponents.resolveListCellColors(list, isSelected);
            Color muted = colors.muted();
            setBackground(colors.background());
            setBorder(SwingHtmlUtils.createThemeMatteBorder(0, 0, 1, 0));

            if (value != null) {
                titleLabel.setText(value.title());
                titleLabel.setForeground(colors.foreground());
                titleLabel.setIcon(FontIcon.of(mode.getIcon(), 14, colors.iconColor()));

                String dateStr = PathUtils.formatTimestampDisplay(value.timestamp(), TIMESTAMP_PREFIX_LEN, "");
                String author = StringUtils.defaultIfBlank(value.createdBy(), "User");
                StringBuilder sub = new StringBuilder();
                if (!dateStr.isEmpty()) {
                    sub.append(dateStr).append("  •  ");
                }
                sub.append(author);
                if (mode == ArchiveMode.TOPICS && value.messageCount() > 0) {
                    sub.append("  •  ").append(value.messageCount()).append(" msgs");
                } else if (mode == ArchiveMode.PLANS && (value.totalTokens() > 0L || value.estimatedCostUsd() > 0.0)) {
                    sub.append("  •  ").append(TokenUtils.formatTokenCount(value.totalTokens())).append(" tok (")
                            .append(TokenUtils.formatCostUsd(value.estimatedCostUsd())).append(")");
                }
                subtitleLabel.setText(sub.toString());
                subtitleLabel.setForeground(muted);

                String snippet = value.matchSnippet();
                boolean hasSnippet = snippet != null && !snippet.isBlank();
                if (hasSnippet) {
                    snippetLabel.setText(StringUtils.abbreviate(snippet.trim(), MAX_SNIPPET_LENGTH));
                } else {
                    snippetLabel.setText("");
                }
                snippetLabel.setForeground(muted);
                snippetLabel.setVisible(hasSnippet);
                setToolTipText(value.title() + " (" + value.filename() + ")");
            }
            return this;
        }
    }
}
