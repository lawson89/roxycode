
package org.roxycode.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import static org.junit.jupiter.api.Assertions.*;

public class MarkdownPaneTest {

    @Test
    public void testContextMenuInitialization() {
        MarkdownPane pane = new MarkdownPane();
        JPopupMenu popupMenu = pane.getComponentPopupMenu();
        
        assertNotNull(popupMenu, "Popup menu should be initialized");
        assertEquals(1, popupMenu.getComponentCount(), "Popup menu should have 1 item");
        
        JMenuItem copyItem = (JMenuItem) popupMenu.getComponent(0);
        assertEquals("Copy", copyItem.getText());
        assertNotNull(copyItem.getIcon(), "Copy item should have an icon");
    }
}
