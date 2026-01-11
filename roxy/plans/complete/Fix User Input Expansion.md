# Goal
Ensure the user input area (RSyntaxTextArea) in the Chat View expands vertically to fill the available space when the split pane slider is adjusted.

# Proposed Changes
- In `ChatView.java`, modify `setupInputField()` to set the layout of `inputContainer` to `BorderLayout`.
- In `ChatView.java`, add the `JScrollPane` (containing `inputField`) to `inputContainer` using `BorderLayout.CENTER`.
- In `ChatView.xml`, ensure the row-panel containing the input area is configured to stretch its children vertically if necessary.

# Implementation Steps
- [ ] Modify `src/main/java/org/roxycode/ui/views/ChatView.java` to set `BorderLayout` on `inputContainer`.
- [ ] Modify `src/main/java/org/roxycode/ui/views/ChatView.java` to add the `JScrollPane` with `BorderLayout.CENTER` constraint.
- [ ] Test the UI by adjusting the split pane slider to verify that the input area now expands and shrinks correctly.

# Implementation Progress
- [x] Modify src/main/java/org/roxycode/ui/views/ChatView.java to set BorderLayout on inputContainer.
- [x] Modify src/main/java/org/roxycode/ui/views/ChatView.java to add the JScrollPane with BorderLayout.CENTER constraint.
- [x] Verify that the input area now expands and shrinks correctly.
