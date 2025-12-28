# Plan: Convert Groovy Tools to GraalJS

## 1. Objective
To convert all existing Groovy-based tools in `roxy_home/tools/` to GraalJS, ensuring functional equivalence and maintaining compatibility with the RoxyCode environment. This modernization effort aims to leverage the performance benefits and broader ecosystem of GraalJS.

## 2. Scope
All `.groovy` files located in the `roxy_home/tools/` directory, along with their corresponding `.toml` configuration files.

## 3. Approach

### 3.1. Analysis and Understanding
*   **Identify all Groovy Tools**: Create a comprehensive list of all `.groovy` files in the `roxy_home/tools/` directory.
*   **Understand Functionality**: For each Groovy tool, analyze its purpose, input parameters, and expected output. This will involve reviewing the Groovy code and its associated `.toml` definition.
*   **GraalJS Equivalents**: Research and identify equivalent GraalJS APIs or patterns for the functionalities currently implemented in Groovy (e.g., file system operations, process execution, string manipulation).

### 3.2. Conversion Process
For each Groovy tool:
1.  **Create GraalJS File**: Create a new `.js` file with the same base name as the `.groovy` file (e.g., `mytool.groovy` becomes `mytool.js`).
2.  **Translate Code**: Rewrite the Groovy logic into JavaScript, making use of GraalJS's capabilities and any available Java interop if necessary.
3.  **Ensure Functional Equivalence**: Rigorously test the new GraalJS script to ensure it produces the same outputs and behaves identically to its Groovy counterpart for all valid inputs and edge cases.
4.  **Update TOML Configuration**: Modify the corresponding `.toml` file to point to the newly created `.js` file instead of the `.groovy` file (e.g., change `script = "mytool.groovy"` to `script = "mytool.js"`).
5.  **Remove Groovy File (Post-Verification)**: Once the GraalJS version is thoroughly tested and verified, the original `.groovy` file can be removed.

### 3.3. Testing
*   **Unit/Integration Tests**: If existing tests are available for Groovy tools, adapt them for the GraalJS versions.
*   **Manual Testing**: Manually execute each converted GraalJS tool with various inputs to confirm correct operation.
*   **System-wide Compilation/Preview**: After converting a batch of tools, run `compile_project` and `launch_preview` (if applicable) to ensure no regressions are introduced into the overall system.

## 4. Checklist

- [x] **compile_project**:
    - [x] Read `compile_project.groovy`
    - [x] Write `compile_project.js`
    - [x] Update `compile_project.toml`
    - [x] Test `compile_project.js`
    - [x] Delete `compile_project.groovy`
- [x] **delete_file**:
    - [x] Read `delete_file.groovy`
    - [x] Write `delete_file.js`
    - [x] Update `delete_file.toml`
    - [x] Test `delete_file.js`
    - [x] Delete `delete_file.groovy`
- [x] **find_files**:
    - [x] Read `find_files.groovy`
    - [x] Investigate existing `find_files.js` and determine if `find_files.groovy` is still in use or if `find_files.js` is a partial/complete conversion.
    - [x] If needed, write `find_files.js` (or update existing)
    - [x] Update `find_files.toml`
    - [x] Test `find_files.js`
    - [x] Delete `find_files.groovy`
- [x] **git_diff**:
    - [x] Read `git_diff.groovy`
    - [x] Write `git_diff.js`
    - [x] Update `git_diff.toml`
    - [x] Test `git_diff.js`
    - [x] Delete `git_diff.groovy`
- [x] **git_status**:
    - [x] Read `git_status.groovy`
    - [x] Write `git_status.js`
    - [x] Update `git_status.toml`
    - [x] Test `git_status.js`
    - [x] Delete `git_status.groovy`
- [x] **grep**:
    - [x] Read `grep.groovy`
    - [x] Write `grep.js`
    - [x] Update `grep.toml`
    - [x] Test `grep.js`
    - [x] Delete `grep.groovy`
- [x] **launch_preview**:
    - [x] Read `launch_preview.groovy`
    - [x] Write `launch_preview.js`
    - [x] Update `launch_preview.toml`
    - [x] Test `launch_preview.js`
    - [x] Delete `launch_preview.groovy`
- [x] **ls**:
    - [x] Read `ls.groovy`
    - [x] Write `ls.js`
    - [x] Update `ls.toml`
    - [x] Test `ls.js`
    - [x] Delete `ls.groovy`
- [x] **read_document**:
    - [x] Read `read_document.groovy`
    - [x] Write `read_document.js`
    - [x] Update `read_document.toml`
    - [x] Test `read_document.js`
    - [x] Delete `read_document.groovy`
- [x] **read_file**:
    - [x] Read `read_file.groovy`
    - [x] Write `read_file.js`
    - [x] Update `read_file.toml`
    - [x] Test `read_file.js`
    - [x] Delete `read_file.groovy`
- [x] **read_files**:
    - [x] Read `read_files.groovy`
    - [x] Write `read_files.js`
    - [x] Update `read_files.toml`
    - [x] Test `read_files.js`
    - [x] Delete `read_files.groovy`
- [x] **replace_in_file**:
    - [x] Read `replace_in_file.groovy`
    - [x] Write `replace_in_file.js`
    - [x] Update `replace_in_file.toml`
    - [x] Test `replace_in_file.js`
    - [x] Delete `replace_in_file.groovy`
- [x] **run_tests**:
    - [x] Read `run_tests.groovy`
    - [x] Write `run_tests.js`
    - [x] Update `run_tests.toml`
    - [x] Test `run_tests.js`
    - [x] Delete `run_tests.groovy`
- [x] **write_file**:
    - [x] Read `write_file.groovy`
    - [x] Write `write_file.js`
    - [x] Update `write_file.toml`
    - [x] Test `write_file.js`
    - [x] Delete `write_file.groovy`

## 5. Implementation Progress

- [x] Plan created and saved as `roxy_project/plans/convert_groovy_to_graaljs.md`
- [x] `compile_project` converted to GraalJS.
- [x] `delete_file` converted to GraalJS.
- [x] `find_files` converted to GraalJS.
- [x] `git_diff` converted to GraalJS.
- [x] `git_status` converted to GraalJS.
- [x] `grep` converted to GraalJS.
- [x] `launch_preview` converted to GraalJS.
- [x] `ls` converted to GraalJS.
- [x] `read_document` converted to GraalJS.
- [x] `read_file` converted to GraalJS.
- [x] `read_files` converted to GraalJS.
- [x] `replace_in_file` converted to GraalJS.
- [x] `run_tests` converted to GraalJS.
- [x] `write_file` converted to GraalJS.
