package starlogue.llm;

import starlogue.config.LlmBackendConfig;

/**
 * Constructs the appropriate {@link LLMClient} for a given backend configuration entry.
 *
 * <p>Implements {@link LlmDispatcher.ClientFactory} so it can be injected into
 * {@link LlmDispatcher} directly. Keeps all provider-specific wiring (class names,
 * endpoint defaults, constructor signatures) in one place and out of the UI layer.
 *
 * <p>Supported providers: {@code openai}, {@code anthropic}, {@code openrouter},
 * {@code xai}, {@code ollama}, {@code custom}.
 */
public final class ProviderFactory implements LlmDispatcher.ClientFactory {

    /** Shared singleton — stateless, safe to reuse across conversations. */
    public static final ProviderFactory INSTANCE = new ProviderFactory();

    private ProviderFactory() {}

    /**
     * Instantiates the correct {@link LLMClient} for the given backend option.
     *
     * <ul>
     *   <li>{@code anthropic}   → {@link AnthropicClient}</li>
     *   <li>{@code openrouter}  → {@link OpenRouterClient}</li>
     *   <li>{@code xai}         → {@link XaiClient} (uses customEndpoint if set)</li>
     *   <li>{@code openai}      → {@link OpenAIClient} pointing at the OpenAI API</li>
     *   <li>{@code ollama}      → {@link OpenAIClient} pointing at localhost:11434</li>
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
        }
        return new OpenAIClient(b.customEndpoint, b.apiKey); // custom / fallback
    }
}
