package org.roxycode.app.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reusable Swing panel that renders an image clipped to a rounded rectangle with a theme-aware
 * border, caching the scaled rounded {@link BufferedImage} across repaints of the same dimensions.
 */
final class RoundedImagePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(RoundedImagePanel.class);
    private static final int INSET = 2;

    private final transient Image image;
    private final int arc;
    private transient BufferedImage cachedBuffer;
    private int cachedWidth = -1;
    private int cachedHeight = -1;

    RoundedImagePanel(String resourcePath, int size, int arc) {
        this(loadClasspathImage(resourcePath), size, arc);
    }

    RoundedImagePanel(Image image, int size, int arc) {
        this.image = image;
        this.arc = arc;
        setPreferredSize(new Dimension(size, size));
        setOpaque(false);
    }

    static Image loadClasspathImage(String resourcePath) {
        try {
            URL url = RoundedImagePanel.class.getResource(resourcePath);
            if (url != null) {
                return ImageIO.read(url);
            }
        } catch (IOException e) {
            log.warn("Failed to load image resource {}: {}", resourcePath, e.getMessage());
        }
        return null;
    }

    Image getImage() {
        return image;
    }

    BufferedImage getCachedBuffer() {
        return cachedBuffer;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            if (image != null) {
                BufferedImage buffer = getOrCreateRoundedBuffer(width, height);
                g2.drawImage(buffer, 0, 0, null);
            } else {
                g2.setColor(
                        SwingHtmlUtils.getUiColor(
                                new Color(100, 100, 255), "Component.accentColor"));
                g2.fillRoundRect(INSET, INSET, width - (2 * INSET), height - (2 * INSET), arc, arc);
            }

            g2.setColor(SwingHtmlUtils.getUiColor(new Color(80, 80, 80), "Component.borderColor"));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRoundRect(
                    INSET, INSET, width - (2 * INSET) - 1, height - (2 * INSET) - 1, arc, arc);
        } finally {
            g2.dispose();
        }
    }

    private BufferedImage getOrCreateRoundedBuffer(int width, int height) {
        if (cachedBuffer != null && cachedWidth == width && cachedHeight == height) {
            return cachedBuffer;
        }
        BufferedImage buffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gBuffer = buffer.createGraphics();
        try {
            gBuffer.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gBuffer.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            gBuffer.setRenderingHint(
                    RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            gBuffer.setColor(Color.BLACK);
            gBuffer.fillRoundRect(
                    INSET, INSET, width - (2 * INSET), height - (2 * INSET), arc, arc);
            gBuffer.setComposite(AlphaComposite.SrcIn);
            gBuffer.drawImage(image, 0, 0, width, height, null);
        } finally {
            gBuffer.dispose();
        }
        cachedBuffer = buffer;
        cachedWidth = width;
        cachedHeight = height;
        return buffer;
    }
}
