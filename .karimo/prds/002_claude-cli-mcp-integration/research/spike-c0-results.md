# C-0 Spike Results — Pre-PRD Validation

**Date:** 2026-05-10
**CLI version:** 2.1.133 (Claude Code)
**Machine:** developer's local Linux

---

## Summary

All three blocker questions resolved. **No issues found.** PRD architecture is viable as designed. One unexpected cost concern surfaced (informational, not blocking).

---

## Test 1 — stdio MCP transport with `--tools ""`

**Setup:** Python script implementing a minimal stdio JSON-RPC MCP server with one `starlogue_echo` tool.

**Command:**
```
claude -p --mcp-config <stdio-config.json> --strict-mcp-config \
  --dangerously-skip-permissions --tools "" --output-format json \
  "Use the starlogue_echo tool with text='hello world' and tell me what it returned."
```

**Result:** ✅ PASS
- Tool discovered, called, result `ECHO: hello world` returned.
- `--tools ""` did NOT block the MCP tool — only built-ins (Bash/Edit/Read) suppressed.
- Latency: **3.147s** (better than research estimate of 7.2s).
- Cost field: `total_cost_usd: 0.116` (informational only — Pro/Max subscribers pay flat, not per-call).

---

## Test 2 — HTTP MCP transport with `--tools ""`

**Setup:** Python `http.server`-based MCP server bound to `127.0.0.1:39337`, accepts `POST /` JSON-RPC.

**mcp-config:**
```json
{"mcpServers":{"starlogue":{"type":"http","url":"http://127.0.0.1:39337/"}}}
```

**Command:** Same as Test 1 but with HTTP config.

**Result:** ✅ PASS
- All 4 RPC methods exchanged cleanly: `initialize`, `notifications/initialized`, `tools/list`, `tools/call`.
- Tool result `HTTP-ECHO: hello over http` flowed back to LLM.
- Latency: **4.503s** (~1.4s overhead vs stdio for HTTP — still within budget).
- CLI sends protocol version `2025-11-25` and client capabilities `{roots:{}, elicitation:{}}` — server ignored, no impact.

**Architecture decision confirmed**: HTTP transport is fully viable. Java MCP server can run in-process inside Starlogue's JVM with direct game-state access. No second JVM needed.

---

## Test 3 — Auth-failure stderr (DEFERRED)

Skipped during pre-PRD spike because rename/restore of `~/.claude/.credentials.json` would disrupt the developer's active Claude session. **C-0 task during execution will run this test in an isolated environment.**

Acceptable to defer because:
- C-8 preflight only needs the matching strings; can be discovered during C-8 implementation by running with a known-bad credentials path.
- No architectural decision depends on the exact stderr format.

---

## Unexpected Finding — Heavy CLI System Prompt

**Observation:** Each `claude -p` call shows `cache_creation_input_tokens: ~16849` plus `cache_read_input_tokens: ~16745`. This is Claude Code's built-in agent system prompt (instructions, tool descriptions for built-ins, skills metadata) — injected on every invocation regardless of `--tools ""`.

**Impact for Pro/Max subscribers:**
- Subscription quotas are usage-based (Anthropic published 5h windows + weekly caps for Pro/Max). Each Starlogue turn consumes ~17k input tokens of "agent overhead" before our actual prompt. A 30-turn conversation = ~500k tokens just for CLI scaffolding.
- This could push casual users toward usage caps faster than they'd expect. **Worth documenting in C-11 README** with realistic conversation count estimates.

**Mitigation options (research only — not in PRD scope):**
- `--bare` mode strips the agent overhead BUT requires `ANTHROPIC_API_KEY` env var, defeating the subscription-auth premise. Not viable here.
- `--system-prompt` overrides Claude Code's default prompt entirely. Untested whether it preserves MCP tool-call behavior; if yes, could trim ~10k tokens per call. **Add as a should-fix follow-up task** (see addendum below).

---

## Default Model = Opus

The CLI defaults to `claude-opus-4-7`. For NPC dialogue this is overkill (cost-aware users would prefer Sonnet or Haiku). The CLI accepts `--model haiku|sonnet|opus|<full-id>`.

**Recommendation:** add a LunaSetting `starlogue_claude_cli_model` (default `sonnet`). Folded into C-1 task scope (was already a "model selector" item but not scoped explicitly).

---

## Findings Affecting PRD Tasks

| Original | Updated |
|----------|---------|
| C-0 spike: live-test HTTP + `--tools ""` + auth stderr | **HTTP and `--tools ""` resolved here.** C-0 scope reduces to: auth-failure stderr capture + minimum-CLI-version verification only. Complexity 3 → 1. |
| C-1: provider config | Add LunaSetting `starlogue_claude_cli_model` (default `sonnet`). Complexity stays 2. |
| C-5: ClaudeCliClient | Confirm: pass `--model <selected>` from config. Already in-scope; just call out explicitly. |
| (NEW) C-12: Test `--system-prompt-file` override for cost reduction | New "could" task. Complexity 2. Preserves MCP behavior verification. |

---

## Sign-Off

PRD `claude-cli-mcp-integration` is **GREEN to proceed**. Architecture confirmed by live test. C-0 task descoped from "verify three things" to "capture auth-failure stderr only".

---

## C-0 Auth-Stderr Capture

**Date:** 2026-05-10
**CLI version:** 2.1.133 (Claude Code)
**Test method:** Moved `~/.claude/.credentials.json` away, ran `timeout 10 claude -p --output-format json "ping" </dev/null`, restored credentials immediately.

**Exit code:** `1`

**stderr:** *(empty — no output)*

**stdout (verbatim JSON):**
```json
{"type":"result","subtype":"success","is_error":true,"api_error_status":null,"duration_ms":43,"duration_api_ms":0,"num_turns":1,"result":"Not logged in · Please run /login","stop_reason":"stop_sequence","session_id":"5f1b9895-0bfb-4bca-bfd3-353a4ad74df3","total_cost_usd":0,"usage":{"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0,"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":0,"ephemeral_5m_input_tokens":0},"inference_geo":"","iterations":[],"speed":"standard"},"modelUsage":{},"permission_denials":[],"terminal_reason":"completed","fast_mode_state":"off","uuid":"d8981354-8105-4e1f-b7b5-60785827e604"}
```

**Key finding:** Auth errors appear on **stdout** (not stderr), embedded in the JSON result field when `--output-format json` is used. The `result` field contains the human-readable message; `is_error: true` is the machine-readable signal.

**Stable matching substrings for C-8 preflight:**

| Approach | Substring | Reliability |
|----------|-----------|-------------|
| Primary (JSON field) | `"is_error":true` | High — present in JSON output mode for any error |
| Secondary (text in result) | `"Not logged in"` | High — stable Claude Code auth message |
| Tertiary (text in result) | `"Please run /login"` | High — stable CLI prompt |

**Recommended C-8 strategy:** When using `--output-format json`, parse JSON and check `is_error == true`. For belt-and-suspenders, also check `stdout.contains("Not logged in")`. Exit code `1` is a necessary but not sufficient check (other errors also return 1).

**Credential restore verified:** `ls -la ~/.claude/.credentials.json` confirmed file present; `claude --version` returned `2.1.133`.
