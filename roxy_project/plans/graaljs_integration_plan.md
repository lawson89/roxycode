# GraalJS Integration Plan

This plan outlines the steps to integrate GraalJS as a scripting language into the RoxyCode project.

## Phase 1: Pulling in Dependencies

### Objective
To add the necessary GraalJS libraries to the project's `pom.xml` file.

### Tasks

1.  **Add GraalJS Dependencies to `pom.xml`**
    *   **File:** `pom.xml`
    *   **Description:** Add the following Maven dependencies to the `<dependencies>` section of the `pom.xml` file.
        *   `org.graalvm.js:js`
        *   `org.graalvm.js:js-scriptengine`
        *   `org.graalvm.sdk:graal-sdk`
    *   **Note:** Ensure to use compatible versions, preferably from a stable GraalVM release. For example, for GraalVM 21.0.0, you might use version `21.0.0.2`.

```xml
<dependency>
    <groupId>org.graalvm.js</groupId>
    <artifactId>js</artifactId>
    <version>${graalvm.version}</version>
</dependency>
<dependency>
    <groupId>org.graalvm.js</groupId>
    <artifactId>js-scriptengine</artifactId>
    <version>${graalvm.version}</version>
</dependency>
<dependency>
    <groupId>org.graalvm.sdk</groupId>
    <artifactId>graal-sdk</artifactId>
    <version>${graalvm.version}</version>
</dependency>
```
    *   **Action:** Add a `graalvm.version` property to the `<properties>` section of `pom.xml` for easier version management.

## Phase 2: Implementing Changes to ToolExecutionService

### Objective
To modify the `ToolExecutionService` to support the execution of GraalJS scripts for tools.

### Tasks

1.  **Locate and Modify `ToolExecutionService.java`**
    *   **File:** `src/main/java/org/roxycode/core/tools/ToolExecutionService.java`
    *   **Description:** This service is responsible for executing various tools. We will extend its functionality to handle JavaScript tools.

2.  **Add Necessary Imports**
    *   **Description:** Add the required GraalVM Polyglot API imports to `ToolExecutionService.java`.
        ```java
        import org.graalvm.polyglot.Context;
        import org.graalvm.polyglot.Source;
        import org.graalvm.polyglot.Value;
        ```

3.  **Implement `executeJavaScript` Method**
    *   **Description:** Create a new private method in `ToolExecutionService.java` to execute JavaScript code.
        ```java
        private String executeJavaScript(String script) {
            try (Context context = Context.newBuilder("js")
                    .allowAllAccess(true) // Consider security implications and restrict as needed
                    .build()) {
                // Bind the 'sandbox' object or other necessary objects to the JavaScript context
                // For example: context.getBindings("js").putMember("sandbox", this.sandbox);
                // The current Groovy scripts use 'sandbox', so we'll need to figure out how to expose that to JS
                // Alternatively, we could pass individual services (e.g., fileSystemService) directly.

                Value result = context.eval(Source.create("js", script));
                return result != null ? result.toString() : "";
            } catch (Exception e) {
                // Log or handle the exception appropriately
                return "Error executing JavaScript: " + e.getMessage();
            }
        }
        ```
    *   **Note:** The binding of the `sandbox` object or individual services needs careful consideration to ensure JavaScript tools have the necessary access, similar to existing Groovy tools.

4.  **Integrate with `executeTool` Method**
    *   **Description:** Modify the existing `executeTool` method (or a new dispatch method) to determine the scripting language based on the tool definition (e.g., a new `scriptType` field in the tool's TOML file or by convention like file extension) and call the appropriate execution method (`executeGroovy` or `executeJavaScript`).

5.  **Update Tool Definition Schema (Implicit)**
    *   **Description:** If tool definitions are currently tied to Groovy, consider how to specify JavaScript as the scripting language for new tools. This might involve updating the `run_script.toml` or creating a new `run_javascript.toml` tool definition if needed. This is beyond the scope of Phase 1, but important for future phases.
