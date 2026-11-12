package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
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
import org.roxycode.app.ai.services.MemoryService.NoteDetail;
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
    static final String PAGE_SUBTITLE = "Manage persistent Global and Project MEMORY.md cheat-sheets and topic notes injected into agent prompts.";
    static final String GLOBAL_TAB_TITLE = "Global Memory (~/.roxycode/memory)";
    static final String PROJECT_TAB_TITLE = "Project Memory (<projectDataDir>/memory)";
    static final String NO_PROJECT_MESSAGE = "No active project selected. Select a project folder in the top bar to manage Project Memory.";
    static final String PINNED_WARNING_TEXT = "Exceeds prompt limit (8,192 chars / 200 lines) — excess content is truncated in prompts";
    static final String PINNED_MEMORY_TOOLTIP_HTML = "<html><body style='width: 300px; padding: 4px;'>"
            + "<b>Pinned MEMORY.md Cheat-Sheet</b><br>"
            + "Automatically injected into Roxy's stable system prompt across all workflow phases (capped at <b>8,192 chars / 200 lines</b> per scope).<br><br>"
            + "• <b>Global Memory</b>: Personal coding style and cross-project preferences (<code>/rem-g &lt;note&gt;</code> in chat).<br>"
            + "• <b>Project Memory</b>: Project architecture rules and build/test gotchas (<code>/rem-p &lt;note&gt;</code> in chat)."
            + "</body></html>";
    static final String TOPIC_NOTES_TOOLTIP_HTML = "<html><body style='width: 300px; padding: 4px;'>"
            + "<b>Topic Notes (notes/*.md)</b><br>"
            + "Structured Markdown notes with YAML frontmatter (title, tags, updated timestamp).<br><br>"
            + "A compact catalog of note slugs, titles, and tags is listed in the system prompt so Roxy can read or search full notes on demand via <code>memoryService</code> without bloating every prompt."
            + "</body></html>";
    private static final int TIMESTAMP_DISPLAY_LEN = 16;

    private final transient MemoryService memoryService;
    private final transient ProjectService projectService;
    private final transient SettingsService settingsService;

    final JLabel titleLabel;
    final JLabel subtitleLabel;
    final JTabbedPane scopeTabbedPane;
    final MemoryScopeView globalScopeView;
    final MemoryScopeView projectScopeView;

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

        this.globalScopeView = new MemoryScopeView(true);
        this.projectScopeView = new MemoryScopeView(false);

        this.scopeTabbedPane = UiComponents.createUnderlinedTabbedPane(null);
        this.scopeTabbedPane.addTab(GLOBAL_TAB_TITLE, FontIcon.of(Codicons.GLOBE, 14), globalScopeView);
        this.scopeTabbedPane.addTab(PROJECT_TAB_TITLE, FontIcon.of(Codicons.FOLDER_OPENED, 14), projectScopeView);
        add(scopeTabbedPane, BorderLayout.CENTER);

        refresh();
    }

    public void refresh() {
        globalScopeView.refresh();
        projectScopeView.refresh();
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
            globalScopeView.updateTheme();
            projectScopeView.updateTheme();
        });
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (globalScopeView != null) {
            globalScopeView.updateTheme();
        }
        if (projectScopeView != null) {
            projectScopeView.updateTheme();
        }
    }

    record ScopeData(String pinnedMemory, List<NoteDetail> notes) {
    }

    final class MemoryScopeView extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final String CARD_CONTENT = "CONTENT";
        private static final String CARD_EMPTY = "EMPTY";
        private static final String GROWX = "growx";
        private static final String GROWX_WRAP = "growx, wrap";

        final boolean globalScope;
        private final CardLayout scopeCardLayout = new CardLayout();
        final JPanel emptyProjectPanel;
        final JLabel emptyProjectLabel;
        final JPanel pinnedCardPanel;
        final JPanel notesCardPanel;

        final JLabel pinnedHeaderLabel;
        final JLabel pinnedInfoIcon;
        final JLabel memoryCounterLabel = new JLabel();
        final JLabel memoryWarningLabel = new JLabel(PINNED_WARNING_TEXT);
        final RSyntaxTextArea memoryEditor = new RSyntaxTextArea();
        final JButton saveMemoryButton = new JButton("Save MEMORY.md");
        final JButton revertMemoryButton = new JButton("Revert");

        final JLabel notesHeaderLabel;
        final JLabel notesInfoIcon;
        final JTextField searchField = new JTextField();
        final JButton newNoteButton = new JButton("New Note");
        final DefaultListModel<NoteDetail> noteListModel = new DefaultListModel<>();
        final JList<NoteDetail> noteList = new JList<>(noteListModel);

        final JTextField noteTitleField = new JTextField();
        final JTextField noteSlugField = new JTextField();
        final JTextField noteTagsField = new JTextField();
        final RSyntaxTextArea noteBodyEditor = new RSyntaxTextArea();
        final JButton saveNoteButton = new JButton("Save Note");
        final JButton deleteNoteButton = new JButton("Delete Note");
        final UiComponents.InlineConfirmBar deleteConfirmBar = new UiComponents.InlineConfirmBar();
        final JLabel statusLabel = new JLabel("");
        private final Timer statusTimer;

        private String loadedMemoryContent = "";
        private final transient UiComponents.UiGuard memoryUpdateGuard = new UiComponents.UiGuard();
        private boolean memoryDirty;

        private List<NoteDetail> allNotes = List.of();
        private transient NoteDetail selectedNote;
        private boolean creatingNewNote = true;
        private boolean slugManuallyEdited;
        private final transient UiComponents.UiGuard noteFieldsUpdateGuard = new UiComponents.UiGuard();
        transient CompletableFuture<?> lastAsyncFuture = CompletableFuture.completedFuture(null);

        MemoryScopeView(boolean globalScope) {
            this.globalScope = globalScope;
            setLayout(scopeCardLayout);
            setOpaque(false);

            emptyProjectPanel = new JPanel(new MigLayout("fill, insets 24", "[grow, center]", "[grow, center]"));
            UiComponents.styleCardPanel(emptyProjectPanel);
            emptyProjectLabel = new JLabel(NO_PROJECT_MESSAGE, SwingConstants.CENTER);
            emptyProjectLabel.setIcon(FontIcon.of(Codicons.INFO, 18, SwingHtmlUtils.getMutedForegroundColor()));
            emptyProjectLabel.setIconTextGap(8);
            emptyProjectPanel.add(emptyProjectLabel);

            pinnedHeaderLabel = UiComponents.createSectionHeader("Pinned MEMORY.md Cheat-Sheet", Codicons.PIN,
                    "font: bold");
            pinnedInfoIcon = UiComponents.createInfoIcon(PINNED_MEMORY_TOOLTIP_HTML);
            notesHeaderLabel = UiComponents.createSectionHeader("Topic Notes (notes/*.md)", Codicons.BOOK,
                    "font: bold");
            notesInfoIcon = UiComponents.createInfoIcon(TOPIC_NOTES_TOOLTIP_HTML);

            pinnedCardPanel = buildPinnedMemoryCard();
            notesCardPanel = buildTopicNotesCard();

            JSplitPane verticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, pinnedCardPanel, notesCardPanel);
            verticalSplit.setDividerLocation(230);
            verticalSplit.setResizeWeight(0.35);
            verticalSplit.setBorder(null);
            verticalSplit.setOpaque(false);

            add(verticalSplit, CARD_CONTENT);
            add(emptyProjectPanel, CARD_EMPTY);

            statusTimer = UiComponents.createStatusTimer(statusLabel, 4000);

            setupListeners();
            updateTheme();
            updateMemoryStatsAndButtons();
            startNewNote();
        }

        private JPanel buildPinnedMemoryCard() {
            JPanel card = new JPanel(
                    new MigLayout("fill, insets 8 12 10 12, hidemode 3", "[grow, fill]", "[][][grow]"));
            UiComponents.styleCardPanel(card);

            JPanel toolbar = new JPanel(new MigLayout("fillx, insets 0", "[]6[]12[grow, fill][][]", "center"));
            toolbar.setOpaque(false);
            toolbar.add(pinnedHeaderLabel);
            toolbar.add(pinnedInfoIcon);
            memoryCounterLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            toolbar.add(memoryCounterLabel, GROWX);

            revertMemoryButton
                    .setIcon(FontIcon.of(Codicons.DISCARD, 14, UiComponents.resolveActionButtonIconColor(null)));
            revertMemoryButton.setEnabled(false);
            saveMemoryButton.putClientProperty(FlatClientProperties.STYLE, UiComponents.ACCENT_BUTTON_STYLE);
            saveMemoryButton.setIcon(FontIcon.of(Codicons.SAVE, 14,
                    UiComponents.resolveActionButtonIconColor(UiComponents.ACCENT_BUTTON_STYLE)));
            saveMemoryButton.setEnabled(false);
            toolbar.add(revertMemoryButton);
            toolbar.add(saveMemoryButton);
            card.add(toolbar, GROWX_WRAP);

            memoryWarningLabel.setVisible(false);
            card.add(memoryWarningLabel, "growx, wrap, hidemode 3");

            UiComponents.configureSyntaxTextArea(memoryEditor, SyntaxConstants.SYNTAX_STYLE_MARKDOWN, true, true);
            card.add(new RTextScrollPane(memoryEditor), "grow, push");
            return card;
        }

        private JPanel buildTopicNotesCard() {
            JPanel card = new JPanel(
                    new MigLayout("fill, insets 8 12 10 12, gapy 6", "[grow, fill]", "[][grow, fill]"));
            UiComponents.styleCardPanel(card);
            JPanel notesHeaderRow = new JPanel(new MigLayout("insets 0, gapx 6", "[][]", "center"));
            notesHeaderRow.setOpaque(false);
            notesHeaderRow.add(notesHeaderLabel);
            notesHeaderRow.add(notesInfoIcon);
            card.add(notesHeaderRow, GROWX_WRAP);
            JPanel leftPane = new JPanel(new BorderLayout(0, 6));
            leftPane.setOpaque(false);

            JPanel filterBar = new JPanel(new MigLayout("insets 0, fillx", "[grow, fill]6[]", "center"));
            filterBar.setOpaque(false);
            searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                    "Filter notes by title, slug, or tag...");
            searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON,
                    FontIcon.of(Codicons.SEARCH, 14, SwingHtmlUtils.getMutedForegroundColor()));
            searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
            newNoteButton.setIcon(FontIcon.of(Codicons.ADD, 14, UiComponents.resolveActionButtonIconColor(null)));
            filterBar.add(searchField, GROWX);
            filterBar.add(newNoteButton);
            leftPane.add(filterBar, BorderLayout.NORTH);

            noteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            noteList.setCellRenderer(new NoteCellRenderer());
            leftPane.add(new JScrollPane(noteList), BorderLayout.CENTER);

            JPanel rightPane = new JPanel(
                    new MigLayout("fill, insets 0 8 0 0", "[right][grow, fill][right][grow, fill]", "[][][grow][]"));
            rightPane.setOpaque(false);

            noteTitleField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                    "Note title (e.g. Swing Table Rules)");
            noteSlugField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "note-slug");
            noteTagsField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "ui, swing, html");

            rightPane.add(new JLabel("Title:"));
            rightPane.add(noteTitleField, "growx, wmin 100");
            rightPane.add(new JLabel("Slug:"));
            rightPane.add(noteSlugField, "growx, wmin 80, wrap");

            rightPane.add(new JLabel("Tags:"));
            rightPane.add(noteTagsField, "span 3, growx, wrap");

            UiComponents.configureSyntaxTextArea(noteBodyEditor, SyntaxConstants.SYNTAX_STYLE_MARKDOWN, true, true);
            rightPane.add(new RTextScrollPane(noteBodyEditor), "span 4, grow, push, wrap");

            JPanel noteButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            noteButtons.setOpaque(false);
            saveNoteButton.putClientProperty(FlatClientProperties.STYLE, UiComponents.ACCENT_BUTTON_STYLE);
            saveNoteButton.setIcon(FontIcon.of(Codicons.SAVE, 14,
                    UiComponents.resolveActionButtonIconColor(UiComponents.ACCENT_BUTTON_STYLE)));
            deleteNoteButton.putClientProperty(FlatClientProperties.STYLE, UiComponents.SUBTLE_RED_BUTTON_STYLE);
            deleteNoteButton.setIcon(FontIcon.of(Codicons.TRASH, 14,
                    UiComponents.resolveActionButtonIconColor(UiComponents.SUBTLE_RED_BUTTON_STYLE)));
            deleteNoteButton.setEnabled(false);
            noteButtons.add(saveNoteButton);
            noteButtons.add(deleteNoteButton);
            noteButtons.add(deleteConfirmBar);
            noteButtons.add(statusLabel);
            rightPane.add(noteButtons, "span 4, growx");

            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, rightPane);
            splitPane.setDividerLocation(290);
            splitPane.setResizeWeight(0.32);
            splitPane.setBorder(null);
            splitPane.setOpaque(false);
            card.add(splitPane, "grow, push");
            return card;
        }

        private void setupListeners() {
            SwingHtmlUtils.addChangeListener(memoryEditor, this::onPinnedMemoryEdited);
            saveMemoryButton.addActionListener(e -> savePinnedMemory());
            revertMemoryButton.addActionListener(e -> revertPinnedMemory());

            SwingHtmlUtils.addChangeListener(searchField, this::applyNoteFilter);
            newNoteButton.addActionListener(e -> startNewNote());
            noteList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    onNoteSelected(noteList.getSelectedValue());
                }
            });

            SwingHtmlUtils.addChangeListener(noteTitleField, this::onTitleFieldEdited);
            SwingHtmlUtils.addChangeListener(noteSlugField, this::onSlugFieldEdited);
            saveNoteButton.addActionListener(e -> saveCurrentNote());
            deleteNoteButton.addActionListener(e -> deleteCurrentNote());
        }

        boolean isScopeAvailable() {
            if (globalScope) {
                return true;
            }
            return projectService != null && projectService.hasActiveProject();
        }

        private void onPinnedMemoryEdited() {
            if (!memoryUpdateGuard.isActive()) {
                memoryDirty = !Objects.equals(memoryEditor.getText(), loadedMemoryContent);
            }
            updateMemoryStatsAndButtons();
        }

        void updateMemoryStatsAndButtons() {
            String text = StringUtils.defaultString(memoryEditor.getText());
            int chars = text.length();
            int lines = 0;
            if (!text.isEmpty()) {
                lines = memoryEditor.getLineCount();
            }
            long tokens = TokenUtils.estimateTokens(text);
            memoryCounterLabel.setText(String.format("%,d / %,d chars  •  %,d / %,d lines  •  ~%s tokens", chars,
                    MemoryService.MAX_PINNED_MEMORY_CHARS, lines, MemoryService.MAX_PINNED_MEMORY_LINES,
                    TokenUtils.formatTokenCount(tokens)));

            boolean overLimit = chars > MemoryService.MAX_PINNED_MEMORY_CHARS
                    || lines > MemoryService.MAX_PINNED_MEMORY_LINES;
            memoryWarningLabel.setVisible(overLimit);

            boolean available = isScopeAvailable();
            saveMemoryButton.setEnabled(available && memoryDirty);
            revertMemoryButton.setEnabled(available && memoryDirty);
        }

        private void onTitleFieldEdited() {
            if (noteFieldsUpdateGuard.isActive()) {
                return;
            }
            if (creatingNewNote && !slugManuallyEdited) {
                String rawTitle = noteTitleField.getText();
                String autoSlug = PathUtils.sanitizeSlug(rawTitle, MemoryService.MAX_SLUG_LENGTH, "");
                noteFieldsUpdateGuard.run(() -> noteSlugField.setText(autoSlug));
            }
        }

        private void onSlugFieldEdited() {
            if (!noteFieldsUpdateGuard.isActive() && creatingNewNote) {
                slugManuallyEdited = !StringUtils.isBlank(noteSlugField.getText());
            }
        }

        CompletableFuture<ScopeData> refresh() {
            deleteConfirmBar.cancel();
            boolean available = isScopeAvailable();
            if (!available) {
                scopeCardLayout.show(this, CARD_EMPTY);
                CompletableFuture<ScopeData> emptyFuture = CompletableFuture
                        .completedFuture(new ScopeData("", List.of()));
                lastAsyncFuture = emptyFuture;
                return emptyFuture;
            }
            scopeCardLayout.show(this, CARD_CONTENT);

            CompletableFuture<ScopeData> future = Edt.supplyAsync(this::loadScopeDataFromDisk, this::applyScopeData,
                    ex -> log.warn("Failed to load agent memory scope (global={}): {}", globalScope, ex.getMessage()));
            lastAsyncFuture = future;
            return future;
        }

        private ScopeData loadScopeDataFromDisk() {
            if (memoryService == null) {
                return new ScopeData("", List.of());
            }
            if (globalScope) {
                return new ScopeData(StringUtils.defaultString(memoryService.readGlobalMemory()),
                        Objects.requireNonNullElse(memoryService.listGlobalNoteDetails(), List.of()));
            }
            return new ScopeData(StringUtils.defaultString(memoryService.readProjectMemory()),
                    Objects.requireNonNullElse(memoryService.listProjectNoteDetails(), List.of()));
        }

        private void applyScopeData(ScopeData data) {
            loadedMemoryContent = StringUtils.defaultString(data.pinnedMemory());
            if (!memoryDirty) {
                memoryUpdateGuard.run(this::resetMemoryEditorToLoadedContent);
            } else {
                memoryDirty = !Objects.equals(memoryEditor.getText(), loadedMemoryContent);
            }
            updateMemoryStatsAndButtons();

            allNotes = List.copyOf(Objects.requireNonNullElse(data.notes(), List.of()));
            applyNoteFilter();
        }

        private void resetMemoryEditorToLoadedContent() {
            memoryEditor.setText(loadedMemoryContent);
            memoryEditor.setCaretPosition(0);
            memoryDirty = false;
        }

        private void applyNoteFilter() {
            String previousSlug = null;
            if (selectedNote != null) {
                previousSlug = selectedNote.slug();
            }
            String query = StringUtils.trimToEmpty(searchField.getText());
            List<NoteDetail> filtered = new ArrayList<>();
            for (NoteDetail note : allNotes) {
                if (matchesFilter(note, query)) {
                    filtered.add(note);
                }
            }
            UiComponents.replaceListItemsPreservingSelection(noteListModel, noteList, filtered, NoteDetail::slug,
                    previousSlug, !creatingNewNote);
        }

        private boolean matchesFilter(NoteDetail note, String query) {
            if (query.isEmpty()) {
                return true;
            }
            return StringUtils.containsIgnoreCase(note.title(), query)
                    || StringUtils.containsIgnoreCase(note.slug(), query)
                    || StringUtils.containsIgnoreCase(note.tags(), query)
                    || StringUtils.containsIgnoreCase(note.body(), query);
        }

        @SuppressWarnings("PMD.NullAssignment")
        void startNewNote() {
            selectedNote = null;
            creatingNewNote = true;
            slugManuallyEdited = false;
            noteList.clearSelection();
            noteFieldsUpdateGuard.run(this::clearNoteEditorFields);
            deleteNoteButton.setEnabled(false);
            statusLabel.setText("");
        }

        private void clearNoteEditorFields() {
            deleteConfirmBar.cancel();
            noteTitleField.setText("");
            noteSlugField.setText("");
            noteSlugField.setEditable(true);
            noteTagsField.setText("");
            noteBodyEditor.setText("");
            noteBodyEditor.setCaretPosition(0);
        }

        void onNoteSelected(@Nullable NoteDetail note) {
            deleteConfirmBar.cancel();
            if (note == null) {
                return;
            }
            selectedNote = note;
            creatingNewNote = false;
            slugManuallyEdited = true;
            noteFieldsUpdateGuard.run(() -> populateNoteEditorFields(note));
            deleteNoteButton.setEnabled(isScopeAvailable());
            statusLabel.setText("");
        }

        private void populateNoteEditorFields(NoteDetail note) {
            noteTitleField.setText(StringUtils.defaultString(note.title()));
            noteSlugField.setText(StringUtils.defaultString(note.slug()));
            noteSlugField.setEditable(false);
            noteTagsField.setText(StringUtils.defaultString(note.tags()));
            noteBodyEditor.setText(StringUtils.defaultString(note.body()));
            noteBodyEditor.setCaretPosition(0);
        }

        CompletableFuture<EditorResult> savePinnedMemory() {
            if (memoryService == null || !isScopeAvailable()) {
                return CompletableFuture.completedFuture(new EditorResult(false, "", "Memory service unavailable"));
            }
            String contentToSave = StringUtils.defaultString(memoryEditor.getText());
            saveMemoryButton.setEnabled(false);
            revertMemoryButton.setEnabled(false);

            CompletableFuture<EditorResult> future = Edt.supplyAsync(
                    () -> supplyInMemoryScope(() -> memoryService.updateGlobalMemory(contentToSave),
                            () -> memoryService.updateProjectMemory(contentToSave)),
                    result -> handlePinnedMemorySaved(result, contentToSave), this::handlePinnedMemorySaveError);
            lastAsyncFuture = future;
            return future;
        }

        private EditorResult supplyInMemoryScope(Supplier<EditorResult> globalAction,
                Supplier<EditorResult> projectAction) {
            return memoryEventRefreshGuard.supply(() -> {
                if (globalScope) {
                    return globalAction.get();
                }
                return projectAction.get();
            });
        }

        void showStatus(String message, boolean success) {
            UiComponents.showStatusMessage(statusLabel, statusTimer, message, success);
        }

        private void handlePinnedMemorySaved(@Nullable EditorResult result, String contentToSave) {
            if (result != null && result.success()) {
                loadedMemoryContent = contentToSave;
                memoryDirty = false;
                showStatus("Saved MEMORY.md", true);
            } else {
                showStatus(resolveErrorMessage(result, "Failed to save MEMORY.md"), false);
            }
            updateMemoryStatsAndButtons();
        }

        private void handlePinnedMemorySaveError(Throwable ex) {
            showStatus("Failed to save MEMORY.md: " + ExceptionUtils.getRootCauseMessage(ex), false);
            updateMemoryStatsAndButtons();
        }

        void revertPinnedMemory() {
            memoryUpdateGuard.run(this::resetMemoryEditorToLoadedContent);
            updateMemoryStatsAndButtons();
            showStatus("Reverted MEMORY.md changes", true);
        }

        CompletableFuture<EditorResult> saveCurrentNote() {
            if (memoryService == null || !isScopeAvailable()) {
                return CompletableFuture.completedFuture(new EditorResult(false, "", "Memory service unavailable"));
            }
            String title = StringUtils.trimToEmpty(noteTitleField.getText());
            String rawSlug = StringUtils.trimToEmpty(noteSlugField.getText());
            if (rawSlug.isEmpty()) {
                rawSlug = title;
            }
            String sanitizedSlug;
            try {
                sanitizedSlug = MemoryService.sanitizeSlug(rawSlug);
            } catch (IllegalArgumentException ex) {
                showStatus(ex.getMessage(), false);
                return CompletableFuture.completedFuture(new EditorResult(false, "", ex.getMessage()));
            }
            String resolvedTitle = StringUtils.defaultIfBlank(title, sanitizedSlug);
            String tags = StringUtils.trimToEmpty(noteTagsField.getText());
            String body = StringUtils.defaultString(noteBodyEditor.getText());

            CompletableFuture<EditorResult> future = Edt
                    .supplyAsync(
                            () -> supplyInMemoryScope(
                                    () -> memoryService.saveGlobalNote(sanitizedSlug, resolvedTitle, tags, body),
                                    () -> memoryService.saveProjectNote(sanitizedSlug, resolvedTitle, tags, body)),
                            result -> handleNoteSaved(result, sanitizedSlug, resolvedTitle, tags, body),
                            ex -> showStatus("Failed to save note: " + ExceptionUtils.getRootCauseMessage(ex), false))
                    .thenCompose(result -> refresh().thenApply(v -> result));
            lastAsyncFuture = future;
            return future;
        }

        private void handleNoteSaved(@Nullable EditorResult result, String sanitizedSlug, String resolvedTitle,
                String tags, String body) {
            if (result != null && result.success()) {
                creatingNewNote = false;
                selectedNote = new NoteDetail(sanitizedSlug, resolvedTitle, tags, "", body, "");
                showStatus("Saved note: " + sanitizedSlug, true);
            } else {
                showStatus(resolveErrorMessage(result, "Failed to save note"), false);
            }
        }

        void deleteCurrentNote() {
            if (memoryService == null || selectedNote == null || !isScopeAvailable()) {
                return;
            }
            String slug = selectedNote.slug();
            deleteConfirmBar.prompt("Delete '" + slug + "'?", "Confirm Delete", true,
                    () -> performDeleteCurrentNote(slug));
        }

        CompletableFuture<EditorResult> performDeleteCurrentNote(String slug) {
            CompletableFuture<EditorResult> future = Edt
                    .supplyAsync(
                            () -> supplyInMemoryScope(() -> memoryService.deleteGlobalNote(slug),
                                    () -> memoryService.deleteProjectNote(slug)),
                            result -> handleNoteDeleted(result, slug),
                            ex -> showStatus("Failed to delete note: " + ExceptionUtils.getRootCauseMessage(ex), false))
                    .thenCompose(result -> refresh().thenApply(v -> result));
            lastAsyncFuture = future;
            return future;
        }

        private void handleNoteDeleted(@Nullable EditorResult result, String slug) {
            if (result != null && result.success()) {
                startNewNote();
                showStatus("Deleted note: " + slug, true);
            } else {
                showStatus(resolveErrorMessage(result, "Failed to delete note"), false);
            }
        }

        private static String resolveErrorMessage(@Nullable EditorResult result, String fallback) {
            if (result != null && StringUtils.isNotBlank(result.errorHint())) {
                return result.errorHint();
            }
            return fallback;
        }

        boolean isMemoryWarningVisible() {
            return memoryWarningLabel.isVisible();
        }

        boolean isMemoryDirty() {
            return memoryDirty;
        }

        void updateTheme() {
            if (pinnedCardPanel != null) {
                UiComponents.styleCardPanel(pinnedCardPanel);
            }
            if (notesCardPanel != null) {
                UiComponents.styleCardPanel(notesCardPanel);
            }
            if (emptyProjectPanel != null) {
                UiComponents.styleCardPanel(emptyProjectPanel);
            }
            Color warnColor = SwingHtmlUtils.getWarningColor();
            Color mutedColor = SwingHtmlUtils.getMutedForegroundColor();
            memoryWarningLabel.setForeground(warnColor);
            memoryWarningLabel.setIcon(FontIcon.of(Codicons.WARNING, 14, warnColor));
            memoryCounterLabel.setForeground(mutedColor);
            statusLabel.setForeground(mutedColor);
            searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON,
                    FontIcon.of(Codicons.SEARCH, 14, mutedColor));
            UiComponents.updateInfoIconTheme(pinnedInfoIcon);
            UiComponents.updateInfoIconTheme(notesInfoIcon);
            UiComponents.refreshActionButtonIcons(this);
            SwingHtmlUtils.applyRSyntaxTheme(memoryEditor, settingsService);
            SwingHtmlUtils.applyRSyntaxTheme(noteBodyEditor, settingsService);
            noteList.repaint();
        }
    }

    private static final class NoteCellRenderer extends JPanel implements ListCellRenderer<NoteDetail> {
        private static final long serialVersionUID = 1L;
        private final JLabel titleLabel = new JLabel();
        private final JLabel metaLabel = new JLabel();

        NoteCellRenderer() {
            super(new MigLayout("insets 6 10 6 10, fillx, wrap 1, gapy 2", "[grow, fill]"));
            setOpaque(true);
            titleLabel.setIconTextGap(6);
            titleLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold 0");
            metaLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            add(titleLabel, "growx");
            add(metaLabel, "growx");
        }

        @Override
        public java.awt.Component getListCellRendererComponent(JList<? extends NoteDetail> list, NoteDetail value,
                int index, boolean isSelected, boolean cellHasFocus) {
            UiComponents.ListCellColors colors = UiComponents.resolveListCellColors(list, isSelected);
            setBackground(colors.background());
            setBorder(SwingHtmlUtils.createThemeMatteBorder(0, 0, 1, 0));

            if (value != null) {
                titleLabel.setText(value.title());
                titleLabel.setForeground(colors.foreground());
                titleLabel.setIcon(FontIcon.of(Codicons.NOTE, 14, colors.iconColor()));

                StringBuilder meta = new StringBuilder(value.slug());
                if (StringUtils.isNotBlank(value.tags())) {
                    meta.append("  •  [").append(value.tags()).append(']');
                }
                if (StringUtils.isNotBlank(value.updated())) {
                    String formattedTs = PathUtils.formatTimestampDisplay(value.updated(), TIMESTAMP_DISPLAY_LEN, "");
                    if (!formattedTs.isEmpty()) {
                        meta.append("  •  ").append(formattedTs);
                    }
                }
                metaLabel.setText(meta.toString());
                metaLabel.setForeground(colors.muted());
                setToolTipText(value.title() + " (notes/" + value.slug() + ".md)");
            }
            return this;
        }
    }
}
