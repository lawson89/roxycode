package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.service.SettingsService;
import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Sidebar panel containing user profile and navigation.
 */
public class SidebarPanel extends JPanel {
    private final SettingsService settingsService;
    private final Consumer<String> navigationAction;

    public SidebarPanel(SettingsService settingsService, Consumer<String> navigationAction) {
        this.settingsService = settingsService;
        this.navigationAction = navigationAction;
        initComponents();
    }

    private void initComponents() {
        setLayout(new MigLayout("wrap, fillx, insets 15", "[fill, grow]", "[]20[]10[]10[]20[]10[]push"));
        
        // Use theme-aware styling
        putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");

        // Profile
        add(new UserProfileComponent("RoxyCode", System.getProperty("user.name")));

        // MAIN Section
        add(createSectionHeader("MAIN"), "gaptop 10");
        add(createNavButton("Chat", "CHAT"));
        add(createNavButton("Plan", "PLAN"));
        add(createNavButton("Context", "CONTEXT"));

        // OTHER Section
        add(createSectionHeader("OTHER"), "gaptop 15");
        add(createNavButton("Settings", "SETTINGS"));
    }

    private JLabel createSectionHeader(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 10f));
        label.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
        return label;
    }

    private JButton createNavButton(String text, String cardName) {
        NavButton btn = new NavButton(text, null);
        btn.addActionListener(e -> navigationAction.accept(cardName));
        return btn;
    }
}