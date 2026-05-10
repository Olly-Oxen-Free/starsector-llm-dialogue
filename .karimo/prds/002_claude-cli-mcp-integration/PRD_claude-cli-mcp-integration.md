# PRD: Claude CLI Provider via MCP Bridge

**Slug:** `claude-cli-mcp-integration`
**Created:** 2026-05-10
**Status:** ready
**Author:** jayden-eppcohen

---

## Executive Summary

Add a new LLM provider that lets Claude.ai Pro / Max subscribers use Starlogue without an API key. The mod spawns the local `claude` CLI binary (`claude -p --mcp-config ...`) and bridges Starlogue's 46 game actions to the LLM via an in-process Java MCP server over HTTP-localhost. Streaming output (`--output-format stream-json --include-partial-messages`) masks the 3-5x latency vs the direct API. Zero new third-party dependencies — JSON-RPC server hand-rolled on JDK's `com.sun.net.httpserver.HttpServer`.

## Goals

1. **Subscription parity** — Pro/Max users get equivalent functionality to API-key Anthropic provider.
2. **Tool-call fidelity** — all 46 Starlogue actions remain callable via MCP, executed on the game thread.
3. **Latency mitigation** — streaming partial text feels responsive despite 5-7s total wall-clock per turn.
4. **Zero-dep lift** — hand-roll JSON-RPC; nothing new in the fat jar beyond ~500 LOC.
5. **First-class UX** — auth/install errors are friendly, actionable; preflight detects problems before the player types a message.

## Non-Goals

- No OAuth-token reuse (Anthropic ToS violation; account-ban risk).
- No prompt-coerced JSON tool calling for this provider (existing OpenRouter/Xai pattern; we want first-class MCP tool calls here).
- No support for multi-turn within a single CLI invocation — `claude -p` is stateless per spec; conversation history continues to be passed in the prompt every turn (matches existing pattern).
- No dynamic MCP tool registration from external mods — `ActionContributor` plugins still get their tools surfaced, but only via the existing pool collection path; no new SPI.
- No rewrite of `LLMClient` interface or existing providers.

## Constraints

- Java 17 fat-jar deployment; no Maven at runtime; only existing classpath deps usable (`json.jar`, `log4j 1.2`, `lwjgl`, `starfarer.api.jar`, plus what we already bundle).
- Subprocess spawning unrestricted (Starsector doesn't enforce SecurityManager).
- Game-thread vs background-thread discipline must be preserved — MCP server runs on its own threads; tool execution marshals back to game thread via queue + `advance()` poll (mirror of existing `AtomicReference` pattern).
- `LLMClient` interface stays stable.
- MCP server binds to `127.0.0.1` only on a random ephemeral port per startup.
- `--strict-mcp-config` + `--dangerously-skip-permissions` + `--tools ""` mandatory CLI flags.

## Research Findings

See `research/findings.md` and `research/external/cli-and-mcp-protocol.md` and `research/internal/integration-points.md`. Highlights:

- **Architecture confirmed:** in-JVM HTTP MCP server via JDK `HttpServer`. Hand-rolled JSON-RPC. Tool execution queued + drained on game thread.
- **Latency:** 4.7-7.2s/turn observed (3-5x direct API). Mitigated with streaming partial messages.
- **Mandatory flags:** `--strict-mcp-config --dangerously-skip-permissions --tools "" --output-format stream-json --include-partial-messages`.
- **Three blockers resolved:** streaming approach approved; T-0 spike gates HTTP transport + `--tools ""` MCP coexistence verification.

## Acceptance Criteria

- LunaSettings exposes "Claude CLI" provider option; selecting it requires no API key.
- Player opens dialog → preflight runs (CLI present + authenticated + MCP server bound) → friendly error on any failure.
- Conversation flow works end-to-end: player message → CLI subprocess → Claude calls Starlogue tools via MCP → game state mutates correctly → assistant response streams back.
- Partial assistant text appears in dialog as it generates (within ~1.5s of submit).
- Abort (T-16 from prior PRD) kills the subprocess gracefully and clears the queue.
- `./build.sh` clean; `./build.sh test` pass; manual integration test script demonstrates one full conversation with at least one tool call.

## Risks

- **R1 — `--tools ""` may suppress MCP tools.** If T-0 spike confirms this, fallback options: (a) whitelist via `--tools "mcp__starlogue__*"` if pattern matching is supported; (b) accept built-ins exposed and rely on system prompt to keep Claude focused on Starlogue tools. Either way, surface in spike report before C-2 begins.
- **R2 — HTTP transport may not be supported by the installed CLI version.** Spike confirms or pivots to stdio (heavier: second-JVM with no shared memory; tool bridge becomes IPC).
- **R3 — Subprocess spawn cost amplifies latency on slow hardware.** Worst-case 8-10s/turn on low-end machines. Mitigation: streaming makes early text visible; if unacceptable, document the trade-off in README.
- **R4 — Anthropic CLI updates may break our integration.** CLI is in active development; flags rename. Mitigation: pin minimum tested version in docs; preflight version check warns on unknown major versions.
- **R5 — Auth flow surprises.** First-time users running `claude` get an OAuth browser flow. We can't drive that — we can only detect "not authenticated" and surface instructions.

---

## Implementation Map (12 tasks, 4 waves)

Refer to `tasks.yaml` for per-task spec and acceptance.

**Wave 1 (Spike + Foundation):** C-0 → C-1
**Wave 2 (MCP Server):** C-2 → C-3 → C-4
**Wave 3 (CLI Client + Integration):** C-5 → C-6 → C-8
**Wave 4 (Polish):** C-7, C-9, C-10, C-11
