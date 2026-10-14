package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sidebar panel containing branding and navigation. */
public final class SidebarPanel extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(SidebarPanel.class);
  private final Consumer<String> navigationAction;
  private final List<NavButton> navButtons = new ArrayList<>();
  private transient Image logoImage;

  public SidebarPanel(WorkflowService workflowService, Consumer<String> navigationAction) {
    this.navigationAction = navigationAction;
    initComponents();
  }

  private void initComponents() {
    setLayout(
        new MigLayout(
            "wrap, fillx, insets 15",
            "[fill, grow]",
            "[]20[]5[]2[]15[]5[]2[]2[]15[]5[]2[]2[]push"));

    putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");

    add(createBrandingHeader());

    add(createSectionHeader("WORKSPACES"), "gaptop 10");

    NavButton qaBtn = createNavButton("Codebase Q&A", "QA_VIEW", Codicons.COMMENT_DISCUSSION);
    add(qaBtn);

    NavButton taskBtn = createNavButton("Active Task", "TASK_VIEW", Codicons.ROCKET);
    add(taskBtn);

    add(createSectionHeader("CONTEXT"), "gaptop 15");
    add(createNavButton("Project Context", "CONTEXT_MGR", Codicons.FILE));
    add(createNavButton("Implicit Cache", "CACHE", Codicons.DATABASE));
    add(createNavButton("API", "API", Codicons.CODE));

    add(createSectionHeader("CONFIG"), "gaptop 15");
    add(createNavButton("System Tools", "TOOLS", Codicons.TOOLS));
    add(createNavButton("Build Config", "BUILD_CONFIG", Codicons.PACKAGE));
    add(createNavButton("Settings", "SETTINGS", Codicons.SETTINGS_GEAR));

    updateSelection(qaBtn);
  }

  private JPanel createBrandingHeader() {
    JPanel brandingPanel = new JPanel(new MigLayout("fillx, insets 20, wrap 1", "[center]", "[]15[]"));
    brandingPanel.setOpaque(false);

    try {
      URL url = getClass().getResource("/static/images/roxy.png");
      if (url != null) {
        logoImage = ImageIO.read(url);
      }
    } catch (IOException e) {
      log.warn("Failed to load logo image: {}", e.getMessage());
    }

    JPanel logoPanel =
        new JPanel() {
          private static final long serialVersionUID = 1L;

          @Override
          protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
              g2.setRenderingHint(
                  RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
              g2.setRenderingHint(
                  RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
              g2.setRenderingHint(
                  RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

              int width = getWidth();
              int height = getHeight();
              int arc = 32;
              int inset = 2;

              if (logoImage != null) {
                BufferedImage buffer =
                    new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D gBuffer = buffer.createGraphics();
                try {
                  gBuffer.setRenderingHint(
                      RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                  gBuffer.setColor(Color.BLACK);
                  gBuffer.fillRoundRect(
                      inset, inset, width - (2 * inset), height - (2 * inset), arc, arc);

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
                g2.fillRoundRect(inset, inset, width - (2 * inset), height - (2 * inset), arc, arc);
              }

              Color borderColor = UIManager.getColor("Component.borderColor");
              if (borderColor == null) {
                borderColor = new Color(80, 80, 80);
              }
              g2.setColor(borderColor);
              g2.setStroke(new BasicStroke(1.5f));
              g2.drawRoundRect(
                  inset, inset, width - (2 * inset) - 1, height - (2 * inset) - 1, arc, arc);
            } finally {
              g2.dispose();
            }
          }
        };
    logoPanel.setPreferredSize(new Dimension(128, 128));
    logoPanel.setOpaque(false);

    JLabel titleLabel = new JLabel("RoxyCode");
    titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));

    brandingPanel.add(logoPanel, "w 128!, h 128!");
    brandingPanel.add(titleLabel);

    return brandingPanel;
  }

  private void updateSelection(NavButton selectedBtn) {
    for (NavButton btn : navButtons) {
      btn.setSelected(btn.equals(selectedBtn));
    }
  }

  private JLabel createSectionHeader(String text) {
    JLabel label = new JLabel(text);
    label.setFont(label.getFont().deriveFont(Font.BOLD, 10f));
    label.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
    return label;
  }

  private NavButton createNavButton(String text, String cardName, Codicons iconCode) {
    return createNavButton(text, cardName, iconCode, null);
  }

  private NavButton createNavButton(
      String text, String cardName, Codicons iconCode, ActionListener customListener) {
    FontIcon icon = FontIcon.of(iconCode, 16);
    NavButton btn = new NavButton(text, icon);
    if (customListener != null) {
      btn.addActionListener(customListener);
    } else {
      btn.addActionListener(
          e -> {
            updateSelection(btn);
            navigationAction.accept(cardName);
          });
    }
    navButtons.add(btn);
    return btn;
  }
}
