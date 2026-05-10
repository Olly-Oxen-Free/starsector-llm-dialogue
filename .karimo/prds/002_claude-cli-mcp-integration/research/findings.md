# Research Findings: Claude CLI Integration via MCP Bridge

**Date:** 2026-05-10
**Scope:** New LLM provider for Starlogue using `claude` CLI (Pro/Max subscription auth) with in-process Java MCP server for tool calls.
**Sources:** Claude Code CLI docs, MCP protocol spec, modelcontextprotocol/java-sdk, live testing on this machine.

---

## Executive Summary

- **Architecture:** ClaudeCliClient spawns `claude -p --mcp-config <file> --strict-mcp-config --dangerously-skip-permissions --tools "" --output-format stream-json --include-partial-messages "<prompt>"`. Mod hosts an HTTP-transport MCP server inside the Starlogue JVM via JDK's built-in `com.sun.net.httpserver.HttpServer` — zero new deps. Tool calls flow through MCP and execute on the game thread via a queue/poll bridge.
- **Latency:** 5-7s/turn measured (text-only ~4.7-6s, with one tool call ~7.2s). 3-5x direct API. Mitigation: `--output-format stream-json` + `--include-partial-messages` for incremental display, leveraging existing `LlmDispatcher` polling pattern.
- **`LLMClient` interface unchanged.** Tool execution moves from dialog plugin's `executeToolCall()` to MCP server's `tools/call` handler. `ClaudeCliClient.complete()` returns `toolCalls=[]` always.
- **No new heavyweight deps.** Hand-roll JSON-RPC (~500 LOC). Skip official `io.modelcontextprotocol.sdk:mcp` (Spring/Reactor, ~4MB).
- **Three blockers resolved by user 2026-05-10:** Streaming acceptable for latency; T-0 spike to live-test HTTP transport + `--tools ""` MCP coexistence before committing.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ Starlogue JVM (Starsector mod)                                  │
│                                                                 │
│  ┌─────────────────────┐      ┌──────────────────────────────┐  │
│  │ StarlogueDialog     │      │ McpServer (HTTP, localhost)  │  │
│  │ Plugin              │      │  - tools/list → schema       │  │
│  │  (game thread)      │      │  - tools/call → enqueue      │  │
│  └─────────┬───────────┘      └──────────────┬───────────────┘  │
│            │                                 │                  │
│            │ dispatch                        │ enqueue          │
│            ▼                                 ▼                  │
│  ┌─────────────────────┐      ┌──────────────────────────────┐  │
│  │ LlmDispatcher       │      │ McpToolBridge                │  │
│  │  (background)       │      │  - game-thread queue         │  │
│  └─────────┬───────────┘      │  - drained by advance()      │  │
│            │                  └──────────────┬───────────────┘  │
│            │ ProcessBuilder                  │                  │
│            ▼                                 │                  │
│  ┌─────────────────────────────────────┐    │                   │
│  │ ClaudeCliClient                     │    │                   │
│  │  - spawn claude -p --mcp-config     │    │                   │
│  │  - parse stream-json events         │    │                   │
│  │  - emit partial text to dispatcher  │    │                   │
│  └─────────┬───────────────────────────┘    │                   │
└────────────┼────────────────────────────────┼───────────────────┘
             │ stdin/stdout                   │ HTTP
             ▼                                ▼
        ┌──────────────────────────────────────────┐
        │ claude (subprocess)                      │
        │  - reads --mcp-config file               │
        │  - calls localhost MCP server            │
        │  - streams JSON events to stdout         │
        └──────────────────────────────────────────┘
