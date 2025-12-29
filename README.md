# RoxyCode

Desktop application to interface with Gemini using the Java platform.

RoxyCode is a self-bootstrapping AI development environment designed to allow developers to build software in collaboration with LLMs. It focuses on a highly extensible architecture where the AI can utilize and modify its own toolset and knowledge base.

## Project Overview

RoxyCode is built as a Java-based desktop application using modern frameworks and a modular design. It is currently under heavy development and serves as an experimental platform for exploring LLM-based development workflows.

### Key Features

*   **Self-Bootstrapping Architecture:** RoxyCode loads tools and context dynamically from its configuration directory (`roxy_home`). This allows the AI to understand its own capabilities and environment.
*   **Dynamic Tooling:** The AI can invoke external tools (implemented as JavaScript scripts via GraalJS) to perform tasks such as file system operations, running tests, or managing git repositories.
*   **Context Awareness:** A dedicated context registry provides the LLM with documentation and project-specific knowledge.
*   **Modern Swing UI:** Built with the Sierra declarative UI engine and FlatLaf, providing a clean, responsive, and themeable interface.
*   **Interactive Chat:** A rich chat interface supporting Markdown, tool execution logs, and multi-turn conversations.
*   **Project & Git Integration:** Includes a built-in file explorer and displays real-time Git status.
*   **Usage & Cost Tracking:** Monitors API calls and token consumption with real-time cost estimation.
*   **Safe Execution:** All AI-driven tool executions are sandboxed within the project root.

## Project Structure

*   `src/main/java`: The core Java application source code.
*   `roxy_home/tools`: JavaScript scripts defining the AI's toolset.
*   `roxy_home/context`: Knowledge base and persona definitions for the LLM.
*   `docs/design`: Architecture documents and developer notes.
*   `pom.xml`: Maven configuration.

## Architecture & Tech Stack

*   **Language:** Java 17+
*   **Framework:** Micronaut (Dependency Injection & Application Lifecycle)
*   **UI Engine:** Sierra DSL (Swing-based declarative layout)
*   **Look & Feel:** FlatLaf
*   **AI Model:** Google Gemini
*   **Scripting:** JavaScript (via GraalJS for dynamic tool implementation)

## Getting Started

*(Instructions for setup and running the application would go here)*

## Developer Notes

This project was started as a way to explore LLM-based development within a Java ecosystem. It is not yet ready for production use.
