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
import javax.swing.text.BadLocationException;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FileMentionPopup {
    private static final Logger log = LoggerFactory.getLogger(FileMentionPopup.class);

    static final int MAX_RESULTS = 15;
    static final int MAX_VISIBLE_ROWS = 8;
    static final int POPUP_VERTICAL_GAP = 4;
    static final int MIN_POPUP_WIDTH = 320;
    static final int MAX_POPUP_WIDTH = 520;

    static final int TIER_FILENAME_PREFIX = 1;
    static final int TIER_FILENAME_SUBSTRING = 2;
    static final int TIER_PATH_SUBSTRING = 3;
    static final int TIER_NO_MATCH = 0;
    private static final char MENTION_TRIGGER_CHAR = '@';

    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java,kt,kts,groovy,scala,py,js,ts,tsx,jsx,c,cpp,h,hpp,rs,go,rb,php,cs,swift,sql,sh,bash,zsh,xml,json,yaml,yml,toml,properties,html,css,scss,md"
                    .split(","));

    public record FileCandidate(String relativePath, String fileName, String parentDir, String fileNameLower,
            String pathLower) {

        public static FileCandidate fromPath(String rawPath) {
            String normalized = StringUtils
                    .stripStart(PathUtils.normalizeRelativePath(StringUtils.trimToEmpty(rawPath)), "/");
            String fileName = PathUtils.getFileName(normalized);
            String parentDir = StringUtils.defaultString(FilenameUtils.getPathNoEndSeparator(normalized));
            return new FileCandidate(normalized, fileName, parentDir, fileName.toLowerCase(Locale.ROOT),
                    normalized.toLowerCase(Locale.ROOT));
        }
    }

    public record MentionToken(int startOffset, int endOffset, String query) {
    }

    private final JTextArea inputArea;
    private final JScrollPane inputScrollPane;
    private final JPopupMenu popupMenu;
    private final DefaultListModel<FileCandidate> listModel;
    private final JList<FileCandidate> candidateList;
    private final JScrollPane popupScrollPane;

    private volatile GrepService grepService;
    private volatile Executor backgroundExecutor = ForkJoinPool.commonPool();
    private volatile List<FileCandidate> cachedCandidates = List.of();

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
        this.candidateList.addMouseListener(new MouseAdapter() {
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
        this.popupScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        this.popupScrollPane.setBorder(BorderFactory.createEmptyBorder());
        this.popupScrollPane.setFocusable(false);

        this.popupMenu = new JPopupMenu();
        this.popupMenu.setFocusable(false);
        this.popupMenu.setBorder(SwingHtmlUtils.createThemeMatteBorder(1, 1, 1, 1));
        this.popupMenu.add(popupScrollPane);

        installInputListeners();
    }

    private void installInputListeners() {
        SwingHtmlUtils.addChangeListener(inputArea, this::onInputChanged);
        inputArea.addCaretListener(e -> onInputChanged());
        inputArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                hidePopup();
            }
        });
    }

    private void onInputChanged() {
        if (!updatingInputText) {
            Edt.run(this::evaluateCurrentInput);
        }
    }

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

    private static Comparator<FileCandidate> candidateComparator(String queryLower) {
        return Comparator.comparingInt((FileCandidate c) -> computeMatchTier(c, queryLower)).thenComparingInt(c -> {
            if (queryLower.isEmpty()) {
                return 0;
            }
            return c.fileName().length();
        }).thenComparing(FileCandidate::fileNameLower).thenComparingInt(c -> c.fileName().length())
                .thenComparingInt(c -> c.relativePath().length()).thenComparing(FileCandidate::pathLower)
                .thenComparing(FileCandidate::relativePath);
    }

    public static List<FileCandidate> rankCandidates(List<FileCandidate> candidates, String query) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String queryLower = StringUtils.trimToEmpty(query).toLowerCase(Locale.ROOT);
        List<FileCandidate> sorted = new ArrayList<>();
        for (FileCandidate candidate : candidates) {
            if (computeMatchTier(candidate, queryLower) != TIER_NO_MATCH) {
                sorted.add(candidate);
            }
        }
        if (sorted.isEmpty()) {
            return List.of();
        }
        sorted.sort(candidateComparator(queryLower));
        return List.copyOf(sorted.subList(0, Math.min(MAX_RESULTS, sorted.size())));
    }

    static int computeMatchTier(FileCandidate candidate, String queryLower) {
        String fileNameLower = candidate.fileNameLower();
        if (queryLower.isEmpty() || fileNameLower.startsWith(queryLower)) {
            return TIER_FILENAME_PREFIX;
        }
        if (fileNameLower.contains(queryLower)) {
            return TIER_FILENAME_SUBSTRING;
        }
        if (candidate.pathLower().contains(queryLower)) {
            return TIER_PATH_SUBSTRING;
        }
        return TIER_NO_MATCH;
    }

    public synchronized void setGrepService(GrepService grepService) {
        this.grepService = grepService;
        refreshIndexAsync();
    }

    GrepService getGrepService() {
        return grepService;
    }

    void setBackgroundExecutor(Executor backgroundExecutor) {
        if (backgroundExecutor != null) {
            this.backgroundExecutor = backgroundExecutor;
        } else {
            this.backgroundExecutor = ForkJoinPool.commonPool();
        }
    }

    public void invalidateCache() {
        cachedCandidates = List.of();
        refreshIndexAsync();
    }

    private List<FileCandidate> performFileIndexing() {
        GrepService service = this.grepService;
        if (service == null) {
            return List.of();
        }
        try {
            List<String> rawFiles = service.listFiles();
            if (rawFiles == null || rawFiles.isEmpty()) {
                return List.of();
            }
            return rawFiles.stream().filter(StringUtils::isNotBlank).map(FileCandidate::fromPath)
                    .sorted(candidateComparator("")).toList();
        } catch (Exception e) {
            log.debug("Failed to perform file indexing", e);
            return List.of();
        }
    }

    public CompletableFuture<List<FileCandidate>> refreshIndexAsync() {
        if (grepService == null) {
            cachedCandidates = List.of();
            return CompletableFuture.completedFuture(List.of());
        }
        CompletableFuture<List<FileCandidate>> future = CompletableFuture.supplyAsync(() -> {
            List<FileCandidate> indexed = performFileIndexing();
            cachedCandidates = indexed;
            return indexed;
        }, backgroundExecutor);
        if (!SwingUtilities.isEventDispatchThread()) {
            future.join();
        }
        return future;
    }

    void evaluateCurrentInput() {
        if (updatingInputText || !inputArea.isEnabled() || !inputArea.isEditable()) {
            hidePopup();
            return;
        }
        MentionToken token = extractMentionToken(inputArea.getText(), inputArea.getCaretPosition());
        if (token == null) {
            hidePopup();
            return;
        }
        this.activeToken = token;

        if (cachedCandidates.isEmpty() && grepService != null) {
            refreshIndexAsync();
        }

        List<FileCandidate> matches = rankCandidates(cachedCandidates, token.query());
        if (matches.isEmpty()) {
            popupActive = false;
            listModel.clear();
            popupMenu.setVisible(false);
            return;
        }

        updateCandidateListModel(matches);
        popupActive = true;

        updatePopupPosition(token.startOffset());
        SwingUtilities.invokeLater(() -> {
            if (popupActive && activeToken != null) {
                updatePopupPosition(activeToken.startOffset());
            }
        });
    }

    private void updateCandidateListModel(List<FileCandidate> matches) {
        FileCandidate previousSelection = candidateList.getSelectedValue();
        listModel.clear();
        int selectedIndex = 0;
        for (int i = 0; i < matches.size(); i++) {
            FileCandidate c = matches.get(i);
            listModel.addElement(c);
            if (previousSelection != null && previousSelection.relativePath().equals(c.relativePath())) {
                selectedIndex = i;
            }
        }
        candidateList.setSelectedIndex(selectedIndex);
        candidateList.ensureIndexIsVisible(selectedIndex);
    }

    void updatePopupPosition(int atTokenStartOffset) {
        if (!popupActive || listModel.isEmpty()) {
            return;
        }
        candidateList.setVisibleRowCount(Math.min(MAX_VISIBLE_ROWS, Math.max(1, listModel.size())));
        popupScrollPane.setPreferredSize(null);
        Dimension listPref = popupScrollPane.getPreferredSize();
        int availableWidth = resolveAvailableBounds().width;
        int targetWidth = Math.clamp(listPref.width + 24, MIN_POPUP_WIDTH, MAX_POPUP_WIDTH);
        if (availableWidth > 0) {
            targetWidth = Math.min(targetWidth, availableWidth);
        }
        popupScrollPane.setPreferredSize(new Dimension(targetWidth, listPref.height));
        popupMenu.pack();

        Dimension popupSize = popupMenu.getPreferredSize();
        Point location = computePopupLocation(atTokenStartOffset, popupSize.width, popupSize.height);
        this.lastPopupLocation = location;

        if (inputArea.isShowing()) {
            popupMenu.show(inputArea, location.x, location.y);
        } else if (inputScrollPane != null && inputScrollPane.isShowing()) {
            popupMenu.show(inputScrollPane, 0, -popupSize.height - POPUP_VERTICAL_GAP);
        }
    }

    private Rectangle resolveAvailableBounds() {
        Rectangle visibleRect = inputArea.getVisibleRect();
        if (visibleRect != null && visibleRect.width > 0) {
            return visibleRect;
        }
        int width = inputArea.getWidth();
        if (width <= 0 && inputScrollPane != null) {
            width = Math.max(0, inputScrollPane.getWidth());
        }
        return new Rectangle(0, 0, Math.max(0, width), 0);
    }

    Point computePopupLocation(int atTokenStartOffset, int popupWidth, int popupHeight) {
        int fallbackY = -popupHeight - POPUP_VERTICAL_GAP;
        try {
            int docLen = inputArea.getDocument().getLength();
            if (atTokenStartOffset >= 0 && atTokenStartOffset <= docLen) {
                Rectangle2D rect = inputArea.modelToView2D(atTokenStartOffset);
                if (rect != null && (rect.getWidth() > 0 || rect.getHeight() > 0 || inputArea.getWidth() > 0)) {
                    int x = (int) Math.round(rect.getX());
                    int y = (int) Math.round(rect.getY()) - popupHeight - POPUP_VERTICAL_GAP;
                    Rectangle bounds = resolveAvailableBounds();
                    if (bounds.width > 0) {
                        x = Math.clamp(x, bounds.x, Math.max(bounds.x, bounds.x + bounds.width - popupWidth));
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
        moveSelection(1);
    }

    public void selectPrevious() {
        moveSelection(-1);
    }

    private void moveSelection(int delta) {
        int size = listModel.size();
        if (!isPopupVisible() || size == 0) {
            return;
        }
        int current = candidateList.getSelectedIndex();
        int next;
        if (delta > 0) {
            next = (current + 1) % size;
        } else if (current <= 0) {
            next = size - 1;
        } else {
            next = current - 1;
        }
        candidateList.setSelectedIndex(next);
        candidateList.ensureIndexIsVisible(next);
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
        String ext = PathUtils.getLowerExtension(fileNameLower, true);
        if (!ext.isEmpty() && CODE_EXTENSIONS.contains(ext)) {
            return Codicons.FILE_CODE;
        }
        return Codicons.FILE;
    }

    private static final class FileCandidateCellRenderer extends JPanel implements ListCellRenderer<FileCandidate> {
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
        public Component getListCellRendererComponent(JList<? extends FileCandidate> list, FileCandidate value,
                int index, boolean isSelected, boolean cellHasFocus) {
            Color bg;
            if (isSelected) {
                bg = SwingHtmlUtils.getUiColor(list.getSelectionBackground(), "List.selectionBackground");
            } else {
                bg = SwingHtmlUtils.getUiColor(list.getBackground(), "List.background");
            }
            Color fg;
            if (isSelected) {
                fg = SwingHtmlUtils.getUiColor(list.getSelectionForeground(), "List.selectionForeground");
            } else {
                fg = SwingHtmlUtils.getUiColor(list.getForeground(), "Label.foreground");
            }
            setBackground(bg);

            if (value != null) {
                nameLabel.setText(value.fileName());
                nameLabel.setForeground(fg);
                Color iconColor;
                if (isSelected) {
                    iconColor = fg;
                } else {
                    iconColor = SwingHtmlUtils.getAccentColor();
                }
                nameLabel.setIcon(FontIcon.of(resolveFileIcon(value.fileNameLower()), 14, iconColor));
                dirLabel.setText(value.parentDir());
                Color dirFg;
                if (isSelected) {
                    dirFg = fg;
                } else {
                    dirFg = SwingHtmlUtils.getMutedForegroundColor();
                }
                dirLabel.setForeground(dirFg);
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
