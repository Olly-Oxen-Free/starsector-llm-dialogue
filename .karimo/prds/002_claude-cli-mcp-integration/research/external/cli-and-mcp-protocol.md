# External Research: Claude CLI Contract and MCP Protocol

**PRD:** claude-cli-mcp-integration
**Date:** 2026-05-10
**CLI Version on this machine:** 2.1.133 (Claude Code)
**CLI binary:** /usr/bin/claude

---

## Part 1: Claude CLI Invocation Contract

### 1.1 Print Mode (`-p` / `--print`)

**Confirmed behavior (verified on this machine):**

```
claude -p "<prompt>" --output-format json
```

- Prompt is passed as a positional CLI argument (string)
- Multi-line prompts work via shell quoting or heredocs
- Non-interactive: returns result and exits. Exit code 0 on success.
- `--output-format json` produces a single JSON object on stdout

**Key flags for Starlogue integration:**

| Flag | Purpose |
|------|---------|
| `--output-format json` | Machine-readable single JSON object result |
| `--output-format stream-json` | Streaming newline-delimited JSON (for latency) |
| `--mcp-config <file>` | Load MCP server configuration |
| `--strict-mcp-config` | Only use servers from --mcp-config; ignore ~/.claude servers |
| `--dangerously-skip-permissions` | Skip tool permission prompts (required for non-interactive use) |
| `--system-prompt <text>` | Override system prompt |
| `--append-system-prompt <text>` | Append to default system prompt |
| `--no-session-persistence` | Don't save session to disk |
| `--bare` | Minimal mode — DOES NOT work with OAuth/subscription auth |
| `--model <name>` | Override model selection |
| `--tools ""` | Disable all built-in tools |

**Verified JSON output schema:**

```json
{
  "type": "result",
  "subtype": "success",
  "is_error": false,
  "api_error_status": null,
  "duration_ms": 7608,
  "duration_api_ms": 6349,
  "num_turns": 3,
  "result": "<assistant text content>",
  "stop_reason": "end_turn",
  "session_id": "ba24aead-...",
  "total_cost_usd": 0.246,
  "usage": {
    "input_tokens": 6,
    "cache_creation_input_tokens": 39407,
    "cache_read_input_tokens": 0,
    "output_tokens": 8,
    "service_tier": "standard"
  },
  "modelUsage": {
    "claude-opus-4-7": {
      "inputTokens": 6,
      "outputTokens": 8,
      "cacheReadInputTokens": 20329,
      "cacheCreationInputTokens": 16948,
      "costUSD": 0.116
    }
  },
  "permission_denials": [],
  "terminal_reason": "completed"
}
```

Key fields for Starlogue:
- `result` — the assistant's final text response
- `is_error` — boolean, true on failure
- `num_turns` — includes tool call round-trips (1 text-only, 3 with one tool call)
- `duration_ms` — total wall time including MCP server startup
- `usage` — token accounting (not directly useful but loggable)

### 1.2 MCP Tool Call Behavior

**Verified experimentally:** With an MCP server registered via `--mcp-config`, Claude CLI:
1. Starts the MCP server subprocess
2. Runs initialize handshake
3. Fetches `tools/list`
4. Sends tools to LLM as native tool schemas
5. When LLM requests a tool call, routes `tools/call` to MCP server
6. MCP result fed back as tool_result to LLM
7. LLM generates final text response
8. CLI exits, MCP server subprocess receives stdin close + SIGTERM

The final JSON response contains only `result` (text) — tool calls are NOT surfaced in `--output-format json`. Tool results are consumed internally by the CLI.

**Permission model:** Without `--dangerously-skip-permissions`, tool calls prompt for user confirmation. In `-p` mode, this results in `"Need permission grant."` response. **Must use `--dangerously-skip-permissions` for programmatic MCP tool invocation.**

### 1.3 Session and Multi-Turn Handling

`claude -p` is stateless per invocation. There is no multi-turn session continuation in print mode without `--resume <session-id>`. However, within a single `-p` invocation, multiple tool call round-trips happen transparently (num_turns reflects this).

For Starlogue: each dialog exchange is one `claude -p` call. Conversation history must be serialized into the prompt or system prompt by Starlogue's `ClaudeCliClient`.

### 1.4 Exit Codes

