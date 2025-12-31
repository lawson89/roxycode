package org.roxycode.ui;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.UIManager;

@Singleton
public class ThemeService {

    private static final Logger log = LoggerFactory.getLogger(ThemeService.class);

    public void applyTheme(String themeName, MarkdownPane... panes) {
        try {
            switch(themeName) {
                case "Dark":
                    UIManager.setLookAndFeel(new FlatDarkLaf());
                    break;
                case "IntelliJ":
                    UIManager.setLookAndFeel(new FlatIntelliJLaf());
                    break;
                case "Darcula":
                    UIManager.setLookAndFeel(new FlatDarculaLaf());
                    break;
                case "Light":
                default:
                    UIManager.setLookAndFeel(new FlatLightLaf());
                    break;
            }
            FlatLaf.updateUI();
            
            if (panes != null) {
                for (MarkdownPane pane : panes) {
                    if (pane != null) {
                        pane.updateStyle();
                    }
                }
            }
        } catch (Exception ex) {
            log.error("Theme Error", ex);
        }
    }
}
