# Antigravity

[English](antigravity.md) · Русский

**Попросите:** «Подключись к моему серверу Minecraft (Almighty) по адресу http://127.0.0.1:25650/mcp». Агент
правит `~/.gemini/config/mcp_config.json` (или `<workspace>/.agents/mcp_config.json`).

**Или сами:** добавьте в этот файл (приложение показывает его путь в MCP Servers → Copy Config File Path):

```json
{ "mcpServers": { "almighty": { "serverUrl": "http://127.0.0.1:25650/mcp" } } }
```

**Вход:** Customizations → Installed → MCP Servers → «Authenticate» рядом с `almighty`. Браузер покажет страницу
сервера с кодом; наберите `/almighty approve <код>` на сервере. Затем браузер откроет страницу Antigravity с кодом
авторизации: вставьте его в поле «Enter authorization code» и нажмите «Submit».

**Плагин (по желанию):** `agy plugin install https://github.com/zentixua/almighty`.
