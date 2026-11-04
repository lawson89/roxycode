package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import javax.swing.ToolTipManager;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;

class WorkflowPhaseIndicatorTest {

    @Test
    void testPhaseIndicatorsWorkspaceInfoIconsAndWelcomeCards() {
        for (WorkflowPhase phase : WorkflowPhase.values()) {
            WorkflowPhaseIndicator indicator = new WorkflowPhaseIndicator(phase);
            assertEquals(phase, indicator.getPhase());
            assertNotNull(indicator.getToolTipText());
            assertTrue(indicator.getToolTipText().contains(phase.getPersonaTitle()));

            indicator.setActive(true);
            assertTrue(indicator.getToolTipText().contains(phase.getPersonaTitle()));

            indicator.setActive(false);
            indicator.setCompleted(true);
            assertTrue(indicator.getToolTipText().contains(phase.getPersonaTitle()));
        }

        PlanService planService = mock(PlanService.class);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);

        TaskWorkspacePanel taskWorkspace = SwingTestUtils
                .callOnEdt(() -> new TaskWorkspacePanel(null, planService, null, null));
        assertNotNull(taskWorkspace.workflowInfoIcon);
        SwingTestUtils.assertContainsAll(taskWorkspace.workflowInfoIcon.getToolTipText(), "Plan", "Code", "Review",
                "Codebase Q&amp;A");

        QaWorkspacePanel qaWorkspace = SwingTestUtils.callOnEdt(() -> new QaWorkspacePanel(null, null));
        assertNotNull(qaWorkspace.mentorInfoIcon);
        SwingTestUtils.assertContainsAll(qaWorkspace.mentorInfoIcon.getToolTipText(), "Read-Only",
                "Move to Feature Request", "RoxyCode");

        FontIcon infoFontIcon = (FontIcon) qaWorkspace.mentorInfoIcon.getIcon();
        assertNotNull(infoFontIcon);
        assertEquals(15, infoFontIcon.getIconSize());
        assertEquals(UiComponents.resolveInfoIconColor(), infoFontIcon.getIconColor());
        assertNull(qaWorkspace.mentorInfoIcon.getClientProperty(FlatClientProperties.STYLE));
        assertNull(qaWorkspace.mentorInfoIcon.getBorder());
        assertFalse(qaWorkspace.mentorInfoIcon.isOpaque());
        assertEquals(qaWorkspace.qaHeaderBar, qaWorkspace.mentorInfoIcon.getParent());
        assertNotEquals(qaWorkspace.mentorBanner, qaWorkspace.mentorInfoIcon.getParent());

        assertEquals(taskWorkspace.topBar, taskWorkspace.workflowInfoIcon.getParent());
        assertNotEquals(taskWorkspace.progressTracker, taskWorkspace.workflowInfoIcon.getParent());
        assertNull(taskWorkspace.workflowInfoIcon.getClientProperty(FlatClientProperties.STYLE));
        assertNull(taskWorkspace.workflowInfoIcon.getBorder());
        assertFalse(taskWorkspace.workflowInfoIcon.isOpaque());

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

        String exploreWelcome = ChatHtmlTemplates.renderWelcomeCard(false);
        SwingTestUtils.assertContainsAll(exploreWelcome, "Ask about RoxyCode", "build.toml");

        String taskWelcome = ChatHtmlTemplates.renderWelcomeCard(true);
        SwingTestUtils.assertContainsAll(taskWelcome, "Hover over the", "Codebase Q&amp;A");
    }
}
