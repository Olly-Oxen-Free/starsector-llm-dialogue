package starlogue.llm;

/**
 * xAI Grok inference API. OpenAI-compatible chat completions at
 * {@code https://api.x.ai/v1/chat/completions} with Bearer auth.
 *
 * <p>Credentials: {@code starlogue_provider} {@code xai} (aliases: {@code x.ai}, {@code grok}),
 * {@code starlogue_api_key}, {@code starlogue_model} (e.g. {@code grok-3-mini}).
 * Optional {@code starlogue_endpoint} overrides the base URL (must end with {@code /v1}).
 */
public class XaiClient extends OpenAIClient {

    private static final String DEFAULT_BASE = "https://api.x.ai/v1";

    public XaiClient(String apiKey, String customBaseUrl) {
        super(resolveBase(customBaseUrl), apiKey);
    }

    private static String resolveBase(String customBaseUrl) {
        if (customBaseUrl == null) return DEFAULT_BASE;
        String t = customBaseUrl.trim();
        if (t.isEmpty()) return DEFAULT_BASE;
        return t;
    }
}
