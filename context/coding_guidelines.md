# PMD Rules (pmd-rules.xml)
*   **Enforces category/java/bestpractices.xml (excluding GuardLogStatement) and category/java/errorprone.xml. No need to read pmd-rules.xml.**

# Simple Java 21 Coding Guidelines

### Control Flow and Logic
*   **Restrict lambdas and method references:** While Java 21 supports robust functional paradigms, limit lambdas (`->`) and method references (`::`) to very simple, highly readable operations (no more than a few lines). For anything more complex, rely on plain procedural blocks and standard `for` loops.
*   **Enforce early returns (guard clauses):** Check for invalid conditions and return or throw immediately at the top of a method. This flattens the architecture and completely avoids deeply nested `if/else` blocks.
*   **Restrict complex pattern matching:** Although Java 21 has powerful pattern matching capabilities, avoid complex `switch` expressions with `when` clauses. Favor straightforward `instanceof` checks or basic `switch` statements to keep branching logic universally understandable.
*   **Always use a full set of brackets on if statements: **

### Syntax and Typing
*   **Ban `var` for local variables:** Require explicit type declarations (e.g., `String name` instead of `var name`). This ensures the code is completely readable and self-documenting without relying on an IDE's type inference.
*   **Limit inline assignments:** Forbid assigning variables inside conditional statements (e.g., do not use `if ((line = reader.readLine()) != null)`). Extract all variable assignments to their own distinct lines before the conditional check.
*   **Prohibit ternary operators:** Avoid the ternary operator (`? :`) entirely. Always use explicit `if/else` blocks to ensure conditional logic is immediately obvious at a glance.

### Architecture and Data
*   **Use Records with Jexl compliance:** Utilize Java 21 `record` structures for data-carrying classes to eliminate the clutter of manual constructors, `equals()`, and `hashCode()` methods. **However, if the data model will be evaluated by or exposed to the Jexl engine, it must conform to JavaBean conventions.** For these specific records, explicitly define standard getters and setters (e.g., `getProperty()`, `setProperty()`) inside the record body, or fall back to a standard POJO.
*   **Prefer composition over inheritance:** Avoid using the `extends` keyword and deep abstract class hierarchies. Favor flat class structures and build complex types through composition.
*   **Avoid generics wildcards:** Avoid complex bounded generics (such as `<? super T>` or `<? extends T>`) unless strictly mandated by a specific Java API. Keep type parameters simple and concrete.
