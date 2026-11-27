# Getting started

English · [Русский](getting-started.ru.md)

From a jar to an AI agent playing game master on your server, in three steps. You need a Minecraft server — 1.21.1
on NeoForge 21.1.264+, or 26.1.2 or 26.3 on NeoForge or Fabric (the jar's name says which: `almighty-<version>+<game
version>-<loader>.jar`) — and an AI agent app on your computer (Claude Code, Codex, the ChatGPT desktop app in
Codex mode, Cursor, VS Code, opencode, Gemini CLI, Antigravity or a local model app).

## 1. Install the mod

Put `almighty-<version>.jar` from the [releases](https://github.com/zentixua/almighty/releases) into the server's
`mods/` folder and start the server. Players install nothing. When the server is ready, its console says:

```
Almighty ready - tell your AI agent: "connect to my Minecraft server at http://127.0.0.1:25650/mcp" (all ways: /almighty connect)
```

## 2. Tell your agent to connect

Say it in your agent app, in your own words:

```
Connect to my Minecraft server (Almighty) at http://127.0.0.1:25650/mcp
```

The agent adds the server to its own settings (most apps ask you to allow that once). Some apps need a new session
before the new server's tools appear. If your app cannot change its own settings, [its page](connect/README.md)
has the one line or the link to use instead; `/almighty connect` in the server console shows them too.

The address above works when the agent runs on the same machine as the server. For a server on a VPS or a hosting
panel, see [Hosting](hosting.md).

## 3. Approve the sign-in with its code

The first time the agent calls the server, your app opens a sign-in page in the browser. It shows a four-digit code
and the app's name. In the game (as an operator) or in the server console, type:

```
/almighty approve 4821
```

with the code from the page. The page returns to your app, and the agent is connected. Nobody copies a token or
edits a file. Only someone who can see the page and type on the server can approve it, so a stranger's sign-in
cannot be approved by mistake.

Your own app on your own computer should act with your rights (run commands and scripts too): approve it with its
name and the word `owner`, for example `/almighty approve 4821 claude-code owner`. Without `owner` it may look,
build, talk and drive bots, but not run commands or scripts (it can still be raised later in
`config/almighty/policy.toml`).

Each app you approve becomes its own agent (`claude-code`, `codex`, ...): `/almighty agents` lists them with their
last call, and `/almighty agent remove <name>` ends one.

## What next

- Ask the agent to look around: "show me a map of spawn", "who is online", "what mods does this server have".
- Players talk to the game master with `/gm <text>`. To have the agent answer them as they come, see
  [Game master mode](game-master.md).
- Teach it your server: "remember that the arena is at 5000 70 5000" goes into a world note that any agent on this
  server reads next time.
- Install the [Almighty plugin](../../plugin/almighty/README.md) for the game master skills in your app (optional).
- Read [Security](security.md) before you open the server to people you do not know.

Something did not work: [Troubleshooting](troubleshooting.md).
