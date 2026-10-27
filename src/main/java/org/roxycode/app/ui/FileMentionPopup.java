package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.util.Edt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-performance RipGrep-backed {@code @} file mention autocomplete popup for {@link ChatPanel}.
 *
 * <p>Precomputes lowercase filename and path tokens off the EDT, ranks candidates in
 * sub-millisecond time across 4 match tiers, and positions a non-focusable popup menu above the
 * caret line.
 */
public final class FileMentionPopup {
    private static final Logger log = LoggerFactory.getLogger(FileMentionPopup.class);

    static final int MAX_RESULTS = 15;
    static final int MAX_VISIBLE_ROWS = 8;
    static final int POPUP_VERTICAL_GAP = 4;
    static final int MIN_POPUP_WIDTH = 320;
    static final int MAX_POPUP_WIDTH = 520;
    static final long STALE_CACHE_TTL_MS = 10_000L;

    static final int TIER_FILENAME_PREFIX = 1;
    static final int TIER_CAMEL_OR_BOUNDARY = 2;
    static final int TIER_FILENAME_SUBSTRING = 3;
    static final int TIER_PATH_SUBSTRING = 4;
    static final int TIER_NO_MATCH = 0;
    private static final char MENTION_TRIGGER_CHAR = '@';

    private static final Set<String> CODE_EXTENSIONS =
            Set.of(
                    "java",
                    "kt",
                    "kts",
                    "groovy",
                    "scala",
                    "py",
                    "js",
                    "ts",
                    "tsx",
                    "jsx",
                    "c",
                    "cpp",
                    "h",
                    "hpp",
                    "rs",
                    "go",
                    "rb",
                    "php",
                    "cs",
                    "swift",
                    "sql",
                    "sh",
                    "bash",
                    "zsh",
                    "xml",
                    "json",
                    "yaml",
                    "yml",
                    "toml",
                    "properties",
                    "html",
                    "css",
                    "scss",
                    "md");

    private static final Comparator<FileCandidate> DEFAULT_CANDIDATE_COMPARATOR =
            Comparator.comparing(FileCandidate::fileNameLower)
                    .thenComparingInt(c -> c.fileName().length())
                    .thenComparingInt(c -> c.relativePath().length())
                    .thenComparing(FileCandidate::pathLower)
                    .thenComparing(FileCandidate::relativePath);

    private static final Comparator<FileCandidate> TIER_TIE_BREAKER =
            Comparator.comparingInt((FileCandidate c) -> c.fileName().length())
                    .thenComparingInt(c -> c.relativePath().length())
                    .thenComparing(FileCandidate::fileNameLower)
                    .thenComparing(FileCandidate::pathLower)
                    .thenComparing(FileCandidate::relativePath);

