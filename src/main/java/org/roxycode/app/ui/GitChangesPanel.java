package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Panel showing current git status and diff. */
public final class GitChangesPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(GitChangesPanel.class);
    static final String ALL_CHANGES = "All Changes";
    private static final String CARD_LIST = "LIST";
    private static final String CARD_TEXT = "TEXT";

    private final transient GitService gitService;
    private final JTextArea statusArea;
    private final DefaultListModel<String> fileListModel;
    private final JList<String> fileList;
    private final JPanel statusCardPanel;
    private final CardLayout statusCardLayout;
    private final JTextPane diffArea;
    private String currentDiffHtml = "";
    private String selectedFilePath = "";
    private boolean updatingSelection = false;

    public GitChangesPanel(GitService gitService) {
        this.gitService = gitService;
        setLayout(new MigLayout("fill, insets 10", "[grow]", "[][grow]"));

        JPanel headerPanel = new JPanel(new MigLayout("insets 0", "[grow][]", "center"));
        headerPanel.setOpaque(false);

        JLabel header = new JLabel("Git Changes");
        header.putClientProperty(FlatClientProperties.STYLE, "font: h2");
        headerPanel.add(header);

        JButton refreshButton = new JButton(FontIcon.of(Codicons.REFRESH, 16));
        refreshButton.setToolTipText("Refresh Git Status");
        refreshButton.putClientProperty(
                FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        refreshButton.addActionListener(e -> refresh());
        headerPanel.add(refreshButton);

        add(headerPanel, "wrap, growx");

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setDividerLocation(150);

        statusArea = new JTextArea();
        statusArea.setEditable(false);
        statusArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        SwingHtmlUtils.installTextContextMenu(statusArea);
        JScrollPane statusScroll = new JScrollPane(statusArea);

        fileListModel = new DefaultListModel<>();
        fileList = new JList<>(fileListModel);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        fileList.addListSelectionListener(
                e -> {
                    if (!e.getValueIsAdjusting() && !updatingSelection) {
                        onFileSelectionChanged();
                    }
                });
        JScrollPane listScroll = new JScrollPane(fileList);

        statusCardLayout = new CardLayout();
        statusCardPanel = new JPanel(statusCardLayout);
        statusCardPanel.setBorder(BorderFactory.createTitledBorder("Status"));
        statusCardPanel.add(listScroll, CARD_LIST);
        statusCardPanel.add(statusScroll, CARD_TEXT);
        splitPane.setTopComponent(statusCardPanel);

        diffArea = new JTextPane();
        diffArea.setEditable(false);
        diffArea.setContentType("text/html");
        diffArea.putClientProperty("JEditorPane.honorDisplayProperties", Boolean.TRUE);
        SwingHtmlUtils.applyTheme(diffArea, 5);
        SwingHtmlUtils.installTextContextMenu(diffArea);
        JScrollPane diffScroll = new JScrollPane(diffArea);
        diffScroll.setBorder(BorderFactory.createTitledBorder("Diff"));
        splitPane.setBottomComponent(diffScroll);

        add(splitPane, "grow");

        refresh();
    }

    public void refresh() {
        refreshAsync();
    }

    SwingWorker<GitChangesResult, Void> refreshAsync() {
        final String targetFile = selectedFilePath;
        SwingWorker<GitChangesResult, Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected GitChangesResult doInBackground() {
                        String status = gitService.getStatus();
                        List<String> entries = parseStatusEntries(status);
                        String preservedPath = "";
                        if (!targetFile.isBlank()) {
                            for (String entry : entries) {
                                if (targetFile.equals(extractFilePathFromStatusLine(entry))) {
                                    preservedPath = targetFile;
                                    break;
                                }
                            }
                        }
                        String diff =
                                !preservedPath.isEmpty()
                                        ? gitService.getDiff(preservedPath)
                                        : gitService.getDiff();
                        return new GitChangesResult(status, formatDiff(diff));
                    }

                    @Override
                    protected void done() {
                        try {
                            GitChangesResult result = get();
                            boolean selectionUnchanged = targetFile.equals(selectedFilePath);
                            String rawStatus = result.status() != null ? result.status() : "";
                            statusArea.setText(rawStatus);
                            statusArea.setCaretPosition(0);

                            List<String> entries = parseStatusEntries(rawStatus);
                            updatingSelection = true;
                            try {
                                fileListModel.clear();
                                if (!entries.isEmpty()) {
                                    fileListModel.addElement(ALL_CHANGES);
                                    int restoreIndex = 0;
                                    for (int i = 0; i < entries.size(); i++) {
                                        String entry = entries.get(i);
                                        fileListModel.addElement(entry);
                                        if (!selectedFilePath.isEmpty()
                                                && selectedFilePath.equals(
                                                        extractFilePathFromStatusLine(entry))) {
                                            restoreIndex = i + 1;
                                        }
                                    }
                                    if (restoreIndex == 0) {
                                        selectedFilePath = "";
                                    }
                                    fileList.setSelectedIndex(restoreIndex);
                                    statusCardLayout.show(statusCardPanel, CARD_LIST);
                                } else {
                                    selectedFilePath = "";
                                    statusCardLayout.show(statusCardPanel, CARD_TEXT);
                                }
                            } finally {
                                updatingSelection = false;
                            }

                            if (selectionUnchanged) {
                                currentDiffHtml =
                                        result.formattedDiff() != null
                                                ? result.formattedDiff()
                                                : "";
                                diffArea.setText(currentDiffHtml);
                                diffArea.setCaretPosition(0);
                            }
                        } catch (Exception e) {
                            log.error("Failed to refresh git changes", e);
                        }
                    }
                };
        worker.execute();
        return worker;
    }

    private void onFileSelectionChanged() {
        String selected = fileList.getSelectedValue();
        if (selected == null || ALL_CHANGES.equals(selected)) {
            selectedFilePath = "";
            loadDiffAsync("");
        } else {
            String path = extractFilePathFromStatusLine(selected);
            selectedFilePath = path;
            loadDiffAsync(path);
        }
    }

    SwingWorker<String, Void> loadDiffAsync(String filePath) {
        final String targetPath = filePath != null ? filePath : "";
        SwingWorker<String, Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected String doInBackground() {
                        String diff =
                                targetPath.isBlank()
                                        ? gitService.getDiff()
                                        : gitService.getDiff(targetPath);
                        return formatDiff(diff);
                    }

                    @Override
                    protected void done() {
                        try {
                            String formatted = get();
                            if (targetPath.equals(selectedFilePath)) {
                                currentDiffHtml = formatted != null ? formatted : "";
                                diffArea.setText(currentDiffHtml);
                                diffArea.setCaretPosition(0);
                            }
                        } catch (Exception e) {
                            log.error("Failed to load file diff", e);
                        }
                    }
                };
        worker.execute();
        return worker;
    }

    static List<String> parseStatusEntries(String porcelainStatus) {
        if (porcelainStatus == null || porcelainStatus.isBlank()) {
            return Collections.emptyList();
        }
        String trimmedStatus = porcelainStatus.trim();
        if (trimmedStatus.startsWith("No active project")
                || trimmedStatus.startsWith("Not a git repository")
                || trimmedStatus.startsWith("Error")) {
            return Collections.emptyList();
        }
        String[] lines = porcelainStatus.split("\\R");
        List<String> entries = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return entries;
    }

    static String extractFilePathFromStatusLine(String statusLine) {
        if (statusLine == null || statusLine.isBlank()) {
            return "";
        }
        String trimmed = statusLine.trim();
        int arrowIndex = trimmed.indexOf(" -> ");
        if (arrowIndex >= 0) {
            return trimmed.substring(arrowIndex + 4).trim();
        }
        int firstSpace = 0;
        while (firstSpace < trimmed.length()
                && !Character.isWhitespace(trimmed.charAt(firstSpace))) {
            firstSpace++;
        }
        if (firstSpace < trimmed.length()) {
            return trimmed.substring(firstSpace).trim();
        }
        return trimmed;
    }

    public static String formatDiff(String diff) {
        if (diff == null || diff.isEmpty()) {
            return "<pre style='font-family: monospace; font-size: 11px;'></pre>";
        }
        String[] lines = diff.split("\\R");
        StringBuilder sb = new StringBuilder();
        sb.append("<pre style='font-family: monospace; font-size: 11px;'>");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String escaped = SwingHtmlUtils.escapeHtml(line);
            if (line.startsWith("+") && !line.startsWith("+++")) {
                sb.append("<span style='color: #28a745;'>").append(escaped).append("</span>");
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                sb.append("<span style='color: #d73a49;'>").append(escaped).append("</span>");
            } else {
                sb.append(escaped);
            }
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        sb.append("</pre>");
        return sb.toString();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (statusArea != null) {
            statusArea.setBackground(UIManager.getColor("TextArea.background"));
            statusArea.setForeground(UIManager.getColor("TextArea.foreground"));
        }
        if (fileList != null) {
            fileList.setBackground(UIManager.getColor("List.background"));
            fileList.setForeground(UIManager.getColor("List.foreground"));
        }
        if (diffArea != null) {
            SwingHtmlUtils.applyTheme(diffArea, 5);
            if (currentDiffHtml != null && !currentDiffHtml.isEmpty()) {
                diffArea.setText(currentDiffHtml);
            }
        }
    }

    JTextArea getStatusArea() {
        return statusArea;
    }

    JList<String> getFileList() {
        return fileList;
    }

    DefaultListModel<String> getFileListModel() {
        return fileListModel;
    }

    JTextPane getDiffArea() {
        return diffArea;
    }

    record GitChangesResult(String status, String formattedDiff) {}
}
