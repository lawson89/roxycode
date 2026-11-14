package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.MemoryService.MemoryEntry;
import org.roxycode.app.events.MemoryChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.ExceptionUtils;
import org.roxycode.app.util.PathUtils;
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
final class AgentMemoryPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(AgentMemoryPanel.class);

    static final String PAGE_TITLE = "Agent Memory";
    static final String PAGE_SUBTITLE = "Manage persistent Global and Project memories with always-on summaries and on-demand Markdown details.";
    static final String PROJECT_TAB_BASE = "Project Memory";
    static final String GLOBAL_TAB_BASE = "Global Memory";
    static final String NO_PROJECT_MESSAGE = "No active project selected. Select a project folder in the top bar to manage Project Memory.";
    static final String MEMORY_TOOLTIP_HTML = "<html><body style='width: 310px; padding: 4px;'>"
            + "<b>Unified Symmetrical Agent Memory</b><br>"
            + "• <b>Summary</b>: Concise rule or takeaway always injected into Gemini's stable system prompt.<br>"
            + "• <b>Extended Details</b>: Optional Markdown body read on demand via <code>memoryService.read(scope, id)</code> when relevant.<br><br>"
            + "Chat quick commands: <code>/mem-p-put</code>, <code>/mem-p-view</code>, <code>/mem-p-find</code>, <code>/mem-p-del</code> (and <code>/mem-g-*</code> for Global)."
            + "</body></html>";
    private static final int TIMESTAMP_DISPLAY_LEN = 16;
    private static final int PROJECT_TAB_INDEX = 0;
    private static final int GLOBAL_TAB_INDEX = 1;
    private static final String CONSTRAINTS_GROWX = "growx";
    private static final String STYLE_FONT_BOLD = "font: bold";

    private final transient MemoryService memoryService;
    private final transient ProjectService projectService;
    private final transient SettingsService settingsService;

    final JLabel titleLabel;
    final JLabel subtitleLabel;
    final JLabel promptFootprintBadge;
    final JTabbedPane scopeTabbedPane;
    final MemoryScopeView projectScopeView;
    final MemoryScopeView globalScopeView;

    private final transient UiComponents.UiGuard memoryEventRefreshGuard = new UiComponents.UiGuard();

    @Autowired
    public AgentMemoryPanel(MemoryService memoryService, @Nullable ProjectService projectService,
            @Nullable SettingsService settingsService) {
        this.memoryService = memoryService;
        this.projectService = projectService;
        this.settingsService = settingsService;

        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        this.titleLabel = new JLabel(PAGE_TITLE);
        this.subtitleLabel = new JLabel(PAGE_SUBTITLE);
        add(UiComponents.createPageHeader(titleLabel, subtitleLabel, null), BorderLayout.NORTH);

        this.promptFootprintBadge = new JLabel("Always-On Prompt: ~0 tok");
        UiComponents.styleCodePillBadge(promptFootprintBadge);

        this.projectScopeView = new MemoryScopeView(false);
        this.globalScopeView = new MemoryScopeView(true);

        this.scopeTabbedPane = UiComponents.createUnderlinedTabbedPane(promptFootprintBadge);
        this.scopeTabbedPane.addTab(PROJECT_TAB_BASE + " (0)", FontIcon.of(Codicons.FOLDER_OPENED, 14),
                projectScopeView);
        this.scopeTabbedPane.addTab(GLOBAL_TAB_BASE + " (0)", FontIcon.of(Codicons.GLOBE, 14), globalScopeView);
        this.scopeTabbedPane.addChangeListener(e -> updatePromptFootprintBadge());
        add(scopeTabbedPane, BorderLayout.CENTER);

        refresh();
    }

    public void refresh() {
        projectScopeView.refresh();
        globalScopeView.refresh();
    }

    void updateTabTitle(boolean globalScope, int count) {
        if (globalScope) {
            scopeTabbedPane.setTitleAt(GLOBAL_TAB_INDEX, GLOBAL_TAB_BASE + " (" + count + ")");
        } else {
            scopeTabbedPane.setTitleAt(PROJECT_TAB_INDEX, PROJECT_TAB_BASE + " (" + count + ")");
        }
        updatePromptFootprintBadge();
    }

    void updatePromptFootprintBadge() {
        int selectedIndex = scopeTabbedPane.getSelectedIndex();
        MemoryScopeView activeView;
        if (selectedIndex == GLOBAL_TAB_INDEX) {
            activeView = globalScopeView;
        } else {
            activeView = projectScopeView;
        }
        long tokens = activeView.estimatePromptTokens();
        promptFootprintBadge.setText("Always-On Prompt: ~" + TokenUtils.formatTokenCount(tokens) + " tok");
    }

    @EventListener
    public void onMemoryChanged(MemoryChangedEvent event) {
        if (event == null || memoryEventRefreshGuard.isActive()) {
            return;
        }
        Edt.run(() -> {
            if (event.globalScope()) {
                globalScopeView.refresh();
            } else {
                projectScopeView.refresh();
            }
        });
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        Edt.run(projectScopeView::refresh);
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        Edt.run(() -> {
            projectScopeView.updateTheme();
            globalScopeView.updateTheme();
        });
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (promptFootprintBadge != null) {
            UiComponents.styleCodePillBadge(promptFootprintBadge);
        }
        if (projectScopeView != null) {
            projectScopeView.updateTheme();
        }
        if (globalScopeView != null) {
            globalScopeView.updateTheme();
        }
    }

    final class MemoryScopeView extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final String CARD_CONTENT = "CONTENT";
        private static final String CARD_EMPTY = "EMPTY";

        private final boolean globalScope;
        private final String scopeName;
        private final CardLayout cardLayout = new CardLayout();
        private final JPanel cardContainer = new JPanel(cardLayout);

        final JTextField searchField = new JTextField();
        final JButton newButton;
        final DefaultListModel<MemoryEntry> listModel = new DefaultListModel<>();
        final JList<MemoryEntry> entryList = new JList<>(listModel);

        final JTextField idField = new JTextField();
        final JTextField tagsField = new JTextField();
        final JLabel summaryTitleLabel = new JLabel("Summary (Always passed to Gemini)");
        final JLabel summaryStatsLabel = new JLabel("0 chars  •  ~0 tok");
        final JLabel memoryInfoIcon = UiComponents.createInfoIcon(MEMORY_TOOLTIP_HTML);
        final JTextArea summaryArea = new JTextArea(3, 40);
        final JScrollPane summaryScrollPane;
        final RSyntaxTextArea detailsEditor = new RSyntaxTextArea(12, 40);
        final RTextScrollPane detailsScrollPane;

        final JButton saveButton;
        final JButton revertButton;
        final JButton deleteButton;
        final JLabel statusLabel = new JLabel(" ");
        final UiComponents.InlineConfirmBar deleteConfirmBar = new UiComponents.InlineConfirmBar();

        private final Timer statusTimer;
        transient CompletableFuture<?> lastAsyncFuture = CompletableFuture.completedFuture(null);
        private final transient UiComponents.UiGuard editorSyncGuard = new UiComponents.UiGuard();

        private List<MemoryEntry> allEntries = List.of();
        private transient @Nullable MemoryEntry selectedEntry;
        private boolean creatingNewEntry = true;
        private boolean idManuallyEdited = false;
        private String loadedId = "";
        private String loadedTags = "";
        private String loadedSummary = "";
        private String loadedDetails = "";

        MemoryScopeView(boolean globalScope) {
            super(new BorderLayout());
            this.globalScope = globalScope;
            if (globalScope) {
                this.scopeName = MemoryService.SCOPE_GLOBAL;
            } else {
                this.scopeName = MemoryService.SCOPE_PROJECT;
            }

            this.newButton = UiComponents.createActionButton("New", Codicons.ADD, null, false, null, null);
            this.saveButton = UiComponents.createActionButton("Save Memory", Codicons.SAVE, null, false,
                    UiComponents.ACCENT_BUTTON_STYLE, null);
            this.revertButton = UiComponents.createActionButton("Revert", Codicons.DISCARD, null, false, null, null);
            this.deleteButton = UiComponents.createActionButton("Delete", Codicons.TRASH, null, false,
                    UiComponents.SUBTLE_RED_BUTTON_STYLE, null);
            this.statusTimer = UiComponents.createStatusTimer(statusLabel, 4000);

            this.summaryArea.setLineWrap(true);
            this.summaryArea.setWrapStyleWord(true);
            this.summaryScrollPane = new JScrollPane(summaryArea);

            UiComponents.configureSyntaxTextArea(detailsEditor, SyntaxConstants.SYNTAX_STYLE_MARKDOWN, true, true);
            this.detailsScrollPane = new RTextScrollPane(detailsEditor);
            this.detailsScrollPane.setFoldIndicatorEnabled(false);
            this.detailsScrollPane.setLineNumbersEnabled(false);

            JPanel masterPanel = buildMasterPanel();
            JPanel detailPanel = buildDetailPanel();

            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, masterPanel, detailPanel);
            splitPane.setResizeWeight(0.34);
            splitPane.setContinuousLayout(true);
            splitPane.setBorder(null);

            JPanel emptyPanel = new JPanel(new BorderLayout());
            JLabel emptyLabel = new JLabel(NO_PROJECT_MESSAGE, SwingConstants.CENTER);
            emptyLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
            emptyPanel.add(emptyLabel, BorderLayout.CENTER);

            cardContainer.add(splitPane, CARD_CONTENT);
            cardContainer.add(emptyPanel, CARD_EMPTY);
            add(cardContainer, BorderLayout.CENTER);

            setupListeners();
            updateTheme();
            startNewEntry();
        }

        private JPanel buildMasterPanel() {
            JPanel panel = new JPanel(new MigLayout("insets 8 4 8 8, fill, wrap 1", "[grow, fill]", "[][grow, fill]"));
            panel.setMinimumSize(new Dimension(250, 200));

            searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search id, summary, tags...");
            searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
            JPanel searchRow = new JPanel(new MigLayout("insets 0, fillx", "[grow, fill][]", "[]"));
            searchRow.setOpaque(false);
            searchRow.add(searchField, CONSTRAINTS_GROWX);
            searchRow.add(newButton);
            panel.add(searchRow, CONSTRAINTS_GROWX);

            entryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            entryList.setCellRenderer(new MemoryEntryCellRenderer());
            JScrollPane listScroll = new JScrollPane(entryList);
            panel.add(listScroll, "grow");
            return panel;
        }

        private JPanel buildDetailPanel() {
            JPanel panel = new JPanel(
                    new MigLayout("insets 8 8 8 4, fill, wrap 1", "[grow, fill]", "[][][][][grow, fill][][]"));
            panel.setMinimumSize(new Dimension(340, 200));

            idField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "e.g., swing-html-tables");
            tagsField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "tag1, tag2");

            JPanel metaRow = new JPanel(new MigLayout("insets 0, fillx", "[][grow, fill]12[][grow, fill]", "[]"));
            metaRow.setOpaque(false);
            JLabel idLabel = new JLabel("ID / Slug:");
            idLabel.putClientProperty(FlatClientProperties.STYLE, STYLE_FONT_BOLD);
            JLabel tagsLabel = new JLabel("Tags:");
            tagsLabel.putClientProperty(FlatClientProperties.STYLE, STYLE_FONT_BOLD);
            metaRow.add(idLabel);
            metaRow.add(idField, CONSTRAINTS_GROWX);
            metaRow.add(tagsLabel);
            metaRow.add(tagsField, CONSTRAINTS_GROWX);
            panel.add(metaRow, CONSTRAINTS_GROWX);

            JPanel summaryHeaderRow = new JPanel(new MigLayout("insets 4 0 0 0, fillx", "[][][grow, fill][]", "[]"));
            summaryHeaderRow.setOpaque(false);
            summaryTitleLabel.putClientProperty(FlatClientProperties.STYLE, STYLE_FONT_BOLD);
            summaryStatsLabel.putClientProperty(FlatClientProperties.STYLE,
                    "font: -1; foreground: $Label.disabledForeground");
            summaryHeaderRow.add(summaryTitleLabel);
            summaryHeaderRow.add(memoryInfoIcon, "gapleft 4");
            summaryHeaderRow.add(new JPanel() {
                private static final long serialVersionUID = 1L;
                {
                    setOpaque(false);
                }
            }, CONSTRAINTS_GROWX);
            summaryHeaderRow.add(summaryStatsLabel);
            panel.add(summaryHeaderRow, CONSTRAINTS_GROWX);

            summaryScrollPane.setMinimumSize(new Dimension(200, 72));
            panel.add(summaryScrollPane, "growx, hmin 72, hmax 110");

            JLabel detailsLabel = new JLabel("Extended Details (Optional Markdown — read on demand by Gemini)");
            detailsLabel.putClientProperty(FlatClientProperties.STYLE, STYLE_FONT_BOLD);
            panel.add(detailsLabel, "gapy 4");

            panel.add(detailsScrollPane, "grow");

            JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            buttonRow.setOpaque(false);
            buttonRow.add(saveButton);
            buttonRow.add(revertButton);
            buttonRow.add(deleteButton);
            statusLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            buttonRow.add(statusLabel);
            panel.add(buttonRow, CONSTRAINTS_GROWX);

            panel.add(deleteConfirmBar, "growx, hidemode 3");
            return panel;
        }

        private void setupListeners() {
            SwingHtmlUtils.addChangeListener(searchField, this::applyFilter);
            newButton.addActionListener(e -> startNewEntry());
            saveButton.addActionListener(e -> saveCurrentMemory());
            revertButton.addActionListener(e -> revertCurrentMemory());
            deleteButton.addActionListener(e -> promptDeleteCurrentMemory());

            entryList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    onEntrySelected(entryList.getSelectedValue());
                }
            });

            SwingHtmlUtils.addChangeListener(idField, this::onIdFieldEdited);
            SwingHtmlUtils.addChangeListener(tagsField, this::updateEditorButtonsAndStats);
            SwingHtmlUtils.addChangeListener(summaryArea, this::onSummaryEdited);
            SwingHtmlUtils.addChangeListener(detailsEditor, this::updateEditorButtonsAndStats);
        }

        boolean isScopeAvailable() {
            if (globalScope) {
                return true;
            }
            return projectService != null && projectService.hasActiveProject();
        }

        long estimatePromptTokens() {
            if (!isScopeAvailable() || allEntries.isEmpty()) {
                return 0L;
            }
            StringBuilder sb = new StringBuilder();
            for (MemoryEntry entry : allEntries) {
                sb.append("- ").append(entry.id()).append(' ');
                if (!entry.tags().isEmpty()) {
                    sb.append(String.join(", ", entry.tags())).append(' ');
                }
                if (StringUtils.isNotBlank(entry.details())) {
                    sb.append("(+details) ");
                }
                sb.append(entry.summary()).append('\n');
            }
            return TokenUtils.estimateTokens(sb.toString());
        }

        private void onIdFieldEdited() {
            if (editorSyncGuard.isActive()) {
                return;
            }
            if (creatingNewEntry) {
                idManuallyEdited = !idField.getText().trim().isEmpty();
            }
            updateEditorButtonsAndStats();
        }

        private void onSummaryEdited() {
            if (editorSyncGuard.isActive()) {
                return;
            }
            if (creatingNewEntry && !idManuallyEdited) {
                editorSyncGuard.run(() -> {
                    String summaryText = summaryArea.getText();
                    if (StringUtils.isBlank(summaryText)) {
                        idField.setText("");
                    } else {
                        idField.setText(MemoryService.deriveSlugFromSummary(summaryText));
                    }
                });
            }
            updateEditorButtonsAndStats();
        }

        void updateEditorButtonsAndStats() {
            String summaryText = StringUtils.defaultString(summaryArea.getText());
            long tokens = TokenUtils.estimateTokens(summaryText);
            summaryStatsLabel.setText(summaryText.length() + " chars  •  ~" + tokens + " tok");

            boolean available = isScopeAvailable();
            boolean dirty = isEditorDirty();
            boolean hasSummary = StringUtils.isNotBlank(summaryText);
            saveButton.setEnabled(available && hasSummary && (creatingNewEntry || dirty));
            revertButton.setEnabled(available && dirty);
            deleteButton.setEnabled(available && selectedEntry != null && !creatingNewEntry);
        }

        boolean isEditorDirty() {
            return !Objects.equals(idField.getText().trim(), loadedId)
                    || !Objects.equals(tagsField.getText().trim(), loadedTags)
                    || !Objects.equals(summaryArea.getText(), loadedSummary)
                    || !Objects.equals(detailsEditor.getText(), loadedDetails);
        }

        CompletableFuture<List<MemoryEntry>> refresh() {
            if (!isScopeAvailable()) {
                cardLayout.show(cardContainer, CARD_EMPTY);
                allEntries = List.of();
                listModel.clear();
                updateTabTitle(globalScope, 0);
                startNewEntry();
                return CompletableFuture.completedFuture(List.of());
            }
            cardLayout.show(cardContainer, CARD_CONTENT);
            lastAsyncFuture = Edt.supplyAsync(() -> memoryService.readAll(scopeName), this::applyLoadedEntries,
                    ex -> log.warn("Failed to load {} memories: {}", scopeName,
                            ExceptionUtils.getRootCauseMessage(ex)));
            return (CompletableFuture<List<MemoryEntry>>) lastAsyncFuture;
        }

        private void applyLoadedEntries(List<MemoryEntry> entries) {
            allEntries = Objects.requireNonNullElse(entries, List.of());
            updateTabTitle(globalScope, allEntries.size());
            applyFilter();
            if (selectedEntry != null) {
                MemoryEntry refreshed = findEntryById(allEntries, selectedEntry.id());
                if (refreshed != null) {
                    if (!isEditorDirty()) {
                        populateEditor(refreshed);
                    }
                } else if (!isEditorDirty()) {
                    startNewEntry();
                }
            }
        }

        private static @Nullable MemoryEntry findEntryById(List<MemoryEntry> entries, String id) {
            for (MemoryEntry candidate : entries) {
                if (candidate.id().equals(id)) {
                    return candidate;
                }
            }
            return null;
        }

        private void applyFilter() {
            String query = StringUtils.trimToEmpty(searchField.getText());
            List<MemoryEntry> filtered = new ArrayList<>();
            for (MemoryEntry entry : allEntries) {
                if (matchesFilter(entry, query)) {
                    filtered.add(entry);
                }
            }
            String previouslySelectedId = null;
            if (selectedEntry != null) {
                previouslySelectedId = selectedEntry.id();
            }
            final String targetId = previouslySelectedId;
            editorSyncGuard.run(() -> UiComponents.replaceListItemsPreservingSelection(listModel, entryList, filtered,
                    MemoryEntry::id, targetId, false));
        }

        private static boolean matchesFilter(MemoryEntry entry, String query) {
            if (query.isEmpty()) {
                return true;
            }
            if (StringUtils.containsIgnoreCase(entry.id(), query)
                    || StringUtils.containsIgnoreCase(entry.summary(), query)
                    || StringUtils.containsIgnoreCase(entry.details(), query)) {
                return true;
            }
            for (String tag : entry.tags()) {
                if (StringUtils.containsIgnoreCase(tag, query)) {
                    return true;
                }
            }
            return false;
        }

        @SuppressWarnings("PMD.NullAssignment")
        void startNewEntry() {
            selectedEntry = null;
            creatingNewEntry = true;
            idManuallyEdited = false;
            deleteConfirmBar.cancel();
            editorSyncGuard.run(() -> {
                entryList.clearSelection();
                loadedId = "";
                loadedTags = "";
                loadedSummary = "";
                loadedDetails = "";
                idField.setText("");
                tagsField.setText("");
                summaryArea.setText("");
                detailsEditor.setText("");
            });
            updateEditorButtonsAndStats();
        }

        void onEntrySelected(@Nullable MemoryEntry entry) {
            if (editorSyncGuard.isActive()) {
                return;
            }
            if (entry == null) {
                if (!creatingNewEntry) {
                    startNewEntry();
                }
                return;
            }
            populateEditor(entry);
        }

        private void populateEditor(MemoryEntry entry) {
            selectedEntry = entry;
            creatingNewEntry = false;
            idManuallyEdited = true;
            deleteConfirmBar.cancel();
            editorSyncGuard.run(() -> {
                loadedId = entry.id();
                loadedTags = MemoryService.formatTagsDisplay(entry.tags());
                loadedSummary = entry.summary();
                loadedDetails = entry.details();
                idField.setText(loadedId);
                tagsField.setText(loadedTags);
                summaryArea.setText(loadedSummary);
                summaryArea.setCaretPosition(0);
                detailsEditor.setText(loadedDetails);
                detailsEditor.setCaretPosition(0);
            });
            updateEditorButtonsAndStats();
        }

        void revertCurrentMemory() {
            deleteConfirmBar.cancel();
            if (selectedEntry != null && !creatingNewEntry) {
                populateEditor(selectedEntry);
            } else {
                startNewEntry();
            }
            showStatus("Reverted changes", true);
        }

        CompletableFuture<EditorResult> saveCurrentMemory() {
            if (!isScopeAvailable()) {
                return CompletableFuture.completedFuture(new EditorResult(false, "", NO_PROJECT_MESSAGE));
            }
            String summary = summaryArea.getText().trim();
            if (summary.isEmpty()) {
                showStatus("Summary must not be blank.", false);
                return CompletableFuture.completedFuture(new EditorResult(false, "", "Summary must not be blank."));
            }
            String rawId = idField.getText().trim();
            if (rawId.isEmpty()) {
                rawId = MemoryService.deriveSlugFromSummary(summary);
            }
            String sanitizedId;
            try {
                sanitizedId = MemoryService.sanitizeSlug(rawId);
            } catch (IllegalArgumentException e) {
                showStatus(e.getMessage(), false);
                return CompletableFuture.completedFuture(new EditorResult(false, "", e.getMessage()));
            }
            List<String> tags = MemoryService.parseTagsString(tagsField.getText());
            String details = detailsEditor.getText();
            final String targetId = sanitizedId;
            saveButton.setEnabled(false);

            lastAsyncFuture = Edt.supplyAsync(
                    () -> memoryEventRefreshGuard
                            .supply(() -> memoryService.put(scopeName, targetId, summary, tags, details)),
                    result -> handleMemorySaved(result, targetId), ex -> {
                        updateEditorButtonsAndStats();
                        showStatus("Failed to save memory: " + ExceptionUtils.getRootCauseMessage(ex), false);
                    });
            return (CompletableFuture<EditorResult>) lastAsyncFuture;
        }

        private void handleMemorySaved(@Nullable EditorResult result, String savedId) {
            if (result != null && result.success()) {
                creatingNewEntry = false;
                idManuallyEdited = true;
                editorSyncGuard.run(() -> {
                    loadedId = savedId;
                    loadedTags = MemoryService.formatTagsDisplay(MemoryService.parseTagsString(tagsField.getText()));
                    loadedSummary = summaryArea.getText().trim();
                    loadedDetails = detailsEditor.getText().stripTrailing();
                    idField.setText(loadedId);
                    tagsField.setText(loadedTags);
                    summaryArea.setText(loadedSummary);
                    detailsEditor.setText(loadedDetails);
                    selectedEntry = new MemoryEntry(loadedId, loadedSummary, MemoryService.parseTagsString(loadedTags),
                            loadedDetails, "");
                });
                showStatus("Saved memory '" + savedId + "'", true);
                refresh();
            } else {
                String err = "Failed to save memory";
                if (result != null && StringUtils.isNotBlank(result.errorHint())) {
                    err = result.errorHint();
                }
                showStatus(err, false);
                updateEditorButtonsAndStats();
            }
        }

        void promptDeleteCurrentMemory() {
            if (!isScopeAvailable() || selectedEntry == null || creatingNewEntry) {
                return;
            }
            String id = selectedEntry.id();
            deleteConfirmBar.prompt("Delete memory '" + id + "'?", "Confirm Delete", true, () -> performDelete(id));
        }

        CompletableFuture<EditorResult> performDelete(String id) {
            deleteButton.setEnabled(false);
            lastAsyncFuture = Edt.supplyAsync(
                    () -> memoryEventRefreshGuard.supply(() -> memoryService.delete(scopeName, id)),
                    result -> handleMemoryDeleted(result, id), ex -> {
                        updateEditorButtonsAndStats();
                        showStatus("Failed to delete memory: " + ExceptionUtils.getRootCauseMessage(ex), false);
                    });
            return (CompletableFuture<EditorResult>) lastAsyncFuture;
        }

        private void handleMemoryDeleted(@Nullable EditorResult result, String deletedId) {
            if (result != null && result.success()) {
                showStatus("Deleted memory '" + deletedId + "'", true);
                startNewEntry();
                refresh();
            } else {
                String err = "Failed to delete memory";
                if (result != null && StringUtils.isNotBlank(result.errorHint())) {
                    err = result.errorHint();
                }
                showStatus(err, false);
                updateEditorButtonsAndStats();
            }
        }

        void showStatus(String message, boolean success) {
            UiComponents.showStatusMessage(statusLabel, statusTimer, message, success);
        }

        void updateTheme() {
            Color accent = SwingHtmlUtils.getAccentColor();
            Color mutedColor = SwingHtmlUtils.getMutedForegroundColor();
            searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON,
                    FontIcon.of(Codicons.SEARCH, 14, mutedColor));
            summaryTitleLabel.setForeground(accent);
            Color tintedBg = SwingHtmlUtils.getTintedBackground(accent, 0.08);
            summaryArea.setBackground(tintedBg);
            summaryScrollPane
                    .setBorder(BorderFactory.createLineBorder(SwingHtmlUtils.blendColor(accent, tintedBg, 0.5)));
            UiComponents.updateInfoIconTheme(memoryInfoIcon);
            UiComponents.refreshActionButtonIcons(this);
            SwingHtmlUtils.applyRSyntaxTheme(detailsEditor, settingsService);
            entryList.repaint();
        }
    }

    private static final class MemoryEntryCellRenderer extends JPanel implements ListCellRenderer<MemoryEntry> {
        private static final long serialVersionUID = 1L;
        private final JLabel idLabel = new JLabel();
        private final JLabel detailsPill = new JLabel("+details");
        private final JLabel summaryLabel = new JLabel();
        private final JLabel metaLabel = new JLabel();

        MemoryEntryCellRenderer() {
            super(new MigLayout("insets 6 10 6 10, fillx, wrap 1, gapy 2", "[grow, fill]"));
            setOpaque(true);
            idLabel.setIconTextGap(6);
            idLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold $monospaced.font");
            detailsPill.putClientProperty(FlatClientProperties.STYLE, "font: -2 bold; border: 1,6,1,6; arc: 10");
            detailsPill.setOpaque(true);
            summaryLabel.putClientProperty(FlatClientProperties.STYLE, "font: 0");
            metaLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");

            JPanel topRow = new JPanel(new MigLayout("insets 0, fillx", "[grow, fill][]", "[]"));
            topRow.setOpaque(false);
            topRow.add(idLabel, CONSTRAINTS_GROWX);
            topRow.add(detailsPill, "hidemode 3");
            add(topRow, CONSTRAINTS_GROWX);
            add(summaryLabel, CONSTRAINTS_GROWX);
            add(metaLabel, CONSTRAINTS_GROWX);
        }

        @Override
        public java.awt.Component getListCellRendererComponent(JList<? extends MemoryEntry> list, MemoryEntry value,
                int index, boolean isSelected, boolean cellHasFocus) {
            UiComponents.ListCellColors colors = UiComponents.resolveListCellColors(list, isSelected);
            setBackground(colors.background());
            setBorder(SwingHtmlUtils.createThemeMatteBorder(0, 0, 1, 0));

            if (value != null) {
                idLabel.setText(value.id());
                idLabel.setForeground(colors.foreground());
                idLabel.setIcon(FontIcon.of(Codicons.NOTE, 14, colors.iconColor()));

                boolean hasDetails = StringUtils.isNotBlank(value.details());
                detailsPill.setVisible(hasDetails);
                if (hasDetails) {
                    Color accent = SwingHtmlUtils.getAccentColor();
                    detailsPill.setForeground(accent);
                    detailsPill.setBackground(SwingHtmlUtils.getTintedBackground(accent, 0.16));
                }

                summaryLabel.setText(value.summary());
                summaryLabel.setForeground(colors.foreground());

                StringBuilder meta = new StringBuilder();
                if (!value.tags().isEmpty()) {
                    meta.append('[').append(MemoryService.formatTagsDisplay(value.tags())).append(']');
                }
                if (StringUtils.isNotBlank(value.updatedAt())) {
                    String formattedTs = PathUtils.formatTimestampDisplay(value.updatedAt(), TIMESTAMP_DISPLAY_LEN, "");
                    if (!formattedTs.isEmpty()) {
                        if (meta.length() > 0) {
                            meta.append("  •  ");
                        }
                        meta.append(formattedTs);
                    }
                }
                metaLabel.setText(meta.toString());
                metaLabel.setForeground(colors.muted());
                setToolTipText(value.id() + ": " + value.summary());
            }
            return this;
        }
    }
}
