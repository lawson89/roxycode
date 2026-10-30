package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;

public final class SidebarPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String LOGO_RESOURCE_PATH = "/static/images/roxy.png";
    static final int LOGO_SIZE = 88;
    static final String SUBTITLE_BADGE_TEXT = "AI Workspace";
    static final String SETTINGS_ATTENTION_TOOLTIP = "Gemini API Key required in Global Settings";
    static final String TOOLS_ATTENTION_TOOLTIP = "Required system tools are missing";
    private final Consumer<String> navigationAction;
    private final List<NavButton> navButtons = new ArrayList<>();
    private final Map<String, NavButton> cardButtons = new LinkedHashMap<>();
    private NavButton qaBtn;
    private NavButton taskBtn;
    private NavButton toolsBtn;
    private NavButton settingsBtn;

    public SidebarPanel(Consumer<String> navigationAction) {
        this.navigationAction = navigationAction;
        initComponents();
    }

    private void initComponents() {
        setLayout(new MigLayout("wrap, fillx, insets 15 12 15 12", "[fill, grow]",
                "[]20[]5[]2[]15[]5[]2[]2[]15[]5[]2[]2[]push"));
        putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
        updateBorder();
        add(createBrandingHeader());

        add(createSectionHeader("WORKSPACES"), "gaptop 10");
        qaBtn = createNavButton("Codebase Q&A", "QA_VIEW", Codicons.COMMENT_DISCUSSION);
        add(qaBtn);
        taskBtn = createNavButton("Feature Request", "TASK_VIEW", Codicons.GIT_PULL_REQUEST);
        add(taskBtn);

        add(createSectionHeader("CONTEXT"), "gaptop 15");
        add(createNavButton("Context", "CONTEXT_MGR", Codicons.FILE));
        add(createNavButton("Implicit Cache", "CACHE", Codicons.DATABASE));
        add(createNavButton("API", "API", Codicons.CODE));

        add(createSectionHeader("CONFIG"), "gaptop 15");
        toolsBtn = createNavButton("System Tools", MainFrame.TOOLS_VIEW, Codicons.TOOLS);
        toolsBtn.setAttentionTooltip(TOOLS_ATTENTION_TOOLTIP);
        add(toolsBtn);
        add(createNavButton("Project Settings", "BUILD_CONFIG", Codicons.PACKAGE));
        settingsBtn = createNavButton("Global Settings", MainFrame.SETTINGS_VIEW, Codicons.SETTINGS_GEAR);
        settingsBtn.setAttentionTooltip(SETTINGS_ATTENTION_TOOLTIP);
        add(settingsBtn);

        updateSelection(qaBtn);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        updateBorder();
    }

    private void updateBorder() {
        setBorder(SwingHtmlUtils.createThemeMatteBorder(0, 0, 0, 1));
    }

    private JPanel createBrandingHeader() {
        JPanel brandingPanel = new JPanel(new MigLayout("fillx, insets 8 10 10 10, wrap 1", "[center]", "[]8[]3[]"));
        brandingPanel.setOpaque(false);
        JLabel titleLabel = new JLabel("RoxyCode");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        JLabel subtitleBadge = new JLabel(SUBTITLE_BADGE_TEXT);
        subtitleBadge.putClientProperty(FlatClientProperties.STYLE,
                "font: bold -2; foreground: $Label.disabledForeground; background: darken($Panel.background, 5%); arc: 8; border: 2,8,2,8");
        brandingPanel.add(UiComponents.createRoundedImagePanel(LOGO_RESOURCE_PATH, LOGO_SIZE, 20),
                "w " + LOGO_SIZE + "!, h " + LOGO_SIZE + "!");
        brandingPanel.add(titleLabel);
        brandingPanel.add(subtitleBadge);
        return brandingPanel;
    }

    public void selectCard(String cardName) {
        NavButton btn = cardButtons.get(cardName);
        if (btn != null) {
            updateSelection(btn);
        }
    }

    public void setQaBusy(boolean busy) {
        if (qaBtn != null) {
            qaBtn.setBusy(busy);
        }
    }

    public boolean isQaBusy() {
        return qaBtn != null && qaBtn.isBusy();
    }

    public void setTaskBusy(boolean busy) {
        if (taskBtn != null) {
            taskBtn.setBusy(busy);
        }
    }

    public boolean isTaskBusy() {
        return taskBtn != null && taskBtn.isBusy();
    }

    public void setTaskWaitingApproval(boolean waitingApproval) {
        if (taskBtn != null) {
            taskBtn.setWaitingApproval(waitingApproval);
        }
    }

    public boolean isTaskWaitingApproval() {
        return taskBtn != null && taskBtn.isWaitingApproval();
    }

    public void setSettingsAttentionNeeded(boolean attentionNeeded) {
        if (settingsBtn != null) {
            settingsBtn.setWaitingApproval(attentionNeeded);
        }
    }

    public boolean isSettingsAttentionNeeded() {
        return settingsBtn != null && settingsBtn.isWaitingApproval();
    }

    public void setToolsAttentionNeeded(boolean attentionNeeded) {
        if (toolsBtn != null) {
            toolsBtn.setWaitingApproval(attentionNeeded);
        }
    }

    public boolean isToolsAttentionNeeded() {
        return toolsBtn != null && toolsBtn.isWaitingApproval();
    }

    NavButton getCardButton(String cardName) {
        return cardButtons.get(cardName);
    }

    private void updateSelection(NavButton selectedBtn) {
        for (NavButton btn : navButtons) {
            btn.setSelected(btn.equals(selectedBtn));
        }
    }

    private JLabel createSectionHeader(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 10f));
        label.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
        return label;
    }

    private NavButton createNavButton(String text, String cardName, Codicons iconCode) {
        NavButton btn = new NavButton(text, FontIcon.of(iconCode, 16));
        btn.addActionListener(e -> {
            updateSelection(btn);
            navigationAction.accept(cardName);
        });
        navButtons.add(btn);
        cardButtons.put(cardName, btn);
        return btn;
    }
}
