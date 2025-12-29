package org.roxycode.core;

import com.google.genai.Client;
import com.google.genai.types.*;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

@Singleton
public class HistoryService {
    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    // --- Configuration ---
    private static final int HISTORY_THRESHOLD = 15;
    private static final int CHUNK_SIZE = 6;
    private static final int MAX_SUMMARY_CHUNKS = 4;

    // Store summaries in a FIFO queue
    private final LinkedList<String> summaryQueue = new LinkedList<>();

    public void compactHistory(Client client, String modelName, List<Content> history, String staticSystemPrompt) {
        // 1. Check if we need to compact
        if (history.size() <= HISTORY_THRESHOLD + 1) {
            return;
        }

        log.info("🧹 History limit reached. Calculating safe compaction slice...");

        // 2. Identify the SAFE slice to compact.
        //    We want to remove roughly CHUNK_SIZE messages.
        //    But we must ensure the *next* message (which becomes the new start) 
        //    is a valid "User Start" node.
        int targetIndex = 1 + CHUNK_SIZE;
        int safeSplitIndex = findSafeSplitIndex(history, targetIndex);

        // Safety check: if we couldn't find a safe split point, abort to avoid corruption
        if (safeSplitIndex == -1 || safeSplitIndex >= history.size()) {
            log.warn("⚠️ Could not find a safe split point. Skipping compaction.");
            return;
        }

        List<Content> messagesToSummarize = new ArrayList<>(history.subList(1, safeSplitIndex));
        System.out.printf("✂️ Compacting %d messages (Target was %d)...\n", messagesToSummarize.size(), CHUNK_SIZE);

        // 3. Generate the Summary
        String newSummary = generateSummary(client, modelName, messagesToSummarize);

        // 4. Update the Summary Queue
        summaryQueue.add(newSummary);
        if (summaryQueue.size() > MAX_SUMMARY_CHUNKS) {
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

        // 6. Remove the compacted messages safely
        history.subList(1, safeSplitIndex).clear();

        log.info("✅ Compaction complete. History size is now: {}", history.size());
    }

    /**
     * Scans forward from the target index to find a message that serves as a valid
     * "Start Node" for the remaining history.
     */
    private int findSafeSplitIndex(List<Content> history, int targetIndex) {
        // Start looking at the target. If it's not safe, keep grabbing more messages
        // until we find a safe place to stop cutting.
        for (int i = targetIndex; i < history.size(); i++) {
            if (isSafeStartNode(history.get(i))) {
                return i;
            }
        }
        return -1; // No safe split point found
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