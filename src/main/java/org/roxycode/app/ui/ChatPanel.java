package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import javax.swing.undo.UndoManager;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.AutonomousTransitionEvent;
import org.roxycode.app.events.TurnEventBridge;
import org.roxycode.app.service.AiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;

/** Panel for the chat interface, including output display and user input area. */

public final class ChatPanel extends JPanel {
  private static final long serialVersionUID = 1L;

  private static final Logger log = LoggerFactory.getLogger(ChatPanel.class);
  private static final String ROLE_SYSTEM = "system";
  private final transient AiService aiService;
  private final transient WorkflowService workflowService;
  private final transient TurnEventBridge turnEventBridge;
  private final transient ChatMemory chatMemory;
  private final MarkdownPane outputArea;
  private final ThoughtPanel thoughtPanel;
  private final JTextArea inputArea;
  private final JButton sendButton;
  private final JButton stopButton;
  private final JButton approveButton;
  private final JButton rejectButton;
  private final JButton resetSessionButton;
  private final JPanel inlineActionPanel;
  private final JLabel transitionLabel;
  private final JLabel turnLabel;
  private final JScrollPane outputScrollPane;
    private SwingWorker<String, Void> currentWorker;
  private final List<Runnable> pendingPostTurnActions = new ArrayList<>();
  private final List<BufferedImage> pendingImages = new ArrayList<>();
  private final JPanel attachmentPanel;

  private final String sessionPrefix;
  private final WorkflowPhase lockedPhase;
  private final boolean isTaskMode;

