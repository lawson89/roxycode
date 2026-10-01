package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.service.ProjectService;
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

    public HeaderPanel(ProjectService projectService, GitService gitService) {
        this.projectService = projectService;
        this.gitService = gitService;
        
        setLayout(new MigLayout("insets 20 20 10 20, fillx", "[][push][]", "center"));
        
        putClientProperty(FlatClientProperties.STYLE, "background: $Panel.background");
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")));

        // Project Info
        JPanel projectInfoPanel = new JPanel(new MigLayout("insets 0", "[]10[]15[]"));
        projectInfoPanel.setOpaque(false);
        
        FontIcon projectIcon = FontIcon.of(Codicons.FOLDER, 20);
        projectInfoPanel.add(new JLabel(projectIcon));

        projectLabel = new JLabel(projectService.getProjectName());
        projectLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +2");
        projectInfoPanel.add(projectLabel);
        
        branchLabel = new JLabel();
        branchLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        updateBranchLabel();
        projectInfoPanel.add(branchLabel);
        
        JButton openButton = new JButton("Open Project");
        openButton.setFocusable(false);
        openButton.addActionListener(e -> {
            chooseProject();
        });

        add(projectInfoPanel, "west");
        add(openButton, "center");
        add(new JLabel("🔔"), "east");

        projectService.addProjectListener(newRoot -> {
            projectLabel.setText(projectService.getProjectName());
            projectLabel.setToolTipText(newRoot.toAbsolutePath().toString());
            updateBranchLabel();
        });
        
        projectLabel.setToolTipText(projectService.getCurrentProjectRoot().toAbsolutePath().toString());
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