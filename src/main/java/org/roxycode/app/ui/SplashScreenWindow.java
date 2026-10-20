package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Theme-aware startup splash screen window. */
public final class SplashScreenWindow extends JWindow {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(SplashScreenWindow.class);

    private final JLabel statusLabel;
    private final JProgressBar progressBar;
    private transient Image logoImage;

    public SplashScreenWindow() {
        this.statusLabel = new JLabel("Starting Spring context...");
        this.progressBar = new JProgressBar();
        initComponents();
    }

    private void initComponents() {
        loadLogoImage();

        JPanel rootPanel =
                new JPanel(
                        new MigLayout(
                                "fill, insets 24 32 24 32, wrap 1",
                                "[grow, center]",
                                "[]12[]4[]16[]8[]"));

        Color borderColor = UIManager.getColor("Component.borderColor");
        if (borderColor == null) {
            borderColor = new Color(80, 80, 80);
        }
        rootPanel.setBorder(BorderFactory.createLineBorder(borderColor, 1));

        JPanel logoPanel = createLogoPanel();

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

    private void loadLogoImage() {
        try {
            URL url = getClass().getResource("/static/images/roxy.png");
            if (url != null) {
                logoImage = ImageIO.read(url);
            }
        } catch (IOException e) {
            log.warn("Failed to load splash logo image: {}", e.getMessage());
        }
    }

    private JPanel createLogoPanel() {
        JPanel logoPanel =
                new JPanel() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    protected void paintComponent(Graphics g) {
                        super.paintComponent(g);
                        Graphics2D g2 = (Graphics2D) g.create();
                        try {
                            g2.setRenderingHint(
                                    RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_ON);
                            g2.setRenderingHint(
                                    RenderingHints.KEY_INTERPOLATION,
                                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                            g2.setRenderingHint(
                                    RenderingHints.KEY_RENDERING,
                                    RenderingHints.VALUE_RENDER_QUALITY);

                            int width = getWidth();
                            int height = getHeight();
                            int arc = 22;
                            int inset = 2;

                            if (logoImage != null) {
                                BufferedImage buffer =
                                        new BufferedImage(
                                                width, height, BufferedImage.TYPE_INT_ARGB);
                                Graphics2D gBuffer = buffer.createGraphics();
                                try {
                                    gBuffer.setRenderingHint(
                                            RenderingHints.KEY_ANTIALIASING,
                                            RenderingHints.VALUE_ANTIALIAS_ON);
                                    gBuffer.setColor(Color.BLACK);
                                    gBuffer.fillRoundRect(
                                            inset,
                                            inset,
                                            width - (2 * inset),
                                            height - (2 * inset),
                                            arc,
                                            arc);

                                    gBuffer.setComposite(AlphaComposite.SrcIn);
                                    gBuffer.drawImage(logoImage, 0, 0, width, height, null);
                                } finally {
                                    gBuffer.dispose();
                                }
                                g2.drawImage(buffer, 0, 0, null);
                            } else {
                                g2.setColor(UIManager.getColor("Component.accentColor"));
                                if (g2.getColor() == null) {
                                    g2.setColor(new Color(100, 100, 255));
                                }
                                g2.fillRoundRect(
                                        inset,
                                        inset,
                                        width - (2 * inset),
                                        height - (2 * inset),
                                        arc,
                                        arc);
                            }

                            Color borderColor = UIManager.getColor("Component.borderColor");
                            if (borderColor == null) {
                                borderColor = new Color(80, 80, 80);
                            }
                            g2.setColor(borderColor);
                            g2.setStroke(new BasicStroke(1.5f));
                            g2.drawRoundRect(
                                    inset,
                                    inset,
                                    width - (2 * inset) - 1,
                                    height - (2 * inset) - 1,
                                    arc,
                                    arc);
                        } finally {
                            g2.dispose();
                        }
                    }
                };
        logoPanel.setPreferredSize(new Dimension(80, 80));
        logoPanel.setOpaque(false);
        return logoPanel;
    }

    public void setStatus(String status) {
        if (SwingUtilities.isEventDispatchThread()) {
            statusLabel.setText(status);
        } else {
            SwingUtilities.invokeLater(() -> statusLabel.setText(status));
        }
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