```

**Two threads inside Starlogue JVM:**
- HTTP MCP server: handles tools/list and tools/call from CLI subprocess
- Game thread: drains tool-call queue via `advance()` polling (mirrors existing `AtomicReference<LLMResponse>` pattern)

**Tool call execution flow:**
1. CLI sends `tools/call` HTTP POST → MCP server thread
2. MCP server enqueues `(toolId, args, resultFuture)` and parks on `resultFuture.get()` with timeout
3. `advance()` on game thread drains queue, invokes `StarlogueAction.execute(ctx, args)`, completes future with result
4. MCP server returns result JSON to CLI
5. CLI continues LLM call with tool result in context

---

## Claude CLI Contract (verified)

### Required flags
- `-p` / `--print` — non-interactive, prompt-and-exit
- `--mcp-config <file>` — register MCP servers for this invocation only
- `--strict-mcp-config` — only allow servers from the explicit config (don't auto-load `~/.claude/`)
- `--dangerously-skip-permissions` — bypass per-tool confirmation prompts (mandatory for programmatic use)
- `--tools ""` — disable built-in tools (Bash/Edit/Read). **Must be live-verified that this preserves MCP tools.**
- `--output-format stream-json` — incremental NDJSON event stream
- `--include-partial-messages` — emit partial text deltas

### Stream JSON event types
- `system` — session metadata (model, tools list)
- `assistant` — partial assistant text (delta) and final
- `tool_use` — Claude calling a tool (informational; the actual call goes through MCP transport)
- `tool_result` — tool returned (informational)
- `result` — final outcome with usage stats

### Exit codes
- 0: success
- non-zero: auth missing, rate limit, network failure, etc. (specific codes need live test in T-0)

### Auth detection
- `claude --version` confirms binary present
- `claude -p "ping"` with timeout: success indicates authenticated session present
- Stderr contains identifiable strings on auth failure ("not authenticated", "login required") — exact strings TBD in T-0

### Stateless invocation
- Each `claude -p` is one-shot; no cross-invocation memory. Conversation history must be passed in the prompt every turn — this is what Starlogue already does with `ConversationHistory`.

---

## MCP Protocol Surface (minimum viable)

JSON-RPC 2.0 over HTTP POST to a single endpoint. Required methods:

- `initialize` — handshake, server returns capabilities (`{tools: {listChanged: false}}`)
- `notifications/initialized` — client confirms (no response)
- `tools/list` — return array of `{name, description, inputSchema}` matching JSON Schema draft-07
- `tools/call` — invoke tool, return `{content: [{type:"text", text:"<result>"}], isError:bool}`

Optional but useful:
- `ping` — liveness check
- `notifications/cancelled` — surface CLI abort to our server (T-16 feature parity)

Not needed: resources, prompts, sampling, completion, logging.

**Single endpoint design:** POST `/mcp` accepts all RPCs. JDK `HttpServer` handles it with one `HttpHandler`.

---

## Java MCP SDK Evaluation

| Approach | Verdict |
|----------|---------|
| `io.modelcontextprotocol.sdk:mcp` (official) | ❌ Pulls Spring + Reactor, ~4MB transitive, unshable cleanly into Starlogue.jar |
| Hand-rolled JSON-RPC | ✅ ~500 LOC, zero new deps. Reuse existing `JsonUtils` (Wave 3) and `org.json` (already in Starsector classpath via `json.jar`) |
| Community alternatives | ⚠️ None mature enough at this date |

**Decision:** Hand-roll. Implementation surface ~3-4 files: `McpServer`, `McpRpcHandler`, `McpToolBridge`, `McpToolSchema`.

---

## Auth & UX Flow

```
User selects "Claude CLI" in LunaSettings → preflight on dialog open:
  1. `claude --version` exits 0?       ── no → "Claude CLI not found. Install: https://docs.anthropic.com/en/docs/claude-code"
  2. `claude -p "test" --print` exit 0? ── no → "Claude CLI not authenticated. Run `claude` once in a terminal to log in."
  3. MCP server bound to localhost?    ── no → "Failed to start MCP bridge. Port collision? See logs."
  4. Probe ok? Show normal dialog.
