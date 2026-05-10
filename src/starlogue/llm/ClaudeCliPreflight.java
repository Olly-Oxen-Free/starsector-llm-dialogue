package starlogue.llm;

import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Preflight checks for the {@code claude} CLI before opening a dialog session.
 *
 * <p>Runs two probes:
 * <ol>
 *   <li>{@code <cliPath> --version} — CLI is installed and on PATH.</li>
 *   <li>{@code <cliPath> -p --output-format json "ping"} — CLI is authenticated.</li>
 * </ol>
 *
 * <p>Results are cached per CLI path for {@link #CACHE_TTL_MS} (5 minutes) to avoid
 * re-probing on every dialog open within the same play session.
 *
 * <p>Auth detection relies on the C-0 spike finding: when not authenticated, the CLI
 * emits {@code is_error:true} and a {@code result} field containing "Not logged in"
 * on stdout (not stderr) when using {@code --output-format json}.
 */
public class ClaudeCliPreflight {

    private static final Logger log = Logger.getLogger(ClaudeCliPreflight.class);

    /** Cache TTL: 5 minutes in milliseconds. */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    /** Timeout for the version probe (1s). */
    private static final int VERSION_TIMEOUT_SEC = 1;

    /** Timeout for the auth ping probe (5s). */
    private static final int PING_TIMEOUT_SEC = 5;

    // ── Preflight result ──────────────────────────────────────────────────────

    public enum PreflightResult {
        /** CLI found, authenticated, and ready. */
        OK,
        /** CLI executable not found or not executable. */
        NOT_INSTALLED,
        /** CLI found but not logged in. */
        NOT_AUTHENTICATED,
        /** CLI found but preflight failed for an unknown reason. */
        UNKNOWN_ERROR
    }

    // ── Cache ─────────────────────────────────────────────────────────────────

    private static final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    private static final class CachedResult {
        final PreflightResult result;
        final String detail;
        final long timestamp;

        CachedResult(PreflightResult result, String detail) {
            this.result    = result;
            this.detail    = detail;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isStale() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    private ClaudeCliPreflight() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Run the preflight for the given CLI path. Result is cached for 5 minutes.
     *
     * @param cliPath path to the {@code claude} executable (e.g. "claude" for PATH lookup)
     * @return the preflight result
     */
    public static PreflightResult check(String cliPath) {
        if (cliPath == null || cliPath.isEmpty()) cliPath = "claude";

        CachedResult cached = cache.get(cliPath);
        if (cached != null && !cached.isStale()) {
            log.debug("ClaudeCliPreflight: cache hit for '" + cliPath + "' → " + cached.result);
            return cached.result;
        }

        PreflightResult result = runPreflight(cliPath);
        cache.put(cliPath, new CachedResult(result, ""));
        return result;
    }

    /**
     * Returns a user-friendly error message for a non-OK result.
     */
    public static String friendlyMessage(PreflightResult result, String cliPath) {
        switch (result) {
            case NOT_INSTALLED:
                return "Claude CLI not found at '" + cliPath + "'. "
                    + "Install it from: https://docs.anthropic.com/en/docs/claude-code";
            case NOT_AUTHENTICATED:
                return "Claude CLI is not signed in. "
                    + "Run `claude` once in a terminal to log in, then come back.";
            case UNKNOWN_ERROR:
                return "Claude CLI preflight failed. "
                    + "Check the Starsector log for details.";
            default:
                return "";
        }
    }

    /**
     * Invalidate the cached result for the given CLI path (e.g. after user re-authenticates).
     */
    public static void invalidate(String cliPath) {
        if (cliPath != null) cache.remove(cliPath);
    }

    // ── Internal probes ───────────────────────────────────────────────────────

    private static PreflightResult runPreflight(String cliPath) {
        // Probe 1: version check
        PreflightResult versionResult = probeVersion(cliPath);
        if (versionResult != PreflightResult.OK) {
            log.warn("ClaudeCliPreflight: version probe failed for '" + cliPath + "' → " + versionResult);
            return versionResult;
        }

        // Probe 2: auth ping
        PreflightResult authResult = probeAuth(cliPath);
        if (authResult != PreflightResult.OK) {
            log.warn("ClaudeCliPreflight: auth probe failed for '" + cliPath + "' → " + authResult);
        } else {
            log.info("ClaudeCliPreflight: all probes passed for '" + cliPath + "'");
        }
        return authResult;
    }

    /**
     * Run {@code <cliPath> --version} with a 1-second timeout.
     * Returns OK if exit code is 0; NOT_INSTALLED if not found or times out.
     */
    private static PreflightResult probeVersion(String cliPath) {
        Process proc = null;
        try {
            proc = new ProcessBuilder(cliPath, "--version")
                .redirectErrorStream(true)
                .start();
            // Drain stdout so process doesn't block
            drainStream(proc);
            boolean exited = proc.waitFor(VERSION_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!exited) {
                proc.destroyForcibly();
                log.warn("ClaudeCliPreflight: version probe timed out");
                return PreflightResult.NOT_INSTALLED;
            }
            return proc.exitValue() == 0 ? PreflightResult.OK : PreflightResult.NOT_INSTALLED;
        } catch (IOException e) {
            // File not found or not executable
            log.debug("ClaudeCliPreflight: version probe IOException: " + e.getMessage());
            return PreflightResult.NOT_INSTALLED;
        } catch (Exception e) {
            log.warn("ClaudeCliPreflight: version probe error: " + e.getMessage());
            return PreflightResult.UNKNOWN_ERROR;
        } finally {
            if (proc != null && proc.isAlive()) proc.destroyForcibly();
        }
    }

    /**
     * Run {@code <cliPath> -p --output-format json "ping"} with a 5-second timeout.
     * Parses stdout JSON to detect auth failures (C-0 spike finding).
     */
    private static PreflightResult probeAuth(String cliPath) {
        Process proc = null;
        Thread stderrDrainer = null;
        try {
            proc = new ProcessBuilder(
                cliPath, "-p", "--output-format", "json", "ping")
                .redirectErrorStream(false)
                .start();

            // Drain stderr in background to prevent pipe-buffer deadlock
            final Process p = proc;
            stderrDrainer = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getErrorStream()))) {
                    while (r.readLine() != null) { /* drain */ }
                } catch (IOException ignored) {}
            });
            stderrDrainer.setDaemon(true);
            stderrDrainer.start();

            // Read stdout
            StringBuilder stdoutBuf = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdoutBuf.append(line).append('\n');
                }
            }

            boolean exited = proc.waitFor(PING_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!exited) {
                proc.destroyForcibly();
                log.warn("ClaudeCliPreflight: auth probe timed out after " + PING_TIMEOUT_SEC + "s");
                return PreflightResult.UNKNOWN_ERROR;
            }

            String stdout = stdoutBuf.toString();
            log.debug("ClaudeCliPreflight: auth probe stdout length=" + stdout.length()
                + " exitCode=" + proc.exitValue());

            // C-0 spike: auth errors appear in JSON stdout with is_error:true
            // and result field containing "Not logged in"
            return parseAuthProbeResult(stdout, proc.exitValue());

        } catch (IOException e) {
            log.debug("ClaudeCliPreflight: auth probe IOException: " + e.getMessage());
            return PreflightResult.NOT_INSTALLED;
        } catch (Exception e) {
            log.warn("ClaudeCliPreflight: auth probe error: " + e.getMessage());
            return PreflightResult.UNKNOWN_ERROR;
        } finally {
            if (proc != null && proc.isAlive()) proc.destroyForcibly();
            if (stderrDrainer != null) {
                try { stderrDrainer.join(500); } catch (InterruptedException ignored) {}
            }
        }
    }

    /**
     * Parse the auth probe stdout to determine the result.
     *
     * <p>C-0 spike findings:
     * <ul>
     *   <li>Not authenticated: exit 1, stdout JSON with {@code "is_error":true} and
     *       {@code "result":"Not logged in · Please run /login"}</li>
     *   <li>Authenticated: exit 0 (typically), stdout JSON with {@code "is_error":false}</li>
     * </ul>
     */
    private static PreflightResult parseAuthProbeResult(String stdout, int exitCode) {
        // Primary: check for auth-failure text (stable CLI message from C-0 spike)
        if (stdout.contains("Not logged in") || stdout.contains("Please run /login")) {
            return PreflightResult.NOT_AUTHENTICATED;
        }

        // Secondary: parse JSON is_error field
        if (stdout.contains("\"is_error\":true") || stdout.contains("\"is_error\": true")) {
            return PreflightResult.NOT_AUTHENTICATED;
        }

        // Success: exit 0 and no error markers
        if (exitCode == 0) {
            return PreflightResult.OK;
        }

        // Exit non-zero but no auth marker — unknown error
        log.warn("ClaudeCliPreflight: auth probe exited " + exitCode + " without auth error marker");
        return PreflightResult.UNKNOWN_ERROR;
    }

    /** Drains the process's combined stdout+stderr (use when redirectErrorStream=true). */
    private static void drainStream(Process proc) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream()))) {
            while (r.readLine() != null) { /* drain */ }
        } catch (IOException ignored) {}
    }
}
