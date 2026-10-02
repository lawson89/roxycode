package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.text.StringEscapeUtils;
import org.roxycode.app.ai.JexlExecutionEvent;
import org.roxycode.app.ai.JexlExecutionListener;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.services.explore.ExploreManager;
import org.roxycode.app.service.AiService;
import org.roxycode.app.ai.workflow.WorkflowPhase;
import org.roxycode.app.ai.workflow.WorkflowService;
import org.roxycode.app.events.TurnEventBridge;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.ui.components.ThoughtPanel;
import org.roxycode.app.ui.syntaxhighlight.JexlToHtmlConverter;
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
    private final MarkdownPane outputArea;
    private final ThoughtPanel thoughtPanel;
    private final JTextArea inputArea;
    private final JButton sendButton;
    private final JButton stopButton;
    private final JButton approveButton;
    private final JLabel turnLabel;

    public ChatPanel(AiService aiService, ExploreManager exploreManager, JexlTool jexlTool, 
                     WorkflowService workflowService, TurnEventBridge turnEventBridge) {
        this.aiService = aiService;
        this.exploreManager = exploreManager;
        this.jexlTool = jexlTool;
        this.workflowService = workflowService;
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

        approveButton = new JButton("Advance", FontIcon.of(Codicons.CHECK, 16));
        approveButton.putClientProperty(FlatClientProperties.STYLE, "arc: 999");
        approveButton.setVisible(false);
        approveButton.addActionListener(e -> advancePhase());

        turnLabel = new JLabel("Turns: 0");
        turnLabel.putClientProperty(FlatClientProperties.STYLE, "font: $small.font; foreground: $Label.disabledForeground");

        JPanel buttonPanel = new JPanel(new MigLayout("insets 0, gap 5", "[]", "[][][]"));
        buttonPanel.setOpaque(false);
        buttonPanel.add(sendButton, "growx, wrap");
        buttonPanel.add(stopButton, "growx, wrap");
        buttonPanel.add(approveButton, "growx, wrap");
        buttonPanel.add(turnLabel, "center");

        inputSection.add(inputScrollPane, "grow");
        inputSection.add(buttonPanel, "aligny bottom");

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, outputWrapper, inputSection);
        splitPane.setResizeWeight(0.8);
        splitPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(splitPane, BorderLayout.CENTER);

        sendButton.addActionListener(this::sendMessage);
        workflowService.addPhaseListener(this::onPhaseChanged);
        setupInputContextMenu();
        setupUndoRedo();
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
        boolean canAdvance = phase.ordinal() < WorkflowPhase.values().length - 1;
        approveButton.setVisible(canAdvance);
    }

    private void advancePhase() {
        WorkflowPhase current = workflowService.getCurrentPhase();
        int nextOrdinal = current.ordinal() + 1;
        if (nextOrdinal < WorkflowPhase.values().length) {
            WorkflowPhase next = WorkflowPhase.values()[nextOrdinal];
            workflowService.transitionPhase(next, "Phase transition from " + current.name());
            outputArea.appendMessage("System", "Advancing to phase **" + next.getDisplayName() + "**...");
        }
    }

    private void sendMessage(ActionEvent e) {
        String text = inputArea.getText().trim();
        if (text.isEmpty()) return;

        outputArea.appendMessage("You", text);
        inputArea.setText("");
        setLoading(true);
        thoughtPanel.clear();
        turnLabel.setText("Turns: 0");

        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                try {
                    String systemPrompt = exploreManager.generateSystemPrompt();
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
                } catch (Exception ex) {
                    log.error("Failed to retrieve AI response: {}", ex.getMessage(), ex);
                    outputArea.appendMessage("Roxy", "_Error communicating with AI._");
                } finally {
                    setLoading(false);
                }
            }
        };
        worker.execute();
    }

    @Override
    public void onJexlExecuted(JexlExecutionEvent event) {
        SwingUtilities.invokeLater(() -> {
            String toolName = "JEXL";
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
        sendButton.setText(loading ? "Sending..." : "Send");
    }

    static String truncateResult(Object result) {
        if (result == null) return "null";
        String resultStr = String.valueOf(result);
        return resultStr.length() > 100 ? resultStr.substring(0, 100) + "... [Truncated]" : resultStr;
    }

    public MarkdownPane getOutputArea() { return outputArea; }
    public JTextArea getInputArea() { return inputArea; }
    public JButton getSendButton() { return sendButton; }
    public JButton getStopButton() { return stopButton; }
}