```

Settings entry: `starlogue_provider: claude_cli`. Optional `starlogue_claude_cli_path` (default: `claude`, lets users override for non-PATH installs).

No API key field needed for this provider.

---

## Performance Estimates

| Metric | Value | Source |
|--------|-------|--------|
| Cold-start (first call after dialog open) | ~6-8s | Researcher live test |
| Warm turn (text only) | ~4.7-6s | Researcher live test |
| Warm turn (1 tool call) | ~7.2s | Researcher live test |
| Direct Anthropic API turn (comparison) | ~1.5-2s | Existing baseline |
| Subprocess spawn cost | ~200-500ms | JVM ProcessBuilder typical |
| MCP HTTP round-trip per tool call | ~5-15ms | localhost loopback |

**Streaming mitigation:** with `--include-partial-messages`, partial assistant text appears within ~1.5s — perceived latency drops to "feels like typing", final completion still ~5-7s.

**Token cost:** Same as native Anthropic provider — tool schemas are sent to Claude either way (~5-7k tokens for full action surface). No regression.

---

## Failure Modes

| Failure | Detection | Recovery |
|---------|-----------|----------|
| `claude` not on PATH | `claude --version` ENOENT | Surface install instructions; disable Send option |
| Not authenticated | exit code + stderr | Surface "run `claude` to log in" |
| Subprocess hangs | Configurable timeout (default 60s) | Kill subprocess, surface timeout to user |
| MCP server port in use | `BindException` on bind | Try next port (5 attempts), then fail loudly |
| Tool call exception (game side) | Exception in `execute()` | Return MCP error response → LLM sees tool error → apologizes |
| User aborts (T-16) | `LlmDispatcher.cancel()` called | Kill subprocess, drain queue with cancellation errors |
| `--tools ""` suppresses MCP tools too | T-0 spike result | Pivot to whitelist `--tools "mcp__starlogue__*"` or accept built-ins exposed |

---

## Security Notes

- **Subprocess spawning is unrestricted in Java 17 by default** — Starsector doesn't enforce a SecurityManager. `Runtime.exec` works.
- **MCP server binds to `127.0.0.1` only** — never `0.0.0.0`. Random ephemeral port per startup; written into the `--mcp-config` file.
- **`--strict-mcp-config` prevents auto-discovery** — Claude CLI won't load any MCP server from `~/.claude/` when this flag is set. Only Starlogue's tools are exposed.
- **`--tools ""` disables built-in tools** — this is the critical security flag that keeps Claude from accessing Bash/Read/Edit. T-0 spike must verify this still allows MCP tools.
- **Temp config file**: write to `Files.createTempFile("starlogue-mcp-", ".json")`, set POSIX 600 perms where supported, delete on subprocess exit.
- **No system-prompt-injection escape**: even if Claude misbehaves, the MCP server only accepts the 46 registered Starlogue tools — anything else returns "unknown tool" error.

---

## Architectural Decisions (locked)

1. **HTTP MCP transport** (not stdio) — keeps server in-JVM, direct game-object access. T-0 spike confirms before deeper work.
2. **Streaming output** (`stream-json` + `--include-partial-messages`) — masks the 5-7s latency.
3. **Hand-rolled JSON-RPC** — no Spring/Reactor.
4. **`LLMClient` interface unchanged** — `ClaudeCliClient.complete()` returns `LLMResponse` with empty `toolCalls`.
5. **Tool side-effects via MCP server** — `executeToolCall()` in dialog plugin is bypassed when provider is `claude_cli`.
6. **Game-thread tool execution** — MCP handler enqueues, `advance()` drains, future completes when execute() finishes.

---

## Recommended Slicing (12 PRD Tasks)

| ID | Title | Complexity | Priority | Depends |
|----|-------|-----------|----------|---------|
| C-0 | Spike: live-test HTTP MCP + `--tools ""` MCP coexistence + identify auth failure stderr strings | 3 | must | — |
| C-1 | Add `claude_cli` provider entry in `LlmBackendConfig` + LunaSettings dropdown + path override field | 2 | must | — |
| C-2 | Hand-rolled MCP JSON-RPC server (`McpServer`, `McpRpcHandler`) on JDK `HttpServer`, localhost-only | 4 | must | C-0 |
| C-3 | `McpToolSchema` — convert Starlogue's existing `EvaluatedActionSet.available` to MCP tool schema (reuse `ConstraintEngine.buildToolsArray` logic) | 2 | must | C-2 |
| C-4 | `McpToolBridge` — game-thread queue + future-based tool execution; drained from `advance()` | 4 | must | C-2 |
| C-5 | `ClaudeCliClient` — implements `LLMClient`; spawns subprocess, writes mcp-config temp file, parses stream-json events | 5 | must | C-2, C-3, C-4 |
| C-6 | Wire `ClaudeCliClient` into `ProviderFactory`; bypass `executeToolCall()` in dialog when provider is claude_cli (toolCalls always empty) | 3 | must | C-5 |
| C-7 | Streaming integration — partial assistant text rendered to dialog as deltas arrive | 3 | should | C-5 |
| C-8 | Auth preflight (CLI present, authenticated) on dialog open; friendly error messages; disable Send if preflight fails | 3 | must | C-1, C-5 |
| C-9 | Abort integration — T-16 cancel kills subprocess gracefully; queue drains with cancellation errors | 2 | should | C-5 |
| C-10 | Manual integration test script (Bash) — spawn one conversation end-to-end, verify tool call flows through MCP, capture latency | 2 | should | C-5 |
| C-11 | Documentation: README section on Claude CLI provider, troubleshooting guide, link to Anthropic Pro/Max info | 2 | could | C-6 |

**Total**: 12 tasks, ~35 complexity points. Wave 1 spike (C-0) is a hard gate.

**Wave plan:**
- **Wave 1 (spike + foundation)**: C-0, C-1
- **Wave 2 (MCP server)**: C-2, C-3, C-4
- **Wave 3 (CLI client + integration)**: C-5, C-6, C-8
- **Wave 4 (polish)**: C-7, C-9, C-10, C-11

---

## Constraints Acknowledged

- Java 17 fat-jar deployment, no Maven at runtime — only deps already on classpath.
- `org.json` (json.jar) already present in Starsector classpath; reuse for JSON-RPC parsing alongside existing `JsonUtils`.
- Game-thread vs background-thread discipline preserved — MCP server runs on its own threads (JDK `HttpServer` defaults), tool execution marshals back to game thread via queue + `advance()` poll.
- `LLMClient` interface stays stable.
- No SecurityManager assumptions — Starsector doesn't enforce one.

---

## Open Items for PRD Interview / Implementation

- **C-0 spike**: actual stderr strings from unauthenticated `claude` invocation; HTTP transport confirmation; `--tools ""` MCP coexistence
- **Conversation history format**: confirm Claude CLI handles long histories cleanly when passed in prompt (might need truncation for extremely long sessions)
- **Streaming UX detail**: do we update existing `WAIT_FRAMES` "thinking" indicator vs replace with partial text? (T-13 already added in-character "{NPC} is thinking…" — likely keep until first delta arrives, then switch to text)
- **Token usage tracking**: stream-json `result` event includes usage stats — surface in audit log?
- **Rate-limit / quota messages**: do Pro/Max subscriptions hit usage caps? Detection + UX
