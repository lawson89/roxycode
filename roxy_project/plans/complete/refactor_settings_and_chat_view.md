# Plan: Refactor Settings and Chat View

## Goal
Improve the layout and user experience of `ChatView.xml` and `SettingsView.xml`.

## Proposed Changes

### 1. ChatView.xml
- [ ] Compact the Top Bar: Move "Model" selection and "Clear Chat" to a single row with better spacing.
- [ ] Improve Input Area: Group "Attach", input field, "Send", and "Stop" buttons more cleanly.
- [ ] Optimize Status Bar: Use icons for message count and token counts (already partially done, but ensure layout is tight).
- [ ] Attachments: Ensure the attachments container doesn't take too much space when empty.

### 2. SettingsView.xml
- [ ] Enhance Tabbed Pane: Add icons to the tabs ("Global Settings", "Memory Compaction").
- [ ] Improved Layout: Use consistent padding and spacing.
- [ ] Grouping: Better visual separation between setting groups.

### 3. MainFrame.java
- [ ] Update `initIcons` to include icons for Settings tabs if possible (Sierra `tabbed-pane` might not support icons easily via XML, might need code).
- [ ] Ensure all new outlets are correctly wired.

## Implementation Steps

### Step 1: Refactor ChatView.xml
- Modify `src/main/resources/org/roxycode/ui/ChatView.xml`.
- Use `row-panel` for the top bar with a smaller weight for the model combo box.
- Improve the input area layout.

### Step 2: Refactor SettingsView.xml
- Modify `src/main/resources/org/roxycode/ui/SettingsView.xml`.
- Add more consistent spacing.

### Step 3: Update MainFrame.java
- Update `initSettings()` and `initIcons()` as needed.

## Verification
- Use `sierra_preview` to verify the layout of `ChatView.xml` and `SettingsView.xml`.
- Run the app and verify functionality (saving settings, clearing chat, sending messages).
