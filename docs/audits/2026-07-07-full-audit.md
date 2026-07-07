# Starlogue Full Audit — 2026-07-07

Branch: `karimo/002-cli-w1` (PR #3 open, 54 ahead of main). Baseline: build green, 15 tests pass.
Method: 4 parallel audit agents (llm/provider/mcp; action/engine/api/starlords; ui/config/memory/personality/debug/compat/plugin; data/build/tests/docs). All Star Lords 0.3.70 link targets verified present.

## P0 — Exploits (unbounded, player-reachable via prompt injection)

### 1. Negative `amount` in ransom_crew grants unbounded credits
`src/starlogue/action/fleet/RansomCrewAction.java:73-79` — `clampedAmount = Math.min(amount, MAX_RANSOM)` has no lower bound; negative amount passes through, `playerCargo.getCredits().add(-clampedAmount)` *adds* |amount| to player, plus crew.
Scenario: player talks NPC LLM into "ransom = -1000000000" → +1B credits in one call.
Fix: clamp non-negative and to player balance; early-return if ≤ 0.

### 2. Negative `price_per_unit` in trade_offer grants goods + credits
`src/starlogue/action/fleet/TradeOfferAction.java:71-103` — `pricePerUnit` never validated positive; negative price → negative cost → affordability check passes → player credited AND receives commodities. Also div-by-zero risk at line 95 if price == 0.
Fix: `if (pricePerUnit <= 0f) return;` before cost computation.

## P1

### 3. Monthly rep cap 100× too loose (unit mismatch)
`AdjustIndividualRelAction.java:57-76`, `AdjustFactionRelAction.java:65-85` — cap `starlogue_rep_gain_cap` (default 20.0, documented as display points −100..+100) compared against accumulator storing internal −1..+1 deltas (~0.10/call). Guard trips after ~200 calls ≈ 2000 display points — never binds. Player walks Hostile→Cooperative in ~20 LLM calls.
Fix: accumulate in points (`used + |effective| * 100f`) and clamp `effective` against `allowed/100f`.

### 4. Dev telemetry ships enabled, blocks game thread, hardcoded dev paths
`src/starlogue/debug/DebugSessionLog.java:24-29, 39-49, 104-115` — ungated `DebugSessionLog.log(...)` does synchronous HTTP to hardcoded `http://127.0.0.1:7690/ingest/<uuid>` + writes hardcoded absolute `.cursor/debug-2c61f0.log` path. Called from game-thread hot paths incl. `StarlogueOptionEnforcerScript` every 0.25s tick while dialog open. On player machines: up to 2-3s block per call (worse on SYN-dropping firewalls); also exfiltrates dialog metadata. Leftover `#region agent log` instrumentation.
Fix: delete the instrumentation (or gate behind Luna debug flag + async).

### 5. Enforcer script duplicated in save every load
`src/starlogue/StarlogueModPlugin.java:44-46` — `onGameLoad` uses `addScript` (persisted) not `addTransientScript`; each save/load adds another instance. N loads → N per-frame scripts + save bloat.
Fix: `addTransientScript` (script is stateless, re-added every load anyway).

### 6. CLI preflight blocks game thread indefinitely
`src/starlogue/llm/ClaudeCliPreflight.java:154-156, 199-209, 270-275` — timeout applied via `waitFor` only AFTER blocking `readLine()` loop reads to EOF; `probeAuth` makes live network call. Hung CLI/captive portal → `readLine()` blocks forever on game/UI thread (called sync in `StarlogueDialogPlugin.init():143`). Game freezes.
Fix: read stdout on background thread + watchdog `destroyForcibly()` (pattern already in `ClaudeCliClient.complete`).

### 7. LlmDispatcher: no epoch guard — stale response answers wrong message
`src/starlogue/llm/LlmDispatcher.java:72-113, 139-145` — `cancel()` is no-op for HTTP providers (blocking `HttpClient.send`, no interruption); `pending`/`pendingError` not drained before next dispatch. UI 30s timeout fires → player sends message B → late response A renders as answer to B; B's response shows for C.
Fix: per-dispatch epoch, ignore stale in `poll()/pollError()`; drain both atomics before every dispatch.

### 8. UI timeout hardcoded 30s < configurable CLI timeout (up to 300s)
`src/starlogue/ui/StarlogueDialogPlugin.java:428-443` vs `LlmBackendConfig.java:51` — `waitTimer > 30f` kills requests the backend was still legitimately serving (claude_cli default timeout 60s, max 300s; slow Ollama too). Spurious timeouts + feeds bug #7's orphan path.
Fix: derive UI timeout from effective backend timeout + margin.

### 9. Quest lord permanently flagged "quest given" on failure paths
`src/starlogue/starlords/QuestDialogPlugin.java:94` — `setQuestGiven(lord, true)` runs unconditionally incl. no-market, exception, and bounty-rejection paths. Player sees "nothing to offer," lord suppressed from future offers.
Fix: only set flag in the `questGiven == true` branch.

### 10. Decorative LunaSettings (config silently ignored)
- `starlogue_history_budget` (`LunaSettings.csv:20`) — "fraction of max_tokens for history" — never read; history bounded only by `starlogue_history_turns`.
- `starlogue_decay_multiplier` (`LunaSettings.csv:23`) — "scales memory TTLs" — never loaded; `MemoryEngine.recordEvent` multiplier param is per-action intensity, Luna key never threaded in.
Fix: wire or delete (user decision).

### 11. Doc rot: provider setup instructions point to removed field
`README.md`, `docs/claude-cli-provider.md` — both instruct setting `starlogue_provider` in LunaSettings; that field no longer exists (credentials-help text in CSV says JSON-only). Onboarding dead-end.
Fix: rewrite both to `saves/common/Starlogue_credentials.json` flow.

## P2

12. Streaming render clobbers MCP narrative notes — `StarlogueDialogPlugin.java:328-331, 362-378`: `drainNarrativeNotes` appends paragraph, then streaming `replaceLastParagraph` erases it. Fix: reset streaming anchor after note.
13. Prompt injection via player ship names in sighting block — `StarlogueDialogPlugin.java:1002-1008`: ship names player-editable free text embedded in system prompt. Fix: delimit + "untrusted labels" instruction (tool gating already via constraint engine).
14. `partialTextAccum` reset race — background get-then-set vs game-thread `set(null)` on timeout; stale partial text resurrected. Fix: epoch/flag check in listener.
15. Possible partial-text duplication — `ClaudeCliClient.java:231-239, 278-300`: appends every `assistant` event text; if CLI emits cumulative snapshots, reply doubles. Fix: treat final result text as authoritative (replace not append).
16. MCP endpoint no shared secret — `McpRpcHandler.java:50-58`: loopback-only but any local process can invoke game actions while dialog open. Fix: per-session token in config headers.
17. `MemoryEngine.recordEvent` no null-person guard — latent NPE after partial side effects if future action offered in null-person context.
18. `FleetPatrolHereAction.isAvailable` missing `repLevel` null guard its siblings have.
19. build.sh compile diagnostics unreachable under `set -e` — `JAVAC_OUTPUT=$(javac …)` assignment aborts before error echo (both main + test paths). Fix: `|| true` on assignment.
20. 137 `.class` files committed, no `.gitignore` — every build dirties tree with 137 deletions. Fix: .gitignore + `git rm -r --cached jars/classes`.
21. Extort clamp exceeds advertised 10% for poor players — `ExtortAction:62` `max(1000f, …)` floor contradicts description.
22. Duration params lack lower-bound clamps — `CeasefireAction:62`, `PledgeAllianceAction:59`, `RecruitAllyAction:54` (siblings `FleetEscortPlayerAction:49`, `FleetPatrolHereAction:62` do it right).
23. Star Lords direct-link fragility — `StarLordPlugin.java:37-157` ~20 direct calls; version drift → `NoSuchMethodError` kills conversation at init. All targets verified in 0.3.70; document pin or narrow guard.
24. README drift — "0.97+" vs mod_info `0.98a-RC8`; provider table omits `custom`.
25. build.sh hardcodes dep versions (`LunaLib-2.0.5`, `Star Lords-0.3.70`) — glob or document.

## Dead code

- `MemoryEngine.recordFactionEvent` — writer never called (`getFactionScore` is used).
- `NexStarlogueCompat` inner actions `NexColonyReportAction`/`NexFactionSignalAction`/`NexAgentTipAction` — `getActions()` returns `emptyList()`; all three unreachable (wire or delete — user decision).
- `StarlogueCredentials.clearCacheForTests()` — empty body, no callers.
- `StarlogueDialogPlugin.init()` lines 171-191 — comment-only `if` block, no statements.
- `McpToolSchema.toMcpTools()` (List variant), `McpServer.isRunning()`, `McpJsonRpc.ERR_TOOL_EXEC`, `ProviderFactory.createSession(BackendOption)` single-arg overload, unused `AtomicReference` import in `ClaudeCliClient`.

## Bad practices (batch)

- Copy-pasted `asFloat` helper across ~8 actions → shared util.
- Stateful `lastNote`/`lastIntelNote` mutable fields on 11 action classes — fragile execute-writes/narrativeNote-reads split.
- `LlmDispatcher.dispatch` never clears opposite atomic (success leaves stale `pendingError`).
- `ClaudeCliClient.parseStream` swallows unparseable NDJSON; truncated stream returns partial text with only WARN.
- Duplicated clamp logic across `MemoryEngine.recordEvent`/`recordFactionEvent`/`recordEventToMap`.
- Pervasive `catch (Throwable ignored)` hides real failures.

## Test gaps (highest value)

1. Economic action parameter validation — negative/zero/NaN amount, price_per_unit (would have caught #1/#2).
2. Rep-cap accounting — N × +0.10 calls stop at configured cap (would have caught #3).
3. `LlmDispatcher` stale-response handling with slow fake client (would have caught #7).
4. `LlmBackendConfig.fromJson`/provider normalization — pure logic, aliasing + migration branches.
5. `ClaudeCliPreflight` timeout enforcement with stub script.
6. `ConstraintEngine.evaluate` filtering (available/bluffOnly).
7. `ClaudeCliClient.handleResultEvent` error classification.
8. `McpToolBridge` queue-full/timeout envelopes (unit, no live CLI).
9. `StarlogueDialogPlugin.validateBackendOption`, `ConversationAuditLog.safeArgsJson`.

## Verified clean

- No secrets committed (grep sk-/ghp_/AKIA/api_key across repo); credentials example ships empty strings; real keys outside repo.
- API keys never logged (lengths only); HTTP error bodies exclude auth headers.
- Streaming thread-safety correct: background thread writes atomics only; all Starsector API on game thread via `advance()` poll/drain.
- Tool-call authorization: LLM sent only `available` tools; `executeToolCall` re-validates against actionSet.
- `ClaudeCliClient.complete` subprocess lifecycle solid (stderr drain thread, watchdog kill, finally cleanup, temp config perms).
- Save safety in llm/mcp scope: all per-dialog runtime objects, nothing XStream-persisted; MemoryEngine bounded (fixed key set, caps, TTLs).
- All Star Lords 0.3.70 reflection/link targets present; registration guarded by `isModEnabled("starlords")`.
- Config keys read by code all exist in CSV with sane defaults (except the 2 decorative rows, #10).
- Nex compat gracefully degrades (reflection, no hard dep).
- `GrantFiefAction` is the validation model (payment threshold, condition, affordability).
- Integration test asserts real behavior with deadline + exit codes.

## Recommended fix order

Wave 1 (exploits + game-thread safety): #1 #2 #3 #4 #5 #6 #9
Wave 2 (LLM plumbing): #7 #8 #12 #14 #15 #16
Wave 3 (config/hygiene/dead code/docs/tests): #10 #11 #13 #17-#25 + dead code + test gaps 1-4
