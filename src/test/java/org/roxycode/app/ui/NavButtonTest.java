package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;

class NavButtonTest {

    @Test
    void testWaitingApprovalBadgeBusyDotTooltipsNavigationAccessAndSplitPaneGrip() {
        SettingsPanel.applyTheme("macOS Dark");
        assertEquals("grip", UIManager.get("SplitPaneDivider.style"));
        assertEquals(UiComponents.INFO_BLUE_DARK, UiComponents.resolveInfoIconColor());
        SettingsPanel.applyTheme("FlatLaf Light");
        assertEquals("grip", UIManager.get("SplitPaneDivider.style"));
        assertEquals(UiComponents.INFO_BLUE_LIGHT, UiComponents.resolveInfoIconColor());

        SwingTestUtils.runOnEdt(() -> {
            NavButton btn = new NavButton("Feature Request", FontIcon.of(Codicons.GIT_PULL_REQUEST, 16));
            btn.setSize(180, 36);
            assertFalse(btn.isWaitingApproval());
            assertFalse(btn.isBusy());
            assertNull(btn.statusIcon);

            btn.setBusy(true);
            assertTrue(btn.isBusy());
            assertNull(btn.statusIcon);
            assertEquals(NavButton.BUSY_TOOLTIP, btn.getToolTipText());

            // Painting busy dot should succeed without throwing
            BufferedImage img = new BufferedImage(180, 36, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            try {
                btn.paint(g2);
            } finally {
                g2.dispose();
            }

            // WaitingApproval takes precedence over busy
            btn.setWaitingApproval(true);
            assertTrue(btn.isWaitingApproval());
            assertTrue(btn.isBusy());
            assertEquals(Codicons.BELL_DOT, btn.statusIcon.getIkon());
            assertEquals(NavButton.WAITING_APPROVAL_TOOLTIP, btn.getToolTipText());

            btn.setAttentionTooltip("Custom attention message");
            assertEquals("Custom attention message", btn.getToolTipText());
            btn.setAttentionTooltip("   ");
            assertEquals(NavButton.WAITING_APPROVAL_TOOLTIP, btn.getToolTipText());

            // Clearing waitingApproval while still busy restores busy tooltip and dot state
            btn.setWaitingApproval(false);
            assertNull(btn.statusIcon);
            assertTrue(btn.isBusy());
            assertEquals(NavButton.BUSY_TOOLTIP, btn.getToolTipText());

            btn.setBusy(false);
            assertFalse(btn.isBusy());
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

            sidebar.setQaBusy(true);
            assertTrue(sidebar.isQaBusy());
            sidebar.setQaBusy(false);
            assertFalse(sidebar.isQaBusy());

            sidebar.setTaskBusy(true);
            assertTrue(sidebar.isTaskBusy());
            sidebar.setTaskBusy(false);
            assertFalse(sidebar.isTaskBusy());

            sidebar.setQaWaitingApproval(true);
            assertTrue(sidebar.isQaWaitingApproval());
            sidebar.setQaWaitingApproval(false);
            assertFalse(sidebar.isQaWaitingApproval());

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
