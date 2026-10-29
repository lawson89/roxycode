#RoxyCode Request Lifecycle: User Input to LLM Response

This document outlines the execution path when a user submits a prompt in the RoxyCode UI. The architecture follows a strict Model-View-Controller (MVC) pattern combined with an event-driven autonomous agent loop.

## 1. User Input & UI Layer
The journey begins in the UI when the user types a message and presses Enter or clicks the Send button.

*   **Class:** `ChatPanel`
*   **Method:** `sendMessage(ActionEvent e)`
    *   Reads the text from the `inputArea`.
    *   Extracts any attached images (via `model.drainPendingImages()`).
    *   Calls `controller.sendMessage(rawText)`.
    *   Clears the input area and disables the send button to prevent double-submissions.

## 2. Controller & Session Management
The `ChatController` serves as the orchestrator for the conversation, managing the state, memory, and threading.

*   **Class:** `ChatController`
*   **Method:** `sendMessage(String rawText)`
    *   Cancels any currently active turn.
    *   Appends the user's message to the `ChatMemory` (using `ROLE_USER`).
    *   Updates the UI to display the user's message immediately.
    *   Calls `triggerTurn(rawText)` to start the AI processing asynchronously.
*   **Method:** `triggerTurn(String text)`
    *   Generates a unique `turnId` and submits a background task to the `turnExecutor` (a Virtual Thread executor).
    *   Inside the background thread, it invokes `aiService.chat(conversationId, phase, text, images)`.

## 3. Prompt Construction & Context Gathering
Before contacting the LLM, the system must build a massive, context-rich System Prompt tailored to the current `WorkflowPhase` (Explore, Plan, Code, or Review).

*   **Class:** `AiService`
*   **Method:** `chat(...)`
    *   Calls `buildPrompt(turnContext, message, additionalInstructions, images)`.
*   **Class:** `PromptService`
    *   **Method:** `buildSystemPrompt(WorkflowPhase phase, String additionalInstructions)`
    *   *Context Gathering steps inside `buildSystemPrompt`:*
        1.  **Base Instructions:** Loads the phase-specific prompt (`loadPhasePrompt(phase)`).
        2.  **JEXL API:** Injects the JEXL tool documentation (`loadJexlContext()`).
        3.  **Agent Memory:** Injects global and project memory (`loadAgentMemoryContext()`).
        4.  **Repo Map:** Injects the Universal Ctags structural repository map (`repoMapPackerService.generateRepoMap()`).
        5.  **Session Context:** Injects custom user context files (`getSessionContext()`).
        6.  **Git Status:** Injects current working tree state (`gitService.getStatus()`).
        7.  **Plan Progress:** Injects the active implementation plan progress (`planService.getCurrentPlan()`).

## 4. LLM Invocation & Autonomous Tool Loop
With the prompt constructed, RoxyCode hands execution over to Spring AI, which manages the HTTP communication and the recursive tool-calling loop.

*   **Class:** `AiService`
*   **Method:** `executeChatCall(ChatClientRequestSpec promptSpec, String convId, TurnToolContext turnContext)`
    *   Executes the request using Spring AI's `chatClient.prompt(promptSpec).call().chatResponse()`.
    *   **Retry Logic:** Uses a `ModelCallRetryAdvisor` (configured in `AiConfig`) to automatically retry on transient API errors (e.g., HTTP 429 Too Many Requests, 503 Service Unavailable).
    *   **Tool Loop:** If the LLM decides to invoke a tool, Spring AI pauses the generation, invokes the registered Java function (`JexlTool`), and sends the result back to the LLM. This loop continues autonomously until the LLM decides to output standard text or hits the maximum turn limit.

## 5. Tool Execution (JEXL Sandbox)
When the LLM outputs a tool call, it targets the `executeJexl` function, passing a JavaScript-like script.

*   **Class:** `JexlTool`
*   **Method:** `executeJexl(String script, ToolContext toolContext)`
    *   Evaluates the JEXL script against the `JexlServiceRegistry`, which exposes authorized `@AgentService` beans (e.g., `fileReadService`, `grepService`, `buildToolService`).
    *   Publishes a `ToolExecutionEvent` via the `ApplicationEventPublisher`.
    *   Serializes the final result of the script (or any exceptions thrown) into a JSON string and returns it to Spring AI, which feeds it back to the LLM.
*   **Class:** `ChatPanel` / `MarkdownPane`
    *   Listens for `ToolExecutionEvent` and renders the real-time tool execution logs (e.g., *"Executed JEXL Script... Success"*) in the UI while the LLM is still "thinking".

## 6. Response Processing & Edge Cases
Once the LLM finishes its autonomous loop and returns a final text response (or hits a token limit), `AiService` processes the raw output.

*   **Class:** `AiService`
*   **Method:** `formatChatResponse(ChatResponse chatResponse, TurnToolContext turnContext)`
    *   Extracts the text content.
    *   Checks the `finishReason`. If the model ran out of tokens (`MAX_TOKENS`), it appends a diagnostic warning (`formatTokenLimitResponse`).
    *   If the model completed its tool turns but returned an empty text string, it formats an empty state warning (`formatEmptyResponse`).
    *   Returns the final formatted string back to the `ChatController`.

## 7. UI Rendering & Auto-Nudge Completion
The controller receives the text response and updates the application state.

*   **Class:** `ChatController`
*   **Method:** `finishTurnSuccess(long turnId, String response)`
    *   Appends the AI's response to the `ChatMemory`.
    *   Calls `finishTurn(...)` which handles the **Auto-Nudge** logic.
    *   **Auto-Nudge (`shouldAutoNudgeOnResponse`):** If the LLM executed tools but returned a blank message (a common LLM behavioral quirk), the controller automatically schedules an invisible follow-up prompt: *"Roxy, you completed tool turns but didn't provide a final summary..."* to force the LLM to explain what it did.
    *   If no auto-nudge is required, it publishes an `AgentTurnCompleteEvent`.
*   **Class:** `ChatPanel`
    *   **Method:** `onAgentTurnComplete(AgentTurnCompleteEvent event)`
    *   Receives the event and passes the Markdown string to the `MarkdownPane`.
*   **Class:** `MarkdownPane`
    *   **Method:** `appendMessage(String role, String content)`
    *   Renders the final Markdown response into HTML using `flexmark-java` and displays it in the `JTextPane`, concluding the request lifecycle.