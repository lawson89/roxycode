
# Plan: Rename RoxyModes (DISCOVER -> ASK, IMPLEMENT -> CODE)

Rename the `DISCOVER` and `IMPLEMENT` modes in `RoxyMode` enum to `ASK` and `CODE` respectively, and update all references including slash commands.

## User Review Required

> [!IMPORTANT]
> This change will rename the slash commands `/discover` to `/ask` and `/implement` to `/code`.

- None

## Proposed Changes

### Core

#### [src/main/java/org/roxycode/core/RoxyMode.java]
- Rename `DISCOVER` to `ASK`.
- Rename `IMPLEMENT` to `CODE`.

#### [src/main/java/org/roxycode/core/RoxyProjectService.java]
- Update default mode from `RoxyMode.DISCOVER` to `RoxyMode.ASK`.

#### [src/main/java/org/roxycode/core/SlashCommandService.java]
- Rename `/discover` command to `/ask`.
- Rename `/implement` command to `/code`.
- Update command logic and help text.

### UI

#### [src/main/java/org/roxycode/ui/MainFrame.java]
- Update `updateRoxyMode` method to handle `ASK` and `CODE` modes.

### Tests

#### [src/test/java/org/roxycode/core/SlashCommandServiceTest.java]
- Update test cases to use the new command names and enum constants.

## Verification Plan

### Automated Tests
- Run `mvn test -Dtest=SlashCommandServiceTest` to verify slash command changes.
- Run all tests to ensure no regressions.

### Manual Verification
- Verify that `/ask` and `/code` commands work as expected.
- Verify that the UI reflects the new mode names.


## Implementation Progress
- Renamed `DISCOVER` to `ASK` and `IMPLEMENT` to `CODE` in `RoxyMode.java`.
- Updated `RoxyProjectService.java` default mode.
- Updated `SlashCommandService.java` command logic, strings, and help text.
- Updated `MainFrame.java` UI logic.
- Updated `SlashCommandServiceTest.java` and verified with a test run.
- Verified no remaining occurrences of `DISCOVER` or `IMPLEMENT` in `.java` files.

**Work complete.**