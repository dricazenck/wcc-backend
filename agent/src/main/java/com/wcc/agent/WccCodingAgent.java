package com.wcc.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Autonomous GitHub coding agent for the WCC backend project.
 *
 * <p>Demonstrates a real MCP-style agentic loop in Java using the Anthropic Java SDK with:
 * <ul>
 *   <li>Claude Opus 4.6 as the reasoning engine</li>
 *   <li>GitHub tools: list/get issues, create PRs (via {@code gh} CLI)</li>
 *   <li>Code tools: read/write files, list directory</li>
 *   <li>Build tools: run Gradle tests</li>
 *   <li>Git tools: branch, commit</li>
 * </ul>
 *
 * <p><b>Prerequisites:</b> Set {@code ANTHROPIC_API_KEY} env var; have {@code gh} CLI authenticated.
 *
 * <p><b>Run:</b> {@code ./gradlew :agent:run --args="Fix GitHub issue #42"}
 */
public class WccCodingAgent {

    private static final String UPSTREAM_REPO = "Women-Coding-Community/wcc-backend";
    private static final String WORKSPACE = System.getProperty("user.home") + "/workspace/wcc-backend";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AnthropicClient client;
    private final List<Tool> tools;

    /**
     * Constructs the agent. Reads the API key from (in order of precedence):
     * <ol>
     *   <li>{@code ANTHROPIC_API_KEY} environment variable</li>
     *   <li>{@code anthropic.api.key} JVM system property (pass via {@code -Danthropic.api.key=…})</li>
     * </ol>
     */
    public WccCodingAgent() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getProperty("anthropic.api.key", "");
        }
        if (apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is not set. Export it in your shell or pass -Danthropic.api.key=sk-ant-…");
        }
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        this.tools = buildTools();
    }

    // ─────────────────────────────────────────────────────────────────
    // Agent loop
    // ─────────────────────────────────────────────────────────────────

    /**
     * Runs the agent with the given task until Claude signals {@code end_turn}.
     *
     * @param task natural-language task description
     */
    public void run(String task) {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║         WCC Autonomous Coding Agent              ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println("Task: " + task + "\n");

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(task)
                .build());

        int iteration = 0;
        while (iteration++ < 20) { // Safety cap: max 20 tool-call rounds
            MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                    .model(Model.CLAUDE_OPUS_4_6)
                    .maxTokens(4096L)
                    .systemOfTextBlockParams(List.of(
                            TextBlockParam.builder().text(systemPrompt()).build()))
                    .messages(messages);
            tools.forEach(paramsBuilder::addTool);

            Message response = client.messages().create(paramsBuilder.build());

            // Print any text Claude produced this turn
            response.content().stream()
                    .flatMap(b -> b.text().stream())
                    .forEach(t -> System.out.println("\n[Claude] " + t.text()));

            // Done?
            if (response.stopReason().map(r -> r == StopReason.END_TURN).orElse(false)) {
                System.out.println("\n✅ Agent task complete.");
                break;
            }

            // Append assistant turn to history (preserving both text and tool_use blocks)
            messages.add(toAssistantMessage(response));

            // Execute all requested tools and collect results
            List<ContentBlockParam> toolResults = response.content().stream()
                    .flatMap(b -> b.toolUse().stream())
                    .map(this::executeTool)
                    .collect(Collectors.toList());

            if (toolResults.isEmpty()) break;

            // Feed all tool results back as a single user message
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(toolResults)
                    .build());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Tool dispatcher
    // ─────────────────────────────────────────────────────────────────

    /**
     * Executes a single tool call and returns the result as a ContentBlockParam.
     *
     * @param toolUse the tool_use block from Claude's response
     * @return a tool_result block to send back to Claude
     */
    private ContentBlockParam executeTool(ToolUseBlock toolUse) {
        System.out.println("\n🔧 [Tool] " + toolUse.name());
        String result;
        try {
            result = switch (toolUse.name()) {
                case "list_issues"         -> listIssues();
                case "get_issue"           -> getIssue(toolUse._input());
                case "read_file"           -> readFile(toolUse._input());
                case "write_file"          -> writeFile(toolUse._input());
                case "list_files"          -> listFiles(toolUse._input());
                case "run_tests"           -> runTests();
                case "create_branch"       -> createBranch(toolUse._input());
                case "git_commit"          -> gitCommit(toolUse._input());
                case "create_pull_request" -> createPullRequest(toolUse._input());
                default                    -> "Unknown tool: " + toolUse.name();
            };
        } catch (Exception e) {
            result = "Error executing " + toolUse.name() + ": " + e.getMessage();
        }

        int preview = Math.min(result.length(), 300);
        System.out.println("   → " + result.substring(0, preview) + (result.length() > 300 ? "…" : ""));

        return ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder()
                        .toolUseId(toolUse.id())
                        .content(result)
                        .build()
        );
    }

    // ─────────────────────────────────────────────────────────────────
    // Tool implementations
    // ─────────────────────────────────────────────────────────────────

    /** Lists open issues on the upstream repo using the {@code gh} CLI. */
    private String listIssues() throws Exception {
        return shell("gh", "issue", "list",
                "--repo", UPSTREAM_REPO,
                "--state", "open",
                "--json", "number,title,labels",
                "--limit", "20");
    }

    /** Gets full details of a single issue (number, title, body, comments). */
    private String getIssue(JsonValue input) throws Exception {
        Map<?, ?> params = parseInput(input);
        String number = params.get("number").toString();
        return shell("gh", "issue", "view", number,
                "--repo", UPSTREAM_REPO,
                "--json", "number,title,body,labels,comments");
    }

    /** Reads a source file from the local checkout. */
    private String readFile(JsonValue input) throws Exception {
        Map<?, ?> params = parseInput(input);
        Path path = safeRepoPath(params.get("path").toString());
        return Files.readString(path);
    }

    /** Writes (or overwrites) a source file in the local checkout. */
    private String writeFile(JsonValue input) throws Exception {
        Map<?, ?> params = parseInput(input);
        Path path = safeRepoPath(params.get("path").toString());
        Files.createDirectories(path.getParent());
        Files.writeString(path, params.get("content").toString());
        return "Written: " + params.get("path");
    }

    /** Recursively lists files in a directory (max depth 3). */
    private String listFiles(JsonValue input) throws Exception {
        Map<?, ?> params = parseInput(input);
        String dir = params.containsKey("directory") ? params.get("directory").toString() : "src/main/java";
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

    /** Creates a new git branch from current HEAD. */
    private String createBranch(JsonValue input) throws Exception {
        Map<?, ?> params = parseInput(input);
        return shell("git", "-C", WORKSPACE, "checkout", "-b", params.get("name").toString());
    }

    /** Stages all changes and creates a git commit. */
    private String gitCommit(JsonValue input) throws Exception {
        Map<?, ?> params = parseInput(input);
        shell("git", "-C", WORKSPACE, "add", "-A");
        return shell("git", "-C", WORKSPACE, "commit", "-m", params.get("message").toString());
    }

    /** Pushes the current branch and opens a draft PR on the upstream repo. */
    private String createPullRequest(JsonValue input) throws Exception {
        Map<?, ?> params = parseInput(input);
        shell("git", "-C", WORKSPACE, "push", "-u", "origin", "HEAD");
        return shell("gh", "pr", "create",
                "--repo", UPSTREAM_REPO,
                "--title", params.get("title").toString(),
                "--body", params.get("body").toString(),
                "--draft");
    }

    // ─────────────────────────────────────────────────────────────────
    // Tool definitions
    // ─────────────────────────────────────────────────────────────────

    /**
     * Builds the list of tools Claude can use, each described with a JSON schema.
     *
     * @return list of Tool definitions
     */
    private List<Tool> buildTools() {
        return List.of(
                tool("list_issues",
                        "List open GitHub issues in the WCC backend repository",
                        Map.of()),

                tool("get_issue",
                        "Get full details of a GitHub issue including body and comments",
                        Map.of("number", prop("integer", "Issue number, e.g. 42"))),

                tool("read_file",
                        "Read the contents of a source file from the repository",
                        Map.of("path", prop("string",
                                "Path relative to repo root, e.g. src/main/java/com/wcc/platform/service/MemberService.java"))),

                tool("write_file",
                        "Create or overwrite a file in the repository with new content",
                        Map.of(
                                "path", prop("string", "Path relative to repo root"),
                                "content", prop("string", "Full file content to write"))),

                tool("list_files",
                        "List files inside a directory (max 3 levels deep)",
                        Map.of("directory", prop("string",
                                "Directory path relative to repo root, e.g. src/main/java/com/wcc/platform/service"))),

                tool("run_tests",
                        "Run the Gradle unit tests and return the result",
                        Map.of()),

                tool("create_branch",
                        "Create a new git branch from current HEAD",
                        Map.of("name", prop("string", "Branch name, e.g. fix/issue-42-member-not-found"))),

                tool("git_commit",
                        "Stage all changed files and create a git commit",
                        Map.of("message", prop("string",
                                "Conventional commit message, e.g. fix: handle empty member list"))),

                tool("create_pull_request",
                        "Push the current branch and open a draft pull request on the upstream repo",
                        Map.of(
                                "title", prop("string", "Short PR title under 70 characters"),
                                "body", prop("string", "PR description with summary and test plan")))
        );
    }

    /**
     * Builds a Tool with a JSON schema from a simple properties map.
     *
     * @param name        tool name
     * @param description tool description Claude uses to decide when to call it
     * @param properties  map of parameter name → {type, description}
     * @return configured Tool
     */
    private Tool tool(String name, String description, Map<String, Object> properties) {
        Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
        properties.forEach((k, v) -> propsBuilder.putAdditionalProperty(k, JsonValue.from(v)));

        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(propsBuilder.build())
                        .required(new ArrayList<>(properties.keySet()))
                        .build())
                .build();
    }

    private Map<String, String> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    /**
     * Runs a shell command in the workspace directory and returns combined stdout+stderr.
     *
     * @param args command and arguments
     * @return command output
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
     * Parses a {@link JsonValue} tool input into a plain Map.
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
     * @return absolute Path inside the workspace
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
     * Converts a Message response to an assistant MessageParam suitable for conversation history.
     * Preserves both text and tool_use blocks — both must be echoed back to the API.
     *
     * @param response Claude's response message
     * @return assistant MessageParam
     */
    private MessageParam toAssistantMessage(Message response) {
        List<ContentBlockParam> blocks = response.content().stream()
                .<ContentBlockParam>mapMulti((block, consumer) -> {
                    block.text().ifPresent(t ->
                            consumer.accept(ContentBlockParam.ofText(
                                    TextBlockParam.builder().text(t.text()).build())));
                    block.toolUse().ifPresent(tu ->
                            consumer.accept(ContentBlockParam.ofToolUse(tu.toParam())));
                })
                .collect(Collectors.toList());

        return MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(blocks)
                .build();
    }

    private String systemPrompt() {
        return """
                You are an autonomous coding agent for the WCC (Women Coding Community) backend platform.

                Repository: %s (upstream) | Local checkout: %s
                Stack: Spring Boot 3.2.5, Java 21, Gradle, PostgreSQL, Flyway, Lombok, JUnit 5

                Workflow for fixing a GitHub issue:
                1. Use list_issues or get_issue to understand the requirement
                2. Use list_files / read_file to explore the relevant code
                3. Make the smallest correct change using write_file
                4. Run tests with run_tests to verify nothing is broken
                5. Create a branch with create_branch (name: fix/issue-<N>-short-description)
                6. Commit with git_commit using conventional commit format (e.g. "fix: ...")
                7. Open a draft PR with create_pull_request

                Code conventions (must follow):
                - Lombok (@Data, @Builder, @RequiredArgsConstructor) for domain classes
                - Java records for DTOs and response objects
                - JUnit 5 tests with @DisplayName("Given…, when…, then…")
                - AssertJ assertions (assertThat)
                - Javadoc on all public classes and methods
                - No AI attribution in commit messages
                """.formatted(UPSTREAM_REPO, WORKSPACE);
    }

    // ─────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────

    /**
     * Entry point. Pass a task as command-line argument, or use the default demo task.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code ./gradlew :agent:run --args="List the open GitHub issues and describe the three smallest ones"}</li>
     *   <li>{@code ./gradlew :agent:run --args="Fix GitHub issue #42"}</li>
     * </ul>
     *
     * @param args optional task description
     */
    public static void main(String[] args) {
        String task = args.length > 0
                ? String.join(" ", args)
                : "List the open GitHub issues and describe the three smallest ones with a suggested fix plan";
        new WccCodingAgent().run(task);
    }
}
