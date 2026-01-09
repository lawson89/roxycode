# Goal
Introduce a split pane in the Chat view to allow resizing between the chat history and the input area, ensuring that both the history area and the input field can expand or shrink as needed.

# Proposed Changes
- Modify src/main/resources/org/roxycode/ui/views/ChatView.xml to wrap the chat history and input controls in a <split-pane>.
- Ensure the input area is grouped into a vertical container so it functions as a single unit in the split pane.
- Configure weights so that resizing the split pane correctly expands the chat history and the input field.
- Update ChatView.java if any outlets change or are added.

# Implementation Steps
- [ ] Modify ChatView.xml to introduce the <split-pane> with vertical orientation.
- [ ] Move the history <scroll-pane> into the top section of the split pane.
- [ ] Group the attachments <row-panel> and the input <row-panel> into a new <column-panel> and place it in the bottom section of the split pane.
- [ ] Assign weight='1' to the input area container to allow the input field to grow vertically when the divider is moved.
- [ ] Verify the UI behavior and component resizing.

# Implementation Progress
- [x] Switch to CODE mode
- [x] Move plan to in_progress
- [x] Modified ChatView.xml to include <split-pane name="splitPane">
- [x] Added splitPane outlet to ChatView.java
- [x] Verified valid Sierra XML
- [x] Ran unit tests (Success)
