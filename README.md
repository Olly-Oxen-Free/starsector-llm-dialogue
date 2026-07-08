# Starlogue

A Starsector mod that replaces scripted faction dialogue with freeform LLM conversations.
Talk to any fleet captain, station commander, or NPC in natural language. The LLM
plays the NPC in character, respects faction standing, and can execute in-game actions
(attack, retreat, trade, share intel) as part of the conversation.

## Requirements

- Starsector 0.98a-RC8 (Java 17 game install)
- LunaLib 2.0.5+ (for in-game settings UI)
- An LLM provider (see below)

## LLM Providers

Starlogue supports several LLM backends. Provider, API key, model, and endpoint are
configured **only** via `saves/common/Starlogue_credentials.json` — LunaSettings no
longer exposes a `starlogue_provider` field. See **Configuration** below.

| Provider | Description | API key required |
|----------|-------------|-----------------|
| `anthropic` | Anthropic API (Claude models) | Yes |
| `openai` | OpenAI API (GPT models) | Yes |
| `openrouter` | OpenRouter.ai multi-model | Yes |
| `ollama` | Local Ollama server | No |
| `xai` | xAI Grok models | Yes |
| `custom` | Any OpenAI-compatible endpoint (set `starlogue_endpoint`) | Depends on endpoint |
| `claude_cli` | Claude CLI (Pro/Max subscription) | No — uses your Claude.ai account |

### LLM Provider: Claude CLI (Pro/Max subscription)

Use your existing Claude.ai Pro or Max subscription without an API key.
Starlogue spawns the `claude` CLI as a subprocess and communicates via a local
MCP server that runs inside the game.

See **[docs/claude-cli-provider.md](docs/claude-cli-provider.md)** for setup, LunaSettings
reference, latency expectations, troubleshooting, and known limitations.

## Configuration

**LLM backend (provider, API key, model, endpoint):** edit
`saves/common/Starlogue_credentials.json` directly. This file is not managed through
LunaSettings. Copy `data/config/starlogue/credentials.example.json` as a starting
template, place it as `Starlogue_credentials.json` in `saves/common/`, and fill in
your provider details:

```json
{
  "starlogue_provider": "openrouter",
  "starlogue_api_key": "sk-...",
  "starlogue_model": "google/gemma-3n-e2b-it:free",
  "starlogue_endpoint": ""
}
```

For ordered failover across multiple backends, use the `starlogue_backends` array
instead (each entry tried in order until one succeeds) — see the example file for the
full shape. If no credentials file is present, Starlogue creates a default one
(`starlogue_provider: "ollama"`, model `mistral`) the first time the mod loads; edit
it afterward to point at your preferred provider.

**Everything else** (temperature, history, memory decay, reputation caps, debug
logging, and the Claude CLI's model/path/timeout settings) is exposed in LunaSettings
(Main Menu → Settings → Mods → Starlogue).

### Dependencies

- **Star Lords integration is direct-linked**, not reflection-based, and is verified
  against Star Lords `0.3.70`. Other Star Lords versions may break lord-conversation
  hooks with a `NoSuchMethodError` at conversation init if their API has drifted.

## Building from Source

```bash
./build.sh        # compiles Starlogue.jar
./build.sh test   # runs unit tests
```

Requires Starsector game files at `/home/jayden-eppcohen/Games/Starsector`
(or edit `build.sh` to point at your install).

## Integration Testing (Claude CLI provider)

```bash
./tools/integration/test-claude-cli-flow.sh
```

See [tools/integration/README.md](tools/integration/README.md) for prerequisites.
