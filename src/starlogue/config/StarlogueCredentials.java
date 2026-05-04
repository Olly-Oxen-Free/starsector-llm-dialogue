package starlogue.config;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.json.JSONTokener;
import starlogue.debug.DebugSessionLog;

/**
 * LLM credentials in {@code Starlogue_credentials.json} under the campaign {@code common} tree.
 * Create/read <b>only</b> through {@link SettingsAPI} (including {@link SettingsAPI#writeJSONToCommon});
 * direct {@code java.io.File} access is blocked by the game sandbox.
 */
public final class StarlogueCredentials {

    private static final Logger log = Logger.getLogger(StarlogueCredentials.class);

    private static boolean loggedCredentialSnapshot;
    private static boolean loggedLoadOutcome;
    private static boolean wroteCommonProbe;
    private static boolean loggedAltPathProbe;

    public static final String FILE_NAME = "Starlogue_credentials.json";

    private static final String[] FILE_NAME_ALIASES = {
        "Starlogue_credentials.json",
        "starlogue_credentials.json",
        "Starlogue_Credentials.json",
    };

    private StarlogueCredentials() {}

    /**
     * If no credentials file is present, writes a default JSON (Ollama + mistral) the same way
     * other mods materialize their {@code data} in {@code common}. Safe to call once on
     * {@link com.fs.starfarer.api.BaseModPlugin#onApplicationLoad()}.
     */
    public static void ensureDefaultFileInCommon() {
        try {
            SettingsAPI s = Global.getSettings();
            if (s == null) return;
            for (String n : FILE_NAME_ALIASES) {
                if (s.fileExistsInCommon(n)) {
                    return;
                }
            }
            JSONObject o = new JSONObject();
            o.put("_comment",
                "LLM: provider ollama = local (install Ollama, default URL). For OpenRouter etc. set starlogue_provider, starlogue_api_key, starlogue_model.");
            o.put("starlogue_provider", "ollama");
            o.put("starlogue_api_key", "");
            o.put("starlogue_model", "mistral");
            o.put("starlogue_endpoint", "http://localhost:11434/v1");
            s.writeJSONToCommon(FILE_NAME, o, false);
            log.info("Starlogue: created " + FILE_NAME
                + " in saves/common (ollama/mistral). Start Ollama for local, or edit file for a cloud API key.");
        } catch (Throwable t) {
            log.warn("Starlogue: could not create default " + FILE_NAME + ": " + t.getClass().getSimpleName()
                + " " + t.getMessage());
        }
    }

    /**
     * Parsed {@code Starlogue_credentials.json}, or {@code null} if missing/unreadable.
     * Prefer {@link LlmBackendConfig#load()} for LLM settings so all fields share one parse.
     */
    public static JSONObject getCredentialsObject() {
        return loadRoot();
    }

    /**
     * @return trimmed non-empty string, or {@code null} if absent, blank, file missing, or parse error
     */
    public static String getIfPresent(String lunaFieldId) {
        JSONObject root = loadRoot();
        if (root == null) return null;
        String s = LlmBackendConfig.stringField(root, lunaFieldId);
        return s.isEmpty() ? null : s;
    }

