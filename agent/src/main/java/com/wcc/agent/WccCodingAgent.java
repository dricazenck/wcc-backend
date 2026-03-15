package com.wcc.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Autonomous GitHub coding agent for the WCC backend project.
 *
 * <p>Supports two git backends, selected via {@code --git=} flag:
 *
 * <ul>
 *   <li><b>shell</b> (default) — git operations run via {@code ProcessBuilder} using the system
 *       {@code git} binary. Simple, no extra dependencies.
 *   <li><b>mcp</b> — git operations are delegated to the {@code @modelcontextprotocol/server-git}
 *       MCP server over a JSON-RPC 2.0 stdio connection. Demonstrates the MCP client pattern in
 *       Java.
 * </ul>
 *
 * <p><b>Prerequisites:</b>
 *
 * <ul>
 *   <li>Set {@code ANTHROPIC_API_KEY} env var
 *   <li>{@code gh} CLI authenticated ({@code gh auth status})
 *   <li>MCP mode only: Node.js + npx on PATH ({@code node --version})
 * </ul>
 *
 * <p><b>Run:</b>
 *
 * <pre>
 * # Shell git backend (default)
 * ./gradlew :agent:run --args="Fix GitHub issue #42"
 *
 * # MCP git backend
 * ./gradlew :agent:run --args="--git=mcp Fix GitHub issue #42"
 * </pre>
 */
public class WccCodingAgent implements AutoCloseable {

  private static final String UPSTREAM_REPO = "Women-Coding-Community/wcc-backend";
  private static final String WORKSPACE =
      System.getProperty("user.home") + "/workspace/wcc-backend";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final AnthropicClient client;
  private final GitMode gitMode;
  private final McpGitClient mcpGitClient; // null when gitMode == SHELL
  private final List<Tool> tools;

  /**
   * Constructs the agent with the specified git backend.
   *
   * <p>API key resolution order:
   *
   * <ol>
   *   <li>{@code ANTHROPIC_API_KEY} environment variable
   *   <li>{@code anthropic.api.key} JVM system property ({@code -Danthropic.api.key=…})
   * </ol>
   *
   * @param gitMode {@link GitMode#SHELL} or {@link GitMode#MCP}
   * @throws Exception if MCP server fails to start (MCP mode only)
   */
  public WccCodingAgent(GitMode gitMode) throws Exception {
    String apiKey = System.getenv("ANTHROPIC_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      apiKey = System.getProperty("anthropic.api.key", "");
    }
    if (apiKey.isBlank()) {
      throw new IllegalStateException(
          "ANTHROPIC_API_KEY is not set. Export it or pass -Danthropic.api.key=sk-ant-…");
    }

    this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    this.gitMode = gitMode;

    if (gitMode == GitMode.MCP) {
      this.mcpGitClient = new McpGitClient(WORKSPACE);
    } else {
      this.mcpGitClient = null;
    }

    this.tools = buildTools();
  }

