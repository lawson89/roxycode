package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import javax.swing.JLabel;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowPhase;

class WorkflowPhaseIndicatorTest {

    @Test
    void testWorkflowPhaseIndicatorSafeColor() {
        Object oldAccent = UIManager.get("Component.accentForeground");
        try {
            UIManager.put("Component.accentForeground", null);
            WorkflowPhaseIndicator indicator = new WorkflowPhaseIndicator(WorkflowPhase.PLAN);
            indicator.setActive(true);
            assertEquals(WorkflowPhase.PLAN, indicator.getPhase());
            assertTrue(indicator.isActive());
        } finally {
            UIManager.put("Component.accentForeground", oldAccent);
        }
    }

    @Test
    void testWorkflowPhaseIndicatorDisplaysPhaseIconByDefault() {
        WorkflowPhaseIndicator indicator = new WorkflowPhaseIndicator(WorkflowPhase.PLAN);
        assertEquals(1, indicator.getComponentCount());
        JLabel label = assertInstanceOf(JLabel.class, indicator.getComponent(0));
        assertEquals("Plan", label.getText());
        assertNull(label.getIcon());
        assertFalse(indicator.isOpaque());
        assertNull(indicator.getClientProperty(FlatClientProperties.STYLE));
    }

    @Test
    void testWorkflowPhaseIndicatorStateTransitionsAndIcons() {
        WorkflowPhaseIndicator indicator = new WorkflowPhaseIndicator(WorkflowPhase.CODE);
        JLabel label = indicator.getTextLabel();

        assertFalse(indicator.isActive());
        assertFalse(indicator.isCompleted());
        assertFalse(indicator.isOpaque());
        assertNull(indicator.getClientProperty(FlatClientProperties.STYLE));
        assertNull(label.getIcon());
        assertEquals("font: -1", label.getClientProperty(FlatClientProperties.STYLE));

        indicator.setActive(true);
        assertTrue(indicator.isActive());
        assertTrue(indicator.isOpaque());
        assertNull(label.getIcon());
        assertEquals(Color.WHITE, label.getForeground());
        assertEquals("font: bold -1", label.getClientProperty(FlatClientProperties.STYLE));
        String activeStyle = (String) indicator.getClientProperty(FlatClientProperties.STYLE);
        assertTrue(
                activeStyle != null
                        && activeStyle.startsWith("background: #")
                        && !activeStyle.contains("arc:"));

        indicator.setActive(false);
        indicator.setCompleted(true);
        assertFalse(indicator.isActive());
        assertTrue(indicator.isCompleted());
        assertFalse(indicator.isOpaque());
        assertNull(label.getIcon());
        assertNull(indicator.getClientProperty(FlatClientProperties.STYLE));
        assertEquals("font: bold -1", label.getClientProperty(FlatClientProperties.STYLE));
        assertDoesNotThrow(indicator::updateUI);
    }
}
