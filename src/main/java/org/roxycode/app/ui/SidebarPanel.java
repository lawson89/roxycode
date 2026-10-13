package org.roxycode.app.ui;
 
import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.WorkflowPhase;
import javax.swing.*;
import java.awt.event.ActionListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
 
/**
 * Sidebar panel containing user profile and navigation.
 */
public class SidebarPanel extends JPanel {
    private final SettingsService settingsService;
    private final WorkflowService workflowService;
    private final Consumer<String> navigationAction;
    private final List<NavButton> navButtons = new ArrayList<>();
 
    public SidebarPanel(SettingsService settingsService, WorkflowService workflowService, Consumer<String> navigationAction) {
        this.settingsService = settingsService;
        this.workflowService = workflowService;
        this.navigationAction = navigationAction;
        initComponents();
    }
 
    private void initComponents() {
        setLayout(new MigLayout("wrap, fillx, insets 15", "[fill, grow]", "[]20[]5[]2[]2[]2[]2[]15[]5[]2[]2[]15[]5[]2[]2[]push"));
        
        putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
 
        add(new UserProfileComponent("RoxyCode", System.getProperty("user.name")));
 
        add(createSectionHeader("MAIN"), "gaptop 10");
        
        NavButton qaBtn = createNavButton("Codebase Q&A", "CHAT", Codicons.COMMENT_DISCUSSION, e -> 
            navigateToChat(WorkflowPhase.EXPLORE, (NavButton)e.getSource()));
        add(qaBtn);
        
        NavButton taskBtn = createNavButton("Active Task", "CHAT", Codicons.ROCKET, e -> {
            WorkflowPhase target = workflowService.getCurrentPhase() == WorkflowPhase.EXPLORE ? 
                WorkflowPhase.PLAN : workflowService.getCurrentPhase();
            navigateToChat(target, (NavButton)e.getSource());
        });
        add(taskBtn);

        add(createNavButton("Project Plan", "PLAN", Codicons.LIST_ORDERED));
 
        add(createNavButton("History", "HISTORY", Codicons.HISTORY));
        add(createNavButton("Git Changes", "GIT", Codicons.DIFF));
        
        add(createSectionHeader("CONTEXT"), "gaptop 15");
        add(createNavButton("Project Context", "CONTEXT_MGR", Codicons.FILE));
        add(createNavButton("Implicit Cache", "CACHE", Codicons.DATABASE));
        add(createNavButton("API", "API", Codicons.CODE));
 
        add(createSectionHeader("CONFIG"), "gaptop 15");
        add(createNavButton("System Tools", "TOOLS", Codicons.TOOLS));
        add(createNavButton("Build Config", "BUILD_CONFIG", Codicons.PACKAGE));
        add(createNavButton("Settings", "SETTINGS", Codicons.SETTINGS_GEAR));
        
        // Listen for phase changes to sync selection
        workflowService.addPhaseListener(phase -> {
            if (phase == WorkflowPhase.EXPLORE) {
                updateSelection(qaBtn);
            } else {
                updateSelection(taskBtn);
            }
        });
    }
 
    private void updateSelection(NavButton selectedBtn) {
        for (NavButton btn : navButtons) {
            btn.setSelected(btn == selectedBtn);
        }
    }
 
    private JLabel createSectionHeader(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 10f));
        label.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
        return label;
    }
 
    private NavButton createNavButton(String text, String cardName, Codicons iconCode) {
        return createNavButton(text, cardName, iconCode, null);
    }

    private NavButton createNavButton(String text, String cardName, Codicons iconCode, ActionListener customListener) {
        FontIcon icon = FontIcon.of(iconCode, 16);
        NavButton btn = new NavButton(text, icon);
        if (customListener != null) {
            btn.addActionListener(customListener);
        } else {
            btn.addActionListener(e -> {
                updateSelection(btn);
                navigationAction.accept(cardName);
            });
        }
        navButtons.add(btn);
        return btn;
    }
    private void navigateToChat(WorkflowPhase targetPhase, NavButton btn) {
        workflowService.switchMode(targetPhase);
        updateSelection(btn);
        navigationAction.accept("CHAT");
    }

}
