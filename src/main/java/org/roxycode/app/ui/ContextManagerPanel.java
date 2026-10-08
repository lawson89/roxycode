package org.roxycode.app.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.roxycode.app.service.PromptService;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class ContextManagerPanel extends JPanel {

    private final PromptService promptService;
    private final JList<String> fileList;
    private final DefaultListModel<String> listModel;
    private final RSyntaxTextArea textArea;
    private final JButton btnNew;
    private final JButton btnDelete;
    private final JButton btnSave;
    private String currentFile = null;

    public ContextManagerPanel(PromptService promptService) {
        this.promptService = promptService;
        setLayout(new BorderLayout());

        listModel = new DefaultListModel<>();
        fileList = new JList<>(listModel);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane listScroll = new JScrollPane(fileList);
        
        btnNew = new JButton("New Rule");
        btnDelete = new JButton("Delete");
        btnDelete.setEnabled(false);

        JPanel leftButtons = new JPanel(new GridLayout(1, 2, 5, 5));
        leftButtons.add(btnNew);
        leftButtons.add(btnDelete);

        JPanel leftPanel = new JPanel(new BorderLayout(0, 5));
        leftPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        leftPanel.add(new JLabel("Context Files (.roxycode/context/)"), BorderLayout.NORTH);
        leftPanel.add(listScroll, BorderLayout.CENTER);
        leftPanel.add(leftButtons, BorderLayout.SOUTH);

        textArea = new RSyntaxTextArea();
        textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
        textArea.setCodeFoldingEnabled(true);
        textArea.setAntiAliasingEnabled(true);
        RTextScrollPane textScroll = new RTextScrollPane(textArea);

        btnSave = new JButton("Save Changes");
        btnSave.setEnabled(false);
        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        rightButtons.add(btnSave);

        JPanel rightPanel = new JPanel(new BorderLayout(0, 5));
        rightPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        rightPanel.add(textScroll, BorderLayout.CENTER);
        rightPanel.add(rightButtons, BorderLayout.SOUTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        splitPane.setDividerLocation(250);
        add(splitPane, BorderLayout.CENTER);

        setupListeners();
        refreshFileList();
    }

    private void setupListeners() {
        fileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selected = fileList.getSelectedValue();
                if (selected != null) {
                    currentFile = selected;
                    textArea.setText(promptService.readContextFile(selected));
                    textArea.setCaretPosition(0);
                    btnDelete.setEnabled(true);
                    btnSave.setEnabled(true);
                } else {
                    currentFile = null;
                    textArea.setText("");
                    btnDelete.setEnabled(false);
                    btnSave.setEnabled(false);
                }
            }
        });

        btnNew.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(this, "Enter filename (e.g., rules.md):", "New Context Rule", JOptionPane.PLAIN_MESSAGE);
            if (name != null && !name.trim().isEmpty()) {
                if (!name.endsWith(".md")) {
                    name += ".md";
                }
                promptService.saveContextFile(name, "# " + name + "\n\n");
                refreshFileList();
                fileList.setSelectedValue(name, true);
            }
        });

        btnDelete.addActionListener(e -> {
            if (currentFile != null) {
                int ans = JOptionPane.showConfirmDialog(this, "Delete " + currentFile + "?", "Confirm Delete", JOptionPane.YES_NO_OPTION);
                if (ans == JOptionPane.YES_OPTION) {
                    promptService.deleteContextFile(currentFile);
                    refreshFileList();
                }
            }
        });

        btnSave.addActionListener(e -> {
            if (currentFile != null) {
                promptService.saveContextFile(currentFile, textArea.getText());
                JOptionPane.showMessageDialog(this, "Saved " + currentFile, "Saved", JOptionPane.INFORMATION_MESSAGE);
            }
        });
    }

    private void refreshFileList() {
        listModel.clear();
        List<String> files = promptService.listContextFiles();
        for (String f : files) {
            listModel.addElement(f);
        }
    }
}