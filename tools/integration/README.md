# Starlogue Integration Tests

## Claude CLI End-to-End Test (`test-claude-cli-flow.sh`)

Tests the full Claude CLI provider flow outside of Starsector:
McpServer → ClaudeCliClient → tool call → response.

### Prerequisites

1. **Claude CLI installed and authenticated**
   ```
   claude --version   # should print 2.1.133 or higher
   claude             # run once to complete OAuth if not yet authenticated
   ```
   Install: https://docs.anthropic.com/en/docs/claude-code

2. **Starlogue.jar built**
   ```
   ./build.sh         # from the mod root
   ```

3. **Java 17+** and **Starsector game files** (for the classpath)

### Usage

```bash
# From the mod root
./tools/integration/test-claude-cli-flow.sh [cli-path] [model]

# Examples
./tools/integration/test-claude-cli-flow.sh               # claude on PATH, haiku model
./tools/integration/test-claude-cli-flow.sh claude haiku  # explicit
./tools/integration/test-claude-cli-flow.sh /usr/local/bin/claude sonnet

# Override Starsector path if not at default
GAME=/path/to/starsector ./tools/integration/test-claude-cli-flow.sh
```

### What It Tests

1. McpServer starts and binds to a random localhost port
2. ClaudeCliClient spawns the `claude` subprocess with the MCP config
3. The CLI discovers the `starlogue_echo` stub tool via `tools/list`
4. The CLI calls `starlogue_echo` with the requested text
5. The game-thread drain loop executes the tool on the "game thread"
6. The response includes the tool result and streaming partial-text deltas

### Output

```
=== Starlogue ClaudeCliIntegrationTest ===
CLI path : claude
Model    : haiku

[setup] McpServer started on port 49312
[game-thread] starting drain loop...
[stub] starlogue_echo called with args: {text=hello from integration test}
[game-thread] tool called at +4123ms

=== Results ===
Response text : I've echoed the phrase for you!
Tool calls    : 1
Partial deltas: 8

  PASS: Tool was called at least once
  PASS: Response is non-null
  PASS: Streaming produced partial deltas

=== Timing ===
MCP server startup : 12 ms
CLI round-trip     : 5847 ms
First tool call    : 4123 ms after request
Total elapsed      : 5860 ms

PASS
```

### Exit Codes

| Code | Meaning |
|------|---------|
| 0    | All assertions passed |
| 1    | Assertion failure or CLI error |
| non-zero | Compilation error or Java crash |

### Notes

- The test uses the `haiku` model by default (fastest, cheapest subscription usage)
- Typical latency: 4–7 seconds per turn
- The test counts against your Claude Pro/Max subscription quota (~17k tokens overhead per call)
- Run from a machine where `claude` is on PATH and the OAuth session is active
