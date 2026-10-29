package org.roxycode.app.service;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.TurnToolContext;
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
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

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
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientRequest currentRequest = chatClientRequest;
        while (true) {
            ChatClientResponse response = callAdvisorChain.copy(this).nextCall(currentRequest);
            ChatResponse chatResponse = null;
            if (response != null) {
                chatResponse = response.chatResponse();
            }
            String text = AiService.extractResponseText(chatResponse);
            if (!shouldAutoNudge(text, chatResponse)) {
                return response;
            }
            nudgeCount++;
            log.info("Empty response detected after tool use. Auto-nudging (attempt {}/{})", nudgeCount,
                    AiService.MAX_AUTO_NUDGES);
            if (!hasGenerationOutput(chatResponse)) {
                chatMemory.add(turnContext.conversationId(), new AssistantMessage(AiService.EMPTY_STOP_MESSAGE));
            }
            currentRequest = buildNudgeRequest(chatClientRequest);
        }
    }

    private boolean shouldAutoNudge(String text, ChatResponse chatResponse) {
        if (StringUtils.isNotBlank(text)) {
            return false;
        }
        if (turnContext.turnCounter().get() <= 0 || nudgeCount >= AiService.MAX_AUTO_NUDGES) {
            return false;
        }
        String finishReason = AiService.extractFinishReason(chatResponse);
        return "STOP".equalsIgnoreCase(finishReason) || AiService.FINISH_REASON_UNKNOWN.equalsIgnoreCase(finishReason);
    }

    private static boolean hasGenerationOutput(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResults() == null || chatResponse.getResults().isEmpty()) {
            return false;
        }
        for (Generation generation : chatResponse.getResults()) {
            if (generation != null && generation.getOutput() != null) {
                return true;
            }
        }
        return false;
    }

    private static ChatClientRequest buildNudgeRequest(ChatClientRequest originalRequest) {
        List<Message> nudgeMessages = new ArrayList<>(2);
        Prompt originalPrompt = originalRequest.prompt();
        if (originalPrompt != null && originalPrompt.getSystemMessage() != null) {
            SystemMessage sysMsg = originalPrompt.getSystemMessage();
            if (StringUtils.isNotBlank(sysMsg.getText())) {
                nudgeMessages.add(sysMsg);
            }
        }
        nudgeMessages.add(new UserMessage(AiService.AUTO_NUDGE_PROMPT));
        Prompt nudgePrompt;
        if (originalPrompt != null && originalPrompt.getOptions() != null) {
            nudgePrompt = new Prompt(nudgeMessages, originalPrompt.getOptions());
        } else {
            nudgePrompt = new Prompt(nudgeMessages);
        }
        return originalRequest.mutate().prompt(nudgePrompt).build();
    }
}
