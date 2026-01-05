# Plan: Refactor MainFrame Views

The `MainFrame` class is currently acting as a monolithic controller for all UI views, leading to a large and difficult-to-maintain class. This plan outlines the steps to refactor `MainFrame` by splitting the individual views into their own dedicated classes.

## Objectives
- Reduce the size and complexity of `MainFrame.java`.
- Improve modularity and separation of concerns.
- Enable easier testing of individual view logic.
- Leverage Micronaut's dependency injection for view-specific services.

## Proposed Architecture
- Each view will be moved to a new class in the `org.roxycode.ui.views` package.
- Views will be Micronaut-managed beans (`@Singleton` or `@Prototype`).
- Each view class will:
    - Receive its required services via constructor injection.
    - Handle its own `@Outlet` fields.
    - Initialize its UI using `UILoader.load(this, "View.xml")`.
    - Set up its own event listeners.
    - Expose its root component to be added to `MainFrame`'s `mainContentStack`.

## Implementation Steps

### Phase 1: Preparation
- [ ] Create the `org.roxycode.ui.views` package.
- [ ] Create a `BaseView` or interface if common functionality is identified (optional).

### Phase 2: Individual View Migration
For each view (Chat, Files, Usage, Settings, SystemPrompt, MessageHistory, Logs, CodebaseCache, GeminiOnlineCaches):
- [ ] Create the view class (e.g., `ChatView`).
- [ ] Move relevant `@Outlet` fields from `MainFrame` to the new class.
- [ ] Move relevant logic (listeners, helper methods, update methods) to the new class.
- [ ] Inject required services into the new class.
- [ ] Implement an initialization method (e.g., `@PostConstruct init()`) to load the XML and wire listeners.

### Phase 3: MainFrame Refactoring
- [ ] Remove migrated outlets and logic from `MainFrame`.
- [ ] Inject all new view beans into `MainFrame`.
- [ ] Update `MainFrame.run()` to add the view components to `mainContentStack`.
- [ ] Update `showView(String)` to manage the visibility of the new view components.
- [ ] Ensure shell-level outlets (Header, Git status, Mode) remain in `MainFrame`.

### Phase 4: Verification
- [ ] Ensure the project compiles.
- [ ] Run existing unit tests.
- [ ] Manually verify UI functionality:
    - Navigation between views.
    - Chat functionality (including attachments and status updates).
    - File tree population.
    - Usage stats display and reset.
    - Settings persistence.
    - Cache management.
- [ ] Create new unit tests for individual views where applicable.

## Progress Tracking
- [ ] Phase 1: Preparation
- [ ] Phase 2: Individual View Migration
    - [ ] ChatView
    - [ ] FilesView
    - [ ] UsageView
    - [ ] SettingsView
    - [ ] SystemPromptView
    - [ ] MessageHistoryView
    - [ ] LogsView
    - [ ] CodebaseCacheView
    - [ ] GeminiOnlineCachesView
- [ ] Phase 3: MainFrame Refactoring
- [ ] Phase 4: Verification

## Completion Criteria
- `MainFrame.java` is significantly reduced in size.
- All views function exactly as they did before the refactor.
- All tests pass.
