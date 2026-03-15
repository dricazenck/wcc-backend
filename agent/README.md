# WCC Autonomous Coding Agent

A Java-based autonomous coding agent that uses **Claude Opus 4.6** to read GitHub issues,
explore the codebase, write fixes, run tests, and open pull requests — with two pluggable
git backends.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     WccCodingAgent                          │
│                                                             │
│  run(task)                                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                  Agent Loop                         │   │
│  │  1. Call Claude Opus 4.6  (tools attached)          │   │
│  │  2. Print Claude's text response                    │   │
│  │  3. For each tool_use block → executeTool()         │   │
│  │  4. Send all tool_result blocks back to Claude      │   │
│  │  5. Repeat until stop_reason == end_turn            │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  Tool dispatch (--git=shell vs --git=mcp)                  │
│  ┌──────────────────────┐  ┌──────────────────────────┐   │
│  │   Always shell        │  │  Git backend (switchable) │   │
│  │  • list_issues        │  │                           │   │
│  │  • get_issue          │  │  SHELL: ProcessBuilder    │   │
│  │  • read_file          │  │    create_branch          │   │
│  │  • write_file         │  │    git_commit             │   │
│  │  • list_files         │  │                           │   │
│  │  • run_tests          │  │  MCP: McpGitClient        │   │
│  │  • create_pull_request│  │    git_status             │   │
│  └──────────────────────┘  │    git_add                │   │
│                             │    git_create_branch      │   │
│                             │    git_checkout           │   │
│                             │    git_commit             │   │
│                             │    git_diff / git_log     │   │
│                             │    … (full MCP tool list) │   │
│                             └──────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### Git backends

| Mode | How it works | Requires |
|------|-------------|---------|
| `--git=shell` (default) | Runs `git` via `ProcessBuilder` | `git` on PATH |
| `--git=mcp` | Connects to `@modelcontextprotocol/server-git` over JSON-RPC 2.0 stdio | Node.js + npx |

In **MCP mode**, `McpGitClient` starts the npm MCP server as a subprocess, performs the
MCP handshake (`initialize` / `notifications/initialized`), fetches the server's tool
definitions, and converts them to Anthropic `Tool` objects. The agent feeds those tools
directly to Claude — Claude calls them by name, and the agent forwards each call over the
stdio pipe using JSON-RPC 2.0.

---

## Prerequisites

| Requirement | Check |
|-------------|-------|
| JDK 21+ | `java --version` |
| `ANTHROPIC_API_KEY` set | `echo $ANTHROPIC_API_KEY` |
| `gh` CLI authenticated | `gh auth status` |
| Node.js + npx *(MCP mode only)* | `node --version && npx --version` |

---

## Running

```bash
# Default: shell git backend, demo task
./gradlew :agent:run

# Custom task, shell git backend
./gradlew :agent:run --args="Fix GitHub issue #42"

# MCP git backend
./gradlew :agent:run --args="--git=mcp Fix GitHub issue #42"

# Explore issues without making changes
./gradlew :agent:run --args="List open issues and suggest which is easiest to fix"
```

### Passing the API key

If `ANTHROPIC_API_KEY` is not in your environment (e.g. Gradle daemon started first):

```bash
# Inline (most reliable)
ANTHROPIC_API_KEY=sk-ant-... ./gradlew :agent:run --args="..."

# Via JVM system property
./gradlew :agent:run --args="..." -Danthropic.api.key=sk-ant-...

# Stop daemon, export, restart
./gradlew --stop
export ANTHROPIC_API_KEY=sk-ant-...
./gradlew :agent:run --args="..."
```

---

## Project structure

```
agent/
├── build.gradle.kts                        # Gradle config (anthropic-java + jackson)
├── README.md                               # This file
├── RUNBOOK.md                              # Operational guide
└── src/main/java/com/wcc/agent/
    ├── WccCodingAgent.java                 # Main agent: loop + tool dispatch
    └── McpGitClient.java                   # MCP stdio client (raw JSON-RPC 2.0)
```

---

## Key classes

### `WccCodingAgent`

- Parses `--git=mcp|shell` from args
- Builds the tool list (shared tools + git-mode-specific tools)
- Runs the agent loop: `messages → Claude → tool_use → tool_result → repeat`
- Routes git tool calls to either `dispatchShellTool()` or `mcpGitClient.callTool()`

### `McpGitClient`

- Starts `npx -y @modelcontextprotocol/server-git <repo>` as a subprocess
- Implements MCP's JSON-RPC 2.0 wire protocol over stdin/stdout
- Converts MCP tool schemas → Anthropic `Tool` objects for Claude
- Exposes `handles(name)` and `callTool(name, input)` for the dispatcher
