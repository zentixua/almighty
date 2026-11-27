<p align="center"><img src="docs/brand/banner/readme-banner-1280x320.png" alt="Almighty"></p>

<h1 align="center">Almighty</h1>

<p align="center"><b>An AI game master for your Minecraft server.</b><br>
Connect Claude Code, Codex (also in the ChatGPT desktop app), Cursor, VS Code or a local model, and it plays game
master on your NeoForge server: it sees the world, talks to players, builds with undo, runs events and drives its own
bot players.</p>

<p align="center">
Minecraft 1.21.1 · NeoForge 21.1.264+ · server-side only, players install nothing · GPL-3.0<br>
English · <a href="README.ru.md">Русский</a>
</p>

<p align="center">
<img src="docs/media/eye.png" width="32%" alt="What a bot sees">
<img src="docs/media/map.png" width="32%" alt="The map view">
<img src="docs/media/look.png" width="32%" alt="A side view of a building">
</p>

## Quick start

1. Put `almighty-<version>.jar` from the [releases](https://github.com/zentixua/almighty/releases) into the server's
   `mods/` folder and start it. The console says: `Almighty ready - tell your AI agent: "connect to my Minecraft
   server at http://127.0.0.1:25650/mcp"`.
2. Tell your agent exactly that. It adds the server itself.
3. Your agent opens a sign-in page with a four-digit code. Type `/almighty approve <code> <name> owner` in the game
   (as an operator) or in the console (`owner`: it is your own app and acts with your rights). Done: no tokens, no
   config files, no extra software.

More: [Getting started](docs/guide/getting-started.md) · [Connect your app](docs/guide/connect/README.md) ·
[Game master mode](docs/guide/game-master.md) · [Hosting](docs/guide/hosting.md) · [Guide](docs/guide/README.md)

> **Security.** A connected agent has operator rights and, with scripts on, runs code on the server machine. The
> bridge listens only on `127.0.0.1` by default, every app signs in with a code you type on the server, and known
> server-crashing commands are refused for every agent. Read [Security](docs/guide/security.md) before opening the
> server to people you do not know.

## What the agent can do

- See the world: a top-down map like a vanilla map, a view of a box from any side, from above or from below, and
  the view through the eyes of a player, a bot, any entity or a free camera. These come as PNG pictures with a
  legend; the eye view also lists what is in sight and under the crosshair. The blocks of a box can also be read as
  text.
- Read the server: tick time, players with position, health, inventory and effects, entities in an area, and ships
  when Sable is installed.
- Follow the game through an event feed: chat, joins and leaves, deaths with their cause, advancements, dimension
  changes, player commands, private messages to the game master and server stalls.
- Talk to players in chat, as a title, subtitle or action bar, to everyone or to chosen players.
- Run commands and functions with operator rights (level 4), including macro functions without a datapack file.
- Build with undo: fill, set and layered plans, placed in portions between ticks. Undo restores blocks and the
  contents of block entities such as chests.
- Load the chunks of a place where nobody is, without ticking them, to view or build there.
- Run Groovy scripts on the server thread, with a time limit.
- Add rules: a reaction to any NeoForge event of the game or of any mod. A rule runs on the server in the same tick,
  can cancel the event and can be saved with the world. A rule turns itself off when it goes over its time budget,
  fails 10 times in a row or floods the feed.
- Below the owner, rules are declarative: conditions on the event's fields and actions (cancel, write to the feed,
  say, run a command). Such a rule acts for the player who asked for it (or the game master acting on its own),
  with their rights checked at every event: it cancels only where they may build, stops working when they lose the
  right, and expires after 7 days at most. Scripts in `config/almighty/scripts/` run by name once the owner
  approves them with `/almighty script approve <name>`, and only until the file changes.
- Spawn bots. A bot is a server player that acts only through the packets a client would send: keys and mouse,
  hotbar, menus, chat and commands, signs, respawning. Bots walk, mine, build, use chests, ride horses, boats, pigs,
  striders and camels, and fly Immersive Aircraft planes. The tab list and chat show them with a `[bot]` marker.

## How it works

- On a dedicated server the mod starts an HTTP bridge, by default on `127.0.0.1:25650`. Single-player worlds do not
  start it. The bridge takes `POST /rpc` with `{"method": ..., "params": {...}}` and the header
  `Authorization: Bearer <token>`.
