package integration;

import starlogue.config.LlmBackendConfig;
import starlogue.mcp.McpServer;
import starlogue.mcp.McpToolBridge;
import starlogue.mcp.McpToolSchema;
import starlogue.llm.ClaudeCliClient;
import starlogue.llm.LLMRequest;
import starlogue.llm.LLMResponse;
import starlogue.action.StarlogueAction;
import starlogue.engine.EvaluatedActionSet;
import starlogue.engine.GameContext;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standalone integration harness for the Claude CLI provider.
 *
 * Boots McpServer + McpToolBridge, registers stub actions, invokes
 * ClaudeCliClient.complete() with a prompt that should trigger a tool call,
 * drains the bridge from a "fake game thread" polling loop, and asserts
 * end-to-end success.
 *
 * REQUIRES: {@code claude} CLI on PATH and authenticated.
 * Run via {@code tools/integration/test-claude-cli-flow.sh}.
 */
public class ClaudeCliIntegrationTest {

    // ── Stub action ──────────────────────────────────────────────────────────

    /** Minimal StarlogueAction stub that records invocations. */
    static class EchoAction implements StarlogueAction {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final AtomicReference<Map<String, Object>> lastArgs = new AtomicReference<>();

        @Override public String getId()          { return "starlogue_echo"; }
        @Override public String getDescription() {
            return "Echo the given text back. Use when asked to echo something.";
        }
        @Override public String narrativeNote()  { return "Echo executed."; }
        @Override public boolean isBluffable()   { return false; }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("text", "string");
            return p;
        }

        @Override
        public Map<String, String> getParameterDescriptions() {
            Map<String, String> d = new LinkedHashMap<>();
            d.put("text", "The text to echo back.");
            return d;
        }

        @Override
        public boolean isAvailable(GameContext ctx) { return true; }

        @Override
        public void execute(GameContext ctx, Map<String, Object> args) {
            callCount.incrementAndGet();
            lastArgs.set(args);
            System.out.println("[stub] starlogue_echo called with args: " + args);
        }

        public int getCallCount()                { return callCount.get(); }
        public Map<String, Object> getLastArgs() { return lastArgs.get(); }
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        String cliPath = args.length > 0 ? args[0] : "claude";
        String model   = args.length > 1 ? args[1] : "haiku";

        System.out.println("=== Starlogue ClaudeCliIntegrationTest ===");
        System.out.println("CLI path : " + cliPath);
        System.out.println("Model    : " + model);
        System.out.println();

        long t0 = System.currentTimeMillis();

        // 1. Boot MCP server + bridge
        McpToolBridge bridge = new McpToolBridge();
        McpServer server = new McpServer(bridge);
        int port = server.start();
        System.out.println("[setup] McpServer started on port " + port);
        long tMcpUp = System.currentTimeMillis();

        // 2. Create stub action + schema
        EchoAction echoAction = new EchoAction();
        List<StarlogueAction> available = Collections.singletonList(echoAction);
        List<StarlogueAction> bluffOnly = Collections.emptyList();
        EvaluatedActionSet actionSet = new EvaluatedActionSet(available, bluffOnly);
        McpToolSchema schema = new McpToolSchema(actionSet);
        bridge.setSchema(schema);
        bridge.setContext(null); // no real GameContext in harness — bridge handles null
        server.setSchema(schema);

        // 3. Create CLI client
        LlmBackendConfig.ClaudeCliConfig cliCfg =
            new LlmBackendConfig.ClaudeCliConfig(model, cliPath, 90);
        ClaudeCliClient client = new ClaudeCliClient(cliCfg, server, bridge);

        // Record partial text for assertion
        List<String> partialDeltas = Collections.synchronizedList(new ArrayList<>());
        client.setPartialTextListener(delta -> partialDeltas.add(delta));

        // 4. Build request — prompt that should trigger a tool call
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> sysMsg = new LinkedHashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content",
            "You are a test assistant. When asked to echo something, ALWAYS call the " +
            "starlogue_echo tool immediately with the requested text. Do not describe " +
            "what you will do — just call the tool.");
        messages.add(sysMsg);
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "Please echo the phrase: hello from integration test");
        messages.add(userMsg);

        // tools list is empty in the request — tools are served via MCP
        LLMRequest request = new LLMRequest(messages, Collections.emptyList(), model, 0.0f, 200);

        // 5. Run CLI dispatch in background; drain bridge from "fake game thread"
        long tRequestStart = System.currentTimeMillis();
        CompletableFuture<LLMResponse> responseFuture = new CompletableFuture<>();

        Thread dispatchThread = new Thread(() -> {
            try {
                LLMResponse r = client.complete(request);
                responseFuture.complete(r);
            } catch (Exception e) {
                responseFuture.completeExceptionally(e);
            }
        });
        dispatchThread.setDaemon(true);
        dispatchThread.setName("starlogue-cli-dispatch");
        dispatchThread.start();

        // 6. Fake game-thread drain loop
        System.out.println("[game-thread] starting drain loop...");
        long tFirstToolCall = -1;
        LLMResponse response = null;

        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            bridge.drainOnGameThread((GameContext) null);

            if (tFirstToolCall < 0 && echoAction.getCallCount() > 0) {
                tFirstToolCall = System.currentTimeMillis();
                System.out.println("[game-thread] tool called at +"
                    + (tFirstToolCall - tRequestStart) + "ms");
            }

            if (responseFuture.isDone()) {
                try {
                    response = responseFuture.get();
                } catch (ExecutionException e) {
                    throw new RuntimeException(
                        "CLI client threw: " + e.getCause().getMessage(), e.getCause());
                }
                break;
            }
            Thread.sleep(50);
        }

        long tDone = System.currentTimeMillis();

        if (response == null) {
            System.err.println("FAIL: response never arrived within 90s");
            server.stop();
            System.exit(1);
        }

        // 7. Assertions
        System.out.println();
        System.out.println("=== Results ===");
        System.out.println("Response text : " + response.content);
        System.out.println("Tool calls    : " + echoAction.getCallCount());
        System.out.println("Partial deltas: " + partialDeltas.size());
        System.out.println();

        assertThat("Tool was called at least once", echoAction.getCallCount() >= 1);
        assertThat("Response is non-null", response.content != null);
        assertThat("Streaming produced partial deltas", !partialDeltas.isEmpty());

        // 8. Timing breakdown
        System.out.println();
        System.out.println("=== Timing ===");
        System.out.printf("MCP server startup : %d ms%n", tMcpUp - t0);
        System.out.printf("CLI round-trip     : %d ms%n", tDone - tRequestStart);
        if (tFirstToolCall > 0) {
            System.out.printf("First tool call    : %d ms after request%n",
                tFirstToolCall - tRequestStart);
        }
        System.out.printf("Total elapsed      : %d ms%n", tDone - t0);

        server.stop();
        System.out.println();
        System.out.println("PASS");
        System.exit(0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void assertThat(String label, boolean condition) {
        if (!condition) {
            System.err.println("ASSERTION FAILED: " + label);
            System.exit(1);
        }
        System.out.println("  PASS: " + label);
    }
}
