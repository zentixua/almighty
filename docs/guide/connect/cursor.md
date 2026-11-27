# Cursor

English · [Русский](cursor.ru.md)

**Ask:** "Connect to my Minecraft server (Almighty) at http://127.0.0.1:25650/mcp". The agent adds it to
`~/.cursor/mcp.json` (or `.cursor/mcp.json` in the project).

**Or yourself:** run `/almighty connect` on the server and click the Cursor link, or add to `~/.cursor/mcp.json`:

```json
{ "mcpServers": { "almighty": { "url": "http://127.0.0.1:25650/mcp" } } }
```

**Sign in:** Cursor shows "Needs login" next to the server; click it, then type the code from the page:
`/almighty approve <code>`. Cursor registers itself (Dynamic Client Registration), which the server supports.
