package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowPhase;
import javax.swing.UIManager;
import java.awt.Color;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowPhaseIndicatorTest {

    @Test
    void testWorkflowPhaseIndicatorSafeColor() {
        // Clear UIManager properties to simulate missing keys
        Object oldAccent = UIManager.get("Component.accentForeground");
        try {
            UIManager.put("Component.accentForeground", null);
            
            WorkflowPhaseIndicator indicator = new WorkflowPhaseIndicator(WorkflowPhase.PLAN);
            indicator.setActive(true);
            
            // Should not throw NPE and should have phase
            assertEquals(WorkflowPhase.PLAN, indicator.getPhase());
        } finally {
            UIManager.put("Component.accentForeground", oldAccent);
        }
    }

    @Test
    void testWorkflowPhaseIndicatorStateTransitions() {
        WorkflowPhaseIndicator indicator = new WorkflowPhaseIndicator(WorkflowPhase.PLAN);
        
        assertDoesNotThrow(() -> {
            indicator.setActive(true);
            indicator.setCompleted(false);
            indicator.setActive(false);
            indicator.setCompleted(true);
        });
    }
}
