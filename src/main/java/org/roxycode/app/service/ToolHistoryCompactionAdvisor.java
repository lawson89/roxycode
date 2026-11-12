package org.roxycode.app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.util.TokenUtils;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import org.springframework.lang.Nullable;

/**
 * Spring AI {@link CallAdvisor} ordered immediately before {@code ModelCallRetryAdvisor} that compacts older
 * {@link ToolResponseMessage} payloads in stateless, token-quantized epochs to prevent prompt token bloat during long
 * multi-step tool turns while preserving 100% Gemini implicit prefix-cache hits.
 */
final class ToolHistoryCompactionAdvisor implements CallAdvisor {

    static final int KEEP_RECENT_TOOL_TURNS = 6;
    static final long COMPACTION_EPOCH_TOKENS = 20_000L;
    static final int MIN_PAYLOAD_CHARS_TO_COMPACT = 1_200;
    static final int MAX_COMPACTED_JSON_CHARS = 2_400;
    static final int COMPACT_HEAD_CHARS = 300;
    static final int COMPACT_TAIL_CHARS = 200;

    @Override
    public String getName() {
        return "ToolHistoryCompactionAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 2;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientRequest effectiveRequest = compactRequestIfNeeded(chatClientRequest);
        return callAdvisorChain.nextCall(effectiveRequest);
    }

    static ChatClientRequest compactRequestIfNeeded(@Nullable ChatClientRequest request) {
        if (request == null || request.prompt() == null) {
            return request;
        }
        Prompt originalPrompt = request.prompt();
        Prompt compactedPrompt = compactPrompt(originalPrompt);
        if (compactedPrompt.equals(originalPrompt)) {
            return request;
        }
        return request.mutate().prompt(compactedPrompt).build();
    }

    static Prompt compactPrompt(@Nullable Prompt prompt) {
        if (prompt == null) {
            return null;
        }
        List<Message> originalMessages = prompt.getInstructions();
        List<Message> compactedMessages = compactMessages(originalMessages);
        if (compactedMessages.equals(originalMessages)) {
            return prompt;
        }
        if (prompt.getOptions() != null) {
            return new Prompt(compactedMessages, prompt.getOptions());
        }
        return new Prompt(compactedMessages);
    }

    static List<Message> compactMessages(@Nullable List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<ToolResponseMessage> toolMessages = new ArrayList<>();
        for (Message msg : messages) {
            if (msg instanceof ToolResponseMessage toolMsg) {
                toolMessages.add(toolMsg);
            }
        }
        int lockedEpochBoundary = resolveLockedEpochBoundary(toolMessages);
        if (lockedEpochBoundary <= 0) {
            return messages;
        }

        List<Message> result = new ArrayList<>(messages.size());
        int currentToolIndex = 0;
        boolean anyModified = false;
        for (Message msg : messages) {
            if (msg instanceof ToolResponseMessage toolMsg) {
                if (currentToolIndex < lockedEpochBoundary) {
                    ToolResponseMessage compactedToolMsg = compactToolResponseMessage(toolMsg);
                    if (!compactedToolMsg.equals(toolMsg)) {
                        anyModified = true;
                    }
                    result.add(compactedToolMsg);
                } else {
                    result.add(toolMsg);
                }
                currentToolIndex++;
            } else {
                result.add(msg);
            }
        }
        if (!anyModified) {
            return messages;
        }
        return result;
    }

    static int resolveLockedEpochBoundary(@Nullable List<ToolResponseMessage> toolMessages) {
        if (toolMessages == null) {
            return 0;
        }
        int totalToolTurns = toolMessages.size();
        int eligibleCount = totalToolTurns - KEEP_RECENT_TOOL_TURNS;
        if (eligibleCount <= 0) {
            return 0;
        }
        long epochAccumulatorTokens = 0L;
        int lockedBoundary = 0;
        for (int i = 0; i < eligibleCount; i++) {
            epochAccumulatorTokens += estimateToolMessageTokens(toolMessages.get(i));
            if (epochAccumulatorTokens >= COMPACTION_EPOCH_TOKENS) {
                lockedBoundary = i + 1;
                epochAccumulatorTokens = 0L;
            }
        }
        return lockedBoundary;
    }

    static long estimateToolMessageTokens(@Nullable ToolResponseMessage toolMsg) {
        if (toolMsg == null || toolMsg.getResponses() == null) {
            return 0L;
        }
        long tokens = 0L;
        for (ToolResponse response : toolMsg.getResponses()) {
            if (response != null && response.responseData() != null) {
                tokens += TokenUtils.estimateTokens(response.responseData());
            }
        }
        return tokens;
    }