  /**
   * Entry point. Flags and task are parsed from command-line arguments.
   *
   * <p>Flags (optional, prefix with {@code --}):
   *
   * <ul>
   *   <li>{@code --git=shell} — use shell git backend (default)
   *   <li>{@code --git=mcp} — use MCP git server backend
   * </ul>
   *
   * <p>Examples:
   *
   * <pre>
   * # Default shell mode
   * ./gradlew :agent:run --args="List the open issues"
   *
   * # MCP git mode
   * ./gradlew :agent:run --args="--git=mcp Fix GitHub issue #42"
   *
   * # Passing API key inline (if not exported)
   * ANTHROPIC_API_KEY=sk-ant-... ./gradlew :agent:run --args="--git=mcp Describe open issues"
   * </pre>
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) throws Exception {
    List<String> argList = Arrays.asList(args);

    GitMode gitMode =
        argList.stream()
            .filter(a -> a.startsWith("--git="))
            .map(a -> a.substring("--git=".length()).toUpperCase())
            .map(GitMode::valueOf)
            .findFirst()
            .orElse(GitMode.SHELL);

    String task =
        argList.stream().filter(a -> !a.startsWith("--")).collect(Collectors.joining(" "));

    if (task.isBlank()) {
      task =
          "List the open GitHub issues and describe the three smallest ones with a suggested fix plan";
    }

    try (WccCodingAgent agent = new WccCodingAgent(gitMode)) {
      agent.run(task);
    }
  }

  @Override
  public void close() {
    if (mcpGitClient != null) mcpGitClient.close();
  }

  // ─────────────────────────────────────────────────────────────────
  // Agent loop
  // ─────────────────────────────────────────────────────────────────

  /**
   * Runs the agentic loop: send task to Claude → execute tool calls → repeat until end_turn.
   *
   * @param task natural-language task description
   */
  public void run(String task) {
    System.out.println("╔══════════════════════════════════════════════════╗");
    System.out.printf("║  WCC Coding Agent  [git: %-22s]  ║%n", gitMode.name().toLowerCase());
    System.out.println("╚══════════════════════════════════════════════════╝");
    System.out.println("Task: " + task + "\n");

    List<MessageParam> messages = new ArrayList<>();
    messages.add(MessageParam.builder().role(MessageParam.Role.USER).content(task).build());

    int iteration = 0;
    while (iteration++ < 20) {
      MessageCreateParams.Builder paramsBuilder =
          MessageCreateParams.builder()
              .model(Model.CLAUDE_OPUS_4_6)
              .maxTokens(4096L)
              .systemOfTextBlockParams(
                  List.of(TextBlockParam.builder().text(systemPrompt()).build()))
              .messages(messages);
      tools.forEach(paramsBuilder::addTool);

      Message response = client.messages().create(paramsBuilder.build());

      response.content().stream()
          .flatMap(b -> b.text().stream())
          .forEach(t -> System.out.println("\n[Claude] " + t.text()));

      if (response.stopReason().map(r -> r == StopReason.END_TURN).orElse(false)) {
        System.out.println("\n✅ Agent task complete.");
        break;
      }

      messages.add(toAssistantMessage(response));

      List<ContentBlockParam> toolResults =
          response.content().stream()
              .flatMap(b -> b.toolUse().stream())
              .map(this::executeTool)
              .collect(Collectors.toList());

      if (toolResults.isEmpty()) break;

      messages.add(
          MessageParam.builder()
              .role(MessageParam.Role.USER)
              .contentOfBlockParams(toolResults)
              .build());
    }
  }

  // ─────────────────────────────────────────────────────────────────
  // Tool dispatcher
  // ─────────────────────────────────────────────────────────────────

  /**
   * Dispatches a tool call to the appropriate backend (MCP or shell) and returns the result.
   *
   * <p>In MCP mode, any tool name owned by {@link McpGitClient} is forwarded to it. All other tools
   * (GitHub, file I/O, tests) always run via shell regardless of mode.
   *
   * @param toolUse tool_use block from Claude's response
   * @return tool_result block to feed back to Claude
   */
  private ContentBlockParam executeTool(ToolUseBlock toolUse) {
    System.out.println("\n🔧 [Tool/" + gitMode.name().toLowerCase() + "] " + toolUse.name());
    String result;
    try {
      // MCP mode: route git tools to the MCP server
      if (gitMode == GitMode.MCP && mcpGitClient.handles(toolUse.name())) {
        result = mcpGitClient.callTool(toolUse.name(), toolUse._input());
      } else {
        result = dispatchShellTool(toolUse);
      }
    } catch (Exception e) {
      result = "Error executing " + toolUse.name() + ": " + e.getMessage();
    }

    int preview = Math.min(result.length(), 300);
    System.out.println("   → " + result.substring(0, preview) + (result.length() > 300 ? "…" : ""));

    return ContentBlockParam.ofToolResult(
        ToolResultBlockParam.builder().toolUseId(toolUse.id()).content(result).build());
  }

