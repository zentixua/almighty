# Cursor

[English](cursor.md) · Русский

**Попросите:** «Подключись к моему серверу Minecraft (Almighty) по адресу http://127.0.0.1:25650/mcp». Агент
добавит его в `~/.cursor/mcp.json` (или в `.cursor/mcp.json` проекта).

**Или сами:** выполните `/almighty connect` на сервере и нажмите ссылку для Cursor или добавьте в `~/.cursor/mcp.json`:

```json
{ "mcpServers": { "almighty": { "url": "http://127.0.0.1:25650/mcp" } } }
```

**Вход:** рядом с сервером Cursor покажет «Needs login»; нажмите, затем наберите код со страницы:
`/almighty approve <код>`. Cursor регистрируется сам (Dynamic Client Registration), и сервер это поддерживает.
