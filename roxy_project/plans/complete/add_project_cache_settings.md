# Plan: Add Project Cache Settings

Add a section to the Settings view to configure project caching, including a toggle for enabling/disabling, a TTL in minutes, and a minimum cache size.

## User Review Required

> [!IMPORTANT]
> - Caching is a feature of Gemini that allows reusing previously processed content to save tokens and time. 
> - The TTL specifies how long the cache should live after its last use.
> - The minimum cache size determines the threshold for when a cache should be created (usually in tokens).

- [ ] Does the "Project Cache" tab name sound correct?
- [ ] Are the default values (Enabled: true, TTL: 60 min, Min Size: 1024 tokens) acceptable?

## Proposed Changes

### 1. `SettingsService.java`

- Add constants for cache setting keys and defaults.
- Implement getters and setters:
    - `isCacheEnabled()` / `setCacheEnabled(boolean)`
    - `getCacheTTL()` / `setCacheTTL(int)`
    - `getCacheMinSize()` / `setCacheMinSize(int)`

### 2. `SettingsView.xml`

- Add a new tab "Project Cache" to the `tabbed-pane`.
- Include UI components:
    - `cacheEnabledCheckBox` (JCheckBox)
    - `cacheTTLField` (JTextField)
    - `cacheMinSizeField` (JTextField)

### 3. `MainFrame.java`

- Add `@Outlet` fields for the new UI components.
- Update `initSettings()` to populate the UI from `SettingsService`.
- Update `onSaveSettings()` to save the UI values back to `SettingsService` with validation.

### 4. `SettingsServiceTest.java`

- Add test cases to verify the new cache settings.

## Verification Plan

### Automated Tests
- Run `mvn test -Dtest=SettingsServiceTest` to ensure settings are correctly persisted.

### Manual Verification
- Launch RoxyCode.
- Navigate to Settings -> Project Cache.
- Change values and click "Save Settings".
- Close and reopen Settings to ensure values persist.
- Restart the application to ensure values persist in `Preferences`.
