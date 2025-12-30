# Plan: View System Prompt Screen

We want to add a screen that shows the exact system prompt (context) being sent to the LLM. This is useful for debugging what knowledge is being included.

## User Review Required

> [!IMPORTANT]
> The system prompt can be quite large. I will put it in a scrollable text area.

## Proposed Changes

### Phase 1: Core Logic Update [DONE]
- [x] Update `GenAIService.java` to expose the method that builds the system prompt.
- [x] Ensure `chat` method uses this same method.

### Phase 2: UI Definition [DONE]
- [x] Create `SystemPromptView.xml` with:
    - [x] A large read-only `text-area` for the prompt.
    - [x] A "Refresh" button.
- [x] Add "View System Prompt" menu item to `MainFrame.xml`.

### Phase 3: MainFrame Integration [DONE]
- [x] Add `@Outlet` fields for `SystemPromptView.xml` components.
- [x] Instantiate the new view and add it to the `mainContentStack`.
- [x] Add listener for the menu item to switch to the new view.
- [x] Add listener for the "Refresh" button to re-fetch the prompt.
- [x] Trigger an initial refresh when the view is shown.

### Phase 4: Verification [DONE]
- [x] Compile and verify no syntax errors.

## Progress Tracking

- [x] Update `GenAIService.java`
- [x] Create `SystemPromptView.xml`
- [x] Update `MainFrame.xml`
- [x] Update `MainFrame.java` (Outlets, Listeners, View switching)
- [x] Verify Compilation
