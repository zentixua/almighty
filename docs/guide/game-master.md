# Game master mode

English · [Русский](game-master.ru.md)

Connected, the agent answers you. To have it answer players as they write, it needs to keep running and watch the
server's event feed. Each app has its own way to keep a session running; the server side is the same for all: the
`events` tool waits up to 25 seconds for new events and returns them.

## How players reach the game master

- **`/gm <text>`** is a private message to the game master. Other players and operators do not see it, and the agent
  can answer that player alone. With several agents, `/gm @<agent> <text>` picks one.
- **Its name in public chat** (`name`, or the words in `feed.names`) addresses it too, even with stretched letters
  or, for names in `feed.typo_names`, one typo.
- **A conversation** stays open for 3 minutes after a player addressed the game master or got an answer: their lines
  reach it without the name.
- **A player alone on the server** reaches it with any chat line.

Messages go to the agent named in `feed.default_agent` (`gm` by default) while it runs; otherwise to your own app
(approved with `owner`) while that runs. No setting is needed for a freshly signed-in app of yours to hear players.
Each joining player is told that an AI game master reads the public chat and acts on messages addressed to it
(`[safety] privacy_notice`).

An agent hears only players trusted with what it can do unasked: on a private server only owners, on a friends'
server verified players, on a public server everyone ([Who an agent hears](permissions.md#who-an-agent-hears)).

The server, not the agent, decides what is addressed. Each player may address the game master 20 times an hour
(`feed.gm_messages`); more get "Too many messages to the game master; try again later." from the server. If the agent
has not read a `/gm` message or a line with its name within 30 seconds (`feed.away_seconds`), or made no call for a
minute, the server tells the player the game master is away; the message is kept. If that agent stays away for over a
minute and players are now routed to another app (for example your own app while `gm` is stopped), that app gets the
kept messages, for up to 10 minutes, unless a message named its agent with `/gm @<agent>` or the player may not
reach the new app. While you pause it
(`/almighty pause`), the server answers "The game master is paused by the server owner; your message is kept." These
lines follow the player's game language: Russian for `ru_ru`, English otherwise.

Each addressed message carries a ticket. When the agent acts on it (every tool takes it as `for`), the call runs with that player's rights
([Permissions](permissions.md)), and anything that affects others waits for the player's confirmation.

## Keep the agent running

The prompt to start with, in any app:

```
You are the game master of my Minecraft server (Almighty). Read notes {id: "almighty:game_master"} first.
Then loop: wait for events with events {after, wait: 25}; answer players who write to you (gm events, or chat
that names you) with say {to}; keep next as the next after. Keep going until I stop you.
```

| App | How to keep it running |
|---|---|
| Claude Code | Run that prompt in a session; `/loop` repeats a prompt on its own. For players to wake the session the moment they write, use the optional chat channel in [`mcp/gm`](../../mcp/gm/README.md) (a Claude Code plugin). |
| Codex, ChatGPT desktop app (Codex mode) | Run the prompt in a session, or `codex exec "<prompt>"` from a script or service for an unattended game master. |
| Others | Run the prompt; most apps keep a long-running task while it calls tools. |

## Memory

Things the game master keeps between sessions belong in world notes (`notes {id: "world:<name>", text}`): they stay
with the world, and any later session or another app reads them. The server records who wrote each note and for
whom. Its own style and your preferences belong in the app's memory (Claude Code: `CLAUDE.md`; Codex: `AGENTS.md`).
Your own notes for every agent go in `config/almighty/notes/<name>.md`; the game master writes them there when you, the
owner, ask.