  /**
   * Routes tool calls to their shell-based implementations. These tools are available in both SHELL
   * and MCP modes.
   *
   * @param toolUse tool_use block
   * @return tool result string
   */
  private String dispatchShellTool(ToolUseBlock toolUse) throws Exception {
    return switch (toolUse.name()) {
      // ── GitHub tools (always shell via gh CLI) ─────────────
      case "list_issues" -> listIssues();
      case "get_issue" -> getIssue(toolUse._input());
      case "create_pull_request" -> createPullRequest(toolUse._input());

      // ── File / build tools (always shell) ──────────────────
      case "read_file" -> readFile(toolUse._input());
      case "write_file" -> writeFile(toolUse._input());
      case "list_files" -> listFiles(toolUse._input());
      case "run_tests" -> runTests();

      // ── Shell git tools (only in SHELL mode) ───────────────
      case "create_branch" -> createBranch(toolUse._input());
      case "git_commit" -> gitCommit(toolUse._input());

      default -> "Unknown tool: " + toolUse.name();
    };
  }

  // ─────────────────────────────────────────────────────────────────
  // Tool implementations — GitHub (always shell)
  // ─────────────────────────────────────────────────────────────────

  /** Lists open issues on the upstream repo using the {@code gh} CLI. */
  private String listIssues() throws Exception {
    return shell(
        "gh",
        "issue",
        "list",
        "--repo",
        UPSTREAM_REPO,
        "--state",
        "open",
        "--json",
        "number,title,labels",
        "--limit",
        "20");
  }

  /** Gets full details of a single issue (number, title, body, comments). */
  private String getIssue(JsonValue input) throws Exception {
    String number = parseInput(input).get("number").toString();
    return shell(
        "gh",
        "issue",
        "view",
        number,
        "--repo",
        UPSTREAM_REPO,
        "--json",
        "number,title,body,labels,comments");
  }

  /** Pushes the current branch and opens a draft PR on the upstream repo. */
  private String createPullRequest(JsonValue input) throws Exception {
    Map<String, Object> params = parseInput(input);
    shell("git", "-C", WORKSPACE, "push", "-u", "origin", "HEAD");
    return shell(
        "gh",
        "pr",
        "create",
        "--repo",
        UPSTREAM_REPO,
        "--title",
        params.get("title").toString(),
        "--body",
        params.get("body").toString(),
        "--draft");
  }

  // ─────────────────────────────────────────────────────────────────
  // Tool implementations — File / build (always shell)
  // ─────────────────────────────────────────────────────────────────

  /** Reads a source file from the local checkout. */
  private String readFile(JsonValue input) throws Exception {
    return Files.readString(safeRepoPath(parseInput(input).get("path").toString()));
  }

  /** Writes (or overwrites) a source file in the local checkout. */
  private String writeFile(JsonValue input) throws Exception {
    Map<String, Object> params = parseInput(input);
    Path path = safeRepoPath(params.get("path").toString());
    Files.createDirectories(path.getParent());
    Files.writeString(path, params.get("content").toString());
    return "Written: " + params.get("path");
  }

  /** Recursively lists files in a directory (max depth 3). */
  private String listFiles(JsonValue input) throws Exception {
    Map<String, Object> params = parseInput(input);
    String dir =
        params.containsKey("directory") ? params.get("directory").toString() : "src/main/java";
    Path dirPath = Path.of(WORKSPACE, dir);
    return Files.walk(dirPath, 3)
        .filter(Files::isRegularFile)
        .map(p -> dirPath.relativize(p).toString())
        .collect(Collectors.joining("\n"));
  }

  /** Runs unit tests via Gradle and returns the output. */
  private String runTests() throws Exception {
    return shell(WORKSPACE + "/gradlew", "test", "--project-dir", WORKSPACE);
  }

