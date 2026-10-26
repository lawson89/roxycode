package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.util.Edt;

/** Theme-aware startup splash screen window. */
public final class SplashScreenWindow extends JWindow {
    private static final long serialVersionUID = 1L;
    private static final String LOGO_RESOURCE_PATH = "/static/images/roxy.png";

    private final JLabel statusLabel;
    private final JProgressBar progressBar;
    private transient Image logoImage;

    public SplashScreenWindow() {
        this.statusLabel = new JLabel("Starting Spring context...");
        this.progressBar = new JProgressBar();
        initComponents();
    }

    private void initComponents() {
        logoImage = RoundedImagePanel.loadClasspathImage(LOGO_RESOURCE_PATH);

        JPanel rootPanel =
                new JPanel(
                        new MigLayout(
                                "fill, insets 24 32 24 32, wrap 1",
                                "[grow, center]",
                                "[]12[]4[]16[]8[]"));

        Color borderColor =
                SwingHtmlUtils.getUiColor(new Color(80, 80, 80), "Component.borderColor");
        rootPanel.setBorder(BorderFactory.createLineBorder(borderColor, 1));

        JPanel logoPanel = new RoundedImagePanel(logoImage, 80, 22);

        JLabel titleLabel = new JLabel("RoxyCode");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 20f));

        JLabel subtitleLabel = new JLabel("AI Coding Harness");
        subtitleLabel.setFont(subtitleLabel.getFont().deriveFont(Font.PLAIN, 12f));
        subtitleLabel.putClientProperty(
                FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");

        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        statusLabel.putClientProperty(
                FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");

        progressBar.setIndeterminate(true);

        rootPanel.add(logoPanel, "w 80!, h 80!, center");
        rootPanel.add(titleLabel, "center");
        rootPanel.add(subtitleLabel, "center");
        rootPanel.add(statusLabel, "center");
        rootPanel.add(progressBar, "growx, w 260::, h 6!");

        setContentPane(rootPanel);
        setSize(420, 240);
        setMinimumSize(new Dimension(420, 240));
        setLocationRelativeTo(null);
    }

    @Override
    public void dispose() {
        progressBar.setIndeterminate(false);
        super.dispose();
    }

    public void setStatus(String status) {
        Edt.run(() -> statusLabel.setText(status));
    }

    JLabel getStatusLabel() {
        return statusLabel;
    }

    JProgressBar getProgressBar() {
        return progressBar;
    }

    Image getLogoImage() {
        return logoImage;
    }
}
