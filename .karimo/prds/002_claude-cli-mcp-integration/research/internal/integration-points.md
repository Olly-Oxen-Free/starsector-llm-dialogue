# Internal Research: Integration Points

**PRD:** claude-cli-mcp-integration
**Date:** 2026-05-10
**Scope:** Where ClaudeCliClient plugs into existing Starlogue LLM infrastructure

---

## 1. LLMClient Interface

`src/starlogue/llm/LLMClient.java` — single method:

```java
LLMResponse complete(LLMRequest request) throws Exception;
```

**Assessment:** The interface is sufficient as-is. `ClaudeCliClient` implements it. The method is already expected to block (javadoc says "run on daemon thread"), which matches a subprocess invocation. No new interface methods needed.

The existing `LLMRequest` carries `messages`, `tools`, `model`, `temperature`, `maxTokens`. For the CLI provider:
- `messages` must be serialized into a prompt string or system-prompt + turn history
- `tools` are NOT sent to the CLI — the MCP server registers them natively; the field is ignored in `ClaudeCliClient.complete()`
- `model` is ignored (Claude CLI chooses the model from the user's subscription plan, though `--model` flag exists if needed)
- `temperature` and `maxTokens` have no direct CLI equivalent in `--print` mode (no flags for these in current CLI v2.1.133)

**Decision point for planning:** How to serialize multi-turn `messages` list into a single `claude -p` invocation. Options:
1. Collapse to single-string prompt (loses history structure)
2. Use `--system-prompt` for the system message + a combined user turn
3. Send only the last user turn (stateless — LLM has no history)

Option 3 is likely correct: the CLI is one-shot per invocation, and the conversation history is managed by the MCP server's tool call results feeding back into the next turn. The system prompt can carry persona/context.

---

## 2. LLMResponse

`src/starlogue/llm/LLMResponse.java`:

```java
public final String content;       // assistant text
public final List<LLMToolCall> toolCalls;  // tool invocations
public final String reasoning;     // nullable, chain-of-thought
public final String usageSummary;  // nullable, token counts
```

**For ClaudeCliClient:** The `--output-format json` response has `"result"` (string) for text content. Tool calls are NOT in the JSON response — they are executed by the CLI via MCP before the response is returned. The JSON response only has the final assistant text after all tool calls are resolved.

This is the fundamental difference from all existing clients: **ClaudeCliClient never returns `toolCalls` in `LLMResponse`**. All tool execution happens inside the CLI subprocess, mediated by the MCP server. Starlogue's dialog plugin loop (`executeToolCall()`) is bypassed for this provider — the game-thread tool effects are driven by the MCP server's `tools/call` handler instead.

The `result` field of the JSON response goes into `LLMResponse.content`. `usageSummary` can be populated from the `usage` field. `reasoning` is not exposed by the CLI JSON output.

---

## 3. LlmDispatcher

`src/starlogue/llm/LlmDispatcher.java`:

- `dispatch()` spawns a daemon thread, calls `factory.create(backend).complete(request)`
- On success, stores `LLMResponse` in `AtomicReference<LLMResponse> pending`
- Game thread polls via `poll()` each frame
- `cancel()` sets volatile flag, interrupts thread, drains atomics

**ClaudeCliClient.complete() behavior:**
- Spawns `claude -p ... --mcp-config <tempfile> --strict-mcp-config --dangerously-skip-permissions --output-format json "<prompt>"` via `ProcessBuilder`
- Starts the in-process MCP server (but this is complex — see Section 5 below)
- Blocks on `process.waitFor()` or stdout read
- Returns `LLMResponse(result, emptyList())`

**Cancel integration:** The background thread can call `process.destroyForcibly()` when `cancelled` is set. This mirrors existing `Thread.interrupt()` behavior but for a subprocess. Need to hold a `volatile Process` reference to destroy it from `cancel()`.

**Timeout:** No `maxTokens` equivalent, but the dispatcher's existing per-backend timeout concept can be a 60s wall-clock timeout via `process.waitFor(60, TimeUnit.SECONDS)`.

---

## 4. ProviderFactory

`src/starlogue/llm/ProviderFactory.java`:

Add one branch to `create()`:

```java
} else if ("claude-cli".equals(b.provider)) {
    return new ClaudeCliClient(b.customEndpoint); // customEndpoint = optional binary path
}
```

`b.customEndpoint` repurposed as the binary path (default: `"claude"`). No API key field needed — `b.apiKey` is empty/ignored.

`LlmBackendConfig.normalizeProvider()` needs `"claude-cli"` added to its known-providers list (currently: openai, anthropic, openrouter, ollama, custom, xai). Without this, the provider string gets normalized to `"ollama"`.

---

## 5. The MCP Server Problem

This is the hardest integration question. Three architectures are possible:

### Option A: In-Process Java MCP Server (Preferred)

The MCP server runs as a Java object in Starlogue's JVM. Claude CLI communicates with it via stdio (or HTTP on localhost). The CLI subprocess's stdin/stdout are connected to the MCP server's I/O handler.

For stdio transport, the CLI must be told to spawn the MCP server as a subprocess. This means `--mcp-config` points to a config like:
```json
{
  "mcpServers": {
    "starlogue": {
      "command": "java",
      "args": ["-cp", "/path/to/Starlogue.jar", "starlogue.mcp.McpServerMain"]
    }
  }
}
```

This means the MCP server runs in a **second JVM** — not truly "in-process" with the game. The game's object state is not directly accessible from the MCP JVM. This architecture requires IPC between the game JVM and the MCP JVM (e.g., local socket, file, or shared memory).

### Option B: HTTP MCP Server on Localhost

Starlogue starts an HTTP MCP server on `localhost:<port>` when the dialog opens. The `--mcp-config` points to this server via `url`. The MCP server thread runs inside the Starlogue JVM with direct access to game state.

```json
{
  "mcpServers": {
    "starlogue": {
      "url": "http://localhost:25800/mcp"
    }
  }
}
```

HTTP/SSE transport is supported by Claude CLI. This gives direct game-object access from the server handler (because it runs in the same JVM), but requires an HTTP server dependency. A minimal HTTP server (e.g., `com.sun.net.httpserver.HttpServer` — built into JDK, zero deps) can serve MCP over HTTP.

**Recommended: Option B with built-in JDK HttpServer.**

`com.sun.net.httpserver.HttpServer` is available in JDK 17 with no external dependencies. It supports the SSE transport needed for MCP streaming. No Netty, no Spring needed.

### Option C: Prompt-Only, No MCP

Strip tools entirely. Send a text-only prompt. The LLM responds in free text. Starlogue parses the action from the text. This was rejected by the user — documented here for completeness.

---

## 6. Game-Thread Safety for MCP Tool Calls

When the MCP server receives a `tools/call` request from the CLI, it runs on the HttpServer thread (not the game thread). `StarlogueAction.execute()` must only be called from the game thread.

Pattern needed:
- MCP `tools/call` handler enqueues the request into a `BlockingQueue<McpToolRequest>`
- Game thread polls the queue in `advance()` (same place it polls `LlmDispatcher.poll()`)
- Game thread executes the action and enqueues the result into `BlockingQueue<McpToolResult>`
- MCP handler blocks on the result queue (with timeout)

This is analogous to the existing `AtomicReference` pattern in `LlmDispatcher` but bidirectional.

**Risk:** If `advance()` is not called while the MCP handler is blocking for a result, deadlock occurs. In practice, `advance()` is called every frame (~20fps in Starsector), so the handler should get a result within ~50ms assuming the action enqueues quickly. The MCP handler should use a 5s timeout and return `isError: true` if no result arrives.

---

## 7. Conversation History Serialization

`LLMRequest.messages` is `List<Map<String, Object>>` with roles: `system`, `user`, `assistant`. For the CLI provider, this must be flattened to:
- `--system-prompt` = the system message content
- Prompt argument = the last user message content (or a synthesized multi-turn history)

Multi-turn history can be embedded in the prompt as a formatted string, or omitted entirely (CLI is one-shot). Since tool calls are now resolved by the MCP server before the response is returned, each CLI invocation is essentially one "thinking step." The conversation history between Starlogue dialog turns needs to be maintained by Starlogue (as it already is in `ConversationHistory`) and re-serialized each turn.

Recommended: pass only the last user turn as the prompt, prepend prior assistant turns as context in `--append-system-prompt`. This avoids complex multi-message serialization.

---

## 8. Files That Need Changes

| File | Change |
|------|--------|
| `src/starlogue/llm/ProviderFactory.java` | Add `claude-cli` branch |
| `src/starlogue/config/LlmBackendConfig.java` | Add `claude-cli` to `normalizeProvider()` |
| `src/starlogue/llm/ClaudeCliClient.java` | New file — implements `LLMClient` |
| `src/starlogue/mcp/StarlogueMcpServer.java` | New file — HTTP MCP server |
| `src/starlogue/mcp/McpToolBridge.java` | New file — game-thread queue bridge |
| `src/starlogue/dialog/StarlogueDialogPlugin.java` | Poll `McpToolBridge` in `advance()` |
| `build.sh` or build config | No new external deps if using JDK HttpServer |

---

## 9. Existing Retry Chain Compatibility

`LlmDispatcher.completeWithBackendRetry()` has OpenRouter-specific retry predicates. These do not apply to `claude-cli`. The `ClaudeCliClient` should throw typed exceptions:
- `ClaudeCliNotInstalledException` (exit code 127 or binary not found)
- `ClaudeCliNotAuthenticatedException` (stderr contains "Not logged in")
- `ClaudeCliTimeoutException` (process did not exit within timeout)

These will be caught by the dispatcher's general `catch (Exception e)` and trigger fallback to the next backend in the chain.
