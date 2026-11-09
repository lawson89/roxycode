package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import javax.swing.UIManager;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;

class NavButtonTest {

    @Test
    void testWaitingApprovalBadgeTooltipsNavigationAccessAndSplitPaneGrip() {
        SettingsPanel.applyTheme("macOS Dark");
        assertEquals("grip", UIManager.get("SplitPaneDivider.style"));
        SettingsPanel.applyTheme("FlatLaf Light");
        assertEquals("grip", UIManager.get("SplitPaneDivider.style"));

        SwingTestUtils.runOnEdt(() -> {
            NavButton btn = new NavButton("Feature Request", FontIcon.of(Codicons.GIT_PULL_REQUEST, 16));
            assertFalse(btn.isWaitingApproval());
            assertNull(btn.statusIcon);

            btn.setWaitingApproval(true);
            assertTrue(btn.isWaitingApproval());
            assertEquals(Codicons.BELL_DOT, btn.statusIcon.getIkon());
            assertEquals(NavButton.WAITING_APPROVAL_TOOLTIP, btn.getToolTipText());

            btn.setAttentionTooltip("Custom attention message");
            assertEquals("Custom attention message", btn.getToolTipText());
            btn.setAttentionTooltip("   ");
            assertEquals(NavButton.WAITING_APPROVAL_TOOLTIP, btn.getToolTipText());

            btn.setWaitingApproval(false);
            assertNull(btn.statusIcon);
            assertNull(btn.getToolTipText());

            SidebarPanel sidebar = new SidebarPanel(card -> {
            });
            assertEquals(SidebarPanel.ASK_ROXY_LABEL, sidebar.getCardButton(MainFrame.QA_VIEW).getText());
            assertEquals(SidebarPanel.BUILD_FEATURE_LABEL, sidebar.getCardButton(MainFrame.TASK_VIEW).getText());
            assertEquals(SidebarPanel.CONTEXT_PROMPTS_LABEL,
                    sidebar.getCardButton(MainFrame.CONTEXT_MGR_VIEW).getText());
            assertSame(sidebar.getCardButton(MainFrame.CONTEXT_MGR_VIEW), sidebar.getCardButton(MainFrame.CACHE_VIEW));
            assertSame(sidebar.getCardButton(MainFrame.CONTEXT_MGR_VIEW), sidebar.getCardButton(MainFrame.API_VIEW));
            assertSame(sidebar.getCardButton(MainFrame.SETTINGS_VIEW), sidebar.getCardButton(MainFrame.TOOLS_VIEW));

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
            assertTrue(sidebar.getCardButton(MainFrame.CACHE_VIEW).isEnabled());
            assertFalse(sidebar.getCardButton(MainFrame.QA_VIEW).isEnabled());
            assertFalse(sidebar.getCardButton(MainFrame.TASK_VIEW).isEnabled());
            assertFalse(sidebar.getCardButton(MainFrame.BUILD_CONFIG_VIEW).isEnabled());
            assertEquals(SidebarPanel.MISSING_PROJECT_TOOLTIP,
                    sidebar.getCardButton(MainFrame.BUILD_CONFIG_VIEW).getToolTipText());

            sidebar.updateNavigationAccess(true, true, false);
            assertTrue(sidebar.getCardButton(MainFrame.BUILD_CONFIG_VIEW).isEnabled());
            assertNull(sidebar.getCardButton(MainFrame.BUILD_CONFIG_VIEW).getToolTipText());
            assertTrue(sidebar.getCardButton(MainFrame.QA_VIEW).isEnabled());
            assertNull(sidebar.getCardButton(MainFrame.QA_VIEW).getToolTipText());
            assertFalse(sidebar.getCardButton(MainFrame.TASK_VIEW).isEnabled());
            assertTrue(sidebar.getCardButton(MainFrame.CACHE_VIEW).isEnabled());
            assertNull(sidebar.getCardButton(MainFrame.CACHE_VIEW).getToolTipText());
            assertEquals(SidebarPanel.UNCONFIRMED_BUILD_TOOLTIP,
                    sidebar.getCardButton(MainFrame.TASK_VIEW).getToolTipText());

            sidebar.updateNavigationAccess(true, true, true);
            assertTrue(sidebar.getCardButton(MainFrame.QA_VIEW).isEnabled());
            assertTrue(sidebar.getCardButton(MainFrame.TASK_VIEW).isEnabled());
            assertTrue(sidebar.getCardButton(MainFrame.CACHE_VIEW).isEnabled());
            assertNull(sidebar.getCardButton(MainFrame.QA_VIEW).getToolTipText());
        });
    }
}
