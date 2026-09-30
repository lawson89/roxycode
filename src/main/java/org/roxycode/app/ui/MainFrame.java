package org.roxycode.app.ui;

import net.miginfocom.swing.MigLayout;
import org.roxycode.app.service.SettingsService;
import javax.swing.*;
import java.awt.*;

/**
 * Main application window for RoxyCode.
 */
public class MainFrame extends JFrame {

    private final SettingsService settingsService;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentArea = new JPanel(cardLayout);

    public MainFrame(SettingsService settingsService) {
        this.settingsService = settingsService;
        setupLaf();
        setupWindow();
        initComponents();
    }

    private void setupLaf() {
        SettingsPanel.applyTheme(settingsService.getSettings().getTheme());
    }

    private void setupWindow() {
        setTitle("RoxyCode");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 800);
        setLocationRelativeTo(null);
    }

    private void initComponents() {
        setLayout(new MigLayout("fill, insets 0", "[200!]0[fill, grow]", "[fill, grow]"));

        // Sidebar
        SidebarPanel sidebar = new SidebarPanel(settingsService, cardName -> cardLayout.show(contentArea, cardName));

        // Main Workspace
        JPanel workspace = new JPanel(new MigLayout("fill, insets 0", "[fill, grow]", "[]0[fill, grow]0[]"));
        
        HeaderPanel header = new HeaderPanel();
        StatusPanel statusBar = new StatusPanel();

        // Content Area Panels
        JPanel welcomePanel = new JPanel(new MigLayout("fill", "[center]", "[center]"));
        welcomePanel.add(new JLabel("Welcome to RoxyCode Agent UI"));
        // background will be default theme color
        
        contentArea.add(welcomePanel, "CHAT");
        contentArea.add(createPlaceholderPanel("Plan Management"), "PLAN");
        contentArea.add(createPlaceholderPanel("Context Viewer"), "CONTEXT");
        contentArea.add(new SettingsPanel(settingsService), "SETTINGS");

        workspace.add(header, "h 110!, wrap");
        workspace.add(contentArea, "grow, wrap");
        workspace.add(statusBar, "h 30!");

        add(sidebar, "growy");
        add(workspace, "grow");
    }
    
    private JPanel createPlaceholderPanel(String text) {
        JPanel p = new JPanel(new MigLayout("fill", "[center]", "[center]"));
        p.add(new JLabel(text));
        return p;
    }
}