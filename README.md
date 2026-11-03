# RoxyCode

RoxyCode is a desktop AI coding assistant built with Java 21, Spring Boot, Spring AI, and a FlatLaf Swing interface. It pairs Google Gemini models with a structured **Explore / Plan / Code / Review** workflow, a sandboxed JEXL tool execution engine, and automatic repository indexing via Ripgrep, Git, and Universal Ctags.

## Key Features

### 1. Structured Four-Phase Workflow
RoxyCode separates exploratory Q&A from feature implementation using four distinct phases:

| Phase | Persona | Access Mode | Purpose |
| :--- | :--- | :--- | :--- |
| **EXPLORE** | Technical Mentor | Read-Only | Codebase Q&A, architecture discovery, and technical guidance without modifying files. |
| **PLAN** | Lead Architect | Read-Only + Plan | Requirements gathering, codebase analysis, and drafting an implementation plan with verification gates. |
| **CODE** | Senior Developer | Read / Write | Executing the approved plan, editing files, compiling, and running unit tests. |
| **REVIEW** | Code Reviewer | Read-Only + Audit | Auditing git diffs, running full test suites, and verifying all plan requirements before completion. |

- **Human-in-the-Loop Approvals**: Transitions from **PLAN → CODE** and **REVIEW → PLAN** require explicit user approval in the UI.
- **Autonomous Verification Loop**: Transitions between **CODE → REVIEW** and **REVIEW → CODE** (when defects or test failures are found) run autonomously until quality gates pass.

### 2. Sandboxed JEXL Tooling Engine
Instead of issuing one tool call per file read or search, the agent executes scripts through an Apache Commons JEXL 3 sandbox (`executeJexl`). This allows the model to batch multiple workspace operations in a single turn:

- `fileReadService`: Read single or multiple files, line ranges, and directory listings within the project root.
- `fileEditorService`: Create, modify, move, copy, or delete files (restricted strictly to the **CODE** phase).
- `grepService`: Fast regex, literal, and file-glob searches powered by Ripgrep (`rg`).
- `symbolService`: File symbol outlines and symbol body extraction powered by Universal Ctags (`ctags`).
- `gitService`: Inspect branch status, working tree diffs, blame ranges, and commit history.
- `buildToolService`: Run project compilation, full test suites, single tests, linting (`lint`), and code formatting (`format`).
- `memoryService`: Read, write, and search persistent global and project agent memory.
- `planService`: Manage implementation plans, step checklists, handoff logs, and workflow phase transitions.
- `userInputService`: Render inline user confirmation, text input, single-select, and multi-select prompts in the chat panel.
- `webSearchService`: Perform live web searches with grounded citations using Google Search grounding.

Only methods explicitly annotated with `@AgentService` and `@AgentDoc` and permitted for the active workflow phase are accessible from JEXL.

### 3. Repository Map & Context Management
- **Repository Map**: Generates a compact structural map of the repository (file paths and method/type signatures extracted with Universal Ctags) so the model can navigate the codebase without reading every file upfront.
- **Context Folders**: Attach global context folders (shared across all projects) and project-specific context folders (such as coding guidelines or architecture notes) that are automatically injected into the system prompt.
- **File Mentions & Image Attachments**: Reference project files in chat with `@filename` autocomplete or attach images (screenshots, diagrams) directly to a prompt.

### 4. Two-Tier Agent Memory & Session Archives
- **Global Memory (`~/.roxycode/memory/`)**: Stores cross-project user preferences and coding conventions in a pinned `MEMORY.md` file and tagged Markdown notes.
- **Project Memory (`~/.roxycode/projects/<project>/memory/`)**: Stores project-specific architecture notes and build/test details.
- **Searchable Archives**: Completed implementation plans and past Q&A topics are automatically archived as Markdown files with frontmatter and can be browsed or searched from the UI or via `memoryService`.

### 5. Configurable Builds & Sandboxing
Each project has a customizable `build.toml` configuration (auto-initialized from built-in templates for Maven, Gradle, Rust, Go, Python, npm, or CMake) that defines install, clean, compile, build, test, lint, and format commands along with regex patterns and JUnit XML paths for parsing compiler errors and test failures.

