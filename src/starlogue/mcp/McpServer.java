package starlogue.mcp;

import com.sun.net.httpserver.HttpServer;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Lifecycle wrapper around a JDK {@link HttpServer} that exposes a single
 * POST /mcp endpoint implementing JSON-RPC 2.0.
 *
 * Usage:
 * <pre>
 *   McpToolBridge bridge = new McpToolBridge();
 *   McpServer server = new McpServer(bridge);
 *   int port = server.start();      // binds to 127.0.0.1 on an ephemeral port
 *   ...
 *   server.setSchema(schema);       // wire C-3 schema once available
 *   ...
 *   server.stop();
 * </pre>
 *
 * Binds exclusively to 127.0.0.1 (loopback). The handler rejects any
 * connection not originating from 127.0.0.1 with HTTP 403.
 */
public class McpServer {

    private static final Logger log = Logger.getLogger(McpServer.class);

    private final McpRpcHandler handler;
    private HttpServer httpServer;
    private ExecutorService executor;
    private int port = -1;

    public McpServer(McpToolBridge bridge) {
        this.handler = new McpRpcHandler(bridge);
    }

    /**
     * Bind to 127.0.0.1 on a random ephemeral port and start accepting requests.
     *
     * @return the actual port the server is listening on
     * @throws IOException if the server cannot bind
     */
    public synchronized int start() throws IOException {
        if (httpServer != null) {
            throw new IllegalStateException("McpServer already started on port " + port);
        }

        // Bind to loopback, port 0 → OS assigns ephemeral port
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), /*backlog*/ 8);
        httpServer.createContext("/mcp", handler);
        // Bounded thread pool: tools/call handlers can block up to 10s waiting for the
        // game-thread bridge. Null executor (default) creates one thread per request unbounded
        // — under a misbehaving CLI that opens many parallel tool calls, that grows without
        // limit. Cap at 4 concurrent worker threads; queued requests wait their turn.
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "starlogue-mcp-worker");
            t.setDaemon(true);
            return t;
        });
        httpServer.setExecutor(executor);
        httpServer.start();

        port = httpServer.getAddress().getPort();
        log.info("Starlogue MCP server started on 127.0.0.1:" + port);
        return port;
    }

    /**
     * Stop the server gracefully, waiting up to {@code delaySec} seconds
     * for in-flight requests to complete.
     *
     * @param delaySec seconds to wait before forcibly closing; 0 = immediate
     */
    public synchronized void stop(int delaySec) {
        if (httpServer == null) return;
        log.info("Starlogue MCP server stopping (delay=" + delaySec + "s)...");
        httpServer.stop(delaySec);
        httpServer = null;
        port = -1;
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(delaySec + 1L, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        log.info("Starlogue MCP server stopped.");
    }

    /** Stop with a 1-second graceful delay. */
    public void stop() {
        stop(1);
    }

    /**
     * The port the server is bound to, or -1 if not started.
     */
    public int getPort() {
        return port;
    }

    /**
     * Wire in the tool schema (C-3). May be called after {@link #start()}.
     * Thread-safe: the handler field is volatile.
     */
    public void setSchema(McpToolSchema schema) {
        handler.setSchema(schema);
    }

    /** True if the server has been started and not yet stopped. */
    public synchronized boolean isRunning() {
        return httpServer != null;
    }
}
