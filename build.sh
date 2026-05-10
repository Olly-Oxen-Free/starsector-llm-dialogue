#!/bin/bash
set -e

MOD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLASSES_DIR="$MOD_DIR/jars/classes"
JAR_FILE="$MOD_DIR/jars/Starlogue.jar"
TEST_CLASSES_DIR="$MOD_DIR/jars/test-classes"
GAME="/home/jayden-eppcohen/Games/Starsector"

CLASSPATH="$GAME/starfarer.api.jar:$GAME/lwjgl.jar:$GAME/log4j-1.2.9.jar:$GAME/json.jar:$GAME/mods/LunaLib-2.0.5/jars/LunaLib.jar:$GAME/mods/Star Lords-0.3.70/jars/StarLords.jar"

# ── Subcommand: test ──────────────────────────────────────────────────────────
if [ "${1}" = "test" ]; then
    echo "=== Starlogue Test ==="

    # Ensure main jar is up to date (build without the 'test' arg)
    if [ ! -f "$JAR_FILE" ]; then
        echo "Main jar missing — building first..."
        bash "$MOD_DIR/build.sh"
    fi

    rm -rf "$TEST_CLASSES_DIR"
    mkdir -p "$TEST_CLASSES_DIR"

    TEST_CLASSPATH="$CLASSPATH:$JAR_FILE"
    find "$MOD_DIR/test" -name "*.java" > /tmp/starlogue_test_sources.txt
    TEST_COUNT=$(wc -l < /tmp/starlogue_test_sources.txt)
    echo "Compiling $TEST_COUNT test source file(s)..."

    JAVAC_OUT=$(javac -source 17 -target 17 -cp "$TEST_CLASSPATH" -d "$TEST_CLASSES_DIR" @/tmp/starlogue_test_sources.txt 2>&1)
    JAVAC_EXIT=$?
    echo "$JAVAC_OUT" | grep -v "^Note:" || true
    if [ "$JAVAC_EXIT" -ne 0 ]; then
        echo "✗ Test compilation failed (exit $JAVAC_EXIT)!"
        exit 1
    fi

    echo "Running tests..."
    java -ea -cp "$TEST_CLASSPATH:$TEST_CLASSES_DIR" starlogue.TestRunner
    exit $?
fi

# ── Default: full build ───────────────────────────────────────────────────────
echo "=== Starlogue Build ==="

rm -rf "$CLASSES_DIR"
mkdir -p "$CLASSES_DIR"

find "$MOD_DIR/src" -name "*.java" > /tmp/starlogue_sources.txt
echo "Compiling $(wc -l < /tmp/starlogue_sources.txt) source files..."

JAVAC_OUTPUT=$(javac -source 17 -target 17 -cp "$CLASSPATH" -d "$CLASSES_DIR" @/tmp/starlogue_sources.txt 2>&1)
JAVAC_EXIT=$?
echo "$JAVAC_OUTPUT" | grep -v "^Note:" || true
if [ "$JAVAC_EXIT" -ne 0 ]; then
    echo "✗ Compilation failed (exit $JAVAC_EXIT)!"
    exit 1
fi

if [ -z "$(ls -A "$CLASSES_DIR" 2>/dev/null)" ]; then
    echo "✗ No class files produced!"
    exit 1
fi

cd "$CLASSES_DIR"
jar cf "$JAR_FILE" .
echo "✓ BUILD SUCCESSFUL — $JAR_FILE"
ls -lh "$JAR_FILE"
