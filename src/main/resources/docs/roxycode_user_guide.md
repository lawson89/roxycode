# RoxyCode Built-In User Guide & Reference

This guide describes RoxyCode's workspaces, context & memory system, and configuration so you can accurately assist users when they ask how to use or configure RoxyCode itself.

## 1. Workspaces & Workflow Lifecycle

### Codebase Q&A (EXPLORE Phase — Technical Mentor)
- **Purpose**: Read-only codebase exploration, architecture Q&A, debugging guidance, and answering questions about RoxyCode itself.
- **Capabilities**: Uses fileReadService, grepService, symbolService, gitService, buildToolService (read/test commands), memoryService, userInputService, and webSearchService via executeJexl. File mutation (fileEditorService) is disabled.
- **Composer Features**:
  - Type '@' or click **@ Mention** to fuzzy-search and attach project file paths.
  - Press **Enter** to send, **Shift+Enter** for a newline, and **Ctrl+V** (or the image button) to attach screenshots/images.
  - Click **[Move to Feature Request]** on any Roxy response to copy that architectural finding or prompt directly into the **Feature Development** workspace.
- **Past Topics Tab**: Browse, search, and copy archived Q&A conversations (<projectDataDir>/archive/topics/*.md). Starting a **New Topic** prompts whether to archive the current conversation.

### Feature Development (PLAN -> CODE -> REVIEW Lifecycle)
- **1. Plan (PLAN — Lead Architect)**:
  - Roxy explores the codebase (read-only) and submits a structured implementation plan (planService.submitPlan) with a title, goal, requirements, and detailed blueprint technical steps containing exact file paths, 1-based line ranges, and method/logic specifications (plus automatic quality gates: "Code compiles successfully", "Unit tests pass", and "Automated code review passes").
  - Roxy calls planService.requestDevelopment(reason), displaying an **Approval Banner** (Approve / Reject). No files are modified until the user clicks **Approve**.
- **2. Code (CODE — Senior Developer)**:
  - Unlocks fileEditorService (writeFile, replaceBlock, replaceBlocks, replaceLines, replaceSymbol, insertAtLine, revertFile, deleteFile, moveFile, copyFile, mkdirs).
  - Roxy implements steps, marks technical steps completed in real time (planService.markStepCompleted / markStepsCompleted), and verifies changes with buildToolService.compile(), runTests(), runSingleTest(), format(), lint(), or buildAndTest().
  - Compilation and unit test verification steps are marked automatically by buildToolService (and reset if any file is edited afterward).
  - Once all steps and verification gates pass, Roxy autonomously transitions to REVIEW via planService.requestReview(reason) (or requests plan revision via requestPlanRevision).
- **3. Review (REVIEW — Code Reviewer)**:
  - Roxy audits gitService.getDiff(), test coverage, and adherence to requirements.
  - If issues are found, Roxy autonomously routes back to CODE via planService.requestFixes(reason).
  - If verification passes, Roxy marks the review gate complete and calls planService.requestCompletion(reason), prompting the user for final **Approve / Reject** sign-off. Approving archives the completed plan and resets for the next feature.
- **Plan & Past Plans Tabs**: The **Plan** tab renders live checklist progress and the handoff log; **Past Plans** searches archived plans (<projectDataDir>/archive/plans/*.md).

## 2. Context, Agent Memory & Repo Map

### Context & Reference Folders (Context Sidebar View)
- **Context Folders (Auto-Injected Docs)**: Manages external read-only folders whose top-level text/markdown files are automatically injected into Roxy's stable system prompt:
  - **Global Context Folders**: Applied across all projects (~/.roxycode/settings.toml).
  - **Project Context Folders**: Specific to the active repository (<projectDataDir>/context.toml), supporting project-relative or absolute paths.
- **Reference Folders (Read-Only `@alias` Source Code)**: Mounts external source repositories or directories as read-only `@<alias>` prefixes (e.g., `@billing-service`) without bloating the system prompt with full file contents:
  - Listed concisely under `## READ-ONLY REFERENCE FOLDERS` in the stable system prompt.
  - Addressable on demand via `@<alias>` or `@<alias>/subpath` in `fileReadService`, `grepService`, and `symbolService`, while strictly blocked from any writes or deletions in `fileEditorService`.

### Agent Memory (Agent Memory Sidebar View)
- Two-tier persistent memory (memoryService) shared across all phases:
  - **Global Memory (~/.roxycode/memory/)**: User-wide preferences and coding style (MEMORY.md + notes/<slug>.md).
  - **Project Memory (<projectDataDir>/memory/)**: Project architecture decisions and build/test gotchas (MEMORY.md + notes/<slug>.md).
- Pinned MEMORY.md files (capped at 8,192 chars / 200 lines each in the prompt) and note catalogs are injected into the stable prompt; full notes and archives are searchable via memoryService.search(pattern).

### Repo Map & Prompt (Repo Map Sidebar View)
- Displays the Universal Ctags structural skeleton (RepoMapPackerService) of source files with class/method line numbers, plus live token estimates for the Repo Map, Stable Context, and Session Context to maximize Gemini prefix cache hits.

## 3. Configuration & System Tools

### Project Settings & build.toml (Project Settings Sidebar View)
- **AI Model & Thinking Overrides**: Optionally override the Global Settings Gemini model and thinking level (ADAPTIVE, MINIMAL/NONE, LOW, MEDIUM, HIGH, or model default) for the active project. When **Adaptive (Phase-Aware)** is selected, Roxy dynamically adjusts thinking per sub-turn (PLAN: HIGH while designing the blueprint -> LOW after submitPlan; CODE: LOW for fast execution -> HIGH after any tool/build/test failure; REVIEW: MEDIUM; EXPLORE: LOW on initial discovery -> MEDIUM on synthesis).
- **Build Configuration (<projectDataDir>/build.toml)**:
  - Must be reviewed and confirmed (**Confirm & Save Settings**) before workspace chat is unlocked for a project. Built-in templates are provided for Maven, Gradle, npm/Node, Python, Rust, Go, CMake, and Default.
  - **[commands]**: Shell commands executed from the project root:
    - install: Sync dependencies (e.g., './mvnw dependency:resolve', 'npm install').
    - clean: Clean build artifacts.
    - compile: Compile source/test code; marks "Code compiles successfully" on exit code 0.
    - build: Package the project (falls back to compile if blank).
    - test_all: Run the test suite; marks "Unit tests pass" on exit code 0.
    - test_single: Run a single test using the {testName} placeholder (e.g., './mvnw test -Dtest={testName}').
    - lint: Static analysis / linter checks (executed as part of buildAndTest()).
    - format: Auto-format source code (triggers file change refresh).
  - **[env]**: Key-value environment variables passed to build commands (e.g., ROXY_CODE = "true").
  - **[parse]**:
    - error_regex / test_failure_regex: RipGrep regex patterns used to extract concise compiler and test failure blocks from build logs.
    - context_lines: Number of trailing log lines captured after each regex match.
    - junit_xml_paths: Directories or glob paths (e.g., ["target/surefire-reports"]) scanned for recent JUnit XML failure reports.
  - **[sandbox]**:
    - mode: "none" (host execution), "native" (OS-level sandbox using Linux bwrap or macOS sandbox-exec with read-only root and writable project directory), or "docker" (isolated Testcontainers container).
    - writable_paths: Extra host paths mounted read-write in "native" mode (supports ~/ expansion, e.g., ["~/.m2", "~/.cargo"]).
    - image & workdir: Container image (e.g., "eclipse-temurin:25-jdk") and working directory ("/workspace") when mode = "docker".

### System Tools, JEXL API & Global Settings
- **System Tools**: Checks status and install commands for required CLI tools (**Ripgrep**, **Universal Ctags**) and optional tools/sandbox runtimes (**Git**, **Bubblewrap** / **Seatbelt**, **Docker**).
- **JEXL API**: Interactive reference of all @AgentService methods exposed in executeJexl.
- **Global Settings**: Configures Light/Dark theme, Gemini API key, default Gemini model & thinking level, max tool turns per request, chat memory window, tool call logging, and Google Search grounding (webSearchService).
