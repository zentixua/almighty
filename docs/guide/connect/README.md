# Connect your agent

English · [Русский](README.ru.md)

The server speaks MCP at `http://<address>:25650/mcp` (from the same machine: `http://127.0.0.1:25650/mcp`). Every app
below can add it when you ask in plain words ("connect to my Minecraft server (Almighty) at ..."); each page also
gives the one line to run yourself. Sign-in is the same everywhere: the app opens a page with a four-digit code,
you type `/almighty approve <code>` in the game or the server console.

`/almighty connect` in the console (or in the game, as an operator) prints the address, these lines and clickable
links for the apps that have install links.

| App | Page | Checked end to end |
|---|---|---|
| Claude Code (terminal, and the Code tab of the Claude desktop app) | [claude-code.md](claude-code.md) | Yes: asked in words, signed in with a code, then used headless |
| Codex CLI, the Codex IDE extension, **the ChatGPT desktop app in Codex mode** | [codex.md](codex.md) | Yes: `codex mcp add`, sign-in with a code, reconnects as its own agent |
| Cursor | [cursor.md](cursor.md) | Settings from Cursor's docs; not run here (needs a desktop) |
| VS Code (Copilot agent mode) | [vscode.md](vscode.md) | Settings from VS Code's docs; not run here (needs a desktop) |
| opencode | [opencode.md](opencode.md) | See the page |
| Gemini CLI | [gemini-cli.md](gemini-cli.md) | See the page |
| Antigravity | [antigravity.md](antigravity.md) | Settings from Antigravity's docs; not run here |
| Local models: LM Studio, Goose, Cline, Continue, Ollama through them; GLM (z.ai) | [local-models.md](local-models.md) | Settings from each app's docs |
| Scripts, CI, `claude -p`, `codex exec` (no browser) | [headless.md](headless.md) | Yes: `claude -p` with an agent token |
| claude.ai, Claude Desktop chat, ChatGPT on the web (advanced: needs a public address) | [cloud.md](cloud.md) | No: needs a public HTTPS address |

ChatGPT users: use the **ChatGPT desktop app in Codex mode** (it runs Codex on your computer, included in every
ChatGPT plan). ChatGPT in the browser connects from OpenAI's servers, so it needs your server on a public HTTPS
address; see [cloud.md](cloud.md).

Small local models: add `?profile=compact&images=false` to the address for 6 short tools and no pictures.
