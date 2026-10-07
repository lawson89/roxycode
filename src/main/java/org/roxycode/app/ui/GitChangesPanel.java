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
    private final JTextArea diffArea;

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

        refresh();
    }

    public void refresh() {
        statusArea.setText(gitService.getStatus());
        diffArea.setText(gitService.getDiff());
        // Scroll to top
        statusArea.setCaretPosition(0);
        diffArea.setCaretPosition(0);
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