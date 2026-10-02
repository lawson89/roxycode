package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;

/**
 * Component displaying user profile information in the sidebar.
 */
public class UserProfileComponent extends JPanel {
    private static final Logger log = LoggerFactory.getLogger(UserProfileComponent.class);
    private Image avatarImage;

    /**
     * Creates a new UserProfileComponent.
     * @param name the user name
     * @param email the user email or identifier
     */
    public UserProfileComponent(String name, String email) {
        setLayout(new MigLayout("fillx, insets 20, wrap 1", "[center]", "[]15[]2[]"));
        setOpaque(false);

        // Load avatar image
        try {
            URL url = getClass().getResource("/static/images/roxy.png");
            if (url != null) {
                avatarImage = ImageIO.read(url);
            }
        } catch (IOException e) {
            log.warn("Failed to load user avatar image: {}", e.getMessage());
            // Handle missing avatar image gracefully (fallback to colored oval)
        }

        // Avatar panel
        JPanel avatar = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

                    int width = getWidth();
                    int height = getHeight();
                    int arc = 32;
                    int inset = 2;

                    if (avatarImage != null) {
                        // Create buffered image for smooth alpha clipping
                        BufferedImage buffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D gBuffer = buffer.createGraphics();
                        try {
                            gBuffer.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                            gBuffer.setColor(Color.BLACK);
                            gBuffer.fillRoundRect(inset, inset, width - (2 * inset), height - (2 * inset), arc, arc);
                            
                            gBuffer.setComposite(AlphaComposite.SrcIn);
                            gBuffer.drawImage(avatarImage, 0, 0, width, height, null);
                        } finally {
                            gBuffer.dispose();
                        }
                        g2.drawImage(buffer, 0, 0, null);
                    } else {
                        g2.setColor(UIManager.getColor("Component.accentColor"));
                        if (g2.getColor() == null) {
                            g2.setColor(new Color(100, 100, 255));
                        }
                        g2.fillRoundRect(inset, inset, width - (2 * inset), height - (2 * inset), arc, arc);
                    }

                    // Draw rounded border
                    Color borderColor = UIManager.getColor("Component.borderColor");
                    if (borderColor == null) {
                        borderColor = new Color(80, 80, 80);
                    }
                    g2.setColor(borderColor);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawRoundRect(inset, inset, width - (2 * inset) - 1, height - (2 * inset) - 1, arc, arc);
                } finally {
                    g2.dispose();
                }
            }
        };
        avatar.setPreferredSize(new Dimension(128, 128));
        avatar.setOpaque(false);

        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 16f));

        JLabel emailLabel = new JLabel(email);
        emailLabel.setFont(emailLabel.getFont().deriveFont(12f));
        emailLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");

        add(avatar, "w 128!, h 128!");
        add(nameLabel);
        add(emailLabel);
    }
}