    /**
     * Precomputed file entry for zero-allocation keystroke filtering.
     *
     * @param relativePath normalized path relative to the project root
     * @param fileName file name portion
     * @param parentDir parent directory path (empty string if in project root)
     * @param fileNameLower precomputed lowercase file name
     * @param pathLower precomputed lowercase relative path
     */
    public record FileCandidate(
            String relativePath,
            String fileName,
            String parentDir,
            String fileNameLower,
            String pathLower) {

        public static FileCandidate fromPath(String rawPath) {
            if (rawPath == null) {
                return new FileCandidate("", "", "", "", "");
            }
            String normalized = rawPath.trim().replace((char) 92, '/');
            while (normalized.startsWith("./")) {
                normalized = normalized.substring(2);
            }
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            int lastSlash = normalized.lastIndexOf('/');
            String fileName = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
            String parentDir = lastSlash > 0 ? normalized.substring(0, lastSlash) : "";
            return new FileCandidate(
                    normalized,
                    fileName,
                    parentDir,
                    fileName.toLowerCase(Locale.ROOT),
                    normalized.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Active {@code @<query>} mention token extracted from the input document.
     *
     * @param startOffset 0-based offset of the {@code @} character
     * @param endOffset 0-based caret offset at the end of the query
     * @param query text between {@code @} and {@code endOffset}
     */
    public record MentionToken(int startOffset, int endOffset, String query) {}

    private record ScoredCandidate(FileCandidate candidate, int tier) {}

    private final JTextArea inputArea;
    private final JScrollPane inputScrollPane;
    private final JPopupMenu popupMenu;
    private final DefaultListModel<FileCandidate> listModel;
    private final JList<FileCandidate> candidateList;
    private final JScrollPane popupScrollPane;

    private volatile GrepService grepService;
    private volatile Executor backgroundExecutor = ForkJoinPool.commonPool();
    private volatile List<FileCandidate> cachedCandidates = List.of();
    private volatile long lastCacheRefreshTimeMs = 0L;
    private volatile CompletableFuture<List<FileCandidate>> inFlightRefresh;

    private boolean popupActive = false;
    private boolean updatingInputText = false;
    private MentionToken activeToken;
    private Point lastPopupLocation = new Point(0, 0);

    public FileMentionPopup(JTextArea inputArea, JScrollPane inputScrollPane) {
        this.inputArea = inputArea;
        this.inputScrollPane = inputScrollPane;

        this.listModel = new DefaultListModel<>();
        this.candidateList = new JList<>(listModel);
        this.candidateList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.candidateList.setFocusable(false);
        this.candidateList.setCellRenderer(new FileCandidateCellRenderer());
        this.candidateList.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (SwingUtilities.isLeftMouseButton(e)) {
                            int idx = candidateList.locationToIndex(e.getPoint());
                            if (idx >= 0 && idx < listModel.size()) {
                                candidateList.setSelectedIndex(idx);
                                acceptSelected();
                            }
                        }
                    }
                });

        this.popupScrollPane = new JScrollPane(candidateList);
        this.popupScrollPane.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        this.popupScrollPane.setBorder(BorderFactory.createEmptyBorder());
        this.popupScrollPane.setFocusable(false);

        this.popupMenu = new JPopupMenu();
        this.popupMenu.setFocusable(false);
        this.popupMenu.setBorder(SwingHtmlUtils.createThemeMatteBorder(1, 1, 1, 1));
        this.popupMenu.add(popupScrollPane);

        installInputListeners();
    }

