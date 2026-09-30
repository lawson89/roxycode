package org.roxycode.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.roxycode.app.ui.MainFrame;
import org.roxycode.app.service.SettingsService;
import javax.swing.SwingUtilities;

@SpringBootApplication
public class RoxyCode {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(RoxyCode.class)
                .headless(false)
                .run(args);

        SwingUtilities.invokeLater(() -> {
            SettingsService settingsService = context.getBean(SettingsService.class);
            MainFrame frame = new MainFrame(settingsService);
            frame.setVisible(true);
        });
    }

}