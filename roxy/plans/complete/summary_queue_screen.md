# Summary Queue Screen Plan

## 1. Description
Create a new screen "Summary Queue" in the left menu. This screen will display the `summaryQueue` FIFO from `HistoryService`. This queue contains summaries of past conversation segments that have been compacted.

## 2. Implementation Steps

### Backend
- [ ] **Modify `HistoryService.java`**:
    - Add a `getSummaryQueue()` method that returns a copy or unmodifiable view of the `summaryQueue` (LinkedList<String>).

### UI Resources
- [ ] **Create `src/main/resources/org/roxycode/ui/SummaryQueueView.xml`**:
    - Use Sierra DSL.
    - Root element: `<column-panel>`.
    - Content: A `row-panel` with a header (Label + Refresh Button) and a `<scroll-pane>` containing a `<component class="org.roxycode.ui.MarkdownPane" id="summaryQueueArea"/>`.
    - This structure mirrors `MessageHistoryView.xml`.

### UI Logic (MainFrame.java)
- [ ] **Inject `HistoryService`**:
    - Add `HistoryService historyService` to the constructor and fields of `MainFrame`.
- [ ] **Add Outlets**:
    - `@Outlet private JButton navSummaryQueueButton;`
    - `@Outlet private JComponent viewSummaryQueue;` (Note: `UILoader` returns the root component, so we map the loaded view to this if needed, or manage visibility via the component name/reference).
    - `@Outlet private MarkdownPane summaryQueueArea;` (For the content).
    - `@Outlet private JButton refreshSummaryQueueButton;` (For the refresh button in the view).
    - `@Outlet private JScrollPane summaryQueueScrollPane;`
- [ ] **Initialize View**:
    - In `run()`:
        - Load `SummaryQueueView.xml` into `mainContentStack`.
        - Initialize `summaryQueueScrollPane` viewport with `summaryQueueArea`.
    - In `initIcons()`:
        - Set icon for `navSummaryQueueButton`.
        - Set icon for `refreshSummaryQueueButton`.
    - In `initListeners()`:
        - Add listener for `navSummaryQueueButton` to call `showView("SUMMARY_QUEUE")`.
        - Add listener for `refreshSummaryQueueButton` to call `updateSummaryQueueView()`.
- [ ] **Implement `updateSummaryQueueView()`**:
    - Get the queue from `historyService.getSummaryQueue()`.
    - Format the summaries (e.g., as Markdown list or separate blocks).
    - Update `summaryQueueArea` with the formatted text.
- [ ] **Update `showView()`**:
    - Add case for "SUMMARY_QUEUE".
    - Toggle visibility of `viewSummaryQueue`.
    - Call `updateSummaryQueueView()`.

### UI Layout (MainFrame.xml)
- [ ] **Add Navigation Button**:
    - Add `<button name="navSummaryQueueButton" text="Summary Queue" .../>` to the side menu `column-panel`.

## 3. Verification
- [ ] **Build**: Run `build_compile` to ensure no errors.
- [ ] **Test**:
    - Since we cannot run the UI interactively, we will verify the code changes.
    - We can write a unit test or integration test if possible, but manual verification of the code structure is primary here.
    - Check if `HistoryService.getSummaryQueue()` returns the expected list.
    - Check if `MainFrame` loads the view and sets up listeners.

## 4. Progress
- [ ] Plan Created
- [ ] Backend Updated
- [ ] View XML Created
- [ ] MainFrame Logic Updated
- [ ] MainFrame XML Updated
- [ ] Verification Complete