  // ─────────────────────────────────────────────────────────────────
  // Tool implementations — Shell git (only active in SHELL mode)
  // ─────────────────────────────────────────────────────────────────

  /** Creates a new git branch from current HEAD. */
  private String createBranch(JsonValue input) throws Exception {
    return shell(
        "git", "-C", WORKSPACE, "checkout", "-b", parseInput(input).get("name").toString());
  }

  /** Stages all changes and creates a git commit. */
  private String gitCommit(JsonValue input) throws Exception {
    shell("git", "-C", WORKSPACE, "add", "-A");
    return shell(
        "git", "-C", WORKSPACE, "commit", "-m", parseInput(input).get("message").toString());
  }

  // ─────────────────────────────────────────────────────────────────
  // Tool definitions
  // ─────────────────────────────────────────────────────────────────

  /**
   * Builds the complete tool list for Claude based on the current git mode.
   *
   * <ul>
   *   <li>SHELL mode: includes custom {@code create_branch} and {@code git_commit} tools
   *   <li>MCP mode: replaces those with the full tool set from the MCP git server
   * </ul>
   *
   * @return combined list of Anthropic Tool definitions
   */
  private List<Tool> buildTools() {
    List<Tool> result = new ArrayList<>();

    // GitHub tools — always available
    result.add(
        tool("list_issues", "List open GitHub issues in the WCC backend repository", Map.of()));
    result.add(
        tool(
            "get_issue",
            "Get full details of a GitHub issue including body and comments",
            Map.of("number", prop("integer", "Issue number, e.g. 42"))));
    result.add(
        tool(
            "create_pull_request",
            "Push the current branch and open a draft pull request on the upstream repo",
            Map.of(
                "title", prop("string", "Short PR title under 70 characters"),
                "body", prop("string", "PR description with summary and test plan"))));

    // File / build tools — always available
    result.add(
        tool(
            "read_file",
            "Read the contents of a source file from the repository",
            Map.of(
                "path",
                prop(
                    "string",
                    "Path relative to repo root, e.g. src/main/java/com/wcc/platform/service/MemberService.java"))));
    result.add(
        tool(
            "write_file",
            "Create or overwrite a file in the repository with new content",
            Map.of(
                "path", prop("string", "Path relative to repo root"),
                "content", prop("string", "Full file content to write"))));
    result.add(
        tool(
            "list_files",
            "List files inside a directory (max 3 levels deep)",
            Map.of("directory", prop("string", "Directory path relative to repo root"))));
    result.add(tool("run_tests", "Run the Gradle unit tests and return the result", Map.of()));

    // Git tools — backend depends on mode
    if (gitMode == GitMode.MCP) {
      // Let the MCP server advertise its own git tools directly
      result.addAll(mcpGitClient.getTools());
    } else {
      result.add(
          tool(
              "create_branch",
              "Create a new git branch from current HEAD",
              Map.of("name", prop("string", "Branch name, e.g. fix/issue-42-member-not-found"))));
      result.add(
          tool(
              "git_commit",
              "Stage all changed files and create a git commit",
              Map.of(
                  "message",
                  prop(
                      "string",
                      "Conventional commit message, e.g. fix: handle empty member list"))));
    }

    return result;
  }

  private Tool tool(String name, String description, Map<String, Object> properties) {
    Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
    properties.forEach((k, v) -> propsBuilder.putAdditionalProperty(k, JsonValue.from(v)));
    return Tool.builder()
        .name(name)
        .description(description)
        .inputSchema(
            Tool.InputSchema.builder()
                .properties(propsBuilder.build())
                .required(new ArrayList<>(properties.keySet()))
                .build())
        .build();
  }

  private Map<String, String> prop(String type, String description) {
    return Map.of("type", type, "description", description);
  }

  // ─────────────────────────────────────────────────────────────────
  // System prompt
  // ─────────────────────────────────────────────────────────────────

