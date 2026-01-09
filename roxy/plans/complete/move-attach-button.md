# Goal
Move the 'Attach' button to the same row as the attachments list and 'Clear' button in ChatView for better layout efficiency.

# Proposed Changes
- Modify src/main/resources/org/roxycode/ui/views/ChatView.xml to move the attachButton element from the input row to the attachments row.
- Remove the attachButton from its current position next to the input field.

# Implementation Steps
- [ ] Locate ChatView.xml in src/main/resources/org/roxycode/ui/views/.
- [ ] Move the <button name="attachButton" text="Attach"/> tag into the row-panel containing 'Attachments:' label.
- [ ] Verify the spacing and layout in the XML.

# Implementation Progress
- [x] x Moved attachButton to attachments row in ChatView.xml
- [x] x Verified XML structure
