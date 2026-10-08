package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.text.StringEscapeUtils;
import org.roxycode.app.ai.JexlExecutionEvent;
import org.roxycode.app.ai.JexlExecutionListener;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.services.ExploreManager;
import org.roxycode.app.service.AiService;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.roxycode.app.events.TurnEventBridge;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.ui.ThoughtPanel;
import org.roxycode.app.ui.JexlToHtmlConverter;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import javax.swing.undo.UndoManager;
import java.awt.event.KeyEvent;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.ActionEvent;

/**
 * Panel for the chat interface, including output display and user input area.
 */
public class ChatPanel extends JPanel implements JexlExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ChatPanel.class);
    private final AiService aiService;
    private final ExploreManager exploreManager;
    private final JexlTool jexlTool;
    private final WorkflowService workflowService;
        private final TurnEventBridge turnEventBridge;
    private final ChatMemory chatMemory;
    private final MarkdownPane outputArea;
    private final ThoughtPanel thoughtPanel;
    private final JTextArea inputArea;
    private final JButton sendButton;
    private final JButton stopButton;
        private final JButton approveButton;
    private final JButton rejectButton;
    private final JButton resetSessionButton;
    private final JPanel inlineActionPanel;
    private final JLabel turnLabel;
    private final JScrollPane outputScrollPane;
    private SwingWorker<String, Void> currentWorker;

        public ChatPanel(AiService aiService, ExploreManager exploreManager, JexlTool jexlTool, 
                     WorkflowService workflowService, TurnEventBridge turnEventBridge, ChatMemory chatMemory) {
        this.aiService = aiService;
        this.exploreManager = exploreManager;
        this.jexlTool = jexlTool;
        this.workflowService = workflowService;
        this.turnEventBridge = turnEventBridge;
        this.chatMemory = chatMemory;
        this.jexlTool.addListener(this);
        
        turnEventBridge.addTurnListener(this::onAgentTurn);
        turnEventBridge.addCompleteListener(event -> setLoading(false));

        setLayout(new BorderLayout());

                // Output section
        outputArea = new MarkdownPane();

        // Create inline action panel
        inlineActionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        inlineActionPanel.setOpaque(false);
        inlineActionPanel.setVisible(false);

        approveButton = new JButton("Approve", FontIcon.of(Codicons.CHECK, 16));
        approveButton.putClientProperty(FlatClientProperties.STYLE, "background: $Actions.Green; foreground: #ffffff");
        approveButton.addActionListener(e -> approvePhase());

        rejectButton = new JButton("Reject", FontIcon.of(Codicons.CLOSE, 16));
        rejectButton.putClientProperty(FlatClientProperties.STYLE, "background: $Actions.Red; foreground: #ffffff");
        rejectButton.addActionListener(e -> rejectPhase());

        inlineActionPanel.add(approveButton);
        inlineActionPanel.add(rejectButton);

                // Wrap text area and action panel together
        JPanel scrollContent = new JPanel(new BorderLayout());
        scrollContent.putClientProperty(FlatClientProperties.STYLE, "background: $TextPane.background");
        scrollContent.add(outputArea, BorderLayout.CENTER);
        scrollContent.add(inlineActionPanel, BorderLayout.SOUTH);

        outputScrollPane = new JScrollPane(scrollContent);
        outputScrollPane.setBorder(BorderFactory.createTitledBorder("Roxy"));

        // Autoscroll to bottom when content changes (messages or action panel visibility)
        scrollContent.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                SwingUtilities.invokeLater(() -> {
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
        JPanel inputSection = new JPanel(new MigLayout("fill, insets 0", "[grow, fill][][][]", "[grow, fill]"));
        
        inputArea = new JTextArea();
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        JScrollPane inputScrollPane = new JScrollPane(inputArea);
        
        sendButton = new JButton("Send", FontIcon.of(Codicons.CHEVRON_RIGHT, 16));
        sendButton.putClientProperty(FlatClientProperties.STYLE, "arc: 999; background: $Component.accentColor; foreground: $List.selectionForeground");

        stopButton = new JButton("Stop", FontIcon.of(Codicons.DEBUG_STOP, 16));
        stopButton.setEnabled(false);
        stopButton.putClientProperty(FlatClientProperties.STYLE, "arc: 999");
        stopButton.addActionListener(e -> { if (currentWorker != null) currentWorker.cancel(true); });

        resetSessionButton = new JButton("Reset Session", FontIcon.of(Codicons.REFRESH, 16));
        resetSessionButton.putClientProperty(FlatClientProperties.STYLE, "arc: 999; foreground: $Label.disabledForeground");
        resetSessionButton.addActionListener(e -> resetSession());

        turnLabel = new JLabel("Turns: 0");
        turnLabel.putClientProperty(FlatClientProperties.STYLE, "font: $small.font; foreground: $Label.disabledForeground");

        JPanel buttonPanel = new JPanel(new MigLayout("insets 0, gap 5", "[]", "[]"));
        buttonPanel.setOpaque(false);
        buttonPanel.add(sendButton, "growx, wrap");
        buttonPanel.add(stopButton, "growx, wrap");
        buttonPanel.add(resetSessionButton, "growx, wrap");
        buttonPanel.add(turnLabel, "center");

        inputSection.add(inputScrollPane, "grow");
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

    private void setupUndoRedo() {
        UndoManager undoManager = new UndoManager();
        inputArea.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));

        InputMap inputMap = inputArea.getInputMap();
        ActionMap actionMap = inputArea.getActionMap();

        KeyStroke undoStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        inputMap.put(undoStroke, "Undo");
        actionMap.put("Undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canUndo()) {
                    undoManager.undo();
                }
            }
        });

        KeyStroke redoStrokeY = KeyStroke.getKeyStroke(KeyEvent.VK_Y, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        KeyStroke redoStrokeZ = KeyStroke.getKeyStroke(KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | java.awt.event.InputEvent.SHIFT_DOWN_MASK);
        inputMap.put(redoStrokeY, "Redo");
        inputMap.put(redoStrokeZ, "Redo");
        actionMap.put("Redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canRedo()) {
                    undoManager.redo();
                }
            }
        });
    }

    private void onAgentTurn(AgentTurnEvent event) {
        SwingUtilities.invokeLater(() -> {
            turnLabel.setText("Turns: " + event.turnNumber());
        });
    }

    private void setupInputContextMenu() {
        inputArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) showPopup(e); }
            @Override
            public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) showPopup(e); }
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
        SwingUtilities.invokeLater(() -> {
            inlineActionPanel.setVisible(false);
        });
    }

    private void onTransitionRequested(WorkflowPhase current, WorkflowPhase requested) {
        SwingUtilities.invokeLater(() -> {
            if (requested != null) {
                approveButton.setText("Approve " + requested.getDisplayName());
                inlineActionPanel.setVisible(true);
                outputArea.appendMessage("system", "Roxy requested a transition to **" + requested.getDisplayName() + "**. Please approve or reject below.");
            } else {
                inlineActionPanel.setVisible(false);
            }
        });
    }

        private void approvePhase() {
        WorkflowPhase requested = workflowService.getPendingPhase();
        if (requested != null) {
            workflowService.approveTransition();
            outputArea.appendMessage("system", "Phase transition to **" + requested.getDisplayName() + "** approved.");
            if (requested == WorkflowPhase.EXPLORE) {
                outputArea.appendMessage("system", "Task complete. We are back in Explore mode. Please enter a new question or task.");
            } else {
                triggerTurn("I approve the transition to " + requested.getDisplayName() + ". Please proceed with the next phase.");
            }
        }
    }

        private void rejectPhase() {
        WorkflowPhase requested = workflowService.getPendingPhase();
        if (requested != null) {
            workflowService.rejectTransition();
            outputArea.appendMessage("system", "Phase transition to **" + requested.getDisplayName() + "** rejected.");
            triggerTurn("I have rejected the transition to " + requested.getDisplayName() + ". Let's discuss what needs to be fixed or adjusted.");
        }
    }

    private void resetSession() {
        int result = JOptionPane.showConfirmDialog(this, "Reset session? This cancels active tasks and clears chat history.", "Reset", JOptionPane.YES_NO_OPTION);
        if (result == JOptionPane.YES_OPTION) {
            workflowService.resetWorkflow();
            chatMemory.clear("default");
            outputArea.clear();
            outputArea.appendMessage("system", "Session reset to Explore phase.");
        }
    }

    private void sendMessage(ActionEvent e) {
        String text = inputArea.getText().trim();
        if (text.isEmpty()) return;

        turnEventBridge.publishUserMessage("user", text);
        outputArea.appendMessage("user", text);
        inputArea.setText("");
        triggerTurn(text);
    }

    private void triggerTurn(String text) {
        if (text == null || text.trim().isEmpty()) return;

        setLoading(true);
        thoughtPanel.clear();
        turnLabel.setText("Turns: 0");

        currentWorker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                try {
                    String systemPrompt = null;
                    if (workflowService.getCurrentPhase() == WorkflowPhase.EXPLORE) {
                        systemPrompt = exploreManager.generateSystemPrompt();
                    }
                    return aiService.chat(text, systemPrompt);
                } catch (Exception ex) {
                    log.error("AI chat interaction failed: {}", ex.getMessage(), ex);
                    return "Error: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                }
            }
            @Override
            protected void done() {
                try {
                    String response = get();
                    outputArea.appendMessage("ai", response);
                } catch (java.util.concurrent.CancellationException ce) {
                    outputArea.appendMessage("system", "_Request cancelled by user._");
                } catch (Exception ex) {
                    log.error("Failed to retrieve AI response: {}", ex.getMessage(), ex);
                    outputArea.appendMessage("ai", "_Error communicating with AI._");
                } finally {
                    currentWorker = null;
                    setLoading(false);
                }
            }
        };
        currentWorker.execute();
    }

                @Override
    public void onJexlExecuted(JexlExecutionEvent event) {
        SwingUtilities.invokeLater(() -> {
            String toolName = "JEXL Execution";
            StringBuilder logContent = new StringBuilder();
            logContent.append("<pre><code>").append(JexlToHtmlConverter.convert(event.script())).append("</code></pre>");
            if (!event.success()) {
                logContent.append("<div class='tool-error'><b>Error:</b> ")
                          .append(StringEscapeUtils.escapeHtml4(event.error()))
                          .append("</div>");
            }
            outputArea.appendToolLog(toolName, logContent.toString());
        });
    }

    private void setLoading(boolean loading) {
        sendButton.setEnabled(!loading);
        inputArea.setEnabled(!loading);
        approveButton.setEnabled(!loading);
        rejectButton.setEnabled(!loading);
        stopButton.setEnabled(loading);
        sendButton.setText(loading ? "Sending..." : "Send");
    }

    

    public MarkdownPane getOutputArea() { return outputArea; }
    private void setupKeyboardShortcuts() {
        InputMap im = inputArea.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = inputArea.getActionMap();

        // Enter sends the message
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send-message");
        am.put("send-message", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendMessage(null);
            }
        });

        // Shift+Enter inserts a newline
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-newline");
        am.put("insert-newline", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                inputArea.insert("\n", inputArea.getCaretPosition());
            }
        });
    }


    public JTextArea getInputArea() { return inputArea; }
    public JButton getSendButton() { return sendButton; }
    public JButton getStopButton() { return stopButton; }
}
