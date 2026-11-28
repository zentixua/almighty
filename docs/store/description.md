# Almighty: AI Game Master

![Almighty: an AI game master for your server](https://raw.githubusercontent.com/zentixua/almighty/main/docs/brand/store/modrinth-banner-1920x480.png)

An AI game master for your Minecraft server. Minecraft 1.21.1, 26.1.2 and 26.3, on NeoForge and Fabric.

Almighty connects an AI agent, such as Claude Code or Codex, to your server. The agent becomes the game master: it
sees the world, talks to players, builds, runs events and plays alongside you with its own bot players. You decide
how much of that it may do, for whom and where. Players install nothing.

## Features

### What the game master can do

- **See the world:** a top-down map, a building from any side, or the view through any player's or bot's eyes.
- **Follow the game:** chat, joins, deaths, advancements and player commands reach it as they happen.
- **Talk to players:** in chat, as a title or above the hotbar. Players can write to it privately with
  `/gm <text>`, or address it by name in chat ("Claude" by default). By default, each player who joins is told that an AI
  reads what is addressed to it.
- **Build:** from a few blocks up to two million, placed a little each tick so the server keeps running.
- **Run the game:** commands, items for players, and rules that react to events of the game and of other mods.
- **Learn your modpack:** blocks, items, recipes and commands of every installed mod, read from the running server.
- **Play alongside you:** bot players that walk, mine, build, open chests, ride horses, camels, pigs, striders and
  boats, and fly Immersive Aircraft planes. A bot uses only what a real player's client can send.

![What a bot sees: the eye view of an airport, drawn by the server, with the crosshair in the middle](https://raw.githubusercontent.com/zentixua/almighty/main/docs/media/eye.png)

![The map view: an airport from above, drawn like a vanilla map](https://raw.githubusercontent.com/zentixua/almighty/main/docs/media/map.png)

### You decide what it may do

- **A request is not an order:** when a player asks for something, the game master acts with that player's rights,
  never more.
- **Presets:** a new server starts as `friends`: players with a verified Minecraft account talk to the game master,
  and on its own it acts as a member would. On a public server, use `public`: everyone talks to it, and on its own it
  acts as a guest would. On `private`, only the owners talk to it, and on its own it acts with its full rights.
- **Confirmation:** something that affects other players and cannot be undone waits until the player who asked
  presses [Allow] in chat, or in a dialog on 26.x; the question is in the player's game language, English or
  Russian. A request beyond the player's rights, or a call beyond the game master's own rights, is refused, unless
  you list its kind under `ask_admin`; then an admin can approve it with one click, and the owners online are told
  of every such decision.
- **Roles:** owner, admin, builder, member and guest. Give one for a while with `/almighty role <player> builder 3h`,
  or let LuckPerms decide through the `almighty.*` permissions.
- **Zones, quotas and claims:** keep spawn free of building, give the game master an arena, limit blocks and items
  per player per hour. With Open Parties and Claims, FTB Chunks or, on Fabric, any protection mod that uses the
  Common Protection API, it changes blocks for a player, by building or by commands, only where that player may
  build, and on its own only outside claims.
- **Limits:** command blocks, spawners, barriers and similar blocks are never built for a player, and items go only
  to the player who asked.
- **Ask in words:** tell the game master "deny building at spawn" and it changes the policy for you. Anything it
  proposes on its own that grants more waits for your confirmation.

### Safe for your server

- **Undo:** every block the game master changes is saved to disk, even if the server crashes mid-build. Any change
  can be undone, chests with their contents; what players built or stored since stays as it is. A player who asked
  for something gets a "Done for you … [Undo]" line in chat.
- **Pause:** `/almighty pause` stops one agent or all of them at once. `/almighty log` shows what they did and what
  was refused.
- **Bots are marked:** `[bot]` before their names in the tab list and chat. Unless you allow it, a bot cannot take
  the name or skin of a player your server knows.
- **No lag:** game master work gets 3 ms per tick, and a quarter of that while the server lags. Several agents
  share that time equally, and limits on calls a minute and waiting jobs (below the owner also on bot time and
  script time) keep one agent from crowding out the rest. Commands known to crash or freeze a server are refused
  for every agent.

## What it is, plainly

Almighty is remote control of your server by an AI agent that you, the owner, run. It opens a small web endpoint on
the server, by default only on `127.0.0.1`, the server machine itself. An app you choose connects to it and signs in
with a four-digit code that you type on the server, or with a token you made for it with `/almighty agent add`;
no app gets in otherwise, and you can revoke one at any time with `/almighty revoke`. The endpoint is reachable from the internet only if you put it there: use your own HTTPS
reverse proxy or tunnel, or give it your certificate, and never open a plain port. The mod refuses plain HTTP
anywhere but the server machine itself, unless you allow it for a private container network with
`bridge.allow_plain_http_off_loopback = true`.

By default, only the owner may run Groovy scripts, on every preset (a permissions plugin can grant it to others,
and builders may run scripts the owner approved by name). With scripts on, the owner's agent can run any code
on the server machine with the rights of the user running the server: files, processes, network. Set
`scripts.enabled = false` if you do not need them. The server itself goes online only to read a signing-in app's
public description and to look up a Minecraft skin when the agent asks for a bot with a player's skin.

## Getting started

1. Put the jar for your Minecraft version and loader into the server's `mods` folder and start the server.
2. Tell your AI app: "connect to my Minecraft server at http://127.0.0.1:25650/mcp". `/almighty connect` in the
   server console shows the exact line or link for each app.
3. The app opens a sign-in page with a four-digit code. Type `/almighty approve <code> <name> owner` in the console
   for your own app. Without `owner` it gets less: no scripts, and only the commands its rights allow.

Once connected, the game master gets a check of your server's setup (preset, owners, identity, claims, HTTPS) and
tells you about any problem, with its fix.

Claude Code, Codex (also in the ChatGPT desktop app), Cursor, VS Code, opencode, Gemini CLI, Antigravity and local
models all work. For a server on a VPS or a hosting panel, see
[Hosting](https://github.com/zentixua/almighty/blob/main/docs/guide/hosting.md).

## Requirements and dependencies

**Minecraft 1.21.1 · NeoForge 21.1.264+ · Java 21**

**Minecraft 26.1.2 · NeoForge 26.1.2.130+ or Fabric Loader 0.20.1+ with Fabric API 0.155.3+ · Java 25**

**Minecraft 26.3 · NeoForge 26.3.0.118+ or Fabric Loader 0.20.1+ with Fabric API 0.167.0+ · Java 25**

Almighty runs on the server only; players join with a plain client. No other mods are required. Immersive
Aircraft (1.21.1, 26.1.2), Sable (1.21.1), Open Parties and Claims, FTB Chunks (1.21.1, 26.1.2), protection mods
that use the Common Protection API (Fabric) and LuckPerms are optional; their support loads only when they are
installed.

## FAQ

**NeoForge or Fabric?** Both, from 26.1.2. Minecraft 1.21.1 is NeoForge only.

**Does it include an AI?** No. You bring the AI app, with its own subscription or a local model. The mod is the
bridge between the app and your server.

**Client or server?** Server only. It starts on dedicated servers; for a single-player or LAN world, set
`bridge.integrated = true` in `config/almighty-common.toml`.

**How demanding is it?** Game master work is capped at 3 ms per tick and backs off while the server lags. Each bot
costs about as much as a player.

**How do I protect builds?** Set zones in `config/almighty/policy.toml`, or ask the game master to. With Open
Parties and Claims, FTB Chunks or a protection mod that uses the Common Protection API, claims are respected. Blocks the game master changed can be put back with undo.

**Can I use it in a modpack?** Yes, under the GPL-3.0 license. Mark it as server-side.

**I make mods. Can the game master learn mine?** It already reads your blocks, items, recipes and commands from the
running server. To explain more, ship a note at `data/<namespace>/almighty/notes/<name>.md`; see
[For mod and datapack authors](https://github.com/zentixua/almighty/blob/main/docs/guide/for-mod-authors.md).

## AI disclosure

- **AI-generated code and text:** Almighty's code, documentation and this page are written by an AI (Anthropic's
  Claude, through Claude Code) under the author's direction, review and testing. The author, ZentixUA, designs it,
  decides every behaviour and runs it on a server with friends.
- **AI functionality:** the mod exists so that an AI agent, chosen and run by the server owner, can act on the
  server. The mod itself contains no AI model and sends nothing to any AI service.
- **External system interactions:** the web endpoint described above; the sign-in, which reads the signing-in app's
  public HTTPS description; Mojang profile lookups for bot skins given as an account name; and code on the server
  machine through Groovy scripts, on by default and, unless you grant them, for the owner's agent only.
- **Images:** the icon and banners are pixel art drawn by small programs, with no image generation; the other
  pictures are rendered by the mod itself on a server.

## Links

[Guide](https://github.com/zentixua/almighty/blob/main/docs/guide/README.md) · [Permissions](https://github.com/zentixua/almighty/blob/main/docs/guide/permissions.md) · [FAQ](https://github.com/zentixua/almighty/blob/main/docs/guide/faq.md) · [Issues](https://github.com/zentixua/almighty/issues) · [Source](https://github.com/zentixua/almighty)

## License and credits

Made by ZentixUA. [GPL-3.0-only](https://github.com/zentixua/almighty/blob/main/LICENSE). Includes Groovy 4
(Apache License 2.0); the Fabric build also includes NightConfig (LGPL-3.0).