    private static JSONObject loadRoot() {
        try {
            SettingsAPI s = Global.getSettings();
            if (s == null) {
                if (!loggedLoadOutcome) {
                    loggedLoadOutcome = true;
                    log.warn("Starlogue: credentials read skipped (Global.getSettings() is null)");
                }
                return null;
            }

            if (!loggedLoadOutcome) {
                StringBuilder sb = new StringBuilder();
                sb.append("Starlogue: credentials alias visibility:");
                for (String n : FILE_NAME_ALIASES) {
                    boolean ex = false;
                    try { ex = s.fileExistsInCommon(n); } catch (Throwable ignored) { }
                    sb.append(" [").append(n).append("=").append(ex).append("]");
                }
                log.info(sb.toString());
            }
            logAlternativePathProbe(s);

            JSONObject best = null;
            String bestSource = null;

            for (String fileName : FILE_NAME_ALIASES) {
                if (!s.fileExistsInCommon(fileName)) {
                    continue;
                }
                // Prefer text + UTF-8 BOM strip first. Many editors save "UTF-8 with BOM";
                // readText + stripBom matches the file on disk. readJSONFromCommon can mis-handle
                // BOM on some runs, which previously yielded empty/missing keys → ollama/mistral/empty key.
                try {
                    String raw = s.readTextFileFromCommon(fileName);
                    if (raw != null && !raw.trim().isEmpty()) {
                        String stripped = stripBom(raw);
                        JSONObject root = new JSONObject(new JSONTokener(stripped));
                        logRawFingerprint(fileName, stripped);
                        best = pickBetter(best, root);
                        bestSource = (best == root) ? ("readTextFileFromCommon:" + fileName) : bestSource;
                    }
                } catch (Throwable t) {
                    log.debug("Starlogue: readTextFileFromCommon(" + fileName + "): " + t.getClass().getSimpleName()
                        + " " + t.getMessage());
                }
                try {
                    JSONObject root = s.readJSONFromCommon(fileName, false);
                    if (root == null) {
                        continue;
                    }
                    logRawFingerprint(fileName, null);
                    best = pickBetter(best, root);
                    bestSource = (best == root) ? ("readJSONFromCommon:" + fileName) : bestSource;
                } catch (Throwable t) {
                    log.debug("Starlogue: readJSONFromCommon(" + fileName + "): " + t.getClass().getSimpleName()
                        + " " + t.getMessage());
                }
            }
            // Extra fallback for installs where "common" resolves somewhere unexpected.
            for (String fileName : FILE_NAME_ALIASES) {
                String rel = "saves/common/" + fileName;
                try {
                    String raw = s.loadText(rel);
                    if (raw == null || raw.trim().isEmpty()) continue;
                    String stripped = stripBom(raw);
                    JSONObject root = new JSONObject(new JSONTokener(stripped));
                    log.info("Starlogue: loaded credentials via loadText(" + rel + ")");
                    best = pickBetter(best, root);
                    bestSource = (best == root) ? ("loadText:" + rel) : bestSource;
                } catch (Throwable t) {
                    log.debug("Starlogue: loadText(" + rel + ") failed: " + t.getClass().getSimpleName()
                        + " " + t.getMessage());
                }
            }
            if (best != null) {
                logLoad(bestSource != null && bestSource.startsWith("loadText")
                        ? "loadText"
                        : "commonRead", bestSource != null ? bestSource : "unknown", best);
                maybeWriteProbe(s, bestSource != null ? bestSource : "unknown",
                    bestSource != null && bestSource.startsWith("loadText") ? "loadText" : "commonRead",
                    null, best);
                return finishRoot(best);
            }
        } catch (Throwable t) {
            log.debug("Starlogue: loadRoot: " + t.getMessage());
        }
        if (!loggedLoadOutcome) {
            loggedLoadOutcome = true;
            log.warn("Starlogue: no credentials file found in saves/common aliases; using defaults.");
        }
        return null;
    }

