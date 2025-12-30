package org.roxycode.core.utils;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

public class ComponentScreenshot {

    public static BufferedImage captureComponent(Component component) {
        // 1. Make sure the component has a size
        // If the frame was never packed/shown, its size might be 0,0
        if (component.getWidth() == 0 || component.getHeight() == 0) {
            component.setSize(component.getPreferredSize());
            // Force layout logic to run if it hasn't already
            component.doLayout();
        }

        // 2. Create an empty image buffer of the same size
        BufferedImage image = new BufferedImage(
                component.getWidth(),
                component.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );

        // 3. Create a Graphics object to draw into the image
        Graphics2D g2d = image.createGraphics();

        // 4. Tell the component to print itself onto the image
        // .print() is often safer than .paint() for off-screen rendering
        component.print(g2d);

        g2d.dispose();
        return image;
    }
}