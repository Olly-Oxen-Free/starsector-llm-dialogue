# External Research: Starsector Modding Patterns
**Date:** 2026-05-09
**Research method:** Web search + documentation review

---

## Threading Model (Confirmed)

Starsector runs on a single game loop thread. All campaign script callbacks (`advance(float)`, `init`, `optionSelected`) execute on that thread. Background threads are permitted but must communicate results back via thread-safe primitives.

Starlogue's threading approach is correct: the LLM HTTP call runs on a daemon `Thread`, result is delivered via `AtomicReference<LLMResponse> pending`, and the game-loop `advance()` polls the reference. This is the standard pattern for async work in Starsector mods.

**Constraint on action execution:** `StarlogueAction.execute()` is called from `advance()` → `displayResponse()` → `executeToolCall()`, meaning all game-API mutations (fleet assignments, rep changes, cargo transfers) happen on the game thread. This is correct and necessary.

**What NOT to do (common mistake):** Calling game API methods from the background LLM thread. Starlogue correctly avoids this — all game calls are deferred to the polling path.

---

## Java Version Reality

Starsector 0.97a ships **Azul Zulu 17.0.10** (confirmed from `jre_linux/release`). This means:
- `java.net.http.HttpClient` (Java 11+) is available at runtime — Starlogue's LLM clients work.
- Records, switch expressions, text blocks, sealed classes are available at runtime but not usable if `-source 8` is set (bytecode compatibility).
- The `-source 8 -target 8` build flags are misleading but not broken. They prevent use of modern syntax while still linking against Java 17 APIs.

**Recommendation:** Update build flags to `-source 11 -target 11` minimum to make the actual API requirements explicit and enable try-with-resources improvements.

---

## LunaLib Soft Dependency Pattern (Best Practice)

The canonical pattern for LunaLib soft-dependency in the Starsector modding community is:
1. Add LunaLib to the build classpath only (for compilation).
2. Guard every LunaLib call with a class-presence check (`Class.forName(...)` once, cache result).
3. Wrap all LunaLib calls in `try/catch (Throwable)` to catch `NoClassDefFoundError`.

Starlogue implements this correctly in `StarlogueDialogPlugin` and `LunaSettingHelper`, but violates it in `AdjustFactionRelAction` and `AdjustIndividualRelAction` (BUG-1). These two files have `import lunalib.lunaSettings.LunaSettings` as a hard compile-time import with no runtime guard.

---

## HttpClient Resource Usage Pattern

`java.net.http.HttpClient` maintains an internal connection pool and thread pool. Best practice is to create one instance per application (or per logical connection target) and reuse it.

Starlogue creates a new `HttpClient` per `OpenAIClient` / `AnthropicClient` instance. Since `createClientForBackend` is called per LLM request, this means a new `HttpClient` (with its own thread pool) is allocated every turn. The old instance becomes eligible for GC when the lambda exits. This is not a leak but is wasteful: each `HttpClient` allocates OS-level resources including a connection pool that never warms up between turns.

**Fix:** Make `HttpClient` a static or application-scope singleton. In Starlogue's context, a static `private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(...).build();` in `OpenAIClient` and `AnthropicClient` would be correct and sufficient. Thread safety: `HttpClient.send()` is thread-safe per Java spec.

---

## LLM Tool Call Patterns in JVM Mods

No direct prior art found for LLM-powered Starsector mods. Comparable patterns found in general JVM game mod space:

**Pattern 1: Polling AtomicReference** (what Starlogue uses) — correct for single-consumer game-loop polling. Standard Java concurrency idiom, no dependencies needed.

**Pattern 2: Callback via Runnable queued to main thread** — used in some Minecraft mods with a command queue. More complex but allows multiple pending responses. Not needed for Starlogue's single-conversation model.

**Pattern 3: CompletableFuture** — Java 8+, available at runtime. Would simplify the retry chain in `completeWithBackendRetry` by chaining `.exceptionally()` handlers. However, the result still needs to be posted back to the game thread via `pending.set()`, so `AtomicReference` polling remains. Low benefit for the complexity trade.

---

## Java 8 Idioms for Clean Abstraction

Given `-source 8 -target 8` constraint (or `-source 11` as recommended):

**What is available:**
- Lambdas and streams (Java 8) — usable for list transformations in action filtering
- Default interface methods (Java 8) — already used in `StarlogueAction.getParameterDescriptions()`
- `Optional<T>` — available but adds noise for simple null-check patterns; Java 8 idiom prefers explicit null guards
- `Collectors.toList()` — available; would simplify some `ArrayList` + for-loop patterns

**What is NOT available at source 8 (but is available at runtime on Java 17):**
- `var` — source 10+
- Records — source 16+
- Switch expressions — source 14+
- Text blocks — source 15+
- `Stream.toList()` — Java 16+

**Recommendation for this codebase:** The current Java 8 idioms (explicit for-loops, anonymous Runnable, ArrayList) are readable and appropriate. Avoid introducing streams unless there is a clear readability benefit — stream operations on small lists (< 50 elements) are not meaningfully faster and are harder to debug with a game-attached debugger.

---

## Sources

- Starsector modding wiki — threading section (forum.fractalsoftworks.com)
- Azul Zulu 17 release notes (confirmed via `jre_linux/release` file, not web)
- Java SE 11 HttpClient specification (docs.oracle.com/en/java/javase/11/docs/api/java.net.http)
- LunaLib mod documentation (Starsector forums)
