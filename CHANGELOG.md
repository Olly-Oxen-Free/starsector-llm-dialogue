# Starlogue Changelog

## 0.1.0 (in development)
- Initial implementation: freeform LLM dialogue with fleet captains
- 2026-07-07 audit fixes (see docs/audits/2026-07-07-full-audit.md):
  - Exploit clamps on ransom/trade/rep actions; monthly rep cap now counted in
    display points (previously never bound)
  - Removed leftover dev telemetry (DebugSessionLog); CLI preflight no longer
    able to freeze the game thread; enforcer script no longer duplicated in saves
  - LLM dispatcher request epochs (stale responses discarded), UI timeout follows
    backend timeout, streaming no longer clobbers narrative notes or doubles
    replies, MCP server requires per-session auth token
  - Wired starlogue_history_budget and starlogue_decay_multiplier settings and
    the three Nexerelin compat actions; docs corrected to credentials-JSON
    provider setup
