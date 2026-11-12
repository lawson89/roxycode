package org.roxycode.app.events;

import java.util.Locale;
import org.roxycode.app.util.TokenUtils;

/**
 * Event published when {@code ToolHistoryCompactionAdvisor} locks in a new token-quantized compaction epoch for a
 * conversation.
 *
 * @param conversationId
 *            the conversation identifier
 * @param compactedTurns
 *            number of tool turns whose payloads were compacted in the locked epoch range
 * @param lockedEpochBoundary
 *            1-based exclusive upper bound of the locked epoch range
 * @param savedTokens
 *            estimated prompt tokens saved across compacted tool payloads
 * @param savedChars
 *            characters saved across compacted tool payloads
 */
public record ToolCompactionEvent(String conversationId, int compactedTurns, int lockedEpochBoundary, long savedTokens,
        int savedChars) {

    private static final int SINGLE_TURN = 1;

    /**
     * Formats a concise diagnostic message suitable for display in the chat output window.
     *
     * @return formatted diagnostic notice
     */
    public String formatDiagnosticMessage() {
        String turnWord;
        if (compactedTurns == SINGLE_TURN) {
            turnWord = "response";
        } else {
            turnWord = "responses";
        }
        return String.format(Locale.US,
                "🗜️ Compacted %d earlier tool %s (locked epoch 1..%d, saved ~%s tokens / %,d chars) to preserve context window and prefix cache.",
                compactedTurns, turnWord, lockedEpochBoundary, TokenUtils.formatTokenCount(savedTokens), savedChars);
    }
}
