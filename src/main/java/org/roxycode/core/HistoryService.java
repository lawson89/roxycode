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
import java.util.function.Function;

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
            String syntheticText = "### Context Continuation\nContinuing from previous context. **Summary of progress so far:**\n\n> " + newSummary + "\n\nPlease proceed with the task.";
            Content syntheticMsg = Content.builder().role("user").parts(List.of(Part.builder().text(syntheticText).build())).build();
            history.add(1, syntheticMsg);
            log.info("➕ Inserted synthetic continuation message.");
        }
        // Merge consecutive User messages at the beginning if they exist
        while (history.size() > 1 && isUserNode(history.get(0)) && isUserNode(history.get(1))) {
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
        parts.add(Part.builder().text("\n\n---\n\n").build());
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

    protected String generateSummary(Client client, String modelName, List<Content> messages) {
        if (messages == null || messages.isEmpty()) {
            return "[No messages to summarize]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## Conversation to Summarize\n\nPlease provide a very concise summary of the conversation below. Focus on key decisions, user intents, and the outcomes of any tool outputs. Ignore polite filler. Aim for 2-3 sentences max.\n\n");
        for (Content content : messages) {
            String role = content.role().orElse("unknown").toUpperCase();
            sb.append("**").append(role).append("**:\n");
            for (Part part : content.parts().orElse(List.of())) {
                if (part.text().isPresent()) {
                    sb.append(part.text().get()).append("\n");
                } else if (part.functionCall().isPresent()) {
                    String fnName = part.functionCall().get().name().orElse("unknown");
                    sb.append("[TOOL CALL: ").append(fnName).append("]\n");
                } else if (part.functionResponse().isPresent()) {
                    String fnName = part.functionResponse().get().name().orElse("unknown");
                    sb.append("[TOOL RESPONSE: ").append(fnName).append("]\n");
                } else if (part.inlineData().isPresent()) {
                    String mime = part.inlineData().get().mimeType().orElse("image/png");
                    sb.append("[IMAGE DATA: ").append(mime).append("]\n");
                } else {
                    sb.append("[NON-TEXT CONTENT]\n");
                }
            }
            sb.append("\n");
        }
        log.info("Sending conversation snippet ({} characters) to LLM for summarization...", sb.length());
        List<Content> promptPayload = List.of(Content.builder().role("user").parts(List.of(Part.builder().text(sb.toString()).build())).build());
        try {
            GenerateContentResponse response = client.models.generateContent(modelName, promptPayload, null);
            String summary = response.text();
            if (summary == null || summary.isBlank()) {
                return "[Summary generation returned empty result]";
            }
            return summary.trim();
        } catch (Exception e) {
            log.error("Failed to generate summary", e);
            return "[Context missing due to error: " + e.getMessage() + "]";
        }
    }

    private String buildDynamicSystemPrompt(String staticPrompt) {
        StringBuilder sb = new StringBuilder(staticPrompt);
        if (!summaryQueue.isEmpty()) {
            sb.append("\n\n## Previous Conversation Context\n*(Summarized segments from oldest to newest)*\n");
            int i = 1;
            for (String summary : summaryQueue) {
                sb.append("\n### Segment ").append(i++).append("\n");
                sb.append(summary).append("\n");
            }
        }
        return sb.toString();
    }

    public List<String> getSummaryQueue() {
        return new ArrayList<>(summaryQueue);
    }

    public String renderContentToHtmlRow(Content content, boolean isDarkTheme, Function<String, String> markdownToHtml) {
        String role = content.role().orElse("unknown");
        String roleColor = "user".equals(role) ? "#4080FF" : ("model".equals(role) ? "#40C080" : "#888888");
        String bgColor = isDarkTheme ? ("user".equals(role) ? "#2d3a4f" : "#2d3f35") : ("user".equals(role) ? "#eef4ff" : "#eefff4");
        StringBuilder row = new StringBuilder();
        row.append("<tr bgcolor='").append(bgColor).append("'>");
        row.append("<td valign='top' width='80'><b><font color='").append(roleColor).append("'>").append(role.toUpperCase()).append("</font></b></td>");
        row.append("<td>");
        List<Part> parts = content.parts().orElse(new ArrayList<>());
        for (Part part : parts) {
            String text = part.text().orElse("");
            if (part.functionCall().isPresent()) {
                text = "<i>[Function Call: " + part.functionCall().get().name().orElse("?") + "]</i>";
            } else if (part.functionResponse().isPresent()) {
                text = "<i>[Function Response: " + part.functionResponse().get().name().orElse("?") + "]</i>";
            } else if (part.inlineData().isPresent()) {
                text = "<i>[Inline Data: " + part.inlineData().get().mimeType().orElse("?") + "]</i>";
            }
            if (!text.isEmpty()) {
                row.append(markdownToHtml.apply(text));
            }
        }
        row.append("</td></tr>");
        return row.toString();
    }
}
