# Claude Code

English · [Русский](claude-code.ru.md)

Works in the terminal and in the Code tab of the Claude desktop app.

**Ask:** "Connect to my Minecraft server (Almighty) at http://127.0.0.1:25650/mcp". Claude Code runs
`claude mcp add` itself (you allow it once) and tells you to sign in.

**Or yourself:**

```sh
claude mcp add --transport http --scope user almighty http://127.0.0.1:25650/mcp
```

**Sign in:** in a session, `/mcp`, choose `almighty`, Authenticate (or `claude mcp login almighty` in a terminal).
The browser shows a code; type `/almighty approve <code>` on the server. Claude Code keeps and refreshes the login
itself. It connects as agent `claude-code`.

Keep the name `almighty`: the optional chat channel and hooks match tools named `mcp__almighty__*`.

**Plugin (optional):** game master skills and the server entry in one step:

```sh
claude plugin marketplace add zentixua/almighty
claude plugin install almighty@almighty
```

Headless (`claude -p`, the Agent SDK): the stored login works there too; for CI use an agent token, see
[headless.md](headless.md). To answer players as they write, see [Game master mode](../game-master.md).
