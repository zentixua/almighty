# VS Code (Copilot agent mode)

English · [Русский](vscode.ru.md)

**Ask:** "Connect to my Minecraft server (Almighty) at http://127.0.0.1:25650/mcp". The agent runs `code --add-mcp`
or edits `mcp.json`.

**Or yourself:** click the VS Code link from `/almighty connect`, or run:

```sh
code --add-mcp '{"name":"almighty","type":"http","url":"http://127.0.0.1:25650/mcp"}'
```

**Sign in:** VS Code asks to authenticate the server; allow it, then type the code from the page:
`/almighty approve <code>`.
