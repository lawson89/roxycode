package org.roxycode.ui;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

@Singleton
public class ThemeService {

    private static final Logger log = LoggerFactory.getLogger(ThemeService.class);

    // Use a weak set to avoid memory leaks
    private final Set<JTextPane> registeredPanes = Collections.newSetFromMap(new WeakHashMap<>());

    public void registerPane(JTextPane pane) {
        registeredPanes.add(pane);
    }

    public void applyTheme(String themeName, java.awt.Component root, JTextPane... panes) {
        try {
            switch(themeName) {
                case "Dark":
                    UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarkLaf());
                    break;
                case "IntelliJ":
                    UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatIntelliJLaf());
                    break;
                case "Darcula":
                    UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarculaLaf());
                    break;
                case "Light":
                default:
                    UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatLightLaf());
                    break;
            }
            if (root != null) {
                javax.swing.SwingUtilities.updateComponentTreeUI(root);
            } else {
                FlatLaf.updateUI();
            }
            if (panes != null) {
                for (JTextPane pane : panes) {
                    if (pane != null) {
                        if(pane instanceof MarkdownPane) {
                            ((MarkdownPane) pane).updateStyle();
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.error("Theme Error", ex);
        }
    }

}
