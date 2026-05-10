package starlogue.llm;

import org.apache.log4j.Logger;
import starlogue.config.LlmBackendConfig;
import starlogue.mcp.McpServer;
import starlogue.mcp.McpToolBridge;

import java.io.IOException;

/**
 * Constructs the appropriate {@link LLMClient} for a given backend configuration entry.
 *
 * <p>Implements {@link LlmDispatcher.ClientFactory} so it can be injected into
 * {@link LlmDispatcher} directly. Keeps all provider-specific wiring (class names,
 * endpoint defaults, constructor signatures) in one place and out of the UI layer.
 *
 * <p>Supported providers: {@code openai}, {@code anthropic}, {@code openrouter},
 * {@code xai}, {@code ollama}, {@code custom}, {@code claude_cli}.
 *
 * <p>For {@code claude_cli}, use {@link #createSession(LlmBackendConfig.BackendOption, LlmBackendConfig.ClaudeCliConfig)}
 * to obtain an {@link LlmSession} that owns the MCP server lifecycle.
 */
public final class ProviderFactory implements LlmDispatcher.ClientFactory {

    private static final Logger log = Logger.getLogger(ProviderFactory.class);

    /** Shared singleton — stateless, safe to reuse across conversations. */
    public static final ProviderFactory INSTANCE = new ProviderFactory();

    private ProviderFactory() {}

    /**
     * Instantiates the correct {@link LLMClient} for the given backend option.
     * For {@code claude_cli} this returns a client with no MCP lifecycle; prefer
     * {@link #createSession} to obtain a properly bound session for that provider.
     *
     * <ul>
     *   <li>{@code anthropic}   → {@link AnthropicClient}</li>
     *   <li>{@code openrouter}  → {@link OpenRouterClient}</li>
     *   <li>{@code xai}         → {@link XaiClient} (uses customEndpoint if set)</li>
     *   <li>{@code openai}      → {@link OpenAIClient} pointing at the OpenAI API</li>
     *   <li>{@code ollama}      → {@link OpenAIClient} pointing at localhost:11434</li>
     *   <li>{@code claude_cli}  → Throws (use {@link #createSession} instead)</li>
     *   <li>anything else       → {@link OpenAIClient} using {@code customEndpoint}</li>
     * </ul>
     */
    @Override
    public LLMClient create(LlmBackendConfig.BackendOption b) {
        if ("anthropic".equals(b.provider)) {
            return new AnthropicClient(b.apiKey);
        } else if ("openrouter".equals(b.provider)) {
            return new OpenRouterClient(b.apiKey);
        } else if ("xai".equals(b.provider)) {
            return new XaiClient(b.apiKey, b.customEndpoint);
        } else if ("openai".equals(b.provider)) {
            return new OpenAIClient("https://api.openai.com/v1", b.apiKey);
        } else if ("ollama".equals(b.provider)) {
            return new OpenAIClient("http://localhost:11434/v1", b.apiKey);
        } else if ("claude_cli".equals(b.provider)) {
            throw new IllegalStateException(
                "Use ProviderFactory.createSession() for the claude_cli provider "
                + "(requires MCP server lifecycle)");
        }
        return new OpenAIClient(b.customEndpoint, b.apiKey); // custom / fallback
    }

    /**
     * Create an {@link LlmSession} for the given backend + optional CLI config.
     *
     * <p>For {@code claude_cli}: starts the MCP server, constructs
     * {@link ClaudeCliClient}, and returns a session whose {@link LlmSession#close()}
     * stops the server.
     *
     * <p>For all other providers: returns a trivial session wrapping
     * {@link #create(LlmBackendConfig.BackendOption)} whose {@code close()} is a no-op.
     *
     * @param b       the backend option
     * @param cliCfg  CLI config (only used when {@code b.provider == "claude_cli"}); may be null
     * @return a live session; caller MUST call {@link LlmSession#close()} on dialog exit
     * @throws IOException if the MCP server fails to bind (claude_cli only)
     */
    public LlmSession createSession(LlmBackendConfig.BackendOption b,
                                    LlmBackendConfig.ClaudeCliConfig cliCfg)
            throws IOException {
        if ("claude_cli".equals(b.provider)) {
            return createClaudeCliSession(cliCfg != null ? cliCfg : LlmBackendConfig.ClaudeCliConfig.fromLunaSettings());
        }
        // All other providers: trivial no-op session
        final LLMClient client = create(b);
        return new LlmSession() {
            @Override public LLMClient client() { return client; }
            @Override public void close() { /* no-op */ }
        };
    }

    /**
     * Convenience overload: read CLI config from LunaSettings automatically.
     */
    public LlmSession createSession(LlmBackendConfig.BackendOption b) throws IOException {
        return createSession(b, null);
    }

    /**
     * Extended {@link LlmSession} interface that exposes the MCP infrastructure
     * for the {@code claude_cli} provider. The dialog plugin casts to this interface
     * to wire the tool schema and context after the action set is resolved.
     */
    public interface CliSessionHolder extends LlmSession {
        McpToolBridge getBridge();
        McpServer getServer();
    }

    // ── claude_cli session ────────────────────────────────────────────────────

    private LlmSession createClaudeCliSession(LlmBackendConfig.ClaudeCliConfig cliCfg)
            throws IOException {
        McpToolBridge bridge = new McpToolBridge();
        McpServer server = new McpServer(bridge);
        int port = server.start();
        log.info("ProviderFactory: MCP server started on port " + port + " for claude_cli session");

        ClaudeCliClient client = new ClaudeCliClient(cliCfg, server, bridge);

        return new CliSessionHolder() {
            @Override
            public LLMClient client() { return client; }

            @Override
            public McpToolBridge getBridge() { return bridge; }

            @Override
            public McpServer getServer() { return server; }

            @Override
            public void close() {
                try {
                    bridge.cancelAll();
                } catch (Throwable t) {
                    log.warn("ProviderFactory: bridge.cancelAll() threw on session close", t);
                }
                try {
                    server.stop();
                } catch (Throwable t) {
                    log.warn("ProviderFactory: McpServer.stop() threw on session close", t);
                }
                log.info("ProviderFactory: claude_cli session closed (MCP server stopped)");
            }
        };
    }
}
