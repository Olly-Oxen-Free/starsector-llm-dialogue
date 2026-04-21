package starlogue.llm;

public interface LLMClient {
    /**
     * Blocking call. Run on a daemon thread — never call from the game loop.
     */
    LLMResponse complete(LLMRequest request) throws Exception;
}
