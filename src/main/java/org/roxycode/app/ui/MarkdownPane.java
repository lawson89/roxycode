package org.roxycode.app.ui;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.model.ChatTranscriptEntry;

/**
 * Swing {@link JTextPane} component that renders chat transcripts and collapsible JEXL tool execution logs as
 * theme-aware HTML 3.2 tables.
 */
public final class MarkdownPane extends JTextPane {
    private static final long serialVersionUID = 1L;
    private static final String COPY_PREFIX = "copy:";
    private static final String FEATURE_PREFIX = "feature:";
    private static final String TOGGLE_TOOL_PREFIX = "toggle-tool:";
    private static final int COPY_FEEDBACK_DELAY_MS = 1500;
    private static final int INITIAL_MESSAGE_COUNT = 1;
    private static final String ERROR_RENDERING_HTML = "<div>Error rendering message</div>";
    private static final int MAX_UI_MESSAGES = 200;
    private boolean pruned = false;

    private final List<Message> messages = new ArrayList<>();
    private final boolean taskMode;
    private java.util.function.Consumer<String> onMoveToFeatureListener;
    int copiedMessageIndex = -1;
    Timer copyFeedbackTimer;
    String lastCopiedMarkdown;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    public record PersistedChatEntry(String role, String content, String timestamp, boolean isTool, boolean success,
            String error, boolean toolExpanded) {
    }

