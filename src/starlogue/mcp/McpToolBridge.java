package starlogue.mcp;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;

import starlogue.api.StarlogueAPI;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bridges the MCP server's worker threads to the Starsector game thread.
 *
 * Design (mirrors the existing {@code AtomicReference<LLMResponse>} polling pattern):
 *
 * <ol>
 *   <li>MCP thread calls {@link #invokeSync}: enqueues a {@link ToolCallRequest}
 *       and blocks on {@link CompletableFuture#get} for up to 10 seconds.</li>
 *   <li>Game thread calls {@link #drainOnGameThread} from
 *       {@code StarlogueDialogPlugin.advance()} on every tick; each request is
 *       executed synchronously on the game thread and the future is completed.</li>
 * </ol>
 *
 * Queue capacity is capped at 8. Overflow returns an error envelope immediately.
 *
 * Thread-safety: the queue is a {@link ConcurrentLinkedQueue}; futures are
 * completed from the game thread and awaited from MCP threads.
 */
public class McpToolBridge {

    private static final Logger log = Logger.getLogger(McpToolBridge.class);

    private static final int  QUEUE_CAPACITY      = 8;
    private static final long INVOKE_TIMEOUT_SEC  = 10;

    private final ConcurrentLinkedQueue<ToolCallRequest> queue = new ConcurrentLinkedQueue<>();

    /** Set by {@link McpServer#setSchema} after C-3 wires the schema in. */
    private volatile McpToolSchema schema;

    /** Set by {@code StarlogueDialogPlugin} when a dialog is open. */
    private volatile GameContext currentContext;

    /**
     * Pending narrative notes from tool executions that the dialog plugin has not yet
     * rendered. Populated on the game thread by {@link #executeRequest}; drained by
     * {@link #drainNarrativeNotes()} called from the dialog plugin.
     */
    private final ConcurrentLinkedQueue<String> pendingNotes = new ConcurrentLinkedQueue<>();

    /**
     * True if any executed action triggered a dialog handoff via
     * {@link StarlogueAPI#handoffToPlugin}. The dialog plugin checks this in
     * {@code advance()} to detect that the dialog should be dismissed (the handoff
     * plugin takes over). Reset by {@link #consumeHandoffFlag()}.
     */
    private volatile boolean handoffRequested = false;

    public McpToolBridge() {}

    // ── Schema / context wiring ───────────────────────────────────────────────

    /**
     * Called by {@code StarlogueDialogPlugin} (or the CLI client) when the action set
     * is resolved for the current dialog.
     */
    public void setSchema(McpToolSchema schema) {
        this.schema = schema;
    }

    /**
     * Called by {@code StarlogueDialogPlugin.init()} to supply the game context
     * that tool executions will receive.
     */
    public void setContext(GameContext ctx) {
        this.currentContext = ctx;
    }

    // ── MCP-thread side ───────────────────────────────────────────────────────

    /**
     * Called from the MCP server thread when a {@code tools/call} JSON-RPC request arrives.
     * Enqueues the call and blocks until the game thread completes it (or timeout).
     *
     * @param mcpToolName the prefixed name, e.g. {@code "starlogue_attack"}
     * @param args        the {@code arguments} JSONObject from the MCP request
     * @return a MCP {@code tool_result} envelope: {@code {content:[{type,text}], isError:bool}}
     */
    public JSONObject invokeSync(String mcpToolName, JSONObject args) {
        // Resolve action
        McpToolSchema s = schema;
        StarlogueAction action = (s != null) ? s.lookup(mcpToolName) : null;
        if (action == null) {
            log.warn("MCP: unknown tool '" + mcpToolName + "'");
            return errorEnvelope("Unknown tool: " + mcpToolName);
        }

        // Check queue capacity (approximate — ConcurrentLinkedQueue.size() is O(n) but fine here)
        if (queue.size() >= QUEUE_CAPACITY) {
            log.warn("MCP: tool call queue full (capacity=" + QUEUE_CAPACITY +
                     "), rejecting '" + mcpToolName + "'");
            return errorEnvelope("Tool call queue full — try again in a moment");
        }

        // Enqueue
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        queue.add(new ToolCallRequest(mcpToolName, action, args, future));

        // Block until game thread drains it
        try {
            return future.get(INVOKE_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("MCP: tool '" + mcpToolName + "' timed out after " + INVOKE_TIMEOUT_SEC + "s");
            future.cancel(true);
            return errorEnvelope("Tool execution timed out (" + INVOKE_TIMEOUT_SEC + "s)");
        } catch (CancellationException e) {
            return errorEnvelope("Tool call cancelled");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorEnvelope("Tool call interrupted");
        } catch (Exception e) {
            log.error("MCP: unexpected error waiting for tool '" + mcpToolName + "'", e);
            return errorEnvelope("Internal error: " + e.getMessage());
        }
    }

    // ── Game-thread side ──────────────────────────────────────────────────────

    /**
     * Drain all pending tool call requests.  MUST be called from the Starsector
     * game thread (e.g. from {@code StarlogueDialogPlugin.advance()}).
     *
     * @param ctx the current {@link GameContext}; if null the stored context is used
     */
    public void drainOnGameThread(GameContext ctx) {
        if (ctx == null) ctx = currentContext;
        if (ctx == null) {
            // No context yet — complete all pending futures with error so MCP thread unblocks
            ToolCallRequest req;
            while ((req = queue.poll()) != null) {
                req.resultFuture.complete(errorEnvelope("No game context available"));
            }
            return;
        }

        ToolCallRequest req;
        while ((req = queue.poll()) != null) {
            executeRequest(req, ctx);
        }
    }

    /** Convenience overload using the stored context. */
    public void drainOnGameThread() {
        drainOnGameThread(currentContext);
    }

    // ── Game-thread signals ───────────────────────────────────────────────────

    /**
     * Drain any narrative notes accumulated during the last tool-execution batch.
     * Returns the list (may be empty) and clears the internal queue.
     * MUST be called from the game thread.
     */
    public List<String> drainNarrativeNotes() {
        List<String> notes = new ArrayList<>();
        String note;
        while ((note = pendingNotes.poll()) != null) notes.add(note);
        return notes;
    }

    /**
     * Returns true (and resets the flag to false) if any tool executed via MCP
     * triggered a dialog handoff via {@link StarlogueAPI#handoffToPlugin}.
     * The dialog plugin should call this each frame in {@code advance()} and, when
     * true, consume the handoff from StarlogueAPI and dismiss itself.
     */
    public boolean consumeHandoffFlag() {
        if (handoffRequested) {
            handoffRequested = false;
            return true;
        }
        return false;
    }

    // ── Cancellation ─────────────────────────────────────────────────────────

    /**
     * Cancel all pending tool calls.  Completes all futures with
     * {@link CancellationException} so that blocking MCP threads unblock immediately.
     * Called from the C-9 abort flow.
     */
    public void cancelAll() {
        ToolCallRequest req;
        int count = 0;
        while ((req = queue.poll()) != null) {
            req.resultFuture.completeExceptionally(new CancellationException("Bridge cancelled"));
            count++;
        }
        if (count > 0) {
            log.info("MCP: bridge cancelled " + count + " pending tool call(s)");
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void executeRequest(ToolCallRequest req, GameContext ctx) {
        try {
            Map<String, Object> argMap = jsonObjectToMap(req.args);
            req.action.execute(ctx, argMap);

            // Check if the action requested a dialog handoff (e.g. tournament / quest).
            // We set a flag so the dialog plugin can detect this in advance() and act.
            // Do NOT consume the handoff here — the dialog plugin must call
            // StarlogueAPI.consumeHandoff() to actually retrieve and install the new plugin.
            com.fs.starfarer.api.campaign.InteractionDialogPlugin pendingHandoff =
                StarlogueAPI.peekHandoff();
            if (pendingHandoff != null) {
                handoffRequested = true;
            }

            String note = req.action.narrativeNote();
            if (note != null) pendingNotes.offer(note);
            req.resultFuture.complete(successEnvelope(note != null ? note : "ok"));

        } catch (Throwable t) {
            log.error("MCP: tool '" + req.mcpToolName + "' threw on game thread", t);
            // Complete with an error envelope (not completeExceptionally) so the MCP
            // thread receives a valid tool_result rather than an exception.
            req.resultFuture.complete(errorEnvelope("[error] " + t.getMessage()));
        }
    }

    /**
     * Convert a flat JSONObject to a {@code Map<String,Object>} compatible with
     * {@link StarlogueAction#execute}.  The bundled json.jar uses {@code keys()}
     * (Iterator-based) rather than the modern {@code keySet()}.
     * Numeric values are normalised to {@code double} to match the OpenAI path.
     */
    static Map<String, Object> jsonObjectToMap(JSONObject obj) {
        Map<String, Object> map = new HashMap<>();
        if (obj == null) return map;
        Iterator<?> keys = obj.keys();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            Object val = obj.opt(key);
            if (val == null || val == JSONObject.NULL) continue;
            // Normalise Integer/Long → double for consistency with OpenAI tool-call path
            if (val instanceof Integer) val = ((Integer) val).doubleValue();
            else if (val instanceof Long) val = ((Long) val).doubleValue();
            map.put(key, val);
        }
        return map;
    }

    private static JSONObject successEnvelope(String text) {
        try {
            JSONObject result = new JSONObject();
            result.put("content", new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", text)));
            result.put("isError", false);
            return result;
        } catch (JSONException e) {
            throw new RuntimeException("McpToolBridge.successEnvelope: unexpected JSONException", e);
        }
    }

    private static JSONObject errorEnvelope(String message) {
        try {
            JSONObject result = new JSONObject();
            result.put("content", new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", message)));
            result.put("isError", true);
            return result;
        } catch (JSONException e) {
            throw new RuntimeException("McpToolBridge.errorEnvelope: unexpected JSONException", e);
        }
    }

    // ── Inner class ───────────────────────────────────────────────────────────

    /**
     * A pending tool call waiting to be executed on the game thread.
     */
    static final class ToolCallRequest {
        final String mcpToolName;
        final StarlogueAction action;
        final JSONObject args;
        final CompletableFuture<JSONObject> resultFuture;

        ToolCallRequest(String mcpToolName, StarlogueAction action,
                        JSONObject args, CompletableFuture<JSONObject> resultFuture) {
            this.mcpToolName  = mcpToolName;
            this.action       = action;
            this.args         = args;
            this.resultFuture = resultFuture;
        }
    }
}
