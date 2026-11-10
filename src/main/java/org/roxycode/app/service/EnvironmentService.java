package org.roxycode.app.service;

import java.awt.Color;
import javax.swing.UIManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.springframework.stereotype.Service;

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

    public static String resolveCurrentUser(EnvironmentService environmentService) {
        if (environmentService != null) {
            return StringUtils.defaultIfBlank(environmentService.getCurrentUser(), "User");
        }
        return "User";
    }

    public long getMemoryUsageMb() {
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
    }

    public long getTotalMemoryMb() {
        return Runtime.getRuntime().totalMemory() / (1024 * 1024);
    }

    public FontIcon getOsIcon() {
        Codicons iconCode = Codicons.TERMINAL;
        if (SystemUtils.IS_OS_WINDOWS) {
            iconCode = Codicons.WINDOW;
        }
        FontIcon fontIcon = FontIcon.of(iconCode, 14);
        Color color = UIManager.getColor("Label.foreground");
        if (color != null) {
            fontIcon.setIconColor(color);
        }
        return fontIcon;
    }
}
