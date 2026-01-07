# Plan: Improve UI Aesthetics

Modernize the RoxyCode UI by refining layouts, improving spacing, and enhancing component styling.

## Proposed Changes

### 1. MainFrame Layout (src/main/resources/org/roxycode/ui/MainFrame.xml)
- Remove `border="silver"` from the header row-panel.
- Replace it with a more subtle background or bottom border.
- Update the project/branch info layout to be more compact and icon-driven.
- Increase padding in the sidebar (from 12 to 16).
- Add a separator or border between the sidebar and the main content stack.

### 2. ChatView Layout (src/main/resources/org/roxycode/ui/views/ChatView.xml)
- Group the input area (attach button, text area, send/stop buttons) into a single row-panel with a distinct background/border.
- Style the bottom status bar (tokens/messages) to be more subtle (smaller font, consistent colors).

### 3. UsageView Layout (src/main/resources/org/roxycode/ui/views/UsageView.xml)
- Standardize padding to 20.
- Improve labels.

### 4. Code Changes (Java)
- Update `MainFrame.java` to add icons to project/branch labels.
- Update `ChatView.java` to ensure new UI components are correctly initialized.

## Progress Checklist
- [ ] Modify `MainFrame.xml` and validate.
- [ ] Modify `ChatView.xml` and validate.
- [ ] Modify `UsageView.xml` and validate.
- [ ] Update `MainFrame.java`.
- [ ] Update `ChatView.java`.
- [ ] Run `mvn compile` to verify.
- [ ] Run existing UI tests.

## Verification
- Run `mvn compile` to ensure no regressions.
- Use `sierraPreviewService.validateSierra()` for all XML changes.
