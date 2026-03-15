# Agent Runbook

Operational guide for running and troubleshooting the WCC autonomous coding agent.

---

## Quick start

```bash
# 1. Make sure prerequisites are in place
echo $ANTHROPIC_API_KEY          # must be set
gh auth status                   # gh CLI must be authenticated
java --version                   # must be 21+

# 2. Run with a task
./gradlew :agent:run --args="List the open GitHub issues and describe the three smallest ones"
```

---

## Running modes

### Shell mode (default)

Uses `git` via `ProcessBuilder`. Recommended for simplicity.

```bash
./gradlew :agent:run --args="Fix issue #42"
```

Git tools available to Claude: `create_branch`, `git_commit`.

### MCP mode

Delegates all git operations to `@modelcontextprotocol/server-git` over JSON-RPC 2.0 stdio.
Claude gets the full git tool set from the MCP server.

```bash
./gradlew :agent:run --args="--git=mcp Fix issue #42"
```

Additional git tools available in MCP mode: `git_status`, `git_add`, `git_create_branch`,
`git_checkout`, `git_commit`, `git_diff`, `git_diff_staged`, `git_log`, `git_show`, and more.

**First run note:** npx will download `@modelcontextprotocol/server-git` on first use (~2s).
Subsequent runs use the npx cache.

---

## Common tasks

### Explore open issues (read-only, safe to run)

```bash
./gradlew :agent:run --args="List the open GitHub issues. For each, describe what needs to change and estimate complexity (S/M/L)."
```

### Fix a specific issue

```bash
./gradlew :agent:run --args="Fix GitHub issue #42. Read the issue, find the relevant code, make the fix, run tests, create a branch and open a draft PR."
```

### Review + plan only (no code changes)

```bash
./gradlew :agent:run --args="Look at issue #42 and read the relevant files. Do NOT write any files. Just describe your fix plan."
```

### Debug a test failure

```bash
./gradlew :agent:run --args="The test MentorshipServiceRetrievalTest is failing. Read the test and the relevant service code, then fix the issue."
```

---

## Troubleshooting

### `UnauthorizedException: x-api-key header is required`

The Gradle daemon doesn't have `ANTHROPIC_API_KEY`. Fix:
```bash
ANTHROPIC_API_KEY=sk-ant-... ./gradlew :agent:run --args="..."
# or
./gradlew --stop && export ANTHROPIC_API_KEY=sk-ant-... && ./gradlew :agent:run --args="..."
```

### `IllegalStateException: ANTHROPIC_API_KEY is not set`

Same as above — the key wasn't found in env or system properties.

### MCP mode: `npx: command not found`

Install Node.js: https://nodejs.org (includes npx). Verify with `npx --version`.

### MCP mode: agent hangs after "Starting @modelcontextprotocol/server-git"

The MCP server failed to start. Run manually to see the error:
```bash
npx -y @modelcontextprotocol/server-git ~/workspace/wcc-backend
```
Common causes: wrong Node version, npm registry timeout, corrupted npx cache.

Clear the npx cache and retry:
```bash
npx clear-npx-cache
./gradlew :agent:run --args="--git=mcp ..."
```

### MCP mode: `MCP error: ...`

The git server returned a tool error. Common causes:
- `repo_path` not passed to a git tool (system prompt instructs Claude to include it)
- Trying to commit with no staged changes
- Branch already exists

Check the `[Tool/mcp]` output lines for the raw error message.

### Agent loops without finishing (hits 20-iteration cap)

The task is too large or Claude keeps calling tools. Options:
- Break the task into smaller steps
- Add "Do NOT run tests" or "Do NOT create a PR" to reduce scope
- Check if a tool is returning an error that Claude is retrying

### `gh: not found` / `gh auth status` shows unauthenticated

Authenticate gh CLI:
```bash
gh auth login
gh auth status    # verify
```

---

## Safety notes

- The agent runs `git add -A` before committing in shell mode — all local changes will be staged
- PRs are created as **drafts** — no auto-merge happens
- The `write_file` tool has a path traversal guard — it rejects paths outside the workspace
- The agent caps at **20 tool-call rounds** to prevent runaway loops

---

## Extending the agent

### Add a new tool (shell)

1. Add a `tool(...)` entry in `buildTools()` inside `WccCodingAgent`
2. Add the implementation method
3. Add a `case "your_tool" -> yourMethod(toolUse._input());` in `dispatchShellTool()`

### Switch to a different MCP server

Replace the server command in `McpGitClient`:
```java
// Example: use a custom MCP server instead of the npm one
new ProcessBuilder("my-mcp-server", "--repo", repoPath)
```

The rest of the client (handshake, tool fetching, tool calling) works with any
MCP-compliant stdio server.

### Add a second MCP server

1. Instantiate a second `McpGitClient`-style client alongside the existing one
2. Add its tools to the `buildTools()` list
3. Route calls to it in `executeTool()` using `handles()`

---

## MCP protocol reference (what McpGitClient sends)

```
Client → Server (initialize)
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","clientInfo":{"name":"wcc-agent","version":"1.0.0"},"capabilities":{}}}

Server → Client (initialize response)
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","serverInfo":{...},"capabilities":{...}}}

Client → Server (initialized notification, no response)
{"jsonrpc":"2.0","method":"notifications/initialized"}

Client → Server (list tools)
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}

Server → Client (tools list)
{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"git_status","description":"...","inputSchema":{...}}]}}

Client → Server (call tool)
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"git_status","arguments":{"repo_path":"/path/to/repo"}}}

Server → Client (tool result)
{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"On branch main..."}]}}
```
