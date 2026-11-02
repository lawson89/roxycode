package org.roxycode.app.ui;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import org.apache.commons.lang3.StringUtils;

public final class MarkdownPane extends JTextPane {
    private static final long serialVersionUID = 1L;
    private static final String COPY_PREFIX = "copy:";
    private static final String SPEAK_PREFIX = "speak:";
    private static final int COPY_FEEDBACK_DELAY_MS = 1500;
    private static final int INITIAL_MESSAGE_COUNT = 1;
    private static final String ERROR_RENDERING_HTML = "<div>Error rendering message</div>";

    private final List<Message> messages = new ArrayList<>();
    private final boolean taskMode;
    int copiedMessageIndex = -1;
    int speakingMessageIndex = -1;
    Timer copyFeedbackTimer;
    String lastCopiedMarkdown;
    private transient BiConsumer<Integer, String> speakActionHandler;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    public record ChatTranscriptEntry(String role, String content, String timestamp) {
    }

    private record Message(String role, String content, String timestamp, boolean isTool, boolean success, String error,
            String renderedHtml) {
    }

    public MarkdownPane(boolean taskMode) {
        this.taskMode = taskMode;
        setEditable(false);
        setContentType("text/html");
        setOpaque(true);
        putClientProperty("JEditorPane.honorDisplayProperties", true);
        SwingHtmlUtils.configureReadOnlyCaret(this);

        addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                handleHyperlinkActivated(e.getDescription());
            }
        });

        updateTheme();
        SwingHtmlUtils.installTextContextMenu(this);
    }

    private void handleHyperlinkActivated(String desc) {
        if (desc == null) {
            return;
        }
        if (desc.startsWith(COPY_PREFIX)) {
            try {
                copyMessageMarkdown(Integer.parseInt(desc.substring(COPY_PREFIX.length())));
            } catch (NumberFormatException ignored) {
            }
        } else if (desc.startsWith(SPEAK_PREFIX)) {
            try {
                toggleSpeakMessage(Integer.parseInt(desc.substring(SPEAK_PREFIX.length())));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    public void setSpeakActionHandler(BiConsumer<Integer, String> speakActionHandler) {
        this.speakActionHandler = speakActionHandler;
    }

    public int getSpeakingMessageIndex() {
        return speakingMessageIndex;
    }

    public int getMessageCount() {
        return messages.size();
    }

    public void setSpeakingMessageIndex(int index) {
        int previousSpeakingIndex = this.speakingMessageIndex;
        if (previousSpeakingIndex == index) {
            return;
        }
        this.speakingMessageIndex = index;
        boolean updated = true;
        if (previousSpeakingIndex >= 0) {
            updated = updateActionCellInPlace(previousSpeakingIndex);
        }
        if (index >= 0) {
            updated = updateActionCellInPlace(index) && updated;
        }
        if (!updated && !messages.isEmpty()) {
            renderAll(false);
        }
    }

    public void toggleSpeakMessage(int index) {
        if (index < 0 || index >= messages.size()) {
            return;
        }
        Message msg = messages.get(index);
        if (msg.isTool()) {
            return;
        }
        if (speakActionHandler != null) {
            speakActionHandler.accept(index, msg.content());
        }
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (messages != null) {
            updateTheme();
        }
    }

    public void updateTheme() {
        SwingHtmlUtils.applyTheme(this, 10);
        renderAll(false);
    }

    public void appendMessage(String role, String content) {
        String safeContent = StringUtils.defaultString(content);
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        int index = messages.size();
        Message msg = new Message(role, safeContent, timestamp, false, true, null,
                renderMarkdownBodySafely(safeContent));
        messages.add(msg);
        appendRenderedEntry(formatMessageEntryHtml(msg, index));
    }

    public void appendToolLog(String output, boolean success, String error) {
        String safeOutput = StringUtils.defaultString(output);
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        int index = messages.size();
        Message msg = new Message(null, safeOutput, timestamp, true, success, error,
                renderToolLogSafely(safeOutput, success, error, timestamp));
        messages.add(msg);
        appendRenderedEntry(formatMessageEntryHtml(msg, index));
    }

    public void copyMessageMarkdown(int index) {
        if (index < 0 || index >= messages.size()) {
            return;
        }
        Message msg = messages.get(index);
        if (msg.isTool()) {
            return;
        }
        String rawMarkdown = msg.content();
        this.lastCopiedMarkdown = rawMarkdown;
        SwingHtmlUtils.copyToClipboard(rawMarkdown);

        if (copyFeedbackTimer != null && copyFeedbackTimer.isRunning()) {
            copyFeedbackTimer.stop();
        }
        int previousCopiedIndex = this.copiedMessageIndex;
        this.copiedMessageIndex = index;

        if (previousCopiedIndex >= 0 && previousCopiedIndex != index) {
            updateActionCellInPlace(previousCopiedIndex);
        }
        if (!updateActionCellInPlace(index)) {
            renderAll(false);
        }

        copyFeedbackTimer = new Timer(COPY_FEEDBACK_DELAY_MS, e -> {
            int indexToRevert = this.copiedMessageIndex;
            this.copiedMessageIndex = -1;
            if (indexToRevert >= 0 && !updateActionCellInPlace(indexToRevert)) {
                renderAll(false);
            }
        });
        copyFeedbackTimer.setRepeats(false);
        copyFeedbackTimer.start();
    }

    private boolean updateActionCellInPlace(int messageIndex) {
        if (!(getDocument() instanceof HTMLDocument doc)) {
            return false;
        }
        Element cellElem = doc.getElement(ChatHtmlTemplates.COPY_CELL_ID_PREFIX + messageIndex);
        if (cellElem == null) {
            return false;
        }
        int savedCaret = getCaretPosition();
        try {
            boolean copied = messageIndex == this.copiedMessageIndex;
            boolean speaking = messageIndex == this.speakingMessageIndex;
            doc.setInnerHTML(cellElem, ChatHtmlTemplates.renderCopyActionHtml(messageIndex, copied, speaking));
            setCaretPosition(Math.min(savedCaret, doc.getLength()));
            return true;
        } catch (BadLocationException | IOException e) {
            return false;
        }
    }

    private void appendRenderedEntry(String entryHtml) {
        if (messages.size() == INITIAL_MESSAGE_COUNT) {
            setText("<html><body>" + entryHtml + "</body></html>");
            scrollToBottomLater();
            return;
        }
        if (getDocument() instanceof HTMLDocument doc) {
            Element bodyElement = findBodyElement(doc.getDefaultRootElement());
            if (bodyElement != null) {
                try {
                    doc.insertBeforeEnd(bodyElement, entryHtml);
                    scrollToBottomLater();
                    return;
                } catch (BadLocationException | IOException ignored) {
                }
            }
        }
        renderAll(true);
    }

    private void scrollToBottomLater() {
        SwingUtilities.invokeLater(() -> setCaretPosition(getDocument().getLength()));
    }

    private static Element findBodyElement(Element element) {
        if (element == null) {
            return null;
        }
        if (element.getAttributes().getAttribute(StyleConstants.NameAttribute) == HTML.Tag.BODY
                || "body".equalsIgnoreCase(element.getName())) {
            return element;
        }
        for (int i = 0; i < element.getElementCount(); i++) {
            Element found = findBodyElement(element.getElement(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String renderMarkdownBodySafely(String content) {
        try {
            return SwingHtmlUtils.renderMarkdownToHtml(content);
        } catch (Exception e) {
            return ERROR_RENDERING_HTML;
        }
    }

    private static String renderToolLogSafely(String output, boolean success, String error, String timestamp) {
        try {
            return ChatHtmlTemplates.renderToolLog(output, success, error, timestamp);
        } catch (Exception e) {
            return ERROR_RENDERING_HTML;
        }
    }

    private String formatMessageEntryHtml(Message msg, int index) {
        try {
            if (msg.isTool()) {
                return msg.renderedHtml();
            }
            String safeRole = "";
            if (msg.role() != null) {
                safeRole = msg.role().toLowerCase(Locale.ROOT);
            }
            return ChatHtmlTemplates.renderChatEntry(safeRole, msg.renderedHtml(), msg.timestamp(), index,
                    index == copiedMessageIndex, index == speakingMessageIndex);
        } catch (Exception e) {
            return ERROR_RENDERING_HTML;
        }
    }

    private void renderAll(boolean scrollToBottom) {
        int savedCaret = getCaretPosition();
        StringBuilder fullHtml = new StringBuilder("<html><body>");
        if (messages.isEmpty()) {
            fullHtml.append(ChatHtmlTemplates.renderWelcomeCard(taskMode));
        } else {
            for (int i = 0; i < messages.size(); i++) {
                fullHtml.append(formatMessageEntryHtml(messages.get(i), i));
            }
        }
        fullHtml.append("</body></html>");
        setText(fullHtml.toString());
        if (scrollToBottom) {
            scrollToBottomLater();
        } else {
            setCaretPosition(Math.min(savedCaret, getDocument().getLength()));
        }
    }

    public List<ChatTranscriptEntry> getConversationMessages() {
        List<ChatTranscriptEntry> transcript = new ArrayList<>();
        for (Message msg : messages) {
            if (!msg.isTool() && msg.role() != null
                    && ("user".equalsIgnoreCase(msg.role()) || "ai".equalsIgnoreCase(msg.role()))) {
                transcript.add(new ChatTranscriptEntry(msg.role(), msg.content(), msg.timestamp()));
            }
        }
        return List.copyOf(transcript);
    }

    public void clear() {
        if (copyFeedbackTimer != null && copyFeedbackTimer.isRunning()) {
            copyFeedbackTimer.stop();
        }
        copiedMessageIndex = -1;
        speakingMessageIndex = -1;
        messages.clear();
        renderAll(true);
    }
}
