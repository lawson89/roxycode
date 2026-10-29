package org.roxycode.app.service;

import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.TurnToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.Ordered;

final class AutoNudgeAdvisor implements CallAdvisor {
    private static final Logger log = LoggerFactory.getLogger(AutoNudgeAdvisor.class);

    private final TurnToolContext turnContext;
    private final ChatMemory chatMemory;
    private int nudgeCount = 0;

    AutoNudgeAdvisor(TurnToolContext turnContext, ChatMemory chatMemory) {
        this.turnContext = turnContext;
        this.chatMemory = chatMemory;
    }

    @Override
    public String getName() {
        return "AutoNudgeAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 2;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientResponse response = callAdvisorChain.nextCall(chatClientRequest);

        String text = null;
        if (response != null && response.chatResponse() != null) {
            text = AiService.extractResponseText(response.chatResponse());
        }

        if (StringUtils.isBlank(text) && turnContext.turnCounter().get() > 0
                && nudgeCount < AiService.MAX_AUTO_NUDGES) {
            String finishReason = AiService.extractFinishReason(response != null ? response.chatResponse() : null);
            boolean normalStop = "STOP".equalsIgnoreCase(finishReason)
                    || AiService.FINISH_REASON_UNKNOWN.equalsIgnoreCase(finishReason);

            if (normalStop) {
                nudgeCount++;
                log.info("Empty response detected after tool use. Auto-nudging (attempt {}/{})", nudgeCount,
                        AiService.MAX_AUTO_NUDGES);

                if (response != null && response.chatResponse() != null && response.chatResponse().getResults() != null
                        && !response.chatResponse().getResults().isEmpty()) {
                    chatMemory.add(turnContext.conversationId(),
                            response.chatResponse().getResults().get(0).getOutput());
                }

                chatMemory.add(turnContext.conversationId(), new UserMessage(AiService.AUTO_NUDGE_PROMPT));

                return adviseCall(chatClientRequest, callAdvisorChain.copy(this));
            }
        }

        return response;
    }
}
