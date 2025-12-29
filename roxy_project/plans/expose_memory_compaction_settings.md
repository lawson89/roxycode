# Plan: Expose Memory Compaction Settings

## Description
Expose memory compaction settings (history threshold, chunk size, max summary chunks) on the Settings pane to allow users to configure the history compaction behavior.

## Implementation Steps

### 1. Update SettingsService
- [x] Add constants for default values.
- [x] Add methods to get and set:
    - `historyThreshold` (Default: 50)
    - `compactionChunkSize` (Default: 15)
    - `maxSummaryChunks` (Default: 5)
- [x] Persist these settings using `Preferences`.

### 2. Update HistoryService
- [x] Inject `SettingsService` into `HistoryService`.
- [x] Replace hardcoded constants with calls to `SettingsService`.

### 3. Update UI (MainFrame)
- [x] Update `src/main/resources/org/roxycode/ui/MainFrame.xml`:
    - Add a new section for "Memory Compaction Settings".
    - Add input fields for:
        - History Threshold (integer)
        - Chunk Size (integer)
        - Max Summary Chunks (integer)
- [x] Update `src/main/java/org/roxycode/ui/MainFrame.java`:
    - Bind the new UI components.
    - Load values from `SettingsService` into the fields in `initSettings()`.
    - Save values from fields to `SettingsService` in `onSaveSettings()`.

### 4. Verify
- [x] Run unit tests.
- [x] Launch preview and verify the settings appear and can be saved.

## Progress
- [x] Plan created.
- [x] Implementation complete.
