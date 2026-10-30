package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

class NavButtonTest {

    @Test
    void testBusyAndWaitingApprovalPrecedenceTooltipsAndTimerLifecycle() {
        NavButton btn = new NavButton("Feature Request", FontIcon.of(Codicons.GIT_PULL_REQUEST, 16));

        assertFalse(btn.isBusy());
        assertFalse(btn.isWaitingApproval());
        assertNull(btn.getStatusIcon());
        assertNull(btn.getToolTipText());

        btn.setBusy(true);
        assertTrue(btn.isBusy());
        assertEquals(FontAwesomeSolid.HOURGLASS_HALF, btn.getStatusIcon().getIkon());
        assertEquals(NavButton.BUSY_TOOLTIP, btn.getToolTipText());
        assertTrue(btn.getBusyTimer().isRunning());

        for (FontAwesomeSolid expected : new FontAwesomeSolid[]{FontAwesomeSolid.HOURGLASS_END,
                FontAwesomeSolid.HOURGLASS_START, FontAwesomeSolid.HOURGLASS_HALF}) {
            btn.advanceBusyFrame();
            assertEquals(expected, btn.getStatusIcon().getIkon());
        }

        btn.setWaitingApproval(true);
        assertTrue(btn.isWaitingApproval());
        assertEquals(FontAwesomeSolid.HOURGLASS_HALF, btn.getStatusIcon().getIkon());
        assertEquals(NavButton.BUSY_TOOLTIP, btn.getToolTipText());

        btn.setBusy(false);
        assertFalse(btn.isBusy());
        assertFalse(btn.getBusyTimer().isRunning());
        assertTrue(btn.isWaitingApproval());
        assertEquals(Codicons.BELL_DOT, btn.getStatusIcon().getIkon());
        assertEquals(NavButton.WAITING_APPROVAL_TOOLTIP, btn.getToolTipText());

        btn.setAttentionTooltip("Custom attention message");
        assertEquals("Custom attention message", btn.getToolTipText());
        btn.setAttentionTooltip("   ");
        assertEquals(NavButton.WAITING_APPROVAL_TOOLTIP, btn.getToolTipText());

        btn.setWaitingApproval(false);
        assertFalse(btn.isWaitingApproval());
        assertNull(btn.getStatusIcon());
        assertNull(btn.getToolTipText());

        btn.setBusy(true);
        assertTrue(btn.getBusyTimer().isRunning());
        btn.removeNotify();
        assertFalse(btn.getBusyTimer().isRunning());
    }
}
