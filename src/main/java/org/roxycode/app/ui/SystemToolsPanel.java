package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.SystemToolService;
import org.roxycode.app.util.Edt;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class SystemToolsPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final transient SystemToolService toolService;
    final DefaultTableModel tableModel;
    private final JTable toolTable;
    private final JButton refreshBtn;
    private final JProgressBar progressBar;

    public SystemToolsPanel(SystemToolService toolService) {
        this.toolService = toolService;
        setLayout(new MigLayout("fill, insets 20, hidemode 3", "[grow]", "[]10[]10[grow]"));
        setOpaque(true);

        JLabel title = new JLabel("System Tools Status");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));

        refreshBtn = new JButton("Refresh Status");
        refreshBtn.addActionListener(e -> refreshTools());

        JPanel header = new JPanel(new MigLayout("fillx, insets 0", "[grow][]", "[]"));
        header.setOpaque(true);
        header.add(title);
        header.add(refreshBtn);
        add(header, "growx, wrap");

        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        add(progressBar, "growx, wrap, hidemode 3");

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

        loadInitialTools();
    }

    private void loadInitialTools() {
        if (toolService == null) {
            return;
        }
        if (toolService.hasCachedTools()) {
            populateTable(toolService.getCachedTools());
            return;
        }
        runAsyncToolLoad(toolService.detectTools());
    }

    public CompletableFuture<List<SystemTool>> refreshTools() {
        if (toolService == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        CompletableFuture<List<SystemTool>> future = toolService.refreshTools();
        if (future == null) {
            future = toolService.detectTools();
        }
        return runAsyncToolLoad(future);
    }

    private CompletableFuture<List<SystemTool>> runAsyncToolLoad(
            CompletableFuture<List<SystemTool>> future) {
        Edt.run(
                () -> {
                    refreshBtn.setEnabled(false);
                    progressBar.setVisible(true);
                });
        if (future == null) {
            Edt.run(
                    () -> {
                        progressBar.setVisible(false);
                        refreshBtn.setEnabled(true);
                    });
            return CompletableFuture.completedFuture(List.of());
        }
        return future.whenComplete(
                (tools, ex) ->
                        Edt.run(
                                () -> {
                                    if (ex == null && tools != null) {
                                        populateTable(tools);
                                    }
                                    progressBar.setVisible(false);
                                    refreshBtn.setEnabled(true);
                                }));
    }

    @EventListener
    public void onSystemToolsUpdated(SystemToolsUpdatedEvent event) {
        if (event != null && event.tools() != null) {
            Edt.run(() -> populateTable(event.tools()));
        }
    }

    private void populateTable(List<SystemTool> tools) {
        tableModel.setRowCount(0);
        if (tools == null) {
            return;
        }
        for (SystemTool tool : tools) {
            tableModel.addRow(
                    new Object[] {tool.name(), tool.version(), tool.status(), tool.description()});
        }
    }

    JButton getRefreshButton() {
        return refreshBtn;
    }

    JProgressBar getProgressBar() {
        return progressBar;
    }

    DefaultTableModel getTableModel() {
        return tableModel;
    }

    private static class StatusCellRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public java.awt.Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            String status = String.valueOf(value);
            if (SystemToolService.STATUS_DETECTED.equals(status)) {
                Color successColor = UIManager.getColor("Actions.Green");
                if (successColor == null) {
                    successColor = new Color(40, 167, 69);
                }
                setForeground(successColor);
                setIcon(FontIcon.of(Codicons.CHECK, 16, successColor));
            } else {
                Color errorColor = UIManager.getColor("Actions.Red");
                if (errorColor == null) {
                    errorColor = new Color(220, 53, 69);
                }
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
                            FlatClientProperties.STYLE,
                            "background: $Table.background; foreground: $Table.foreground; font: bold");
        }
    }
}
