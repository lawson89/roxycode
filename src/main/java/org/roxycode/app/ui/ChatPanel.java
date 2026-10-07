package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.text.StringEscapeUtils;
import org.roxycode.app.ai.JexlExecutionEvent;
import org.roxycode.app.ai.JexlExecutionListener;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.services.ExploreManager;
import org.roxycode.app.service.AiService;
import org.roxycode.app.ai.workflow.WorkflowPhase;
import org.roxycode.app.ai.workflow.WorkflowService;
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
    private final MarkdownPane outputArea;
    private final ThoughtPanel thoughtPanel;
    private final JTextArea inputArea;
    private final JButton sendButton;
    private final JButton stopButton;
    private final JButton approveButton;
    private final JButton rejectButton;
    private final JButton cancelTaskButton;
    private final JLabel turnLabel;
    private SwingWorker<String, Void> currentWorker;

    public ChatPanel(AiService aiService, ExploreManager exploreManager, JexlTool jexlTool, 
                     WorkflowService workflowService, TurnEventBridge turnEventBridge) {
        this.aiService = aiService;
        this.exploreManager = exploreManager;
        this.jexlTool = jexlTool;
        this.workflowService = workflowService;
        this.turnEventBridge = turnEventBridge;
        this.jexlTool.addListener(this);
        
        turnEventBridge.addTurnListener(this::onAgentTurn);
        turnEventBridge.addCompleteListener(event -> setLoading(false));

        setLayout(new BorderLayout());

        // Output section
        outputArea = new MarkdownPane();
        JScrollPane outputScrollPane = new JScrollPane(outputArea);
        outputScrollPane.setBorder(BorderFactory.createTitledBorder("Assistant"));

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

        approveButton = new JButton("Advance", FontIcon.of(Codicons.CHECK, 16));
        approveButton.putClientProperty(FlatClientProperties.STYLE, "arc: 999");
        approveButton.setVisible(false);
        approveButton.addActionListener(e -> approvePhase());

        rejectButton = new JButton("Reject", FontIcon.of(Codicons.CLOSE, 16));
        rejectButton.putClientProperty(FlatClientProperties.STYLE, "arc: 999");
        rejectButton.setVisible(false);
        rejectButton.addActionListener(e -> rejectPhase());

        cancelTaskButton = new JButton("Cancel Task", FontIcon.of(Codicons.DEBUG_STOP, 16));
        cancelTaskButton.putClientProperty(FlatClientProperties.STYLE, "arc: 999; foreground: $Label.disabledForeground");
        cancelTaskButton.setVisible(false);
        cancelTaskButton.addActionListener(e -> cancelTask());

        turnLabel = new JLabel("Turns: 0");
        turnLabel.putClientProperty(FlatClientProperties.STYLE, "font: $small.font; foreground: $Label.disabledForeground");

        JPanel buttonPanel = new JPanel(new MigLayout("insets 0, gap 5", "[]", "[][][][]"));
        buttonPanel.setOpaque(false);
        buttonPanel.add(sendButton, "growx, wrap");
        buttonPanel.add(stopButton, "growx, wrap");
        buttonPanel.add(approveButton, "growx, wrap");
        buttonPanel.add(rejectButton, "growx, wrap");
        buttonPanel.add(cancelTaskButton, "growx, wrap");
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
            approveButton.setVisible(false);
            rejectButton.setVisible(false);
            updateCancelButtonVisibility();
        });
    }

    private void onTransitionRequested(WorkflowPhase current, WorkflowPhase requested) {
        SwingUtilities.invokeLater(() -> {
            if (requested != null) {
                approveButton.setText("Approve " + requested.getDisplayName());
                approveButton.setVisible(true);
                rejectButton.setVisible(true);
                outputArea.appendMessage("System", "Roxy requested a transition to **" + requested.getDisplayName() + "**. Please approve or reject.");
                updateCancelButtonVisibility();
            } else {
                approveButton.setVisible(false);
                rejectButton.setVisible(false);
                updateCancelButtonVisibility();
            }
        });
    }

    private void approvePhase() {
        WorkflowPhase requested = workflowService.getPendingPhase();
        if (requested != null) {
            workflowService.approveTransition();
            outputArea.appendMessage("System", "Phase transition to **" + requested.getDisplayName() + "** approved.");
            triggerTurn("I approve the transition to " + requested.getDisplayName() + ". Please proceed with the next phase.");
        }
    }

    private void rejectPhase() {
        WorkflowPhase requested = workflowService.getPendingPhase();
        if (requested != null) {
            workflowService.rejectTransition();
            outputArea.appendMessage("System", "Phase transition to **" + requested.getDisplayName() + "** rejected.");
        }
    }

    private void cancelTask() {
        int result = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to cancel the current task and reset to Explore phase?",
                "Cancel Task",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (result == JOptionPane.YES_OPTION) {
            workflowService.resetWorkflow();
            outputArea.appendMessage("System", "Task cancelled. Workflow reset to **Explore** phase.");
        }
    }

    private void updateCancelButtonVisibility() {
        boolean isPending = workflowService.getPendingPhase() != null;
        boolean isNotExplore = workflowService.getCurrentPhase() != WorkflowPhase.EXPLORE;
        cancelTaskButton.setVisible(isPending || isNotExplore);
    }

    private void sendMessage(ActionEvent e) {
        String text = inputArea.getText().trim();
        if (text.isEmpty()) return;

        turnEventBridge.publishUserMessage("You", text);
        outputArea.appendMessage("You", text);
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
                    return "Error: " + ex.getMessage();
                }
            }
            @Override
            protected void done() {
                try {
                    String response = get();
                    outputArea.appendMessage("Roxy", response);
                } catch (java.util.concurrent.CancellationException ce) {
                    outputArea.appendMessage("System", "_Request cancelled by user._");
                } catch (Exception ex) {
                    log.error("Failed to retrieve AI response: {}", ex.getMessage(), ex);
                    outputArea.appendMessage("Roxy", "_Error communicating with AI._");
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
            String toolName = "";
            StringBuilder logContent = new StringBuilder();
            logContent.append("<pre><code>").append(JexlToHtmlConverter.convert(event.script())).append("</code></pre>");
            if (event.success()) {
                logContent.append("<div class='tool-result'><b>Result:</b> ").append(StringEscapeUtils.escapeHtml4(truncateResult(event.result()))).append("</div>");
            } else {
                logContent.append("<div class='tool-error'><b>Error:</b> ").append(StringEscapeUtils.escapeHtml4(event.error())).append("</div>");
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

    static String truncateResult(Object result) {
        if (result == null) return "null";
        String resultStr = String.valueOf(result);
        return resultStr.length() > 100 ? resultStr.substring(0, 100) + "... [Truncated]" : resultStr;
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