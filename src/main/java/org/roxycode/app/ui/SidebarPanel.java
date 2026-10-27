package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
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
    private final Consumer<String> navigationAction;
    private final List<NavButton> navButtons = new ArrayList<>();
    private NavButton qaBtn;
    private NavButton taskBtn;

    public SidebarPanel(Consumer<String> navigationAction) {
        this.navigationAction = navigationAction;
        initComponents();
    }

    private void initComponents() {
        setLayout(
                new MigLayout(
                        "wrap, fillx, insets 15 12 15 12",
                        "[fill, grow]",
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
        add(createNavButton("System Tools", "TOOLS", Codicons.TOOLS));
        add(createNavButton("Build Config", "BUILD_CONFIG", Codicons.PACKAGE));
        add(createNavButton("Settings", "SETTINGS", Codicons.SETTINGS_GEAR));

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
        JPanel brandingPanel =
                new JPanel(
                        new MigLayout("fillx, insets 10 10 15 10, wrap 1", "[center]", "[]10[]"));
        brandingPanel.setOpaque(false);
        JLabel titleLabel = new JLabel("RoxyCode");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        brandingPanel.add(
                new RoundedImagePanel(LOGO_RESOURCE_PATH, LOGO_SIZE, 22),
                "w " + LOGO_SIZE + "!, h " + LOGO_SIZE + "!");
        brandingPanel.add(titleLabel);
        return brandingPanel;
    }

    public void setQaBusy(boolean busy) {
        if (qaBtn != null) qaBtn.setBusy(busy);
    }

    public boolean isQaBusy() {
        return qaBtn != null && qaBtn.isBusy();
    }

    public void setTaskBusy(boolean busy) {
        if (taskBtn != null) taskBtn.setBusy(busy);
    }

    public boolean isTaskBusy() {
        return taskBtn != null && taskBtn.isBusy();
    }

    public void setTaskWaitingApproval(boolean waitingApproval) {
        if (taskBtn != null) taskBtn.setWaitingApproval(waitingApproval);
    }

    public boolean isTaskWaitingApproval() {
        return taskBtn != null && taskBtn.isWaitingApproval();
    }

    private void updateSelection(NavButton selectedBtn) {
        for (NavButton btn : navButtons) btn.setSelected(btn.equals(selectedBtn));
    }

    private JLabel createSectionHeader(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 10f));
        label.putClientProperty(
                FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
        return label;
    }

    private NavButton createNavButton(String text, String cardName, Codicons iconCode) {
        NavButton btn = new NavButton(text, FontIcon.of(iconCode, 16));
        btn.addActionListener(
                e -> {
                    updateSelection(btn);
                    navigationAction.accept(cardName);
                });
        navButtons.add(btn);
        return btn;
    }
}
