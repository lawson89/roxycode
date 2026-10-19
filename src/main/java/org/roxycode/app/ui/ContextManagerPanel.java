package org.roxycode.app.ui;

import java.awt.*;
import java.util.List;
import javax.swing.*;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.roxycode.app.service.PromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ContextManagerPanel extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(ContextManagerPanel.class);

  private final transient PromptService promptService;
  private final JList<String> fileList;
  private final DefaultListModel<String> listModel;
  private final RSyntaxTextArea textArea;
  private final JButton btnNew;
  private final JButton btnDelete;
  private final JButton btnSave;
  private String currentFile;

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
    refreshFileList(null);
  }

  private void setupListeners() {
    fileList.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting()) {
            String selected = fileList.getSelectedValue();
            if (selected != null) {
              currentFile = selected;
              loadSelectedFileAsync(selected);
            } else {
              currentFile = null; // NOPMD
              textArea.setText("");
              btnDelete.setEnabled(false);
              btnSave.setEnabled(false);
            }
          }
        });

    btnNew.addActionListener(
        e -> {
          String input =
              JOptionPane.showInputDialog(
                  this,
                  "Enter filename (e.g., rules.md):",
                  "New Context Rule",
                  JOptionPane.PLAIN_MESSAGE);
          if (input != null && !input.trim().isEmpty()) {
            String name = input.endsWith(".md") ? input : input + ".md";
            createNewFileAsync(name);
          }
        });

    btnDelete.addActionListener(
        e -> {
          if (currentFile != null) {
            String filename = currentFile;
            int ans =
                JOptionPane.showConfirmDialog(
                    this, "Delete " + filename + "?", "Confirm Delete", JOptionPane.YES_NO_OPTION);
            if (ans == JOptionPane.YES_OPTION) {
              deleteFileAsync(filename);
            }
          }
        });

    btnSave.addActionListener(
        e -> {
          if (currentFile != null) {
            saveFileAsync(currentFile, textArea.getText());
          }
        });
  }

  SwingWorker<String, Void> loadSelectedFileAsync(String selected) {
    SwingWorker<String, Void> worker =
        new SwingWorker<>() {
          @Override
          protected String doInBackground() {
            return promptService.readContextFile(selected);
          }

          @Override
          protected void done() {
            try {
              String content = get();
              if (selected.equals(currentFile)) {
                textArea.setText(content != null ? content : "");
                textArea.setCaretPosition(0);
                btnDelete.setEnabled(true);
                btnSave.setEnabled(true);
              }
            } catch (Exception ex) {
              log.error("Failed to read context file {}", selected, ex);
            }
          }
        };
    worker.execute();
    return worker;
  }

  SwingWorker<Void, Void> createNewFileAsync(String name) {
    SwingWorker<Void, Void> worker =
        new SwingWorker<>() {
          @Override
          protected Void doInBackground() {
            promptService.saveContextFile(name, "# " + name + "\n\n");
            return null;
          }

          @Override
          protected void done() {
            try {
              get();
              refreshFileList(name);
            } catch (Exception ex) {
              log.error("Failed to create context file {}", name, ex);
            }
          }
        };
    worker.execute();
    return worker;
  }

  SwingWorker<Void, Void> deleteFileAsync(String filename) {
    SwingWorker<Void, Void> worker =
        new SwingWorker<>() {
          @Override
          protected Void doInBackground() {
            promptService.deleteContextFile(filename);
            return null;
          }

          @Override
          protected void done() {
            try {
              get();
              refreshFileList(null);
            } catch (Exception ex) {
              log.error("Failed to delete context file {}", filename, ex);
            }
          }
        };
    worker.execute();
    return worker;
  }

  SwingWorker<Void, Void> saveFileAsync(String filename, String content) {
    SwingWorker<Void, Void> worker =
        new SwingWorker<>() {
          @Override
          protected Void doInBackground() {
            promptService.saveContextFile(filename, content);
            return null;
          }

          @Override
          protected void done() {
            try {
              get();
              JOptionPane.showMessageDialog(
                  ContextManagerPanel.this,
                  "Saved " + filename,
                  "Saved",
                  JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
              log.error("Failed to save context file {}", filename, ex);
            }
          }
        };
    worker.execute();
    return worker;
  }

  SwingWorker<List<String>, Void> refreshFileList(String selectAfterLoad) {
    SwingWorker<List<String>, Void> worker =
        new SwingWorker<>() {
          @Override
          protected List<String> doInBackground() {
            return promptService.listContextFiles();
          }

          @Override
          protected void done() {
            try {
              List<String> files = get();
              listModel.clear();
              if (files != null) {
                for (String f : files) {
                  listModel.addElement(f);
                }
              }
              if (selectAfterLoad != null) {
                fileList.setSelectedValue(selectAfterLoad, true);
              }
            } catch (Exception ex) {
              log.error("Failed to list context files", ex);
            }
          }
        };
    worker.execute();
    return worker;
  }

  DefaultListModel<String> getListModel() {
    return listModel;
  }

  JList<String> getFileList() {
    return fileList;
  }

  RSyntaxTextArea getTextArea() {
    return textArea;
  }
}