| Condition | Exit Code | Notes |
|-----------|-----------|-------|
| Success | 0 | `is_error: false` in JSON |
| Auth error (--bare mode) | 0 | `is_error: true`, result = "Not logged in..." |
| Auth error (normal) | Likely non-zero | Not tested — user is authenticated |
| Binary not found | 127 | Shell convention |
| Timeout (process killed) | Non-zero | OS-level kill |

**Note:** Auth errors with `--bare` mode return exit 0 but `is_error: true`. Normal OAuth authentication errors need testing against a logged-out state. Check `claude auth status` for structured auth state.

**`claude auth status` output (verified):**
```json
{
  "loggedIn": true,
  "authMethod": "claude.ai",
  "apiProvider": "firstParty",
  "email": "jeppcohen@gmail.com"
}
```
This is machine-readable. ClaudeCliClient can run `claude auth status --output-format json` as a preflight check.

### 1.5 Security: --strict-mcp-config and ~/.claude Auto-Discovery

**`--strict-mcp-config` confirmed behavior:** When provided alongside `--mcp-config`, Claude CLI uses only the specified MCP servers. Known bug (github.com/anthropics/claude-code#14490): the flag does not override `disabledMcpServers` entries in `~/.claude.json`. However, it does prevent auto-loading of servers from `~/.claude/` settings.

**Recommendation:** Use both `--strict-mcp-config` AND `--tools ""` to additionally disable all built-in tools (Bash, Read, Write, etc.), limiting the CLI's capabilities to only Starlogue MCP tools.

Invocation template:
```
claude -p
  --output-format json
  --mcp-config <tempfile>
  --strict-mcp-config
  --dangerously-skip-permissions
  --tools ""
  --no-session-persistence
  --system-prompt "<persona>"
  "<prompt>"
```

### 1.6 Latency (Measured)

| Scenario | Wall time | Notes |
|----------|-----------|-------|
| `claude -p "ok"` cold | ~6.1s | First call, no cache |
| `claude -p "ok"` warm | ~4.7s | Second call, partial cache |
| `claude -p` + MCP server + 1 tool call | ~7.2s | Including MCP server subprocess spawn |
| `claude -p --bare "ok"` | ~0.55s | Fails with OAuth auth — unusable |

**The ~5-7s total latency per dialog turn is the primary UX risk.** This includes:
- Node.js process startup (~0.3s for a JS MCP server; less for a pre-running HTTP server)
- CLI initialization (~1s)
- API round-trip (~3-5s for claude-opus-4 with cache)
- MCP tool call round-trip (~50-200ms for local IPC)

---

## Part 2: MCP Protocol Minimum Surface

Source: modelcontextprotocol.io/specification/2025-03-26

### 2.1 Required Methods

A minimal MCP server for Starlogue must implement:

**Lifecycle:**

1. `initialize` — server MUST respond with `protocolVersion`, `capabilities`, `serverInfo`
   ```json
   {
     "protocolVersion": "2024-11-05",
     "capabilities": {"tools": {}},
     "serverInfo": {"name": "starlogue", "version": "1.0"}
   }
   ```

2. `notifications/initialized` — incoming notification from client; server MUST be ready after this. No response required.

**Tools:**

3. `tools/list` — server returns array of tool definitions
   ```json
   {
     "tools": [{
       "name": "speak",
       "description": "...",
       "inputSchema": {"type": "object", "properties": {...}, "required": [...]}
     }]
   }
   ```

4. `tools/call` — server executes tool and returns result
   ```json
   {
     "content": [{"type": "text", "text": "result text"}],
     "isError": false
   }
   ```

**Optional but recommended:**
- `ping` — respond with `{}` to keep connection alive
- Error response for unknown methods: JSON-RPC error `-32601` (method not found)

### 2.2 Transport

MCP supports three transports:

| Transport | Claude CLI Support | Notes |
|-----------|-------------------|-------|
| stdio | Yes — via `command`/`args` in mcp-config | Spawns server as subprocess |
| HTTP (Streamable) | Yes — via `url` in mcp-config | Server listens on localhost port |
| SSE (legacy) | Deprecated in 2025-03-26 spec | Avoid |

**Recommended: HTTP transport** — allows server to run inside Starlogue JVM with direct game-object access, avoiding the IPC problem of stdio (which requires a separate JVM process).

MCP config for HTTP:
```json
{
  "mcpServers": {
    "starlogue": {
      "url": "http://localhost:25800/mcp"
    }
  }
}
```

### 2.3 Tool Result Types

| Type | When to use |
|------|-------------|
| `text` | String result from action (e.g., "Fleet jumped to Ancyra") |
| `image` | Not needed by Starlogue |
| `embedded resource` | Not needed |

Starlogue only needs `text` content results.

### 2.4 Error Signaling

Two mechanisms:
1. **Protocol error** (JSON-RPC error object) — for tool not found, bad arguments
2. **Tool execution error** (`isError: true` in result) — for action failed, null fleet, etc.

Starlogue should use `isError: true` with a descriptive message when `StarlogueAction.execute()` returns an error. The LLM will see this and can respond appropriately in-character.

### 2.5 Concurrency

Starlogue's dialog is single-conversation. Within a single `-p` invocation, Claude CLI may make multiple sequential tool calls (num_turns shows this). The spec does not guarantee parallel tool calls in `tools/call` requests — sequential is standard. The MCP server does not need to be fully reentrant for Starlogue's use case, but the game-thread queue bridge must handle queued requests.

### 2.6 Lifecycle and Shutdown

For HTTP transport: Claude CLI closes the HTTP connection when `-p` invocation completes. The server can remain running (for the next turn) or be shut down.

**Recommended server lifecycle:** Start HTTP MCP server when dialog opens, keep running for the duration of the dialog (reuse across turns), shut down when dialog closes or `LlmDispatcher.cancel()` is called.

---

## Part 3: Java MCP SDK Evaluation

### 3.1 Official SDK

**`io.modelcontextprotocol.sdk:mcp`** — github.com/modelcontextprotocol/java-sdk

Latest stable: `0.10.0` (Maven Central). Latest listed: `2.0.0-M2` (milestone, avoid).

**Dependencies of `mcp:0.10.0`:**
- `com.fasterxml.jackson.core:jackson-databind` — ~1.7 MB jar
- `io.projectreactor:reactor-core` — ~1.9 MB jar
- `org.slf4j:slf4j-api` — small
- `jakarta.servlet:jakarta.servlet-api` — compile-scope only

**Total added jar weight: ~4.0 MB** (mcp 256KB + reactor-core 1.9MB + jackson-databind 1.7MB + slf4j).

**Classpath conflict risk:**
- Starsector bundles `log4j 1.2` — `slf4j-api` requires a binding. Use `slf4j-simple` or `slf4j-log4j12` adapter, or let it fall back to no-op. Not a hard conflict.
- Jackson: Starsector does NOT bundle Jackson (verified: `jar tf Starlogue.jar` shows no Jackson). Safe to bundle.
- Reactor-core: uses `sun.misc.Unsafe` for some paths on Java 17. Likely fine but adds initialization overhead.

**Min JDK:** 17 (matches Starlogue's Azul Zulu 17).

**SDK API surface:**
```java
McpServer server = McpServer.sync(transport)
    .serverInfo("starlogue", "1.0.0")
    .capabilities(ServerCapabilities.builder().tools(true).build())
    .tools(
        new McpServerFeatures.SyncToolSpecification(
            new Tool("speak", "Speak to player", schema),
            (exchange, args) -> new CallToolResult(List.of(new TextContent("ok")), false)
        )
    )
    .build();
```

The SDK is Spring-centric (uses Project Reactor types). For HTTP transport it expects Spring WebFlux or Tomcat. This is too heavy.

**Recommendation: Do NOT use the official Java SDK.** The Spring/Reactor dependency chain is incompatible with Starlogue's zero-framework deployment model.

### 3.2 Alternative: Hand-Written Minimal MCP Server

MCP over HTTP is JSON-RPC 2.0. The protocol surface is small enough to implement manually:

- HTTP server: `com.sun.net.httpserver.HttpServer` (JDK built-in, zero deps)
- JSON: `org.json` (already bundled in Starlogue.jar via Starsector)
- Protocol: ~200 lines of Java to handle `initialize`, `tools/list`, `tools/call`

**This is the recommended approach.** Proven by live test: a 30-line Node.js MCP server was sufficient for Claude CLI to discover and call tools correctly.

Total new code: ~500 lines Java. Zero new jar dependencies.

### 3.3 Community Alternatives

No widely-adopted lightweight Java MCP server libraries were found at research time (2026-05). The ecosystem is young. Stick with the hand-written approach.
