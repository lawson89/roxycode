package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import java.awt.Component;
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
        assertEquals(1, indicator.getComponentCount(), "Indicator should have textLabel");
        Component comp = indicator.getComponent(0);
        JLabel label = assertInstanceOf(JLabel.class, comp);
        assertEquals("Plan", label.getText());
        assertNull(label.getIcon());
        assertFalse(indicator.isOpaque());
        assertNull(indicator.getClientProperty(FlatClientProperties.STYLE));
    }

    @Test
    void testWorkflowPhaseIndicatorStateTransitionsAndIcons() {
        WorkflowPhaseIndicator indicator = new WorkflowPhaseIndicator(WorkflowPhase.CODE);
        JLabel label = indicator.getTextLabel();

        // Inactive initially: transparent segment inside the unified bar with no icon
        assertFalse(indicator.isActive());
        assertFalse(indicator.isCompleted());
        assertFalse(indicator.isOpaque());
        assertNull(indicator.getClientProperty(FlatClientProperties.STYLE));
        assertNull(label.getIcon());
        assertEquals("font: -1", label.getClientProperty(FlatClientProperties.STYLE));

        // Active state: solid rectangular accent background with white bold text and no icon
        indicator.setActive(true);
        assertTrue(indicator.isActive());
        assertTrue(indicator.isOpaque());
        assertNull(label.getIcon());
        assertEquals(Color.WHITE, label.getForeground());
        assertEquals("font: bold -1", label.getClientProperty(FlatClientProperties.STYLE));
        String activeStyle = (String) indicator.getClientProperty(FlatClientProperties.STYLE);
        assertNotNull(activeStyle);
        assertTrue(activeStyle.startsWith("background: #"));
        assertFalse(activeStyle.contains("arc:"));

        // Completed state (when not active): transparent without rounded pill, green bold text, no
        // icon
        indicator.setActive(false);
        indicator.setCompleted(true);
        assertFalse(indicator.isActive());
        assertTrue(indicator.isCompleted());
        assertFalse(indicator.isOpaque());
        assertNull(label.getIcon());
        assertNull(indicator.getClientProperty(FlatClientProperties.STYLE));
        assertEquals("font: bold -1", label.getClientProperty(FlatClientProperties.STYLE));

        // Theme refresh
        assertDoesNotThrow(indicator::updateUI);
    }
}
