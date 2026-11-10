# RoxyCode Request Lifecycle: User Input to LLM Response

This document outlines the execution path when a user submits a prompt in the RoxyCode UI. The architecture follows a strict Model-View-Controller (MVC) pattern combined with an event-driven autonomous agent loop.

## 1. User Input & UI Layer
The journey begins in the UI when the user types a message and presses Enter or clicks the Send button.

*   **Class:** `ChatPanel`
*   **Method:** `sendMessage(ActionEvent e)`
    *   Reads the text from the `inputArea`.
    *   Delegates to `controller.sendMessage(rawText)`.
    *   Clears the input area and attachments once the turn starts.

## 2. Controller & Session Management
The `ChatController` serves as the orchestrator for the conversation, managing the state, memory, and threading.

*   **Class:** `ChatController`
*   **Method:** `sendMessage(String rawText)`
    *   Handles slash commands (`/clear`, `/reset`, `/memory`) or drains any attached images (`model.drainPendingImages()`).
    *   Cancels any currently active turn.
    *   Updates the UI to display the user's message and image thumbnails immediately, and persists the active chat session.
    *   Calls `triggerTurn(text, pendingImages)` to start the AI processing asynchronously.
*   **Method:** `triggerTurn(String text, List<BufferedImage> pendingImages)`
    *   Generates a unique `turnId` and submits a background task to the `turnExecutor` (a Virtual Thread executor).
    *   Inside the background thread, it encodes any attached images to PNG bytes and invokes `aiService.chat(conversationId, phase, text, encodedImages)`.

## 3. Prompt Construction & Context Gathering
Before contacting the LLM, the system builds a prefix-cache-friendly System Prompt tailored to the current `WorkflowPhase` (`EXPLORE`, `PLAN`, `CODE`, or `REVIEW`).

*   **Class:** `AiService`
*   **Method:** `chat(String conversationId, WorkflowPhase phase, String message, List<byte[]> images)`
    *   Calls `buildPrompt(turnContext, message, images)`.
*   **Class:** `PromptService`
*   **Method:** `buildSystemPrompt(WorkflowPhase phase)`
    *   *Context Gathering steps inside `buildSystemPrompt`:*
        1.  **Stable Context (`getStableContext()`):** Loads the base workflow protocol (`system-prompt.st`), JEXL documentation (`loadJexlContext()`), built-in user guide (`loadUserGuideContext()`), global and project context folders (`loadGlobalContext()`, `loadProjectContext()`), mounted read-only reference folders (`loadReferenceFoldersContext()`), and global/project agent memory (`loadAgentMemoryContext()`).
        2.  **Repository Map:** Appends the Universal Ctags structural repository map (`repoMapPackerService.generateRepoMap()`).
        3.  **Phase Instructions:** Appends the phase-specific prompt (`loadPhasePrompt(currentPhase)`).
        4.  **Static Phase Context (`getStaticPhaseContext(currentPhase)`):** Appends the dominant language, active phase persona, and phase-filtered JEXL API documentation (`jexlServiceRegistry.getDocumentation(currentPhase)`).
        5.  **Volatile Session Context (`getVolatileSessionContext()`):** Appends current Git branch/status (`gitService.getCurrentBranch()`, `gitService.getStatus()`) and active implementation plan checklist progress (`planService.getCurrentPlan()`).

## 4. LLM Invocation & Spring AI Advisor Chain
With the prompt constructed, `AiService` executes the call through Spring AI's `ChatClient` advisor pipeline.

*   **Class:** `AiService`
*   **Method:** `executeChatCall(ChatClient.ChatClientRequestSpec promptSpec, String convId, WorkflowPhase phase)`
    *   **Memory Advisor (`MessageChatMemoryAdvisor`):** Loads and persists conversation history from `ChatMemory`.
    *   **Tool Calling Advisor (`ToolCallingAdvisor`):** Manages the autonomous tool-calling loop with `JexlTool` until the model produces a final response or reaches the turn limit.
    *   **Auto-Nudge Advisor (`AutoNudgeAdvisor`):** Ordered before chat memory persistence; if the model executed tools during the turn but returned an empty text response with a recoverable finish reason, it automatically nudges the model (up to `AiService.MAX_AUTO_NUDGES` times) to summarize what it did.
    *   **Retry & Telemetry Advisor (`ModelCallRetryAdvisor`):** Retries transient API errors (e.g., HTTP 429, 503, network timeouts) via `RetryTemplate` and publishes `TokenUsageEvent` telemetry after each model call.

## 5. Tool Execution (JEXL Sandbox)
When the LLM outputs a tool call, it targets `JexlTool.executeJexl`, passing a JavaScript-like orchestration script.

*   **Class:** `JexlTool`
*   **Method:** `executeJexl(String script, ToolContext toolContext)`
    *   Evaluates the JEXL script against the `JexlServiceRegistry`, which exposes authorized `@AgentService` beans permitted for the active phase (e.g., `fileReadService`, `fileEditorService`, `grepService`, `symbolService`, `buildToolService`, `planService`).
    *   Publishes `AgentTurnEvent`, `AgentActivityEvent`, and `ToolExecutionEvent` via the `ApplicationEventPublisher`.
    *   Serializes the final result of the script (or any error) into a string and returns it to Spring AI, which feeds it back to the LLM.
*   **Class:** `ChatController` / `ChatPanel` / `MarkdownPane`
    *   Listens for `ToolExecutionEvent` and renders collapsible tool execution cards in the UI while the LLM is still working.

## 6. Response Formatting & Edge Cases
Once the advisor chain completes and returns a `ChatResponse`, `ChatResponseFormatter` formats the output.

*   **Class:** `ChatResponseFormatter`
*   **Method:** `formatChatResponse(ChatResponse chatResponse, TurnToolContext turnContext)`
    *   Extracts the text content (`extractResponseText`) and finish reason (`extractFinishReason`).
    *   If the model hit a token limit (`MAX_TOKENS` or `LENGTH`), appends or formats a truncation diagnostic banner (`formatTokenLimitResponse`).
    *   If the response still has no visible text after advisor execution, formats an empty-response diagnostic message (`formatEmptyResponse`).
    *   `AiService.chat(...)` publishes an `AgentTurnCompleteEvent` and returns the formatted string to `ChatController`.

## 7. UI Rendering & Turn Completion
The controller receives the final formatted response on the background turn thread and updates the UI on the Swing EDT.

*   **Class:** `ChatController`
*   **Method:** `finishTurn(long turnId, String role, String message)`
    *   Appends the assistant message to `ChatPanel` (`view.appendMessage(role, message)`).
    *   Clears the loading state, updates the turn status label, persists the active chat session to disk, and applies any deferred autonomous workflow transition (`deferredTransitionEvent`).
*   **Class:** `MarkdownPane`
    *   **Method:** `appendMessage(String role, String content)`
    *   Renders the Markdown response (including syntax-highlighted code blocks and PlantUML/Ditaa diagrams) into HTML 3.2 using `flexmark-java` and displays it in the `JTextPane`, concluding the request lifecycle.
