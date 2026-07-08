#!/usr/bin/env bash
# tools/spikes/system-prompt-override/test.sh
#
# C-12 spike: test whether --system-prompt-file reduces cache_creation_input_tokens
# and whether MCP tool calls still work under the overridden system prompt.
#
# REQUIRES: `claude` CLI authenticated, Python 3 on PATH.
# RESULTS:  Written to .karimo/prds/002_claude-cli-mcp-integration/research/spike-c12-results.md
#
# USAGE:
#   ./tools/spikes/system-prompt-override/test.sh [cli-path]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
RESULTS_FILE="$MOD_DIR/.karimo/prds/002_claude-cli-mcp-integration/research/spike-c12-results.md"
CLI="${1:-claude}"

TMPDIR_SPIKE="$(mktemp -d /tmp/starlogue-c12-spike.XXXXXX)"
trap 'rm -rf "$TMPDIR_SPIKE"; [ -n "${MCP_PID:-}" ] && kill "$MCP_PID" 2>/dev/null || true' EXIT

echo "=== C-12 Spike: --system-prompt-file cost reduction ==="
echo "CLI     : $CLI"
echo "Tmp dir : $TMPDIR_SPIKE"
echo ""

# ── Write minimal system prompt file ─────────────────────────────────────────

SYSTEM_PROMPT_FILE="$TMPDIR_SPIKE/starlogue-npc.md"
cat >"$SYSTEM_PROMPT_FILE" <<'SYSPROMPT'
You are an NPC fleet commander in the Persean Sector (Starsector game).
Respond briefly and in character. When asked to use a tool, call it immediately.
Do not describe what you will do — just act.
SYSPROMPT

echo "[info] System prompt file: $SYSTEM_PROMPT_FILE"
echo "[info] System prompt size: $(wc -c < "$SYSTEM_PROMPT_FILE") bytes"
echo ""

# ── Write minimal Python MCP echo server ─────────────────────────────────────

MCP_SERVER_PY="$TMPDIR_SPIKE/mcp_server.py"
cat >"$MCP_SERVER_PY" <<'PYMCP'
#!/usr/bin/env python3
"""Minimal HTTP MCP JSON-RPC echo server for C-12 spike."""
import json, sys, threading
from http.server import HTTPServer, BaseHTTPRequestHandler

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 0

TOOLS = [{
    "name": "starlogue_echo",
    "description": "Echo the given text back. Use when asked to echo something.",
    "inputSchema": {
        "type": "object",
        "properties": {"text": {"type": "string", "description": "Text to echo."}},
        "required": ["text"]
    }
}]

TOOL_CALLS = []

class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args): pass  # suppress access log

    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length)
        req = json.loads(body)
        method = req.get('method', '')
        rid = req.get('id')

        if method == 'initialize':
            resp = {"jsonrpc":"2.0","id":rid,"result":{
                "protocolVersion":"2024-11-05",
                "capabilities":{"tools":{"listChanged":False}},
                "serverInfo":{"name":"c12-spike","version":"0.1"}
            }}
        elif method == 'notifications/initialized':
            self.send_response(200); self.end_headers(); return
        elif method == 'tools/list':
            resp = {"jsonrpc":"2.0","id":rid,"result":{"tools":TOOLS}}
        elif method == 'tools/call':
            params = req.get('params', {})
            tool_name = params.get('name', '')
            tool_args = params.get('arguments', {})
            text = tool_args.get('text', '')
            TOOL_CALLS.append({'name': tool_name, 'args': tool_args})
            print(f"[mcp] tool call: {tool_name}({tool_args})", flush=True)
            resp = {"jsonrpc":"2.0","id":rid,"result":{
                "content":[{"type":"text","text":f"ECHO: {text}"}],
                "isError":False
            }}
        elif method == 'ping':
            resp = {"jsonrpc":"2.0","id":rid,"result":{}}
        else:
            resp = {"jsonrpc":"2.0","id":rid,"error":{"code":-32601,"message":"Method not found"}}

        out = json.dumps(resp).encode()
        self.send_response(200)
        self.send_header('Content-Type','application/json')
        self.send_header('Content-Length', str(len(out)))
        self.end_headers()
        self.wfile.write(out)

server = HTTPServer(('127.0.0.1', PORT), Handler)
actual_port = server.server_address[1]
print(f"MCP_PORT={actual_port}", flush=True)
server.serve_forever()
PYMCP
chmod +x "$MCP_SERVER_PY"

# ── Helper: run one claude invocation and capture token stats ─────────────────

