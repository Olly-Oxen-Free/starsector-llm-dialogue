# Claude CLI Provider — Setup & Reference

## What It Is

The `claude_cli` provider lets Starlogue use your **Claude.ai Pro or Max subscription**
to power NPC dialogue. No API key is needed — Starlogue spawns the
[Claude Code CLI](https://docs.anthropic.com/en/docs/claude-code) as a subprocess
and exchanges messages through a local HTTP MCP server running inside the game's JVM.

This means you can run full, context-aware LLM dialogue with Starsector NPCs without
registering for an Anthropic API account or paying per-token rates, as long as you
have an active Pro or Max subscription.

---

## Install the Claude CLI

**Minimum CLI version required: 2.1.133**

Install instructions: https://docs.anthropic.com/en/docs/claude-code

Quick check:
```
claude --version
```

---

## First-Time Authentication

Run `claude` once in a terminal. It will open a browser for OAuth login.
After completing the flow, close the browser and the terminal — the credentials
are stored in `~/.claude/.credentials.json` and reused by Starlogue automatically.

```
claude
# Follow the browser prompt, then close
```

You only need to do this once per machine. Credentials persist across game sessions.

---

## LunaSettings Configuration

Open **Main Menu → Settings → Mods → Starlogue** and set:

| Setting | Description | Default |
|---------|-------------|---------|
| `starlogue_provider` | Set to `claude_cli` | `ollama` |
| `starlogue_claude_cli_model` | `haiku`, `sonnet`, or `opus` | `sonnet` |
| `starlogue_claude_cli_path` | Path to the `claude` executable | `claude` (PATH) |
| `starlogue_claude_cli_timeout_sec` | Seconds before the subprocess is killed | `60` |

**Model guidance:**
- `haiku` — fastest (~3–4s/turn), cheapest on subscription quota. Good for casual play.
- `sonnet` — default. Best balance of quality and latency (~5–7s/turn).
- `opus` — highest quality, slowest (~8–12s/turn). Most quota usage per turn.

---

## Latency Expectations

Each conversational turn spawns a `claude` subprocess, waits for it to start, exchanges
MCP `tools/list` and `tools/call` messages, then reads the response. Typical round-trips:

| Model | Latency |
|-------|---------|
| haiku | 3–5 seconds |
| sonnet | 5–7 seconds |
| opus | 8–12 seconds |

This is slower than direct API calls (~1–2 seconds) because of subprocess startup and
the Claude Code agent bootstrapping overhead.

**Streaming:** Starlogue renders partial text in the dialog as the model generates it,
so the conversation starts appearing within ~1.5 seconds and builds up progressively
rather than appearing all at once after the full round-trip.

---

## Cost / Quota Note

Each `claude -p` call injects approximately **12,000–17,000 tokens of Claude Code agent
overhead** (CLI system prompt + tool descriptions) before your actual dialogue content.
This counts against your Pro/Max subscription quota.

Rough estimates at `sonnet`:
- ~10–15 conversational turns per 5-hour usage window (Pro)
- ~50+ turns per 5-hour window (Max, which has a higher quota)

For heavy use (many dialogues per session), consider the direct `anthropic` API provider
which does not have the CLI overhead and uses standard API pricing instead.

A spike (C-12, 2026-05-10) found that `--system-prompt-file` may reduce this overhead
by ~52%. Integration is planned as a follow-up optimization.

---

## Troubleshooting

### "Claude CLI not found"
The `claude` executable is not on your PATH, or the path in LunaSettings is wrong.

**Fix:** Install the CLI from https://docs.anthropic.com/en/docs/claude-code, or set
`starlogue_claude_cli_path` in LunaSettings to the full path (e.g. `/usr/local/bin/claude`).

---

### "Claude CLI is not signed in"
The preflight auth check failed — the CLI returned an auth error.

**Fix:** Run `claude` once in a terminal to complete the OAuth flow. The credentials
are stored automatically and persist across sessions.

---

### "Failed to start MCP bridge on localhost"
The in-process MCP server (HTTP on a random ephemeral port) failed to bind.

**Fix:** This usually means a port was unavailable at dialog open time. Try restarting
the game. If the error persists, check `starsector.log` for the full Java exception.
Port collisions are rare because the server binds to `127.0.0.1:0` (OS-assigned port).

---

### Slow responses / timeout
The subprocess is taking longer than `starlogue_claude_cli_timeout_sec`.

**Fix options:**
- Lower latency: switch to `starlogue_claude_cli_model = haiku`
- Increase timeout: set `starlogue_claude_cli_timeout_sec = 120`
- Check network: `claude` needs to reach `api.anthropic.com`
- Check subscription: if your quota is exhausted, `claude` may hang or return an error

---

### Abort during a conversation
If you end the conversation while the NPC is "thinking", Starlogue sends SIGTERM to
the subprocess (then SIGKILL after 1 second) and drains any pending MCP tool calls.
No orphan processes are left. Partial text may remain visible in the dialog on the
last rendered frame — this is cosmetic only.

---

## Known Limitations

- **Stateless per-turn:** the full conversation history is re-sent on every turn. Long
  conversations consume proportionally more tokens.
- **17k token CLI overhead per call.** See the cost note above.
- **No streaming abort mid-token:** the streaming text is rendered in the dialog, but
  once partial text has been written it cannot be fully cleared on abort.
- **Minimum CLI version: 2.1.133** (earlier versions have MCP transport differences).
- **No offline mode:** requires `api.anthropic.com` to be reachable.
