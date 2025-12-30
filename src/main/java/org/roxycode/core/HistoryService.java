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
        if (splitIndex <= 1) {
            log.info("🔍 No safe split point found (or split point at beginning). Attempting forced split point...");
            splitIndex = findForcedSplitIndex(history, targetIndex);
            isForced = true;
        }
        if (splitIndex == -1 || splitIndex >= history.size()) {
            log.warn("⚠️ Could not find any split point (safe or forced) among {} messages. Skipping compaction.", history.size());
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
        Content newSystemMsg = Content.builder().role("user").parts(List.of(Part.builder().text(dynamicSystemText).build())).build();
        history.set(0, newSystemMsg);
        // 6. Remove the compacted messages
        history.subList(1, splitIndex).clear();
        
        // 7. Handle consecutive User messages to maintain role alternation
        if (isForced) {
            // Insert a synthetic message to bridge the gap
            String syntheticText = "Continuing from previous context. Summary of progress so far:\n" + newSummary + "\n\nPlease proceed with the task.";
            Content syntheticMsg = Content.builder().role("user").parts(List.of(Part.builder().text(syntheticText).build())).build();
            history.add(1, syntheticMsg);
            log.info("➕ Inserted synthetic continuation message.");
        }

        // Merge consecutive User messages at the beginning if they exist
        if (history.size() > 1 && isUserNode(history.get(0)) && isUserNode(history.get(1))) {
            log.info("Merging consecutive User messages at indices 0 and 1.");
            Content merged = mergeUserMessages(history.get(0), history.get(1));
            history.set(0, merged);
            history.remove(1);
        }
        
        log.info("✅ Compaction complete. History size is now: {}", history.size());
    }

    private int findSafeSplitIndex(List<Content> history, int targetIndex) {
        int lookAheadLimit = Math.max(targetIndex, history.size() - 5);
        for (int i = targetIndex; i < lookAheadLimit; i++) {
            if (isSafeStartNode(history.get(i))) {
                return i;
            }
        }
        for (int i = targetIndex - 1; i >= 1; i--) {
            if (isSafeStartNode(history.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private int findForcedSplitIndex(List<Content> history, int targetIndex) {
        int lookAheadLimit = Math.min(history.size(), targetIndex + 5);
        for (int i = targetIndex; i < lookAheadLimit; i++) {
            if (isModelNode(history.get(i))) {
                return i;
            }
        }
        for (int i = targetIndex - 1; i >= 1; i--) {
            if (isModelNode(history.get(i))) {
                return i;
            }
        }
        if (targetIndex >= 1 && targetIndex < history.size()) {
            return targetIndex;
        }
        return -1;
    }

    private boolean isModelNode(Content content) {
        return "model".equals(content.role().orElse("?"));
    }

    private boolean isUserNode(Content content) {
        return "user".equals(content.role().orElse("?"));
    }

    private Content mergeUserMessages(Content c1, Content c2) {
        List<Part> parts = new ArrayList<>();
        parts.addAll(c1.parts().orElse(List.of()));
        parts.add(Part.builder().text("\n--- CONTINUATION ---\n").build());
        parts.addAll(c2.parts().orElse(List.of()));
        return Content.builder().role("user").parts(parts).build();
    }

    private boolean isSafeStartNode(Content content) {
        String role = content.role().orElse("?");
        boolean isToolResponse = hasFunctionResponse(content);
        return "user".equals(role) && !isToolResponse;
    }

    private boolean hasFunctionResponse(Content content) {
        if (content.parts().isEmpty())
            return false;
        for (Part part : content.parts().orElse(List.of())) {
            if (part.functionResponse().isPresent()) {
                return true;
            }
        }
        return false;
    }

    private String generateSummary(Client client, String modelName, List<Content> messages) {
        if (messages == null || messages.isEmpty()) {
            return "[No messages to summarize]";
        }
        List<Content> promptPayload = new ArrayList<>();
        promptPayload.add(Content.builder().role("user").parts(List.of(Part.builder().text("Summarize the following conversation snippet concisely. Capture key decisions, user intents, and tool outputs. Ignore polite filler. Return ONLY the summary text.").build())).build());
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
