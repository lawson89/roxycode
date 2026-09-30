package org.roxycode.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.roxycode.app.ui.MainFrame;
import javax.swing.SwingUtilities;

@SpringBootApplication
public class AppApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(AppApplication.class)
                .headless(false)
                .run(args);

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }

}