run_test() {
    local label="$1"
    local use_system_prompt="$2"

    # Start MCP server
    MCP_OUT="$TMPDIR_SPIKE/mcp-${label}.log"
    python3 "$MCP_SERVER_PY" 0 >"$MCP_OUT" 2>&1 &
    MCP_PID=$!
    sleep 0.5

    # Read the port
    MCP_PORT=$(grep "MCP_PORT=" "$MCP_OUT" | head -1 | cut -d= -f2 | tr -d ' \r\n')
    if [ -z "$MCP_PORT" ]; then
        echo "ERROR: MCP server did not start for test '$label'"
        kill "$MCP_PID" 2>/dev/null || true
        return 1
    fi

    # Write MCP config
    MCP_CFG="$TMPDIR_SPIKE/mcp-cfg-${label}.json"
    printf '{"mcpServers":{"starlogue":{"type":"http","url":"http://127.0.0.1:%s/"}}}' "$MCP_PORT" >"$MCP_CFG"

    # Build command
    CMD=(
        "$CLI" -p
        --mcp-config "$MCP_CFG"
        --strict-mcp-config
        --dangerously-skip-permissions
        --tools ""
        --output-format json
        --model haiku
    )
    if [ "$use_system_prompt" = "true" ]; then
        CMD+=(--system-prompt-file "$SYSTEM_PROMPT_FILE")
    fi
    CMD+=("Use the starlogue_echo tool with text='hello world'.")

    echo "[run:$label] Spawning claude..."
    RESULT_FILE="$TMPDIR_SPIKE/result-${label}.json"
    set +e
    "${CMD[@]}" >"$RESULT_FILE" 2>&1
    EXIT_CODE=$?
    set -e

    # Kill MCP server
    kill "$MCP_PID" 2>/dev/null || true
    MCP_PID=""

    if [ "$EXIT_CODE" -ne 0 ]; then
        echo "[run:$label] Non-zero exit: $EXIT_CODE"
    fi

    # Extract token fields
    CACHE_CREATE=$(python3 -c "
import json, sys
try:
    data = json.load(open('$RESULT_FILE'))
    u = data.get('usage', {})
    print(u.get('cache_creation_input_tokens', 'N/A'))
except Exception as e:
    print('parse-error: ' + str(e))
" 2>/dev/null)
    CACHE_READ=$(python3 -c "
import json, sys
try:
    data = json.load(open('$RESULT_FILE'))
    u = data.get('usage', {})
    print(u.get('cache_read_input_tokens', 'N/A'))
except Exception as e:
    print('parse-error: ' + str(e))
" 2>/dev/null)
    IS_ERROR=$(python3 -c "
import json
try:
    data = json.load(open('$RESULT_FILE'))
    print(data.get('is_error', False))
except:
    print('parse-error')
" 2>/dev/null)
    TOOL_CALLED=$(grep -c '"starlogue_echo"' "$MCP_OUT" || echo "0")

    echo "[run:$label] cache_creation_input_tokens = $CACHE_CREATE"
    echo "[run:$label] cache_read_input_tokens     = $CACHE_READ"
    echo "[run:$label] is_error                    = $IS_ERROR"
    echo "[run:$label] tool calls logged by MCP    = $TOOL_CALLED"

    # Export for results doc
    eval "${label}_CACHE_CREATE=\"$CACHE_CREATE\""
    eval "${label}_CACHE_READ=\"$CACHE_READ\""
    eval "${label}_IS_ERROR=\"$IS_ERROR\""
    eval "${label}_TOOL_CALLED=\"$TOOL_CALLED\""
}

# ── Run baseline (no system prompt override) ──────────────────────────────────
echo "--- Baseline (no --system-prompt-file) ---"
run_test "baseline" "false"
echo ""

# ── Run with system prompt override ──────────────────────────────────────────
echo "--- Test (with --system-prompt-file) ---"
run_test "override" "true"
echo ""

# ── Compute verdict ───────────────────────────────────────────────────────────
VERDICT="INCONCLUSIVE"
REDUCTION_PCT="N/A"
TOOL_OK="unknown"

baseline_val="${baseline_CACHE_CREATE:-0}"
override_val="${override_CACHE_CREATE:-0}"

if [[ "$baseline_val" =~ ^[0-9]+$ ]] && [[ "$override_val" =~ ^[0-9]+$ ]]; then
    if [ "$baseline_val" -gt 0 ]; then
        REDUCTION_PCT=$(( (baseline_val - override_val) * 100 / baseline_val ))
        if [ "$REDUCTION_PCT" -ge 30 ] && [ "${override_TOOL_CALLED:-0}" -ge 1 ] && [ "${override_IS_ERROR:-}" != "True" ]; then
            VERDICT="PASS"
        elif [ "${override_TOOL_CALLED:-0}" -lt 1 ] || [ "${override_IS_ERROR:-}" = "True" ]; then
            VERDICT="FAIL — tool calls broken or CLI error under system-prompt override"
        else
            VERDICT="FAIL — reduction ${REDUCTION_PCT}% below 30% threshold"
        fi
    fi
fi

if [ "${override_TOOL_CALLED:-0}" -ge 1 ] && [ "${override_IS_ERROR:-}" != "True" ]; then
    TOOL_OK="yes — starlogue_echo called successfully"
else
    TOOL_OK="no — tool call did not happen or CLI errored"
fi

echo "=== Verdict: $VERDICT ==="
echo "Token reduction: ${REDUCTION_PCT}%"
echo "Tool calls OK  : $TOOL_OK"

# ── Write results file ────────────────────────────────────────────────────────
cat >"$RESULTS_FILE" <<RESULTS
# C-12 Spike Results — --system-prompt-file Cost Reduction

**Date:** $(date +%Y-%m-%d)
**CLI version:** $("$CLI" --version 2>&1 | head -1 || echo "unknown")
**Model:** claude-haiku (via --model haiku)
**Spike script:** tools/spikes/system-prompt-override/test.sh

---

## Objective

Test whether passing \`--system-prompt-file <minimal.md>\` to \`claude -p\` reduces
\`cache_creation_input_tokens\` vs. a baseline invocation with the same MCP config.
Verify that tool calls still work under the overridden system prompt.

---

## Test Setup

- MCP server: minimal Python HTTP echo server (same pattern as C-0 spike)
- Stub tool: \`starlogue_echo\` — takes \`{text: string}\`
- Prompt: "Use the starlogue_echo tool with text='hello world'."
- System prompt file (override run only): 3-line Starsector NPC persona (~180 bytes)

---

## Results

| Run | cache_creation_input_tokens | cache_read_input_tokens | is_error | Tool called |
|-----|-----------------------------|------------------------|----------|-------------|
| Baseline (no override) | ${baseline_CACHE_CREATE} | ${baseline_CACHE_READ} | ${baseline_IS_ERROR} | ${baseline_TOOL_CALLED} |
| Override (--system-prompt-file) | ${override_CACHE_CREATE} | ${override_CACHE_READ} | ${override_IS_ERROR} | ${override_TOOL_CALLED} |

**Token reduction:** ${REDUCTION_PCT}%

**Tool call behavior unchanged:** $TOOL_OK

---

## Verdict: $VERDICT

RESULTS

if [ "$VERDICT" = "PASS" ]; then
cat >>"$RESULTS_FILE" <<PASSBLOCK

Token reduction of ${REDUCTION_PCT}% confirmed. Tool calls work normally under the
system-prompt override. The override approach is viable.

---

## Recommended Follow-up

**Integration point in \`ClaudeCliClient\`:**

In \`buildCommand()\` (C-5 implementation), replace the \`--append-system-prompt\`
flag with \`--system-prompt-file\` using a per-call temp file:

\`\`\`java
// Write system prompt to a temp file (instead of --append-system-prompt)
Path systemPromptFile = Files.createTempFile("starlogue-sysprompt-", ".md");
Files.writeString(systemPromptFile, systemPrompt, StandardCharsets.UTF_8);
tempFiles.add(systemPromptFile); // add to finally-block cleanup list

cmd.add("--system-prompt-file");
cmd.add(systemPromptFile.toAbsolutePath().toString());
\`\`\`

The system prompt content comes from \`StarloguePlugin.getSystemPromptPreamble(ctx)\`
(existing per-provider preamble) — same as today, just delivered via file instead
of inline. This should yield ~${REDUCTION_PCT}% reduction in cache_creation overhead
per turn, improving subscription quota efficiency by roughly $(( REDUCTION_PCT * 17000 / 100 ))
tokens per call.

**Expected impact:** A 30-turn conversation saves ~$(( REDUCTION_PCT * 17000 / 100 * 30 / 1000 ))k tokens
of cache_creation overhead — significant for Pro/Max quota limits.
PASSBLOCK
else
cat >>"$RESULTS_FILE" <<FAILBLOCK

The spike did not meet the acceptance threshold (30% token reduction with tool calls working).

Possible reasons:
- \`--system-prompt-file\` may still trigger Claude Code's internal system prompt injection
  in addition to the file content (not a clean replacement).
- The haiku model may behave differently from sonnet/opus under the override.
- The tool call failure (if observed) suggests the override disrupts MCP tool use behavior.

**Decision:** Do not integrate \`--system-prompt-file\` into ClaudeCliClient.
The cost overhead documented in C-11 (README/docs) stands as a known limitation.
FAILBLOCK
fi

echo ""
echo "Results written to: $RESULTS_FILE"
