package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.codicons.Codicons;

class SidebarPanelTest {

    @Test
    void testBuildConfigAttentionBellAndNavigation() {
        AtomicReference<String> selectedCard = new AtomicReference<>();
        SidebarPanel sidebar = SwingTestUtils.callOnEdt(() -> new SidebarPanel(selectedCard::set));

        assertFalse(sidebar.isBuildConfigAttentionNeeded());
        NavButton buildConfigBtn = sidebar.getCardButton(MainFrame.BUILD_CONFIG_VIEW);
        assertNotNull(buildConfigBtn);
        assertNull(buildConfigBtn.getStatusIcon());

        SwingTestUtils.runOnEdt(() -> sidebar.setBuildConfigAttentionNeeded(true));
        assertTrue(sidebar.isBuildConfigAttentionNeeded());
        assertNotNull(buildConfigBtn.getStatusIcon());
        assertEquals(Codicons.BELL_DOT, buildConfigBtn.getStatusIcon().getIkon());
        assertEquals(SidebarPanel.BUILD_CONFIG_ATTENTION_TOOLTIP, buildConfigBtn.getToolTipText());

        SwingTestUtils.clickOnEdt(buildConfigBtn);
        assertEquals(MainFrame.BUILD_CONFIG_VIEW, selectedCard.get());

        SwingTestUtils.runOnEdt(() -> sidebar.setBuildConfigAttentionNeeded(false));
        assertFalse(sidebar.isBuildConfigAttentionNeeded());
        assertNull(buildConfigBtn.getStatusIcon());
    }
}
