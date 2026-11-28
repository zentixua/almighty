# For developers

How Almighty is built and how to talk to it from your own code. Server owners need none of this: the
[guide](guide/README.md) covers installing, connecting and configuring. Mod and datapack authors who want to teach
agents about their content: [For mod and datapack authors](guide/for-mod-authors.md).

## Layout

| Path | What |
|---|---|
| `src/main/java/ua/zentix/almighty/` | The mod: one source for every Minecraft version and loader |
| `src/main/java/.../neoforge/`, `.../fabric/` | The loader entry points; everything else is plain Java and vanilla Minecraft |
| `src/main/java/.../compat/` | Optional mod support, loaded only when that mod is installed |
| `src/test/`, `src/devtest/` | Unit tests and GameTests |
| `versions/<version>-<loader>/` | One build node per Minecraft version and loader ([Stonecutter](https://stonecutter.kikugie.dev/)) |
| `src/main/resources/assets/almighty/mcp/tools.json` | The MCP tools agents see; `docs/guide/tools.md` is generated from it |
| `src/main/resources/data/almighty/almighty/notes/` | The notes shipped in the jar; `plugin/almighty/skills/` is generated from them |
| `mcp/almighty.py` | Optional command line and stdio MCP relay |
| `mcp/gm/` | Optional Claude Code kit: a chat channel that wakes the session when a player writes |
| `plugin/almighty/` | The Almighty plugin (game master skills) for Claude Code, Codex, Cursor and VS Code |

[AGENTS.md](../AGENTS.md) is the full developer guide: architecture, threading and the tick budget, every bridge
method with its parameters, the permission gate, and the known pitfalls of each Minecraft version and loader.
[Bots and vision](agent-bots.md) covers the bot players. Building and testing: [CONTRIBUTING.md](../CONTRIBUTING.md).

## The bridge

On a dedicated server the mod starts an HTTP server on `bridge.host:bridge.port` (default `127.0.0.1:25650`). It
serves:

- **`/mcp`**: MCP over Streamable HTTP, both the current and the earlier protocol versions, without sessions. 14 tools
  ([list](guide/tools.md)); `?profile=compact` gives 6 short ones, `?images=false` leaves pictures out. Apps sign in
  with OAuth 2.1 (PKCE, dynamic client registration and client ID metadata documents), approved by the owner with a
  code typed on the server. A request without a valid token gets 401 with a `WWW-Authenticate` challenge and a
  JSON-RPC error whose message says no key is needed and gives the `claude mcp add` and `codex mcp add` commands for
  this address.
- **`/rpc`**: plain JSON for scripts. `POST /rpc` with `Authorization: Bearer <token>` and
  `{"method": "status", "params": {}}`; the answer is `{"ok": true, "result": …}` or
  `{"ok": false, "error": {"code": …, "message": …}}`. A top-level `"key"` (up to 128 characters) makes a call with
  effects idempotent for 10 minutes. A call made for a player carries `for` in its params, the ticket from that
  player's addressed event; it then runs with that player's rights. The methods are listed in
  [AGENTS.md](../AGENTS.md#methods).

Every call, from any transport, passes the same checks: the deny list, pause, the permission gate, and the audit log.
Per agent, `policy.toml` can limit where its token is accepted (`[agents.<name>] allow_from`: 403 `not_allowed_from`)
and how often it calls (`[quotas] calls_per_minute`, 1200 by default: 429).
A token for your own code: `/almighty agent add <name>` in the console writes it to
`config/almighty/agents/<name>.token` ([headless](guide/connect/headless.md)).

## Command line

[`mcp/almighty.py`](../mcp/almighty.py) needs [uv](https://docs.astral.sh/uv/). It reads the address from
`ALMIGHTY_URL` (default `http://127.0.0.1:25650`) and the token from `ALMIGHTY_TOKEN` or the file in
`ALMIGHTY_TOKEN_FILE`.

```sh
uv run mcp/almighty.py call status                                       # one call, JSON answer
uv run mcp/almighty.py call map '{"center": [0, 0], "size": 256}' --png map.png
uv run mcp/almighty.py follow                                            # event feed, one JSON line per event
uv run mcp/almighty.py mcp                                               # stdio MCP relay for apps without HTTP
```
