package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
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

        btn.setWaitingApproval(false);
        assertFalse(btn.isWaitingApproval());
        assertNull(btn.getStatusIcon());
        assertNull(btn.getToolTipText());

        btn.setBusy(true);
        assertTrue(btn.getBusyTimer().isRunning());
        btn.removeNotify();
        assertFalse(btn.getBusyTimer().isRunning());
    }

    @Test
    void testHourglassAnimatorStandaloneLifecycleAndFrameCycling() {
        AtomicInteger frameCallbacks = new AtomicInteger(0);
        HourglassAnimator animator = new HourglassAnimator(frameCallbacks::incrementAndGet);

        assertFalse(animator.isRunning());
        assertNull(animator.getIcon());
        animator.advanceFrame();
        assertEquals(0, frameCallbacks.get());

        FontIcon icon = animator.start();
        assertSame(icon, animator.getIcon());
        assertEquals(HourglassAnimator.DEFAULT_ICON_SIZE, icon.getIconSize());
        assertEquals(FontAwesomeSolid.HOURGLASS_HALF, icon.getIkon());
        assertTrue(animator.isRunning());
        assertEquals(HourglassAnimator.ANIMATION_DELAY_MS, animator.getTimer().getDelay());

        FontAwesomeSolid[] expectedFrames = {FontAwesomeSolid.HOURGLASS_END, FontAwesomeSolid.HOURGLASS_START,
                FontAwesomeSolid.HOURGLASS_HALF};
        for (int i = 0; i < expectedFrames.length; i++) {
            animator.advanceFrame();
            assertEquals(expectedFrames[i], icon.getIkon());
            assertEquals(i + 1, frameCallbacks.get());
        }

        animator.stop();
        assertFalse(animator.isRunning());
        animator.advanceFrame();
        assertEquals(FontAwesomeSolid.HOURGLASS_END, icon.getIkon());
        assertSame(icon, animator.start());
        assertEquals(FontAwesomeSolid.HOURGLASS_HALF, icon.getIkon());
        assertTrue(animator.isRunning());
        animator.stop();
    }
}
