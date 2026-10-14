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
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
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
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class AiService {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiService.class);
  private static final String NEWLINE_DOUBLE = "\n\n";
  private static final String HEADER_JEXL_CONTEXT = "\n\n## JEXL CONTEXT\n";
  private static final String HEADER_REPO_MAP = "\n\n## REPOSITORY MAP\n";
  private static final String HEADER_SESSION_CONTEXT = "\n\n## SESSION CONTEXT\n";
  private static final String HEADER_GIT_STATUS = "## GIT STATUS\n";
  private static final String HEADER_ADDITIONAL_INSTRUCTIONS = "## ADDITIONAL INSTRUCTIONS\n";
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
  private final RepoMapPackerService repoMapPackerService;
  private final GitService gitService;
  private final ProjectAnalysisService projectAnalysisService;

  public AiService(
      ChatClient.Builder chatClientBuilder,
      SettingsService settingsService,
      PromptService promptService,
      JexlServiceRegistry jexlServiceRegistry,
      WorkflowService workflowService,
      ChatMemory chatMemory,
      ApplicationEventPublisher eventPublisher,
      RepoMapPackerService repoMapPackerService,
      GitService gitService,
      ProjectAnalysisService projectAnalysisService) {
    this.settingsService = settingsService;
    this.promptService = promptService;
    this.jexlServiceRegistry = jexlServiceRegistry;
    this.workflowService = workflowService;
    this.chatMemory = chatMemory;
    this.eventPublisher = eventPublisher;
    this.repoMapPackerService = repoMapPackerService;
    this.gitService = gitService;
    this.projectAnalysisService = projectAnalysisService;
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

    StringBuilder systemPrompt = new StringBuilder(promptService.loadCoreWorkflowPrompt());
    systemPrompt.append(promptService.loadAllPrompts());
    systemPrompt
        .append(HEADER_JEXL_CONTEXT)
        .append(promptService.loadJexlContext())
        .append(NEWLINE_DOUBLE);
    systemPrompt.append(promptService.loadAllDocs());
    systemPrompt.append(promptService.loadProjectContext());

    if (currentPhase == WorkflowPhase.EXPLORE) {
      systemPrompt.append("\n\n").append(promptService.generateExploreSystemPrompt());
    }
    if (currentPhase == WorkflowPhase.REVIEW) {
      systemPrompt.append("\n\n").append(promptService.loadReviewPrompt());
    }
    if (currentPhase == WorkflowPhase.PLAN) {
      systemPrompt.append("\n\n").append(promptService.loadPlanPrompt());
    }
    if (currentPhase == WorkflowPhase.CODE) {
      systemPrompt.append("\n\n").append(promptService.loadCodePrompt());
    }

    EditorResult repoMap = repoMapPackerService.generateRepoMap();
    if (repoMap.success()) {
      systemPrompt.append(HEADER_REPO_MAP).append(repoMap.content()).append("\n");
    }

    systemPrompt.append(HEADER_SESSION_CONTEXT);
    systemPrompt
        .append("DOMINANT LANGUAGE: ")
        .append(projectAnalysisService.getDominantLanguage())
        .append("\n");
    systemPrompt.append("CURRENT PHASE: ").append(currentPhase.name()).append("\n");

    String persona =
        String.format(
            "You are the %s. %s", currentPhase.getPersonaTitle(), currentPhase.getDescription());
    systemPrompt.append(persona).append("\n\n");
    systemPrompt
        .append("You have access to the following JEXL tools:\n")
        .append(jexlServiceRegistry.getDocumentation(currentPhase));

    String gitStatus = gitService.getStatus();
    if (gitStatus != null && !gitStatus.isEmpty() && !gitStatus.startsWith("Error")) {
      systemPrompt.append(HEADER_GIT_STATUS).append(gitStatus).append(NEWLINE_DOUBLE);
    }
    if (systemPromptText != null) {
      systemPrompt
          .append(HEADER_ADDITIONAL_INSTRUCTIONS)
          .append(systemPromptText)
          .append(NEWLINE_DOUBLE);
    }

    return chatClient
        .prompt()
        .tools(tools)
        .system(systemPrompt.toString())
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
