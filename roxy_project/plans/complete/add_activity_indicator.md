
# Plan: Add Activity Indicator for Busy State

Show an activity indicator next to the Roxy mode label in the header when the AI is busy processing a request.

## Proposed Changes

### 1. Core Services

#### [GenAIService.java]
- Add a way to observe the `isChatting` state. Since `isChatting` is already an `AtomicBoolean`, I can add a `PropertyChangeListener` or a simple callback mechanism.
- Alternatively, add a `isBusy()` method and have `MainFrame` poll or be notified.
- Better: Add `addBusyListener(Consumer<Boolean> listener)`.

### 2. UI Components

#### [MainFrame.xml]
- Add `<activity-indicator name="activityIndicator" indicatorSize="12" visible="false"/>` next to the `roxyModeLabel`.

#### [MainFrame.java]
- Add `@Outlet private ActivityIndicator activityIndicator;`.
- In `run()`, subscribe to the busy state from `GenAIService`.
- Implement a method to toggle the indicator's visibility and animation.

### 3. Implementation Details

- `ActivityIndicator` in Sierra usually has `start()` and `stop()` methods.
- When busy, call `activityIndicator.setVisible(true)` and `activityIndicator.start()`.
- When not busy, call `activityIndicator.stop()` and `activityIndicator.setVisible(false)`.

## Verification Plan

### Automated Tests
- N/A (UI visual change), but can check if `GenAIService` correctly notifies listeners.

### Manual Verification
- Run the application.
- Send a message to Roxy.
- Observe the activity indicator appearing next to the Roxy mode.
- Verify it disappears when the response is finished.
