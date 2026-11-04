package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import javax.swing.ToolTipManager;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;

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
        assertNotNull(qaWorkspace.mentorInfoIcon);

        FontIcon infoFontIcon = (FontIcon) qaWorkspace.mentorInfoIcon.getIcon();
        assertNotNull(infoFontIcon);
        assertEquals(UiComponents.resolveInfoIconColor(), infoFontIcon.getIconColor());

        ToolTipManager toolTipManager = ToolTipManager.sharedInstance();
        int customInitialDelay = 4321;
        SwingTestUtils.runOnEdt(() -> {
            toolTipManager.setInitialDelay(customInitialDelay);
            MouseEvent pressEvent = new MouseEvent(qaWorkspace.mentorInfoIcon, MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(), 0, 4, 4, 1, false);
            for (MouseListener listener : qaWorkspace.mentorInfoIcon.getMouseListeners()) {
                listener.mousePressed(pressEvent);
            }
            assertEquals(customInitialDelay, toolTipManager.getInitialDelay());
            UiComponents.showInfoTooltipImmediately(qaWorkspace.mentorInfoIcon, null);
            assertEquals(customInitialDelay, toolTipManager.getInitialDelay());
        });
    }
}
