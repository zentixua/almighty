# Mission almighty-agent: report

Mission: almighty-agent (plan 1.0 steps 12, 12b, 13, 14, 15: connect any agent, knowledge, skills plugin, docs).
Tracking PR: #38. Cloud session, 27.11.2026. Every PR: CI green, one review round by a fresh subagent that did not
write the code (from the diff and surrounding code), fixes, then merged by this mission.

## What was built

| PR | What | Review |
|---|---|---|
| #41 | **Knowledge.** `notes` serves an index (ids + one-line descriptions, no bodies), one note by id, and a search over notes and a **live catalog** of the running server: mods, blocks with states, items, entities, effects, enchantments, biomes, dimensions, tags, recipes, commands (usage), events, mod config values (secret-looking keys hidden). Notes from mods/datapacks (Agent Skills frontmatter, legacy notes still read, datapack overrides), the owner's `config/almighty/notes/`, and **world notes** `<world>/almighty/notes/` with server-set provenance the text cannot forge. | 3 blocking found, fixed |
| #42 | **GM skills plugin** (only in this repo, D6): 5 skills shipped in the jar as notes and exported to `plugin/almighty/skills/*/SKILL.md`; one directory in both formats (Agent Plugins `plugin.json`, Claude `.claude-plugin/plugin.json`); marketplaces `.claude-plugin/marketplace.json` and `.agents/plugins/marketplace.json`; CI checks export and versions. | clean; minor fixed |
| #44 | **MCP served by the mod** at `/mcp`, both protocol eras on one stateless endpoint (legacy `initialize`; modern 2026-07-28 `server/discover`, header checks, version errors), the 14 tools defined once in `tools.json` and routed in Java with the adapter's checks, annotations, short instructions (core rule in the first 512 chars), `?profile=compact`, `?images=false`. Python adapter optional: CLI + stdio relay, no MCP SDK. | 3 blocking found, fixed |
| #49 | **Keyless sign-in.** The mod as a minimal OAuth 2.1 server (PKCE S256, DCR + Client ID Metadata Documents, RFC 9207 `iss`, refresh rotation); the login page shows a **four-digit code**, the owner types `/almighty approve <code>` in game or console; the app becomes an ordinary agent. `/almighty connect` (address, one line per app, secret-free links), console hint at start, plugin server entry. | 4 blocking (security) found, fixed |
| TLS PR | **HTTPS from the owner's certificate** (`bridge.tls_cert`/`tls_key`), `bridge.integrated` for single-player/LAN (loopback only). | see the PR |
| #50 | **Docs** en + ru: README first screen (store layout, current icon as placeholder), `docs/guide/` (getting started, one connect page per app, game master mode, hosting, security, mod authors, troubleshooting), generated `tools.md` with a CI drift check; GM kit retirement notes. | 3 blocking found, fixed |

## Done criteria and evidence

- **MCP served by the mod, both eras, tested against recorded Claude Code and Codex handshakes:** `McpProtocolTest`
  replays requests recorded on loopback from Claude Code 2.1.332 (modern `server/discover` probe and its fallback
  sequence) and Codex CLI 0.172.0 (`initialize` 2025-06-18); `McpEndpointTest` over real HTTP. On a trial server
  Claude Code stayed modern end to end (`server/discover` → `tools/list` → `tools/call` with an image it described);
  Codex `initialize`/`tools/list` 200.
- **Keyless sign-in, approve in game or console:** real logins on the trial server, browser played by curl, code typed
  into the server console: Codex ("Successfully logged in", CIMD), Claude Code ("Authenticated", CIMD; then
  `claude -p` with no token anywhere ran `status` and a `say`), opencode ("Authentication successful", DCR). Unit
  tests: `SignInTest` (6), `SignInHttpTest` (2, the whole dance over HTTP to an `/mcp` call as the new agent).
- **"Tell your agent: connect":** `claude -p "Connect to my Minecraft server at http://127.0.0.1:25650/mcp ..."` ran
  `claude mcp add` itself and reported "Needs authentication". The console prints the sentence at start.
- **One-click links:** `/almighty connect` (VS Code, Cursor, LM Studio, Goose; claude.ai only with a public HTTPS URL).
- **ChatGPT:** the ChatGPT desktop app in Codex mode is the documented path (same config as Codex CLI, verified via
  Codex CLI); browser ChatGPT documented as advanced (needs a public HTTPS address).
- **Knowledge:** `NotesTest` loads real packs (datapack overrides a mod note, empty removes, legacy note, provenance);
  GameTest `catalogAndWorldNotes`.
- **Plugin in Claude and Agent Plugins formats:** installed in Claude Code (5 skills + server entry "Needs
  authentication" → sign-in) and Codex CLI (5 `SKILL.md`).
- **Retiring crash_guard.py / recipes.py / GM_OWNERS, Python adapter optional:** no setup path needs them; the server
  enforces the deny list (core step 2). The two scripts stay in the repo because existing Claude Code sessions point
  their hook at `crash_guard.py` (a missing hook fails closed and would stop that game master; mission limit: Open
  Skies behaviour must not change). `GM_OWNERS` is only the optional chat channel's setting.
- **Docs en+ru, README first screen:** #50.

## Deviations, with reasons

- Plan 7.2 "a non-loopback bind without TLS is refused": logs a warning instead; refusing would stop setups that bind a
  private container address (Open Skies may), which the mission forbids changing. No self-signed generation: owners use
  a reverse proxy/tunnel or bring a certificate (Cursor and VS Code handle self-signed MCP certificates poorly anyway).
- Plan 4.10 "mark the client as the owner's own" at approval: left to core's origin work (step 6); today every agent has
  the same rights.
- Two instances of one app share one agent name (a second login replaces the first); name another with
  `/almighty approve <code> <name>`.

## Not verified here

- Cursor, VS Code, Antigravity, LM Studio, Goose, Cline, Continue, the ChatGPT desktop app: desktop apps, no GUI in
  this container. Their settings follow each product's docs; the server supports what they use (DCR, CIMD, loopback
  and https redirects).
- Gemini CLI 0.71.1: `gemini mcp add` checked; its sign-in needs an interactive session with a Google account.
- A model-driven Codex tool call: needs an OpenAI login.
- claude.ai / browser ChatGPT: need a public HTTPS address.
