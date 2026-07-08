package starlogue.config;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LLM backend routing and secrets from {@link StarlogueCredentials} only.
 * Use {@link #load()} once per request and pass the {@link Snapshot} through validate + HTTP
 * so all fields come from the same JSON parse (avoids differing reads from multiple
 * {@code getIfPresent} calls).
 */
public final class LlmBackendConfig {

    private LlmBackendConfig() {}

    public static final class BackendOption {
        public final String provider;
        public final String apiKey;
        public final String model;
        public final String customEndpoint;

        public BackendOption(String provider, String apiKey, String model, String customEndpoint) {
            this.provider = provider != null ? provider : "ollama";
            this.apiKey = apiKey != null ? apiKey : "";
            this.model = model != null && !model.isEmpty() ? model : "mistral";
            this.customEndpoint = customEndpoint != null && !customEndpoint.isEmpty()
                ? customEndpoint
                : defaultEndpointForProvider(this.provider);
        }
    }

    /**
     * Configuration specific to the claude_cli provider.
     * Populated from LunaSettings when LunaLib is present; falls back to hardcoded defaults.
     * Wired into the active client in C-5/C-6.
     */
    public static final class ClaudeCliConfig {
        /** Short alias passed to {@code --model}: haiku, sonnet, or opus. Default: sonnet. */
        public final String model;
        /** Path (or bare name) of the {@code claude} executable. Default: "claude". */
        public final String cliPath;
        /** Seconds before the CLI subprocess is killed. Default: 60. */
        public final int timeoutSec;

        public ClaudeCliConfig(String model, String cliPath, int timeoutSec) {
            this.model = (model != null && !model.isEmpty()) ? model : "sonnet";
            this.cliPath = (cliPath != null && !cliPath.isEmpty()) ? cliPath : "claude";
            this.timeoutSec = (timeoutSec > 0) ? timeoutSec : 60;
        }

        /** Reads from LunaSettings when available, else returns defaults. */
        public static ClaudeCliConfig fromLunaSettings() {
            String model = "sonnet";
            String cliPath = "claude";
            int timeoutSec = 60;
            if (LunaSettingHelper.lunaLibAvailable()) {
                // getString not available in LunaSettingHelper; read via getBoolean/int/double
                // For string fields we fall back to the LunaLib API directly (graceful on failure).
                try {
                    String m = lunalib.lunaSettings.LunaSettings.getString(
                            "starlogue", "starlogue_claude_cli_model");
                    if (m != null && !m.isEmpty()) model = m;
                } catch (Throwable ignored) {}
                try {
                    String p = lunalib.lunaSettings.LunaSettings.getString(
                            "starlogue", "starlogue_claude_cli_path");
                    if (p != null && !p.isEmpty()) cliPath = p;
                } catch (Throwable ignored) {}
                timeoutSec = LunaSettingHelper.getInt("starlogue_claude_cli_timeout_sec", 60);
            }
            return new ClaudeCliConfig(model, cliPath, timeoutSec);
        }
    }

    public static final class Snapshot {
        public final List<BackendOption> backends;
        public final String provider;
        public final String apiKey;
        public final String model;
        public final String customEndpoint;
        /** Non-null only when provider == "claude_cli". Populated from LunaSettings. */
        public final ClaudeCliConfig claudeCli;

        public Snapshot(List<BackendOption> backends) {
            List<BackendOption> list = (backends == null) ? new ArrayList<BackendOption>() : backends;
            if (list.isEmpty()) {
                list.add(defaultLegacyBackend());
            }
            this.backends = Collections.unmodifiableList(new ArrayList<BackendOption>(list));
            BackendOption first = this.backends.get(0);
            // Legacy fields kept for older call sites and diagnostics.
            this.provider = first.provider;
            this.apiKey = first.apiKey;
            this.model = first.model;
            this.customEndpoint = first.customEndpoint;
            // Populate CLI config only for the claude_cli provider; avoids unnecessary Luna reads.
            this.claudeCli = "claude_cli".equals(this.provider) ? ClaudeCliConfig.fromLunaSettings() : null;
        }
    }

    /** Single read of credentials JSON; pass this to validation and the LLM client. */
    public static Snapshot load() {
        return fromJson(StarlogueCredentials.getCredentialsObject());
    }

    static Snapshot fromJson(JSONObject o) {
        if (o == null) {
            return new Snapshot(Collections.singletonList(defaultLegacyBackend()));
        }

        List<BackendOption> parsed = parseBackendArray(o.optJSONArray("starlogue_backends"));
        if (!parsed.isEmpty()) {
            return new Snapshot(parsed);
        }

        // Backward-compatible single backend fields.
        String pRaw = stringField(o, "starlogue_provider");
        String key = stringField(o, "starlogue_api_key");
        String model = stringField(o, "starlogue_model");
        String ep = stringField(o, "starlogue_endpoint");
        return new Snapshot(Collections.singletonList(normalizeBackendOption(pRaw, key, model, ep)));
    }

    private static String defaultModelForProvider(String provider) {
        if ("openrouter".equals(provider)) {
            return "nvidia/nemotron-nano-12b-v2-vl:free";
        }
        if ("xai".equals(provider)) {
            return "grok-3-mini";
        }
        return "mistral";
    }

    private static String defaultEndpointForProvider(String provider) {
        if ("xai".equals(provider)) return "https://api.x.ai/v1";
        return "http://localhost:11434/v1";
    }

    private static BackendOption defaultLegacyBackend() {
        return normalizeBackendOption("ollama", "", "mistral", "http://localhost:11434/v1");
    }

    private static BackendOption normalizeBackendOption(String rawProvider, String rawApiKey,
                                                        String rawModel, String rawEndpoint) {
        String provider = normalizeProvider(rawProvider);
        String apiKey = rawApiKey != null ? rawApiKey.trim() : "";
        String model = rawModel != null ? rawModel.trim() : "";
        String endpoint = rawEndpoint != null ? rawEndpoint.trim() : "";

        if (model.isEmpty()) {
            model = defaultModelForProvider(provider);
        } else if ("openrouter".equals(provider) && "mistral".equalsIgnoreCase(model)) {
            // Common migration case: user switched provider from ollama but kept old model.
            model = defaultModelForProvider(provider);
        } else if ("xai".equals(provider) && "mistral".equalsIgnoreCase(model)) {
            model = defaultModelForProvider(provider);
        }
        if (endpoint.isEmpty()) endpoint = defaultEndpointForProvider(provider);
        return new BackendOption(provider, apiKey, model, endpoint);
    }

    private static List<BackendOption> parseBackendArray(JSONArray arr) {
        List<BackendOption> out = new ArrayList<BackendOption>();
        if (arr == null || arr.length() == 0) return out;
        for (int i = 0; i < arr.length(); i++) {
            Object item = arr.opt(i);
            if (!(item instanceof JSONObject)) continue;
            JSONObject b = (JSONObject) item;
            String p = stringField(b, "starlogue_provider");
            String k = stringField(b, "starlogue_api_key");
            String m = stringField(b, "starlogue_model");
            String e = stringField(b, "starlogue_endpoint");
            if (p.isEmpty() && k.isEmpty() && m.isEmpty() && e.isEmpty()) continue;
            out.add(normalizeBackendOption(p, k, m, e));
        }
        return out;
    }

    /** Same package: used for optional CSV/Luna-style field reads. */
    static String stringField(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) {
            return "";
        }
        Object val = o.opt(k);
        if (val == null || val == JSONObject.NULL) {
            return "";
        }
        if (val instanceof String) {
            String s = ((String) val).trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
                return "";
            }
            return s;
        }
        String s = val.toString().trim();
        return "null".equalsIgnoreCase(s) ? "" : s;
    }

    private static String normalizeProvider(String raw) {
        if (raw == null) {
            return "ollama";
        }
        String p = raw.trim().toLowerCase();
        if (p.isEmpty()) {
            return "ollama";
        }
        if ("openai_compatible".equals(p)) {
            return "custom";
        }
        p = p.replace(".", "").replace("-", "").replace("_", "").replace(" ", "");
        if (p.isEmpty()) {
            return "ollama";
        }
        // "grok" alone → xAI (user shorthand); x.ai → xai after dot strip
        if ("grok".equals(p)) {
            return "xai";
        }
        // claude_cli: user invokes the local Claude Code CLI subprocess instead of a direct API call.
        // The raw string "claudecli" results from the normalisation strip above.
        if ("claudecli".equals(p)) {
            return "claude_cli";
        }
        if ("openai".equals(p) || "anthropic".equals(p)
            || "openrouter".equals(p) || "ollama".equals(p) || "custom".equals(p) || "xai".equals(p)
            || "claude_cli".equals(p)) {
            return p;
        }
        return "ollama";
    }
}
