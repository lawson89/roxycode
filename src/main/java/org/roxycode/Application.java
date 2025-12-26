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

        // 2. Check if we should launch GUI (default if no CLI args)
        // If your CLI logic uses a bean listener, that runs first.
        // For the UI phase, we explicitly launch the frame.
        if (!context.getEnvironment().containsProperty("cli")) {
            MainFrame mainFrame = context.getBean(MainFrame.class);
            SwingUtilities.invokeLater(mainFrame);
        }
    }
}