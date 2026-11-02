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
        });
    }
}
