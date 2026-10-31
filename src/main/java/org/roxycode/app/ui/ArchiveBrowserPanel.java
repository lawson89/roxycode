package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
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
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.MemoryService.ArchiveEntrySummary;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.FrontmatterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ArchiveBrowserPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(ArchiveBrowserPanel.class);

    static final int SEARCH_DEBOUNCE_MS = 200;
    static final int COPY_FEEDBACK_DELAY_MS = 1500;
    static final int MAX_SNIPPET_LENGTH = 80;
    static final int TIMESTAMP_PREFIX_LEN = 16;
    static final String COPY_BUTTON_TEXT = "Copy as Markdown";

    public enum ArchiveMode {
        TOPICS("Search past topics...", "topic", "topics", Codicons.COMMENT_DISCUSSION, "Archived On",
                "No archived Q&A topics yet.",
                "When you start a New Topic in Codebase Q&A, the conversation is archived here automatically."),
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
    final JButton copyMarkdownButton;
    final JEditorPane displayArea;
    private final Timer searchDebounceTimer;

    private Timer copyFeedbackTimer;
    private String currentRawMarkdown = "";
    String lastCopiedMarkdown;
    private transient ArchiveEntrySummary currentSelectedEntry;
    CompletableFuture<String> lastSelectionFuture = CompletableFuture.completedFuture("");

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

        topBar.add(searchField, "growx");
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

        JPanel actionRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actionRight.setOpaque(false);
        copyMarkdownButton = UiComponents.createCopyButton(COPY_BUTTON_TEXT, false, this::copyCurrentMarkdown);
        actionRight.add(copyMarkdownButton);
        detailToolbar.add(actionRight, BorderLayout.EAST);

        displayArea = SwingHtmlUtils.createHtmlEditorPane();
        JScrollPane detailScrollPane = UiComponents.createHtmlScrollPane(displayArea);

        detailPanel.add(detailToolbar, BorderLayout.NORTH);
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

    public CompletableFuture<?> refresh() {
        if (searchDebounceTimer != null && searchDebounceTimer.isRunning()) {
            searchDebounceTimer.stop();
        }
        return executeSearchAsync();
    }

    CompletableFuture<?> executeSearchAsync() {
        String query = "";
        if (searchField != null) {
            query = searchField.getText();
        }
        final String searchQuery = query;
        return Edt.supplyAsync(() -> loadEntriesForQuery(searchQuery),
                results -> applySearchResults(results, searchQuery), e -> {
                    log.warn("Failed to load archive entries for {}: {}", mode, e.getMessage());
                    applySearchResults(List.of(), searchQuery);
                }).thenCompose(results -> lastSelectionFuture);
    }

    private List<ArchiveEntrySummary> loadEntriesForQuery(String query) {
        if (memoryService == null) {
            return List.of();
        }
        String trimmed;
        if (query != null) {
            trimmed = query.trim();
        } else {
            trimmed = "";
        }
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
        List<ArchiveEntrySummary> safeResults;
        if (results != null) {
            safeResults = results;
        } else {
            safeResults = List.of();
        }
        String previousFilename = null;
        if (currentSelectedEntry != null) {
            previousFilename = currentSelectedEntry.filename();
        }

        listModel.clear();
        int indexToSelect = -1;
        for (int i = 0; i < safeResults.size(); i++) {
            ArchiveEntrySummary item = safeResults.get(i);
            listModel.addElement(item);
            if (previousFilename != null && previousFilename.equals(item.filename())) {
                indexToSelect = i;
            }
        }
        countLabel.setText(formatCountText(safeResults.size()));

        if (safeResults.isEmpty()) {
            currentSelectedEntry = null;
            currentRawMarkdown = "";
            detailSummaryLabel.setText("");
            copyMarkdownButton.setEnabled(false);
            renderEmptyState(query != null && !query.isBlank());
            lastSelectionFuture = CompletableFuture.completedFuture("");
        } else {
            lastSelectionFuture = CompletableFuture.completedFuture("");
            int targetIndex = Math.max(0, indexToSelect);
            entryList.setSelectedIndex(targetIndex);
        }
    }

    CompletableFuture<String> onEntrySelected(ArchiveEntrySummary entry) {
        currentSelectedEntry = entry;
        if (entry == null) {
            currentRawMarkdown = "";
            detailSummaryLabel.setText("");
            copyMarkdownButton.setEnabled(false);
            renderEmptyState(searchField != null && !searchField.getText().isBlank());
            CompletableFuture<String> done = CompletableFuture.completedFuture("");
            lastSelectionFuture = done;
            return done;
        }

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
        if (rawMarkdown != null) {
            this.currentRawMarkdown = rawMarkdown;
        } else {
            this.currentRawMarkdown = "";
        }
        copyMarkdownButton.setEnabled(!this.currentRawMarkdown.isBlank());
        displayArea.setText(buildDetailHtml(entry, this.currentRawMarkdown, mode));
        displayArea.setCaretPosition(0);
    }

    static String buildDetailHtml(ArchiveEntrySummary entry, String rawMarkdown, ArchiveMode archiveMode) {
        if (entry == null) {
            return "<html><body></body></html>";
        }
        String renderedBody = SwingHtmlUtils.renderMarkdownToHtml(FrontmatterUtils.stripFrontmatter(rawMarkdown));
        String formattedDate = formatTimestampDisplay(entry.timestamp());
        String author = StringUtils.defaultIfBlank(entry.createdBy(), "User");

        String dateDisplay;
        if (formattedDate.isEmpty()) {
            dateDisplay = entry.filename();
        } else {
            dateDisplay = formattedDate;
        }
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

    private String formatCountText(int count) {
        if (count == 1) {
            return count + " " + mode.singularNoun;
        }
        return count + " " + mode.pluralNoun;
    }

    static String formatTimestampDisplay(String rawTimestamp) {
        if (rawTimestamp == null || rawTimestamp.isBlank()) {
            return "";
        }
        String trimmed = rawTimestamp.trim();
        if (trimmed.length() >= TIMESTAMP_PREFIX_LEN) {
            return trimmed.substring(0, TIMESTAMP_PREFIX_LEN).replace('T', ' ');
        }
        return trimmed;
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
            Color bg;
            Color fg;
            Color muted;
            Color iconColor;
            if (isSelected) {
                bg = list.getSelectionBackground();
                fg = list.getSelectionForeground();
                muted = fg;
                iconColor = fg;
            } else {
                bg = list.getBackground();
                fg = list.getForeground();
                muted = SwingHtmlUtils.getMutedForegroundColor();
                iconColor = SwingHtmlUtils.getAccentColor();
            }

            setBackground(bg);
            setBorder(SwingHtmlUtils.createThemeMatteBorder(0, 0, 1, 0));

            if (value != null) {
                titleLabel.setText(value.title());
                titleLabel.setForeground(fg);
                titleLabel.setIcon(FontIcon.of(mode.getIcon(), 14, iconColor));

                String dateStr = formatTimestampDisplay(value.timestamp());
                String author = StringUtils.defaultIfBlank(value.createdBy(), "User");
                StringBuilder sub = new StringBuilder();
                if (!dateStr.isEmpty()) {
                    sub.append(dateStr).append("  •  ");
                }
                sub.append(author);
                if (mode == ArchiveMode.TOPICS && value.messageCount() > 0) {
                    sub.append("  •  ").append(value.messageCount()).append(" msgs");
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