Build and test execution supports configurable sandboxing via the `[sandbox]` section in `build.toml` (`mode = "none" | "native" | "docker"`):
- **Native OS Sandboxing (`mode = "native"`)**: Uses Linux Bubblewrap (`bwrap`) or macOS Seatbelt (`sandbox-exec`) to mount the host filesystem read-only while restricting writes to the project workspace and configured `writable_paths`.
- **Docker Container Isolation (`mode = "docker"`)**: Runs build and test commands inside an isolated Testcontainers Docker container with the project workspace bind-mounted.

## Prerequisites

### Required System Tools
RoxyCode checks for the following CLI tools on startup (and provides a **System Tools** status page in the UI):

- **Java 21+** (JDK 21 is required to build and run RoxyCode)
- **Ripgrep (`rg`)**: Fast text and file search
- **Git (`git`)**: Version control inspection and diff auditing
- **Universal Ctags (`ctags`)**: JSON-capable ctags binary for symbol outlines and repository map generation

### Optional Tools
- **Bubblewrap (`bwrap`)** (Linux) or **Seatbelt (`sandbox-exec`)** (macOS): Used when native OS build sandboxing is enabled (`[sandbox] mode = "native"` in `build.toml`).
- **Docker**: Required only if you enable Docker container-isolated builds (`[sandbox] mode = "docker"` in `build.toml`).

### API Credentials
- A **Google Gemini API key** (configured in the application **Settings** panel or via the `GOOGLE_API_KEY` environment variable).

## Getting Started

### Build and Run from Source
Clone the repository and use the included Maven Wrapper (`./mvnw` on Linux/macOS or `mvnw.cmd` on Windows):

```bash
git clone https://github.com/lawson89/roxycode.git
cd roxycode

# Compile and run the desktop application
./mvnw spring-boot:run
```

### Package an Executable JAR
```bash
./mvnw clean package
java -jar target/roxycode-0.0.1-SNAPSHOT.jar
```

### Run Tests and Static Analysis
The build enforces code formatting with Spotless (Eclipse formatter) and static analysis with PMD:

```bash
# Run unit tests
./mvnw test

# Run full verification (tests, Spotless check, and PMD)
./mvnw verify

# Auto-format source code with Spotless
./mvnw spotless:apply
```

## Configuration & Data Layout

RoxyCode stores user settings, caches, memory, and per-project metadata under `~/.roxycode/`:

```text
~/.roxycode/
├── settings.toml                # Theme, Gemini model, API key, turn/memory limits, global context folders
├── system-tools.toml            # Cached CLI tool detection results
├── logs/
│   ├── roxy.log                 # Application log
│   └── toolcalls.log            # Optional JEXL tool execution log (when enabled in Settings)
├── memory/
│   ├── MEMORY.md                # Pinned global memory cheat-sheet
│   └── notes/                   # Global topic notes (*.md)
└── projects/
    └── <project-name>-<hash>/
        ├── project.toml         # Per-project model, thinking level, and build confirmation state
        ├── build.toml           # Project build/test/lint/format commands and sandbox settings
        ├── context.toml         # Project-scoped context folder paths
        ├── tmp/repo_cache.txt   # Cached repository map snapshot
        ├── memory/
        │   ├── MEMORY.md        # Pinned project memory cheat-sheet
        │   └── notes/           # Project topic notes (*.md)
        └── archive/
            ├── plans/           # Archived implementation plans (*.md)
            └── topics/          # Archived Codebase Q&A topics (*.md)
```

## Technology Stack

- **Runtime & Framework**: Java 21, Spring Boot 4.1, Spring AI 2.0 (Google GenAI / Gemini)
- **Desktop UI**: Swing, FlatLaf (Light & Dark themes), MigLayout, RSyntaxTextArea, Flexmark (Markdown rendering), Ikonli (Codicons & FontAwesome icons)
- **Agent Sandbox & Caching**: Apache Commons JEXL 3, Caffeine, Jackson (JSON & TOML), Testcontainers

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
