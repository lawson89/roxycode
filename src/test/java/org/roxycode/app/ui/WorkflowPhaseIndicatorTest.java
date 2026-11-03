package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
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

        String exploreWelcome = ChatHtmlTemplates.renderWelcomeCard(false);
        SwingTestUtils.assertContainsAll(exploreWelcome, "Ask about RoxyCode", "build.toml");

        String taskWelcome = ChatHtmlTemplates.renderWelcomeCard(true);
        SwingTestUtils.assertContainsAll(taskWelcome, "Hover over the", "Codebase Q&amp;A");
    }
}
