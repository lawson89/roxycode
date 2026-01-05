# Plan: Push Codebase Cache to Gemini

This plan outlines the steps to add a "Push to Gemini" button to the Codebase Cache screen, allowing the local codebase snapshot to be uploaded to Gemini's context caching service.

## User Interface Changes

### 1. Update `CodebaseCacheView.xml`
- [x] Add a new section (e.g., a `row-panel`) to display online cache information:
    - [x] `onlineCacheIdLabel`: To display the Gemini Cache ID.
    - [x] `onlineCacheTimestampLabel`: To display when the cache was last pushed.
- [x] Add a `pushCacheButton` with the text "Push to Gemini".
- [x] Arrange the layout so it remains organized.

### 2. Update `MainFrame.java`
- [x] **Add Outlets**:
    - `@Outlet private JLabel onlineCacheIdLabel;`
    - `@Outlet private JLabel onlineCacheTimestampLabel;`
    - `@Outlet private JButton pushCacheButton;`
- [x] **Initialize Icons**:
    - [x] Add an icon for `pushCacheButton` in `initIcons()` (e.g., `MaterialDesignC.CLOUD_UPLOAD_OUTLINE`).
- [x] **Initialize Listeners**:
    - [x] Add an action listener for `pushCacheButton` in `initListeners()` that calls `onPushCache()`.
- [x] **Update View Logic**:
    - [x] Modify `updateCodebaseCacheView()`:
        - [x] Call `geminiCacheService.getProjectCacheMeta()`.
        - [x] If metadata exists, populate `onlineCacheIdLabel` and `onlineCacheTimestampLabel`.
        - [x] Otherwise, set them to "Not Pushed" and "-".
        - [x] Enable `pushCacheButton` only if the local cache file exists.
- [x] **Implement `onPushCache()`**:
    - [x] Disable `pushCacheButton` and `rebuildCacheButton`.
    - [x] Show a "Pushing..." message in the `cacheContentArea` or a status label.
    - [x] Start a new thread to call `geminiCacheService.pushCache()`.
    - [x] On completion (using `SwingUtilities.invokeLater`):
        - [x] Re-enable buttons.
        - [x] Refresh the view by calling `updateCodebaseCacheView()`.
        - [x] If successful, show a success dialog.
        - [x] If it fails, show an error dialog.

## Service Changes

### `GeminiCacheService.java`
- [x] Add a `getClient()` method that returns the current client or creates one if the API key has changed.
- [x] Ensure `pushCache()` uses the latest API key from `settingsService`.
- [x] Review `pushCache()` to ensure it correctly handles the `cacheKey` and metadata storage.

## Unit Testing

### `GeminiCacheServiceTest.java`
- [x] Add a test case for `writeProjectCacheMeta` and `getProjectCacheMeta` to ensure metadata is correctly persisted and retrieved.
- [x] Add a test case for `pushCache` (will likely require significant mocking of the `google-genai` client).


## Progress Checklist
- [x] Update `CodebaseCacheView.xml`
- [x] Update `MainFrame.java` (Outlets, Icons, Listeners, View Logic)
- [x] Implement `onPushCache()` in `MainFrame.java`
- [x] Add tests in `GeminiCacheServiceTest.java`
- [x] Verify functionality manually

