package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.PlanManagerService;
import org.roxycode.app.events.TurnEventBridge;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Header panel containing application controls and project selection.
 */
public class HeaderPanel extends JPanel {
    private final ProjectService projectService;
    private final GitService gitService;
    private final WorkflowService workflowService;
    private final PlanManagerService planManagerService;
    
    private final JLabel projectLabel;
    private final JLabel branchLabel;
    private final JLabel modelLabel;
    private final JProgressBar progressBar;
    
    // Workflow components
    private final JLabel planLabel;
    private final JPanel progressTracker;
    private final List<PhaseIndicator> indicators = new ArrayList<>();

    public HeaderPanel(ProjectService projectService, GitService gitService, SettingsService settingsService, 
                       WorkflowService workflowService, PlanManagerService planManagerService,
                       TurnEventBridge turnEventBridge, Consumer<String> navigationAction) {
        this.projectService = projectService;
        this.gitService = gitService;
        this.workflowService = workflowService;
        this.planManagerService = planManagerService;
        
        // 3-column layout: Left (Project), Center (Workflow), Right (Utils)
        setLayout(new MigLayout("insets 10 20 10 20, fillx", "[left][center, grow][right]", "center"));
        
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

                // --- CENTER SECTION: Workflow Info ---
        JPanel workflowPanel = new JPanel(new MigLayout("insets 0, gapy 2", "[center]", "[]0[]"));
        workflowPanel.setOpaque(false);

        planLabel = new JLabel();
        planLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold -1");
        workflowPanel.add(planLabel, "center, wrap, hidemode 3");

        progressTracker = new JPanel(new MigLayout("insets 0, gapx 8", "[]", "center"));
        progressTracker.setOpaque(false);

                WorkflowPhase[] phases = WorkflowPhase.values();
        for (WorkflowPhase phase : phases) {
            indicators.add(new PhaseIndicator(phase));
        }
                workflowPanel.add(progressTracker, "center, hidemode 3");
        add(workflowPanel, "center");

        // --- RIGHT SECTION: Model & Utils ---
        JPanel rightPanel = new JPanel(new MigLayout("insets 0", "[]15[]15[]", "center"));
        rightPanel.setOpaque(false);

        modelLabel = new JLabel();
        modelLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        updateModelDisplay(settingsService.getSettings().getGeminiModel());

        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);

        JPanel modelPanel = new JPanel(new MigLayout("insets 0", "[]", "[]0[]"));
        modelPanel.setOpaque(false);
        modelPanel.add(modelLabel, "wrap");
        modelPanel.add(progressBar, "growx, h 2!");

        rightPanel.add(modelPanel);
        add(rightPanel, "right");

        // Listeners
        projectService.addProjectListener(newRoot -> {
            projectLabel.setText(projectService.getProjectName());
            if (newRoot != null) {
                projectLabel.setToolTipText(newRoot.toAbsolutePath().toString());
            } else {
                projectLabel.setToolTipText("No project selected");
            }
            updateBranchLabel();
        });
        
        if (projectService.getCurrentProjectRoot() != null) {
            projectLabel.setToolTipText(projectService.getCurrentProjectRoot().toAbsolutePath().toString());
        } else {
            projectLabel.setToolTipText("No project selected");
        }

        settingsService.addSettingsListener(settings -> {
            updateModelDisplay(settings.getGeminiModel());
        });

        workflowService.addPhaseListener(this::updateActivePhase);
        planManagerService.addListener((plan) -> updateActivePhase(workflowService.getCurrentPhase()));
        
        turnEventBridge.addUserMessageListener(event -> SwingUtilities.invokeLater(() -> progressBar.setVisible(true)));
        turnEventBridge.addCompleteListener(event -> SwingUtilities.invokeLater(() -> progressBar.setVisible(false)));

        // Initialize workflow display
        updateActivePhase(workflowService.getCurrentPhase());
    }

    private void updateActivePhase(WorkflowPhase currentPhase) {
        ImplementationPlan plan = planManagerService.getCurrentPlan();
        if (currentPhase != WorkflowPhase.EXPLORE && plan != null && plan.title() != null && !plan.title().isBlank()) {
            planLabel.setText(plan.title().toUpperCase());
            planLabel.setVisible(true);
        } else {
            planLabel.setVisible(false);
        }

                        progressTracker.removeAll();
        java.util.Set<WorkflowPhase> visited = workflowService.getVisitedPhases();
        for (PhaseIndicator indicator : indicators) {
            indicator.setActive(indicator.phase == currentPhase);
            indicator.setCompleted(visited.contains(indicator.phase));
            progressTracker.add(indicator);
        }
        revalidate();
        repaint();
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
        if (projectService.getCurrentProjectRoot() != null) {
            chooser.setCurrentDirectory(projectService.getCurrentProjectRoot().toFile());
        }
        
        int returnVal = chooser.showOpenDialog(this);
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            projectService.setCurrentProjectRoot(file.toPath());
        }
    }

        private static class PhaseIndicator extends JPanel {
        private final WorkflowPhase phase;
        private final JLabel textLabel;
        private boolean isActive;
        private boolean isCompleted;

                public PhaseIndicator(WorkflowPhase phase) {
            this.phase = phase;
            setLayout(new MigLayout("insets 2 8 2 8", "[]", "center"));
            setOpaque(false);
            
            textLabel = new JLabel(phase.getDisplayName());
            textLabel.putClientProperty(FlatClientProperties.STYLE, "font: -2");

            add(textLabel);
        }

        public void setActive(boolean active) {
            this.isActive = active;
            updateStyle();
        }

        public void setCompleted(boolean completed) {
            this.isCompleted = completed; 
            updateStyle();
        }

                private void updateStyle() {
            if (isActive) {
                setOpaque(true);
                putClientProperty(FlatClientProperties.STYLE, "arc: 12");
                setBackground(UIManager.getColor("Component.accentColor"));
                textLabel.setForeground(UIManager.getColor("Component.accentForeground"));
            } else {
                setOpaque(false);
                putClientProperty(FlatClientProperties.STYLE, "arc: 0");
                Color foreground = UIManager.getColor("Label.foreground");
                Color disabledForeground = UIManager.getColor("Label.disabledForeground");
                textLabel.setForeground(isCompleted ? foreground : disabledForeground);
            }
        }
    }
}
