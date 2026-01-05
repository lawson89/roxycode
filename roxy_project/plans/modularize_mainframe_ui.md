# Plan: Modularize MainFrame UI

The `MainFrame.java` and `MainFrame.xml` files have grown too large and complex, containing logic for over 10 different views. This plan outlines the refactoring of the UI into smaller, modular components using Sierra DSL and Micronaut's dependency injection.

## Objectives
- Improve code maintainability and readability.
- Decouple view-specific logic from the main application shell.
- Enable easier testing and evolution of individual UI components.

## Proposed Structure
- `MainFrame.java` & `MainFrame.xml`: High-level shell with sidebar navigation, header, and a `stack-panel` for content.
- `org.roxycode.ui.views`: Package for individual view controllers.
- `src/main/resources/org/roxycode/ui/`: Dedicated XML files for each view.

## Components to Extract
1. **ChatView**: Main conversation interface.
2. **FilesView**: Project file explorer.
3. **UsageView**: API consumption statistics.
4. **SettingsView**: Global and conversation settings.
5. **SystemPromptView**: Live preview of the current system context.
6. **MessageHistoryView**: Full transcript of the conversation.
7. **LogsView**: System output and error logs.
8. **CodebaseCacheView**: Project snapshot and caching controls.
9. **SummaryQueueView**: (Future) Task summarization queue.
10. **GeminiOnlineCachesView**: Remote cache management.

## Implementation Steps
- [x] 📂 Created `org.roxycode.ui.views` package.
- [x] 🧱 Extracted individual view components.
- [x] 🎨 Updated `MainFrame.xml` to serve as a high-level shell.
- [x] 🏗️ Refactored `MainFrame.java` to inject and manage views.
- [x] 🔌 Integrated `ThemeService` registration mechanism.
- [x] ✅ Successfully compiled the project.

### Implementation Progress
- [x] 📂 Created `org.roxycode.ui.views` package.
- [x] 🧱 Extracted 10 individual view components.
- [x] 🎨 Updated `MainFrame.xml` to serve as a high-level shell with navigation and a `stack-panel`.
- [x] 🏗️ Refactored `MainFrame.java` to inject and manage these views.
- [x] 🔌 Integrated `ThemeService` with a registration mechanism.
- [x] 🔄 Verified status updates and project switching logic.
- [x] ✅ Successfully compiled the project.

**Work is complete.**
