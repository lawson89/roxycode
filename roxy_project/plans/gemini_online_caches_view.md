# Plan: Gemini Online Caches View

Implement a new screen to view and manage Gemini online context caches.

## Proposed Changes

### 1. UI Definition: GeminiOnlineCachesView.xml
- Create `src/main/resources/org/roxycode/ui/GeminiOnlineCachesView.xml`.
- Use a `scroll-pane` containing a `table` to list the caches.
- Columns: Name, Display Name, Model, Created, Expires, Tokens.
- Add a toolbar with:
    - "Refresh" button.
    - "Delete All" button.

### 2. MainFrame Updates
- File: `src/main/java/org/roxycode/ui/MainFrame.java`
- Add `@Outlet` for:
    - `geminiCachesTable` (JTable)
    - `geminiCachesModel` (DefaultTableModel)
- Implement `updateGeminiCachesView()`:
    - Call `geminiCacheService.listCaches()`.
    - Populate the table model with the results.
- Implement `onRefreshGeminiCaches()`:
    - Trigger `updateGeminiCachesView()`.
- Implement `onDeleteAllGeminiCaches()`:
    - Show confirmation dialog.
    - Call `geminiCacheService.deleteAllCaches()`.
    - Refresh view.
- Implement `onDeleteGeminiCache()` (triggered by a button or context menu):
    - Call `geminiCacheService.deleteCache(name)`.
    - Refresh view.

### 3. MainFrame.xml Updates
- File: `src/main/resources/org/roxycode/ui/MainFrame.xml`
- Add a new navigation button for "Gemini Caches" in the sidebar.
- Include the `GeminiOnlineCachesView.xml` in the main content card layout.

### 4. Theme Service
- Ensure the new view is themed correctly if necessary.

## Checklist
- [x] Create `GeminiOnlineCachesView.xml`.
- [x] Add navigation button to `MainFrame.xml`.
- [x] Update `MainFrame.java` with outlets and action handlers.
- [x] Implement table data population logic.
- [x] Test Refresh functionality.
- [x] Test Delete All functionality.
- [x] Test individual Delete functionality (e.g. via right-click or a button in the row).

## Progress
- [x] Phase 1: UI Definitions
- [x] Phase 2: Controller Implementation
- [x] Phase 3: Integration & Testing
