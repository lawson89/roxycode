# Plan: ChangesView to List View

Change the project changes view from a tree view to a scrollable list view using `MarkdownPane` for efficiency and better readability.

## User Requirements
- Change `ChangesView` to be a list view instead of a tree.
- Make it scrollable.
- Use `JTextPane` (via `MarkdownPane`) for efficiency.

## Proposed Changes

### 1. UI Definition (`ChangesView.xml`)
- Update `src/main/resources/org/roxycode/ui/views/ChangesView.xml`.
- Remove `changesTree`.
- Name the `scroll-pane` as `changesScrollPane` so it can be accessed via `@Outlet`.

### 2. View Class (`ChangesView.java`)
- Update `src/main/java/org/roxycode/ui/views/ChangesView.java`.
- Remove `changesTree` and `addPathToTree`.
- Inject `ThemeService` to register the new `MarkdownPane`.
- Add `changesArea` as a `MarkdownPane`.
- Link `changesArea` to `changesScrollPane` in `init()`.
- Refactor `populateChangesTree` to `populateChangesList`.
- Format git status output as a markdown list:
    - `[A] file.txt` -> `* **[A]** file.txt` (or similar)
- Use colors/icons if possible via Markdown or `MarkdownPane` methods.

## Implementation Progress
- [x] Update `ChangesView.xml`
- [x] Update `ChangesView.java`
- [x] Verify the changes

## Verification Plan
- Launch the application and open a project with git changes.
- Check the "Changes" tab to see if it displays a scrollable list of changes.

**COMPLETED**
