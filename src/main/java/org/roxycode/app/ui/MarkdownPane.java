package org.roxycode.app.ui;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;

/**
 * A JTextPane that renders Markdown messages using native Java templates. Supports streaming
 * updates by re-rendering the message history.
 */
public final class MarkdownPane extends JTextPane {
    private static final long serialVersionUID = 1L;
    private static final String COPY_PREFIX = "copy:";
    private static final int COPY_FEEDBACK_DELAY_MS = 1500;

    private final transient Parser parser;
    private final transient HtmlRenderer renderer;
    private final List<Message> messages = new ArrayList<>();
    private int copiedMessageIndex = -1;
    private Timer copyFeedbackTimer;
    private String lastCopiedMarkdown;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private record Message(
            String role,
            StringBuilder content,
            String timestamp,
            boolean isTool,
            boolean success,
            String error) {}

    public MarkdownPane() {
        setEditable(false);
        setContentType("text/html");
        setOpaque(true);
        putClientProperty("JEditorPane.honorDisplayProperties", true);

        parser = SwingHtmlUtils.markdownParser();
        renderer = SwingHtmlUtils.markdownRenderer();

        addHyperlinkListener(
                e -> {
                    if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                        String desc = e.getDescription();
                        if (desc != null && desc.startsWith(COPY_PREFIX)) {
                            try {
                                int index = Integer.parseInt(desc.substring(COPY_PREFIX.length()));
                                copyMessageMarkdown(index);
                            } catch (NumberFormatException ignored) {
                                // Ignore malformed copy link index
                            }
                        }
                    }
                });

        updateTheme();
        SwingHtmlUtils.installTextContextMenu(this);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (parser != null) {
            updateTheme();
        }
    }

    public void updateTheme() {
        SwingHtmlUtils.applyTheme(this, 10);
        renderAll(false);
    }

    public void appendMessage(String role, String content) {
        String safeContent = content != null ? content : "";
        messages.add(
                new Message(
                        role,
                        new StringBuilder(safeContent),
                        LocalDateTime.now().format(TIME_FORMATTER),
                        false,
                        true,
                        null));
        renderAll(true);
    }

    public void appendToolLog(String output, boolean success, String error) {
        String safeOutput = output != null ? output : "";
        messages.add(
                new Message(
                        null,
                        new StringBuilder(safeOutput),
                        LocalDateTime.now().format(TIME_FORMATTER),
                        true,
                        success,
                        error));
        renderAll(true);
    }

    /**
     * Copies the raw Markdown content of the message at the given index to the system clipboard and
     * triggers temporary visual feedback.
     *
     * @param index the 0-based message index
     */
    public void copyMessageMarkdown(int index) {
        if (index < 0 || index >= messages.size()) {
            return;
        }
        Message msg = messages.get(index);
        if (msg.isTool()) {
            return;
        }
        String rawMarkdown = msg.content().toString();
        this.lastCopiedMarkdown = rawMarkdown;
        try {
            StringSelection selection = new StringSelection(rawMarkdown);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        } catch (Exception ignored) {
            // Ignore clipboard access errors in headless or restricted environments
        }

        this.copiedMessageIndex = index;
        renderAll(false);

        if (copyFeedbackTimer != null && copyFeedbackTimer.isRunning()) {
            copyFeedbackTimer.stop();
        }
        copyFeedbackTimer =
                new Timer(
                        COPY_FEEDBACK_DELAY_MS,
                        e -> {
                            this.copiedMessageIndex = -1;
                            renderAll(false);
                        });
        copyFeedbackTimer.setRepeats(false);
        copyFeedbackTimer.start();
    }

    int getCopiedMessageIndex() {
        return copiedMessageIndex;
    }

    String getLastCopiedMarkdown() {
        return lastCopiedMarkdown;
    }

    private void renderAll(boolean scrollToBottom) {
        int savedCaret = getCaretPosition();
        StringBuilder fullHtml = new StringBuilder("<html><body>");
        if (messages.isEmpty()) {
            fullHtml.append(ChatHtmlTemplates.renderWelcomeCard());
        } else {
            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                try {
                    if (msg.isTool()) {
                        fullHtml.append(
                                ChatHtmlTemplates.renderToolLog(
                                        msg.content().toString(), msg.success(), msg.error()));
                    } else {
                        String renderedMarkdown =
                                SwingHtmlUtils.styleMarkdownTables(
                                        renderer.render(parser.parse(msg.content().toString())));
                        String highlightedHtml =
                                JexlToHtmlConverter.highlightCodeBlocks(renderedMarkdown);
                        fullHtml.append(
                                ChatHtmlTemplates.renderChatEntry(
                                        msg.role().toLowerCase(Locale.ROOT),
                                        highlightedHtml,
                                        msg.timestamp(),
                                        i,
                                        i == copiedMessageIndex));
                    }
                } catch (Exception e) {
                    fullHtml.append("<div>Error rendering message</div>");
                }
            }
        }
        fullHtml.append("</body></html>");
        setText(fullHtml.toString());
        if (scrollToBottom) {
            SwingUtilities.invokeLater(() -> setCaretPosition(getDocument().getLength()));
        } else {
            int docLen = getDocument().getLength();
            setCaretPosition(Math.min(savedCaret, docLen));
        }
    }

    public void clear() {
        if (copyFeedbackTimer != null && copyFeedbackTimer.isRunning()) {
            copyFeedbackTimer.stop();
        }
        copiedMessageIndex = -1;
        messages.clear();
        renderAll(true);
    }
}
