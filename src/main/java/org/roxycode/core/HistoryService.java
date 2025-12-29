package org.roxycode.core;

import com.google.genai.Client;
import com.google.genai.types.*;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

@Singleton
public class HistoryService {
    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    private final SettingsService settingsService;
    
    // Store summaries in a FIFO queue
    private final LinkedList<String> summaryQueue = new LinkedList<>();

    @Inject
    public HistoryService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public void compactHistory(Client client, String modelName, List<Content> history, String staticSystemPrompt) {
        // 1. Check if we need to compact
        int historyThreshold = settingsService.getHistoryThreshold();
        if (history.size() <= historyThreshold + 1) {
            return;
        }

        log.info("🧹 History limit reached ({} messages). Calculating safe compaction slice...", history.size());

        // 2. Identify the slice to compact.
        //    We want to remove roughly CHUNK_SIZE messages.
        int chunkSize = settingsService.getCompactionChunkSize();
        int targetIndex = 1 + chunkSize;
        int splitIndex = findSafeSplitIndex(history, targetIndex);
        boolean isForced = false;

        // Safety check: if we couldn't find a safe split point, try a forced one
        if (splitIndex == -1) {
            log.info("🔍 No safe split point found. Attempting forced split point...");
            splitIndex = findForcedSplitIndex(history, targetIndex);
            isForced = true;
        }

        if (splitIndex == -1 || splitIndex >= history.size()) {
            log.warn("⚠️ Could not find any split point (safe or forced) among {} messages. " +
                     "History likely consists of an ongoing tool-call sequence. Skipping compaction.", history.size());
            return;
        }

        if (splitIndex <= 1) {
            log.info("ℹ️ Split point is at the beginning. Nothing to compact yet.");
            return;
        }

        List<Content> messagesToSummarize = new ArrayList<>(history.subList(1, splitIndex));
        log.info("✂️ Compacting {} messages (Target was {}, Forced={})...", messagesToSummarize.size(), chunkSize, isForced);

        // 3. Generate the Summary
        String newSummary = generateSummary(client, modelName, messagesToSummarize);

        // 4. Update the Summary Queue
        summaryQueue.add(newSummary);
        int maxSummaryChunks = settingsService.getMaxSummaryChunks();
        if (summaryQueue.size() > maxSummaryChunks) {
            log.info("🗑️ Flushing oldest summary chunk.");
            summaryQueue.removeFirst();
        }

        // 5. Rebuild the System Prompt (Index 0)
        String dynamicSystemText = buildDynamicSystemPrompt(staticSystemPrompt);
        Content newSystemMsg = Content.builder()
                .role("user")
                .parts(List.of(Part.builder().text(dynamicSystemText).build()))
                .build();

        history.set(0, newSystemMsg);

        // 6. Remove the compacted messages and handle forced split
        history.subList(1, splitIndex).clear();

        if (isForced) {
            // Insert a synthetic message to bridge the gap
            String syntheticText = "Continuing from previous context. Summary of progress so far:\n" + newSummary +
                                   "\n\nPlease proceed with the task.";
            Content syntheticMsg = Content.builder()
                    .role("user")
                    .parts(List.of(Part.builder().text(syntheticText).build()))
                    .build();
            history.add(1, syntheticMsg);
            log.info("➕ Inserted synthetic continuation message.");
        }

        log.info("✅ Compaction complete. History size is now: {}", history.size());
    }

    /**
     * Finds a safe message index to start the new history.
     * A safe index must point to a "pure" user message (not a tool response).
     */
    private int findSafeSplitIndex(List<Content> history, int targetIndex) {
        // 1. First, try looking forward from targetIndex to find a clean break.
        // We stop before the last few messages to ensure the model keeps some recent context.
        int lookAheadLimit = Math.max(targetIndex, history.size() - 5);
        for (int i = targetIndex; i < lookAheadLimit; i++) {
            if (isSafeStartNode(history.get(i))) {
                return i;
            }
        }

        // 2. If no clean break is found forward, look backward from targetIndex.
        // This is useful if the current tool chain started just before our target.
        for (int i = targetIndex - 1; i >= 1; i--) {
            if (isSafeStartNode(history.get(i))) {
                return i;
            }
        }

        // 3. If we still haven't found a pure user message, we might be in a very long tool chain.
        // For now, we return -1 to allow the caller to decide if a forced split is needed.
        return -1;
    }

    /**
     * Finds a "forced" split index when no safe split point exists.
     * It prefers a 'model' message because a 'model' message can follow a 'user' message.
     */
    private int findForcedSplitIndex(List<Content> history, int targetIndex) {
        // Look forward first
        int lookAheadLimit = Math.min(history.size() - 1, targetIndex + 5);
        for (int i = targetIndex; i < lookAheadLimit; i++) {
            if (isModelNode(history.get(i))) {
                return i;
            }
        }

        // Look backward
        for (int i = targetIndex - 1; i >= 1; i--) {
            if (isModelNode(history.get(i))) {
                return i;
            }
        }

        // Final fallback: just use targetIndex if it's not 0 and less than size
        if (targetIndex >= 1 && targetIndex < history.size()) {
            return targetIndex;
        }

        return -1;
    }

    private boolean isModelNode(Content content) {
        return "model".equals(content.role().orElse("?"));
    }

    /**
     * A node is a Safe Start Node if:
     * 1. It is from the "user"
     * 2. It is NOT a Function Response (Tool Output)
     */
    private boolean isSafeStartNode(Content content) {
        String role = content.role().orElse("?");
        boolean isToolResponse = hasFunctionResponse(content);

        return "user".equals(role) && !isToolResponse;
    }

    /**
     * Checks if the content contains a function response part.
     */
    private boolean hasFunctionResponse(Content content) {
        if (content.parts().isEmpty()) return false;
        // In the new SDK, we check if the part has a functionResponse
        for (Part part : content.parts().get()) {
            if (part.functionResponse().isPresent()) {
                return true;
            }
        }
        return false;
    }

    private String generateSummary(Client client, String modelName, List<Content> messages) {
        if(messages == null || messages.isEmpty()){
            return "[No messages to summarize]";
        }
        List<Content> promptPayload = new ArrayList<>();
        promptPayload.add(Content.builder().role("user").parts(List.of(Part.builder().text(
                "Summarize the following conversation snippet concisely. " +
                "Capture key decisions, user intents, and tool outputs. " +
                "Ignore polite filler. Return ONLY the summary text."
        ).build())).build());

        promptPayload.addAll(messages);

        try {
            GenerateContentResponse response = client.models.generateContent(modelName, promptPayload, null);
            return response.text();
        } catch (Exception e) {
            log.error("Failed to generate summary", e);
            return "[Context missing due to error]";
        }
    }

    private String buildDynamicSystemPrompt(String staticPrompt) {
        StringBuilder sb = new StringBuilder(staticPrompt);
        log.info("Building a dynamic system prompt with {} chunks of previous context.", summaryQueue.size());

        if (!summaryQueue.isEmpty()) {
            sb.append("\n\n=== PREVIOUS CONTEXT (Oldest to Newest) ===\n");
            int i = 1;
            for (String summary : summaryQueue) {
                sb.append("--- Segment ").append(i++).append(" ---\n");
                sb.append(summary).append("\n");
            }
            sb.append("===========================================\n");
        }

        return sb.toString();
    }
}
