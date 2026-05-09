# Research Findings: Codebase Audit — KISS / SOLID / Bugs / UX

**Date:** 2026-05-09
**Scope:** Starlogue mod, 99 Java files, ~9800 LOC, 16 packages
**Method:** Internal package-by-package read + external research (Starsector threading model, JDK reality, LunaLib best practices)

---

## Executive Summary

- **Threading is correct.** Background LLM thread + `AtomicReference` polling on game thread is the right pattern. No race conditions found.
- **Two real bugs ship today**: hard LunaLib import in 2 rep-adjustment actions (mod load crash if LunaLib absent — confirmed user-reachable since LunaLib is truly optional), and ~8 fleet actions NPE on `repLevel.isAtBest()` when player has no rep with NPC faction.
- **One god class**: `StarlogueDialogPlugin` (983 lines) does dialog rendering + LLM dispatch + retry policy + provider factory + config validation + LunaLib proxy + error formatting. SRP-1.
- **Provider duplication**: `StarLordPlugin` and `FleetCaptainPlugin` share ~30 actions and a context builder via copy-paste. ~150 lines of structural duplication.
- **Build flags lie**: `-source 8 -target 8` but code uses Java 11+ APIs (`java.net.http.HttpClient`). Starsector ships Zulu 17. User confirmed: bump to Java 17.

---

## Architecture Map (Current)

```
ui (god class) ─────┐
                    ├──> engine ──> action/{fleet}
provider ───────────┤              memory
                    ├──> personality
starlords ──────────┤              api
compat/nex ─────────┘              llm/{OpenAI,Anthropic,OpenRouter,Xai}
                                   config (settings, credentials)
                                   debug (audit, session log)
```

Fan-in healthy on `GameContext`, `StarlogueAction`, `MemoryEngine`. Worst fan-out: `StarlogueDialogPlugin`.

---

## Findings

### MUST FIX (Bugs)

**BUG-1 · Hard LunaLib import in rep-adjust actions** · `action/fleet/AdjustFactionRelAction.java`, `action/fleet/AdjustIndividualRelAction.java`
`import lunalib.lunaSettings.LunaSettings;` at top of file. No runtime guard. Throws `NoClassDefFoundError` on mod load if LunaLib absent. **User confirmed LunaLib is truly optional.** Fix: route through `LunaSettingHelper` or guard with `try/catch (Throwable)`.

**BUG-2 · `repLevel.isAtBest()` NPE in ~8 fleet actions** · `action/fleet/*.java`
Approximately half the rep-related fleet actions call `ctx.repLevel.isAtBest(...)` without `ctx.repLevel == null` guard. Triggers when player has no prior rep with target faction. Other ~8 actions guard correctly — apply that pattern uniformly.

### SHOULD FIX (Risks + Meaningful Smells)

**BUG-3** (referenced in agent report, location TBD during implementation) — needs spot check during task execution
**BUG-4** (referenced in agent report, location TBD)
**BUG-5 · `asFloat(Object)` not used in rep-adjust actions** — string→number coercion present in resource-transfer actions but absent in rep adjustments. LLM responses with string-typed numbers will fail silently.
**BUG-7 · `ConversationAuditLog` reopens file per write** · `debug/ConversationAuditLog.java` — feature is gated off by default so impact is invisible, but when enabled the per-write open/close is wasteful. Hold a `BufferedWriter` for the session.

**SRP-1 · `StarlogueDialogPlugin` god class** · `ui/StarlogueDialogPlugin.java` (983 lines)
Extract: `LlmDispatcher` (background thread + retry + polling), `ProviderFactory` (`createClientForBackend`), `DialogRenderer` (option/text rendering), keep dialog plugin as orchestrator.

**KISS-1 · Duplicated fleet-context builder** · `starlords/StarLordPlugin.java:37–75` ↔ `provider/FleetCaptainPlugin.java`
Extract shared `FleetContextBuilder` helper in `engine/`.

**KISS-4 · Duplicated action list** · `starlords/StarLordPlugin.getActions()` first 28 entries duplicate `provider/FleetCaptainPlugin.getActions()`. Extract `defaultFleetActions()` static method.

### COULD FIX (Cleanup + UX)

**SRP-2 · `StarlogueCredentials` accumulating concerns** · `config/StarlogueCredentials.java` (329 lines) — credentials loading + telemetry + fragile `pickBetter` heuristic. Split telemetry out.

**KISS-3 · `PersonalityComposer` near-duplicate methods** · `personality/PersonalityComposer.java:101-124` × 3. The three private compose methods only differ in their "base" string source. Parameterize.

**KISS-5 · Telemetry in `StarlogueCredentials`** — see SRP-2 split.

**KISS-6 · Nexerelin placeholder actions** · `compat/nex/NexStarlogueCompat.java` — 3 inner classes that execute no real logic but appear in every Nex tool list. Either implement or remove.

