### Phase 1: Core Service Enhancements (IMPLEMENTED)
- [x] Update `CodebasePackerService`:
    - [x] Add `getCacheFilePath()` method.
    - [x] Ensure `buildProjectCache()` is public.
- [x] Update `ThemeService`:
    - [x] Include `MarkdownPane` for codebase cache content in theme applications.

### Phase 2: UI Definitions (IMPLEMENTED)
- [x] Create `src/main/resources/org/roxycode/ui/CodebaseCacheView.xml`:
    - [x] Labels for Path, Last Modified, and Token Count.
    - [x] Rebuild Cache button.
    - [x] Scrollable `MarkdownPane` for content preview.
- [x] Update `src/main/resources/org/roxycode/ui/MainFrame.xml`:
    - [x] Add navigation button for "Codebase Cache".

### Phase 3: Main UI Controller Updates (IMPLEMENTED)
- [x] Update `MainFrame.java`:
    - [x] Add `@Outlet` for new components.
    - [x] Initialize icons for the new button.
    - [x] Implement `updateCodebaseCacheView()`: Populate labels and preview.
    - [x] Implement `onRebuildCache()`: Trigger background processing and refresh UI.
    - [x] Integrate view into `showView()` logic.

### Phase 4: Verification (IMPLEMENTED)
- [x] Compile and run the application.
- [x] Verify "Codebase Cache" navigation works.
- [x] Verify cache information is displayed correctly.
- [x] Verify "Rebuild Cache" button works and updates the view.
- [x] Ensure all existing tests pass. (Fixed broken test `CodebasePackerServiceTest.java`)

## Progress Tracking
- **Total Tasks**: 14
- **Completed**: 14
- **Status**: 100% Complete