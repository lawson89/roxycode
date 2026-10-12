package org.roxycode.app.ui;
 
import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
 
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.PlanService;
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
    private final PlanService planManagerService;
    
    private final JLabel projectLabel;
    private final JLabel branchLabel;
    private final JLabel modelLabel;
    
    // Workflow components
    private final JLabel planLabel;
    private final JPanel progressTracker;
    private final JLabel mentorBadge;
    private final List<WorkflowPhaseIndicator> indicators = new ArrayList<>();
 
    public HeaderPanel(ProjectService projectService, GitService gitService, SettingsService settingsService, 
                       WorkflowService workflowService, PlanService planManagerService,
                       Consumer<String> navigationAction) {
        this.projectService = projectService;
        this.gitService = gitService;
        this.workflowService = workflowService;
        this.planManagerService = planManagerService;
        
        // 3-column layout: Left (Project), Center (Workflow), Right (Utils)
        setLayout(new MigLayout("insets 5 20 5 20, fillx", "[left][center, grow][right]", "center"));
        
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
        JPanel workflowPanel = new JPanel(new MigLayout("insets 0, gapy 0", "[center]", "[]0[]0[]"));
        workflowPanel.setOpaque(false);
 
        planLabel = new JLabel();
        planLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold -2; foreground: $Component.accentColor");
        workflowPanel.add(planLabel, "center, wrap, hidemode 3");
 
        mentorBadge = new JLabel("READ-ONLY MENTOR MODE", FontIcon.of(Codicons.EYE, 14), SwingConstants.CENTER);
        mentorBadge.putClientProperty(FlatClientProperties.STYLE, 
            "font: bold -2; " +
            "foreground: $Label.disabledForeground; " +
            "background: darken($Panel.background, 5%); " +
            "arc: 12");
        mentorBadge.setOpaque(true);
        mentorBadge.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        workflowPanel.add(mentorBadge, "center, wrap, hidemode 3");
 
        progressTracker = new JPanel(new MigLayout("insets 0, gapx 8", "[]", "center"));
        progressTracker.setOpaque(false);
 
        WorkflowPhase[] phases = WorkflowPhase.values();
        for (WorkflowPhase phase : phases) {
            indicators.add(new WorkflowPhaseIndicator(phase, "font: bold -1"));
        }
        workflowPanel.add(progressTracker, "center, hidemode 3, gaptop 2");
        add(workflowPanel, "center");
 
        // --- RIGHT SECTION: Model & Utils ---
        JPanel rightPanel = new JPanel(new MigLayout("insets 0", "[]", "center"));
        rightPanel.setOpaque(false);
 
        modelLabel = new JLabel();
        modelLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        updateModelDisplay(settingsService.getSettings().getGeminiModel());
 
        rightPanel.add(modelLabel);
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
 
        mentorBadge.setVisible(currentPhase == WorkflowPhase.EXPLORE);
        progressTracker.setVisible(currentPhase != WorkflowPhase.EXPLORE);
 
        progressTracker.removeAll();
        for (WorkflowPhaseIndicator indicator : indicators) {
            if (indicator.getPhase() == WorkflowPhase.EXPLORE) continue;
            indicator.setActive(indicator.getPhase() == currentPhase);
            indicator.setCompleted(workflowService.getVisitedPhases().contains(indicator.getPhase()));
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
}
