# Antigravity

English · [Русский](antigravity.ru.md)

**Ask:** "Connect to my Minecraft server (Almighty) at http://127.0.0.1:25650/mcp". The agent edits
`~/.gemini/config/mcp_config.json` (or `<workspace>/.agents/mcp_config.json`).

**Or yourself:** add to that file (the app shows its path under MCP Servers → Copy Config File Path):

```json
{ "mcpServers": { "almighty": { "serverUrl": "http://127.0.0.1:25650/mcp" } } }
```

**Sign in:** Customizations → Installed → MCP Servers → Authenticate next to `almighty`. The browser shows the
server's page with a code; type `/almighty approve <code>` on the server. The browser then lands on Antigravity's
page with an authorization code: paste it into **Enter authorization code** and Submit.

**Plugin (optional):** `agy plugin install https://github.com/zentixua/almighty`.
