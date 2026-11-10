package org.roxycode.app.service;

import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.TurnToolContext;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

final class ChatResponseFormatter {

    static final String FINISH_REASON_UNKNOWN = "UNKNOWN";
    public static final String TRUNCATED_MARKER = "⚠️ **Response Truncated (finishReason:";
    public static final String NO_VISIBLE_RESPONSE_MARKER = "⚠️ **No Visible Response (finishReason:";
    public static final String EMPTY_RESPONSE_MARKER = "⚠️ **Empty Response Returned (finishReason:";
    public static final String EMPTY_STOP_MESSAGE = "Roxy completed the turn without generating a visible text message.";

    private ChatResponseFormatter() {
    }

    static String formatChatResponse(ChatResponse chatResponse, TurnToolContext turnContext) {
        String content = extractResponseText(chatResponse);
        String finishReason = extractFinishReason(chatResponse);
        Usage usage = null;
        if (chatResponse != null && chatResponse.getMetadata() != null) {
            usage = chatResponse.getMetadata().getUsage();
        }
        String usageSummary = formatUsageSummary(usage);
        int toolTurns = turnContext.turnCounter().get();
        boolean hasVisibleContent = StringUtils.isNotBlank(content);

        if (isTokenLimitFinishReason(finishReason)) {
            return formatTokenLimitResponse(content, hasVisibleContent, finishReason, toolTurns, usageSummary);
        }
        if (!hasVisibleContent) {
            return formatEmptyResponse(finishReason, toolTurns, usageSummary);
        }
        return content;
    }

    static String extractResponseText(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null) {
            return null;
        }
        Generation result = chatResponse.getResult();
        if (result.getOutput() != null) {
            return result.getOutput().getText();
        }
        return null;
    }

    static String extractFinishReason(ChatResponse chatResponse) {
        String rawFinishReason = null;
        if (chatResponse != null && chatResponse.getResult() != null
                && chatResponse.getResult().getMetadata() != null) {
            rawFinishReason = chatResponse.getResult().getMetadata().getFinishReason();
        }
        return StringUtils.defaultIfBlank(StringUtils.trim(rawFinishReason), FINISH_REASON_UNKNOWN);
    }

    private static String formatTokenLimitResponse(String content, boolean hasVisibleContent, String finishReason,
            int toolTurns, String usageSummary) {
        if (hasVisibleContent) {
            StringBuilder sb = new StringBuilder(content).append("\n\n---\n> ").append(TRUNCATED_MARKER).append(" ")
                    .append(finishReason)
                    .append(")**: The response was cut off because it reached the maximum output token limit.");
            if (!usageSummary.isEmpty()) {
                sb.append(" (Token usage: ").append(usageSummary).append(")");
            }
            return sb.append(" Reply **'continue'** so Roxy can resume where she left off.").toString();
        }

        StringBuilder sb = new StringBuilder().append(NO_VISIBLE_RESPONSE_MARKER).append(" ").append(finishReason)
                .append(")**\n\n")
                .append("Roxy produced no visible text because the model exhausted its output or thinking token limit before generating a final message.");
        appendDiagnosticMetadata(sb, finishReason, toolTurns, usageSummary);
        return sb.append("\n\nPlease ask Roxy to **'continue'** or summarize in smaller steps.").toString();
    }

    private static String formatEmptyResponse(String finishReason, int toolTurns, String usageSummary) {
        boolean normalStop = "STOP".equalsIgnoreCase(finishReason)
                || FINISH_REASON_UNKNOWN.equalsIgnoreCase(finishReason);

        StringBuilder sb = new StringBuilder(EMPTY_RESPONSE_MARKER).append(" ").append(finishReason).append(")**\n\n");
        if (normalStop) {
            sb.append(EMPTY_STOP_MESSAGE);
        } else {
            sb.append("Generation was blocked or aborted by the model (").append(finishReason).append(").");
        }
        appendDiagnosticMetadata(sb, finishReason, toolTurns, usageSummary);
        if (normalStop) {
            sb.append(
                    "\n\nPlease reply (e.g., *'Please continue and summarize your findings'*) so Roxy can provide the final response.");
        } else {
            sb.append(
                    "\n\nPlease review the last step or ask Roxy to continue with a rephrased request or simpler tool call.");
        }
        return sb.toString();
    }

    private static void appendDiagnosticMetadata(StringBuilder sb, String finishReason, int toolTurns,
            String usageSummary) {
        sb.append("\n- **Finish reason:** ").append(finishReason).append("\n- **Tool turns executed:** ")
                .append(toolTurns);
        if (!usageSummary.isEmpty()) {
            sb.append("\n- **Token usage:** ").append(usageSummary);
        }
    }

    private static boolean isTokenLimitFinishReason(String finishReason) {
        return "MAX_TOKENS".equalsIgnoreCase(finishReason) || "LENGTH".equalsIgnoreCase(finishReason);
    }

    private static String formatUsageSummary(Usage usage) {
        if (usage == null) {
            return "";
        }
        int promptTokens = safeNonNegativeInt(usage.getPromptTokens());
        int completionTokens = safeNonNegativeInt(usage.getCompletionTokens());
        int totalTokens = safeNonNegativeInt(usage.getTotalTokens());
        if (promptTokens <= 0 && completionTokens <= 0 && totalTokens <= 0) {
            return "";
        }
        return String.format("promptTokens=%d, completionTokens=%d, totalTokens=%d", promptTokens, completionTokens,
                totalTokens);
    }

    static int safeNonNegativeInt(Integer value) {
        if (value == null || value < 0) {
            return 0;
        }
        return value;
    }
}
