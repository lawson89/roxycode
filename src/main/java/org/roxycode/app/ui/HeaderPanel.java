package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Header panel containing application controls and project selection. */
@Component
@Lazy
public final class HeaderPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(HeaderPanel.class);
    private static final String LAYOUT_CENTER = "center";
    private final transient ProjectService projectService;
    private final transient GitService gitService;

    private final JLabel projectLabel;
    private final JLabel branchLabel;
    private final JLabel modelLabel;

    public HeaderPanel(
            ProjectService projectService, GitService gitService, SettingsService settingsService) {
        this.projectService = projectService;
        this.gitService = gitService;

        // 2-column layout: Left (Project), Right (Utils)
        setLayout(new MigLayout("insets 2 15 2 15, fillx", "[left]push[right]", LAYOUT_CENTER));

        putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
        updateBorder();

        // --- LEFT SECTION: Project Info ---
        JPanel leftPanel = new JPanel(new MigLayout("insets 0", "[]5[]12[]", LAYOUT_CENTER));
        leftPanel.setOpaque(false);

        projectLabel = new JLabel(projectService.getProjectName());
        projectLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +1");
        leftPanel.add(projectLabel);

        JButton openButton =
                createIconButton(Codicons.FOLDER_OPENED, "Open Project", e -> chooseProject());
        leftPanel.add(openButton);

        branchLabel = new JLabel();
        branchLabel.putClientProperty(
                FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        branchLabel.setVisible(false);
        leftPanel.add(branchLabel);
        updateBranchLabel();

        add(leftPanel, "left");

        // --- RIGHT SECTION: Model & Utils ---
        JPanel rightPanel = new JPanel(new MigLayout("insets 0", "[]", LAYOUT_CENTER));
        rightPanel.setOpaque(false);

        modelLabel = new JLabel();
        modelLabel.putClientProperty(
                FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        updateModelDisplay(settingsService.getSettings().getGeminiModel());

        rightPanel.add(modelLabel);
        add(rightPanel, "right");

        if (projectService.getCurrentProjectRoot() != null) {
            projectLabel.setToolTipText(
                    projectService.getCurrentProjectRoot().toAbsolutePath().toString());
        } else {
            projectLabel.setToolTipText("No project selected");
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        updateBorder();
    }

    private void updateBorder() {
        setBorder(
                BorderFactory.createMatteBorder(
                        0, 0, 1, 0, UIManager.getColor("Component.borderColor")));
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        Path newRoot = event.projectRoot();
        projectLabel.setText(projectService.getProjectName());
        if (newRoot != null) {
            projectLabel.setToolTipText(newRoot.toAbsolutePath().toString());
        } else {
            projectLabel.setToolTipText("No project selected");
        }
        updateBranchLabel();
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        updateModelDisplay(event.settings().getGeminiModel());
    }

    private JButton createIconButton(
            Codicons icon, String tooltip, java.awt.event.ActionListener listener) {
        JButton button = new JButton(FontIcon.of(icon, 16, UIManager.getColor("Label.foreground")));
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(listener);

        button.addMouseListener(
                new java.awt.event.MouseAdapter() {
                    @Override
                    public void mouseEntered(java.awt.event.MouseEvent e) {
                        button.setContentAreaFilled(true);
                        button.setBackground(UIManager.getColor("Button.hoverBackground"));
                    }

                    @Override
                    public void mouseExited(java.awt.event.MouseEvent e) {
                        button.setContentAreaFilled(false);
                    }
                });

        return button;
    }

    SwingWorker<String, Void> updateBranchLabel() {
        SwingWorker<String, Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected String doInBackground() {
                        return gitService.getCurrentBranch();
                    }

                    @Override
                    protected void done() {
                        try {
                            String branch = get();
                            if (branch != null
                                    && !branch.isEmpty()
                                    && !branch.startsWith("Error")
                                    && !"Not a git repository".equals(branch)) {
                                branchLabel.setIcon(
                                        FontIcon.of(
                                                Codicons.SOURCE_CONTROL,
                                                14,
                                                UIManager.getColor("Label.disabledForeground")));
                                branchLabel.setText(branch);
                                branchLabel.setVisible(true);
                            } else {
                                branchLabel.setIcon(null);
                                branchLabel.setText("");
                                branchLabel.setVisible(false);
                            }
                        } catch (Exception e) {
                            log.error("Failed to fetch git branch", e);
                            branchLabel.setIcon(null);
                            branchLabel.setText("");
                            branchLabel.setVisible(false);
                        }
                    }
                };
        worker.execute();
        return worker;
    }

    JLabel getBranchLabel() {
        return branchLabel;
    }

    private void updateModelDisplay(String modelName) {
        modelLabel.setIcon(
                FontIcon.of(Codicons.HUBOT, 16, UIManager.getColor("Label.disabledForeground")));
        modelLabel.setText(modelName != null ? modelName : "No model selected");
    }

    private void chooseProject() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (projectService.getCurrentProjectRoot() != null) {
            chooser.setCurrentDirectory(projectService.getCurrentProjectRoot().toFile());
        }

        int returnVal = chooser.showOpenDialog(this);
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            projectService.setCurrentProjectRoot(file.toPath());
        }
    }
}
