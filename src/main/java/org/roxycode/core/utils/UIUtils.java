package org.roxycode.core.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.swing.FontIcon;
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

    public static void addContextMenu(JTextComponent component) {
        JPopupMenu popupMenu = new JPopupMenu();

        if (component.isEditable()) {
            JMenuItem cutItem = new JMenuItem("Cut");
            cutItem.setIcon(FontIcon.of(MaterialDesignC.CONTENT_CUT, 16));
            cutItem.addActionListener(e -> component.cut());
            popupMenu.add(cutItem);
        }

        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setIcon(FontIcon.of(MaterialDesignC.CONTENT_COPY, 16));
        copyItem.addActionListener(e -> component.copy());
        popupMenu.add(copyItem);

        if (component.isEditable()) {
            JMenuItem pasteItem = new JMenuItem("Paste");
            pasteItem.setIcon(FontIcon.of(MaterialDesignC.CONTENT_PASTE, 16));
            pasteItem.addActionListener(e -> component.paste());
            popupMenu.add(pasteItem);
        }

        popupMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                boolean hasSelection = component.getSelectedText() != null && !component.getSelectedText().isEmpty();
                copyItem.setEnabled(hasSelection);
                if (component.isEditable()) {
                    JMenuItem cutItem = (JMenuItem) popupMenu.getComponent(0);
                    cutItem.setEnabled(hasSelection);
                }
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        component.setComponentPopupMenu(popupMenu);
    }

}