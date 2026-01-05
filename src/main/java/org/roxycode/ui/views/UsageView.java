package org.roxycode.ui.views;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.core.UsageService;

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
    private JButton resetUsageButton;

    @Inject
    public UsageView(UsageService usageService) {
        this.usageService = usageService;
        setLayout(new BorderLayout());
    }

    @PostConstruct
    public void init() {
        add(UILoader.load(this, "UsageView.xml"));
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
        String html = "<html><table border='0' cellspacing='0' cellpadding='8'>" +
                "<tr><td><b><font color='#888888'>API CALLS</font></b></td><td>" + usageService.getApiCalls() + "</td></tr>" +
                "<tr><td><b><font color='#888888'>TOTAL TOKENS</font></b></td><td>" + String.format("%,d", usageService.getTotalTokens()) + "</td></tr>" +
                "<tr><td><b><font color='#888888'>PROMPT TOKENS</font></b></td><td>" + String.format("%,d", usageService.getPromptTokens()) + "</td></tr>" +
                "<tr><td><b><font color='#888888'>CANDIDATE TOKENS</font></b></td><td>" + String.format("%,d", usageService.getCandidateTokens()) + "</td></tr>" +
                "<tr><td><b><font color='#888888'>ESTIMATED COST</font></b></td><td>" + String.format("$%.4f", usageService.getEstimatedCost()) + "</td></tr>" +
                "</table></html>";
        usageHtmlLabel.setText(html);
    }
}
