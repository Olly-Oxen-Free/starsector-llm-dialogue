package starlogue;

import org.json.JSONObject;
import starlogue.action.StarlogueAction;
import starlogue.engine.EvaluatedActionSet;
import starlogue.engine.GameContext;
import starlogue.mcp.McpToolBridge;
import starlogue.mcp.McpToolSchema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link McpToolBridge} error envelopes (audit test gap 8), no live CLI.
 *
 * <p>Covers the deterministic, fast error paths:
 * <ul>
 *   <li>unknown-tool → error envelope,</li>
 *   <li>queue-full → error envelope (queue filled with wedged invokeSync calls),</li>
 *   <li>no-game-context drain → error envelope.</li>
 * </ul>
 *
 * <p><b>Skipped:</b> the real 10s wall-clock {@code future.get} timeout envelope is not exercised
 * here — {@code INVOKE_TIMEOUT_SEC} is a hardcoded 10s constant and asserting it would add a
 * 10-second stall to the suite. Its envelope is constructed by the same {@code errorEnvelope}
 * helper validated by the other cases.
 */
public class McpToolBridgeTest {

    public static void main(String[] args) throws Exception {
        McpToolBridgeTest t = new McpToolBridgeTest();
        t.testUnknownToolReturnsErrorEnvelope();
        t.testQueueFullReturnsErrorEnvelope();
        t.testNoContextDrainReturnsErrorEnvelope();
        System.out.println("McpToolBridgeTest: ALL PASSED");
    }

    private static final String TOOL = "starlogue_noop";

    private static McpToolBridge bridgeWithNoop() {
        McpToolBridge bridge = new McpToolBridge();
        EvaluatedActionSet set = new EvaluatedActionSet(
            List.<StarlogueAction>of(new NoopAction()), List.<StarlogueAction>of());
        bridge.setSchema(new McpToolSchema(set));
        return bridge;
    }

    private static boolean isError(JSONObject env) {
        return env.optBoolean("isError", false);
    }

    private static String text(JSONObject env) {
        return env.optJSONArray("content").optJSONObject(0).optString("text", "");
    }

    /** An unresolvable tool name yields an isError envelope. */
    public void testUnknownToolReturnsErrorEnvelope() {
        McpToolBridge bridge = bridgeWithNoop();
        JSONObject env = bridge.invokeSync("starlogue_does_not_exist", new JSONObject());
        assert isError(env) : "expected isError=true for unknown tool";
        assert text(env).toLowerCase().contains("unknown tool") : "unexpected text: " + text(env);
    }

    /** Filling the queue to capacity makes the next call return a queue-full error envelope. */
    public void testQueueFullReturnsErrorEnvelope() throws Exception {
        final McpToolBridge bridge = bridgeWithNoop();

        // Fill the queue with QUEUE_CAPACITY (8) calls that block in future.get (never drained).
        // Enqueue one at a time and wait until each thread is parked in future.get (TIMED_WAITING),
        // which guarantees it has passed the enqueue step — no concurrent-add race.
        final int capacity = 8;
        Thread[] fillers = new Thread[capacity];
        for (int i = 0; i < capacity; i++) {
            Thread th = new Thread(() -> bridge.invokeSync(TOOL, new JSONObject()));
            th.setDaemon(true);
            fillers[i] = th;
            th.start();
            long deadline = System.currentTimeMillis() + 3000;
            while (th.getState() != Thread.State.TIMED_WAITING
                   && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            assert th.getState() == Thread.State.TIMED_WAITING
                : "filler " + i + " did not block in future.get (state=" + th.getState() + ")";
        }

        // The queue now holds 8; the next call must be rejected immediately.
        JSONObject env = bridge.invokeSync(TOOL, new JSONObject());
        assert isError(env) : "expected isError=true when queue full";
        assert text(env).toLowerCase().contains("queue full") : "unexpected text: " + text(env);

        // Unblock the wedged fillers so they don't linger for 10s.
        bridge.cancelAll();
    }

    /** Draining with no game context completes queued futures with an error envelope. */
    public void testNoContextDrainReturnsErrorEnvelope() throws Exception {
        final McpToolBridge bridge = bridgeWithNoop();
        final JSONObject[] result = new JSONObject[1];
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

        Thread caller = new Thread(() -> {
            result[0] = bridge.invokeSync(TOOL, new JSONObject());
            done.countDown();
        });
        caller.setDaemon(true);
        caller.start();

        // Wait until the call is parked (enqueued), then drain with no context.
        long deadline = System.currentTimeMillis() + 3000;
        while (caller.getState() != Thread.State.TIMED_WAITING
               && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        bridge.drainOnGameThread((GameContext) null);

        assert done.await(2, java.util.concurrent.TimeUnit.SECONDS) : "caller never unblocked";
        assert isError(result[0]) : "expected isError=true for no-context drain";
        assert text(result[0]).toLowerCase().contains("no game context")
            : "unexpected text: " + text(result[0]);
    }

    // ── Fake action ──────────────────────────────────────────────────────────

    static final class NoopAction implements StarlogueAction {
        @Override public String getId() { return "noop"; }
        @Override public String getDescription() { return "noop test action"; }
        @Override public Map<String, Object> getParameters() { return Collections.emptyMap(); }
        @Override public boolean isAvailable(GameContext ctx) { return true; }
        @Override public boolean isBluffable() { return false; }
        @Override public void execute(GameContext ctx, Map<String, Object> args) { }
        @Override public String narrativeNote() { return null; }
    }
}
