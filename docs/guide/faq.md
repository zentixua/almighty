# FAQ

English · [Русский](faq.ru.md)

**Do players need to install anything?**
No. Almighty adds no blocks, items, entities or network packets, so vanilla clients join as usual.

**Which Minecraft versions and loaders?**
1.21.1 on NeoForge; 26.1.2 and 26.3 on NeoForge and on Fabric. See [Getting started](getting-started.md#1-install-the-mod).
Forge, Quilt, Paper and Spigot are not supported.

**Does it work in single-player or on LAN?**
The bridge starts on dedicated servers. Set `bridge.integrated = true` to start it in single-player and LAN worlds;
it then listens on the local machine only.

**Which AI does it use?**
Whichever you connect. Almighty is a bridge: any app that speaks MCP can be the agent, from Claude Code and Codex to a
model running on your own computer ([Connect your agent](connect/README.md)). You need your own account or plan with
that app's maker.

**Do I need an API key or a token?**
No. An agent app signs in through the browser with a four-digit code that you approve on the server; a request without
a sign-in is told so, with the command to run. Tokens are only for scripts and CI ([headless](connect/headless.md)).

**Does it cost anything?**
Almighty is free and open source. The AI app is billed by its maker as usual; a local model costs nothing but your
hardware.

**Can I use it in a modpack?**
Yes, under the GPL-3.0 license. It needs no other mod and is server-side only.

**Can several agents work at once?**
Yes. Each app you approve is its own agent with its own rights, audit trail and token. Players pick one with
`/gm @<agent> <text>`; the rest go to the agent in `feed.default_agent` while it runs, otherwise to your own app
(approved with `owner`).

**Can players tell the game master to do anything?**
No. A player's request runs with that player's rights, never above the agent's ceiling; what could affect others waits
for the player's confirmation, and what is above their rights is refused or goes to an admin only for the kinds you
list ([Permissions](permissions.md)).

**What if it breaks something?**
Every block it writes is journaled: the player who asked gets an `[Undo]` button, and you can undo any change with
`/almighty undo <change>`. `/almighty pause` at once stops every agent's builds, bots, rules
with scripts and rules made below the owner ([Safety](security.md#undo-audit-and-the-red-button)).

**Does it slow the server down?**
Game master work gets a fixed slice of each tick (3 ms by default) and a quarter of it while the server lags. A bot
costs as much as a player; `bots.max` limits them (8 by default).

**Are bots real players?**
They are server players without a client: they count in the player list and act only through the packets a client
sends. They are marked `[bot]` and never take a player's UUID.

**Can a cloud chat (claude.ai, ChatGPT in the browser) connect?**
Yes, with a public HTTPS address in front of the bridge ([cloud apps](connect/cloud.md)). A local app is simpler and
keeps the bridge private.
