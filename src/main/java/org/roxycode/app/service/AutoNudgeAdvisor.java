package org.roxycode.app.service;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.TurnToolContext;
import org.roxycode.app.events.AgentActivityEvent;
import org.roxycode.app.ui.PlantUmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;

/**
 * Spring AI {@link CallAdvisor} ordered before chat memory persistence that automatically nudges the model once per
 * turn if it completes tool execution with an empty text response, and retries once when invalid diagram syntax is
 * detected in a non-empty response.
 */
final class AutoNudgeAdvisor implements CallAdvisor {
    private static final Logger log = LoggerFactory.getLogger(AutoNudgeAdvisor.class);

    private final TurnToolContext turnContext;
    private final ChatMemory chatMemory;
    @Nullable
    private final ApplicationEventPublisher eventPublisher;
    private int nudgeCount = 0;
    private int diagramFixCount = 0;

    AutoNudgeAdvisor(TurnToolContext turnContext, ChatMemory chatMemory) {
        this(turnContext, chatMemory, null);
    }

    AutoNudgeAdvisor(TurnToolContext turnContext, ChatMemory chatMemory,
            @Nullable ApplicationEventPublisher eventPublisher) {
        this.turnContext = turnContext;
        this.chatMemory = chatMemory;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String getName() {
        return "AutoNudgeAdvisor";
    }

    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientRequest currentRequest = chatClientRequest;
        String pendingDiagramFixPrompt = null;
        while (true) {
            ChatClientResponse response = callAdvisorChain.copy(this).nextCall(currentRequest);
            ChatResponse chatResponse = null;
            if (response != null) {
                chatResponse = response.chatResponse();
            }
            String text = ChatResponseFormatter.extractResponseText(chatResponse);
            if (StringUtils.isNotBlank(text)) {
                if (pendingDiagramFixPrompt != null) {
                    pruneIntermediateDiagramFixMessages(pendingDiagramFixPrompt);
                }
                if (diagramFixCount < AiService.MAX_DIAGRAM_FIXES) {
                    List<PlantUmlRenderer.DiagramValidationError> diagramErrors = PlantUmlRenderer
                            .validateMarkdownDiagrams(text);
                    if (!diagramErrors.isEmpty()) {
                        diagramFixCount++;
                        log.info("Invalid diagram syntax detected ({} error(s)). Requesting self-correction ({}/{})",
                                diagramErrors.size(), diagramFixCount, AiService.MAX_DIAGRAM_FIXES);
                        if (eventPublisher != null) {
                            eventPublisher.publishEvent(new AgentActivityEvent(turnContext.conversationId(),
                                    AiService.DIAGRAM_FIX_ACTIVITY_MESSAGE));
                        }
                        String fixPromptText = buildDiagramFixPromptText(diagramErrors);
                        pendingDiagramFixPrompt = fixPromptText;
                        currentRequest = buildDiagramFixRequest(chatClientRequest, fixPromptText);
                        continue;
                    }
                }
                return response;
            }
            if (!shouldAutoNudge(text, chatResponse)) {
                return response;
            }
            nudgeCount++;
            log.info("Empty response detected after tool use. Auto-nudging (attempt {}/{})", nudgeCount,
                    AiService.MAX_AUTO_NUDGES);
            if (!hasGenerationOutput(chatResponse)) {
                chatMemory.add(turnContext.conversationId(),
                        new AssistantMessage(ChatResponseFormatter.EMPTY_STOP_MESSAGE));
            }
            currentRequest = buildNudgeRequest(chatClientRequest);
        }
    }

