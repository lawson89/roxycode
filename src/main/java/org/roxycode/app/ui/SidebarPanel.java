package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.springframework.lang.Nullable;

public final class SidebarPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    static final int LOGO_SIZE = 88;
    static final int AVATAR_SIZE = 72;
    static final String SUBTITLE_BADGE_TEXT = "AI Workspace";
    static final String TOOLS_ATTENTION_TOOLTIP = "Required system tools are missing";
    static final String MISSING_API_KEY_TOOLTIP = "Configure your Gemini API Key in Global Settings first";
    static final String MISSING_PROJECT_TOOLTIP = "Select a project folder in the top bar first";
    static final String UNCONFIRMED_BUILD_TOOLTIP = "Confirm your project build configuration in Project Settings first";

    private final Consumer<String> navigationAction;
    private final Map<String, NavButton> cardButtons = new LinkedHashMap<>();
    final AvatarPanel avatarPanel;
    private NavButton qaBtn;
    private NavButton taskBtn;
    private NavButton contextMgrBtn;
    private NavButton memoryBtn;
    private NavButton cacheBtn;
    private NavButton apiBtn;
    private NavButton toolsBtn;
    private NavButton buildConfigBtn;
    private NavButton settingsBtn;

    public SidebarPanel(Consumer<String> navigationAction) {
        this.navigationAction = navigationAction;
        this.avatarPanel = new AvatarPanel(AVATAR_SIZE);
        initComponents();
    }

    private void initComponents() {
        setLayout(new MigLayout("wrap, fillx, insets 15 12 15 12", "[fill, grow]",
                "[]20[]5[]2[]15[]5[]2[]2[]2[]15[]5[]2[]2[]push"));
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
        memoryBtn = createNavButton("Agent Memory", MainFrame.MEMORY_VIEW, Codicons.BOOK);
        add(memoryBtn);
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
        brandingPanel.add(avatarPanel, "w " + AVATAR_SIZE + "!, h " + AVATAR_SIZE + "!");
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

    public boolean isQaBusy() {
        if (qaBtn == null) {
            return false;
        }
        return qaBtn.isBusy();
    }

    public boolean isTaskBusy() {
        if (taskBtn == null) {
            return false;
        }
        return taskBtn.isBusy();
    }

    public void setTaskWaitingApproval(boolean waitingApproval) {
        taskBtn.setWaitingApproval(waitingApproval);
    }

    public boolean isTaskWaitingApproval() {
        return taskBtn.isWaitingApproval();
    }

    public void updateAgentStatus(@Nullable String projectName, AppIconRenderer.AgentStatus status,
            boolean blinkHighlight) {
        if (avatarPanel != null) {
            avatarPanel.updateState(projectName, status, blinkHighlight);
        }
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
            applyButtonAccess(memoryBtn, false, MISSING_API_KEY_TOOLTIP);
            applyButtonAccess(buildConfigBtn, false, MISSING_API_KEY_TOOLTIP);
            return;
        }

        applyButtonAccess(apiBtn, true, null);
        applyButtonAccess(contextMgrBtn, true, null);
        applyButtonAccess(memoryBtn, true, null);

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

    static final class AvatarPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private final int size;
        private String projectName;
        private AppIconRenderer.AgentStatus status = AppIconRenderer.AgentStatus.IDLE;
        private boolean blinkHighlight;
        private transient BufferedImage cachedImage;

        AvatarPanel(int size) {
            this.size = size;
            setPreferredSize(new Dimension(size, size));
            setOpaque(false);
            refreshImage();
        }

        void updateState(@Nullable String projectName, AppIconRenderer.AgentStatus status, boolean blinkHighlight) {
            this.projectName = projectName;
            this.status = status;
            this.blinkHighlight = blinkHighlight;
            refreshImage();
            repaint();
        }

        private void refreshImage() {
            cachedImage = AppIconRenderer.renderAppIcon(size, projectName, status, blinkHighlight);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (cachedImage != null) {
                g.drawImage(cachedImage, 0, 0, getWidth(), getHeight(), null);
            }
        }
    }
}
