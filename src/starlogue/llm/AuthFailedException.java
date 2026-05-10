package starlogue.llm;

/**
 * Thrown by {@link ClaudeCliClient} when the Claude CLI subprocess reports that the user
 * is not authenticated. Retrying is pointless — the user must run {@code claude} once
 * in a terminal to log in. {@link LlmDispatcher} should NOT retry on this exception.
 */
public class AuthFailedException extends RuntimeException {
    public AuthFailedException(String message) {
        super(message);
    }
}
