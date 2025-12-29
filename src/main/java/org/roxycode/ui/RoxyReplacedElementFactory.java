package org.roxycode.ui;

import org.w3c.dom.Element;
import org.xhtmlrenderer.layout.LayoutContext;
import org.xhtmlrenderer.render.BlockBox;
import org.xhtmlrenderer.simple.extend.FormSubmissionListener;
import org.xhtmlrenderer.swing.SwingReplacedElement;
import org.xhtmlrenderer.extend.ReplacedElement;
import org.xhtmlrenderer.extend.ReplacedElementFactory;
import org.xhtmlrenderer.extend.UserAgentCallback;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class RoxyReplacedElementFactory implements ReplacedElementFactory {
    private final ReplacedElementFactory superFactory;
    private final Map<String, Image> imageCache = new HashMap<>();

    public RoxyReplacedElementFactory(ReplacedElementFactory superFactory) {
        this.superFactory = superFactory;
    }

    public void putImage(String url, Image image) {
        imageCache.put(url, image);
    }

    @Override
    public ReplacedElement createReplacedElement(LayoutContext c, BlockBox box, UserAgentCallback uac, int cssWidth, int cssHeight) {
        Element e = box.getElement();
        if (e == null) {
            return null;
        }

        String nodeName = e.getNodeName();
        if ("img".equals(nodeName)) {
            String src = e.getAttribute("src");
            if (imageCache.containsKey(src)) {
                Image image = imageCache.get(src);
                if (image != null) {
                    // 1. Create label
                    ImageIcon icon = new ImageIcon(image);
                    JLabel label = new JLabel(icon);
                    label.setOpaque(false);

                    // 2. Handle scaling
                    if (cssWidth > -1 || cssHeight > -1) {
                        int w = (cssWidth > -1) ? cssWidth : icon.getIconWidth();
                        int h = (cssHeight > -1) ? cssHeight : icon.getIconHeight();

                        if (w != icon.getIconWidth() || h != icon.getIconHeight()) {
                            Image scaled = image.getScaledInstance(w, h, Image.SCALE_SMOOTH);
                            label.setIcon(new ImageIcon(scaled));
                        }
                    }

                    // 3. FIX: Pass the Dimension as the second argument
                    return new SwingReplacedElement(label, label.getPreferredSize());
                }
            }
        }

        return superFactory.createReplacedElement(c, box, uac, cssWidth, cssHeight);
    }

    @Override
    public void reset() {
        superFactory.reset();
    }

    @Override
    public void remove(Element e) {
        superFactory.remove(e);
    }

    @Override
    public void setFormSubmissionListener(FormSubmissionListener listener) {
        superFactory.setFormSubmissionListener(listener);
    }
}