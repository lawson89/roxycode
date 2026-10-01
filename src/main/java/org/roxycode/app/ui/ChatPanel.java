package org.roxycode.app.ui;

import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.services.explore.ExploreManager;
import org.roxycode.app.service.AiService;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;

/**
 * Panel for the chat interface, including output display and user input area.
 */
public class ChatPanel extends JPanel {

    private final AiService aiService;
    private final ExploreManager exploreManager;
    private final JTextPane outputArea;
    private final JEditorPane inputArea;
    private final JButton sendButton;
    private final JButton stopButton;

    public ChatPanel(AiService aiService, ExploreManager exploreManager) {
        this.aiService = aiService;
        this.exploreManager = exploreManager;

        // Main layout: Output grows, Input area at bottom
        setLayout(new MigLayout("fill, insets 10", "[grow, fill]", "[grow, fill][]"));

        // Output area (LLM response)
        outputArea = new JTextPane();
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

        appendToOutput("You: " + text + "\n\n");
        inputArea.setText("");
        setLoading(true);

        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                try {
                    // Use the ExploreManager prompt for the chat
                    String systemPrompt = exploreManager.generateSystemPrompt();
                    return aiService.chat(text, systemPrompt);
                } catch (Exception ex) {
                    return "Error: " + ex.getMessage();
                }
            }

            @Override
            protected void done() {
                try {
                    String response = get();
                    appendToOutput("Roxy: " + response + "\n\n");
                } catch (Exception ex) {
                    appendToOutput("Roxy: Error communicating with AI.\n\n");
                } finally {
                    setLoading(false);
                }
            }
        };
        worker.execute();
    }

    private void appendToOutput(String text) {
        String current = outputArea.getText();
        outputArea.setText(current + text);
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
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