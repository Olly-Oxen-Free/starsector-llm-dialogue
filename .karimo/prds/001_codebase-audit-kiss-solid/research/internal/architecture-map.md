# Internal Architecture Map

## Package Inventory

### `starlogue/ui/` — Dialog presentation layer
Files: `StarlogueDialogPlugin.java` (983 lines), `OpenChannelCommand.java`, `AddStarlogueOptionCmd.java`, `InteractionTargetResolver.java`, `RuleMemoryHelper.java`, `StarlogueOptionEnforcerScript.java`

**Responsibilities:** Starsector `InteractionDialogPlugin` implementation; polling loop for background LLM thread via `AtomicReference<LLMResponse> pending`; input field management; prior-dialog snapshot and restore.

**Fan-out:** Depends on `engine`, `llm`, `config`, `debug`, `api`, `provider`. Highest-coupling package in the mod.

**Key smell:** `StarlogueDialogPlugin` is 983 lines doing dialog rendering + LLM dispatch + retry policy + provider factory + config validation + LunaLib proxy + user-facing error formatting. See SRP-1.

**LunaLib proxy pattern:** The file caches `LUNA_AVAILABLE` as a static `Boolean` (null = unchecked, true/false = cached), then gates all `lunalib.lunaSettings.LunaSettings` calls in try/catch. This pattern works and is correct. The identical safe-get helpers live in `config/LunaSettingHelper.java` — the duplication should be removed.

---

### `starlogue/engine/` — Context building and action evaluation
Files: `ConstraintEngine.java` (198 lines), `GameContext.java`, `EvaluatedActionSet.java`, `FleetContextHelper.java`, `FleetSnapshotFormatter.java`, `FactionDescriptionHelper.java`, `StarlogueTargetFilter.java`

**Responsibilities:** Plugin dispatch (first match wins), `GameContext` assembly, action pool evaluation (available vs bluff-only), tool schema generation for LLM request.

**Fan-out:** Depends on `action`, `api`, `provider`.

**`GameContext`** is a plain data struct (all public fields). This is intentional for performance — no getter/setter indirection. The `extras` map allows contrib extensions without changing the struct. Clean design.

**`FactionDescriptionHelper.fetchGameDescription`** uses reflection to call `SettingsAPI.getDescription(String, Enum)` because the exact method signature is unstable across Starsector versions. This is the right approach but adds ~40 lines of reflective boilerplate.

---

### `starlogue/provider/` — NPC type plugins
Files: `StarloguePlugin.java` (interface), `FleetCaptainPlugin.java`, `MarketAdminPlugin.java`, `PersonInteractionPlugin.java`, `SystemAIPlugin.java`

**Responsibilities:** Implement the `canEngage` / `buildContext` / `getActions` / `getSystemPromptPreamble` lifecycle per NPC type.

**Fan-out (per plugin):** `FleetCaptainPlugin` → `action/fleet/*`, `engine`, `memory`, `personality`. `MarketAdminPlugin` → subset of `action/fleet/*`, `engine`, `memory`, `personality`.

**`MarketAdminPlugin.canEngage`** has a two-signature override pattern (`canEngage(entity)` and `canEngage(entity, memoryMap)`) to support memoryMap-based person disambiguation. `FleetCaptainPlugin` only overrides the single-entity form. This slight asymmetry in the interface is the ISP-1 smell.

**`PersonInteractionPlugin`** and `SystemAIPlugin` not read in detail; likely straightforward given their role as lower-priority fallbacks.

---

### `starlogue/action/fleet/` — 31 fleet action implementations
Pattern: each is a small class (~50–95 lines) implementing `StarlogueAction`. All follow: `getId`, `getDescription`, `getParameters`, `isAvailable`, `execute`, `narrativeNote`.

**Notable patterns:**
- Resource transfers (`TransferCreditsAction`, `TransferFuelAction`, etc.) use a defensive `asFloat(Object)` helper that handles String-typed numbers from LLM responses. Rep-adjustment actions do NOT use this pattern (BUG-5).
- Most `execute` methods guard `if (ctx.fleet == null) return;` at the top (safe). A few skip this (e.g. `AdjustFactionRelAction` which accesses `ctx.npcFaction` and `ctx.playerFaction` directly after an `isAvailable` guard — fine since `isAvailable` checks non-null).
- `repLevel.isAtBest(...)` NPE pattern: approximately 8 actions call this without a null guard (BUG-2). Approximately 8 others guard with `ctx.repLevel == null || ...`.

---

### `starlogue/starlords/` — Star Lords mod integration
Files: `StarLordPlugin.java` (250 lines), `QuestDialogPlugin.java`, `FiefValueCalculator.java`, `StarLordPersonalityModifier.java`, and 15 action files under `action/`

**Coupling:** Hard compile-time dependency on `starlords.jar` (Star Lords mod). `StarLordPlugin` is registered in `StarlogueModPlugin` only when the mod is present (soft-dep at registration, hard-dep at classload). This is correct — if Star Lords is absent, the registration call is guarded.

**`StarLordPlugin.buildContext`** (188 lines total including lord-specific context notes): the base fleet context section (lines 37–75) duplicates `FleetCaptainPlugin.buildContext` (KISS-1). The lord-specific section (lines 77–187) is appropriate and rich.

**`StarLordPlugin.getActions`**: 43 `new XxxAction()` calls — the first 28 are a copy of `FleetCaptainPlugin.getActions()` (KISS-4).

---

### `starlogue/personality/` — NPC character voice
Files: `PersonalityComposer.java` (204 lines), `CharacterProfileRegistry.java`, `FactionProfileRegistry.java`, `CharacterProfile.java`, `FactionProfile.java`

