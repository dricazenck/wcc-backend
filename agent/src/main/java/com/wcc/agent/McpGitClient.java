package com.wcc.agent;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal MCP (Model Context Protocol) client that connects to a stdio MCP server.
 *
 * <p>Implements the JSON-RPC 2.0 wire protocol over stdin/stdout. This is intentionally
 * lightweight — no SDK dependency needed — and makes the MCP protocol visible for learning.
 *
 * <p>Connects to {@code @modelcontextprotocol/server-git} which provides git tools:
 * {@code git_status}, {@code git_add}, {@code git_commit}, {@code git_create_branch},
 * {@code git_checkout}, {@code git_log}, {@code git_diff}, {@code git_diff_staged}, etc.
 *
 * <p><b>Prerequisite:</b> Node.js and npx must be available on the PATH.
 *
 * <pre>
 * McpGitClient git = new McpGitClient("/path/to/repo");
 * List&lt;Tool&gt; tools = git.getTools();     // pass to Claude
 * String result = git.callTool("git_status", input);
 * git.close();
 * </pre>
 */
public class McpGitClient implements AutoCloseable {

    /** The MCP server name as advertised to Claude via the git MCP npm package. */
    public static final String SERVER_PACKAGE = "@modelcontextprotocol/server-git";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger REQUEST_ID = new AtomicInteger(1);
    private final Process serverProcess;
    private final BufferedWriter stdin;
    private final BufferedReader stdout;
    private final List<Tool> tools;
    private final Set<String> toolNames;

    /**
     * Starts the git MCP server for the given repository and completes the MCP handshake.
     *
     * @param repoPath absolute path to the git repository
     * @throws Exception if the server process cannot be started or the handshake fails
     */
    public McpGitClient(String repoPath) throws Exception {
        System.out.println("[MCP] Starting " + SERVER_PACKAGE + " for " + repoPath);

        this.serverProcess = new ProcessBuilder("npx", "-y", SERVER_PACKAGE, repoPath)
                .redirectError(ProcessBuilder.Redirect.DISCARD) // suppress npx download noise
                .start();

        this.stdin  = new BufferedWriter(new OutputStreamWriter(serverProcess.getOutputStream()));
        this.stdout = new BufferedReader(new InputStreamReader(serverProcess.getInputStream()));

        initialize();

        this.tools     = fetchTools();
        this.toolNames = new HashSet<>();
        tools.forEach(t -> toolNames.add(t.name()));

        System.out.println("[MCP] Connected. Available git tools: " + toolNames);
    }

    // ─────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if this MCP server owns the given tool name.
     * Used by the agent dispatcher to route tool calls.
     *
     * @param toolName tool name to check
     * @return true if this client can handle it
     */
    public boolean handles(String toolName) {
        return toolNames.contains(toolName);
    }

    /**
     * Returns the MCP server's tools as Anthropic {@link Tool} definitions,
     * ready to be added to a Claude message request.
     *
     * @return list of Anthropic-compatible tool definitions
     */
    public List<Tool> getTools() {
        return tools;
    }

    /**
     * Calls a tool on the MCP server and returns the text output.
     *
     * @param name  tool name (e.g. {@code git_commit})
     * @param input raw {@link JsonValue} from Claude's tool_use block
     * @return combined text content from all MCP content blocks
     * @throws Exception on protocol or tool execution errors
     */
    @SuppressWarnings("unchecked")
    public String callTool(String name, JsonValue input) throws Exception {
        Map<String, Object> args = MAPPER.readValue(input.toString(), Map.class);

        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", MAPPER.valueToTree(args));

        JsonNode result = sendRequest("tools/call", params);

        // MCP returns content as an array of {type, text} blocks
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : result.path("content")) {
            if ("text".equals(item.path("type").asText())) {
                sb.append(item.path("text").asText());
            }
        }
        return sb.isEmpty() ? "(no output)" : sb.toString();
    }

    /**
     * Shuts down the MCP server process.
     */
    @Override
    public void close() {
        serverProcess.destroy();
    }

    // ─────────────────────────────────────────────────────────────────
    // MCP protocol implementation
    // ─────────────────────────────────────────────────────────────────

    /**
     * Performs the MCP initialize handshake (JSON-RPC "initialize" + "notifications/initialized").
     * Must be called once before any other requests.
     */
    private void initialize() throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "wcc-agent");
        clientInfo.put("version", "1.0.0");
        params.putObject("capabilities"); // empty = minimal client

        sendRequest("initialize", params);

        // The "initialized" notification has no id and expects no response
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        writeLine(MAPPER.writeValueAsString(notification));
    }

    /**
     * Fetches the tool list from the MCP server and converts each entry into an
     * Anthropic {@link Tool} so it can be handed directly to Claude.
     *
     * <p>The MCP tool schema uses standard JSON Schema; we preserve it as-is inside
     * the Anthropic {@code InputSchema.Properties} so Claude sees the same documentation.
     *
     * @return list of Anthropic-compatible Tool definitions
     */
    private List<Tool> fetchTools() throws Exception {
        JsonNode result = sendRequest("tools/list", MAPPER.createObjectNode());
        List<Tool> anthropicTools = new ArrayList<>();

        for (JsonNode mcpTool : result.path("tools")) {
            String name        = mcpTool.path("name").asText();
            String description = mcpTool.path("description").asText();
            JsonNode schema    = mcpTool.path("inputSchema");

            Tool.InputSchema.Properties.Builder propsBuilder =
                    Tool.InputSchema.Properties.builder();

            // Forward each JSON Schema property definition verbatim
            schema.path("properties").fields().forEachRemaining(entry ->
                    propsBuilder.putAdditionalProperty(
                            entry.getKey(),
                            JsonValue.from(MAPPER.convertValue(entry.getValue(), Object.class))));

            List<String> required = new ArrayList<>();
            schema.path("required").forEach(r -> required.add(r.asText()));

            anthropicTools.add(Tool.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(Tool.InputSchema.builder()
                            .properties(propsBuilder.build())
                            .required(required)
                            .build())
                    .build());
        }
        return anthropicTools;
    }

    /**
     * Sends a JSON-RPC 2.0 request and blocks until the matching response arrives.
     *
     * <p>The MCP wire format is newline-delimited JSON. Each request has a numeric {@code id};
     * we skip any lines whose id does not match (e.g. server-initiated notifications).
     *
     * @param method JSON-RPC method
     * @param params method parameters
     * @return the {@code result} node from the response
     * @throws Exception if the server returns an error or closes the stream
     */
    private JsonNode sendRequest(String method, ObjectNode params) throws Exception {
        int id = REQUEST_ID.getAndIncrement();

        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);

        writeLine(MAPPER.writeValueAsString(request));

        String line;
        while ((line = stdout.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode response = MAPPER.readTree(line);
            if (!response.path("id").isMissingNode() && response.path("id").asInt() == id) {
                if (response.has("error")) {
                    throw new RuntimeException("MCP error: " + response.path("error"));
                }
                return response.path("result");
            }
            // Discard notifications or responses for other ids
        }
        throw new java.io.IOException("MCP server closed connection unexpectedly for method: " + method);
    }

    /**
     * Writes a single JSON line to the server's stdin and flushes.
     *
     * @param json JSON string to send
     */
    private void writeLine(String json) throws Exception {
        stdin.write(json);
        stdin.newLine();
        stdin.flush();
    }
}