  public ChatPanel(
      AiService aiService,
      WorkflowService workflowService,
      TurnEventBridge turnEventBridge,
      ChatMemory chatMemory,
      String sessionPrefix,
      WorkflowPhase lockedPhase,
      boolean isTaskMode) {
    this.aiService = aiService;
    this.workflowService = workflowService;
    this.turnEventBridge = turnEventBridge;
    this.chatMemory = chatMemory;
    this.sessionPrefix = sessionPrefix;
    this.lockedPhase = lockedPhase;
    this.isTaskMode = isTaskMode;

    attachmentPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
    attachmentPanel.setOpaque(false);
    attachmentPanel.setVisible(false);

    turnEventBridge.addTurnListener(getConversationId(), this::onAgentTurn);
    turnEventBridge.addAutonomousTransitionListener(this::onAutonomousTransition);
    turnEventBridge.addToolExecutionListener(getConversationId(), this::onToolExecution);
        turnEventBridge.addCompleteListener(
        getConversationId(),
        event ->
            SwingUtilities.invokeLater(
                () -> {
                  if (currentWorker == null) {
                    setLoading(false);
                  }
                }));

    setLayout(new BorderLayout());

    // Output section
    outputArea = new MarkdownPane();

    // Create inline action panel (Decision Bar)
    inlineActionPanel = new JPanel(new MigLayout("insets 10 20 10 20, gap 15", "", "center"));
    inlineActionPanel.putClientProperty(
        FlatClientProperties.STYLE,
        "background: darken($TextPane.background, 3%); "
            + "border: 1,0,0,0,$Component.borderColor");
    inlineActionPanel.setVisible(false);

    Color accentColor = UIManager.getColor("Component.accentColor");
    if (accentColor == null) accentColor = UIManager.getColor("Label.foreground");
    if (accentColor == null) accentColor = Color.GRAY;

    transitionLabel =
        new JLabel(
            "Workflow Transition",
            FontIcon.of(Codicons.ROCKET, 16, accentColor),
            SwingConstants.LEFT);
    transitionLabel.setFont(transitionLabel.getFont().deriveFont(Font.BOLD));
    transitionLabel.putClientProperty(
        FlatClientProperties.STYLE, "foreground: $Component.accentColor");

    approveButton = new JButton("Approve", FontIcon.of(Codicons.CHECK, 16));
    approveButton.putClientProperty(
        FlatClientProperties.STYLE, "background: $Actions.Green; foreground: #ffffff");
    approveButton.addActionListener(e -> approvePhase());

    rejectButton = new JButton("Reject", FontIcon.of(Codicons.CLOSE, 16));
    rejectButton.putClientProperty(
        FlatClientProperties.STYLE, "background: $Actions.Red; foreground: #ffffff");
    rejectButton.addActionListener(e -> rejectPhase());

    inlineActionPanel.add(transitionLabel);
    inlineActionPanel.add(Box.createHorizontalStrut(10));
    inlineActionPanel.add(approveButton);
    inlineActionPanel.add(rejectButton);

    // Wrap text area and action panel together
    JPanel scrollContent = new JPanel(new BorderLayout());
    scrollContent.putClientProperty(FlatClientProperties.STYLE, "background: $TextPane.background");
    scrollContent.add(outputArea, BorderLayout.CENTER);

    if (isTaskMode) {
      scrollContent.add(inlineActionPanel, BorderLayout.SOUTH);
    }

    outputScrollPane = new JScrollPane(scrollContent);
    outputScrollPane.setBorder(BorderFactory.createTitledBorder("Roxy"));

    // Autoscroll to bottom
    scrollContent.addComponentListener(
        new java.awt.event.ComponentAdapter() {
          @Override
          public void componentResized(java.awt.event.ComponentEvent e) {
            SwingUtilities.invokeLater(
                () -> {
                  JScrollBar vertical = outputScrollPane.getVerticalScrollBar();
                  vertical.setValue(vertical.getMaximum());
                });
          }
        });

    thoughtPanel = new ThoughtPanel();

    JPanel outputWrapper = new JPanel(new BorderLayout());
    outputWrapper.add(thoughtPanel, BorderLayout.NORTH);
    outputWrapper.add(outputScrollPane, BorderLayout.CENTER);

    // Input section
    JPanel inputSection =
        new JPanel(new MigLayout("fill, insets 0", "[grow, fill][][][]", "[grow, fill]"));

    inputArea = new JTextArea();
    inputArea.setLineWrap(true);
    inputArea.setWrapStyleWord(true);
    JScrollPane inputScrollPane = new JScrollPane(inputArea);

    JPanel inputAreaContainer = new JPanel(new BorderLayout());
    inputAreaContainer.setOpaque(false);
    inputAreaContainer.add(attachmentPanel, BorderLayout.NORTH);
    inputAreaContainer.add(inputScrollPane, BorderLayout.CENTER);

    sendButton = new JButton("Send", FontIcon.of(Codicons.CHEVRON_RIGHT, 16));
    sendButton.putClientProperty(
        FlatClientProperties.STYLE,
        "arc: 999; background: $Component.accentColor; foreground: $List.selectionForeground");

    stopButton = new JButton("Stop", FontIcon.of(Codicons.DEBUG_STOP, 16));
    stopButton.setEnabled(false);
    stopButton.putClientProperty(FlatClientProperties.STYLE, "arc: 999");
    stopButton.addActionListener(
        e -> {
          if (currentWorker != null) currentWorker.cancel(true);
        });

    resetSessionButton = new JButton("Reset Session", FontIcon.of(Codicons.REFRESH, 16));
    resetSessionButton.putClientProperty(
        FlatClientProperties.STYLE, "arc: 999; foreground: $Label.disabledForeground");
    resetSessionButton.addActionListener(e -> resetSession());

    turnLabel = new JLabel("Turns: 0");
    turnLabel.putClientProperty(
        FlatClientProperties.STYLE, "font: $small.font; foreground: $Label.disabledForeground");

    JPanel buttonPanel = new JPanel(new MigLayout("insets 0, gap 5", "[]", "[]"));
    buttonPanel.setOpaque(false);
    buttonPanel.add(sendButton, "growx, wrap");
    buttonPanel.add(stopButton, "growx, wrap");
    buttonPanel.add(resetSessionButton, "growx, wrap");
    buttonPanel.add(turnLabel, "center");

    inputSection.add(inputAreaContainer, "grow");
    inputSection.add(buttonPanel, "aligny bottom");

    JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, outputWrapper, inputSection);
    splitPane.setResizeWeight(0.8);
    splitPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    add(splitPane, BorderLayout.CENTER);

    sendButton.addActionListener(this::sendMessage);
    workflowService.addPhaseListener(this::onPhaseChanged);
    workflowService.addTransitionRequestListener(this::onTransitionRequested);

