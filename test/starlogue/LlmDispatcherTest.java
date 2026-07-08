package starlogue;

import starlogue.config.LlmBackendConfig;
import starlogue.config.LlmBackendConfig.BackendOption;
import starlogue.llm.LLMClient;
import starlogue.llm.LLMRequest;
import starlogue.llm.LLMResponse;
import starlogue.llm.LlmDispatcher;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link LlmDispatcher}'s stale-response epoch guard (audit #7 / #14).
 *
 * <p>A slow fake {@link LLMClient} lets us wedge a request "in flight", supersede it (via
 * {@link LlmDispatcher#cancel()} or a newer dispatch), then release it and assert its late result
 * is never surfaced by {@link LlmDispatcher#poll()} — while the current request's result is.
 */
public class LlmDispatcherTest {

    public static void main(String[] args) throws Exception {
        LlmDispatcherTest t = new LlmDispatcherTest();
        t.testCancelledResponseNeverSurfaces();
        t.testNewerDispatchSupersedesInFlight();
        t.testHappyPathResponseSurfaces();
        System.out.println("LlmDispatcherTest: ALL PASSED");
    }

    private static final List<BackendOption> BACKENDS =
        List.of(new BackendOption("ollama", "", "model-x", ""));

    private static LLMRequest req() {
        return new LLMRequest(List.of(), List.of(), "model-x", 0.8f, 100);
    }

    /** cancel() bumps the epoch → a released in-flight request must not surface, next one does. */
    public void testCancelledResponseNeverSurfaces() throws Exception {
        CountDownLatch gateA = new CountDownLatch(1);
        GatedClient a = new GatedClient("A-RESPONSE", gateA);
        GatedClient b = new GatedClient("B-RESPONSE", null);

        LlmDispatcher d = new LlmDispatcher();
        d.dispatch(req(), BACKENDS, x -> a);
        assert a.started.await(2, TimeUnit.SECONDS) : "client A never started";

        d.cancel();          // supersede A
        gateA.countDown();   // let A's blocking complete() return
        assert a.finished.await(2, TimeUnit.SECONDS) : "client A never finished";
        Thread.sleep(50);    // let the dispatch thread run its (guarded) publish attempt

        assert d.poll().isEmpty()      : "cancelled response A surfaced via poll()";
        assert d.pollError().isEmpty() : "cancelled response A surfaced via pollError()";

        d.dispatch(req(), BACKENDS, x -> b);
        LLMResponse r = awaitPoll(d, 2000);
        assert r != null && "B-RESPONSE".equals(r.content)
            : "expected B-RESPONSE, got " + (r == null ? "null" : r.content);
    }

    /** A second dispatch while the first is in flight must supersede it (drain + epoch bump). */
    public void testNewerDispatchSupersedesInFlight() throws Exception {
        CountDownLatch gateA = new CountDownLatch(1);
        GatedClient a = new GatedClient("STALE-A", gateA);
        GatedClient b = new GatedClient("FRESH-B", null);

        LlmDispatcher d = new LlmDispatcher();
        d.dispatch(req(), BACKENDS, x -> a);
        assert a.started.await(2, TimeUnit.SECONDS) : "client A never started";

        // Dispatch B while A is still wedged; this bumps the epoch and drains stale state.
        d.dispatch(req(), BACKENDS, x -> b);
        LLMResponse rb = awaitPoll(d, 2000);
        assert rb != null && "FRESH-B".equals(rb.content)
            : "expected FRESH-B, got " + (rb == null ? "null" : rb.content);

        // Now release A; its late completion must never surface.
        gateA.countDown();
        assert a.finished.await(2, TimeUnit.SECONDS) : "client A never finished";
        Thread.sleep(50);
        assert d.poll().isEmpty()      : "stale response A surfaced after B via poll()";
        assert d.pollError().isEmpty() : "stale response A surfaced after B via pollError()";
    }

    /** Sanity: an uncontended dispatch's response is delivered. */
    public void testHappyPathResponseSurfaces() throws Exception {
        GatedClient c = new GatedClient("HELLO", null);
        LlmDispatcher d = new LlmDispatcher();
        d.dispatch(req(), BACKENDS, x -> c);
        LLMResponse r = awaitPoll(d, 2000);
        assert r != null && "HELLO".equals(r.content)
            : "expected HELLO, got " + (r == null ? "null" : r.content);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static LLMResponse awaitPoll(LlmDispatcher d, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            Optional<LLMResponse> o = d.poll();
            if (o.isPresent()) return o.get();
            Thread.sleep(5);
        }
        return null;
    }

    /**
     * Fake client that signals when {@code complete()} starts, optionally blocks on a gate, then
     * returns a fixed response and signals completion.
     */
    static final class GatedClient implements LLMClient {
        final String content;
        final CountDownLatch gate;
        final CountDownLatch started  = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);

        GatedClient(String content, CountDownLatch gate) {
            this.content = content;
            this.gate = gate;
        }

        @Override
        public LLMResponse complete(LLMRequest request) throws Exception {
            started.countDown();
            if (gate != null) gate.await(5, TimeUnit.SECONDS);
            try {
                return new LLMResponse(content, List.of());
            } finally {
                finished.countDown();
            }
        }
    }
}