    private record Message(String role, String content, String timestamp, boolean isTool, boolean success, String error,
            String renderedHtml, boolean toolExpanded) {
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
        } else if (desc.startsWith(FEATURE_PREFIX)) {
            try {
                int index = Integer.parseInt(desc.substring(FEATURE_PREFIX.length()));
                if (index >= 0 && index < messages.size() && onMoveToFeatureListener != null) {
                    onMoveToFeatureListener.accept(messages.get(index).content());
                }
            } catch (NumberFormatException ignored) {
            }
        } else if (desc.startsWith(TOGGLE_TOOL_PREFIX)) {
            try {
                toggleToolLog(Integer.parseInt(desc.substring(TOGGLE_TOOL_PREFIX.length())));
            } catch (NumberFormatException ignored) {
            }
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

    public void setOnMoveToFeatureListener(java.util.function.Consumer<String> listener) {
        this.onMoveToFeatureListener = listener;
    }

    public void appendMessage(String role, String content) {
        String safeContent = StringUtils.defaultString(content);
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        Message msg = new Message(role, safeContent, timestamp, false, true, null,
                renderMarkdownBodySafely(safeContent), true);
        messages.add(msg);
        if (pruneMessagesIfNeeded()) {
            renderAll(true);
        } else {
            appendRenderedEntry(formatMessageEntryHtml(msg, messages.size() - 1));
        }
    }

    public void appendToolLog(String output, boolean success, String error) {
        String safeOutput = StringUtils.defaultString(output);
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        boolean expanded = ChatHtmlTemplates.shouldDefaultToolExpanded(safeOutput, success);
        Message msg = new Message(null, safeOutput, timestamp, true, success, error, null, expanded);
        messages.add(msg);
        if (pruneMessagesIfNeeded()) {
            renderAll(true);
        } else {
            appendRenderedEntry(formatMessageEntryHtml(msg, messages.size() - 1));
        }
    }

    private boolean pruneMessagesIfNeeded() {
        if (messages.size() <= MAX_UI_MESSAGES) {
            return false;
        }
        while (messages.size() > MAX_UI_MESSAGES) {
            messages.remove(0);
        }
        pruned = true;
        return true;
    }

    public void toggleToolLog(int index) {
        if (index < 0 || index >= messages.size()) {
            return;
        }
        Message msg = messages.get(index);
        if (!msg.isTool()) {
            return;
        }
        Message updated = new Message(msg.role(), msg.content(), msg.timestamp(), true, msg.success(), msg.error(),
                msg.renderedHtml(), !msg.toolExpanded());
        messages.set(index, updated);
        if (!updateToolCellInPlace(index, updated)) {
            renderAll(false);
        }
    }

    private boolean updateToolCellInPlace(int messageIndex, Message msg) {
        if (!(getDocument() instanceof HTMLDocument doc)) {
            return false;
        }
        Element cellElem = doc.getElement(ChatHtmlTemplates.TOOL_BODY_CELL_ID_PREFIX + messageIndex);
        if (cellElem == null) {
            return false;
        }
        int savedCaret = getCaretPosition();
        try {
            doc.setInnerHTML(cellElem, ChatHtmlTemplates.renderToolBodyCellContent(msg.content(), msg.success(),
                    msg.error(), msg.timestamp(), messageIndex, msg.toolExpanded()));
            setCaretPosition(Math.min(savedCaret, doc.getLength()));
            return true;
        } catch (BadLocationException | IOException e) {
            return false;
        }
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
            doc.setInnerHTML(cellElem, ChatHtmlTemplates.renderAiActionCellContent(messageIndex, copied, !taskMode));
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

    private static String renderToolLogSafely(String output, boolean success, String error, String timestamp,
            int messageIndex, boolean expanded) {
        try {
            return ChatHtmlTemplates.renderToolLog(output, success, error, timestamp, messageIndex, expanded);
        } catch (Exception e) {
            return ERROR_RENDERING_HTML;
        }
    }

    private String formatMessageEntryHtml(Message msg, int index) {
        try {
            if (msg.isTool()) {
                return renderToolLogSafely(msg.content(), msg.success(), msg.error(), msg.timestamp(), index,
                        msg.toolExpanded());
            }
            String safeRole = "";
            if (msg.role() != null) {
                safeRole = msg.role().toLowerCase(Locale.ROOT);
            }
            return ChatHtmlTemplates.renderChatEntry(safeRole, msg.renderedHtml(), msg.timestamp(), index,
                    index == copiedMessageIndex, !taskMode);
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
            if (pruned) {
                fullHtml.append(ChatHtmlTemplates.renderSystemNotice(
                        "Older messages pruned from UI to save memory. Full history is available in archived plans."));
            }
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
            if (!msg.isTool() && msg.role() != null && (ChatHtmlTemplates.ROLE_USER.equalsIgnoreCase(msg.role())
                    || ChatHtmlTemplates.ROLE_AI.equalsIgnoreCase(msg.role()))) {
                transcript.add(new ChatTranscriptEntry(msg.role(), msg.content(), msg.timestamp()));
            }
        }
        return List.copyOf(transcript);
    }

    public List<PersistedChatEntry> exportMessages() {
        List<PersistedChatEntry> exported = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            exported.add(new PersistedChatEntry(msg.role(), msg.content(), msg.timestamp(), msg.isTool(), msg.success(),
                    msg.error(), msg.toolExpanded()));
        }
        return List.copyOf(exported);
    }

    public void restoreMessages(List<PersistedChatEntry> entries) {
        if (copyFeedbackTimer != null && copyFeedbackTimer.isRunning()) {
            copyFeedbackTimer.stop();
        }
        copiedMessageIndex = -1;
        messages.clear();
        pruned = false;
        if (entries != null) {
            for (PersistedChatEntry entry : entries) {
                if (entry == null) {
                    continue;
                }
                String safeContent = StringUtils.defaultString(entry.content());
                String safeTimestamp = StringUtils.defaultIfBlank(entry.timestamp(),
                        LocalDateTime.now().format(TIME_FORMATTER));
                if (entry.isTool()) {
                    messages.add(new Message(null, safeContent, safeTimestamp, true, entry.success(), entry.error(),
                            null, entry.toolExpanded()));
                } else {
                    messages.add(new Message(entry.role(), safeContent, safeTimestamp, false, true, null,
                            renderMarkdownBodySafely(safeContent), true));
                }
            }
        }
        pruneMessagesIfNeeded();
        renderAll(true);
    }

    public void clear() {
        if (copyFeedbackTimer != null && copyFeedbackTimer.isRunning()) {
            copyFeedbackTimer.stop();
        }
        copiedMessageIndex = -1;
        messages.clear();
        pruned = false;
        renderAll(true);
    }
}