  private String systemPrompt() {
    String gitSection =
        switch (gitMode) {
          case SHELL ->
              """
                    Git tools available: create_branch, git_commit.
                    Use create_branch before committing. Use git_commit to stage + commit all changes.
                    """;
          case MCP ->
              """
                    Git tools come from the @modelcontextprotocol/server-git MCP server.
                    Available tools include: git_status, git_add, git_create_branch, git_checkout,
                    git_commit, git_diff, git_log, and others.
                    Always pass repo_path = "%s" to every git tool call.
                    Workflow: git_create_branch → write_file → git_add → git_commit.
                    """
                  .formatted(WORKSPACE);
        };

    return """
                You are an autonomous coding agent for the WCC (Women Coding Community) backend platform.

                Repository: %s (upstream) | Local checkout: %s
                Stack: Spring Boot 3.2.5, Java 21, Gradle, PostgreSQL, Flyway, Lombok, JUnit 5

                %s
                After making code changes:
                1. Run run_tests to verify nothing is broken
                2. Create a branch and commit using the available git tools
                3. Open a draft PR with create_pull_request

                Code conventions (must follow):
                - Lombok (@Data, @Builder, @RequiredArgsConstructor) for domain classes
                - Java records for DTOs and response objects
                - JUnit 5 tests with @DisplayName("Given…, when…, then…")
                - AssertJ assertions (assertThat)
                - Javadoc on all public classes and methods
                - No AI attribution in commit messages
                """
        .formatted(UPSTREAM_REPO, WORKSPACE, gitSection);
  }

  // ─────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────

  /**
   * Runs a shell command in the workspace directory and returns combined stdout+stderr.
   *
   * @param args command and arguments
   * @return command output, or "(no output)" if blank
   */
  private String shell(String... args) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(args);
    pb.directory(new File(WORKSPACE));
    pb.redirectErrorStream(true);
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes());
    process.waitFor();
    return output.isBlank() ? "(no output)" : output;
  }

  /**
   * Parses a {@link JsonValue} tool input into a typed Map.
   *
   * @param input raw JsonValue from a tool_use block
   * @return parsed map of parameter values
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> parseInput(JsonValue input) throws Exception {
    return MAPPER.readValue(input.toString(), Map.class);
  }

  /**
   * Resolves a repo-relative path to an absolute path, preventing directory traversal.
   *
   * @param relativePath path relative to workspace root
   * @return safe absolute Path inside the workspace
   * @throws SecurityException if the resolved path escapes the workspace
   */
  private Path safeRepoPath(String relativePath) {
    Path root = Path.of(WORKSPACE).toAbsolutePath().normalize();
    Path resolved = root.resolve(relativePath).normalize();
    if (!resolved.startsWith(root)) {
      throw new SecurityException("Path escapes workspace: " + relativePath);
    }
    return resolved;
  }

  /**
   * Converts a Message response into an assistant MessageParam for conversation history. Both text
   * and tool_use blocks must be echoed back — the API requires it.
   *
   * @param response Claude's response message
   * @return assistant MessageParam ready to append to history
   */
  private MessageParam toAssistantMessage(Message response) {
    List<ContentBlockParam> blocks =
        response.content().stream()
            .<ContentBlockParam>mapMulti(
                (block, consumer) -> {
                  block
                      .text()
                      .ifPresent(
                          t ->
                              consumer.accept(
                                  ContentBlockParam.ofText(
                                      TextBlockParam.builder().text(t.text()).build())));
                  block
                      .toolUse()
                      .ifPresent(tu -> consumer.accept(ContentBlockParam.ofToolUse(tu.toParam())));
                })
            .collect(Collectors.toList());

    return MessageParam.builder()
        .role(MessageParam.Role.ASSISTANT)
        .contentOfBlockParams(blocks)
        .build();
  }

  // ─────────────────────────────────────────────────────────────────
  // Entry point
  // ─────────────────────────────────────────────────────────────────

  /** Selects the git backend strategy. */
  public enum GitMode {
    SHELL,
    MCP
  }
}
