package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import javax.swing.ImageIcon;
import org.junit.jupiter.api.Test;

class MainFrameIconTest {
    @Test
    void testIconResourceIsLoadable() {
        URL iconUrl = MainFrame.class.getResource("/static/images/roxy.png");
        assertNotNull(iconUrl, "Icon resource should exist on classpath");
        ImageIcon icon = new ImageIcon(iconUrl);
        assertNotNull(icon.getImage(), "Image should be loadable from the URL");
    }
}
