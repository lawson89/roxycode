**Feature:** toml_service_v1

**Description:** A service to read and write TOML files.

**Plan:**
- [x] Create a new package `com.roxy.tomlservice`. (Package `org.roxycode.core.tools.service` already exists)
- [x] Create a new interface `TomlService` in the package. (Class `TomlService` already exists)
- [x] Create a new class `TomlServiceImpl` that implements the `TomlService` interface. (Class `TomlService` already exists)
- [x] Implement the `read` method in `TomlService`.
- [x] Implement the `write` method in `TomlService`.
- [x] Add a dependency for a TOML parsing library. (`jackson-dataformat-toml` is already a dependency)
- [x] Write unit tests for the `TomlService`.

**Implementation Progress:**
- [x] Plan created.
- [x] `roxy_project/plans/toml_service_v1.md` file created.
- [x] Plan written to the file.
- [x] `read` and `write` methods implemented in `TomlService`.
- [x] Unit tests for `read` and `write` methods created.
- [x] `ToolExecutionService` updated to inject `TomlService`.