- On first start the mod writes a random token to `config/almighty/token` (32 bytes as hex; on Linux and macOS only
  the file's owner can read it). The token is not written to the log. It belongs to the agent `gm`; each further agent
  gets its own token with `/almighty agent add <name>` (in `config/almighty/agents/<name>.token`), and
  `/almighty agents` shows who called last and from where.
- Requests from web browsers (with an `Origin` header) are refused unless that origin is listed in
  `bridge.allowed_origins`. Five wrong tokens within a minute lock the sending address out for a while.
- The world is read and changed only on the server thread: between ticks, or by jobs that get a fixed time per tick
  (`work.ms_per_tick`, 3 ms by default). Large builds and pictures do not stall the server.
- The same port serves MCP at `/mcp` (Streamable HTTP, both the current and the earlier protocol versions), so any
  MCP app connects by address, with no adapter. Apps sign in with the standard MCP login: the server shows a
  four-digit code on its sign-in page and the owner types `/almighty approve <code>`. The agent gets 14 tools:
  `status`, `entities`, `command`, `say`, `script`, `rule`, `events`, `view`, `build`, `area`, `job`, `bot`, `notes`,
  and `call` for any bridge method by name ([Tools](docs/guide/tools.md)). The bridge methods are listed in
  [AGENTS.md](AGENTS.md) (in English).
- Any player can write to the game master privately with `/gm <text>`. Other players and operators do not see the
  message, and the agent can answer that player alone. If the game master is unavailable, the reply follows the
  player's language: Russian for `ru_ru`, English otherwise, without a client mod. The server itself decides which
  chat lines are meant for the game master (its name, an ongoing conversation, or a player alone online), limits each
  player to 20 such messages an hour, and tells the player when the game master is away. With several agents,
  `/gm @<agent> <text>` picks one.
- The [Almighty plugin](plugin/almighty/README.md) (optional, for Claude Code, Codex, Cursor and VS Code) brings the
  game master skills and the server entry. [`mcp/gm/`](mcp/gm/README.md) adds, for Claude Code only, a chat channel
  that wakes the session the moment a player writes. Its crash-guard hook and recipe runner are no longer needed:
  the server refuses crash-prone calls for every app itself.

## Notes for other mods

The agent learns any modpack from the running server: `notes {query: "<mod id>"}` returns the mod's blocks with
states, items, recipes, commands, events and config values. A mod or datapack can also leave the agent a note on how
to use its mechanics: a Markdown file `data/<namespace>/almighty/notes/<name>.md`, optionally with Agent Skills front
matter (`name`, `description`). Agents see an index and read a note when they need it; a datapack overrides a mod's
note at the same path; an empty file removes it. Without Almighty the file is ignored. Details:
[For mod and datapack authors](docs/guide/for-mod-authors.md).

## Install

1. Use a Minecraft 1.21.1 server with NeoForge 21.1.264 or newer. Put `almighty-<version>.jar` from the
   [releases](https://github.com/zentixua/almighty/releases) into the server's `mods/` folder and start it.
2. Tell your agent app: `connect to my Minecraft server at http://127.0.0.1:25650/mcp`, or add it yourself as its
   page says ([Connect your agent](docs/guide/connect/README.md)).
3. Approve the sign-in with the code from the page: `/almighty approve <code>`.

For scripts and CI without a browser, give an agent a token (`/almighty agent add <name>`) and pass it from an
environment variable ([headless](docs/guide/connect/headless.md)). The command line
[`mcp/almighty.py`](mcp/almighty.py) (`call`, `follow`, and a stdio relay for apps without HTTP) is optional and needs
[uv](https://docs.astral.sh/uv/):

```sh
uv run mcp/almighty.py call status                                      # one call, JSON answer
uv run mcp/almighty.py call map '{"center": [0, 0], "size": 256}' --png map.png
uv run mcp/almighty.py follow                                           # event feed, one JSON line per event
```

## Configuration

`config/almighty-common.toml`:

| Key | Default | Meaning |
|---|---|---|
| `bridge.enabled` | `true` | Start the HTTP bridge on a dedicated server. |
| `bridge.host` | `"127.0.0.1"` | Address the bridge listens on. |
| `bridge.port` | `25650` | Bridge port. |
| `bridge.public_url` | `""` | The public HTTPS address when a reverse proxy or tunnel puts the bridge on the internet (cloud apps). |
| `bridge.allowed_origins` | `[]` | Browser origins allowed to call the bridge; requests from other web pages are refused. |
| `bridge.tls_cert`, `bridge.tls_key` | `""` | Your certificate (PEM chain) and key (PKCS#8 PEM): the bridge then speaks only HTTPS. |
| `bridge.integrated` | `false` | Also start the bridge in single-player and LAN worlds (loopback only). |
| `work.ms_per_tick` | `3.0` | Time per server tick for builds, undos and pictures, in ms (0.5 to 20). |
| `work.build_max_blocks` | `2000000` | Maximum number of block positions in one build. |
| `work.max_chunks` | `1500` | Maximum number of chunks the agent keeps loaded at once: builds and prepared areas, counting a ring of neighbouring chunks. |
| `feed.lag_ms` | `1000` | A `lag` event is written when more than this many ms pass between two ticks. |
| `scripts.enabled` | `true` | The `script` method and rules with a script. |
| `bots.max` | `8` | Maximum number of bots on the server at once. A bot keeps chunks loaded like a player. |
| `bots.marker` | `"[bot]"` | Marker before a bot's name in the tab list and chat. Empty: no marker. |
| `bots.allow_disguise` | `false` | Allow a bot without the marker, or with the name or skin of a player the server knows. A bot always has its own UUID and gives the name up when that player joins. |
| `name` | `"Claude"` | Name the game master uses in chat and as the source of its commands. |

Rules saved with `persist` are stored in `<world>/almighty/rules.json`.

## Security

The bridge gives the agent operator rights. With scripts on, which is the default, it also runs any code in the
server's JVM with the rights of the operating system user that runs the server: files, processes, network. Treat
access to the bridge like shell access to the server machine.

- Keep `bridge.host` on loopback (`127.0.0.1`, the default) or on a network that only the server owner can reach, for
  example the address of a Docker container whose port is not published. The bridge speaks plain HTTP; do not expose
  its port to the internet.
- Keep the tokens secret. Anyone who has one controls the server. To replace a token, run `/almighty revoke <agent>`
  in the console: the old token stops working at once and the new one is in the same file.
- Who may make the game master do what is set in `config/almighty/policy.toml` (written with comments on first
  start; `/almighty reload` after editing). `preset = "private"` (the default for existing and new servers in this
  version) keeps everything as before. `friends` and `public` make the game master act, unasked, as a member or a
  guest would; a player's request then runs with that player's rights. Owners are listed by UUID (`/almighty whoami`
  shows yours). Each capability is a permission node `almighty.<capability>`, so LuckPerms can grant them.
- A new server starts as a friends' server (`preset = "friends"`): anyone may talk to the game master, and unasked
  it acts as a member would. Servers updating from an earlier version keep `private`, so nothing changes for them.
  Zones (`[zones.<name>]` in `policy.toml`) protect places (for example no building at spawn) or give the game
  master its own arena. A zone can deny `build`, `area`, `bots` and `command`; a command is checked where it runs and
  at every coordinate it names, not by the entities it selects. `/almighty role <player> builder 3h` gives a player a role for a while (without LuckPerms);
  `[quotas]` limits how many blocks and items one player's requests may take per hour (the game master's own builds
  share one such budget). With Open Parties and Claims
  or FTB Chunks installed, the game master never builds in a claim the player may not build in.
- The owner can also change the policy by asking the game master ("deny building at spawn", "make this a friends'
  server"). A change you ask for is applied at once; one the game master proposes on its own applies at once only if
  it restricts, and anything that grants more (a wider preset, a new owner, an arena, larger quotas) waits for the
  owner to confirm it in game (`/almighty inbox`). Players cannot change the policy. Raising an agent to an owner
  ceiling is done only by editing the file. Each change keeps the previous file in `config/almighty/policy.history/`;
  `/almighty policy undo` puts the previous one back.
- Every game master call that changes something, and every refused call, is written to
  `logs/almighty/audit-<date>.jsonl` (kept 30 days, `audit.days`). `/almighty log [agent|player] [count]` shows the
  latest in game or in the console. `/almighty pause [agent]` is the red button: that agent (or every agent) can
  only look until `/almighty resume`, its builds wait and its bots stop.
- Some commands and calls are refused for every agent because they crash, freeze or stop the server: `stop`,
  `reload`, `save-off`, `save-all flush`, `forceload add`, `tick rate` above 100, `/almighty` (agents and tokens are
  the owner's), and building, loading or teleporting at Sable ship plot coordinates. Scripts that take chunk tickets,
  load chunks synchronously, start threads, register listeners, sleep, exit the JVM or start processes are refused
  too. The owner can still run such commands in the console.
- Game master work shares a fixed time per tick (`work.ms_per_tick`). When the server lags (average tick above 50 ms
  for 10 seconds, at 20 ticks per second), game master work gets a quarter of its time until the server recovers.
- To turn scripts off, set `scripts.enabled = false`. The `script` method and rules with a script are then refused,
  and saved rules with a script do not load. Commands and rules without a script still work.
- To turn the bridge off, set `bridge.enabled = false`.

## Optional mods

Almighty depends on no other mod. Its support for these mods loads only when they are installed:

- [Immersive Aircraft](https://modrinth.com/mod/immersive-aircraft): bots fly its aircraft with the same controls as a
  player. Checked with versions 1.5.2, 1.6.0 and 1.7.0 for Minecraft 1.21.1.
- Sable, the physics mod behind Create Aeronautics: the `ships` method and `gm.ship(name)` in scripts give a ship's
  position, rotation, velocity and bounds. A build or a prepared area on a ship plot is refused, because Sable
  manages those chunks.

## Building

With Java 25 (Gradle and Stonecutter; each game version compiles with its own Java toolchain): `./gradlew build`
compiles the mod for every game version and loader in `versions/` and runs the unit tests; `./gradlew
runGameTestServer` runs the GameTests on a headless server with Immersive Aircraft and Sable. The `mcp/gm` kit is tested with
`python3 -m unittest discover -s mcp/gm/tests` and `node --test` in `mcp/gm/almighty-chat`. The developer guide with
the internals, every bridge method and the known pitfalls is [AGENTS.md](AGENTS.md), in English.

## License

Almighty is written by ZentixUA and licensed under the GNU General Public License, version 3 only
([LICENSE](LICENSE)). The jar bundles Groovy 4, which is licensed under the Apache License 2.0.
