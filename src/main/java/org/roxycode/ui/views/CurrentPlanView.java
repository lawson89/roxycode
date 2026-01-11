package org.roxycode.ui.views;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.core.tools.service.plans.PlanService;
import org.roxycode.ui.MarkdownPane;
import org.roxycode.ui.ThemeService;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

@Singleton
public class CurrentPlanView extends JPanel {

    private final PlanService planService;
    private final ThemeService themeService;
    private final MarkdownPane planArea = new MarkdownPane();

    @Outlet
    private JScrollPane planScrollPane;

    @Outlet
    private JButton refreshPlanButton;

    @Inject
    public CurrentPlanView(PlanService planService, ThemeService themeService) {
        this.planService = planService;
        this.themeService = themeService;
        setLayout(new BorderLayout());
    }

    @PostConstruct
    public void init() {
        add(UILoader.load(this, "CurrentPlanView.xml"));
        if (planScrollPane != null) {
            planScrollPane.setViewportView(planArea);
        }
        themeService.registerPane(planArea);
        initListeners();
        refresh();
    }

    private void initListeners() {
        if (refreshPlanButton != null) {
            refreshPlanButton.addActionListener(e -> refresh());
        }
    }

    public void refresh() {
        try {
            String markdown = planService.getCurrentPlanMarkdown();
            if (markdown == null || markdown.isEmpty()) {
                planArea.setMarkdown("*No active plan.*");
            } else {
                planArea.setMarkdown(markdown);
            }
        } catch (IOException e) {
            planArea.setMarkdown("Error loading plan: " + e.getMessage());
        }
    }
    public MarkdownPane getPlanArea() {
        return planArea;
    }
}
