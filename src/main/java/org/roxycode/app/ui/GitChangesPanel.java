package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
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
    private final JTextArea diffArea;

    public GitChangesPanel(GitService gitService) {
        this.gitService = gitService;
        setLayout(new MigLayout("fill, insets 10", "[grow]", "[][grow]"));

        JLabel header = new JLabel("Git Changes");
        header.putClientProperty(FlatClientProperties.STYLE, "font: h2");
        add(header, "wrap");

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setDividerLocation(150);

        statusArea = new JTextArea();
        statusArea.setEditable(false);
        statusArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane statusScroll = new JScrollPane(statusArea);
        statusScroll.setBorder(BorderFactory.createTitledBorder("Status"));
        splitPane.setTopComponent(statusScroll);

        diffArea = new JTextArea();
        diffArea.setEditable(false);
        diffArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane diffScroll = new JScrollPane(diffArea);
        diffScroll.setBorder(BorderFactory.createTitledBorder("Diff"));
        splitPane.setBottomComponent(diffScroll);

        add(splitPane, "grow");

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> refresh());
        add(refreshButton, "south");

        refresh();
    }

    public void refresh() {
        statusArea.setText(gitService.getStatus());
        diffArea.setText(gitService.getDiff());
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (statusArea != null) {
            statusArea.setBackground(UIManager.getColor("TextArea.background"));
            statusArea.setForeground(UIManager.getColor("TextArea.foreground"));
        }
        if (diffArea != null) {
            diffArea.setBackground(UIManager.getColor("TextArea.background"));
            diffArea.setForeground(UIManager.getColor("TextArea.foreground"));
        }
    }
}