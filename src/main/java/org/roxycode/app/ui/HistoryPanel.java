package org.roxycode.app.ui;

import org.roxycode.app.ai.JexlExecutionEvent;
import org.roxycode.app.ai.JexlExecutionListener;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.events.TurnEventBridge;
import org.roxycode.app.events.UserMessageEvent;
import org.roxycode.app.ui.syntaxhighlight.JexlToHtmlConverter;
import org.apache.commons.text.StringEscapeUtils;

import javax.swing.*;
import java.awt.*;

/**
 * Panel for displaying a persistent history of all AI interactions and tool executions.
 */
public class HistoryPanel extends JPanel implements JexlExecutionListener {
    private final MarkdownPane displayArea;

    public HistoryPanel(TurnEventBridge turnEventBridge, JexlTool jexlTool) {
        setLayout(new BorderLayout());
        displayArea = new MarkdownPane();
        JScrollPane scrollPane = new JScrollPane(displayArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Interaction History"));
        add(scrollPane, BorderLayout.CENTER);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        turnEventBridge.addUserMessageListener(this::onUserMessage);
        turnEventBridge.addCompleteListener(this::onAgentResponse);
        jexlTool.addListener(this);
    }

    private void onUserMessage(UserMessageEvent event) {
        SwingUtilities.invokeLater(() -> displayArea.appendMessage(event.user(), event.message()));
    }

    private void onAgentResponse(AgentTurnCompleteEvent event) {
        SwingUtilities.invokeLater(() -> displayArea.appendMessage(event.agentName(), event.result()));
    }

    @Override
    public void onJexlExecuted(JexlExecutionEvent event) {
        SwingUtilities.invokeLater(() -> {
            String toolName = "JEXL";
            StringBuilder logContent = new StringBuilder();
            logContent.append("<pre><code>").append(JexlToHtmlConverter.convert(event.script())).append("</code></pre>");
            if (event.success()) {
                logContent.append("<div class='tool-result'><b>Result:</b> ").append(StringEscapeUtils.escapeHtml4(ChatPanel.truncateResult(event.result()))).append("</div>");
            } else {
                logContent.append("<div class='tool-error'><b>Error:</b> ").append(StringEscapeUtils.escapeHtml4(event.error())).append("</div>");
            }
            displayArea.appendToolLog(toolName, logContent.toString());
        });
    }
}
