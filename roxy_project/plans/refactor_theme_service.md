# Refactor Theme Service

## Goal
Move the theme application logic from `MainFrame` to a new `ThemeService` in the `ui` package.

## Steps

### 1. Create ThemeService
- [x] Create `src/main/java/org/roxycode/ui/ThemeService.java`.
- [x] Annotate with `@Singleton`.
- [x] Implement `applyTheme(String themeName, MarkdownPane... panes)`.
- [x] Move the `UIManager.setLookAndFeel` logic and `FlatLaf.updateUI()` into this method.
- [x] Iterate over provided panes and call `updateStyle()`.

### 2. Modify MainFrame
- [x] Inject `ThemeService` into `MainFrame` constructor.
- [x] Remove private `applyTheme` method from `MainFrame`.
- [x] Update usages of `applyTheme` to call `themeService.applyTheme`.
  - In `run()`: pass the panes (`chatArea`, `systemPromptArea`, `messageHistoryArea`, `summaryQueueArea`).
  - In `onSaveSettings()`: pass the panes.

### 3. Tests
- [x] Create `src/test/java/org/roxycode/ui/ThemeServiceTest.java`.
- [x] Test `applyTheme` logic (mocking UIManager might be hard, but we can check if it runs without exception or verify interaction with mocks if we abstract UIManager, but for now simple test that it executes).
  - Actually, testing Swing UIManager in headless env can be tricky. I'll write a basic test.
  - I can mock the `MarkdownPane`s and verify `updateStyle` is called.

## Implementation Progress
- [x] Create ThemeService
- [x] Modify MainFrame
- [x] Create Tests
- [x] Verify