    private void installInputListeners() {
        inputArea
                .getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            @Override
                            public void insertUpdate(DocumentEvent e) {
                                onInputChanged();
                            }

                            @Override
                            public void removeUpdate(DocumentEvent e) {
                                onInputChanged();
                            }

                            @Override
                            public void changedUpdate(DocumentEvent e) {
                                onInputChanged();
                            }
                        });

        inputArea.addCaretListener(e -> onInputChanged());

        inputArea.addFocusListener(
                new FocusAdapter() {
                    @Override
                    public void focusLost(FocusEvent e) {
                        hidePopup();
                    }
                });
    }

    private void onInputChanged() {
        if (updatingInputText) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            evaluateCurrentInput();
        } else {
            SwingUtilities.invokeLater(this::evaluateCurrentInput);
        }
    }

    /**
     * Extracts an active {@code @<query>} mention token ending at {@code caretOffset}, or returns
     * {@code null} if no valid mention token is active.
     */
    public static MentionToken extractMentionToken(String text, int caretOffset) {
        if (text == null || caretOffset < 1 || caretOffset > text.length()) {
            return null;
        }
        for (int i = caretOffset - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                return null;
            }
            if (c == MENTION_TRIGGER_CHAR) {
                if (i == 0 || Character.isWhitespace(text.charAt(i - 1))) {
                    return new MentionToken(i, caretOffset, text.substring(i + 1, caretOffset));
                }
                return null;
            }
        }
        return null;
    }

    /**
     * Ranks and filters precomputed file candidates against {@code query}, returning at most {@link
     * #MAX_RESULTS} matches.
     */
    public static List<FileCandidate> rankCandidates(List<FileCandidate> candidates, String query) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String trimmedQuery = query == null ? "" : query.trim();
        if (trimmedQuery.isEmpty()) {
            int limit = Math.min(MAX_RESULTS, candidates.size());
            List<FileCandidate> sorted = new ArrayList<>(candidates);
            sorted.sort(DEFAULT_CANDIDATE_COMPARATOR);
            return List.copyOf(sorted.subList(0, limit));
        }

        String queryLower = trimmedQuery.toLowerCase(Locale.ROOT);
        List<ScoredCandidate> scored = new ArrayList<>();
        for (FileCandidate candidate : candidates) {
            int tier = computeMatchTier(candidate, queryLower);
            if (tier != TIER_NO_MATCH) {
                scored.add(new ScoredCandidate(candidate, tier));
            }
        }
        if (scored.isEmpty()) {
            return List.of();
        }

        scored.sort(
                (a, b) -> {
                    int tierCmp = Integer.compare(a.tier(), b.tier());
                    if (tierCmp != 0) {
                        return tierCmp;
                    }
                    return TIER_TIE_BREAKER.compare(a.candidate(), b.candidate());
                });

        int limit = Math.min(MAX_RESULTS, scored.size());
        List<FileCandidate> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            result.add(scored.get(i).candidate());
        }
        return List.copyOf(result);
    }

    static int computeMatchTier(FileCandidate candidate, String queryLower) {
        if (queryLower.isEmpty()) {
            return TIER_FILENAME_PREFIX;
        }
        String fileNameLower = candidate.fileNameLower();
        if (fileNameLower.startsWith(queryLower)) {
            return TIER_FILENAME_PREFIX;
        }
        if (matchesCamelCaseOrWordBoundary(candidate.fileName(), fileNameLower, queryLower)) {
            return TIER_CAMEL_OR_BOUNDARY;
        }
        if (fileNameLower.contains(queryLower)) {
            return TIER_FILENAME_SUBSTRING;
        }
        if (candidate.pathLower().contains(queryLower)) {
            return TIER_PATH_SUBSTRING;
        }
        return TIER_NO_MATCH;
    }

    static boolean matchesCamelCaseOrWordBoundary(
            String fileName, String fileNameLower, String queryLower) {
        if (queryLower.isEmpty() || fileName.isEmpty()) {
            return false;
        }
        int fLen = fileName.length();
        char firstQ = queryLower.charAt(0);
        for (int i = 0; i < fLen; i++) {
            if (isWordBoundary(fileName, i)
                    && fileNameLower.charAt(i) == firstQ
                    && matchBoundarySubsequence(fileName, fileNameLower, i, queryLower, 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchBoundarySubsequence(
            String fileName, String fileNameLower, int fIdx, String queryLower, int qIdx) {
        if (qIdx + 1 == queryLower.length()) {
            return true;
        }
        char nextQ = queryLower.charAt(qIdx + 1);
        int fLen = fileName.length();
        if (fIdx + 1 < fLen
                && fileNameLower.charAt(fIdx + 1) == nextQ
                && matchBoundarySubsequence(
                        fileName, fileNameLower, fIdx + 1, queryLower, qIdx + 1)) {
            return true;
        }
        for (int nextB = fIdx + 1; nextB < fLen; nextB++) {
            if (isWordBoundary(fileName, nextB)
                    && fileNameLower.charAt(nextB) == nextQ
                    && matchBoundarySubsequence(
                            fileName, fileNameLower, nextB, queryLower, qIdx + 1)) {
                return true;
            }
        }
        return false;
    }

    static boolean isWordBoundary(String fileName, int index) {
        if (index < 0 || index >= fileName.length()) {
            return false;
        }
        if (index == 0) {
            return true;
        }
        char curr = fileName.charAt(index);
        char prev = fileName.charAt(index - 1);
        if (!Character.isLetterOrDigit(prev) && Character.isLetterOrDigit(curr)) {
            return true;
        }
        if (Character.isUpperCase(curr)) {
            if (!Character.isUpperCase(prev)) {
                return true;
            }
            return index + 1 < fileName.length()
                    && Character.isLowerCase(fileName.charAt(index + 1));
        }
        return false;
    }

    public void setGrepService(GrepService grepService) {
        this.grepService = grepService;
        if (grepService != null) {
            refreshIndexAsync(true);
        } else {
            cachedCandidates = List.of();
            lastCacheRefreshTimeMs = 0L;
        }
    }

    GrepService getGrepService() {
        return grepService;
    }

    void setBackgroundExecutor(Executor backgroundExecutor) {
        this.backgroundExecutor =
                backgroundExecutor != null ? backgroundExecutor : ForkJoinPool.commonPool();
    }

    public void invalidateCache() {
        lastCacheRefreshTimeMs = 0L;
        refreshIndexAsync(true);
    }

    public CompletableFuture<List<FileCandidate>> refreshIndexAsync() {
        return refreshIndexAsync(true);
    }

    CompletableFuture<List<FileCandidate>> refreshIndexAsync(boolean force) {
        GrepService service = this.grepService;
        if (service == null) {
            return CompletableFuture.completedFuture(cachedCandidates);
        }
        CompletableFuture<List<FileCandidate>> existing = this.inFlightRefresh;
        if (!force && existing != null && !existing.isDone()) {
            return existing;
        }

        CompletableFuture<List<FileCandidate>> future =
                CompletableFuture.supplyAsync(
                                () -> {
                                    List<String> rawFiles = service.listFiles();
                                    if (rawFiles == null || rawFiles.isEmpty()) {
                                        return List.<FileCandidate>of();
                                    }
                                    List<FileCandidate> built = new ArrayList<>(rawFiles.size());
                                    for (String raw : rawFiles) {
                                        if (raw != null && !raw.isBlank()) {
                                            built.add(FileCandidate.fromPath(raw));
                                        }
                                    }
                                    built.sort(DEFAULT_CANDIDATE_COMPARATOR);
                                    return List.copyOf(built);
                                },
                                backgroundExecutor)
                        .exceptionally(
                                ex -> {
                                    log.debug("Failed to refresh file mention index", ex);
                                    return cachedCandidates;
                                });

        this.inFlightRefresh = future;
        future.thenAccept(
                candidates -> {
                    this.cachedCandidates = candidates;
                    this.lastCacheRefreshTimeMs = System.currentTimeMillis();
                    Edt.run(
                            () -> {
                                if (activeToken != null) {
                                    evaluateCurrentInput();
                                }
                            });
                });
        return future;
    }

    void evaluateCurrentInput() {
        if (updatingInputText || !inputArea.isEnabled() || !inputArea.isEditable()) {
            hidePopup();
            return;
        }
        String text = inputArea.getText();
        int caretOffset = inputArea.getCaretPosition();
        MentionToken token = extractMentionToken(text, caretOffset);
        if (token == null) {
            hidePopup();
            return;
        }

        this.activeToken = token;

        long now = System.currentTimeMillis();
        if (cachedCandidates.isEmpty() || now - lastCacheRefreshTimeMs > STALE_CACHE_TTL_MS) {
            refreshIndexAsync(false);
        }

        List<FileCandidate> matches = rankCandidates(cachedCandidates, token.query());
        if (matches.isEmpty()) {
            popupActive = false;
            listModel.clear();
            if (popupMenu.isVisible()) {
                popupMenu.setVisible(false);
            }
            return;
        }

        FileCandidate previousSelection = candidateList.getSelectedValue();
        listModel.clear();
        int selectedIndex = 0;
        for (int i = 0; i < matches.size(); i++) {
            FileCandidate c = matches.get(i);
            listModel.addElement(c);
            if (previousSelection != null
                    && previousSelection.relativePath().equals(c.relativePath())) {
                selectedIndex = i;
            }
        }
        candidateList.setSelectedIndex(selectedIndex);
        candidateList.ensureIndexIsVisible(selectedIndex);

        int visibleRows = Math.min(MAX_VISIBLE_ROWS, Math.max(1, listModel.size()));
        candidateList.setVisibleRowCount(visibleRows);
        popupActive = true;

        updatePopupPosition(token.startOffset());
        SwingUtilities.invokeLater(
                () -> {
                    if (popupActive && activeToken != null) {
                        updatePopupPosition(activeToken.startOffset());
                    }
                });
    }

    void updatePopupPosition(int atTokenStartOffset) {
        if (!popupActive || listModel.isEmpty()) {
            return;
        }
        int visibleRows = Math.min(MAX_VISIBLE_ROWS, Math.max(1, listModel.size()));
        candidateList.setVisibleRowCount(visibleRows);

        popupScrollPane.setPreferredSize(null);
        Dimension listPref = popupScrollPane.getPreferredSize();
        int availableWidth = resolveAvailableWidth();
        int targetWidth = Math.max(MIN_POPUP_WIDTH, Math.min(MAX_POPUP_WIDTH, listPref.width + 24));
        if (availableWidth > 0) {
            targetWidth = Math.min(targetWidth, availableWidth);
        }
        popupScrollPane.setPreferredSize(new Dimension(targetWidth, listPref.height));
        popupMenu.pack();

        Dimension popupSize = popupMenu.getPreferredSize();
        Point location =
                computePopupLocation(atTokenStartOffset, popupSize.width, popupSize.height);
        this.lastPopupLocation = location;

        if (inputArea.isShowing()) {
            popupMenu.show(inputArea, location.x, location.y);
        } else if (inputScrollPane != null && inputScrollPane.isShowing()) {
            popupMenu.show(inputScrollPane, 0, -popupSize.height - POPUP_VERTICAL_GAP);
        }
    }

    private int resolveAvailableWidth() {
        Rectangle visibleRect = inputArea.getVisibleRect();
        if (visibleRect != null && visibleRect.width > 0) {
            return visibleRect.width;
        }
        if (inputArea.getWidth() > 0) {
            return inputArea.getWidth();
        }
        if (inputScrollPane != null && inputScrollPane.getWidth() > 0) {
            return inputScrollPane.getWidth();
        }
        return 0;
    }

    Point computePopupLocation(int atTokenStartOffset, int popupWidth, int popupHeight) {
        int fallbackY = -popupHeight - POPUP_VERTICAL_GAP;
        try {
            int docLen = inputArea.getDocument().getLength();
            if (atTokenStartOffset >= 0 && atTokenStartOffset <= docLen) {
                Rectangle2D rect = inputArea.modelToView2D(atTokenStartOffset);
                if (rect != null
                        && (rect.getWidth() > 0
                                || rect.getHeight() > 0
                                || inputArea.getWidth() > 0)) {
                    int x = (int) Math.round(rect.getX());
                    int y = (int) Math.round(rect.getY()) - popupHeight - POPUP_VERTICAL_GAP;
                    Rectangle visibleRect = inputArea.getVisibleRect();
                    int availWidth = resolveAvailableWidth();
                    if (availWidth > 0) {
                        int minX =
                                (visibleRect != null && visibleRect.width > 0) ? visibleRect.x : 0;
                        int rightEdge =
                                (visibleRect != null && visibleRect.width > 0)
                                        ? visibleRect.x + visibleRect.width
                                        : availWidth;
                        int maxX = Math.max(minX, rightEdge - popupWidth);
                        x = Math.max(minX, Math.min(x, maxX));
                    } else {
                        x = Math.max(0, x);
                    }
                    return new Point(x, y);
                }
            }
        } catch (BadLocationException ex) {
            log.debug("Failed to compute modelToView2D for @ mention offset", ex);
        }
        return new Point(0, fallbackY);
    }

    public boolean isPopupVisible() {
        return popupActive && !listModel.isEmpty();
    }

    @SuppressWarnings("PMD.NullAssignment")
    public void hidePopup() {
        popupActive = false;
        activeToken = null;
        if (popupMenu.isVisible()) {
            popupMenu.setVisible(false);
        }
    }

    public void selectNext() {
        int size = listModel.size();
        if (!isPopupVisible() || size == 0) {
            return;
        }
        int next = (candidateList.getSelectedIndex() + 1) % size;
        candidateList.setSelectedIndex(next);
        candidateList.ensureIndexIsVisible(next);
    }

    public void selectPrevious() {
        int size = listModel.size();
        if (!isPopupVisible() || size == 0) {
            return;
        }
        int current = candidateList.getSelectedIndex();
        int prev = (current <= 0 ? size : current) - 1;
        candidateList.setSelectedIndex(prev);
        candidateList.ensureIndexIsVisible(prev);
    }

    @SuppressWarnings("PMD.UnusedAssignment")
    public boolean acceptSelected() {
        if (!isPopupVisible()) {
            return false;
        }
        FileCandidate selected = candidateList.getSelectedValue();
        MentionToken token = this.activeToken;
        if (selected == null || token == null) {
            hidePopup();
            return false;
        }

        int docLen = inputArea.getDocument().getLength();
        int start = Math.max(0, Math.min(token.startOffset(), docLen));
        int end = Math.max(start, Math.min(token.endOffset(), docLen));
        String replacement = "@" + selected.relativePath() + " ";

        updatingInputText = true;
        try {
            inputArea.replaceRange(replacement, start, end);
            inputArea.setCaretPosition(start + replacement.length());
        } finally {
            updatingInputText = false;
        }

        hidePopup();
        if (inputArea.isShowing()) {
            inputArea.requestFocusInWindow();
        }
        return true;
    }

    public void updateTheme() {
        if (popupMenu != null) {
            popupMenu.setBorder(SwingHtmlUtils.createThemeMatteBorder(1, 1, 1, 1));
            SwingUtilities.updateComponentTreeUI(popupMenu);
        }
    }

    DefaultListModel<FileCandidate> getListModel() {
        return listModel;
    }

    JList<FileCandidate> getCandidateList() {
        return candidateList;
    }

    JPopupMenu getPopupMenu() {
        return popupMenu;
    }

    Point getLastPopupLocation() {
        return lastPopupLocation;
    }

    List<FileCandidate> getCachedCandidates() {
        return cachedCandidates;
    }

    static Codicons resolveFileIcon(String fileNameLower) {
        int dot = fileNameLower.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < fileNameLower.length()) {
            String ext = fileNameLower.substring(dot + 1);
            if (CODE_EXTENSIONS.contains(ext)) {
                return Codicons.FILE_CODE;
            }
        }
        return Codicons.FILE;
    }

    private static final class FileCandidateCellRenderer extends JPanel
            implements ListCellRenderer<FileCandidate> {
        private static final long serialVersionUID = 1L;

        private final JLabel nameLabel = new JLabel();
        private final JLabel dirLabel = new JLabel();

        FileCandidateCellRenderer() {
            super(new MigLayout("insets 4 8 4 8, fillx, gapx 8", "[][grow, fill]"));
            setOpaque(true);
            nameLabel.setIconTextGap(6);
            nameLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold 0");
            dirLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            dirLabel.setHorizontalAlignment(JLabel.RIGHT);

            add(nameLabel);
            add(dirLabel, "growx");
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends FileCandidate> list,
                FileCandidate value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            Color bg =
                    isSelected
                            ? SwingHtmlUtils.getUiColor(
                                    list.getSelectionBackground(), "List.selectionBackground")
                            : SwingHtmlUtils.getUiColor(list.getBackground(), "List.background");
            Color fg =
                    isSelected
                            ? SwingHtmlUtils.getUiColor(
                                    list.getSelectionForeground(), "List.selectionForeground")
                            : SwingHtmlUtils.getUiColor(list.getForeground(), "Label.foreground");
            Color muted = isSelected ? fg : SwingHtmlUtils.getMutedForegroundColor();
            Color iconColor = isSelected ? fg : SwingHtmlUtils.getAccentColor();

            setBackground(bg);

            if (value != null) {
                nameLabel.setText(value.fileName());
                nameLabel.setForeground(fg);
                nameLabel.setIcon(
                        FontIcon.of(resolveFileIcon(value.fileNameLower()), 14, iconColor));

                dirLabel.setText(value.parentDir());
                dirLabel.setForeground(muted);
                setToolTipText(value.relativePath());
            } else {
                nameLabel.setText("");
                nameLabel.setIcon(null);
                dirLabel.setText("");
                setToolTipText(null);
            }
            return this;
        }
    }
}
