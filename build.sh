#!/bin/bash
set -e

MOD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLASSES_DIR="$MOD_DIR/jars/classes"
JAR_FILE="$MOD_DIR/jars/Starlogue.jar"
GAME="/home/jayden-eppcohen/Games/Starsector"

echo "=== Starlogue Build ==="

rm -rf "$CLASSES_DIR"
mkdir -p "$CLASSES_DIR"

CLASSPATH="$GAME/starfarer.api.jar:$GAME/lwjgl.jar:$GAME/log4j-1.2.9.jar:$GAME/json.jar:$GAME/mods/LunaLib-2.0.5/jars/LunaLib.jar:$GAME/mods/Star Lords-0.3.70/jars/StarLords.jar"

find "$MOD_DIR/src" -name "*.java" > /tmp/starlogue_sources.txt
echo "Compiling $(wc -l < /tmp/starlogue_sources.txt) source files..."

javac -source 8 -target 8 -cp "$CLASSPATH" -d "$CLASSES_DIR" @/tmp/starlogue_sources.txt 2>&1 | grep -v "^Note:"
JAVAC_EXIT=${PIPESTATUS[0]}
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
