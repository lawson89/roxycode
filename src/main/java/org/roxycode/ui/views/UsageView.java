package org.roxycode.ui.views;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.core.UsageService;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import java.awt.*;

@Singleton
public class UsageView extends JPanel {

    private final UsageService usageService;

    @Outlet
    private JComponent viewUsage;

    @Outlet
    private JLabel usageHtmlLabel;

    @Outlet
    private JLabel usageIcon;

    @Outlet
    private JButton resetUsageButton;

    @Inject
    public UsageView(UsageService usageService) {
        this.usageService = usageService;
        setLayout(new BorderLayout());
    }

    @PostConstruct
    public void init() {
        add(UILoader.load(this, "UsageView.xml"));
        if (usageIcon != null) {
            usageIcon.setIcon(FontIcon.of(MaterialDesignC.CHART_LINE, 32));
        }
        initListeners();
        refresh();
    }

    private void initListeners() {
        if (resetUsageButton != null) {
            resetUsageButton.addActionListener(e -> {
                usageService.reset();
                refresh();
            });
        }
    }

    public void refresh() {
        if (usageHtmlLabel == null)
            return;
        String html = "<html><div style='padding: 10px;'><table border='0' cellspacing='0' cellpadding='8'>" +
                "<tr><td><font color='#888888'><b>API CALLS</b></font></td><td><font size='4'>" + usageService.getApiCalls() + "</font></td></tr>" +
                "<tr><td><font color='#888888'><b>TOTAL TOKENS</b></font></td><td><font size='4'>" + String.format("%,d", usageService.getTotalTokens()) + "</font></td></tr>" +
                "<tr><td><font color='#888888'><b>PROMPT TOKENS</b></font></td><td><font size='4'>" + String.format("%,d", usageService.getPromptTokens()) + "</font></td></tr>" +
                "<tr><td><font color='#888888'><b>CANDIDATE TOKENS</b></font></td><td><font size='4'>" + String.format("%,d", usageService.getCandidateTokens()) + "</font></td></tr>" +
                "<tr><td><font color='#888888'><b>ESTIMATED COST</b></font></td><td><font size='4' color='#4CAF50'><b>" + String.format("$%.4f", usageService.getEstimatedCost()) + "</b></font></td></tr>" +
                "</table></div></html>";
        usageHtmlLabel.setText(html);
    }
}
