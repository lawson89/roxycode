package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.io.File;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;

/** Header panel containing application controls and project selection. */
public final class HeaderPanel extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final String LAYOUT_CENTER = "center";
  private final transient ProjectService projectService;
  private final transient GitService gitService;

  private final JLabel projectLabel;
  private final JLabel branchLabel;
  private final JLabel modelLabel;

  public HeaderPanel(
      ProjectService projectService, GitService gitService, SettingsService settingsService) {
    this.projectService = projectService;
    this.gitService = gitService;

    // 2-column layout: Left (Project), Right (Utils)
    setLayout(new MigLayout("insets 2 15 2 15, fillx", "[left]push[right]", LAYOUT_CENTER));

    putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
    setBorder(
        BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")));

    // --- LEFT SECTION: Project Info ---
    JPanel leftPanel = new JPanel(new MigLayout("insets 0", "[]5[]12[]", LAYOUT_CENTER));
    leftPanel.setOpaque(false);

    projectLabel = new JLabel(projectService.getProjectName());
    projectLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +1");
    leftPanel.add(projectLabel);

    JButton openButton =
        createIconButton(Codicons.FOLDER_OPENED, "Open Project", e -> chooseProject());
    leftPanel.add(openButton);

    branchLabel = new JLabel();
    branchLabel.putClientProperty(
        FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
    updateBranchLabel();
    leftPanel.add(branchLabel);

    add(leftPanel, "left");

    // --- RIGHT SECTION: Model & Utils ---
    JPanel rightPanel = new JPanel(new MigLayout("insets 0", "[]", LAYOUT_CENTER));
    rightPanel.setOpaque(false);

    modelLabel = new JLabel();
    modelLabel.putClientProperty(
        FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
    updateModelDisplay(settingsService.getSettings().getGeminiModel());

    rightPanel.add(modelLabel);
    add(rightPanel, "right");

    // Listeners
    projectService.addProjectListener(
        newRoot -> {
          projectLabel.setText(projectService.getProjectName());
          if (newRoot != null) {
            projectLabel.setToolTipText(newRoot.toAbsolutePath().toString());
          } else {
            projectLabel.setToolTipText("No project selected");
          }
          updateBranchLabel();
        });

    if (projectService.getCurrentProjectRoot() != null) {
      projectLabel.setToolTipText(
          projectService.getCurrentProjectRoot().toAbsolutePath().toString());
    } else {
      projectLabel.setToolTipText("No project selected");
    }

    settingsService.addSettingsListener(
        settings -> {
          updateModelDisplay(settings.getGeminiModel());
        });
  }

  private JButton createIconButton(
      Codicons icon, String tooltip, java.awt.event.ActionListener listener) {
    JButton button = new JButton(FontIcon.of(icon, 16, UIManager.getColor("Label.foreground")));
    button.setToolTipText(tooltip);
    button.setFocusable(false);
    button.setContentAreaFilled(false);
    button.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    button.addActionListener(listener);

    button.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseEntered(java.awt.event.MouseEvent e) {
            button.setContentAreaFilled(true);
            button.setBackground(UIManager.getColor("Button.hoverBackground"));
          }

          @Override
          public void mouseExited(java.awt.event.MouseEvent e) {
            button.setContentAreaFilled(false);
          }
        });

    return button;
  }

  private void updateBranchLabel() {
    String branch = gitService.getCurrentBranch();
    if (branch != null
        && !branch.isEmpty()
        && !branch.startsWith("Error")
        && !"Not a git repository".equals(branch)) {
      branchLabel.setIcon(
          FontIcon.of(Codicons.SOURCE_CONTROL, 14, UIManager.getColor("Label.disabledForeground")));
      branchLabel.setText(branch);
      branchLabel.setVisible(true);
    } else {
      branchLabel.setIcon(null);
      branchLabel.setText("");
      branchLabel.setVisible(false);
    }
  }

  private void updateModelDisplay(String modelName) {
    modelLabel.setIcon(
        FontIcon.of(Codicons.HUBOT, 16, UIManager.getColor("Label.disabledForeground")));
    modelLabel.setText(modelName != null ? modelName : "No model selected");
  }

  private void chooseProject() {
    JFileChooser chooser = new JFileChooser();
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (projectService.getCurrentProjectRoot() != null) {
      chooser.setCurrentDirectory(projectService.getCurrentProjectRoot().toFile());
    }

    int returnVal = chooser.showOpenDialog(this);
    if (returnVal == JFileChooser.APPROVE_OPTION) {
      File file = chooser.getSelectedFile();
      projectService.setCurrentProjectRoot(file.toPath());
    }
  }
}
