package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Objects;
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
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.MemoryService.ArchiveEntrySummary;
import org.roxycode.app.util.Edt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reusable Master-Detail Swing panel for browsing and searching archived Q&A topics or completed
 * implementation plans without blocking the EDT.
 */
public final class ArchiveBrowserPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(ArchiveBrowserPanel.class);

    static final int SEARCH_DEBOUNCE_MS = 200;
    static final int COPY_FEEDBACK_DELAY_MS = 1500;
    static final int MAX_SNIPPET_LENGTH = 80;
    static final int TIMESTAMP_PREFIX_LEN = 16;
    static final String COPY_BUTTON_TEXT = "Copy as Markdown";

    public enum ArchiveMode {
        TOPICS(
                "Search past topics...",
                "topic",
                "topics",
                Codicons.COMMENT_DISCUSSION,
                "Archived On",
                "No archived Q&A topics yet.",
                "When you start a New Topic in Codebase Q&A, the conversation is archived here automatically."),
        PLANS(
                "Search past plans...",
                "plan",
                "plans",
                Codicons.PROJECT,
                "Completed On",
                "No archived implementation plans yet.",
                "When a Feature Request is approved as complete in Review, its plan is archived here automatically.");

        private final String placeholder;
        private final String singularNoun;
        private final String pluralNoun;
        private final Codicons icon;
        private final String dateLabel;
        private final String emptyTitle;
        private final String emptyHint;

        ArchiveMode(
                String placeholder,
                String singularNoun,
                String pluralNoun,
                Codicons icon,
                String dateLabel,
                String emptyTitle,
                String emptyHint) {
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
    private final ArchiveMode mode;

    private final JTextField searchField;
    private final JLabel countLabel;
    private final JButton refreshButton;
    private final DefaultListModel<ArchiveEntrySummary> listModel;
    private final JList<ArchiveEntrySummary> entryList;
    private final JLabel detailSummaryLabel;
    private final JButton copyMarkdownButton;
    private final JEditorPane displayArea;
    private final Timer searchDebounceTimer;

    private Timer copyFeedbackTimer;
    private String currentRawMarkdown = "";
    private String lastCopiedMarkdown;
    private ArchiveEntrySummary currentSelectedEntry;
    private transient volatile SwingWorker<List<ArchiveEntrySummary>, Void> activeSearchWorker;
    private transient volatile SwingWorker<String, Void> activeReadWorker;

    public ArchiveBrowserPanel(MemoryService memoryService, ArchiveMode mode) {
        super(new BorderLayout());
        this.memoryService = memoryService;
        this.mode = Objects.requireNonNull(mode, "mode must not be null");

        // Top Search & Action Bar
        JPanel topBar =
                new JPanel(
                        new MigLayout("insets 8 12 8 12, fillx", "[grow, fill]12[]8[]", "center"));
        topBar.setBorder(SwingHtmlUtils.createThemeMatteBorder(0, 0, 1, 0));

        searchField = new JTextField();
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, mode.getPlaceholder());
        searchField.putClientProperty(
                FlatClientProperties.TEXT_FIELD_LEADING_ICON, FontIcon.of(Codicons.SEARCH, 14));
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

        // Left Master List
        listModel = new DefaultListModel<>();
        entryList = new JList<>(listModel);
        entryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        entryList.setCellRenderer(new ArchiveEntryCellRenderer());
        entryList.addListSelectionListener(
                e -> {
                    if (!e.getValueIsAdjusting()) {
                        onEntrySelected(entryList.getSelectedValue());
                    }
                });

        JScrollPane listScrollPane = new JScrollPane(entryList);
        listScrollPane.setBorder(null);

        // Right Detail Reader Pane
        JPanel detailPanel = new JPanel(new BorderLayout());
        JPanel detailToolbar = new JPanel(new BorderLayout(8, 0));
        detailToolbar.setBorder(
                BorderFactory.createCompoundBorder(
                        SwingHtmlUtils.createThemeMatteBorder(0, 0, 1, 0),
                        BorderFactory.createEmptyBorder(4, 12, 4, 8)));

        detailSummaryLabel = new JLabel("");
        detailSummaryLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold -1");
        detailToolbar.add(detailSummaryLabel, BorderLayout.WEST);

        JPanel actionRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actionRight.setOpaque(false);
        copyMarkdownButton = new JButton(COPY_BUTTON_TEXT, FontIcon.of(Codicons.CLIPPY, 14));
        copyMarkdownButton.setEnabled(false);
        copyMarkdownButton.addActionListener(e -> copyCurrentMarkdown());
        actionRight.add(copyMarkdownButton);
        detailToolbar.add(actionRight, BorderLayout.EAST);

        displayArea = new JEditorPane();
        displayArea.setEditable(false);
        displayArea.setContentType("text/html");
        displayArea.setOpaque(true);
        displayArea.putClientProperty("JEditorPane.honorDisplayProperties", true);
        SwingHtmlUtils.installTextContextMenu(displayArea);

        JScrollPane detailScrollPane = new JScrollPane(displayArea);
        detailScrollPane.setBorder(null);

        detailPanel.add(detailToolbar, BorderLayout.NORTH);
        detailPanel.add(detailScrollPane, BorderLayout.CENTER);

        JSplitPane splitPane =
                new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScrollPane, detailPanel);
        splitPane.setDividerLocation(320);
        splitPane.setResizeWeight(0.28);
        splitPane.setBorder(null);
        add(splitPane, BorderLayout.CENTER);

        searchDebounceTimer = new Timer(SEARCH_DEBOUNCE_MS, e -> executeSearchAsync());
        searchDebounceTimer.setRepeats(false);

        searchField
                .getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            @Override
                            public void insertUpdate(DocumentEvent e) {
                                scheduleDebouncedSearch();
                            }

                            @Override
                            public void removeUpdate(DocumentEvent e) {
                                scheduleDebouncedSearch();
                            }

                            @Override
                            public void changedUpdate(DocumentEvent e) {
                                scheduleDebouncedSearch();
                            }
                        });

        updateTheme();
        renderEmptyState(false);
    }

    private void scheduleDebouncedSearch() {
        if (searchDebounceTimer.isRunning()) {
            searchDebounceTimer.restart();
        } else {
            searchDebounceTimer.start();
        }
    }

    /** Refreshes the archive list and search results asynchronously off the EDT. */
    public SwingWorker<List<ArchiveEntrySummary>, Void> refresh() {
        if (searchDebounceTimer != null && searchDebounceTimer.isRunning()) {
            searchDebounceTimer.stop();
        }
        return executeSearchAsync();
    }

    SwingWorker<List<ArchiveEntrySummary>, Void> executeSearchAsync() {
        String query = searchField != null ? searchField.getText() : "";
        SwingWorker<List<ArchiveEntrySummary>, Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected List<ArchiveEntrySummary> doInBackground() {
                        try {
                            List<ArchiveEntrySummary> results = loadEntriesForQuery(query);
                            Edt.run(() -> applySearchResults(this, results, query));
                            return results;
                        } catch (Exception e) {
                            log.warn(
                                    "Failed to load archive entries for {}: {}",
                                    mode,
                                    e.getMessage());
                            Edt.run(() -> applySearchResults(this, List.of(), query));
                            return List.of();
                        }
                    }
                };
        activeSearchWorker = worker;
        worker.execute();
        return worker;
    }

    private List<ArchiveEntrySummary> loadEntriesForQuery(String query) {
        if (memoryService == null) {
            return List.of();
        }
        String trimmed = query != null ? query.trim() : "";
        if (mode == ArchiveMode.TOPICS) {
            return trimmed.isEmpty()
                    ? memoryService.listArchivedTopicSummaries()
                    : memoryService.searchArchivedTopics(trimmed);
        } else {
            return trimmed.isEmpty()
                    ? memoryService.listArchivedPlanSummaries()
                    : memoryService.searchArchivedPlans(trimmed);
        }
    }

    private void applySearchResults(
            SwingWorker<List<ArchiveEntrySummary>, Void> worker,
            List<ArchiveEntrySummary> results,
            String query) {
        if (worker != activeSearchWorker) {
            return;
        }
        List<ArchiveEntrySummary> safeResults = results != null ? results : List.of();
        String previousFilename =
                currentSelectedEntry != null ? currentSelectedEntry.filename() : null;

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
            boolean hasQuery = query != null && !query.isBlank();
            renderEmptyState(hasQuery);
        } else {
            if (indexToSelect < 0) {
                indexToSelect = 0;
            }
            entryList.setSelectedIndex(indexToSelect);
        }
    }

    private void onEntrySelected(ArchiveEntrySummary entry) {
        currentSelectedEntry = entry;
        if (entry == null) {
            currentRawMarkdown = "";
            detailSummaryLabel.setText("");
            copyMarkdownButton.setEnabled(false);
            boolean hasQuery = searchField != null && !searchField.getText().isBlank();
            renderEmptyState(hasQuery);
            return;
        }

        detailSummaryLabel.setText(entry.title());
        SwingWorker<String, Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected String doInBackground() {
                        try {
                            String raw = readEntryMarkdown(entry.filename());
                            String normalized = raw != null ? raw : "";
                            Edt.run(() -> applyDetailContent(this, entry, normalized));
                            return normalized;
                        } catch (Exception e) {
                            log.warn(
                                    "Failed to read archived markdown {}: {}",
                                    entry.filename(),
                                    e.getMessage());
                            Edt.run(() -> applyDetailContent(this, entry, ""));
                            return "";
                        }
                    }
                };
        activeReadWorker = worker;
        worker.execute();
    }

    private String readEntryMarkdown(String filename) {
        if (memoryService == null || filename == null || filename.isBlank()) {
            return "";
        }
        return mode == ArchiveMode.TOPICS
                ? memoryService.readArchivedTopic(filename)
                : memoryService.readArchivedPlan(filename);
    }

    private void applyDetailContent(
            SwingWorker<String, Void> worker, ArchiveEntrySummary entry, String rawMarkdown) {
        if (worker != activeReadWorker) {
            return;
        }
        this.currentRawMarkdown = rawMarkdown != null ? rawMarkdown : "";
        copyMarkdownButton.setEnabled(!this.currentRawMarkdown.isBlank());
        String html = buildDetailHtml(entry, this.currentRawMarkdown, mode);
        displayArea.setText(html);
        displayArea.setCaretPosition(0);
    }

    static String buildDetailHtml(
            ArchiveEntrySummary entry, String rawMarkdown, ArchiveMode archiveMode) {
        if (entry == null) {
            return "<html><body></body></html>";
        }
        String cleanBody = MemoryService.stripFrontmatter(rawMarkdown);
        String renderedBody =
                JexlToHtmlConverter.highlightCodeBlocks(
                        SwingHtmlUtils.renderMarkdownToHtml(cleanBody));

        String formattedDate = formatTimestampDisplay(entry.timestamp());
        String author =
                entry.createdBy() != null && !entry.createdBy().isBlank()
                        ? entry.createdBy()
                        : "User";

        StringBuilder metaRows = new StringBuilder();
        metaRows.append(
                """
                <tr>
                    <td valign='top' nowrap='nowrap' class='plan-muted'><b>%s:</b></td>
                    <td width='100%%'>%s &nbsp;&bull;&nbsp; <b>By:</b> %s%s</td>
                </tr>
                """
                        .formatted(
                                SwingHtmlUtils.escapeHtml(archiveMode.dateLabel),
                                SwingHtmlUtils.escapeHtml(
                                        formattedDate.isEmpty() ? entry.filename() : formattedDate),
                                SwingHtmlUtils.escapeHtml(author),
                                archiveMode == ArchiveMode.TOPICS && entry.messageCount() > 0
                                        ? " &nbsp;&bull;&nbsp; <b>Messages:</b> "
                                                + entry.messageCount()
                                        : ""));

        if (entry.matchSnippet() != null && !entry.matchSnippet().isBlank()) {
            metaRows.append(
                    """
                    <tr>
                        <td valign='top' nowrap='nowrap' class='plan-muted'><b>Match:</b></td>
                        <td width='100%%'><i>%s</i></td>
                    </tr>
                    """
                            .formatted(SwingHtmlUtils.escapeHtml(entry.matchSnippet())));
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
                """
                .formatted(
                        SwingHtmlUtils.escapeHtml(entry.title()),
                        SwingHtmlUtils.escapeHtml(entry.filename()),
                        metaRows,
                        renderedBody);
    }

    private void renderEmptyState(boolean isSearchEmpty) {
        String title =
                isSearchEmpty ? "No matching " + mode.pluralNoun + " found." : mode.emptyTitle;
        String hint =
                isSearchEmpty
                        ? "Try a different search term or clear the search box."
                        : mode.emptyHint;
        String html =
                """
                <html><body>
                <h3>%s</h3>
                <p class='plan-muted'>%s</p>
                </body></html>
                """
                        .formatted(
                                SwingHtmlUtils.escapeHtml(title), SwingHtmlUtils.escapeHtml(hint));
        displayArea.setText(html);
        displayArea.setCaretPosition(0);
    }

    private void copyCurrentMarkdown() {
        if (currentRawMarkdown == null || currentRawMarkdown.isBlank()) {
            return;
        }
        this.lastCopiedMarkdown = currentRawMarkdown;
        SwingHtmlUtils.copyToClipboard(currentRawMarkdown);
        copyFeedbackTimer =
                SwingHtmlUtils.showCopyButtonFeedback(
                        copyMarkdownButton,
                        copyFeedbackTimer,
                        "Copied!",
                        FontIcon.of(Codicons.CHECK, 14),
                        COPY_BUTTON_TEXT,
                        FontIcon.of(Codicons.CLIPPY, 14),
                        COPY_FEEDBACK_DELAY_MS);
    }

    private String formatCountText(int count) {
        return count + " " + (count == 1 ? mode.singularNoun : mode.pluralNoun);
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
                displayArea.setText(
                        buildDetailHtml(currentSelectedEntry, currentRawMarkdown, mode));
                displayArea.setCaretPosition(0);
            } else {
                boolean hasQuery = searchField != null && !searchField.getText().isBlank();
                renderEmptyState(hasQuery);
            }
        }
    }

    private void updateTheme() {
        SwingHtmlUtils.applyTheme(displayArea, 16);
        if (countLabel != null) {
            countLabel.setForeground(
                    SwingHtmlUtils.getUiColor(
                            Color.GRAY, "Label.disabledForeground", "Component.infoForeground"));
        }
    }

    ArchiveMode getMode() {
        return mode;
    }

    JTextField getSearchField() {
        return searchField;
    }

    JLabel getCountLabel() {
        return countLabel;
    }

    JButton getRefreshButton() {
        return refreshButton;
    }

    DefaultListModel<ArchiveEntrySummary> getListModel() {
        return listModel;
    }

    JList<ArchiveEntrySummary> getEntryList() {
        return entryList;
    }

    JLabel getDetailSummaryLabel() {
        return detailSummaryLabel;
    }

    JButton getCopyMarkdownButton() {
        return copyMarkdownButton;
    }

    JEditorPane getDisplayArea() {
        return displayArea;
    }

    String getLastCopiedMarkdown() {
        return lastCopiedMarkdown;
    }

    SwingWorker<List<ArchiveEntrySummary>, Void> getActiveSearchWorker() {
        return activeSearchWorker;
    }

    SwingWorker<String, Void> getActiveReadWorker() {
        return activeReadWorker;
    }

    private final class ArchiveEntryCellRenderer extends JPanel
            implements ListCellRenderer<ArchiveEntrySummary> {
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
        public Component getListCellRendererComponent(
                JList<? extends ArchiveEntrySummary> list,
                ArchiveEntrySummary value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            Color bg = isSelected ? list.getSelectionBackground() : list.getBackground();
            Color fg = isSelected ? list.getSelectionForeground() : list.getForeground();
            Color muted =
                    isSelected
                            ? fg
                            : SwingHtmlUtils.getUiColor(
                                    Color.GRAY,
                                    "Label.disabledForeground",
                                    "Component.infoForeground");
            Color iconColor =
                    isSelected
                            ? fg
                            : SwingHtmlUtils.getUiColor(
                                    new Color(0x00, 0x7a, 0xcc),
                                    "Component.accentColor",
                                    "Actions.Blue");

            setBackground(bg);
            setBorder(SwingHtmlUtils.createThemeMatteBorder(0, 0, 1, 0));

            if (value != null) {
                titleLabel.setText(value.title());
                titleLabel.setForeground(fg);
                titleLabel.setIcon(FontIcon.of(mode.getIcon(), 14, iconColor));

                String dateStr = formatTimestampDisplay(value.timestamp());
                String author =
                        value.createdBy() != null && !value.createdBy().isBlank()
                                ? value.createdBy()
                                : "User";
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
                if (snippet != null && !snippet.isBlank()) {
                    snippetLabel.setText(
                            StringUtils.abbreviate(snippet.trim(), MAX_SNIPPET_LENGTH));
                    snippetLabel.setForeground(muted);
                    snippetLabel.setVisible(true);
                } else {
                    snippetLabel.setText("");
                    snippetLabel.setVisible(false);
                }
                setToolTipText(value.title() + " (" + value.filename() + ")");
            }
            return this;
        }
    }
}