    private void pruneIntermediateDiagramFixMessages(String fixPromptText) {
        String convId = turnContext.conversationId();
        List<Message> existing = chatMemory.get(convId);
        if (existing == null || existing.size() < 3) {
            return;
        }
        int fixUserIndex = -1;
        for (int i = existing.size() - 1; i >= 1; i--) {
            Message msg = existing.get(i);
            if (msg instanceof UserMessage && fixPromptText.equals(msg.getText())) {
                fixUserIndex = i;
                break;
            }
        }
        if (fixUserIndex <= 0) {
            return;
        }
        int failedAssistantIndex = fixUserIndex - 1;
        if (!(existing.get(failedAssistantIndex) instanceof AssistantMessage)) {
            return;
        }
        List<Message> cleaned = new ArrayList<>(existing.size() - 2);
        for (int i = 0; i < existing.size(); i++) {
            if (i != failedAssistantIndex && i != fixUserIndex) {
                cleaned.add(existing.get(i));
            }
        }
        chatMemory.clear(convId);
        chatMemory.add(convId, cleaned);
    }

    private boolean shouldAutoNudge(String text, ChatResponse chatResponse) {
        if (StringUtils.isNotBlank(text)) {
            return false;
        }
        if (turnContext.turnCounter().get() <= 0 || nudgeCount >= AiService.MAX_AUTO_NUDGES) {
            return false;
        }
        String finishReason = ChatResponseFormatter.extractFinishReason(chatResponse);
        return isRecoverableEmptyFinishReason(finishReason);
    }

    static boolean isRecoverableEmptyFinishReason(String finishReason) {
        return "STOP".equalsIgnoreCase(finishReason)
                || ChatResponseFormatter.FINISH_REASON_UNKNOWN.equalsIgnoreCase(finishReason)
                || "OTHER".equalsIgnoreCase(finishReason) || "MALFORMED_FUNCTION_CALL".equalsIgnoreCase(finishReason);
    }

    private static boolean hasGenerationOutput(ChatResponse chatResponse) {
        return chatResponse != null && chatResponse.getResults() != null
                && chatResponse.getResults().stream().anyMatch(g -> g != null && g.getOutput() != null);
    }

    private static ChatClientRequest buildNudgeRequest(ChatClientRequest originalRequest) {
        return buildFollowUpRequest(originalRequest, AiService.AUTO_NUDGE_PROMPT);
    }

    private static ChatClientRequest buildDiagramFixRequest(ChatClientRequest originalRequest, String fixPromptText) {
        return buildFollowUpRequest(originalRequest, fixPromptText);
    }

    private static String buildDiagramFixPromptText(List<PlantUmlRenderer.DiagramValidationError> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("Your previous response contained diagram syntax error(s) that failed validation:\n");
        for (PlantUmlRenderer.DiagramValidationError err : errors) {
            sb.append("- Block #").append(err.blockIndex()).append(" (").append(err.language()).append(") line ")
                    .append(err.lineNumber());
            if (StringUtils.isNotBlank(err.offendingLine())) {
                sb.append(" [").append(err.offendingLine()).append("]");
            }
            sb.append(": ").append(err.errorMessage()).append("\n");
        }
        sb.append(
                "Fix the diagram syntax error(s) above (for PlantUML, declare allowmixing after @startuml if mixing class/object and component/card elements, and use simple alphanumeric aliases for generic/bracketed names; for SVG, ensure well-formed XML with explicit width/height and inline attributes) and return the complete corrected response.");
        return sb.toString();
    }

    private static ChatClientRequest buildFollowUpRequest(ChatClientRequest originalRequest, String userPromptText) {
        List<Message> followUpMessages = new ArrayList<>(2);
        Prompt originalPrompt = originalRequest.prompt();
        if (originalPrompt != null && originalPrompt.getSystemMessage() != null) {
            SystemMessage sysMsg = originalPrompt.getSystemMessage();
            if (StringUtils.isNotBlank(sysMsg.getText())) {
                followUpMessages.add(sysMsg);
            }
        }
        followUpMessages.add(new UserMessage(userPromptText));
        Prompt followUpPrompt;
        if (originalPrompt != null && originalPrompt.getOptions() != null) {
            followUpPrompt = new Prompt(followUpMessages, originalPrompt.getOptions());
        } else {
            followUpPrompt = new Prompt(followUpMessages);
        }
        return originalRequest.mutate().prompt(followUpPrompt).build();
    }
}
