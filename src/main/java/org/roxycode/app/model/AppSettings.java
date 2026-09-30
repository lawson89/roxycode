package org.roxycode.app.model;

/**
 * Model representing application settings.
 */
public class AppSettings {
    private String theme = "FlatLaf Light";

    public AppSettings() {}

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }
}