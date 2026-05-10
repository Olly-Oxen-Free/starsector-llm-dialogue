# Starlogue

A Starsector mod that replaces scripted faction dialogue with freeform LLM conversations.
Talk to any fleet captain, station commander, or NPC in natural language. The LLM
plays the NPC in character, respects faction standing, and can execute in-game actions
(attack, retreat, trade, share intel) as part of the conversation.

## Requirements

- Starsector 0.97+ (Java 17 game install)
- LunaLib 2.0.5+ (for in-game settings UI)
- An LLM provider (see below)

## LLM Providers

Starlogue supports several LLM backends. Set `starlogue_provider` in LunaSettings.

| Provider | Description | API key required |
|----------|-------------|-----------------|
| `anthropic` | Anthropic API (Claude models) | Yes |
| `openai` | OpenAI API (GPT models) | Yes |
| `openrouter` | OpenRouter.ai multi-model | Yes |
| `ollama` | Local Ollama server | No |
| `xai` | xAI Grok models | Yes |
| `claude_cli` | Claude CLI (Pro/Max subscription) | No — uses your Claude.ai account |

### LLM Provider: Claude CLI (Pro/Max subscription)

Use your existing Claude.ai Pro or Max subscription without an API key.
Starlogue spawns the `claude` CLI as a subprocess and communicates via a local
MCP server that runs inside the game.

See **[docs/claude-cli-provider.md](docs/claude-cli-provider.md)** for setup, LunaSettings
reference, latency expectations, troubleshooting, and known limitations.

## Configuration

All settings are exposed in LunaSettings (Main Menu → Settings → Mods → Starlogue).
Advanced users can also edit `saves/common/Starlogue_credentials.json` directly.

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
