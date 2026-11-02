package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Font;
import java.util.LinkedHashMap;
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
    static final String TOOLS_ATTENTION_TOOLTIP = "Required system tools are missing";
    static final String MISSING_API_KEY_TOOLTIP = "Configure your Gemini API Key in Global Settings first";
    static final String MISSING_PROJECT_TOOLTIP = "Select a project folder in the top bar first";
    static final String UNCONFIRMED_BUILD_TOOLTIP = "Confirm your project build configuration in Project Settings first";

    private final Consumer<String> navigationAction;
    private final Map<String, NavButton> cardButtons = new LinkedHashMap<>();
    private NavButton qaBtn;
    private NavButton taskBtn;
    private NavButton contextMgrBtn;
    private NavButton cacheBtn;
    private NavButton apiBtn;
    private NavButton toolsBtn;
    private NavButton buildConfigBtn;
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
        qaBtn = createNavButton("Codebase Q&A", MainFrame.QA_VIEW, Codicons.COMMENT_DISCUSSION);
        add(qaBtn);
        taskBtn = createNavButton("Feature Request", MainFrame.TASK_VIEW, Codicons.GIT_PULL_REQUEST);
        add(taskBtn);

        add(createSectionHeader("CONTEXT"), "gaptop 15");
        contextMgrBtn = createNavButton("Context", MainFrame.CONTEXT_MGR_VIEW, Codicons.FILE);
        add(contextMgrBtn);
        cacheBtn = createNavButton("Repo Map & Prompt", MainFrame.CACHE_VIEW, Codicons.DATABASE);
        add(cacheBtn);
        apiBtn = createNavButton("Agent Tools API", MainFrame.API_VIEW, Codicons.CODE);
        add(apiBtn);

        add(createSectionHeader("CONFIG"), "gaptop 15");
        toolsBtn = createNavButton("System Tools", MainFrame.TOOLS_VIEW, Codicons.TOOLS);
        toolsBtn.setAttentionTooltip(TOOLS_ATTENTION_TOOLTIP);
        add(toolsBtn);
        buildConfigBtn = createNavButton("Project Settings", MainFrame.BUILD_CONFIG_VIEW, Codicons.PACKAGE);
        add(buildConfigBtn);
        settingsBtn = createNavButton("Global Settings", MainFrame.SETTINGS_VIEW, Codicons.SETTINGS_GEAR);
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
        qaBtn.setBusy(busy);
    }

    public void setTaskBusy(boolean busy) {
        taskBtn.setBusy(busy);
    }

    public void setTaskWaitingApproval(boolean waitingApproval) {
        taskBtn.setWaitingApproval(waitingApproval);
    }

    public boolean isTaskWaitingApproval() {
        return taskBtn.isWaitingApproval();
    }

    public void setToolsAttentionNeeded(boolean attentionNeeded) {
        toolsBtn.setWaitingApproval(attentionNeeded);
    }

    public boolean isToolsAttentionNeeded() {
        return toolsBtn.isWaitingApproval();
    }

    public void updateNavigationAccess(boolean hasApiKey, boolean hasProject, boolean buildConfirmed) {
        applyButtonAccess(settingsBtn, true, null);
        applyButtonAccess(toolsBtn, true, null);

        if (!hasApiKey) {
            applyButtonAccess(qaBtn, false, MISSING_API_KEY_TOOLTIP);
            applyButtonAccess(taskBtn, false, MISSING_API_KEY_TOOLTIP);
            applyButtonAccess(cacheBtn, false, MISSING_API_KEY_TOOLTIP);
            applyButtonAccess(apiBtn, false, MISSING_API_KEY_TOOLTIP);
            applyButtonAccess(contextMgrBtn, false, MISSING_API_KEY_TOOLTIP);
            applyButtonAccess(buildConfigBtn, false, MISSING_API_KEY_TOOLTIP);
            return;
        }

        applyButtonAccess(apiBtn, true, null);
        applyButtonAccess(contextMgrBtn, true, null);

        if (!hasProject) {
            applyButtonAccess(qaBtn, false, MISSING_PROJECT_TOOLTIP);
            applyButtonAccess(taskBtn, false, MISSING_PROJECT_TOOLTIP);
            applyButtonAccess(cacheBtn, false, MISSING_PROJECT_TOOLTIP);
            applyButtonAccess(buildConfigBtn, false, MISSING_PROJECT_TOOLTIP);
            return;
        }

        applyButtonAccess(buildConfigBtn, true, null);

        if (!buildConfirmed) {
            applyButtonAccess(qaBtn, false, UNCONFIRMED_BUILD_TOOLTIP);
            applyButtonAccess(taskBtn, false, UNCONFIRMED_BUILD_TOOLTIP);
            applyButtonAccess(cacheBtn, false, UNCONFIRMED_BUILD_TOOLTIP);
            return;
        }

        applyButtonAccess(qaBtn, true, null);
        applyButtonAccess(taskBtn, true, null);
        applyButtonAccess(cacheBtn, true, null);
    }

    private static void applyButtonAccess(NavButton btn, boolean enabled, String disabledTooltip) {
        if (btn == null) {
            return;
        }
        btn.setEnabled(enabled);
        if (!enabled) {
            btn.setToolTipText(disabledTooltip);
        } else {
            btn.restoreStatusTooltip();
        }
    }

    NavButton getCardButton(String cardName) {
        return cardButtons.get(cardName);
    }

    private void updateSelection(NavButton selectedBtn) {
        for (NavButton btn : cardButtons.values()) {
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
        cardButtons.put(cardName, btn);
        return btn;
    }
}
