package starlogue.config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Tests for {@link LlmBackendConfig#fromJson} and provider-alias normalization
 * (audit test gap #4): backends array vs legacy single-backend fields, provider aliasing
 * (claudecli -> claude_cli, grok -> xai), the openrouter+mistral model migration, and
 * empty-entry skipping in the backends array.
 *
 * <p>In package {@code starlogue.config} to reach the package-private
 * {@link LlmBackendConfig#fromJson(JSONObject)}.
 */
public class LlmBackendConfigTest {

    public static void main(String[] args) throws Exception {
        LlmBackendConfigTest t = new LlmBackendConfigTest();
        t.testLegacyFieldsFallback();
        t.testBackendsArrayTakesPrecedenceOverLegacyFields();
        t.testClaudeCliAlias();
        t.testGrokAliasesToXai();
        t.testOpenrouterMistralMigration();
        t.testXaiMistralMigration();
        t.testEmptyBackendEntriesSkipped();
        t.testNullJsonFallsBackToDefaultOllama();
        System.out.println("LlmBackendConfigTest: ALL PASSED");
    }

    public void testLegacyFieldsFallback() throws Exception {
        JSONObject o = new JSONObject();
        o.put("starlogue_provider", "anthropic");
        o.put("starlogue_api_key", "sk-test");
        o.put("starlogue_model", "claude-3-5-sonnet");
        o.put("starlogue_endpoint", "");

        LlmBackendConfig.Snapshot snap = LlmBackendConfig.fromJson(o);

        assert snap.backends.size() == 1 : "expected exactly one legacy backend";
        LlmBackendConfig.BackendOption b = snap.backends.get(0);
        assert "anthropic".equals(b.provider) : "provider mismatch: " + b.provider;
        assert "sk-test".equals(b.apiKey) : "apiKey mismatch: " + b.apiKey;
        assert "claude-3-5-sonnet".equals(b.model) : "model mismatch: " + b.model;
    }

    public void testBackendsArrayTakesPrecedenceOverLegacyFields() throws Exception {
        JSONObject o = new JSONObject();
        // Legacy fields present but should be ignored once starlogue_backends is non-empty.
        o.put("starlogue_provider", "anthropic");
        o.put("starlogue_api_key", "legacy-key");

        JSONArray backends = new JSONArray();
        JSONObject b1 = new JSONObject();
        b1.put("starlogue_provider", "openai");
        b1.put("starlogue_api_key", "key-1");
        b1.put("starlogue_model", "gpt-4o");
        backends.put(b1);
        o.put("starlogue_backends", backends);

        LlmBackendConfig.Snapshot snap = LlmBackendConfig.fromJson(o);

        assert snap.backends.size() == 1 : "expected exactly one backend from array";
        assert "openai".equals(snap.backends.get(0).provider)
            : "backends array should win over legacy fields, got " + snap.backends.get(0).provider;
        assert "key-1".equals(snap.backends.get(0).apiKey);
    }

    public void testClaudeCliAlias() throws Exception {
        JSONObject o = legacyBackend("claudecli", "", "sonnet", "");
        LlmBackendConfig.Snapshot snap = LlmBackendConfig.fromJson(o);
        assert "claude_cli".equals(snap.backends.get(0).provider)
            : "expected claudecli -> claude_cli alias, got " + snap.backends.get(0).provider;
    }

    public void testGrokAliasesToXai() throws Exception {
        JSONObject o = legacyBackend("grok", "xai-key", "", "");
        LlmBackendConfig.Snapshot snap = LlmBackendConfig.fromJson(o);
        LlmBackendConfig.BackendOption b = snap.backends.get(0);
        assert "xai".equals(b.provider) : "expected grok -> xai alias, got " + b.provider;
        assert "grok-3-mini".equals(b.model) : "expected xai default model, got " + b.model;
    }

    public void testOpenrouterMistralMigration() throws Exception {
        // A user who switched provider from ollama to openrouter but kept the old "mistral"
        // model string should be migrated to the openrouter default model.
        JSONObject o = legacyBackend("openrouter", "or-key", "mistral", "");
        LlmBackendConfig.Snapshot snap = LlmBackendConfig.fromJson(o);
        LlmBackendConfig.BackendOption b = snap.backends.get(0);
        assert "openrouter".equals(b.provider);
        assert !"mistral".equalsIgnoreCase(b.model)
            : "expected openrouter+mistral migration to replace stale model, got " + b.model;
        assert "nvidia/nemotron-nano-12b-v2-vl:free".equals(b.model) : "unexpected migrated model: " + b.model;
    }

    public void testXaiMistralMigration() throws Exception {
        JSONObject o = legacyBackend("xai", "xai-key", "mistral", "");
        LlmBackendConfig.Snapshot snap = LlmBackendConfig.fromJson(o);
        LlmBackendConfig.BackendOption b = snap.backends.get(0);
        assert "grok-3-mini".equals(b.model) : "expected xai+mistral migration, got " + b.model;
    }

    public void testEmptyBackendEntriesSkipped() throws Exception {
        JSONArray backends = new JSONArray();
        backends.put(new JSONObject()); // all fields blank -> should be skipped
        JSONObject real = new JSONObject();
        real.put("starlogue_provider", "ollama");
        real.put("starlogue_model", "llama3");
        backends.put(real);

        JSONObject o = new JSONObject();
        o.put("starlogue_backends", backends);

        LlmBackendConfig.Snapshot snap = LlmBackendConfig.fromJson(o);

        assert snap.backends.size() == 1
            : "expected the empty entry to be skipped, got " + snap.backends.size() + " backends";
        assert "llama3".equals(snap.backends.get(0).model);
    }

    public void testNullJsonFallsBackToDefaultOllama() throws Exception {
        LlmBackendConfig.Snapshot snap = LlmBackendConfig.fromJson(null);
        assert snap.backends.size() == 1;
        assert "ollama".equals(snap.backends.get(0).provider);
    }

    private static JSONObject legacyBackend(String provider, String apiKey, String model, String endpoint) throws Exception {
        JSONObject o = new JSONObject();
        o.put("starlogue_provider", provider);
        o.put("starlogue_api_key", apiKey);
        o.put("starlogue_model", model);
        o.put("starlogue_endpoint", endpoint);
        return o;
    }
}
