package starlogue.llm;

/**
 * Thrown by {@link ClaudeCliClient} when the Claude CLI subprocess is rate-limited
 * by the Anthropic API.
 */
public class RateLimitedException extends RuntimeException {
    public RateLimitedException(String message) {
        super(message);
    }
}
