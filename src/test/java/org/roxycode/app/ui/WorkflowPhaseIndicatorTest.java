package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import javax.swing.JLabel;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowPhase;

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
  void testWorkflowPhaseIndicatorTextOnlyNoIcon() {
    WorkflowPhaseIndicator indicator = new WorkflowPhaseIndicator(WorkflowPhase.PLAN);
    assertEquals(1, indicator.getComponentCount(), "Indicator should only have textLabel");
    Component comp = indicator.getComponent(0);
    assertInstanceOf(JLabel.class, comp);
    JLabel label = (JLabel) comp;
    assertEquals("Plan", label.getText());
    assertNull(label.getIcon(), "Indicator label should not have an icon");
  }

  @Test
  void testWorkflowPhaseIndicatorStateTransitions() {
    WorkflowPhaseIndicator indicator = new WorkflowPhaseIndicator(WorkflowPhase.PLAN);

    assertDoesNotThrow(
        () -> {
          indicator.setActive(true);
          indicator.setCompleted(false);
          indicator.setActive(false);
          indicator.setCompleted(true);
        });
  }
}
