# Plan to Replace Bootstrap Icons with Material Design 2 Icons

## Feature Name: Replace Bootstrap Icons with Material Design 2 Icons

## Goal:
Convert all existing Bootstrap icons in the project to their equivalent Material Design 2 icons using the Ikonli library.

## Steps:

1.  **Analyze Current Icon Usage:**
    *   Search the project for common Bootstrap icon patterns (e.g., `<i class="bi bi-ICON_NAME">`, or any references to "bi-").
    *   Identify all files that use Bootstrap icons. These are likely `.java`, `.xml` (especially Sierra DSL files, if applicable), or `.html` files.
    *   *Tools to use:* `grep` with patterns like `bi bi-`, `class=".*bi-"`

2.  **Add Ikonli Material Design 2 Dependency:**
    *   Verify `pom.xml` contains the `ikonli-materialdesign2-pack` dependency. If not, add the following to `pom.xml`:

        ```xml
        <dependency>
            <groupId>org.kordamp.ikonli</groupId>
            <artifactId>ikonli-materialdesign2-pack</artifactId>
            <version>12.4.0</version>
        </dependency>
        ```
    *   Ensure the `ikonli-swing` (or `ikonli-javafx` if applicable) dependency is also present, as RoxyCode uses Java Swing for its UI (implied by Sierra DSL context).

3.  **Map Bootstrap Icons to Material Design 2 Icons:**
    *   Create a mapping table (or list) of Bootstrap icon names to their most appropriate Material Design 2 counterparts. This will be a manual step and might require developer judgment to choose the best visual match.
    *   *Example Mapping (to be expanded during implementation):*
        *   `bi-plus` -> `MD2_ADD`
        *   `bi-pencil` -> `MD2_EDIT`
        *   `bi-trash` -> `MD2_DELETE`
        *   `bi-folder` -> `MD2_FOLDER`
        *   `bi-file` -> `MD2_INSERT_DRIVE_FILE`

4.  **Refactor Code to Use Ikonli Material Design 2 Icons:**
    *   For each file identified in Step 1, replace Bootstrap icon declarations with Ikonli Material Design 2 icon declarations.
    *   The exact syntax for Ikonli will depend on how it's integrated with the UI framework (e.g., `new FlatSVGIcon("path/to/icon.svg")` for FlatLaf, or direct Ikonli component usage). If Sierra DSL is used, refer to `sierradsl.toml` and `sierra.dtd` for icon declaration syntax.
    *   *Tools to use:* `replace_in_file` for bulk replacements, `read_file` to understand file content.

5.  **Compile and Test:**
    *   Run `mvn clean compile` to ensure the project compiles without errors after dependency changes and code refactoring.
    *   Run `run_tests` to execute all JUnit tests to catch any regressions.
    *   Manually inspect the UI (using `launch_preview` if applicable, or by running the application) to ensure all icons are displayed correctly and are visually appealing.

## Definition of Done:
*   All Bootstrap icons are replaced with Material Design 2 icons.
*   The application builds and runs successfully.
*   All existing tests pass.
*   The UI displays icons correctly without visual regressions.
