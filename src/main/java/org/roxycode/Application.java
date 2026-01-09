package org.roxycode;

import io.micronaut.runtime.Micronaut;
import org.roxycode.ui.MainFrame;
import javax.swing.SwingUtilities;

public class Application {

    public static void main(String[] args) {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        // Set up FlatLaf before any UI components are created
        com.formdev.flatlaf.FlatLightLaf.setup();

        // 1. Initialize Micronaut Context
        var context = io.micronaut.runtime.Micronaut.run(Application.class, args);
        org.roxycode.ui.MainFrame mainFrame = context.getBean(org.roxycode.ui.MainFrame.class);
        javax.swing.SwingUtilities.invokeLater(mainFrame);
    }
}
