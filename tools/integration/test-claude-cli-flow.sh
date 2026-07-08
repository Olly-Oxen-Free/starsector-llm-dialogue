#!/usr/bin/env bash
# tools/integration/test-claude-cli-flow.sh
#
# End-to-end integration test for the Starlogue Claude CLI provider.
#
# REQUIRES:
#   - `claude` CLI installed and authenticated (run `claude` once first)
#   - Starlogue.jar built (run `./build.sh` first)
#   - Java 17+ on PATH
#
# USAGE:
#   ./tools/integration/test-claude-cli-flow.sh [cli-path] [model]
#
#   cli-path : path to the claude executable (default: claude)
#   model    : haiku | sonnet | opus (default: haiku — fastest, cheapest)
#
# EXIT CODE: 0 on PASS, non-zero on FAIL

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

CLI_PATH="${1:-claude}"
MODEL="${2:-haiku}"

echo "========================================"
echo " Starlogue Claude CLI Integration Test"
echo "========================================"
echo "Mod directory : $MOD_DIR"
echo "CLI path      : $CLI_PATH"
echo "Model         : $MODEL"
echo ""

# ── Preflight checks ─────────────────────────────────────────────────────────

echo "[preflight] Checking claude CLI..."
if ! command -v "$CLI_PATH" &>/dev/null && [ ! -x "$CLI_PATH" ]; then
    echo "ERROR: Claude CLI not found: $CLI_PATH"
    echo "Install instructions: https://docs.anthropic.com/en/docs/claude-code"
    exit 1
fi

CLI_VERSION=$("$CLI_PATH" --version 2>&1 | head -1 || echo "unknown")
echo "[preflight] CLI version: $CLI_VERSION"

echo "[preflight] Checking Starlogue.jar..."
JAR="$MOD_DIR/jars/Starlogue.jar"
if [ ! -f "$JAR" ]; then
    echo "[preflight] Starlogue.jar not found — building..."
    (cd "$MOD_DIR" && ./build.sh)
fi

GAME="${GAME:-/home/jayden-eppcohen/Games/Starsector}"
if [ ! -d "$GAME" ]; then
    echo "ERROR: Starsector not found at $GAME"
    echo "Set the GAME environment variable to your Starsector installation path."
    exit 1
fi

echo "[preflight] All checks passed."
echo ""

# ── Compile integration harness ───────────────────────────────────────────────

HARNESS_SRC="$MOD_DIR/test/integration/ClaudeCliIntegrationTest.java"
HARNESS_CLASSES="$MOD_DIR/jars/integration-classes"

echo "[compile] Compiling integration harness..."
rm -rf "$HARNESS_CLASSES"
mkdir -p "$HARNESS_CLASSES"

CLASSPATH="$GAME/starfarer.api.jar:$GAME/lwjgl.jar:$GAME/log4j-1.2.9.jar:$GAME/json.jar:$GAME/mods/LunaLib-2.0.5/jars/LunaLib.jar:$JAR"

if ! javac -source 17 -target 17 -cp "$CLASSPATH" -d "$HARNESS_CLASSES" "$HARNESS_SRC" 2>&1; then
    echo "ERROR: Harness compilation failed."
    exit 1
fi
echo "[compile] Done."
echo ""

# ── Run harness ───────────────────────────────────────────────────────────────

echo "[run] Starting integration test (model=$MODEL, timeout=120s)..."
echo ""

RUN_CLASSPATH="$CLASSPATH:$HARNESS_CLASSES"

# Capture output and exit code
set +e
java -ea \
    -Dlog4j.configuration=file:"$MOD_DIR/data/config/log4j.properties" 2>/dev/null \
    -cp "$RUN_CLASSPATH" \
    integration.ClaudeCliIntegrationTest "$CLI_PATH" "$MODEL"
EXIT_CODE=$?
set -e

echo ""
if [ "$EXIT_CODE" -eq 0 ]; then
    echo "========================================"
    echo " RESULT: PASS"
    echo "========================================"
else
    echo "========================================"
    echo " RESULT: FAIL (exit code $EXIT_CODE)"
    echo "========================================"
fi

# Cleanup compiled harness
rm -rf "$HARNESS_CLASSES"

exit "$EXIT_CODE"