    setupInputContextMenu();
    setupUndoRedo();
    setupKeyboardShortcuts();
  }

  private String getConversationId() {
    return sessionPrefix + ":default";
  }

  private void setupUndoRedo() {
    UndoManager undoManager = new UndoManager();
    inputArea.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));

    InputMap inputMap = inputArea.getInputMap();
    ActionMap actionMap = inputArea.getActionMap();

    KeyStroke undoStroke =
        KeyStroke.getKeyStroke(
            KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    inputMap.put(undoStroke, "Undo");
    actionMap.put(
        "Undo",
        new AbstractAction() {
          @Override
          public void actionPerformed(ActionEvent e) {
            if (undoManager.canUndo()) {
              undoManager.undo();
            }
          }
        });

    KeyStroke redoStrokeY =
        KeyStroke.getKeyStroke(
            KeyEvent.VK_Y, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    KeyStroke redoStrokeZ =
        KeyStroke.getKeyStroke(
            KeyEvent.VK_Z,
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
                | java.awt.event.InputEvent.SHIFT_DOWN_MASK);
    inputMap.put(redoStrokeY, "Redo");
    inputMap.put(redoStrokeZ, "Redo");
    actionMap.put(
        "Redo",
        new AbstractAction() {
          @Override
          public void actionPerformed(ActionEvent e) {
            if (undoManager.canRedo()) {
              undoManager.redo();
            }
          }
        });
  }

    private void runAfterCurrentTurn(Runnable action) {
    SwingUtilities.invokeLater(
        () -> {
          if (currentWorker != null) {
            pendingPostTurnActions.add(action);
          } else {
            action.run();
          }
        });
  }

  private void onAutonomousTransition(AutonomousTransitionEvent event) {
    if (!isTaskMode) return;
    runAfterCurrentTurn(
        () -> {
          outputArea.appendMessage(
              ROLE_SYSTEM,
              "Autonomous transition detected: **"
                  + event.from()
                  + " → "
                  + event.to()
                  + "**. Reason: "
                  + event.reason());

          if (event.to() == WorkflowPhase.EXPLORE) {
            outputArea.appendMessage(
                ROLE_SYSTEM,
                "Task complete. We are back in Explore mode. Please enter a new question or task.");
          } else if (event.to() == WorkflowPhase.PLAN) {
            outputArea.appendMessage(
                ROLE_SYSTEM, "Task complete. What is the next feature you would like to work on?");
          } else {
            triggerTurn(
                "Phase changed to "
                    + event.to()
                    + ". Please proceed with the tasks in the new phase.");
          }
        });
  }

  private void onAgentTurn(AgentTurnEvent event) {
    SwingUtilities.invokeLater(
        () -> {
          turnLabel.setText("Turns: " + event.turnNumber());
        });
  }

  private void setupInputContextMenu() {
    inputArea.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            if (e.isPopupTrigger()) showPopup(e);
          }

          @Override
          public void mouseReleased(MouseEvent e) {
            if (e.isPopupTrigger()) showPopup(e);
          }

          private void showPopup(MouseEvent e) {
            inputArea.requestFocusInWindow();
            JPopupMenu menu = new JPopupMenu();
            menu.add(new JMenuItem(new DefaultEditorKit.CutAction())).setText("Cut");
            menu.add(new JMenuItem(new DefaultEditorKit.CopyAction())).setText("Copy");
            menu.add(new JMenuItem(new DefaultEditorKit.PasteAction())).setText("Paste");
            menu.addSeparator();
            JMenuItem selectAll = new JMenuItem("Select All");
            selectAll.addActionListener(ae -> inputArea.selectAll());
            menu.add(selectAll);
            menu.show(e.getComponent(), e.getX(), e.getY());
          }
        });
  }

  private void onPhaseChanged(WorkflowPhase phase) {
    log.debug("Phase changed to: {}", phase);
    SwingUtilities.invokeLater(
        () -> {
          if (isTaskMode) {
            inlineActionPanel.setVisible(false);
          }
        });
  }

  private void onTransitionRequested(
      WorkflowPhase current, WorkflowPhase requested, String reason) {
    if (!isTaskMode) return;
    if (requested == null) {
      SwingUtilities.invokeLater(() -> inlineActionPanel.setVisible(false));
      return;
    }
    runAfterCurrentTurn(
        () -> {
          String path = current.getDisplayName() + " → " + requested.getDisplayName();
          transitionLabel.setText(path);
          approveButton.setText("Approve");
          approveButton.setEnabled(true);
          rejectButton.setEnabled(true);
          inlineActionPanel.setVisible(true);
          outputArea.appendMessage(
              ROLE_SYSTEM,
              "Roxy requested a transition: **" + path + "**.\n\n**Reason:** " + reason);
        });
  }

  private void approvePhase() {
    WorkflowPhase requested = workflowService.getPendingPhase();
    if (requested != null) {
      workflowService.approveTransition();
      outputArea.appendMessage(
          ROLE_SYSTEM, "Transition to **" + requested.getDisplayName() + "** approved.");
      if (requested == WorkflowPhase.EXPLORE) {
        outputArea.appendMessage(
            ROLE_SYSTEM,
            "Task complete. We are back in Explore mode. Please enter a new question or task.");
      } else if (requested == WorkflowPhase.PLAN) {
        outputArea.appendMessage(
            ROLE_SYSTEM, "Task complete. What is the next feature you would like to work on?");
      } else {
        triggerTurn(
            "I approve the transition to "
                + requested.getDisplayName()
                + ". Please proceed with the next phase.");
      }
    }
  }

  private void rejectPhase() {
    WorkflowPhase requested = workflowService.getPendingPhase();
    if (requested != null) {
      workflowService.rejectTransition();
      outputArea.appendMessage(
          ROLE_SYSTEM, "Transition to **" + requested.getDisplayName() + "** rejected.");
      triggerTurn(
          "I have rejected the transition to "
              + requested.getDisplayName()
              + ". Let's discuss what needs to be fixed or adjusted.");
    }
  }

  private void resetSession() {
    int result =
        JOptionPane.showConfirmDialog(
            this,
            "Reset session? This cancels active tasks and clears chat history.",
            "Reset",
            JOptionPane.YES_NO_OPTION);
        if (result == JOptionPane.YES_OPTION) {
      pendingPostTurnActions.clear();
      if (currentWorker != null) {
        currentWorker.cancel(true);
        currentWorker = null; // NOPMD
        setLoading(false);
      }
      if (isTaskMode) {
        workflowService.resetWorkflow();
      }
      chatMemory.clear(getConversationId());
      outputArea.clear();
      outputArea.appendMessage(ROLE_SYSTEM, "Session reset.");
    }
  }

  private void sendMessage(ActionEvent e) {
    if (e != null) {
      log.debug("Send message triggered by: {}", e.getActionCommand());
    }
    String text = inputArea.getText().trim();
    if (text.isEmpty()) return;

    turnEventBridge.publishUserMessage(getConversationId(), "user", text);
    outputArea.appendMessage("user", text);
    inputArea.setText("");
    triggerTurn(text);
  }

  private void addPendingImage(Image img) {
    BufferedImage bimage;
    if (img instanceof BufferedImage) {
      bimage = (BufferedImage) img;
    } else {
      bimage =
          new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
      Graphics2D bGr = bimage.createGraphics();
      bGr.drawImage(img, 0, 0, null);
      bGr.dispose();
    }

    pendingImages.add(bimage);

    Image thumbnail = bimage.getScaledInstance(64, 64, Image.SCALE_SMOOTH);
    JLabel thumbLabel = new JLabel(new ImageIcon(thumbnail));
    thumbLabel.setBorder(
        BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")));

    JPanel wrapper = new JPanel(new BorderLayout());
    wrapper.setOpaque(false);
    wrapper.add(thumbLabel, BorderLayout.CENTER);

    JButton removeBtn = new JButton(FontIcon.of(Codicons.CLOSE, 12));
    removeBtn.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
    removeBtn.setMargin(new Insets(0, 0, 0, 0));
    removeBtn.addActionListener(
        ae -> {
          pendingImages.remove(bimage);
          attachmentPanel.remove(wrapper);
          attachmentPanel.setVisible(!pendingImages.isEmpty());
          attachmentPanel.revalidate();
          attachmentPanel.repaint();
        });
    wrapper.add(removeBtn, BorderLayout.NORTH);

    attachmentPanel.add(wrapper);
    attachmentPanel.setVisible(true);
    attachmentPanel.revalidate();
    attachmentPanel.repaint();
  }

  private void triggerTurn(String text) {
    if (text == null || text.trim().isEmpty()) return;

    setLoading(true);
    thoughtPanel.clear();
    turnLabel.setText("Turns: 0");

    List<byte[]> imageBytes = new ArrayList<>();
    for (BufferedImage bimg : pendingImages) {
      try {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(bimg, "png", baos);
        imageBytes.add(baos.toByteArray());
      } catch (Exception ex) {
        log.error("Failed to encode image", ex);
      }
    }

    pendingImages.clear();
    attachmentPanel.removeAll();
    attachmentPanel.setVisible(false);

        if (currentWorker != null && !currentWorker.isDone()) {
      currentWorker.cancel(true);
    }

    currentWorker =
        new SwingWorker<>() {
          @Override
          protected String doInBackground() {
            try {
              WorkflowPhase phase =
                  (lockedPhase != null) ? lockedPhase : workflowService.getCurrentPhase();
              return aiService.chat(getConversationId(), phase, text, null, imageBytes);
            } catch (Exception ex) {
              log.error("AI chat interaction failed: {}", ex.getMessage(), ex);
              return "Error: "
                  + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            }
          }

          @Override
          protected void done() {
            boolean cancelled = isCancelled();
            try {
              String response = get();
              outputArea.appendMessage("ai", response);
            } catch (java.util.concurrent.CancellationException ce) {
              cancelled = true;
              outputArea.appendMessage(ROLE_SYSTEM, "_Request cancelled by user._");
            } catch (Exception ex) {
              log.error("Failed to retrieve AI response: {}", ex.getMessage(), ex);
              outputArea.appendMessage("ai", "_Error communicating with AI._");
            } finally {
              if (currentWorker == this) {
                currentWorker = null; // NOPMD
                setLoading(false);
              }
              List<Runnable> actions = new ArrayList<>(pendingPostTurnActions);
              pendingPostTurnActions.clear();
              if (!cancelled) {
                for (Runnable action : actions) {
                  action.run();
                }
              }
            }
          }
        };
    currentWorker.execute();
  }

  private void onToolExecution(org.roxycode.app.events.ToolExecutionEvent event) {
    SwingUtilities.invokeLater(
        () -> {
          String toolName = "";
          String output = JexlToHtmlConverter.convert(event.script());
          outputArea.appendToolLog(toolName, output);
        });
  }

  private void setLoading(boolean loading) {
    if (loading) {
      stopButton.setEnabled(true);
      SwingUtilities.invokeLater(() -> stopButton.requestFocusInWindow());
      sendButton.setEnabled(false);
      inputArea.setEnabled(false);
      if (isTaskMode) {
        boolean hasPending = workflowService.getPendingPhase() != null;
        approveButton.setEnabled(hasPending);
        rejectButton.setEnabled(hasPending);
      }
    } else {
      sendButton.setEnabled(true);
      inputArea.setEnabled(true);
      if (isTaskMode) {
        approveButton.setEnabled(true);
        rejectButton.setEnabled(true);
      }
      stopButton.setEnabled(false);
      SwingUtilities.invokeLater(() -> inputArea.requestFocusInWindow());
    }
    sendButton.setText(loading ? "Sending..." : "Send");
  }

  public MarkdownPane getOutputArea() {
    return outputArea;
  }

  public JTextArea getInputArea() {
    return inputArea;
  }

  public JButton getSendButton() {
    return sendButton;
  }

  public JButton getStopButton() {
    return stopButton;
  }

  private void setupKeyboardShortcuts() {
    InputMap im = inputArea.getInputMap(JComponent.WHEN_FOCUSED);
    ActionMap am = inputArea.getActionMap();

    im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send-message");
    am.put(
        "send-message",
        new AbstractAction() {
          @Override
          public void actionPerformed(ActionEvent e) {
            sendMessage(e);
          }
        });

    im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-newline");
    am.put(
        "insert-newline",
        new AbstractAction() {
          @Override
          public void actionPerformed(ActionEvent e) {
            inputArea.insert("\n", inputArea.getCaretPosition());
          }
        });

    Action defaultPaste = inputArea.getActionMap().get(DefaultEditorKit.pasteAction);
    inputArea
        .getActionMap()
        .put(
            DefaultEditorKit.pasteAction,
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent e) {
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                  try {
                    Image img = (Image) clipboard.getData(DataFlavor.imageFlavor);
                    addPendingImage(img);
                  } catch (Exception ex) {
                    log.error("Failed to paste image", ex);
                  }
                } else {
                  defaultPaste.actionPerformed(e);
                }
              }
            });
  }
}
