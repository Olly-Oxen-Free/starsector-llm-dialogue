package starlogue.debug;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Session NDJSON (debug). No secrets: never pass raw API keys.
 * Writes to the first usable path: dev workspace (if that folder exists), then game-style
 * {@code saves/common/Starlogue_debug_2c61f0.log} fallbacks, then system temp. Also POSTs the same
 * line to the Cursor debug ingest (when reachable) so {@code .cursor/debug-2c61f0.log} can fill
 * in-repo during local development.
 */
public final class DebugSessionLog {

    private static final String DEV_LOG_PATH =
        "/home/jayden-eppcohen/Documents/Projects/GameDev/Modding/Starsector-Mods/.cursor/debug-2c61f0.log";
    private static final String COMMON_NAME = "Starlogue_debug_2c61f0.log";
    private static final String INGEST =
        "http://127.0.0.1:7690/ingest/a7da16bd-0f6f-4f2f-bb24-588b8f96f5b6";
    private static final String SESSION = "2c61f0";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();
    private static File cachedFile;
    private static final Object lock = new Object();

    private DebugSessionLog() {}

    public static void log(String hypothesisId, String location, String message, String dataJson) {
        try {
            long ts = System.currentTimeMillis();
            String data = dataJson == null ? "{}" : dataJson;
            String line = "{\"sessionId\":\"" + SESSION + "\",\"timestamp\":" + ts
                + ",\"hypothesisId\":\"" + esc(hypothesisId) + "\",\"location\":\"" + esc(location)
                + "\",\"message\":\"" + esc(message) + "\",\"data\":" + data + "}\n";
            writeFile(line);
            postIngest(line.trim());
        } catch (Throwable ignored) { }
    }

    private static void writeFile(String line) {
        File f;
        synchronized (lock) {
            f = (cachedFile != null) ? cachedFile : (cachedFile = resolveWritableFile());
        }
        if (f == null) return;
        try {
            File p = f.getParentFile();
            if (p != null && !p.isDirectory() && !p.mkdirs() && !p.isDirectory()) return;
        } catch (Throwable ignored) { return; }
        try (FileOutputStream fos = new FileOutputStream(f, true);
             OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            w.write(line);
            w.flush();
        } catch (Throwable ignored) { }
    }

    private static boolean tryOpenAppend(File f) {
        if (f == null) return false;
        try {
            if (f.equals(new File(DEV_LOG_PATH))) {
                if (f.getParentFile() == null || !f.getParentFile().isDirectory()) return false;
            }
            File par = f.getParentFile();
            if (par != null) {
                if (!par.isDirectory() && !par.mkdirs() && !par.isDirectory()) return false;
            }
            new FileOutputStream(f, true).close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static File resolveWritableFile() {
        List<File> cands = new ArrayList<File>(10);
        File devLog = new File(DEV_LOG_PATH);
        if (devLog.getParentFile() != null && devLog.getParentFile().exists()) {
            cands.add(devLog);
        }
        String userDir = System.getProperty("user.dir", ".");
        String home = System.getProperty("user.home", ".");
        cands.add(new File(userDir, "saves/common/" + COMMON_NAME));
        cands.add(new File(home, ".starsector/saves/common/" + COMMON_NAME));
        cands.add(new File(home, "starsector/saves/common/" + COMMON_NAME));
        cands.add(new File(home, ".local/share/starsector/saves/common/" + COMMON_NAME));
        cands.add(new File(System.getProperty("java.io.tmpdir", "/tmp"), COMMON_NAME));
        for (File f : cands) {
            if (tryOpenAppend(f)) return f;
        }
        return null;
    }

    private static void postIngest(String jsonObjectOneLine) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(INGEST))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .header("X-Debug-Session-Id", SESSION)
                .POST(HttpRequest.BodyPublishers.ofString(jsonObjectOneLine))
                .build();
            HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Throwable ignored) { }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
