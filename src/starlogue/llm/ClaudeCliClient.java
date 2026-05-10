package starlogue.llm;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import starlogue.config.LlmBackendConfig;
import starlogue.mcp.McpConfigWriter;
import starlogue.mcp.McpServer;
import starlogue.mcp.McpToolBridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * {@link LLMClient} implementation that spawns the {@code claude} CLI subprocess
 * and communicates with Claude via the local MCP server.
 *
 * <p><b>Flow per {@link #complete} call:</b>
 * <ol>
 *   <li>Write an MCP config temp file pointing at our in-process {@link McpServer}.</li>
 *   <li>Build and launch a {@code ProcessBuilder} with {@code --output-format stream-json}.</li>
 *   <li>Parse stdout NDJSON line-by-line; accumulate assistant text deltas.</li>
 *   <li>Drain stderr to a background thread to prevent pipe-buffer deadlock.</li>
 *   <li>On {@code result} event: detect auth/rate-limit errors, finalize response.</li>
 *   <li>Enforce timeout; kill subprocess if exceeded.</li>
 *   <li>Delete temp file in {@code finally}.</li>
 * </ol>
 *
 * <p>{@code toolCalls} in the returned {@link LLMResponse} is always empty — tool execution
 * happens inside the CLI via MCP transport and is not duplicated here.
 *
 * <p><b>Partial-text hook (C-7):</b> call {@link #setPartialTextListener} before invoking
 * {@link #complete}. The listener receives each incremental assistant text delta.
 */
public class ClaudeCliClient implements LLMClient {

    private static final Logger log = Logger.getLogger(ClaudeCliClient.class);

    private final LlmBackendConfig.ClaudeCliConfig cliCfg;
    private final McpServer mcpServer;
    private final McpToolBridge bridge;

    /** Optional listener for partial text deltas (C-7). Default: no-op. */
    private volatile Consumer<String> partialTextListener = delta -> {};

    /** Reference to the current in-flight subprocess for abort support (C-9). */
    private volatile Process currentProcess;

    public ClaudeCliClient(LlmBackendConfig.ClaudeCliConfig cliCfg,
                           McpServer mcpServer,
                           McpToolBridge bridge) {
        this.cliCfg    = cliCfg;
        this.mcpServer = mcpServer;
        this.bridge    = bridge;
    }

    /**
     * Register a listener that is invoked on every assistant text delta from the stream.
     * The delta string is the incremental new text (not cumulative). Default: no-op.
     * May be called from any thread before {@link #complete} is invoked.
     */
    public void setPartialTextListener(Consumer<String> listener) {
        this.partialTextListener = (listener != null) ? listener : delta -> {};
    }

    // ── LLMClient ──────────────────────────────────────────────────────────────

    @Override
    public LLMResponse complete(LLMRequest request) throws Exception {
        Path tmpConfig = null;
        Process process = null;
        Thread stderrDrainer = null;

        try {
            // 1. Write MCP config temp file
            tmpConfig = McpConfigWriter.write(mcpServer.getPort());

            // 2. Build prompt from request messages
            String userPrompt    = buildUserPrompt(request);
            String systemPrompt  = extractSystemPrompt(request);

            // 3. Compose ProcessBuilder
            List<String> cmd = buildCommand(tmpConfig, systemPrompt, userPrompt);
            log.info("ClaudeCliClient: spawning subprocess, model=" + cliCfg.model
                     + " timeout=" + cliCfg.timeoutSec + "s");
            if (log.isDebugEnabled()) {
                log.debug("ClaudeCliClient: command = " + cmd);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            // Don't inherit IO — use streams
            process = pb.start();
            currentProcess = process;

            // 4. Drain stderr in background to prevent pipe-buffer deadlock
            final Process proc = process;
            final StringBuilder stderrBuf = new StringBuilder();
            stderrDrainer = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        stderrBuf.append(line).append('\n');
                        log.debug("ClaudeCliClient stderr: " + line);
                    }
                } catch (IOException e) {
                    log.debug("ClaudeCliClient: stderr drain interrupted: " + e.getMessage());
                }
            });
            stderrDrainer.setDaemon(true);
            stderrDrainer.start();

            // 5. Parse stdout NDJSON
            String assembledText = parseStream(process);

            // 6. Wait for process to exit (with timeout)
            boolean exited = process.waitFor(cliCfg.timeoutSec, TimeUnit.SECONDS);
            if (!exited) {
                log.warn("ClaudeCliClient: subprocess timed out after " + cliCfg.timeoutSec + "s — killing");
                process.destroy();
                boolean gone = process.waitFor(1, TimeUnit.SECONDS);
                if (!gone) process.destroyForcibly();
                throw new java.util.concurrent.TimeoutException(
                    "Claude CLI subprocess timed out after " + cliCfg.timeoutSec + "s");
            }

            // 7. Return response — toolCalls always empty (tool execution via MCP)
            return new LLMResponse(assembledText, Collections.emptyList());

        } finally {
            currentProcess = null;
            // Kill subprocess if still alive
            if (process != null && process.isAlive()) {
                process.destroy();
                try { process.waitFor(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                if (process.isAlive()) process.destroyForcibly();
            }
            // Wait for stderr drainer to finish
            if (stderrDrainer != null) {
                try { stderrDrainer.join(2000); } catch (InterruptedException ignored) {}
            }
            // Delete temp config
            if (tmpConfig != null) {
                try { Files.deleteIfExists(tmpConfig); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Abort any in-flight subprocess (C-9 hook). Safe to call from any thread.
     */
    public void abort() {
        Process p = currentProcess;
        if (p != null && p.isAlive()) {
            log.info("ClaudeCliClient: aborting in-flight subprocess");
            p.destroy();
            Thread killer = new Thread(() -> {
                try {
                    boolean gone = p.waitFor(1, TimeUnit.SECONDS);
                    if (!gone) p.destroyForcibly();
                } catch (InterruptedException ignored) {}
            });
            killer.setDaemon(true);
            killer.start();
        }
    }

    // ── Stream parsing ────────────────────────────────────────────────────────

    /**
     * Read stdout NDJSON from the subprocess. Parse each line as a JSON event.
     * Returns the accumulated assistant text.
     *
     * @throws AuthFailedException   if a {@code result} event signals "Not logged in"
     * @throws RateLimitedException  if a {@code result} event signals rate limiting
     * @throws RuntimeException      for other fatal result errors
     */
    private String parseStream(Process process) throws Exception {
        StringBuilder assembled = new StringBuilder();
        boolean resultSeen = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                JSONObject ev;
                try {
                    ev = new JSONObject(line);
                } catch (Exception e) {
                    log.warn("ClaudeCliClient: could not parse NDJSON line: " + line);
                    continue;
                }

                String type = ev.optString("type", "");
                switch (type) {
                    case "system":
                        // Initialization / metadata — ignore
                        break;

                    case "assistant": {
                        // Extract text from content blocks
                        String delta = extractAssistantText(ev);
                        if (delta != null && !delta.isEmpty()) {
                            assembled.append(delta);
                            try { partialTextListener.accept(delta); } catch (Throwable ignored) {}
                        }
                        break;
                    }

                    case "user":
                        // Tool result echoes — ignore
                        break;

                    case "tool_use":
                        // Tool call notification — log only; execution happens via MCP
                        log.debug("ClaudeCliClient: tool_use event (MCP handles): "
                            + ev.optString("name", "?"));
                        break;

                    case "result":
                        resultSeen = true;
                        handleResultEvent(ev, assembled);
                        break;

                    default:
                        log.debug("ClaudeCliClient: unknown event type '" + type + "'");
                        break;
                }
            }
        }

        if (!resultSeen) {
            log.warn("ClaudeCliClient: stdout closed without a result event");
        }

        return assembled.toString();
    }

    /**
     * Extract text from an {@code assistant} stream event.
     *
     * The stream-json format wraps text in the message content array:
     * {@code {"type":"assistant","message":{"content":[{"type":"text","text":"..."}]}}}
     *
     * Handles both delta (partial) and final forms.
     */
    private static String extractAssistantText(JSONObject ev) {
        try {
            JSONObject message = ev.optJSONObject("message");
            if (message == null) return null;
            JSONArray content = message.optJSONArray("content");
            if (content == null || content.length() == 0) return null;
            // Accumulate all text blocks in the content array
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                Object item = content.opt(i);
                if (!(item instanceof JSONObject)) continue;
                JSONObject block = (JSONObject) item;
                if ("text".equals(block.optString("type"))) {
                    String text = block.optString("text", "");
                    if (!text.isEmpty()) sb.append(text);
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Throwable t) {
            log.debug("ClaudeCliClient: failed to extract assistant text: " + t.getMessage());
            return null;
        }
    }

    /**
     * Handle the {@code result} terminal event.
     *
     * <ul>
     *   <li>If {@code is_error: true} and result contains "Not logged in" → {@link AuthFailedException}</li>
     *   <li>If {@code is_error: true} and result contains "rate" → {@link RateLimitedException}</li>
     *   <li>If {@code is_error: true} → generic RuntimeException</li>
     *   <li>If {@code is_error: false} → optionally harvest final result text if assembled is empty</li>
     * </ul>
     */
    private static void handleResultEvent(JSONObject ev, StringBuilder assembled) {
        boolean isError = ev.optBoolean("is_error", false);
        if (!isError) {
            // On success: if we have no assembled text yet, try harvesting from result field
            if (assembled.length() == 0) {
                String resultText = ev.optString("result", "");
                if (!resultText.isEmpty()) {
                    assembled.append(resultText);
                }
            }
            return;
        }

        // Error path
        String resultMsg = ev.optString("result", "");
        String lower     = resultMsg.toLowerCase();

        if (lower.contains("not logged in") || lower.contains("please run /login")) {
            throw new AuthFailedException(
                "Claude CLI is not authenticated. Run `claude` once in a terminal to log in. "
                + "(CLI message: " + resultMsg + ")");
        }
        if (lower.contains("rate") && (lower.contains("limit") || lower.contains("throttl"))) {
            throw new RateLimitedException("Claude CLI rate limited: " + resultMsg);
        }
        throw new RuntimeException("Claude CLI result error: " + resultMsg);
    }

    // ── Prompt building ───────────────────────────────────────────────────────

    /**
     * Extract the system prompt from the request messages (the first message with role "system").
     */
    private static String extractSystemPrompt(LLMRequest request) {
        if (request.messages == null) return "";
        for (Map<String, Object> msg : request.messages) {
            if ("system".equals(msg.get("role"))) {
                Object content = msg.get("content");
                if (content instanceof String) return (String) content;
            }
        }
        return "";
    }

    /**
     * Build a single transcript-style prompt string from the conversation history +
     * the latest user message.
     *
     * <p>Format:
     * <pre>
     * [Prior turns, if any:]
     * Conversation so far:
     * User: &lt;prev user&gt;
     * Assistant: &lt;prev assistant&gt;
     * ...
     *
     * [Current user message:]
     * &lt;current user message&gt;
     * </pre>
     */
    private static String buildUserPrompt(LLMRequest request) {
        if (request.messages == null || request.messages.isEmpty()) return "";

        List<Map<String, Object>> nonSystem = new ArrayList<>();
        for (Map<String, Object> msg : request.messages) {
            if (!"system".equals(msg.get("role"))) {
                nonSystem.add(msg);
            }
        }

        if (nonSystem.isEmpty()) return "";

        // Last message is the current user turn
        Map<String, Object> last = nonSystem.get(nonSystem.size() - 1);
        String currentMsg = contentToString(last.get("content"));

        if (nonSystem.size() == 1) {
            // No prior history — just the current message
            return currentMsg;
        }

        // Build conversation transcript from prior turns
        StringBuilder sb = new StringBuilder("Conversation so far:\n");
        for (int i = 0; i < nonSystem.size() - 1; i++) {
            Map<String, Object> msg = nonSystem.get(i);
            String role    = String.valueOf(msg.get("role"));
            String content = contentToString(msg.get("content"));
            if ("user".equals(role)) {
                sb.append("User: ").append(content).append("\n");
            } else if ("assistant".equals(role)) {
                sb.append("Assistant: ").append(content).append("\n");
            }
        }
        sb.append("\n").append(currentMsg);
        return sb.toString();
    }

    private static String contentToString(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        return content.toString();
    }

    // ── Command assembly ──────────────────────────────────────────────────────

    private List<String> buildCommand(Path mcpConfigPath, String systemPrompt, String userPrompt) {
        List<String> cmd = new ArrayList<>();
        cmd.add(cliCfg.cliPath);
        cmd.add("-p");
        cmd.add("--mcp-config");
        cmd.add(mcpConfigPath.toAbsolutePath().toString());
        cmd.add("--strict-mcp-config");
        cmd.add("--dangerously-skip-permissions");
        cmd.add("--tools");
        cmd.add("");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--include-partial-messages");
        cmd.add("--model");
        cmd.add(cliCfg.model);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            cmd.add("--append-system-prompt");
            cmd.add(systemPrompt);
        }
        // The positional prompt arg — last
        cmd.add(userPrompt);
        return cmd;
    }
}
