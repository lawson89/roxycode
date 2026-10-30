package org.roxycode.app.ui;

import javax.swing.Timer;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

public final class HourglassAnimator {
    static final int DEFAULT_ICON_SIZE = 13;
    static final int ANIMATION_DELAY_MS = 500;
    private static final FontAwesomeSolid[] HOURGLASS_FRAMES = {FontAwesomeSolid.HOURGLASS_START,
            FontAwesomeSolid.HOURGLASS_HALF, FontAwesomeSolid.HOURGLASS_END};

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

    public void stop() {
        if (timer != null && timer.isRunning()) {
            timer.stop();
        }
    }

    public boolean isRunning() {
        return timer != null && timer.isRunning();
    }

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
