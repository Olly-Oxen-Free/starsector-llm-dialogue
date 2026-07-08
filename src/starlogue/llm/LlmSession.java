package starlogue.llm;

/**
 * Bundles an {@link LLMClient} with an optional lifecycle handle that must be
 * closed when the dialog session ends (e.g., to stop the MCP server).
 *
 * <p>Non-CLI providers return a trivial session whose {@link #close()} is a no-op.
 * The {@code claude_cli} provider's session owns the {@link starlogue.mcp.McpServer}
 * and {@link starlogue.mcp.McpToolBridge}; closing it stops the HTTP server.
 *
 * <p>{@link starlogue.ui.StarlogueDialogPlugin} holds the session returned by
 * {@link ProviderFactory#createSession} and calls {@link #close()} in
 * {@code exitConversation()} / {@code failSafe()}.
 */
public interface LlmSession extends AutoCloseable {

    /** The LLM client for this session. */
    LLMClient client();

    /**
     * Release resources associated with this session (e.g., stop the MCP server).
     * Must not throw checked exceptions.
     */
    @Override
    void close();
}
