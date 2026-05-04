package starlogue.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenRouter-specific client. OpenRouter speaks the OpenAI Chat Completions
 * format, so we reuse {@link OpenAIClient} for the body, and only differ in
 * two ways:
 *
 * <ol>
 *   <li>The base URL is hardcoded to {@code https://openrouter.ai/api/v1}.
 *       Users don't need to set {@code starlogue_endpoint} at all, and any
 *       value they do set is ignored — this avoids the footgun of pointing
 *       OpenRouter at an Ollama localhost default.</li>
 *   <li>We send OpenRouter's recommended attribution headers:
 *       {@code HTTP-Referer} and {@code X-Title}. Both are optional, but
 *       they surface on the user's OpenRouter dashboard and leaderboards.</li>
 * </ol>
 */
public class OpenRouterClient extends OpenAIClient {

    private static final String BASE_URL = "https://openrouter.ai/api/v1";

    public OpenRouterClient(String apiKey) {
        super(BASE_URL, apiKey);
    }

    @Override
    protected Map<String, String> extraHeaders() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        // OpenRouter docs use "HTTP-Referer"; some stacks only forward the standard Referer name.
        String ref = "https://fractalsoftworks.com/forum/";
        headers.put("HTTP-Referer", ref);
        headers.put("Referer", ref);
        headers.put("X-Title", "Starlogue (Starsector mod)");
        return headers;
    }
}
