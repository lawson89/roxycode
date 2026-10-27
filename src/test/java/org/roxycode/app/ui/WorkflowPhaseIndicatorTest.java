package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Component;
import javax.swing.JLabel;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
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
        FontIcon icon = assertInstanceOf(FontIcon.class, label.getIcon());
        assertEquals(Codicons.LIST_ORDERED, icon.getIkon());
        assertFalse(indicator.isOpaque());
        assertNull(indicator.getClientProperty(FlatClientProperties.STYLE));
    }

    @Test
    void testWorkflowPhaseIndicatorStateTransitionsAndIcons() {
        WorkflowPhaseIndicator indicator = new WorkflowPhaseIndicator(WorkflowPhase.CODE);
        JLabel label = indicator.getTextLabel();

        // Inactive initially: transparent segment inside the unified bar
        assertFalse(indicator.isActive());
        assertFalse(indicator.isCompleted());
        assertFalse(indicator.isOpaque());
        assertNull(indicator.getClientProperty(FlatClientProperties.STYLE));
        FontIcon initialIcon = assertInstanceOf(FontIcon.class, label.getIcon());
        assertEquals(Codicons.CODE, initialIcon.getIkon());

        // Active state: phase icon + 18% faded accent pill background
        indicator.setActive(true);
        assertTrue(indicator.isActive());
        assertTrue(indicator.isOpaque());
        FontIcon activeIcon = assertInstanceOf(FontIcon.class, label.getIcon());
        assertEquals(Codicons.CODE, activeIcon.getIkon());
        String activeStyle = (String) indicator.getClientProperty(FlatClientProperties.STYLE);
        assertNotNull(activeStyle);
        assertTrue(
                activeStyle.contains("arc: 10")
                        && activeStyle.contains("fade(")
                        && activeStyle.contains("18%)"));

        // Completed state (when not active): CHECK icon in green + 12% faded green pill background
        indicator.setActive(false);
        indicator.setCompleted(true);
        assertFalse(indicator.isActive());
        assertTrue(indicator.isCompleted());
        assertTrue(indicator.isOpaque());
        FontIcon completedIcon = assertInstanceOf(FontIcon.class, label.getIcon());
        assertEquals(Codicons.CHECK, completedIcon.getIkon());
        String completedStyle = (String) indicator.getClientProperty(FlatClientProperties.STYLE);
        assertNotNull(completedStyle);
        assertTrue(
                completedStyle.contains("arc: 10")
                        && completedStyle.contains("fade(")
                        && completedStyle.contains("12%)"));

        // Theme refresh
        assertDoesNotThrow(indicator::updateUI);
    }
}
