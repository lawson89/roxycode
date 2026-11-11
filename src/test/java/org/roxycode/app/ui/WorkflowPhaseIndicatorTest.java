package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import javax.swing.ToolTipManager;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.swing.FontIcon;
import java.util.List;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.model.ImplementationPlan;

class WorkflowPhaseIndicatorTest {

    @Test
    void testPhaseIndicatorsAndImmediateTooltipBehavior() {
        for (WorkflowPhase phase : WorkflowPhase.values()) {
            WorkflowPhaseIndicator indicator = new WorkflowPhaseIndicator(phase);
            assertEquals(phase, indicator.getPhase());
            assertNotNull(indicator.getToolTipText());

            indicator.setActive(true);
            indicator.setActive(false);
            indicator.setCompleted(true);
            assertNotNull(indicator.getToolTipText());
        }

        PlanService planService = mock(PlanService.class);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);

        TaskWorkspacePanel taskWorkspace = SwingTestUtils
                .callOnEdt(() -> new TaskWorkspacePanel(null, planService, null, null));
        assertNotNull(taskWorkspace.workflowInfoIcon);
        assertSame(taskWorkspace.planLabel, taskWorkspace.topBar.getComponent(0));
        assertSame(taskWorkspace.progressTracker, taskWorkspace.topBar.getComponent(1));
        assertSame(taskWorkspace.workflowInfoIcon, taskWorkspace.topBar.getComponent(2));

        String fiftyCharTitle = "12345678901234567890123456789012345678901234567890";
        assertEquals(fiftyCharTitle, TaskWorkspacePanel.formatPlanTitleDisplay(fiftyCharTitle));
        String seventyCharTitle = "1234567890123456789012345678901234567890123456789012345678901234567890";
        String truncatedTitle = TaskWorkspacePanel.formatPlanTitleDisplay(seventyCharTitle);
        assertEquals(60, truncatedTitle.length());
        assertTrue(truncatedTitle.endsWith("..."));

        ImplementationPlan activePlan = new ImplementationPlan(fiftyCharTitle, "Goal", List.of(), List.of(), false,
                null, null, null, null, List.of());
        when(planService.getCurrentPlan()).thenReturn(activePlan);
        SwingTestUtils.runOnEdt(() -> taskWorkspace.updateActivePhase(WorkflowPhase.CODE));
        assertTrue(taskWorkspace.planLabel.isVisible());
        assertEquals(fiftyCharTitle, taskWorkspace.planLabel.getText());
        assertEquals(fiftyCharTitle + " (Click to view Plan)", taskWorkspace.planLabel.getToolTipText());

        assertEquals("Plan", taskWorkspace.taskTabs.getTitleAt(1));
        assertEquals(0, taskWorkspace.taskTabs.getSelectedIndex());
        SwingTestUtils.runOnEdt(() -> {
            MouseEvent clickEvent = new MouseEvent(taskWorkspace.planLabel, MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, 4, 4, 1, false);
            for (MouseListener listener : taskWorkspace.planLabel.getMouseListeners()) {
                listener.mouseClicked(clickEvent);
            }
        });
        assertEquals(1, taskWorkspace.taskTabs.getSelectedIndex());

        QaWorkspacePanel qaWorkspace = SwingTestUtils.callOnEdt(() -> new QaWorkspacePanel(null, null));
        assertNotNull(qaWorkspace.mentorModeLabel);
        assertEquals(QaWorkspacePanel.MENTOR_MODE_BADGE_TEXT, qaWorkspace.mentorModeLabel.getText());

        FontIcon searchFontIcon = (FontIcon) qaWorkspace.mentorModeLabel.getIcon();
        assertNotNull(searchFontIcon);
        assertEquals(org.kordamp.ikonli.codicons.Codicons.SEARCH, searchFontIcon.getIkon());

        ToolTipManager toolTipManager = ToolTipManager.sharedInstance();
        int customInitialDelay = 4321;
        SwingTestUtils.runOnEdt(() -> {
            toolTipManager.setInitialDelay(customInitialDelay);
            MouseEvent pressEvent = new MouseEvent(qaWorkspace.mentorModeLabel, MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(), 0, 4, 4, 1, false);
            for (MouseListener listener : qaWorkspace.mentorModeLabel.getMouseListeners()) {
                listener.mousePressed(pressEvent);
            }
            assertEquals(customInitialDelay, toolTipManager.getInitialDelay());
            UiComponents.showInfoTooltipImmediately(qaWorkspace.mentorModeLabel, null);
            assertEquals(customInitialDelay, toolTipManager.getInitialDelay());
        });
    }
}
