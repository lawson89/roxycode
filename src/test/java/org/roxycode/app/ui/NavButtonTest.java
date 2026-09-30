package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import javax.swing.*;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class NavButtonTest {
    @Test
    void testSelectionStyling() {
        NavButton btn = new NavButton("Test", null);
        
        assertFalse(btn.isSelected());
        assertFalse(btn.isContentAreaFilled());
        
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

    @Test
    void testFontIconColorSync() {
        FontIcon icon = FontIcon.of(Codicons.COMMENT_DISCUSSION, 16);
        NavButton btn = new NavButton("Test", icon);
        
        btn.setForeground(Color.RED);
        btn.setSelected(false);
        assertEquals(Color.RED, icon.getIconColor());
        
        btn.setForeground(Color.BLUE);
        btn.setSelected(false);
        assertEquals(Color.BLUE, icon.getIconColor());
    }
}
