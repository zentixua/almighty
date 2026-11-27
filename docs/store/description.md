[Русский](https://github.com/zentixua/almighty/blob/main/docs/store/description.ru.md)

![Almighty](https://raw.githubusercontent.com/zentixua/almighty/main/docs/brand/store/modrinth-banner-1920x480.png)

**An AI game master for your Minecraft server.** Connect Claude Code, Codex (also in the ChatGPT desktop app),
Cursor, VS Code or a local model, and it plays game master on your server: it sees the world, talks to players,
builds with undo, runs events and drives its own bot players. Server-side only: players install nothing.

Minecraft 1.21.1, 26.1.2 and 26.3 · NeoForge and Fabric (see each file for its game version and loader) · GPL-3.0

## What it is, plainly

Almighty is **owner-operated remote control of your server by an AI agent**. It opens a small web endpoint on the
server (by default only on `127.0.0.1`, the machine itself). An app you choose connects to it, signs in with a
four-digit code that you type on the server, and can then act with the rights you give it: look, talk, build, run
commands, and — for the owner only — run Groovy code on the server. Nothing connects by itself, nothing reaches the
internet unless you put it there, and `/almighty pause` stops every agent at once.

## Quick start

1. Put the jar for your game version and loader into the server's `mods/` folder and start the server. The console
   says what to tell your agent.
2. Tell your agent: `connect to my Minecraft server at http://127.0.0.1:25650/mcp`.
3. It opens a sign-in page with a four-digit code. Type `/almighty approve <code> <name> owner` in the console or in
   game as an operator. Done: no tokens, no config files, no extra software.

Guides: [getting started](https://github.com/zentixua/almighty/blob/main/docs/guide/getting-started.md) ·
[connect your app](https://github.com/zentixua/almighty/blob/main/docs/guide/connect/README.md) ·
[security](https://github.com/zentixua/almighty/blob/main/docs/guide/security.md)

## What the agent can do

- **See the world:** a top-down map like a vanilla map, a view of a box from any side, and the view through the eyes
  of a player, a bot or a free camera, as pictures with a legend; blocks of a box as text.
- **Follow the game:** chat, joins, deaths with their cause, advancements, player commands and private `/gm`
  messages arrive in an event feed. The server itself decides which lines are meant for the game master.
- **Talk to players** in chat, titles or the action bar, to everyone or to one player, in the player's language.
- **Build with undo:** plans are placed in portions between ticks, so the server never stalls; every block the game
  master changes is journaled on disk and can be undone, even after a crash.
- **Run commands and functions**, load distant places without ticking them, and learn any modpack from the running
  server (blocks, items, recipes, commands, events, config).
- **Add rules:** reactions to game events, run in the same tick, that switch themselves off when they cost too much.
- **Spawn bots:** real server players driven only by the packets a client would send. They walk, mine, build, use
  chests, ride horses and boats, and fly Immersive Aircraft planes. They are marked `[bot]`.

## Who may make it do what

Players can ask the game master for things; the server checks every request against their own rights, so the agent
can never do more for a player than that player may do. `config/almighty/policy.toml` picks a preset:

- **friends** (new servers): anyone may talk to it; members' everyday requests run at once, anything bigger asks the
  right person with one click in chat.
- **public**: anyone may talk to it, unasked it acts as a guest, everything above a guest's rights asks the person whose right it
  uses. **Use this on a public server.**
- **private**: only the owners; the agent acts with their rights.

Zones (no building at spawn, an arena of its own), roles with a duration, hourly quotas, LuckPerms nodes, and claims of
Open Parties and Claims or FTB Chunks are respected. Command blocks, spawners, barriers and similar blocks are never
built for a player, and items are given only to the player who asked. Known server-crashing commands are refused for
every agent. Every change is in the audit log (`/almighty log`).

## Security

- The endpoint listens on `127.0.0.1` by default. To reach it from elsewhere, put it behind your own HTTPS reverse
  proxy or tunnel, or give it your certificate; never expose a plain port.
- An app gets in only with a code you type on the server; you can revoke it at any time (`/almighty revoke`).
- Raw scripts are for the owner only (on every preset). With scripts on, the owner's agent can run any code in the server's JVM with the rights of the
  operating system user running the server: files, processes, network. Turn scripts off with
  `scripts.enabled = false` if you do not need them.

## Optional mods

Almighty depends on no other mod. When installed, it also works with Immersive Aircraft (bots fly its aircraft),
Sable (the agent reads ships), Open Parties and Claims and FTB Chunks (claims are respected), and LuckPerms (permission
nodes `almighty.*`).

## For mod and datapack authors

Ship a Markdown note at `data/<namespace>/almighty/notes/<name>.md` (Agent Skills front matter optional) and agents
learn how to use your mechanics. Without Almighty the file is ignored.

## AI disclosure

- **AI-generated code and text:** Almighty's code, documentation and these page texts are written by an AI (Anthropic's
  Claude, through Claude Code) under the author's direction, review and testing. The author designs it, decides every
  behaviour and runs it on a server with friends.
- **AI functionality:** the mod exists so that an external AI agent, chosen and run by the server owner, can act on
  the server. The mod itself contains no AI model and sends nothing to any AI service.
- **External system interactions:** an HTTP endpoint on the server (loopback by default), a sign-in flow, and, when
  the owner enables scripts, code execution on the server machine.
- **Images:** the icon and banners are hand-made pixel art drawn by small programs (no image generation); the gallery
  shows pictures the mod itself renders on a server.

## Source and license

Source, issues and documentation: https://github.com/zentixua/almighty. GPL-3.0-only; the jar bundles Groovy under
the Apache License 2.0.
