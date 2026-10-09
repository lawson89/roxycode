package org.roxycode.app.ui;

import org.roxycode.app.ai.JexlExecutionEvent;
import org.roxycode.app.ai.JexlExecutionListener;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.services.cache.GeminiCacheService;
import org.roxycode.app.events.TurnEventBridge;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.MessageType;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel for displaying a persistent history of all AI interactions and tool executions.
 */
public class HistoryPanel extends JPanel implements JexlExecutionListener {
    private final ChatMemory chatMemory;
    private final GeminiCacheService geminiCacheService;
    private final HistoryTableModel tableModel;
    private final JTable table;
    private final JLabel statsLabel;

    public HistoryPanel(TurnEventBridge turnEventBridge, JexlTool jexlTool, ChatMemory chatMemory, GeminiCacheService geminiCacheService) {
        this.chatMemory = chatMemory;
        this.geminiCacheService = geminiCacheService;
        this.tableModel = new HistoryTableModel();
        this.table = new JTable(tableModel);
        
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Header
        JPanel header = new JPanel(new BorderLayout());
        statsLabel = new JLabel("0 Messages | 0 Tokens");
        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> refresh());
        
        header.add(statsLabel, BorderLayout.WEST);
        header.add(refreshButton, BorderLayout.EAST);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        add(header, BorderLayout.NORTH);

        // Table setup
        table.setRowHeight(35);
        table.getTableHeader().setReorderingAllowed(false);
        
        // Custom cell renderer for preview to handle snippets
        table.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
                return this;
            }
        });

        // View Button column
        table.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            private final JButton button = new JButton("View");
            {
                button.putClientProperty("JButton.buttonType", "roundRect");
            }
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                return button;
            }
        });

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row >= 0 && col == 4) {
                    showDetail(row);
                }
            }
        });

        // Column widths
        table.getColumnModel().getColumn(0).setPreferredWidth(50);
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(1).setMaxWidth(150);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setMaxWidth(100);
        table.getColumnModel().getColumn(4).setPreferredWidth(80);
        table.getColumnModel().getColumn(4).setMaxWidth(100);

                add(new JScrollPane(table), BorderLayout.CENTER);

        // Event listeners
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                refresh();
            }
        });
        turnEventBridge.addUserMessageListener(event -> refresh());
        turnEventBridge.addCompleteListener(event -> refresh());
        jexlTool.addListener(this);
        
        refresh();
    }

    public void refresh() {
        SwingUtilities.invokeLater(() -> {
            List<Message> messages = chatMemory.get("default");
            if (messages == null) {
                messages = new ArrayList<>();
            }
            tableModel.setMessages(messages);
            
            long totalTokens = 0;
            for (Message m : messages) {
                totalTokens += geminiCacheService.estimateTokens(m.getText());
            }
            statsLabel.setText(messages.size() + " Messages | " + totalTokens + " Tokens (Estimated)");
        });
    }

    private void showDetail(int row) {
        Message msg = tableModel.getMessageAt(row);
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Message Detail", true);
        dialog.setSize(800, 600);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout());
        
        MarkdownViewer viewer = new MarkdownViewer();
        viewer.setContent(msg.getText());
        
        dialog.add(new JScrollPane(viewer), BorderLayout.CENTER);
        
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        bottom.add(close);
        dialog.add(bottom, BorderLayout.SOUTH);
        
        dialog.setVisible(true);
    }

    @Override
    public void onJexlExecuted(JexlExecutionEvent event) {
        refresh();
    }

    private class HistoryTableModel extends AbstractTableModel {
        private final String[] columns = {"#", "Role", "Tokens", "Preview", "Action"};
        private List<Message> messages = new ArrayList<>();

        public void setMessages(List<Message> messages) {
            this.messages = messages;
            fireTableDataChanged();
        }

        public Message getMessageAt(int row) {
            return messages.get(row);
        }

        @Override
        public int getRowCount() {
            return messages.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Message msg = messages.get(rowIndex);
            switch (columnIndex) {
                case 0: return rowIndex + 1;
                case 1: 
                    if (msg.getMessageType() == MessageType.ASSISTANT) {
                        AssistantMessage am = (AssistantMessage) msg;
                        if (am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                            return "tool-call";
                        }
                        return "assistant";
                    }
                    if (msg.getMessageType() == MessageType.TOOL) {
                        return "tool-response";
                    }
                    return msg.getMessageType().name().toLowerCase();
                case 2: return geminiCacheService.estimateTokens(msg.getText());
                case 3: 
                    String content = msg.getText();
                    if (content == null) return "";
                    content = content.replace('\n', ' ').replace('\r', ' ');
                    if (content.length() > 100) {
                        return content.substring(0, 97) + "...";
                    }
                    return content;
                case 4: return "View";
                default: return null;
            }
        }
    }
}
