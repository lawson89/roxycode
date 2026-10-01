package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.service.ProjectService;
import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * Header panel containing application controls and project selection.
 */
public class HeaderPanel extends JPanel {
    private final ProjectService projectService;
    private final JLabel projectLabel;

    public HeaderPanel(ProjectService projectService) {
        this.projectService = projectService;
        setLayout(new MigLayout("insets 10 20 10 20, fillx", "[][push][]", "center"));
        
        putClientProperty(FlatClientProperties.STYLE, "background: $Panel.background");
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")));

        // Project Info
        JPanel projectInfoPanel = new JPanel(new MigLayout("insets 0", "[]10[]"));
        projectInfoPanel.setOpaque(false);
        
        projectLabel = new JLabel(projectService.getProjectName());
        projectLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +2");
        
        JButton openButton = new JButton("Open Project");
        openButton.setFocusable(false);
        openButton.addActionListener(e -> {
            chooseProject();
        });

        projectInfoPanel.add(projectLabel);
        projectInfoPanel.add(openButton);
        
        add(projectInfoPanel, "west");
        add(new JLabel("🔔"), "east");

        projectService.addProjectListener(newRoot -> {
            projectLabel.setText(projectService.getProjectName());
            projectLabel.setToolTipText(newRoot.toAbsolutePath().toString());
        });
        
        projectLabel.setToolTipText(projectService.getCurrentProjectRoot().toAbsolutePath().toString());
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