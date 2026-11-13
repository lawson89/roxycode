# RoxyCode

[![CI](https://github.com/lawson89/roxycode/actions/workflows/ci.yml/badge.svg)](https://github.com/lawson89/roxycode/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![Spring AI 2.0](https://img.shields.io/badge/Spring%20AI-2.0-6DB33F.svg)](https://spring.io/projects/spring-ai)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

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

- **Human-in-the-Loop Approvals**: Transitions from **PLAN → CODE** and **REVIEW → PLAN** require explicit user approval in the UI (with an optional **Auto-approve Plan** toggle in the composer toolbar).
- **Autonomous Verification Loop**: Transitions between **CODE → REVIEW** and **REVIEW → CODE** (when defects or test failures are found) run autonomously until quality gates pass.
- **Native Diagram Rendering & One-Click Handoffs**: Renders fenced `plantuml` (`puml`), `ditaa`, and `svg` (via `jsvg`) Markdown blocks as theme-aware inline diagrams and UI wireframes, and provides **[Move to Feature Request]** and **[Apply to build.toml]** action links to hand off findings or generated build configs directly from **Ask Roxy** (**EXPLORE**) into **Build Feature** or **Project Settings**.

### 2. Agent Profiles, Adaptive Phase-Aware Thinking & Prefix-Cache Telemetry
- **3-Profile AgentProfile System**: Configure global or per-project **Agent Profiles** (**Max**: `Carbon → Hydrogen`, **Normal**: `Argon → Hydrogen`, **Speed**: `Pure Hydrogen`) with phase-aware model routing (`EXPLORE`/`PLAN` vs. `CODE`/`REVIEW`) and **Adaptive (Phase-Aware)** sub-turn thinking:
  - **PLAN**: `MEDIUM` on initial discovery → `HIGH` while designing the architectural blueprint after tool responses → `LOW` after `submitPlan`.
  - **CODE**: `MEDIUM` by default → `HIGH` after a tool/build/test failure.
  - **REVIEW**: `HIGH`.
  - **EXPLORE**: `LOW` on initial discovery → `MEDIUM` on synthesis.
- **Prefix-Cache Optimization & Live Telemetry**: Structures the stable system prompt, repository map, and pinned agent memory to maximize Gemini context prefix-cache hits, displaying live token usage, cache-hit percentages, and estimated turn/plan cost in the status bar and composer toolbar.

### 3. Sandboxed JEXL Tooling Engine
Instead of issuing one tool call per file read or search, the agent executes scripts through an Apache Commons JEXL 3 sandbox (`executeJexl`). This allows the model to batch multiple workspace operations in a single turn:

- `fileReadService`: Read single or multiple files, line ranges, and directory listings within the project root or mounted `@<alias>` reference folders.
- `fileEditorService`: Create, modify, replace symbols/blocks, revert, move, copy, or delete files (restricted strictly to the **CODE** phase).
- `grepService`: Fast regex, literal, and file-glob searches powered by Ripgrep (`rg`).
- `symbolService`: File symbol outlines, symbol body extraction, cross-project symbol search (`findSymbol`), usage analysis (`findUsages`), and related test discovery (`findRelatedTests`) powered by Universal Ctags (`ctags`) and Ripgrep.
- `gitService`: Inspect branch status, working tree and branch diffs, blame ranges, and commit history.
- `buildToolService`: Run project compilation, full test suites, single tests, related tests (`runRelatedTests`), linting (`lint`), and code formatting (`format`).
- `memoryService`: Read, write, and search persistent global and project agent memory and session archives.
- `planService`: Manage implementation plans, step checklists, handoff logs, and workflow phase transitions.
- `userInputService`: Render inline user confirmation, text input, single-select, and multi-select prompts in the chat panel.
- `webSearchService`: Perform live web searches with grounded citations using Google Search grounding.

Only methods explicitly annotated with `@AgentService` and `@AgentDoc` and permitted for the active workflow phase are accessible from JEXL.

### 4. Repository Map, Context Folders & Read-Only Reference Folders (`@alias`)
- **Repository Map**: Generates a compact structural map of the repository (file paths and method/type signatures with line numbers extracted via Universal Ctags) so the model can perform targeted symbol-first reads without reading every file upfront.
- **Context Folders (Auto-Injected Docs)**: Attach global context folders (shared across all projects) and project-specific context folders (such as coding guidelines or architecture notes) whose top-level text/Markdown files are automatically injected into the stable system prompt.
- **Read-Only Reference Folders (`@alias` Source Code)**: Mount external repositories or directories as read-only `@<alias>` prefixes (e.g., `@billing-service`) addressable on demand across `fileReadService`, `grepService`, and `symbolService` without bloating the system prompt or permitting writes.
- **File Mentions & Image Attachments**: Reference project files in chat with `@filename` autocomplete or attach images (screenshots, diagrams) directly to a prompt.

### 5. Two-Tier Agent Memory & Session Archives
- **Global Memory (`~/.roxycode/memory/`)**: Stores cross-project user preferences and coding conventions in a pinned `MEMORY.md` file and tagged Markdown notes (quick-add via `/rem-g <note>`).
- **Project Memory (`~/.roxycode/projects/<project>/memory/`)**: Stores project-specific architecture notes and build/test details (quick-add via `/rem-p <note>`).
- **Searchable Archives**: Completed implementation plans and past Q&A topics are automatically archived as Markdown files with frontmatter (starting a **New Topic** automatically archives non-empty Q&A conversations in the background with a title generated by `gemini-3.8-flash`). Both **Past Plans** and **Past Topics** support browsing, searching, copying, inline renaming, and deletion from the UI or via `memoryService`.

### 6. Configurable Builds, Interactive "Configure with Roxy" & Sandboxing
Each project has a customizable `build.toml` configuration (auto-initialized from built-in templates for Maven, Gradle, Rust, Go, Python, npm, or CMake) that defines install, clean, compile, build, test, lint, and format commands along with regex patterns and JUnit XML paths for parsing compiler errors and test failures.

- **Interactive "Configure with Roxy"**: Click **Configure with Roxy** in **Project Settings** to launch an interactive **Ask Roxy** session that inspects your project files and installed host toolchains/JDKs (`~/.sdkman`, `~/.jdks`, `~/.nvm`, `~/.cargo`, etc.), asks clarifying questions if needed, and generates a tailored `build.toml` with a one-click **[Apply to build.toml]** button.
- **Configurable Build Sandboxing**: Build and test execution supports configurable sandboxing via the `[sandbox]` section in `build.toml` (`mode = "none" | "native" | "docker"`):
  - **Native OS Sandboxing (`mode = "native"`)**: Uses Linux Bubblewrap (`bwrap`) or macOS Seatbelt (`sandbox-exec`) to mount the host filesystem read-only (with configurable `readable_paths` for user-installed toolchains/JDKs) while restricting writes to the project workspace and configured `writable_paths` (such as package caches).
  - **Docker Container Isolation (`mode = "docker"`)**: Runs build and test commands inside an isolated Testcontainers Docker container with the project workspace bind-mounted.

## Prerequisites

### Required System Tools
RoxyCode checks for the following CLI tools on startup (and provides a **System Tools** status page in the UI):

- **Java 21+** (JDK 21 is required to build and run RoxyCode)
- **Ripgrep (`rg`)**: Fast text and file search
- **Universal Ctags (`ctags`)**: JSON-capable ctags binary for symbol outlines and repository map generation

### Optional Tools
- **Git (`git`)**: Version control inspection, branch status, and diff auditing (`gitService` gracefully disables itself if Git is not installed).
- **Bubblewrap (`bwrap`)** (Linux) or **Seatbelt (`sandbox-exec`)** (macOS): Used when native OS build sandboxing is enabled (`[sandbox] mode = "native"` in `build.toml`).
- **Docker**: Required only if you enable Docker container-isolated builds (`[sandbox] mode = "docker"` in `build.toml`).

### Quick Install by OS
- **macOS (Homebrew)**:
  ```bash
  brew install openjdk@21 ripgrep universal-ctags
  ```
- **Ubuntu / Debian**:
  ```bash
  sudo apt install openjdk-21-jdk ripgrep universal-ctags bubblewrap
  ```
- **Fedora / RHEL**:
  ```bash
  sudo dnf install java-21-openjdk-devel ripgrep ctags bubblewrap
  ```
- **Windows (Winget)**:
  ```bash
  winget install EclipseAdoptium.Temurin.21.JDK BurntSushi.ripgrep.MSVC UniversalCtags.Ctags
  ```

### API Credentials
- **Google Gemini API key** (Google AI Studio — configured in the application **Global Settings** panel or via the `GOOGLE_API_KEY` environment variable), **or**
- **Google Cloud Vertex AI** (configured in **Global Settings** with your GCP Project ID, Vertex Location such as `global` or `us-central1`, and either Application Default Credentials via `gcloud auth application-default login` or a Service Account JSON key file).

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
├── settings.toml                # Theme, agentProfile / Gemini model, API key / Vertex AI credentials, turn/memory limits, global context folders
├── system-tools.toml            # Cached CLI tool detection results
├── logs/
│   ├── roxy.log                 # Application log
│   └── toolcalls.jsonl          # Always-on structured JSONL log of JEXL tool executions
├── memory/
│   ├── MEMORY.md                # Pinned global memory cheat-sheet
│   └── notes/                   # Global topic notes (*.md)
└── projects/
    └── <project-name>-<hash>/
        ├── project.toml             # Per-project agentProfile / model, thinking level, and build confirmation state
        ├── build.toml               # Project build/test/lint/format commands and sandbox settings
        ├── context.toml             # Project-scoped context and reference folder paths
        ├── active-workflow.json     # Persisted active implementation plan and workflow phase state
        ├── active-chat-*.json       # Persisted active chat transcripts, memory, and token telemetry
        ├── tmp/build.log            # Latest build/test command output log for error extraction
        ├── memory/
        │   ├── MEMORY.md        # Pinned project memory cheat-sheet
        │   └── notes/           # Project topic notes (*.md)
        └── archive/
            ├── plans/           # Archived implementation plans (*.md)
            └── topics/          # Archived Codebase Q&A topics (*.md)
```

## Technology Stack

- **Runtime & Framework**: Java 21, Spring Boot 4.1, Spring AI 2.0 (Google GenAI / Gemini)
- **Desktop UI**: Swing, FlatLaf (Light & Dark themes), MigLayout, RSyntaxTextArea, Flexmark (Markdown rendering), PlantUML / Ditaa / jsvg (inline diagram & SVG rendering), Ikonli (Codicons icons)
- **Agent Sandbox & Caching**: Apache Commons JEXL 3, Caffeine, Jackson (JSON & TOML), Testcontainers

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
