package org.roxycode.app.ai.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.TurnToolContext;
import org.roxycode.app.events.UserInputRequestEvent;
import org.roxycode.app.events.UserInputRequestEvent.PromptType;
import org.roxycode.app.util.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;

/**
 * Provides interactive user prompts (confirmation, text input, single-select, and multi-select) rendered inline in the
 * chat panel.
 */
@AgentService(value = "userInputService", phases = {"*"})
@AgentDoc("Provides interactive user prompts (confirmation, text input, single-select, and multi-select) rendered inline in the chat panel.")
public class UserInputService {

    private static final String DOC_QUESTION = "The question to display to the user";
    private static final String DOC_OPTIONS = "The available options to choose from";

    private final ApplicationEventPublisher eventPublisher;
    private final ConcurrentMap<String, CompletableFuture<Object>> pendingRequests;
    private final String conversationId;

    @Autowired
    public UserInputService(@Nullable ApplicationEventPublisher eventPublisher) {
        this(eventPublisher, new ConcurrentHashMap<>(), TurnToolContext.DEFAULT_CONVERSATION_ID);
    }

    UserInputService(@Nullable ApplicationEventPublisher eventPublisher,
            ConcurrentMap<String, CompletableFuture<Object>> pendingRequests, String conversationId) {
        this.eventPublisher = eventPublisher;
        this.pendingRequests = Objects.requireNonNull(pendingRequests, "pendingRequests must not be null");
        this.conversationId = StringUtils.defaultIfBlank(conversationId, TurnToolContext.DEFAULT_CONVERSATION_ID);
    }

    public UserInputService forConversation(String targetConversationId) {
        return new UserInputService(this.eventPublisher, this.pendingRequests, targetConversationId);
    }

    public String getConversationId() {
        return conversationId;
    }

    /**
     * Asks the user a Yes/No confirmation question.
     *
     * @param question
     *            the question text
     * @return true if confirmed, false otherwise
     */
    @AgentDoc(value = "Asks the user a Yes/No confirmation question and returns true if confirmed.", examples = "userInputService.confirm('Proceed with database migration?')")
    public boolean confirm(@AgentDoc("The confirmation question to display to the user") String question) {
        return confirm(question, null);
    }

    @AgentDoc(value = "Asks the user a Yes/No confirmation question with additional details and returns true if confirmed.", examples = "userInputService.confirm('Delete legacy files?', 'This will remove 3 deprecated classes.')")
    public boolean confirm(@AgentDoc("The confirmation question to display to the user") String question,
            @AgentDoc("Optional context or details shown below the question") String details) {
        String validQuestion = requireNonBlankQuestion(question);
        String normalizedDetails = StringUtils.trimToNull(details);
        Object result = requestUserInput(PromptType.CONFIRM, validQuestion, normalizedDetails, null, List.of());
        return Boolean.TRUE.equals(result);
    }

    @AgentDoc(value = "Prompts the user to enter free-form text and returns the submitted string.", examples = "userInputService.askText('What package name should be used?')")
    public String askText(@AgentDoc(DOC_QUESTION) String question) {
        return askText(question, "");
    }

    @AgentDoc(value = "Prompts the user to enter free-form text pre-filled with a default value and returns the submitted string.", examples = "userInputService.askText('Target branch name?', 'main')")
    public String askText(@AgentDoc(DOC_QUESTION) String question,
            @AgentDoc("The initial default text pre-filled in the input field") String defaultValue) {
        String validQuestion = requireNonBlankQuestion(question);
        String safeDefault = StringUtils.defaultString(defaultValue);
        Object result = requestUserInput(PromptType.TEXT, validQuestion, null, safeDefault, List.of());
        if (result == null) {
            return "";
        }
        return result.toString();
    }

    @AgentDoc(value = "Prompts the user to choose a single option from a list and returns the selected option.", examples = "userInputService.selectOption('Which build tool?', ['Maven', 'Gradle'])")
    public String selectOption(@AgentDoc(DOC_QUESTION) String question, @AgentDoc(DOC_OPTIONS) List<String> options) {
        String validQuestion = requireNonBlankQuestion(question);
        List<String> validOptions = validateOptions(options);
        Object result = requestUserInput(PromptType.SINGLE_SELECT, validQuestion, null, null, validOptions);
        if (result == null) {
            return "";
        }
        return result.toString();
    }

