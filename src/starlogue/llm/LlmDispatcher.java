package starlogue.llm;

import org.apache.log4j.Logger;
import org.json.JSONObject;
import starlogue.config.LlmBackendConfig;
import starlogue.debug.DebugSessionLog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the background-thread LLM dispatch and backend-fallback retry chain.
 *
 * <p><b>Threading model</b>: HTTP I/O runs on a daemon background thread started by
 * {@link #dispatch}. The game thread reads results via {@link #poll()} and
 * {@link #pollError()} — both operations use {@link AtomicReference#getAndSet} and
 * are safe to call from the game thread without locking.
 *
 * <p><b>Game-API rule</b>: This class must never call Starsector game-API methods.
 * All game-state mutations (dialog text, fleet flags, etc.) stay in the dialog plugin.
 */
public class LlmDispatcher {

    private static final Logger log = Logger.getLogger(LlmDispatcher.class);

    /**
     * Factory: converts a backend config entry into a concrete {@link LLMClient}.
     * Extracted to {@link starlogue.llm.ProviderFactory} in T-6; kept as an inner
     * interface here so T-5 compiles independently.
     */
    public interface ClientFactory {
        LLMClient create(LlmBackendConfig.BackendOption backend);
    }

    private final AtomicReference<LLMResponse> pending = new AtomicReference<>(null);
    private final AtomicReference<Exception> pendingError = new AtomicReference<>(null);

    /**
     * Starts a daemon thread that iterates {@code backends} in order, attempting each
     * until one succeeds. On success, stores the response in {@link #pending}. On total
     * failure (all backends exhausted), stores the aggregate error in {@link #pendingError}.
     *
     * <p>Calling dispatch while a prior request is still in-flight is safe — the new thread
     * will overwrite the atomic references when it completes. The caller is responsible for
     * not issuing concurrent requests if it wants ordered results (the dialog plugin holds
     * state == WAITING as the guard).
     */
    public void dispatch(final LLMRequest baseRequest,
                         final List<LlmBackendConfig.BackendOption> backends,
                         final ClientFactory factory) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder attemptErrors = new StringBuilder();
                for (int i = 0; i < backends.size(); i++) {
                    LlmBackendConfig.BackendOption backend = backends.get(i);
                    LLMRequest request = new LLMRequest(
                        baseRequest.messages, baseRequest.tools,
                        backend.model, baseRequest.temperature, baseRequest.maxTokens);
                    try {
                        LLMClient client = factory.create(backend);
                        LLMResponse response = completeWithBackendRetry(client, backend, request);
                        pending.set(response);
                        return;
                    } catch (Exception e) {
                        String msg = e.getMessage() != null ? e.getMessage() : describeThrowable(e);
                        if (attemptErrors.length() > 0) attemptErrors.append(" | ");
                        attemptErrors.append("#").append(i + 1).append(" ")
                            .append(backend.provider).append("/")
                            .append(backend.model).append(": ").append(msg);
                        log.warn("Starlogue: backend attempt " + (i + 1) + "/" + backends.size()
                            + " failed for provider=" + backend.provider
                            + " model=" + backend.model, e);
                    }
                }
                pendingError.set(new RuntimeException(
                    "All configured backends failed: " + attemptErrors.toString()));
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * Returns and clears the pending {@link LLMResponse} if one has arrived, or
     * {@link Optional#empty()} if the background thread is still running or no request
     * is in-flight. Safe to call on the game thread every frame.
     */
    public Optional<LLMResponse> poll() {
        LLMResponse r = pending.getAndSet(null);
        return Optional.ofNullable(r);
    }

    /**
     * Returns and clears any pending error from the most recent dispatch, or
     * {@link Optional#empty()} if no error has been recorded. Safe to call on the game thread.
     */
    public Optional<Exception> pollError() {
        Exception e = pendingError.getAndSet(null);
        return Optional.ofNullable(e);
    }

    // ── Retry chain ───────────────────────────────────────────────────────

    private LLMResponse completeWithBackendRetry(LLMClient client,
                                                 LlmBackendConfig.BackendOption backend,
                                                 LLMRequest request) throws Exception {
        try {
            return client.complete(request);
        } catch (Exception e) {
            if ("openrouter".equals(backend.provider) && shouldRetryOpenRouterNoTools(e)) {
                try {
                    JSONObject d = new JSONObject();
                    d.put("model", request.model);
                    d.put("retryMode", "same-model-no-tools");
                    d.put("error", e.getMessage() != null ? e.getMessage() : "");
                    DebugSessionLog.log("H_OR_TOOLLESS", "LlmDispatcher.completeWithBackendRetry",
                        "retry-without-tools", d.toString());
                } catch (Throwable ignore) { }
                log.warn("Starlogue: OpenRouter model does not support tool use; retrying once without tools");
                LLMRequest retryNoTools = new LLMRequest(request.messages,
                    java.util.Collections.<Map<String, Object>>emptyList(),
                    request.model, request.temperature, request.maxTokens);
                return client.complete(retryNoTools);
            }
            if ("openrouter".equals(backend.provider) && shouldRetryOpenRouterError(e)) {
                try {
                    JSONObject d = new JSONObject();
                    d.put("model", request.model);
                    d.put("retryModel", "openrouter/auto");
                    d.put("error", e.getMessage() != null ? e.getMessage() : "");
                    DebugSessionLog.log("H_OR_RETRY", "LlmDispatcher.completeWithBackendRetry",
                        "retry-on-provider-error", d.toString());
                } catch (Throwable ignore) { }
                log.warn("Starlogue: OpenRouter provider error for model=" + request.model
                    + ", retrying once with openrouter/auto");
                LLMRequest retry = new LLMRequest(request.messages, request.tools, "openrouter/auto",
                    request.temperature, request.maxTokens);
                return client.complete(retry);
            }
            if (shouldRetryWithDefaultTemperature(e, request.temperature)) {
                float fallbackTemp = defaultFallbackTemperature();
                log.warn("Starlogue: invalid temperature for model=" + request.model
                    + ", retrying once with fallback temperature=" + fallbackTemp);
                LLMRequest retry = new LLMRequest(request.messages, request.tools, request.model,
                    fallbackTemp, request.maxTokens);
                return client.complete(retry);
            }
            throw e;
        }
    }

    // ── Retry predicates (package-private for ProviderFactory use in T-6) ─

    static boolean shouldRetryOpenRouterError(Exception e) {
        String msg = e != null ? e.getMessage() : null;
        if (msg == null) return false;
        boolean is429 = msg.contains("HTTP 429") || msg.contains(" 429 ");
        boolean is502 = msg.contains("HTTP 502") || msg.contains(" 502 ");
        boolean providerErr = msg.toLowerCase().contains("provider returned error");
        return is429 || (is502 && providerErr);
    }

    static boolean shouldRetryOpenRouterNoTools(Exception e) {
        String msg = e != null ? e.getMessage() : null;
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return (m.contains("http 404") || m.contains("http 400"))
            && m.contains("no endpoints found that support tool use");
    }

    static boolean shouldRetryWithDefaultTemperature(Exception e, float currentTemp) {
        if (Math.abs(currentTemp - defaultFallbackTemperature()) < 0.0001f) return false;
        String msg = e != null ? e.getMessage() : null;
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("temperature")
            && (m.contains("invalid")
                || m.contains("only 1 is allowed")
                || m.contains("must be")
                || m.contains("unsupported value"));
    }

    static float defaultFallbackTemperature() {
        return 1.0f;
    }

    private static String describeThrowable(Throwable t) {
        if (t == null) return "unknown error";
        String msg = t.getMessage();
        String cls = t.getClass().getSimpleName();
        if (msg == null || msg.isEmpty()) return cls;
        return cls + ": " + msg;
    }
}
