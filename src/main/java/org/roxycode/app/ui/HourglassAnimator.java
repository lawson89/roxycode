package org.roxycode.app.ui;

import javax.swing.Timer;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

/** Reusable animator for cycling a FontAwesome hourglass {@link FontIcon} on the Swing EDT. */
public final class HourglassAnimator {
    static final int DEFAULT_ICON_SIZE = 13;
    static final int ANIMATION_DELAY_MS = 500;
    private static final FontAwesomeSolid[] HOURGLASS_FRAMES = {
        FontAwesomeSolid.HOURGLASS_START,
        FontAwesomeSolid.HOURGLASS_HALF,
        FontAwesomeSolid.HOURGLASS_END
    };

    private final int iconSize;
    private final Runnable onFrameChanged;
    private FontIcon icon;
    private Timer timer;
    private int frameIndex = 1;

    public HourglassAnimator(Runnable onFrameChanged) {
        this(DEFAULT_ICON_SIZE, onFrameChanged);
    }

    public HourglassAnimator(int iconSize, Runnable onFrameChanged) {
        this.iconSize = iconSize;
        this.onFrameChanged = onFrameChanged;
    }

    /**
     * Resets the hourglass icon to {@link FontAwesomeSolid#HOURGLASS_HALF}, starts the animation
     * timer if not already running, and returns the managed {@link FontIcon}.
     */
    public FontIcon start() {
        frameIndex = 1;
        if (icon == null) {
            icon = FontIcon.of(HOURGLASS_FRAMES[frameIndex], iconSize);
        } else {
            icon.setIkon(HOURGLASS_FRAMES[frameIndex]);
        }
        if (timer == null) {
            timer = new Timer(ANIMATION_DELAY_MS, e -> advanceFrame());
        }
        if (!timer.isRunning()) {
            timer.start();
        }
        return icon;
    }

    /** Stops the animation timer if it is currently running. */
    public void stop() {
        if (timer != null && timer.isRunning()) {
            timer.stop();
        }
    }

    /** Returns {@code true} if the animation timer is currently running. */
    public boolean isRunning() {
        return timer != null && timer.isRunning();
    }

    /** Advances the hourglass icon to the next frame and invokes the frame-change callback. */
    public void advanceFrame() {
        if (icon == null) {
            stop();
            return;
        }
        frameIndex = (frameIndex + 1) % HOURGLASS_FRAMES.length;
        icon.setIkon(HOURGLASS_FRAMES[frameIndex]);
        if (onFrameChanged != null) {
            onFrameChanged.run();
        }
    }

    public FontIcon getIcon() {
        return icon;
    }

    Timer getTimer() {
        return timer;
    }
}
