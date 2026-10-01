package org.roxycode.app.service;

import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.springframework.stereotype.Service;

import java.awt.Color;
import javax.swing.UIManager;

/**
 * Service providing environment and system information.
 */
@Service
public class EnvironmentService {

    public String getOsName() {
        return System.getProperty("os.name");
    }

    public String getOsVersion() {
        return System.getProperty("os.version");
    }

    public String getJavaVersion() {
        return System.getProperty("java.version");
    }

    public String getCurrentUser() {
        return System.getProperty("user.name");
    }

    public long getMemoryUsageMb() {
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
    }

    public long getTotalMemoryMb() {
        return Runtime.getRuntime().totalMemory() / (1024 * 1024);
    }

    public FontIcon getOsIcon() {
        String os = getOsName().toLowerCase();
        Codicons icon;
        if (os.contains("win")) {
            icon = Codicons.WINDOW;
        } else {
            icon = Codicons.TERMINAL;
        }
        
        FontIcon fontIcon = FontIcon.of(icon);
        fontIcon.setIconSize(14);
        Color color = UIManager.getColor("Label.foreground");
        if (color != null) {
            fontIcon.setIconColor(color);
        }
        return fontIcon;
    }
}