package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.workflow.WorkflowService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.function.Consumer;

/**
 * Header panel containing application controls and project selection.
 */
public class HeaderPanel extends JPanel {
    private final ProjectService projectService;
    private final GitService gitService;
    private final JLabel projectLabel;
    private final JLabel branchLabel;
    private final JLabel modelLabel;
    

    public HeaderPanel(ProjectService projectService, GitService gitService, SettingsService settingsService, Consumer<String> navigationAction) {
        this.projectService = projectService;
        this.gitService = gitService;
        
        // 2-column layout: Left (Project), Right (Utils)
        setLayout(new MigLayout("insets 10 20 10 20, fillx", "[left]push[right]", "center"));
        
        putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")));

        // --- LEFT SECTION: Project Info ---
        JPanel leftPanel = new JPanel(new MigLayout("insets 0", "[]", "[]0[]"));
        leftPanel.setOpaque(false);

        JPanel namePanel = new JPanel(new MigLayout("insets 0", "[]5[]", "center"));
        namePanel.setOpaque(false);
        
        projectLabel = new JLabel(projectService.getProjectName());
        projectLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +2");
        namePanel.add(projectLabel);
        
        JButton openButton = createIconButton(Codicons.FOLDER_OPENED, "Open Project", e -> chooseProject());
        namePanel.add(openButton);
        
        leftPanel.add(namePanel, "wrap");

        branchLabel = new JLabel();
        branchLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        updateBranchLabel();
        leftPanel.add(branchLabel, "gapleft 2");

        add(leftPanel, "left");

        

        // --- RIGHT SECTION: Model & Utils ---
        JPanel rightPanel = new JPanel(new MigLayout("insets 0", "[]15[]15[]", "center"));
        rightPanel.setOpaque(false);

        modelLabel = new JLabel();
        modelLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        updateModelDisplay(settingsService.getSettings().getGeminiModel());
        rightPanel.add(modelLabel);

        JButton settingsButton = createIconButton(Codicons.SETTINGS_GEAR, "Settings", e -> navigationAction.accept("SETTINGS"));
        rightPanel.add(settingsButton);

        JLabel notificationLabel = new JLabel(FontIcon.of(Codicons.BELL, 16, UIManager.getColor("Label.disabledForeground")));
        rightPanel.add(notificationLabel);

        add(rightPanel, "right");

        // Listeners
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

    private JButton createIconButton(Codicons icon, String tooltip, java.awt.event.ActionListener listener) {
        JButton button = new JButton(FontIcon.of(icon, 16, UIManager.getColor("Label.foreground")));
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(listener);
        
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent e) {
                button.setContentAreaFilled(true);
                button.setBackground(UIManager.getColor("Button.hoverBackground"));
            }
            public void mouseExited(java.awt.event.MouseEvent e) {
                button.setContentAreaFilled(false);
            }
        });
        
        return button;
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
