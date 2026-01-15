# AI Advent Challenge - MCP Server 🚀

This repository implements a Model Context Protocol (MCP) server for the AI Advent Challenge.
It acts as a bridge between LLMs (like YandexGPT) and project tools (GitHub, Documentation/RAG, CRM).

## 🔥 Features

- **AI Code Reviewer**: Analyzes Pull Requests for style violations using RAG.
- **AI Support Agent**: Chatbot with access to CRM data (user history) and documentation.
- **AI Team Manager**: An autonomous agent that can manage the project (check status, create tasks).
- **RAG System**: Uses Yandex Embeddings to index and search project documentation.

## 🛠️ Usage

### Prerequisites
1. **Yandex Cloud API Key**: Set `YANDEX_API_KEY` and `YANDEX_FOLDER_ID` in `local.properties` or env vars.
2. **GitHub Token**: Set `GITHUB_TOKEN` (needs repo read/write permissions).

### Commands

**1. Index Documentation (Run once)**
Builds the vector index from `docs/` and source code.
```bash
./gradlew run --args="index"
```

**2. Run AI Code Review**
Analyzes the latest PR or a specific one.
```bash
./gradlew run --args="review"
# OR
./gradlew run --args="review_pr https://github.com/Owner/Repo/pull/1"
```

**3. Run Support Chat**
Simulates a support session with a specific user context.
```bash
./gradlew run --args="support user_123"
```

**4. Run Project Manager Agent** 🆕
Starts an interactive session where you can manage the project using natural language.
- Ask for project status ("What are the open tasks?")
- Ask technical questions ("What is the logging policy?") - *Uses RAG*
- Create tasks ("Create a high priority bugfix task for login")
```bash
./gradlew run --args="manage"
```

## 🏗️ Architecture

- **Main.kt**: Entry point, initializes clients and routes commands.
- **MCP Servers**:
    - `DeveloperAssistantMCPServer`: Tools for docs (RAG) and git diffs.
    - `SupportMCPServer`: Tools for CRM data access.
    - `ManageMCPServer`: Tools for GitHub project management (Issues, PRs).
- **AI Client**: `YandexGPTClient` for chat and tool execution.

## 📜 Rules & Policies
See `docs/Documentation.md` for coding standards enforced by the AI Reviewer.
