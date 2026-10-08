package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;

/**
 * Panel showing current git status and diff.
 */
public class GitChangesPanel extends JPanel {
    private static final Logger log = LoggerFactory.getLogger(GitChangesPanel.class);
    private final GitService gitService;
    private final JTextArea statusArea;
    private final JTextPane diffArea;
    private String currentDiffHtml = "";

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
        refreshButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
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
        statusScroll.setBorder(BorderFactory.createTitledBorder("Status"));
        splitPane.setTopComponent(statusScroll);

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
        SwingWorker<GitChangesResult, Void> worker = new SwingWorker<>() {
            @Override
            protected GitChangesResult doInBackground() {
                String status = gitService.getStatus();
                String diff = gitService.getDiff();
                return new GitChangesResult(status, formatDiff(diff));
            }

            @Override
            protected void done() {
                try {
                    GitChangesResult result = get();
                    statusArea.setText(result.status() != null ? result.status() : "");
                    currentDiffHtml = result.formattedDiff() != null ? result.formattedDiff() : "";
                    diffArea.setText(currentDiffHtml);
                    statusArea.setCaretPosition(0);
                    diffArea.setCaretPosition(0);
                } catch (Exception e) {
                    log.error("Failed to refresh git changes", e);
                }
            }
        };
        worker.execute();
        return worker;
    }

    public static String formatDiff(String diff) {
        if (diff == null || diff.isEmpty()) {
            return "<pre style=\"font-family: monospace; font-size: 11px;\"></pre>";
        }
        String[] lines = diff.split("\n");
        StringBuilder sb = new StringBuilder();
        sb.append("<pre style=\"font-family: monospace; font-size: 11px;\">");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String escaped = SwingHtmlUtils.escapeHtml(line);
            if (line.startsWith("+")) {
                sb.append("<span style=\"color: #28a745;\">").append(escaped).append("</span>");
            } else if (line.startsWith("-")) {
                sb.append("<span style=\"color: #d73a49;\">").append(escaped).append("</span>");
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

    JTextPane getDiffArea() {
        return diffArea;
    }

    record GitChangesResult(String status, String formattedDiff) {}
}
