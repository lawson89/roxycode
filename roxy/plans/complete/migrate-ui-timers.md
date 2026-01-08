# Plan: Migrate UI Background Timers to Micronaut Scheduled Service

This plan outlines the steps to migrate UI background timer tasks into a single Micronaut scheduled service that executes on the Swing EDT.

## User Requirements
- Migrate UI background timer tasks into a single Micronaut scheduled service.
- Ensure updates are executed on the Swing EDT.

## Analysis
- Currently, `ChatView` has a `javax.swing.Timer` to update cache status every 5 seconds.
- `MainFrame` also starts a new repeating `javax.swing.Timer` to update cache status every time the `GenAIService` becomes busy, which is redundant and potentially leaks timers.
- A one-shot `notificationTimer` exists in `MainFrame`, but it is event-driven and should remain separate or be handled specifically.
- Micronaut's `@Scheduled` can be used to create a recurring task in a `@Singleton` service.

## Proposed Changes
1.  **Create `org.roxycode.ui.UISchedulerService`**:
    - Annotate with `@Singleton`.
    - Inject `ChatView`.
    - Create a method `updateUI()` annotated with `@Scheduled(fixedDelay = "5s")`.
    - In `updateUI()`, call `chatView.updateCacheStatus()`.
    - Note: `chatView.updateCacheStatus()` already uses `SwingUtilities.invokeLater()` internally.

2.  **Modify `ChatView.java`**:
    - Remove `cacheStatusTimer` field.
    - Remove `initCacheStatusTimer()` method and its call in `init()`.

3.  **Modify `MainFrame.java`**:
    - Remove the redundant `new Timer(...)` inside the `busyListener`.

## Steps
- [ ] Create `src/main/java/org/roxycode/ui/UISchedulerService.java`.
- [ ] Remove the timer from `src/main/java/org/roxycode/ui/views/ChatView.java`.
- [ ] Remove the redundant timer from `src/main/java/org/roxycode/ui/MainFrame.java`.
- [ ] Verify that Micronaut scheduling is working (by running or manual inspection).
- [ ] Ensure all tests pass.

## Implementation Progress
- [x] Step 1: Create `UISchedulerService`
- [x] Step 2: Clean up `ChatView`
- [x] Step 3: Clean up `MainFrame`
- [x] Step 4: Verification

**Status: Completed**
