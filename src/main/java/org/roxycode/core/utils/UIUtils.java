package org.roxycode.core.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;

public class UIUtils {

    private static final Logger LOG = LoggerFactory.getLogger(UIUtils.class);


    private static Point initialScreenCenter;

    public static void setInitialScreenCenter(Point center) {
        LOG.info("Saving initial screen center to: {}", center);
        initialScreenCenter = center;
    }

    public static void centerDialog(JDialog dialog, Component parent) {
        dialog.setModal(true);
        dialog.pack();
        if (parent != null && parent.isShowing()) {
            LOG.info("Centering dialog relative to parent: {}", parent);
            dialog.setLocationRelativeTo(parent);
        } else {
            LOG.info("Centering dialog relative to screen");
            dialog.setLocationRelativeTo(null);
        }

        // Fallback if it ends up at (0,0) or off-screen, and we have a cached center
        if (dialog.getX() == 0 && dialog.getY() == 0 && initialScreenCenter != null) {
            LOG.info("Setting initial screen center to: {}", initialScreenCenter);
            dialog.setLocation(initialScreenCenter.x - dialog.getWidth() / 2,
                    initialScreenCenter.y - dialog.getHeight() / 2);
        }

        LOG.info("Dialog location to: {}", dialog.getLocation());
    }
}
