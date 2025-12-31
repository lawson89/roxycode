# Add Readme Locator to BuildToolService

## Feature Description
Add a method `getReadmeContents()` to the `BuildToolService` class to locate and return the contents of the project's README file.

The method should:
1.  Search for common README filenames (case-insensitive or specific variations like README.md, README.txt, README).
2.  Return the content of the first match found.
3.  Return a user-friendly message if no README is found or if an error occurs.

## Implementation Steps
- [x] Add `getReadmeContents()` method to `BuildToolService.java`.
    - [x] Define a list of potential filenames to check (e.g., `README.md`, `README.txt`, `README`, `readme.md`).
    - [x] Iterate through the list and check if the file exists in the sandbox root.
    - [x] If found, read and return the content.
    - [x] If not found, return a specific message.
- [x] Add a unit test for this new method in `BuildToolServiceTest.java`.
    - [x] Test with `README.md`.
    - [x] Test with no README.
- [x] Verify the feature works.

## Verification
- Run the new unit tests. (PASSED)
- **Complete**