**KISS-2 · `LunaSettingHelper` duplicated proxy logic in `StarlogueDialogPlugin`** — dialog plugin re-implements safe-get instead of routing through helper. Consolidate.

**ISP-1 · `canEngage` two-signature asymmetry** · `provider/StarloguePlugin.java` interface vs `MarketAdminPlugin` / `FleetCaptainPlugin` — formalize the memoryMap-aware variant in the interface.

**API-STUB · `StarlogueAPI.getMemoryEngine()` / `getFactionProfileRegistry()` return null** · `api/StarlogueAPI.java:144,148` — **User confirmed: wire them up properly** for external mod authors.

**LLM-RESOURCE · `HttpClient` allocated per turn** · `llm/OpenAIClient.java`, `llm/AnthropicClient.java` — make static singleton. Thread-safe per Java spec.

**LLM-DUPL · `mapToJson` / `deepToJson` duplicated** · `llm/OpenAIClient.java:145-165` ↔ `llm/AnthropicClient.java:151-171` — extract `JsonUtils`.

**JAVA-MODERNIZE · Build flags say Java 8, code uses Java 11+** · `build.sh` — **User confirmed bump to Java 17.** Update `-source 17 -target 17`, allow modern syntax in subsequent refactors.

**DEBUG-NOISE · `#region agent log` blocks scattered through `StarlogueDialogPlugin`** — 6+ inline debug blocks. Move to a `DebugTrace` helper.

---

## UX Concerns

- Latency handling: user sees no progress indicator during LLM call. Currently the dialog freezes visually until polling resolves.
- API key missing → cryptic error. Should detect at dialog open and show "Configure API key in LunaSettings" rather than failing on first message.
- Bar event archetype coverage: 25 archetypes ship, but unmatched events fall through to faction profile. Verify the fallthrough message reads naturally.

---

## Testing Strategy Recommendation

- Only 3 test files exist; no test runner. Tests are JUnit-style classes invoked manually.
- **Recommend**: add a tiny `TestRunner.main()` that reflectively invokes `@Test`-annotated methods (or just `testXxx` naming convention) — avoids JUnit jar in classpath. Run via `java -cp ...` from `build.sh test` target.
- Prioritize tests around: `ToolCallParser` (already has one), `MemoryEngine` (already has one), `PersonalityComposer` (already has one), and add: `FleetContextBuilder` (after extraction), the `repLevel` null guards (BUG-2 regression), `LunaSettingHelper` fallback paths.

---

## Recommended Slicing (13 PRD Tasks)

| ID | Title | Complexity | Priority | Depends |
|----|-------|-----------|----------|---------|
| T-1 | Fix hard LunaLib import in rep-adjust actions (BUG-1) | 2 | must | — |
| T-2 | Add repLevel null guards uniformly (BUG-2) | 2 | must | — |
| T-3 | Bump build flags to Java 17 + verify build | 2 | must | — |
| T-4 | Apply `asFloat` coercion in rep-adjust actions (BUG-5) | 2 | should | T-1 |
| T-5 | Extract `LlmDispatcher` from `StarlogueDialogPlugin` (SRP-1) | 5 | should | T-3 |
| T-6 | Extract `ProviderFactory` from `StarlogueDialogPlugin` (SRP-1) | 3 | should | T-5 |
| T-7 | Extract `FleetContextBuilder` shared helper (KISS-1) | 3 | should | — |
| T-8 | Extract `defaultFleetActions()` (KISS-4) | 2 | should | T-7 |
| T-9 | `HttpClient` singleton in LLM clients | 2 | should | — |
| T-10 | Wire up `StarlogueAPI.getMemoryEngine()` / `getFactionProfileRegistry()` | 2 | should | — |
| T-11 | Consolidate `LunaSettingHelper` duplication (KISS-2) + extract `JsonUtils` (LLM-DUPL) | 3 | could | T-5 |
| T-12 | Parameterize `PersonalityComposer` triplet (KISS-3) + decide on Nex placeholders (KISS-6) | 3 | could | — |
| T-13 | UX: API key preflight + LLM-call progress indicator + lightweight test runner | 4 | could | T-5 |

**Total**: 13 tasks, ~35 complexity points. Wave 1 (no deps): T-1, T-2, T-3, T-7, T-9, T-10, T-12. Wave 2: T-4, T-6 (after T-5), T-8, T-11. Wave 3: T-13.

---

## Constraints Acknowledged

- Java 17 target (user confirmed).
- LunaLib remains soft-dependency (user confirmed).
- API stubs to be wired, not removed (user confirmed).
- No new heavyweight frameworks (Spring etc.); fat-jar classpath constraint stands.
- Game-thread vs background-thread discipline must be preserved in any refactor.
