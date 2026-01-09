# Goal
Replace the standard JScrollPane with RTextScrollPane for the input field in ChatView to properly support features like vertical scrollbars and ensure compatibility with RSyntaxTextArea features.

# Proposed Changes
- Modify src/main/java/org/roxycode/ui/views/ChatView.java to import RTextScrollPane.
- Update the setupInputField method in ChatView.java to wrap the inputField in an RTextScrollPane instead of a JScrollPane.
- Set the vertical scrollbar policy to VERTICAL_SCROLLBAR_ALWAYS as requested by Choice 1.

# Implementation Steps
- [ ] Open src/main/java/org/roxycode/ui/views/ChatView.java
- [ ] Add 'import org.fife.ui.rsyntaxtextarea.RTextScrollPane;' to the imports section.
- [ ] Locate the setupInputField() method.
- [ ] Change 'JScrollPane sp = new JScrollPane(inputField);' to 'RTextScrollPane sp = new RTextScrollPane(inputField);'.
- [ ] Add 'sp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);' after creating the scroll pane.
- [ ] Compile the project to verify changes.
- [ ] Run the application to test the scrollbar visibility.

# Implementation Progress
- [x]  [x] Add import for RTextScrollPane (fixed package to org.fife.ui.rtextarea)
- [x]  [x] Update setupInputField to use RTextScrollPane and set vertical scrollbar policy
- [x]  [x] Verify changes by compiling
