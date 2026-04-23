package starlogue.debug;

import com.fs.starfarer.api.campaign.SectorEntityToken;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import starlogue.config.LunaSettingHelper;
import starlogue.engine.EvaluatedActionSet;
import starlogue.engine.GameContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent NDJSON conversation audit trail that can be viewed outside the game.
 * Each line is a complete JSON object with event type + payload.
 * Gated by Luna: {@code starlogue_audit_conversation_log} (master switch).
 */
public final class ConversationAuditLog {

    private static final Logger log = Logger.getLogger(ConversationAuditLog.class);
    private static final String LOG_NAME = "Starlogue_conversations.ndjson";
    private static final int MAX_TEXT_FIELD = 200_000;
    private static final Object LOCK = new Object();
    private static File cachedFile;

    private ConversationAuditLog() { }

    /** Tool args may contain non-JSON-native types from the LLM parser — coerce for valid NDJSON lines. */
    public static JSONObject safeArgsJson(Map<String, Object> args) {
        JSONObject o = new JSONObject();
        if (args == null) return o;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            String key = e.getKey();
            if (key == null) continue;
            Object v = e.getValue();
            try {
                if (v == null) {
                    o.put(key, JSONObject.NULL);
                } else if (v instanceof JSONObject) {
                    o.put(key, v);
                } else if (v instanceof JSONArray) {
                    o.put(key, v);
                } else if (v instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) v;
                    o.put(key, safeArgsJson(m));
                } else if (v instanceof Collection) {
                    JSONArray arr = new JSONArray();
                    for (Object x : (Collection<?>) v) {
                        if (x == null) {
                            arr.put(JSONObject.NULL);
                        } else if (x instanceof Number) {
                            arr.put(x);
                        } else if (x instanceof Boolean) {
                            arr.put(x);
                        } else {
                            arr.put(String.valueOf(x));
                        }
                    }
                    o.put(key, arr);
                } else if (v instanceof Number || v instanceof Boolean) {
                    o.put(key, v);
                } else {
                    o.put(key, String.valueOf(v));
                }
            } catch (Throwable t) {
                try {
                    o.put(key, "<unserializable:" + v.getClass().getSimpleName() + ">");
                } catch (Throwable ignored) { }
            }
        }
        return o;
    }

    private static String truncateForLog(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...(truncated,len=" + s.length() + ")";
    }

    public static String newConversationId() {
        return "conv-" + Long.toHexString(System.currentTimeMillis()) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static void logConversationStart(String conversationId, SectorEntityToken target, GameContext ctx, EvaluatedActionSet set) {
        try {
            JSONObject data = base(conversationId, "conversation_started");
            data.put("targetId", target != null ? target.getId() : "");
            data.put("targetName", target != null ? target.getName() : "");
            data.put("speaker", ctx != null && ctx.speakerName != null ? ctx.speakerName : "");
            data.put("npcFaction", ctx != null && ctx.npcFaction != null ? ctx.npcFaction.getId() : "");
            data.put("repLevel", ctx != null && ctx.repLevel != null ? ctx.repLevel.name() : "");
            JSONArray tools = new JSONArray();
            if (set != null && set.available != null) {
                for (starlogue.action.StarlogueAction action : set.available) {
                    tools.put(action.getId());
                }
            }
            data.put("availableTools", tools);
            writeLine(data);
        } catch (Throwable t) {
            log.debug("Starlogue audit: logConversationStart failed", t);
        }
    }

    public static void logConversationEnd(String conversationId, String reason) {
        try {
            JSONObject data = base(conversationId, "conversation_ended");
            data.put("reason", reason != null ? reason : "unknown");
            writeLine(data);
        } catch (Throwable t) {
            log.debug("Starlogue audit: logConversationEnd failed", t);
        }
    }

    public static void logUserMessage(String conversationId, String text) {
        try {
            JSONObject data = base(conversationId, "user_message");
            data.put("text", truncateForLog(text != null ? text : "", MAX_TEXT_FIELD));
            writeLine(data);
        } catch (Throwable t) {
            log.debug("Starlogue audit: logUserMessage failed", t);
        }
    }

    public static void logAssistantMessage(String conversationId, String text, int toolCallCount) {
        try {
            JSONObject data = base(conversationId, "assistant_message");
            data.put("text", truncateForLog(text != null ? text : "", MAX_TEXT_FIELD));
            data.put("toolCallCount", toolCallCount);
            writeLine(data);
        } catch (Throwable t) {
            log.debug("Starlogue audit: logAssistantMessage failed", t);
        }
    }

    public static void logToolCall(String conversationId, String toolId, JSONObject args, String status, String detail) {
        try {
            JSONObject data = base(conversationId, "tool_call");
            data.put("toolId", toolId != null ? toolId : "");
            data.put("status", status != null ? status : "unknown");
            data.put("detail", truncateForLog(detail != null ? detail : "", 8000));
            data.put("args", args != null ? args : new JSONObject());
            writeLine(data);
        } catch (Throwable t) {
            log.debug("Starlogue audit: logToolCall failed", t);
        }
    }

    /**
     * Provider-native chain-of-thought / thinking blocks (never fabricated).
     */
    public static void logModelThinking(String conversationId, String text) {
        if (!LunaSettingHelper.getBoolean("starlogue_audit_conversation_log", false)) return;
        if (!LunaSettingHelper.getBoolean("starlogue_audit_include_thinking", false)) return;
        try {
            JSONObject data = base(conversationId, "model_thinking");
            String t = text != null ? text : "";
            if (t.length() > 32000) t = t.substring(0, 32000) + "...(truncated)";
            data.put("text", t);
            writeLine(data);
        } catch (Throwable t) {
            log.debug("Starlogue audit: logModelThinking failed", t);
        }
    }

    /** Redacted LLM metadata (usage, timing) — no secrets. */
    public static void logLlmMeta(String conversationId, String subEvent, JSONObject payload) {
        if (!LunaSettingHelper.getBoolean("starlogue_audit_conversation_log", false)) return;
        if (!LunaSettingHelper.getBoolean("starlogue_audit_include_raw_llm", false)) return;
        try {
            JSONObject data = base(conversationId, "llm_meta");
            data.put("subEvent", subEvent != null ? subEvent : "");
            data.put("payload", payload != null ? payload : new JSONObject());
            writeLine(data);
        } catch (Throwable t) {
            log.debug("Starlogue audit: logLlmMeta failed", t);
        }
    }

    private static JSONObject base(String conversationId, String eventType) throws org.json.JSONException {
        JSONObject data = new JSONObject();
        data.put("timestamp", System.currentTimeMillis());
        data.put("conversationId", conversationId != null ? conversationId : "");
        data.put("eventType", eventType);
        return data;
    }

    private static void writeLine(JSONObject json) {
        if (!LunaSettingHelper.getBoolean("starlogue_audit_conversation_log", false)) return;
        File f;
        synchronized (LOCK) {
            f = (cachedFile != null) ? cachedFile : (cachedFile = resolveWritableFile());
        }
        if (f == null) return;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) return;
        } catch (Throwable ignored) { return; }

        try (FileOutputStream fos = new FileOutputStream(f, true);
             OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            w.write(json.toString());
            w.write('\n');
            w.flush();
        } catch (Throwable t) {
            log.debug("Starlogue audit: writeLine failed for " + f, t);
        }
    }

    private static File resolveWritableFile() {
        String userDir = System.getProperty("user.dir", ".");
        String home = System.getProperty("user.home", ".");
        String tmp = System.getProperty("java.io.tmpdir", "/tmp");
        List<File> candidates = new ArrayList<File>(6);
        candidates.add(new File(userDir, "saves/common/" + LOG_NAME));
        candidates.add(new File(home, ".starsector/saves/common/" + LOG_NAME));
        candidates.add(new File(home, "starsector/saves/common/" + LOG_NAME));
        candidates.add(new File(home, ".local/share/starsector/saves/common/" + LOG_NAME));
        candidates.add(new File(userDir, LOG_NAME));
        candidates.add(new File(tmp, LOG_NAME));
        for (File c : candidates) {
            if (tryOpenAppend(c)) return c;
        }
        return null;
    }

    private static boolean tryOpenAppend(File f) {
        if (f == null) return false;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) return false;
            new FileOutputStream(f, true).close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
