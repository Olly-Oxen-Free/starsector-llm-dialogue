# PRD: Codebase Audit — KISS / SOLID / Bugs / UX

**Slug:** `codebase-audit-kiss-solid`
**Created:** 2026-05-09
**Status:** ready
**Author:** jayden-eppcohen

---

## Executive Summary

Audit and remediate the Starlogue codebase against KISS and SOLID principles, fix two shipping bugs, modernize the build target to Java 17, and improve user-facing latency feedback. The work is sliced into 13 tasks executed in three waves (PR per wave), preserving Starsector's strict game-thread / background-thread discipline throughout.

## Goals

1. **Correctness** — eliminate two confirmed bugs (hard LunaLib import, repLevel NPE) and the `asFloat` coercion gap.
2. **Architecture** — break apart the `StarlogueDialogPlugin` god class (983 LOC) into single-responsibility collaborators.
3. **Duplication** — eliminate the structural copy-paste between `StarLordPlugin` and `FleetCaptainPlugin` (context builder + action list).
4. **Modernization** — bump build target from Java 8 to Java 17 (matches Starsector's actual Zulu 17 runtime).
5. **API completion** — wire up the two `StarlogueAPI` accessors that currently return null.
6. **UX** — surface latency to the player via animated dots, plus an API-key preflight check, plus a minimal test runner.

## Non-Goals

- No new heavyweight dependencies (Spring, Guice, etc.) — fat-jar classpath constraint stands.
- No rewrite of the threading model — current `AtomicReference` polling pattern is correct and stays.
- No redesign of personality/profile system (recent work, stable).
- Memory engine package untouched — clean and tested.

## Constraints

- **Java 17 target** — confirmed by user; matches Starsector 0.98a runtime (Zulu 17.0.10). Update `build.sh` to `-source 17 -target 17`.
- **LunaLib soft-dependency** — must continue to work without LunaLib installed.
- **Game-thread discipline** — all `SectorAPI` / `FleetAPI` mutations stay on the game thread; HTTP I/O stays on background thread; `AtomicReference` is the only handoff.
- **Per-wave merges** — three PRs total, one per wave.

## Research Findings

See `research/findings.md` and `research/internal/architecture-map.md` for full detail. Highlights:

- **2 must-fix bugs**: `BUG-1` hard LunaLib import in `AdjustFactionRelAction` / `AdjustIndividualRelAction`; `BUG-2` `repLevel.isAtBest()` NPE in ~8 fleet actions.
- **God class**: `StarlogueDialogPlugin` (983 lines) doing 6+ jobs.
- **Duplication**: ~150 lines copy-pasted between `StarLordPlugin` and `FleetCaptainPlugin`.
- **Resource waste**: `HttpClient` allocated per LLM turn instead of reused.
- **Dead API**: `StarlogueAPI.getMemoryEngine()` and `getFactionProfileRegistry()` return `null`.

## Acceptance Criteria

- `./build.sh` succeeds with Java 17 source/target flags.
- Mod loads without LunaLib installed (no `NoClassDefFoundError`).
- Fleet rep-adjust actions execute without NPE when player has no prior reputation with NPC faction.
- `StarlogueDialogPlugin` is ≤ 400 lines and contains only orchestration logic.
- `StarLordPlugin` and `FleetCaptainPlugin` share their fleet-context builder and default action list via extracted helpers.
- `StarlogueAPI.getMemoryEngine()` and `getFactionProfileRegistry()` return non-null instances reachable by `ContributorPlugin` implementations.
- Dialog displays cycling dots while waiting on LLM response (game-loop driven, no separate timer thread).
- Three test classes execute via a `TestRunner.main()` invoked from `build.sh test` and pass.

## Risks

- **R1 — God-class extraction regressions** — `StarlogueDialogPlugin` orchestrates the polling loop; an extraction mistake could break LLM response delivery. Mitigation: aggressive extraction is approved by user, but smoke-playtest after T-5 and T-6 before wave 2 merges.
- **R2 — Java 17 source bump exposes latent code** — modern syntax becomes legal but existing code must still compile cleanly. Mitigation: T-3 changes only build flags, no syntax changes; subsequent tasks may opt into Java 11+ syntax piecemeal.
- **R3 — `HttpClient` singleton + multiple credential rotations** — caching `HttpClient` across requests means TLS context is reused; if user rotates API keys mid-session, ensure the auth header still goes per-request (not per-client). Mitigation: keep auth headers in `HttpRequest`, never in `HttpClient`.

---

## Research Findings (embedded summary)

See `research/findings.md` for the canonical document. The 13-task slicing in that file is the source of truth for `tasks.yaml`.