    @AgentDoc(value = "Prompts the user to choose a single option from an array of options and returns the selected option.", examples = "userInputService.selectOption('Which build tool?', ['Maven', 'Gradle'])")
    public String selectOption(@AgentDoc(DOC_QUESTION) String question, @AgentDoc(DOC_OPTIONS) String... options) {
        List<String> list = List.of();
        if (options != null) {
            list = Arrays.asList(options);
        }
        return selectOption(question, list);
    }

    @AgentDoc(value = "Prompts the user to select zero or more options from a list and returns the selected options.", examples = "userInputService.selectMultiple('Select modules to update:', ['core', 'ui', 'api'])")
    public List<String> selectMultiple(@AgentDoc(DOC_QUESTION) String question,
            @AgentDoc(DOC_OPTIONS) List<String> options) {
        String validQuestion = requireNonBlankQuestion(question);
        List<String> validOptions = validateOptions(options);
        Object result = requestUserInput(PromptType.MULTI_SELECT, validQuestion, null, null, validOptions);
        if (result instanceof List<?> list) {
            List<String> selected = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    selected.add(item.toString());
                }
            }
            return List.copyOf(selected);
        }
        return List.of();
    }

    @AgentDoc(value = "Prompts the user to select zero or more options from an array of options and returns the selected options.", examples = "userInputService.selectMultiple('Select modules to update:', ['core', 'ui', 'api'])")
    public List<String> selectMultiple(@AgentDoc(DOC_QUESTION) String question,
            @AgentDoc(DOC_OPTIONS) String... options) {
        List<String> list = List.of();
        if (options != null) {
            list = Arrays.asList(options);
        }
        return selectMultiple(question, list);
    }

    public boolean cancelPendingRequest(String targetConversationId) {
        if (StringUtils.isBlank(targetConversationId)) {
            return false;
        }
        CompletableFuture<Object> future = pendingRequests.remove(targetConversationId);
        if (future != null && !future.isDone()) {
            return future.cancel(true);
        }
        return false;
    }

    public void cancelAllPendingRequests() {
        for (Map.Entry<String, CompletableFuture<Object>> entry : pendingRequests.entrySet()) {
            if (pendingRequests.remove(entry.getKey(), entry.getValue())) {
                CompletableFuture<Object> future = entry.getValue();
                if (future != null && !future.isDone()) {
                    future.cancel(true);
                }
            }
        }
    }

    private Object requestUserInput(PromptType promptType, String question, String details, String defaultValue,
            List<String> options) {
        ExceptionUtils.checkInterrupted("User input request interrupted before start");
        if (eventPublisher == null) {
            throw new IllegalStateException("ApplicationEventPublisher is not available for user input.");
        }
        CompletableFuture<Object> future = new CompletableFuture<>();
        CompletableFuture<Object> previous = pendingRequests.put(conversationId, future);
        if (previous != null && !previous.isDone()) {
            previous.cancel(true);
        }
        UserInputRequestEvent event = new UserInputRequestEvent(conversationId, promptType, question, details,
                defaultValue, options, future);
        try {
            eventPublisher.publishEvent(event);
            return future.get();
        } catch (RuntimeException e) {
            future.cancel(true);
            ExceptionUtils.rethrowIfCancelled(e, "User input request cancelled");
            throw e;
        } catch (Exception e) {
            future.cancel(true);
            ExceptionUtils.rethrowIfCancelled(e, "User input request cancelled");
            throw new IllegalStateException("Failed to await user input: " + ExceptionUtils.getRootCauseMessage(e), e);
        } finally {
            pendingRequests.remove(conversationId, future);
        }
    }

    private static String requireNonBlankQuestion(String question) {
        if (StringUtils.isBlank(question)) {
            throw new IllegalArgumentException("Question must not be null or blank.");
        }
        return question.trim();
    }

    private static List<String> validateOptions(List<String> options) {
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("Options must not be null or empty.");
        }
        List<String> sanitized = new ArrayList<>(options.size());
        for (String opt : options) {
            if (StringUtils.isBlank(opt)) {
                throw new IllegalArgumentException("Options must not contain null or blank values.");
            }
            sanitized.add(opt.trim());
        }
        return List.copyOf(sanitized);
    }
}
