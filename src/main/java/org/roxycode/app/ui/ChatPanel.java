package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;

import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.JexlExecutionEvent;
import org.roxycode.app.ai.JexlExecutionListener;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.services.explore.ExploreManager;
import org.roxycode.app.service.AiService;
import org.roxycode.app.ai.workflow.WorkflowPhase;
import org.roxycode.app.ai.workflow.WorkflowService;
import org.roxycode.app.ui.syntaxhighlight.JexlToHtmlConverter;
import org.apache.commons.text.StringEscapeUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
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
    private final JTextPane outputArea;
    private final JEditorPane inputArea;
    private final JButton sendButton;
    private final JButton stopButton;
    private final JButton approveButton;

    public ChatPanel(AiService aiService, ExploreManager exploreManager, JexlTool jexlTool, WorkflowService workflowService) {
        this.aiService = aiService;
        this.exploreManager = exploreManager;
        this.jexlTool = jexlTool;
        this.workflowService = workflowService;
        this.jexlTool.addListener(this);

        // Main layout: Resizable Split Pane
        setLayout(new BorderLayout());

        // Output area (LLM response)
        outputArea = new JTextPane();
        outputArea.setContentType("text/html");
        outputArea.setEditable(false);
        JScrollPane outputScrollPane = new JScrollPane(outputArea);
        outputScrollPane.setBorder(BorderFactory.createTitledBorder("Assistant"));

        // Input section
        JPanel inputSection = new JPanel(new MigLayout("fill, insets 0", "[grow, fill][][][]", "[grow, fill]"));
        
        inputArea = new JEditorPane();
        JScrollPane inputScrollPane = new JScrollPane(inputArea);
        
        sendButton = new JButton("Send", FontIcon.of(Codicons.CHEVRON_RIGHT, 16));
        sendButton.setToolTipText("Send Message");
        sendButton.putClientProperty(FlatClientProperties.STYLE, "arc: 999; background: $Component.accentColor; foreground: $List.selectionForeground");

        stopButton = new JButton("Stop", FontIcon.of(Codicons.DEBUG_STOP, 16));
        stopButton.setToolTipText("Stop AI");
        stopButton.putClientProperty(FlatClientProperties.STYLE, "arc: 999");
        stopButton.setEnabled(false);

        approveButton = new JButton("Advance", FontIcon.of(Codicons.CHECK, 16));
        approveButton.putClientProperty(FlatClientProperties.STYLE, "arc: 999");
        approveButton.setVisible(false);
        approveButton.addActionListener(e -> advancePhase());

        JPanel buttonPanel = new JPanel(new MigLayout("insets 0, gap 5", "[]", "[][]"));
        buttonPanel.setOpaque(false);
        buttonPanel.add(sendButton, "growx, wrap");
        buttonPanel.add(stopButton, "growx, wrap");
        buttonPanel.add(approveButton, "growx");

        inputSection.add(inputScrollPane, "grow");
        inputSection.add(buttonPanel, "aligny bottom");

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, outputScrollPane, inputSection);
        splitPane.setResizeWeight(0.7);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(splitPane, BorderLayout.CENTER);

        sendButton.addActionListener(this::sendMessage);
        workflowService.addPhaseListener(this::onPhaseChanged);
    }

    private void onPhaseChanged(WorkflowPhase phase) {
        boolean canAdvance = phase.ordinal() < WorkflowPhase.values().length - 1;
        approveButton.setVisible(canAdvance);
        if (canAdvance) {
            approveButton.setToolTipText("Advance from " + phase.getDisplayName());
        }
    }

    private void advancePhase() {
        WorkflowPhase current = workflowService.getCurrentPhase();
        int nextOrdinal = current.ordinal() + 1;
        if (nextOrdinal < WorkflowPhase.values().length) {
            WorkflowPhase next = WorkflowPhase.values()[nextOrdinal];
            workflowService.setCurrentPhase(next);
            appendToOutput("<div style='margin: 10px 0; color: #98C379;'><b>System:</b> Advancing to phase <b>" + next.getDisplayName() + "</b>...</div><br>");
        }
    }

    private void sendMessage(ActionEvent e) {
        String text = inputArea.getText().trim();
        if (text.isEmpty()) {
            return;
        }

        appendToOutput("<b>You:</b> " + StringEscapeUtils.escapeHtml4(text).replace("\n", "<br>") + "<br><br>");
        inputArea.setText("");
        setLoading(true);

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
                    appendToOutput("<b>Roxy:</b> " + StringEscapeUtils.escapeHtml4(response).replace("\n", "<br>") + "<br><br>");
                } catch (Exception ex) {
                    log.error("Failed to retrieve AI response: {}", ex.getMessage(), ex);
                    appendToOutput("<b>Roxy:</b> <font color='#E06C75'>Error communicating with AI.</font><br><br>");
                } finally {
                    setLoading(false);
                }
            }
        };
        worker.execute();
    }

    private void appendToOutput(String htmlSnippet) {
        SwingUtilities.invokeLater(() -> {
            HTMLDocument doc = (HTMLDocument) outputArea.getStyledDocument();
            HTMLEditorKit kit = (HTMLEditorKit) outputArea.getEditorKit();
            try {
                kit.insertHTML(doc, doc.getLength(), htmlSnippet, 0, 0, null);
            } catch (Exception ex) {
                log.error("Failed to append HTML to chat output: {}", ex.getMessage());
            }
            outputArea.setCaretPosition(doc.getLength());
        });
    }

    @Override
    public void onJexlExecuted(JexlExecutionEvent event) {
        String html = JexlToHtmlConverter.convert(event.script());
        StringBuilder snippet = new StringBuilder();
        snippet.append("<div style='margin: 10px; padding: 10px; border: 1px solid #3E4451; background-color: #21252B;'>");
        snippet.append("<b style='color: #61AFEF;'>🛠️ Tool Execution (JEXL):</b><br><br>");
        snippet.append(html);
        if (event.success()) {
            snippet.append("<br><b style='color: #98C379;'>Result:</b> " + StringEscapeUtils.escapeHtml4(String.valueOf(event.result())));
        } else {
            snippet.append("<br><b style='color: #E06C75;'>Error:</b> " + StringEscapeUtils.escapeHtml4(event.error()));
        }
        snippet.append("</div><br>");
        
        appendToOutput(snippet.toString());
    }

    private void setLoading(boolean loading) {
        sendButton.setEnabled(!loading);
        inputArea.setEnabled(!loading);
        approveButton.setEnabled(!loading);
        if (loading) {
            sendButton.setText("Sending...");
        } else {
            sendButton.setText("Send");
        }
    }

    public JTextPane getOutputArea() {
        return outputArea;
    }

    public JEditorPane getInputArea() {
        return inputArea;
    }

    public JButton getSendButton() {
        return sendButton;
    }

    public JButton getStopButton() {
        return stopButton;
    }
}
