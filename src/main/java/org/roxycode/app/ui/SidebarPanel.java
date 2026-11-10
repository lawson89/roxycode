package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;

final class SidebarPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    static final int LOGO_SIZE = 44;
    static final int LOGO_ARC = 12;
    static final String ASK_ROXY_LABEL = "Ask Roxy";
    static final String BUILD_FEATURE_LABEL = "Build Feature";
    static final String CONTEXT_PROMPTS_LABEL = "Context & Prompts";
    static final String AGENT_MEMORY_LABEL = "Agent Memory";
    static final String PROJECT_SETTINGS_LABEL = "Project Settings";
    static final String SETTINGS_TOOLS_LABEL = "Settings & Tools";
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
    private NavButton memoryBtn;
    private NavButton buildConfigBtn;
    private NavButton settingsBtn;

    public SidebarPanel(Consumer<String> navigationAction) {
        this.navigationAction = navigationAction;
        initComponents();
    }

    private void initComponents() {
        setLayout(
                new MigLayout("wrap, fillx, insets 12 12 12 12", "[fill, grow]", "[]14[]5[]2[]14[]5[]2[]push[]5[]2[]"));
        putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
        updateBorder();
        add(createBrandingHeader());

        add(createSectionHeader("WORKSPACES"), "gaptop 6");
        qaBtn = createNavButton(ASK_ROXY_LABEL, MainFrame.QA_VIEW, Codicons.COMMENT_DISCUSSION);
        add(qaBtn);
        taskBtn = createNavButton(BUILD_FEATURE_LABEL, MainFrame.TASK_VIEW, Codicons.GIT_PULL_REQUEST);
        add(taskBtn);

        add(createSectionHeader("KNOWLEDGE"), "gaptop 12");
        contextMgrBtn = createNavButton(CONTEXT_PROMPTS_LABEL, MainFrame.CONTEXT_MGR_VIEW, Codicons.FILE);
        cardButtons.put(MainFrame.CACHE_VIEW, contextMgrBtn);
        cardButtons.put(MainFrame.API_VIEW, contextMgrBtn);
        add(contextMgrBtn);
        memoryBtn = createNavButton(AGENT_MEMORY_LABEL, MainFrame.MEMORY_VIEW, Codicons.BOOK);
        add(memoryBtn);

        add(createSectionHeader("SETTINGS"), "gaptop 12");
        buildConfigBtn = createNavButton(PROJECT_SETTINGS_LABEL, MainFrame.BUILD_CONFIG_VIEW, Codicons.PACKAGE);
        add(buildConfigBtn);
        settingsBtn = createNavButton(SETTINGS_TOOLS_LABEL, MainFrame.SETTINGS_VIEW, Codicons.SETTINGS_GEAR);
        settingsBtn.setAttentionTooltip(TOOLS_ATTENTION_TOOLTIP);
        cardButtons.put(MainFrame.TOOLS_VIEW, settingsBtn);
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
        JPanel brandingPanel = new JPanel(new MigLayout("insets 4 4 6 4", "[]10[grow]", "[center]"));
        brandingPanel.setOpaque(false);

        JPanel textStack = new JPanel(new MigLayout("insets 0, wrap 1, gapy 3", "[left]", "[][]"));
        textStack.setOpaque(false);

        JLabel titleLabel = new JLabel("RoxyCode");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 15f));
        JLabel subtitleBadge = new JLabel(SUBTITLE_BADGE_TEXT);
        subtitleBadge.putClientProperty(FlatClientProperties.STYLE,
                "font: bold -2; foreground: $Label.disabledForeground; background: darken($Panel.background, 5%); arc: 8; border: 2,8,2,8");
        textStack.add(titleLabel);
        textStack.add(subtitleBadge);

        brandingPanel.add(UiComponents.createRoundedImagePanel(UiComponents.ROXY_LOGO_RESOURCE, LOGO_SIZE, LOGO_ARC),
                "w " + LOGO_SIZE + "!, h " + LOGO_SIZE + "!");
        brandingPanel.add(textStack, "growx");
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

    public boolean isQaBusy() {
        return qaBtn.isBusy();
    }

    public void setTaskBusy(boolean busy) {
        taskBtn.setBusy(busy);
    }

    public boolean isTaskBusy() {
        return taskBtn.isBusy();
    }

    public void setQaWaitingApproval(boolean waitingApproval) {
        qaBtn.setWaitingApproval(waitingApproval);
    }

    public boolean isQaWaitingApproval() {
        return qaBtn.isWaitingApproval();
    }

    public void setTaskWaitingApproval(boolean waitingApproval) {
        taskBtn.setWaitingApproval(waitingApproval);
    }

    public boolean isTaskWaitingApproval() {
        return taskBtn.isWaitingApproval();
    }

    public void setToolsAttentionNeeded(boolean attentionNeeded) {
        settingsBtn.setWaitingApproval(attentionNeeded);
    }

    public boolean isToolsAttentionNeeded() {
        return settingsBtn.isWaitingApproval();
    }

    public void updateNavigationAccess(boolean hasApiKey, boolean hasProject, boolean buildConfirmed) {
        applyButtonAccess(settingsBtn, true, null);

        if (!hasApiKey) {
            applyButtonAccess(qaBtn, false, MISSING_API_KEY_TOOLTIP);
            applyButtonAccess(taskBtn, false, MISSING_API_KEY_TOOLTIP);
            applyButtonAccess(contextMgrBtn, false, MISSING_API_KEY_TOOLTIP);
            applyButtonAccess(memoryBtn, false, MISSING_API_KEY_TOOLTIP);
            applyButtonAccess(buildConfigBtn, false, MISSING_API_KEY_TOOLTIP);
            return;
        }

        applyButtonAccess(contextMgrBtn, true, null);
        applyButtonAccess(memoryBtn, true, null);

        if (!hasProject) {
            applyButtonAccess(qaBtn, false, MISSING_PROJECT_TOOLTIP);
            applyButtonAccess(taskBtn, false, MISSING_PROJECT_TOOLTIP);
            applyButtonAccess(buildConfigBtn, false, MISSING_PROJECT_TOOLTIP);
            return;
        }

        applyButtonAccess(buildConfigBtn, true, null);
        applyButtonAccess(qaBtn, true, null);

        if (!buildConfirmed) {
            applyButtonAccess(taskBtn, false, UNCONFIRMED_BUILD_TOOLTIP);
            return;
        }

        applyButtonAccess(taskBtn, true, null);
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
        Set<NavButton> distinctButtons = new LinkedHashSet<>(cardButtons.values());
        for (NavButton btn : distinctButtons) {
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
