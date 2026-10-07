package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.service.SettingsService;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Sidebar panel containing user profile and navigation.
 */
public class SidebarPanel extends JPanel {
    private final SettingsService settingsService;
    private final Consumer<String> navigationAction;
    private final List<NavButton> navButtons = new ArrayList<>();

    public SidebarPanel(SettingsService settingsService, Consumer<String> navigationAction) {
        this.settingsService = settingsService;
        this.navigationAction = navigationAction;
        initComponents();
    }

    private void initComponents() {
        setLayout(new MigLayout("wrap, fillx, insets 15", "[fill, grow]", "[]20[]5[]2[]2[]2[]15[]5[]2[]2[]15[]5[]2[]2[]push"));
        
        putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");

        add(new UserProfileComponent("RoxyCode", System.getProperty("user.name")));

        add(createSectionHeader("MAIN"), "gaptop 10");
        add(createNavButton("Chat", "CHAT", Codicons.COMMENT_DISCUSSION));
        add(createNavButton("History", "HISTORY", Codicons.HISTORY));
        add(createNavButton("Plan", "PLAN", Codicons.LIST_ORDERED));
        add(createNavButton("Git Changes", "GIT", Codicons.DIFF));

        add(createSectionHeader("CONTEXT"), "gaptop 15");
        add(createNavButton("Codebase Cache", "CACHE", Codicons.DATABASE));
        add(createNavButton("API", "API", Codicons.CODE));
        add(createNavButton("Prompt", "PROMPT", Codicons.BOOK));

        add(createSectionHeader("CONFIG"), "gaptop 15");
        add(createNavButton("System Tools", "TOOLS", Codicons.TOOLS));
        add(createNavButton("Build Config", "BUILD_CONFIG", Codicons.PACKAGE));
        add(createNavButton("Settings", "SETTINGS", Codicons.SETTINGS_GEAR));
        
        if (!navButtons.isEmpty()) {
            updateSelection(navButtons.get(0));
        }
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

    private JButton createNavButton(String text, String cardName, Codicons iconCode) {
        FontIcon icon = FontIcon.of(iconCode, 16);
        NavButton btn = new NavButton(text, icon);
        btn.addActionListener(e -> {
            updateSelection(btn);
            navigationAction.accept(cardName);
        });
        navButtons.add(btn);
        return btn;
    }
}