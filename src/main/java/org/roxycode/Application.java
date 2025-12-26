package org.roxycode;

import io.micronaut.runtime.Micronaut;
import org.roxycode.ui.MainFrame;

import javax.swing.SwingUtilities;

public class Application {

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        // 1. Initialize Micronaut Context
        // We use try-with-resources to ensure context closes if app exits
        var context = Micronaut.run(Application.class, args);

        MainFrame mainFrame = context.getBean(MainFrame.class);
        SwingUtilities.invokeLater(mainFrame);
    }
}