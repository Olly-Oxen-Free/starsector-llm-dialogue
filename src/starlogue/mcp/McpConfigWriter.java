package starlogue.mcp;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Writes the per-call MCP config temp file that tells the {@code claude} CLI
 * where our in-process MCP server is listening.
 *
 * <p>The config format (HTTP transport, confirmed by C-0 spike):
 * <pre>
 * {"mcpServers":{"starlogue":{"type":"http","url":"http://127.0.0.1:&lt;port&gt;/mcp"}}}
 * </pre>
 *
 * <p>The returned {@link Path} must be deleted in a {@code finally} block by the caller.
 */
public class McpConfigWriter {

    private static final Logger log = Logger.getLogger(McpConfigWriter.class);

    private McpConfigWriter() {}

    /**
     * Write a temp MCP config file pointing at the given MCP server port, including the
     * per-session shared secret as an HTTP header (#16).
     *
     * @param port      the port returned by {@link McpServer#getPort()}
     * @param authToken the per-session token from {@link McpServer#getAuthToken()}; may be null/empty
     * @return path to the temp file; caller is responsible for deletion
     * @throws IOException if the file cannot be created
     */
    public static Path write(int port, String authToken) throws IOException {
        Path tmp = Files.createTempFile("starlogue-mcp-", ".json");
        // Best-effort: restrict to owner-read/write only (POSIX systems)
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(tmp, perms);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX OS (Windows) — skip permission tightening
        } catch (Throwable t) {
            log.debug("McpConfigWriter: could not set POSIX permissions on temp file: " + t.getMessage());
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"mcpServers\":{\"starlogue\":{\"type\":\"http\",")
            .append("\"url\":\"http://127.0.0.1:").append(port).append("/mcp\"");
        if (authToken != null && !authToken.isEmpty()) {
            json.append(",\"headers\":{\"").append(McpServer.AUTH_HEADER).append("\":\"")
                .append(authToken).append("\"}");
        }
        json.append("}}}");
        Files.writeString(tmp, json.toString());
        log.debug("McpConfigWriter: wrote config to " + tmp + " (port=" + port + ", auth="
            + (authToken != null && !authToken.isEmpty() ? "yes" : "no") + ")");
        return tmp;
    }

}
