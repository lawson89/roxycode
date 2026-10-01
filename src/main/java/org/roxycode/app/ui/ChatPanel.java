package org.roxycode.app.ui;

import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.JexlExecutionEvent;
import org.roxycode.app.ai.JexlExecutionListener;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.services.explore.ExploreManager;
import org.roxycode.app.service.AiService;
import org.roxycode.app.ui.syntaxhighlight.JexlToHtmlConverter;
import org.apache.commons.text.StringEscapeUtils;
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
    private final JTextPane outputArea;
    private final JEditorPane inputArea;
    private final JButton sendButton;
    private final JButton stopButton;

    public ChatPanel(AiService aiService, ExploreManager exploreManager, JexlTool jexlTool) {
        this.aiService = aiService;
        this.exploreManager = exploreManager;
        this.jexlTool = jexlTool;
        this.jexlTool.addListener(this);

        // Main layout: Output grows, Input area at bottom
        setLayout(new MigLayout("fill, insets 10", "[grow, fill]", "[grow, fill][]"));

        // Output area (LLM response)
        outputArea = new JTextPane();
        outputArea.setContentType("text/html");
        outputArea.setEditable(false);
        JScrollPane outputScrollPane = new JScrollPane(outputArea);
        outputScrollPane.setBorder(BorderFactory.createTitledBorder("Assistant"));

        // Input section
        JPanel inputSection = new JPanel(new MigLayout("fill, insets 0", "[grow, fill][][]", "[fill]"));
        
        inputArea = new JEditorPane();
        JScrollPane inputScrollPane = new JScrollPane(inputArea);
        
        sendButton = new JButton("Send");
        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);

        inputSection.add(inputScrollPane, "h 80!");
        inputSection.add(sendButton, "aligny bottom");
        inputSection.add(stopButton, "aligny bottom");

        add(outputScrollPane, "grow, wrap");
        add(inputSection, "growx, h 80!");

        sendButton.addActionListener(this::sendMessage);
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