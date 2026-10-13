package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.service.EnvironmentService;

/** Bottom status bar with system info. */
@SuppressWarnings("PMD.ConstructorCallsOverridableMethod")
public final class StatusPanel extends JPanel {
  private static final long serialVersionUID = 1L;
  private final transient EnvironmentService envService;
  private final transient org.springframework.ai.chat.memory.ChatMemory chatMemory;
  private final JLabel memLabel;
  private final JLabel msgLabel;

  public StatusPanel(
      EnvironmentService envService, org.springframework.ai.chat.memory.ChatMemory chatMemory) {
    this.envService = envService;
    this.chatMemory = chatMemory;
    setLayout(new MigLayout("insets 2 15 2 15", "[] 20 [] 20 [] push []", "center"));

    // Use theme-aware styling
    putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
    setBorder(
        BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")));

    JLabel osLabel = new JLabel(envService.getOsName());
    osLabel.setIcon(envService.getOsIcon());
    osLabel.setFont(osLabel.getFont().deriveFont(11f));
    osLabel.setToolTipText(envService.getOsVersion() + " | User: " + envService.getCurrentUser());

    JLabel javaLabel = new JLabel("Java " + envService.getJavaVersion());
    javaLabel.setFont(osLabel.getFont());
    javaLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

    memLabel = new JLabel();
    memLabel.setFont(osLabel.getFont());

    msgLabel = new JLabel();
    msgLabel.setFont(osLabel.getFont());

    updateStats();

    add(osLabel);
    add(javaLabel);
    add(msgLabel);
    add(memLabel);

    // Update memory usage every 5 seconds
    Timer timer = new Timer(5000, e -> updateStats());
    timer.start();
  }

  private void updateStats() {
    memLabel.setText(
        "Mem: " + envService.getMemoryUsageMb() + "/" + envService.getTotalMemoryMb() + "MB");

    java.util.List<org.springframework.ai.chat.messages.Message> messages =
        chatMemory.get("default");
    int count = (messages != null) ? messages.size() : 0;
    msgLabel.setText("Messages: " + count);
  }
}
