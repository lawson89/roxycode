# RoxyCode

Desktop application to interface with Gemini using the Java platform.

RoxyCode is a self-bootstrapping AI development environment designed to allow developers to build software in collaboration with LLMs. It focuses on a highly extensible architecture where the AI can utilize and modify its own toolset and knowledge base.

## Project Overview

RoxyCode is built as a Java-based desktop application using modern frameworks and a modular design. It is currently under heavy development and serves as an experimental platform for exploring LLM-based development workflows.

### Key Features

*   **Self-Bootstrapping Architecture:** RoxyCode loads tools and context dynamically from its configuration directory (`roxy_home`). This allows the AI to understand its own capabilities and environment.
*   **Dynamic Tooling (GraalJS):** The AI can invoke external tools implemented as JavaScript scripts. These tools have access to a rich set of Java services for file system operations, git management, building, testing, and more.
*   **Advanced Java Analysis:** Deep integration with JavaParser allows the AI to analyze class dependencies, retrieve method/field source code, and generate code skeletons for large projects.
*   **Intelligent Context Management:** Supports Gemini Context Caching to handle large codebases efficiently. Features like "Push to Cache" and dedicated cache views help manage LLM context.
*   **Multi-format Document Support:** Integrated Apache Tika for extracting text and metadata from various file formats (PDF, DOCX, etc.).
*   **Structured Data Tools:** Dedicated services for analyzing and modifying XML and TOML files using XPath and structured nodes.
*   **Modern Swing UI:** Built with the Sierra declarative UI engine and FlatLaf, providing a clean, responsive, and themeable interface with Material Design icons.
*   **Rich Developer Experience:** 
    *   Interactive chat with Markdown and HTML rendering.
    *   Slash commands with autocomplete.
    *   Real-time system logs and codebase cache views.
    *   Tool execution logs and status updates.
    *   Message history with persistence and navigation.
*   **UI Preview & Automated Testing:** Built-in support for validating and previewing Sierra DSL files, and automated application launch/screenshotting for UI verification.
*   **Safe Execution:** All AI-driven tool executions are sandboxed within the project root.

## Modes of Operation

RoxyCode operates in one of several distinct modes to ensure a structured and safe development process:

*   **Discover:** RoxyCode answers questions about the current project to help understand the codebase.
*   **Plan:** RoxyCode asks clarifying questions and develops a detailed plan, which is stored in the `roxy_project/plans` folder. No code changes are made in this mode.
*   **Implement:** Once a plan is approved, RoxyCode uses its full suite of tools to implement the feature. Code changes are only permitted in this mode.

## Project Structure

*   `src/main/java`: The core Java application source code.
*   `roxy_home/tools`: JavaScript scripts defining the AI's toolset.
*   `roxy_home/context`: Knowledge base and persona definitions for the LLM.
*   `roxy_project`: Project-specific data, including plans and local caches.
*   `docs/design`: Architecture documents and developer notes.
*   `pom.xml`: Maven configuration.

## Architecture & Tech Stack

*   **Language:** Java 21
*   **Framework:** Micronaut (Dependency Injection & Application Lifecycle)
*   **UI Engine:** Sierra DSL (Swing-based declarative layout)
*   **Look & Feel:** FlatLaf with Material Design icons (Ikonli)
*   **AI Model:** Google Gemini (Generative AI SDK)
*   **Scripting:** JavaScript (via GraalJS for dynamic tool implementation)
*   **Parsing & Analysis:** JavaParser, Apache Tika, Jackson (TOML/JSON)

## Getting Started

1.  Ensure you have JDK 21 installed.
2.  Set your `GEMINI_API_KEY` in a `.env` file in the project root.
3.  Run the application using Maven:
    ```bash
    ./mvnw exec:java
    ```

## Developer Notes

This project was started as a way to explore LLM-based development within a Java ecosystem. It is self-bootstrapping, meaning the AI can and does help build itself!
