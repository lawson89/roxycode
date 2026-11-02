package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

class NavButtonTest {

    @Test
    void testBusyAndWaitingApprovalPrecedenceTooltipsAndTimerLifecycle() {
        SwingTestUtils.runOnEdt(() -> {
            NavButton btn = new NavButton("Feature Request", FontIcon.of(Codicons.GIT_PULL_REQUEST, 16));
            assertFalse(btn.isBusy());
            assertFalse(btn.isWaitingApproval());
            assertNull(btn.statusIcon);

            btn.setBusy(true);
            assertTrue(btn.isBusy());
            assertEquals(FontAwesomeSolid.HOURGLASS_HALF, btn.statusIcon.getIkon());
            assertEquals(NavButton.BUSY_TOOLTIP, btn.getToolTipText());
            assertTrue(btn.busyTimer.isRunning());
            btn.busyTimer.stop();

            btn.advanceBusyFrame();
            assertEquals(FontAwesomeSolid.HOURGLASS_END, btn.statusIcon.getIkon());

            btn.setWaitingApproval(true);
            assertEquals(FontAwesomeSolid.HOURGLASS_HALF, btn.statusIcon.getIkon());
            assertEquals(NavButton.BUSY_TOOLTIP, btn.getToolTipText());

            btn.setBusy(false);
            assertFalse(btn.busyTimer.isRunning());
            assertEquals(Codicons.BELL_DOT, btn.statusIcon.getIkon());
            assertEquals(NavButton.WAITING_APPROVAL_TOOLTIP, btn.getToolTipText());

            btn.setAttentionTooltip("Custom attention message");
            assertEquals("Custom attention message", btn.getToolTipText());
            btn.setAttentionTooltip("   ");
            assertEquals(NavButton.WAITING_APPROVAL_TOOLTIP, btn.getToolTipText());

            btn.setWaitingApproval(false);
            assertNull(btn.statusIcon);
            assertNull(btn.getToolTipText());

            btn.setBusy(true);
            assertTrue(btn.busyTimer.isRunning());
            btn.removeNotify();
            assertFalse(btn.busyTimer.isRunning());

            SidebarPanel sidebar = new SidebarPanel(card -> {
            });
            sidebar.updateNavigationAccess(false, false, false);
            assertTrue(sidebar.getCardButton(MainFrame.SETTINGS_VIEW).isEnabled());
            assertTrue(sidebar.getCardButton(MainFrame.TOOLS_VIEW).isEnabled());
            assertFalse(sidebar.getCardButton(MainFrame.QA_VIEW).isEnabled());
            assertFalse(sidebar.getCardButton(MainFrame.TASK_VIEW).isEnabled());
            assertFalse(sidebar.getCardButton(MainFrame.CACHE_VIEW).isEnabled());
            assertFalse(sidebar.getCardButton(MainFrame.API_VIEW).isEnabled());
            assertFalse(sidebar.getCardButton(MainFrame.CONTEXT_MGR_VIEW).isEnabled());
            assertFalse(sidebar.getCardButton(MainFrame.BUILD_CONFIG_VIEW).isEnabled());
            assertEquals(SidebarPanel.MISSING_API_KEY_TOOLTIP,
                    sidebar.getCardButton(MainFrame.QA_VIEW).getToolTipText());

            sidebar.updateNavigationAccess(true, false, false);
            assertTrue(sidebar.getCardButton(MainFrame.SETTINGS_VIEW).isEnabled());
            assertTrue(sidebar.getCardButton(MainFrame.TOOLS_VIEW).isEnabled());
            assertTrue(sidebar.getCardButton(MainFrame.API_VIEW).isEnabled());
            assertTrue(sidebar.getCardButton(MainFrame.CONTEXT_MGR_VIEW).isEnabled());
            assertFalse(sidebar.getCardButton(MainFrame.QA_VIEW).isEnabled());
            assertFalse(sidebar.getCardButton(MainFrame.TASK_VIEW).isEnabled());
            assertFalse(sidebar.getCardButton(MainFrame.CACHE_VIEW).isEnabled());
            assertFalse(sidebar.getCardButton(MainFrame.BUILD_CONFIG_VIEW).isEnabled());
            assertEquals(SidebarPanel.MISSING_PROJECT_TOOLTIP,
                    sidebar.getCardButton(MainFrame.BUILD_CONFIG_VIEW).getToolTipText());

            sidebar.updateNavigationAccess(true, true, false);
            assertTrue(sidebar.getCardButton(MainFrame.BUILD_CONFIG_VIEW).isEnabled());
            assertNull(sidebar.getCardButton(MainFrame.BUILD_CONFIG_VIEW).getToolTipText());
            assertFalse(sidebar.getCardButton(MainFrame.QA_VIEW).isEnabled());
            assertFalse(sidebar.getCardButton(MainFrame.TASK_VIEW).isEnabled());
            assertFalse(sidebar.getCardButton(MainFrame.CACHE_VIEW).isEnabled());
            assertEquals(SidebarPanel.UNCONFIRMED_BUILD_TOOLTIP,
                    sidebar.getCardButton(MainFrame.QA_VIEW).getToolTipText());

            sidebar.updateNavigationAccess(true, true, true);
            assertTrue(sidebar.getCardButton(MainFrame.QA_VIEW).isEnabled());
            assertTrue(sidebar.getCardButton(MainFrame.TASK_VIEW).isEnabled());
            assertTrue(sidebar.getCardButton(MainFrame.CACHE_VIEW).isEnabled());
            assertNull(sidebar.getCardButton(MainFrame.QA_VIEW).getToolTipText());
        });
    }
}