    static ToolResponseMessage compactToolResponseMessage(ToolResponseMessage toolMsg) {
        List<ToolResponse> responses = toolMsg.getResponses();
        if (responses == null || responses.isEmpty()) {
            return toolMsg;
        }
        List<ToolResponse> compactedResponses = new ArrayList<>(responses.size());
        boolean changed = false;
        for (ToolResponse resp : responses) {
            if (resp == null) {
                compactedResponses.add(null);
            } else {
                String originalData = resp.responseData();
                String compactedData = compactPayload(originalData);
                if (!compactedData.equals(originalData)) {
                    changed = true;
                    compactedResponses.add(new ToolResponse(resp.id(), resp.name(), compactedData));
                } else {
                    compactedResponses.add(resp);
                }
            }
        }
        if (!changed) {
            return toolMsg;
        }
        return ToolResponseMessage.builder().responses(compactedResponses).metadata(toolMsg.getMetadata()).build();
    }

    static String compactPayload(@Nullable String data) {
        if (data == null || data.length() <= MIN_PAYLOAD_CHARS_TO_COMPACT) {
            return data;
        }
        return compactJsonOrPlainText(data);
    }

    private static String compactJsonOrPlainText(String data) {
        try {
            JsonNode root = AiConfig.SHARED_OBJECT_MAPPER.readTree(data);
            if (root == null) {
                return compactPlainText(data);
            }
            if (root.isTextual()) {
                String innerText = root.asText();
                String compactedInner = compactJsonNodeOrPlainText(innerText);
                return AiConfig.SHARED_OBJECT_MAPPER.writeValueAsString(compactedInner);
            }
            return compactJsonNode(root, data);
        } catch (JsonProcessingException e) {
            return compactPlainText(data);
        }
    }

    private static String compactJsonNodeOrPlainText(String text) {
        if (text.length() <= MIN_PAYLOAD_CHARS_TO_COMPACT) {
            return text;
        }
        try {
            JsonNode nested = AiConfig.SHARED_OBJECT_MAPPER.readTree(text);
            if (nested != null && (nested.isObject() || nested.isArray())) {
                return compactJsonNode(nested, text);
            }
        } catch (JsonProcessingException ignored) {
            // Fall through to plain text compaction
        }
        return compactPlainText(text);
    }

    private static String compactJsonNode(JsonNode node, String rawJsonFallback) throws JsonProcessingException {
        boolean modified = compactNodeStrings(node);
        if (modified) {
            String serialized = AiConfig.SHARED_OBJECT_MAPPER.writeValueAsString(node);
            if (serialized.length() <= MAX_COMPACTED_JSON_CHARS) {
                return serialized;
            }
        }
        String summary = compactPlainText(rawJsonFallback);
        return AiConfig.SHARED_OBJECT_MAPPER.writeValueAsString(Map.of("result", summary));
    }

    private static boolean compactNodeStrings(JsonNode node) {
        boolean modified = false;
        if (node instanceof ObjectNode objectNode) {
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode child = entry.getValue();
                if (child != null && child.isTextual()) {
                    String text = child.asText();
                    if (text.length() > MIN_PAYLOAD_CHARS_TO_COMPACT) {
                        entry.setValue(TextNode.valueOf(compactPlainText(text)));
                        modified = true;
                    }
                } else if (child != null && (child.isObject() || child.isArray())) {
                    if (compactNodeStrings(child)) {
                        modified = true;
                    }
                }
            }
        } else if (node instanceof ArrayNode arrayNode) {
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode child = arrayNode.get(i);
                if (child != null && child.isTextual()) {
                    String text = child.asText();
                    if (text.length() > MIN_PAYLOAD_CHARS_TO_COMPACT) {
                        arrayNode.set(i, TextNode.valueOf(compactPlainText(text)));
                        modified = true;
                    }
                } else if (child != null && (child.isObject() || child.isArray())) {
                    if (compactNodeStrings(child)) {
                        modified = true;
                    }
                }
            }
        }
        return modified;
    }

    static String compactPlainText(String data) {
        if (data == null || data.length() <= MIN_PAYLOAD_CHARS_TO_COMPACT) {
            return data;
        }
        int omittedChars = data.length() - COMPACT_HEAD_CHARS - COMPACT_TAIL_CHARS;
        if (omittedChars <= 0) {
            return data;
        }
        String head = data.substring(0, COMPACT_HEAD_CHARS);
        String tail = data.substring(data.length() - COMPACT_TAIL_CHARS);
        return head + "\n...[compacted " + omittedChars + " chars from earlier tool turn; re-read if needed]...\n"
                + tail;
    }
}
