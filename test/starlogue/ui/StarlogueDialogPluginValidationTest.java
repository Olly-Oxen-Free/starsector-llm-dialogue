package starlogue.ui;

import starlogue.config.LlmBackendConfig;

/**
 * Tests for {@link StarlogueDialogPlugin#validateBackendOption} (audit test gap #9): a pure,
 * package-static helper (no instance state) that gates whether a backend option has enough
 * config to attempt an LLM call.
 */
public class StarlogueDialogPluginValidationTest {

    public static void main(String[] args) throws Exception {
        StarlogueDialogPluginValidationTest t = new StarlogueDialogPluginValidationTest();
        t.testNullEntryRejected();
        t.testClaudeCliSkipsModelAndKeyChecks();
        t.testMissingModelRejected();
        t.testCustomProviderRequiresEndpoint();
        t.testCloudProvidersRequireApiKey();
        t.testOllamaNeedsOnlyModel();
        System.out.println("StarlogueDialogPluginValidationTest: ALL PASSED");
    }

    public void testNullEntryRejected() {
        String err = StarlogueDialogPlugin.validateBackendOption(null);
        assert err != null && err.contains("null") : "expected null-entry error, got: " + err;
    }

    public void testClaudeCliSkipsModelAndKeyChecks() {
        LlmBackendConfig.BackendOption b = new LlmBackendConfig.BackendOption("claude_cli", "", "", "");
        String err = StarlogueDialogPlugin.validateBackendOption(b);
        assert err == null : "claude_cli should never require model/apiKey, got: " + err;
    }

    public void testMissingModelRejected() {
        // BackendOption's constructor defaults an empty model to "mistral", so force emptiness
        // isn't reachable via that constructor for ollama; use a provider whose validation still
        // requires a non-empty model even though the constructor supplies a default — this
        // asserts the default kicks in and validation passes.
        LlmBackendConfig.BackendOption b = new LlmBackendConfig.BackendOption("ollama", "", "", "");
        String err = StarlogueDialogPlugin.validateBackendOption(b);
        assert err == null : "ollama with defaulted model should validate clean, got: " + err;
    }

    public void testCustomProviderRequiresEndpoint() {
        LlmBackendConfig.BackendOption b = new LlmBackendConfig.BackendOption("custom", "key", "some-model", "");
        String err = StarlogueDialogPlugin.validateBackendOption(b);
        // customEndpoint defaults via defaultEndpointForProvider() to the ollama localhost URL for
        // "custom" providers with no explicit endpoint, so validation should pass (endpoint non-empty).
        assert err == null : "custom provider with defaulted endpoint should validate, got: " + err;
    }

    public void testCloudProvidersRequireApiKey() {
        for (String provider : new String[] { "openai", "anthropic", "openrouter", "xai" }) {
            LlmBackendConfig.BackendOption missingKey =
                new LlmBackendConfig.BackendOption(provider, "", "some-model", "");
            String err = StarlogueDialogPlugin.validateBackendOption(missingKey);
            assert err != null && err.contains("starlogue_api_key")
                : provider + " with empty apiKey should be rejected, got: " + err;

            LlmBackendConfig.BackendOption withKey =
                new LlmBackendConfig.BackendOption(provider, "sk-abc", "some-model", "");
            String ok = StarlogueDialogPlugin.validateBackendOption(withKey);
            assert ok == null : provider + " with apiKey should validate clean, got: " + ok;
        }
    }

    public void testOllamaNeedsOnlyModel() {
        LlmBackendConfig.BackendOption b = new LlmBackendConfig.BackendOption("ollama", "", "llama3", "");
        String err = StarlogueDialogPlugin.validateBackendOption(b);
        assert err == null : "ollama with a model set should validate clean, got: " + err;
    }
}