    private static void logRawFingerprint(String fileName, String strippedRaw) {
        if (loggedLoadOutcome) return;
        try {
            boolean hasProvider = strippedRaw != null && strippedRaw.contains("\"starlogue_provider\"");
            boolean hasOpenRouter = strippedRaw != null && strippedRaw.toLowerCase().contains("openrouter");
            boolean hasGemma = strippedRaw != null && strippedRaw.toLowerCase().contains("gemma");
            int rawLen = strippedRaw != null ? strippedRaw.length() : -1;
            log.info("Starlogue: raw credentials fingerprint file=" + fileName
                + " len=" + rawLen
                + " hasProviderKey=" + hasProvider
                + " hasOpenRouterToken=" + hasOpenRouter
                + " hasGemmaToken=" + hasGemma);
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("file", fileName);
                d.put("len", rawLen);
                d.put("hasProviderKey", hasProvider);
                d.put("hasOpenRouterToken", hasOpenRouter);
                d.put("hasGemmaToken", hasGemma);
                DebugSessionLog.log("H_PATH", "StarlogueCredentials.logRawFingerprint", "raw", d.toString());
            } catch (Throwable ignore) { }
            // #endregion
        } catch (Throwable ignore) { }
    }

    /**
     * Prefer whichever candidate looks user-configured:
     * non-empty API key > non-default provider/model > fallback.
     */
    private static JSONObject pickBetter(JSONObject current, JSONObject candidate) {
        if (candidate == null) return current;
        if (current == null) return candidate;
        int c = scoreConfig(current);
        int n = scoreConfig(candidate);
        return n > c ? candidate : current;
    }

    private static int scoreConfig(JSONObject root) {
        LlmBackendConfig.Snapshot s = LlmBackendConfig.fromJson(root);
        int score = 0;
        if (s.apiKey != null && !s.apiKey.isEmpty()) score += 100;
        if (s.provider != null && !"ollama".equals(s.provider)) score += 20;
        if (s.model != null && !"mistral".equalsIgnoreCase(s.model)) score += 10;
        return score;
    }

    private static void maybeWriteProbe(SettingsAPI s, String fileName, String method, String strippedRaw, JSONObject root) {
        if (wroteCommonProbe) return;
        wroteCommonProbe = true;
        try {
            LlmBackendConfig.Snapshot snap = LlmBackendConfig.fromJson(root);
            StringBuilder sb = new StringBuilder();
            sb.append("method=").append(method).append('\n');
            sb.append("file=").append(fileName).append('\n');
            sb.append("provider=").append(snap.provider).append('\n');
            sb.append("model=").append(snap.model).append('\n');
            sb.append("apiKeyChars=").append(snap.apiKey.length()).append('\n');
            if (strippedRaw != null) {
                sb.append("rawLen=").append(strippedRaw.length()).append('\n');
                sb.append("rawHasOpenRouter=").append(strippedRaw.toLowerCase().contains("openrouter")).append('\n');
                sb.append("rawHasGemma=").append(strippedRaw.toLowerCase().contains("gemma")).append('\n');
            }
            s.writeTextFileToCommon("Starlogue_common_probe.txt", sb.toString());
            log.info("Starlogue: wrote Starlogue_common_probe.txt to saves/common");
        } catch (Throwable t) {
            log.debug("Starlogue: failed to write Starlogue_common_probe.txt: " + t.getMessage());
        }
    }

    private static void logAlternativePathProbe(SettingsAPI s) {
        if (loggedAltPathProbe) return;
        loggedAltPathProbe = true;
        try {
            String userDir = System.getProperty("user.dir", "");
            String userHome = System.getProperty("user.home", "");
            log.info("Starlogue: env user.dir=" + userDir + " user.home=" + userHome);
        } catch (Throwable ignored) { }
        String[] candidates = new String[] {
            "saves/common/Starlogue_credentials.json",
            "saves/common/starlogue_credentials.json"
        };
        for (String c : candidates) {
            try {
                String raw = s.loadText(c);
                String stripped = stripBom(raw);
                boolean hasOpenRouter = stripped != null && stripped.toLowerCase().contains("openrouter");
                boolean hasGemma = stripped != null && stripped.toLowerCase().contains("gemma");
                int len = stripped != null ? stripped.length() : -1;
                log.info("Starlogue: alt loadText probe path=" + c + " len=" + len
                    + " hasOpenRouterToken=" + hasOpenRouter + " hasGemmaToken=" + hasGemma);
                // #region agent log
                try {
                    JSONObject d = new JSONObject();
                    d.put("path", c);
                    d.put("len", len);
                    d.put("hasOpenRouterToken", hasOpenRouter);
                    d.put("hasGemmaToken", hasGemma);
                    DebugSessionLog.log("H_PATH", "StarlogueCredentials.logAlternativePathProbe", "alt", d.toString());
                } catch (Throwable ignore) { }
                // #endregion
            } catch (Throwable t) {
                log.info("Starlogue: alt loadText probe path=" + c + " failed: "
                    + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        }
    }

    private static void logLoad(String method, String fileName, JSONObject root) {
        if (loggedLoadOutcome) return;
        loggedLoadOutcome = true;
        LlmBackendConfig.Snapshot snap = LlmBackendConfig.fromJson(root);
        log.info("Starlogue: credentials loaded via " + method
            + " file=" + fileName
            + " provider=" + snap.provider
            + " model=" + snap.model
            + " apiKeyChars=" + snap.apiKey.length());
    }

    private static JSONObject finishRoot(JSONObject root) {
        try {
            if (!loggedCredentialSnapshot) {
                loggedCredentialSnapshot = true;
                // #region agent log
                try {
                    LlmBackendConfig.Snapshot snap = LlmBackendConfig.fromJson(root);
                    String prov = snap.provider;
                    if (prov.length() > 32) prov = prov.substring(0, 32) + "...";
                    JSONObject d = new JSONObject();
                    d.put("apiKeyLen", snap.apiKey.length());
                    d.put("modelLen", snap.model.length());
                    d.put("endpointLen", snap.customEndpoint.length());
                    d.put("hasProviderKey", root.has("starlogue_provider"));
                    d.put("providerHead", prov);
                    DebugSessionLog.log("H_CRED", "StarlogueCredentials.loadRoot", "parsed", d.toString());
                } catch (Throwable ignore) { }
                // #endregion
            }
        } catch (Throwable ignore) { }
        return root;
    }

    private static String stripBom(String raw) {
        if (raw == null) return null;
        if (raw.isEmpty()) return raw;
        if (raw.charAt(0) == '\uFEFF') {
            return raw.substring(1);
        }
        return raw;
    }

    public static void clearCacheForTests() {
    }
}
