# Almighty

English · [Русский](README.ru.md)

An AI game master for your Minecraft server.

Almighty is a server-side NeoForge mod for Minecraft 1.21.1. It opens a local HTTP bridge through which an AI agent,
such as Claude Code, runs the game on the server without a game client. Players install nothing: the mod has no
blocks, items or network packets of its own.

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
- Spawn bots. A bot is a server player that acts only through the packets a client would send: keys and mouse,
  hotbar, menus, chat and commands, signs, respawning. Bots walk, mine, build, use chests, ride horses, boats, pigs,
  striders and camels, and fly Immersive Aircraft planes. The tab list and chat show them with a `[bot]` marker.

## How it works

- On a dedicated server the mod starts an HTTP bridge, by default on `127.0.0.1:25650`. Single-player worlds do not
  start it. The bridge takes `POST /rpc` with `{"method": ..., "params": {...}}` and the header
  `Authorization: Bearer <token>`.
- On first start the mod writes a random token to `config/almighty/token` (32 bytes as hex; on Linux and macOS only
  the file's owner can read it). The token is not written to the log.
- The world is read and changed only on the server thread: between ticks, or by jobs that get a fixed time per tick
  (`work.ms_per_tick`, 3 ms by default). Large builds and pictures do not stall the server.
- The agent connects through the MCP adapter [`mcp/almighty.py`](mcp/almighty.py). It has 14 tools: `status`,
  `entities`, `command`, `say`, `script`, `rule`, `events`, `view`, `build`, `area`, `job`, `bot`, `notes`, and `call`
  for any bridge method by name. The 32 bridge methods are listed in [AGENTS.md](AGENTS.md) (in English).
- Any player can write to the game master privately with `/gm <text>`. Other players and operators do not see the
  message, and the agent can answer that player alone. If the game master is unavailable, the reply follows the
  player's language: Russian for `ru_ru`, English otherwise, without a client mod.
- [`mcp/gm/`](mcp/gm/README.md) is a kit for running the game master as one Claude Code session: a chat channel that
  wakes the session when players address it, a hook that blocks calls known to crash a server, and recipes.

## Notes for other mods

A mod or datapack can leave the agent a note on how to use its mechanics: its commands, rules and pitfalls. The note
is a Markdown file `data/<namespace>/almighty/notes/<name>.md` in the mod's or datapack's data, with the id
`<namespace>:<name>`. The adapter appends the server's notes to its instructions when it connects, and the `notes`
tool returns the current ones, for example after `/reload`. A datapack overrides a mod's note by using the same path;
an empty file removes the note. The mod does not depend on Almighty: without it, the file is ignored.

## Install

1. Use a Minecraft 1.21.1 server with NeoForge 21.1.264 or newer. Put `almighty-<version>.jar` from the
   [releases](https://github.com/zentixua/almighty/releases) into the server's `mods/` folder.
2. Start the server once. It creates `config/almighty-common.toml` and `config/almighty/token`.
3. On the agent's machine, install [uv](https://docs.astral.sh/uv/), get the adapter from the repository at the
   release tag, and add it to Claude Code:
   ```sh
   git clone --branch v<version> https://github.com/zentixua/almighty
   claude mcp add almighty -e ALMIGHTY_TOKEN_FILE=/path/to/server/config/almighty/token \
     -- uv run /path/to/almighty/mcp/almighty.py mcp
   ```
   Keep the name `almighty`: the hook and the chat channel in `mcp/gm` match tools named `mcp__almighty__*`.
   If the bridge runs on another machine, add `-e ALMIGHTY_URL=http://<address>:25650`. The token can also be passed
   directly in `ALMIGHTY_TOKEN`.

The adapter also works from the command line, with the same environment variables:

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
- Keep the token secret. Anyone who has it controls the server. To replace it, delete `config/almighty/token` and
  restart the server.
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

With Java 21: `./gradlew build` compiles the mod and runs the unit tests; `./gradlew runGameTestServer` runs the
GameTests on a headless server with Immersive Aircraft and Sable. The `mcp/gm` kit is tested with
`python3 -m unittest discover -s mcp/gm/tests` and `node --test` in `mcp/gm/almighty-chat`. The developer guide with
the internals, every bridge method and the known pitfalls is [AGENTS.md](AGENTS.md), in English.

## License

Almighty is written by ZentixUA and licensed under the GNU General Public License, version 3 only
([LICENSE](LICENSE)). The jar bundles Groovy 4, which is licensed under the Apache License 2.0.
