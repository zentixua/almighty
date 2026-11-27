# Game master mode

English · [Русский](game-master.ru.md)

Connected, the agent answers you. To have it answer players as they write (`/gm <text>` or its name in chat), it
needs to keep running and watch the server's event feed. Each app has its own way to keep a session running; the
server side is the same for all: the `events` tool waits up to 25 seconds for new events and returns them.

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

Things the game master keeps between sessions belong in world notes (`notes {id: "world:<name>", text}`): they stay
with the world and any later session, or another app, reads them. Its own style and your preferences belong in the
app's memory (Claude Code: `CLAUDE.md`; Codex: `AGENTS.md`).
