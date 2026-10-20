package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Font;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Panel showing current git status. */
public final class GitChangesPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(GitChangesPanel.class);
    static final String WORKING_TREE_CLEAN = "Working tree clean";

    private final transient GitService gitService;
    private final JTextArea statusArea;

    public GitChangesPanel(GitService gitService) {
        this.gitService = gitService;
        setLayout(new MigLayout("fill, insets 10", "[grow]", "[][grow]"));

        JPanel headerPanel = new JPanel(new MigLayout("insets 0", "[grow][]", "center"));
        headerPanel.setOpaque(false);

        JLabel header = new JLabel("Git Status");
        header.putClientProperty(FlatClientProperties.STYLE, "font: h2");
        headerPanel.add(header);

        JButton refreshButton = new JButton(FontIcon.of(Codicons.REFRESH, 16));
        refreshButton.setToolTipText("Refresh Git Status");
        refreshButton.putClientProperty(
                FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        refreshButton.addActionListener(e -> refresh());
        headerPanel.add(refreshButton);

        add(headerPanel, "wrap, growx");

        statusArea = new JTextArea();
        statusArea.setEditable(false);
        statusArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        SwingHtmlUtils.installTextContextMenu(statusArea);

        JScrollPane statusScroll = new JScrollPane(statusArea);
        add(statusScroll, "grow");

        refresh();
    }

    public void refresh() {
        refreshAsync();
    }

    SwingWorker<String, Void> refreshAsync() {
        SwingWorker<String, Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected String doInBackground() {
                        String status = gitService.getStatus();
                        if (status == null || status.isBlank()) {
                            return WORKING_TREE_CLEAN;
                        }
                        return status.trim();
                    }

                    @Override
                    protected void done() {
                        try {
                            String result = get();
                            statusArea.setText(result != null ? result : WORKING_TREE_CLEAN);
                            statusArea.setCaretPosition(0);
                        } catch (Exception e) {
                            log.error("Failed to refresh git status", e);
                        }
                    }
                };
        worker.execute();
        return worker;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (statusArea != null) {
            statusArea.setBackground(UIManager.getColor("TextArea.background"));
            statusArea.setForeground(UIManager.getColor("TextArea.foreground"));
        }
    }

    JTextArea getStatusArea() {
        return statusArea;
    }
}
