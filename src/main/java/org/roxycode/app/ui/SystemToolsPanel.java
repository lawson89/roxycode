package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.SystemToolService;


public final class SystemToolsPanel extends JPanel {
  private static final long serialVersionUID = 1L;

  private final transient SystemToolService toolService;
  final DefaultTableModel tableModel;
  private final JTable toolTable;

  public SystemToolsPanel(SystemToolService toolService) {
    this.toolService = toolService;
    setLayout(new MigLayout("fill, insets 20", "[grow]", "[]20[grow]"));
    setOpaque(true);

    JLabel title = new JLabel("System Tools Status");
    title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));

    JButton refreshBtn = new JButton("Refresh Status");
    refreshBtn.addActionListener(e -> refreshTools());

    JPanel header = new JPanel(new MigLayout("fillx, insets 0", "[grow][]", "[]"));
    header.setOpaque(true);
    header.add(title);
    header.add(refreshBtn);
    add(header, "wrap");

    String[] columnNames = {"TOOL", "VERSION", "STATUS", "DESCRIPTION"};
    tableModel =
        new DefaultTableModel(columnNames, 0) {
          @Override
          public boolean isCellEditable(int row, int column) {
            return false;
          }
        };

    toolTable = new JTable(tableModel);
    toolTable.setShowGrid(false);
    toolTable.setRowHeight(35);
    toolTable.getTableHeader().setReorderingAllowed(false);
    toolTable.putClientProperty(
        FlatClientProperties.STYLE,
        "background: $Panel.background; selectionBackground: $Table.selectionBackground; selectionForeground: $Table.selectionForeground");
    toolTable
        .getTableHeader()
        .putClientProperty(
            FlatClientProperties.STYLE,
            "background: $Table.background; foreground: $Table.foreground; font: bold");

    // Custom renderer for Status column
    toolTable.getColumnModel().getColumn(2).setCellRenderer(new StatusCellRenderer());

    JScrollPane scrollPane = new JScrollPane(toolTable);
    scrollPane.setBorder(BorderFactory.createEmptyBorder());
    scrollPane.setOpaque(true);
    scrollPane.getViewport().setOpaque(true);
    add(scrollPane, "grow");

    refreshTools();
  }

  private void refreshTools() {
    tableModel.setRowCount(0);

    toolService
        .detectTools()
        .thenAccept(
            tools ->
                SwingUtilities.invokeLater(
                    () -> {
                      tableModel.setRowCount(0);
                      for (SystemTool tool : tools) {
                        tableModel.addRow(
                            new Object[] {
                              tool.name(), tool.version(), tool.status(), tool.description()
                            });
                      }
                    }));
  }

  private static class StatusCellRenderer extends DefaultTableCellRenderer {
    private static final long serialVersionUID = 1L;

    @Override
    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

      String status = String.valueOf(value);
      final String statusDetected = "Detected";
      if (statusDetected.equals(status)) {
        Color successColor = new Color(40, 167, 69);
        setForeground(successColor);
        setIcon(FontIcon.of(Codicons.CHECK, 16, successColor));
      } else {
        Color errorColor = new Color(220, 53, 69);
        setForeground(errorColor);
        setIcon(FontIcon.of(Codicons.ERROR, 16, errorColor));
      }

      setHorizontalTextPosition(SwingConstants.RIGHT);
      setIconTextGap(8);

      return this;
    }
  }

  @Override
  public void updateUI() {
    super.updateUI();
    if (toolTable != null) {
      toolTable
          .getTableHeader()
          .putClientProperty(
              com.formdev.flatlaf.FlatClientProperties.STYLE,
              "background: $Table.background; foreground: $Table.foreground; font: bold");
    }
  }
}
