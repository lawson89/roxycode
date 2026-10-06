package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import javax.swing.ImageIcon;
import java.net.URL;
import static org.junit.jupiter.api.Assertions.*;

class MainFrameIconTest {
    @Test
    void testIconResourceIsLoadable() {
        URL iconUrl = MainFrame.class.getResource("/static/images/roxy.png");
        assertNotNull(iconUrl, "Icon resource should exist on classpath");
        ImageIcon icon = new ImageIcon(iconUrl);
        assertNotNull(icon.getImage(), "Image should be loadable from the URL");
    }
}