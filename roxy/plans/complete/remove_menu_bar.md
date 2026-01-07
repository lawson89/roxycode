# Plan - Remove Menu Bar

Remove the menu bar from the application UI and its corresponding logic in `MainFrame`.

## Proposed Changes

### UI Changes
#### [src/main/resources/org/roxycode/ui/MainFrame.xml]
- Remove the `<menu-bar>` element and all its children. (DONE)

### Java Changes
#### [src/main/java/org/roxycode/ui/MainFrame.java]
- Remove `@Outlet` fields: `settingsMenuItem`, `exitMenuItem`, `aboutMenuItem`. (DONE)
- Remove menu item listener registrations in `initListeners()`. (DONE)
- Remove menu item icon initializations in `initIcons()`. (DONE)
- Remove `onAbout` method. (DONE)

## Verification Plan

### Automated Tests
- Run `mvn test` to ensure no regressions. (DONE)

### Manual Verification
- Compile the project using `mvn clean compile`. (DONE)
- Run the application and verify the menu bar is gone. (User to verify)

## Implementation Progress
- [x] Remove `<menu-bar>` from `MainFrame.xml`.
- [x] Update `MainFrame.java` to remove menu-related fields and logic.
- [x] Verify build and tests.
