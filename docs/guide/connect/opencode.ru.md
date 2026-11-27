# opencode

[English](opencode.md) · Русский

**Попросите:** «Подключись к моему серверу Minecraft (Almighty) по адресу http://127.0.0.1:25650/mcp». Агент
выполнит `opencode mcp add` или поправит `opencode.json`.

**Или сами:** добавьте в `opencode.json` (проект) или `~/.config/opencode/opencode.json`:

```json
{ "$schema": "https://opencode.ai/config.json", "mcp": { "almighty": { "type": "remote", "url": "http://127.0.0.1:25650/mcp" } } }
```

**Вход:** `opencode mcp auth almighty` открывает страницу с кодом; наберите `/almighty approve <код>` на сервере.
opencode регистрируется сам и подключается как агент `opencode`. Проверено с opencode 1.19.6: вход, затем
`initialize` и `tools/list` с новым токеном.

opencode читает навыки и из `.claude/skills` и `.agents/skills`: скопируйте туда `plugin/almighty/skills/*`, чтобы
получить навыки ведущего.
