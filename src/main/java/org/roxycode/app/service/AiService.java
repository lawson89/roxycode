package org.roxycode.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.JexlTool;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.YieldTurnException;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class AiService {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiService.class);
  private static final String NEWLINE_DOUBLE = "\n\n";
  private static final String FIELD_MESSAGE = "message";
  private static final String MAX_TURNS_EXCEEDED = "MAX_TOOL_TURNS_EXCEEDED";

  public String chat(String message) {
    return chat("default", workflowService.getCurrentPhase(), message, null, null);
  }

  public String chat(String message, List<byte[]> images) {
    return chat("default", workflowService.getCurrentPhase(), message, null, images);
  }

  private final ChatClient chatClient;
  private final SettingsService settingsService;
  private final PromptService promptService;
  private final JexlServiceRegistry jexlServiceRegistry;
  private final WorkflowService workflowService;
  private final ChatMemory chatMemory;
  private final ApplicationEventPublisher eventPublisher;

  public AiService(
      ChatClient.Builder chatClientBuilder,
      SettingsService settingsService,
      PromptService promptService,
      JexlServiceRegistry jexlServiceRegistry,
      WorkflowService workflowService,
      ChatMemory chatMemory,
      ApplicationEventPublisher eventPublisher) {
    this.settingsService = settingsService;
    this.promptService = promptService;
    this.jexlServiceRegistry = jexlServiceRegistry;
    this.workflowService = workflowService;
    this.chatMemory = chatMemory;
    this.eventPublisher = eventPublisher;
    this.chatClient =
        chatClientBuilder
            .defaultAdvisors(
                MessageChatMemoryAdvisor.builder(chatMemory).build(),
                ToolCallingAdvisor.builder()
                    .toolCallingManager(
                        ToolCallingManager.builder()
                            .toolExecutionExceptionProcessor(
                                DefaultToolExecutionExceptionProcessor.builder()
                                    .alwaysThrow(true)
                                    .build())
                            .build())
                    .build())
            .build();
  }

  public String chat(
      String conversationId,
      WorkflowPhase phase,
      String message,
      String systemPromptText,
      List<byte[]> images) {
    JexlTool ephemeralJexlTool = new JexlTool(jexlServiceRegistry, phase);
    AtomicInteger turnCount = new AtomicInteger(0);
    int maxTurns = settingsService.getSettings().getMaxAgentToolTurns();

    ephemeralJexlTool.addListener(
        event -> {
          int turn = turnCount.incrementAndGet();
          eventPublisher.publishEvent(
              new AgentTurnEvent(conversationId, "Roxy", turn, phase.name()));
          eventPublisher.publishEvent(
              new ToolExecutionEvent(
                  conversationId, event.script(), event.result(), event.success(), event.error()));
          if (turn > maxTurns) {
            throw new RuntimeException(MAX_TURNS_EXCEEDED);
          }
        });

    try {
      String content;
      try {
        content =
            buildPrompt(phase, message, systemPromptText, images, ephemeralJexlTool)
                .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                .call()
                .content();

        content = formatStructuredContent(content);
      } catch (Exception e) {
        YieldTurnException yieldEx = findYieldTurnException(e);
        if (yieldEx != null) {
          content = formatStructuredContent(yieldEx.getMessage());
          if (content != null && !content.isBlank()) {
            chatMemory.add(conversationId, new AssistantMessage(content));
          }
        } else if (isMaxToolTurnsExceeded(e)) {
          content = "Autonomous execution stopped: Maximum tool turns (" + maxTurns + ") exceeded.";
        } else {
          eventPublisher.publishEvent(
              new AgentTurnCompleteEvent(
                  conversationId, "Roxy", turnCount.get(), "Error: " + e.getMessage()));
          throw e;
        }
      }

      eventPublisher.publishEvent(
          new AgentTurnCompleteEvent(conversationId, "Roxy", turnCount.get(), content));
      return content;
    } finally {
      // Ephemeral tool, no cleanup needed
    }
  }

  private String formatStructuredContent(String raw) {
    if (raw != null && raw.trim().startsWith("{") && raw.contains(FIELD_MESSAGE)) {
      try {
        JsonNode node = new ObjectMapper().readTree(raw);
        if (node.has("summary")
            && !node.get("summary").asText().isBlank()
            && node.has(FIELD_MESSAGE)) {
          return node.get("summary").asText() + NEWLINE_DOUBLE + node.get(FIELD_MESSAGE).asText();
        } else if (node.has(FIELD_MESSAGE)) {
          return node.get(FIELD_MESSAGE).asText();
        }
      } catch (Exception e) {
        log.trace("Response is not valid JSON, using raw content");
      }
    }
    return raw;
  }

  private static YieldTurnException findYieldTurnException(Throwable t) {
    Throwable current = t;
    while (current != null) {
      if (current instanceof YieldTurnException yieldEx) {
        return yieldEx;
      }
      if (Objects.equals(current.getCause(), current)) {
        break;
      }
      current = current.getCause();
    }
    return null;
  }

  private static boolean isMaxToolTurnsExceeded(Throwable t) {
    Throwable current = t;
    while (current != null) {
      if (current.getMessage() != null && current.getMessage().contains(MAX_TURNS_EXCEEDED)) {
        return true;
      }
      if (Objects.equals(current.getCause(), current)) {
        break;
      }
      current = current.getCause();
    }
    return false;
  }

  private ChatClient.ChatClientRequestSpec buildPrompt(
      WorkflowPhase phase,
      String message,
      String systemPromptText,
      List<byte[]> images,
      JexlTool tools) {
    String activeModel = settingsService.getSettings().getGeminiModel();
    WorkflowPhase currentPhase = (phase != null) ? phase : workflowService.getCurrentPhase();
    String systemPrompt = promptService.buildSystemPrompt(currentPhase, systemPromptText);

    return chatClient
        .prompt()
        .tools(tools)
        .system(systemPrompt)
        .user(
            u -> {
              u.text(message);
              if (images != null) {
                for (byte[] img : images) {
                  u.media(
                      org.springframework.util.MimeTypeUtils.IMAGE_PNG,
                      new org.springframework.core.io.ByteArrayResource(img));
                }
              }
            })
        .options(
            org.springframework.ai.google.genai.GoogleGenAiChatOptions.builder()
                .model(activeModel));
  }
}
