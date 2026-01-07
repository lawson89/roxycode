
# Plan: Support Colorization in System Logs View

Support color-coded output in the "System Logs" view to distinguish between standard output, errors, and log levels.

## Proposed Changes

### UI Components
- **LogsView.java**:
    - Replace `JTextArea` with `JTextPane`.
    - Update `init()` to configure the `JTextPane`.
    - Refactor `refresh()` to apply color styles to log lines based on their content (e.g., `[OUT]`, `[ERR]`, `INFO`, `WARN`, `ERROR`).
    - Use `StyledDocument` for efficient text insertion with attributes.

### Services
- No changes needed to `LogCaptureService` as it already provides the necessary prefixes (`[OUT]`, `[ERR]`).

## Implementation Checklist
- [x] Update `LogsView.java` to use `JTextPane`.
- [x] Define `AttributeSet` for different log categories (INFO, WARN, ERROR, OUT, ERR).
- [x] Implement log parsing and styled insertion in `LogsView.refresh()`.
- [x] Ensure `JTextPane` is registered with `ThemeService` if applicable.
- [x] Verify that auto-scroll still works with `JTextPane`.

## Verification Plan
- [ ] Manual verification: Open the System Logs view and check if `[ERR]` lines are red and `[OUT]` lines are in default or green color.
- [ ] Verify that log levels like `INFO`, `WARN`, `ERROR` (if present in the stream) are also colorized.
- [ ] Check if the theme (Dark/Light) correctly affects the log view.
