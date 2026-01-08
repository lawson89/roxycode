# Plan: Count Swing Timers

This plan outlines the steps to identify and count the number of Swing timers used in the project.

## User Requirements
- Determine the number of Swing timers used in the project.

## Analysis
- Swing timers are typically instances of `javax.swing.Timer`.
- They are often instantiated using `new Timer(int, ActionListener)`.

## Proposed Changes
- Search the codebase for `javax.swing.Timer` and `new Timer`.
- Confirm that the `Timer` class refers to `javax.swing.Timer`.
- Count the total number of occurrences.

## Steps
- [x] Search the codebase for `javax.swing.Timer` and `new Timer`.
- [x] Verify imports in the files where `Timer` is used.
- [x] Count the occurrences.
- [x] Report the total count to the user.

## Implementation Progress

### Step 1: Search the codebase
- Found 3 occurrences of `new Timer` in the following files:
    - `src/main/java/org/roxycode/ui/views/ChatView.java` (1 occurrence: `cacheStatusTimer`)
    - `src/main/java/org/roxycode/ui/MainFrame.java` (2 occurrences: anonymous timer and `notificationTimer`)

### Step 2: Verify imports
- Checked `src/main/java/org/roxycode/ui/views/ChatView.java`: Imports `javax.swing.*`.
- Checked `src/main/java/org/roxycode/ui/MainFrame.java`: Imports `javax.swing.*`.
- Both are using Swing timers.

### Step 3: Count the occurrences
- Total count is 3.

### Step 4: Report to the user
- Total of 3 Swing timers found.

**Status: Completed**
