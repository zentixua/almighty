Almighty is a server-side NeoForge mod for Minecraft 1.21.1. It lets an AI agent, such as Claude Code, act as the
game master of your server: it sees the world, follows what players do, talks to them and changes the game, all
without a game client. Players install nothing.

> **Security.** The bridge gives the agent operator rights. With scripts on, which is the default, it can also run
> any code in the server's JVM with the rights of the operating system user that runs the server. Keep the bridge on
> `127.0.0.1` (the default) or on a network only you can reach, never expose its port to the internet, and keep the
> token in `config/almighty/token` secret. If you do not need scripts, set `scripts.enabled = false`.

## What the agent can do

- **See the world:** a top-down map like a vanilla map, a view of a box from any side, from above or from below, and
  the view through the eyes of a player, a bot, any entity or a free camera. These come as PNG pictures with a
  legend. The blocks of a box can also be read as text.
- **Read the server:** tick time, players with position, health, inventory and effects, entities in an area.
- **Follow the game:** chat, joins and leaves, deaths with their cause, advancements, dimension changes, player
  commands and server stalls arrive in an event feed.
- **Talk to players:** chat, title, subtitle or action bar, to everyone or to chosen players. Any player can write
  to the game master privately with `/gm <text>`.
- **Run commands and functions** with operator rights, including macro functions without a datapack file.
- **Build with undo:** fill, set and layered plans are placed in portions between ticks, so the server does not
  stall. Undo restores blocks and the contents of chests and other block entities.
- **Load distant places** without ticking them, to view or build where nobody is.
- **Run Groovy scripts** on the server thread, with a time limit.
- **Add rules:** a reaction to any NeoForge event of the game or of any mod, run in the same tick. A rule can cancel
  the event and can be saved with the world. It turns itself off when it goes over its time budget, fails 10 times in
  a row or floods the feed.
- **Spawn bots:** server players that act only through the packets a client would send. Bots walk, mine, build, use
  chests and menus, chat, ride horses, boats, pigs, striders and camels, and fly Immersive Aircraft planes. They are
  marked `[bot]` in the tab list and chat, and cannot take the name or skin of a known player unless the server
  owner allows it.

## How the agent connects

The mod starts an HTTP bridge on a dedicated server, on `127.0.0.1:25650` by default; single-player worlds do not
start it. Every call carries a token that the mod generates on first start. The agent connects through an MCP
adapter, a Python script from the GitHub repository run with [uv](https://docs.astral.sh/uv/), which gives it 14
tools. The repository also has a kit for running the game master as one Claude Code session: a chat channel that
wakes the session when players address it, a hook that blocks calls known to crash a server, and recipes.

## Notes for mod and datapack authors

A mod or datapack can tell the agent how to use its mechanics by shipping a Markdown file
`data/<namespace>/almighty/notes/<name>.md`. The agent receives these notes when it connects and can reload them
later. Nothing changes for players or servers without Almighty.

## Install

1. Put the jar into the `mods/` folder of a Minecraft 1.21.1 server with NeoForge 21.1.259 or newer.
2. Start the server once. It creates `config/almighty-common.toml` and the token file `config/almighty/token`.
3. Connect the agent as described in the
   [README](https://github.com/zentixua/almighty#install), which also lists every setting.

## Optional mods

Almighty depends on no other mod.

- **Immersive Aircraft:** bots fly its aircraft with the same controls as a player.
- **Sable** (the physics behind Create Aeronautics): the agent can read a ship's position, rotation, velocity and
  bounds.

## Source and license

Source, issues and documentation: https://github.com/zentixua/almighty. Licensed under GPL-3.0-only; the jar bundles
Groovy 4 under the Apache License 2.0.

## About

I design Almighty and run it on my own server, where an AI game master plays with my friends and me. The code is
written with Claude Code. The icon is an in-game screenshot; the other images on this page are drawn by the mod on a
server.
