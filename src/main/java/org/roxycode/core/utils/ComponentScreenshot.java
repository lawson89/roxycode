package org.roxycode.core.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

public class ComponentScreenshot {

    private static final Logger log = LoggerFactory.getLogger(ComponentScreenshot.class);

    public static BufferedImage captureComponent(Component component) {
        // 1. Ensure Visibility
        if (!component.isVisible()) {
            component.setVisible(true);
        }

        // 2. Handle Layout (using pack() via a utility or container is safer,
        // but your existing logic is okay for simple cases if we add addNotify)
        if (component.getWidth() == 0 || component.getHeight() == 0) {
            component.setSize(component.getPreferredSize());
            component.addNotify(); // Creates the peer
            component.validate();
            component.doLayout();
        }

        // 3. Create Image
        BufferedImage image = new BufferedImage(
                Math.max(1, component.getWidth()), // Prevent 0-width error
                Math.max(1, component.getHeight()),
                BufferedImage.TYPE_INT_RGB
        );

        Graphics2D g2d = image.createGraphics();

        // 4. FIX: Handle Double Buffering
        RepaintManager rm = RepaintManager.currentManager(component);
        boolean doubleBufferState = rm.isDoubleBufferingEnabled();
        rm.setDoubleBufferingEnabled(false);

        try {
            // 5. FIX: Paint background explicitly (in case component is transparent)
            g2d.setColor(component.getBackground() != null ? component.getBackground() : Color.WHITE);
            g2d.fillRect(0, 0, image.getWidth(), image.getHeight());

            // 6. Print
            component.print(g2d);
        } finally {
            g2d.dispose();
            rm.setDoubleBufferingEnabled(doubleBufferState);
        }

        return image;
    }
}