**Resolution order:** CharacterProfileContributor plugins → built-in character profiles (by display name) → bar event archetypes (by missionId) → FactionProfile (by faction ID) → live game faction API.

**`PersonalityComposer`**: the three private methods `composeFromParts`, `buildBaseFromLiveContext`, `buildBaseFromProfile` are structurally identical from lines 101–124 in each (KISS-3). Each builds a `StringBuilder` by appending base → personality note → rank note → skill notes → AI core note → closing instruction. Only the "base" source differs.

**`CharacterProfileRegistry.load()`**: loads two JSON files at mod startup. The second load (`bar_archetype_profiles.json`) is wrapped in `try/catch` so a missing file is non-fatal. The first is not wrapped — a missing `character_profiles.json` will propagate and fail mod load. This is probably intentional (the file ships with the mod) but worth noting.

---

### `starlogue/llm/` — HTTP clients and response parsing
Files: `LLMClient.java` (interface), `OpenAIClient.java` (166 lines), `AnthropicClient.java` (172 lines), `OpenRouterClient.java` (~39 lines), `XaiClient.java` (~25 lines), `ToolCallParser.java` (181 lines), `LLMRequest.java`, `LLMResponse.java`, `LLMToolCall.java`, `ConversationHistory.java`

**Inheritance chain:**
```
LLMClient (interface)
├── OpenAIClient       ← base OpenAI-compatible implementation
│   ├── OpenRouterClient  ← adds attribution headers via extraHeaders() hook
│   └── XaiClient         ← changes base URL
└── AnthropicClient    ← separate format; contains its own deepToJson duplicated from OpenAIClient
```

**Duplication:** `AnthropicClient.mapToJson` / `deepToJson` (lines 151–171) are identical to `OpenAIClient.mapToJson` / `deepToJson` (lines 145–165). Could be extracted to a shared `JsonUtils` class, but both are private — low priority.

**`java.net.http.HttpClient`**: created fresh per client instance (one `HttpClient` per `OpenAIClient` / `AnthropicClient` construction). Since the dialog plugin creates a new client per LLM call (via `createClientForBackend`), a new `HttpClient` with a new connection pool is allocated per turn. `HttpClient` is designed to be shared. This is a minor resource waste but not a leak — the client is garbage collected after the lambda returns.
Fix: cache the client factory or pass `HttpClient` as constructor dependency.

**`ToolCallParser`**: all methods return safe empty/null values on any parse failure. Logging is at `WARN` level (fixed in recent commit). `parseContent` silently returns null on exception — appropriate since content-only responses are normal.

---

### `starlogue/memory/` — Conversation memory
Files: `MemoryEngine.java` (93 lines), `MemoryEvent.java`

Clean package. No threading concerns — `MemoryAPI` access is game-thread-only and always called from game thread paths. Dual-path design (game API + plain Map) is the right pattern for testability. No issues found.

---

### `starlogue/config/` — Settings and credentials
Files: `LlmBackendConfig.java` (180 lines), `StarlogueCredentials.java` (329 lines), `LunaSettingHelper.java`, `StarlogueCredentials.java`

`LlmBackendConfig` is clean. `StarlogueCredentials` has accumulated telemetry logic (KISS-5, SRP-2). The `pickBetter` scoring logic (lines 206–221) is a fragile heuristic: a file with a 1-character api key scores higher than one with provider=openrouter and no key. This could mismatch if two credential file aliases coexist.

---

### `starlogue/debug/` — Observability
Files: `ConversationAuditLog.java` (247 lines), `DebugSessionLog.java`

`ConversationAuditLog` is feature-complete but inefficient (BUG-7: file opened/closed per write). The audit is gated by `LunaSettingHelper.getBoolean("starlogue_audit_conversation_log", false)` so it defaults off — the performance impact is invisible to most users.

The `#region agent log` blocks scattered throughout `StarlogueDialogPlugin` (6+ occurrences) call `DebugSessionLog.log(...)` inline. These are development diagnostics embedded in production code. They are guarded by try/catch and produce no user-visible output, but they add visual noise to the primary dialog class.

---

### `starlogue/compat/nex/` — Nexerelin compatibility
Files: `NexStarlogueCompat.java` (135 lines)

Three placeholder `StarlogueAction` inner classes that execute no real logic. All three appear in the tool list for every Nexerelin-enabled conversation. The LLM may call them and get back a canned narrative note. This wastes a tool-call slot and may confuse users who read the audit log. See KISS-6.

---

### `starlogue/api/` — Public extension API
Files: `StarlogueAPI.java` (176 lines), five contributor/modifier interfaces

Clean facade. Five extension points: `ContextModifier`, `PersonalityModifier`, `ActionContributor`, `FactionProfileContributor`, `CharacterProfileContributor`. All registries are `ArrayList` — fine for the expected contributor count (<10 per registry in practice).

`StarlogueAPI.getMemoryEngine()` returns `null` (line 144). `getFactionProfileRegistry()` returns `null` (line 148). These methods promise something and deliver nothing — vestigial or forgotten. Should be either implemented or removed.

---

## Dependency Fan-In (which packages are most depended upon)

| Package | Depended on by |
|---|---|
| `engine/GameContext` | All providers, all actions, personality, ui |
| `action/StarlogueAction` | ConstraintEngine, all providers, ui |
| `api/StarlogueAPI` | engine, personality, ui, starlords |
| `memory/MemoryEngine` | Most fleet actions, providers, starlords |
| `config/LlmBackendConfig` | ui, StarlogueCredentials |

Most-depended-on classes are `GameContext` and `StarlogueAction` — both are intentionally stable interfaces/data structs. The fan-in is healthy.
