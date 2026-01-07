package org.roxycode.core;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Singleton
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    private final SettingsService settingsService;

    @Inject
    public HistoryService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Applies a sliding window to the conversation history.
     * 1. Preserves System Prompt (Index 0).
     * 2. Skips over "Function Responses" to avoid breaking (Model -> Tool) chains.
     * 3. If a safe "User" node isn't found, it forces a cut before a "Model" node
     * and inserts a Synthetic User Message to keep the conversation valid.
     */
    public void applySlidingWindow(List<Content> history) {
        int windowSize = settingsService.getHistoryWindowSize();
        if (history.size() <= windowSize) {
            return;
        }
        log.info("🪟 History size ({}) exceeds window ({}) - Applying sliding window.", history.size(), windowSize);
        int currentSize = history.size();
        int idealRemovalCount = currentSize - windowSize;

        // Start looking for a cut point after the System Prompt (Index 0)
        // and ideally after the number of items we want to remove.
        int candidateIndex = 1 + idealRemovalCount;

        // Find the next node that is NOT a Function Response.
        // This could be a "User" message (Clean Cut) or a "Model" message (Requires Synthetic Bridge).
        int cutIndex = findNextCuttableNode(history, candidateIndex);

        if (cutIndex == -1 || cutIndex >= history.size()) {
            log.warn("⚠️ Could not find ANY point to slice history (Buffer full of Tool Responses?). Keeping full history.");
            return;
        }

        Content cutNode = history.get(cutIndex);
        String role = cutNode.role().orElse("unknown");

        // Strategy A: Natural Cut (Next node is User)
        if ("user".equalsIgnoreCase(role)) {
            history.subList(1, cutIndex).clear();
            log.info("✂️ Sliding Window: Clean cut. Removed old messages.");
        }
        // Strategy B: Synthetic Cut (Next node is Model)
        else if ("model".equalsIgnoreCase(role)) {
            // We cannot leave "System -> Model". We must insert "System -> SyntheticUser -> Model"
            log.info("✂️ Sliding Window: Cutting before a Model message. Inserting Synthetic User bridge.");

            // 1. Insert Synthetic User Message at the cut point
            Content syntheticMsg = Content.builder()
                    .role("user")
                    .parts(List.of(Part.builder().text("[System: Conversation history trimmed. Resuming context.]").build()))
                    .build();
            history.add(cutIndex, syntheticMsg);

            // 2. Now delete everything from Index 1 up to (but not including) the new Synthetic message
            // Note: After insertion, syntheticMsg is at 'cutIndex'.
            // The items to remove are at indices 1 to cutIndex-1.
            history.subList(1, cutIndex).clear();
        }
    }

    /**
     * Finds the index of the next message that is allowed to start a new chain.
     * Allowed: Role "user" (pure) or Role "model".
     * DISALLOWED: Role "function" or Role "user" containing FunctionResponse.
     */
    protected int findNextCuttableNode(List<Content> history, int startIndex) {
        if (startIndex >= history.size()) {
            startIndex = history.size() - 1;
        }

        // 1. Look forward
        for (int i = startIndex; i < history.size(); i++) {
            if (isCuttableNode(history.get(i))) {
                return i;
            }
        }

        // 2. Look backward (if we overshot into a tail of function calls)
        for (int i = startIndex - 1; i > 0; i--) {
            if (isCuttableNode(history.get(i))) {
                return i;
            }
        }

        return -1;
    }

    protected boolean isCuttableNode(Content content) {
        // We can never cut before a Function Response (it must follow a Model call)
        if (hasFunctionResponse(content)) return false;

        String role = content.role().orElse("?");
        if ("function".equalsIgnoreCase(role)) return false;

        // We CAN cut before a User message (Ideal)
        // We CAN cut before a Model message (If we insert a synthetic user)
        return "user".equalsIgnoreCase(role) || "model".equalsIgnoreCase(role);
    }

    protected boolean hasFunctionResponse(Content content) {
        if (content.parts().isEmpty()) return false;
        for (Part part : content.parts().orElse(List.of())) {
            if (part.functionResponse().isPresent()) {
                return true;
            }
        }
        return false;
    }
}
