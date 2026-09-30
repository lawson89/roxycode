package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import static org.junit.jupiter.api.Assertions.*;

class NavButtonTest {
    @Test
    void testSelectionStyling() {
        NavButton btn = new NavButton("Test", null);
        
        // Initial state
        assertFalse(btn.isSelected());
        assertFalse(btn.isContentAreaFilled());
        
        // Select
        btn.setSelected(true);
        assertTrue(btn.isSelected());
        assertTrue(btn.isContentAreaFilled());
        String style = (String) btn.getClientProperty(FlatClientProperties.STYLE);
        assertNotNull(style);
        
        if (FlatLaf.isLafDark()) {
            assertTrue(style.contains("background: $Component.accentColor"));
        } else {
            assertTrue(style.contains("background: #0d6efd"));
            assertTrue(style.contains("foreground: #ffffff"));
        }
        
        // Deselect
        btn.setSelected(false);
        assertFalse(btn.isSelected());
        assertFalse(btn.isContentAreaFilled());
        assertEquals("", btn.getClientProperty(FlatClientProperties.STYLE));
    }
    
    @Test
    void testArcProperty() {
        NavButton btn = new NavButton("Test", null);
        assertEquals(6, btn.getClientProperty("JButton.arc"));
    }
}