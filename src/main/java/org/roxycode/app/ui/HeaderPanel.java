package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * Header panel containing application controls and project selection.
 */
public class HeaderPanel extends JPanel {
    private final ProjectService projectService;
    private final GitService gitService;
    private final JLabel projectLabel;
    private final JLabel branchLabel;
    private final JLabel modelLabel;

    public HeaderPanel(ProjectService projectService, GitService gitService, SettingsService settingsService) {
        this.projectService = projectService;
        this.gitService = gitService;
        
        setLayout(new MigLayout("insets 30 30 20 30, fillx", "[]push[][]", "center"));
        
        putClientProperty(FlatClientProperties.STYLE, "background: $Panel.background");
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")));

        // Project Info
        JPanel projectInfoPanel = new JPanel(new MigLayout("insets 0", "[]10[]", "[]2[]"));
        projectInfoPanel.setOpaque(false);
        
        FontIcon projectIcon = FontIcon.of(Codicons.FOLDER, 20);
        projectInfoPanel.add(new JLabel(projectIcon), "top");

        projectLabel = new JLabel(projectService.getProjectName());
        projectLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +2");
        projectInfoPanel.add(projectLabel, "split 2");
        
        branchLabel = new JLabel();
        branchLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        updateBranchLabel();
        projectInfoPanel.add(branchLabel, "gapleft 10, wrap");
        
        JButton openButton = new JButton("Open Project");
        openButton.setFocusable(false);
        openButton.addActionListener(e -> {
            chooseProject();
        });
        projectInfoPanel.add(openButton, "skip 1");

        add(projectInfoPanel);

        // Active Model display
        modelLabel = new JLabel();
        modelLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        updateModelDisplay(settingsService.getSettings().getGeminiModel());
        add(modelLabel, "gapright 20");
        add(new JLabel("🔔"));

        projectService.addProjectListener(newRoot -> {
            projectLabel.setText(projectService.getProjectName());
            projectLabel.setToolTipText(newRoot.toAbsolutePath().toString());
            updateBranchLabel();
        });
        
        projectLabel.setToolTipText(projectService.getCurrentProjectRoot().toAbsolutePath().toString());

        settingsService.addSettingsListener(settings -> {
            updateModelDisplay(settings.getGeminiModel());
        });
    }

    private void updateBranchLabel() {
        String branch = gitService.getCurrentBranch();
        if (branch != null && !branch.isEmpty() && !branch.startsWith("Error") && !branch.equals("Not a git repository")) {
            branchLabel.setIcon(FontIcon.of(Codicons.SOURCE_CONTROL, 14, UIManager.getColor("Label.disabledForeground")));
            branchLabel.setText(branch);
            branchLabel.setVisible(true);
        } else {
            branchLabel.setIcon(null);
            branchLabel.setText("");
            branchLabel.setVisible(false);
        }
    }

    private void updateModelDisplay(String modelName) {
        modelLabel.setIcon(FontIcon.of(Codicons.HUBOT, 16, UIManager.getColor("Label.disabledForeground")));
        modelLabel.setText(modelName != null ? modelName : "No model selected");
    }

    private void chooseProject() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setCurrentDirectory(projectService.getCurrentProjectRoot().toFile());
        
        int returnVal = chooser.showOpenDialog(this);
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            projectService.setCurrentProjectRoot(file.toPath());
        }
    }
}