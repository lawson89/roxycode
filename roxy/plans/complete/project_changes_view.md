
# Plan: Change Project Files view to Project Changes view

The goal is to replace the "Project Files" view (which shows all files in the project) with a "Project Changes" view (which shows only files changed in git).

## Proposed Changes

### 1. Rename and Update UI Component
- Rename `FilesView.java` to `ChangesView.java`.
- Rename `FilesView.xml` to `ChangesView.xml`.
- Update `ChangesView.xml`:
    - Change title to "Project Changes".
    - Change root component name to `viewChanges`.
- Update `ChangesView.java`:
    - Inject `RoxyProjectService`.
    - Change `populateFileTree` to `populateChangesTree`.
    - Implement git status parsing to display changed files in the tree.

### 2. Update MainFrame
- Update `MainFrame.java` to use `ChangesView` instead of `FilesView`.
- Update `MainFrame.xml`:
    - Rename `navFilesButton` to `navChangesButton`.
    - Change button text to "Project Changes".
- Update `MainFrame.java` icons and listeners to reflect the change.

### 3. Implementation Details for Changes Tree
- Run `git status --porcelain` using `GitRunner`.
- Parse each line. Porcelain format is usually `XY PATH` or `XY PATH -> NEW_PATH`.
- Build a tree structure from the paths of changed files.

## Detailed Steps

### Step 1: Rename Files
- [ ] Rename `src/main/java/org/roxycode/ui/views/FilesView.java` to `src/main/java/org/roxycode/ui/views/ChangesView.java`.
- [ ] Rename `src/main/resources/org/roxycode/ui/views/FilesView.xml` to `src/main/resources/org/roxycode/ui/views/ChangesView.xml`.

### Step 2: Update ChangesView.xml
- [ ] Update `src/main/resources/org/roxycode/ui/views/ChangesView.xml`:
    - Set `name="viewChanges"`.
    - Set label text to "Project Changes".

### Step 3: Update ChangesView.java
- [ ] Update class name to `ChangesView`.
- [ ] Update constructor and injection.
- [ ] Update `@Outlet` for `viewChanges`.
- [ ] In `refresh()`, get git status and populate the tree.
- [ ] Implement logic to parse `git status --porcelain` and build a hierarchical tree.

### Step 4: Update MainFrame.xml
- [ ] Update `src/main/resources/org/roxycode/ui/MainFrame.xml`:
    - Rename `navFilesButton` to `navChangesButton`.
    - Update text to "Project Changes".

### Step 5: Update MainFrame.java
- [ ] Update `FilesView` field to `ChangesView changesView`.
- [ ] Update `navFilesButton` field to `navChangesButton`.
- [ ] Update constructor and `run()` method to use `changesView`.
- [ ] Update `initIcons()` for `navChangesButton`.
- [ ] Update `initListeners()` for `navChangesButton`.
- [ ] Update `showView()` for `CHANGES`.

### Step 6: Testing
- [ ] Verify that the view shows only changed files.
- [ ] Verify that the tree structure is correct.

## Implementation Progress (Complete)

- [x] Step 1: Rename Files
- [x] Step 2: Update ChangesView.xml
- [x] Step 3: Update ChangesView.java
- [x] Step 4: Update MainFrame.xml
- [x] Step 5: Update MainFrame.java
- [ ] Step 6: Testing
